package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.SmsForwarderApp
import com.ozyab.smsforwarder.util.Prefs
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обёртка над TDLib (MTProto) для отправки сообщений ботом.
 *
 * - Авторизация по bot-токену (CheckAuthenticationBotToken), без телефона.
 * - Поддержка прокси: HTTP / SOCKS5 / MTProto-proxy (AddProxy + EnableProxy).
 * - Hебольшая файловая БД для сессии (filesDir/tdlib) — пере-авторизация не нужна.
 * - Инициализация ленивая, идемпотентная; вызывать из фоновых потоков.
 *
 * Никаких секретов в логах: ошибки возвращаются текстом без токена.
 */
object TdClient {

    // Публичные demo-credentials TDLib (требуются для SetTdlibParameters; не секрет).
    private const val DEFAULT_API_ID = 94575
    private const val DEFAULT_API_HASH = "a3406de8d171a422f79f2ff8d8e15a4a"

    private const val TIMEOUT_MS = 30_000L

    private val ready = AtomicBoolean(false)
    @Volatile private var client: Client? = null
    @Volatile private var initError: String? = null

    /** Результат отправки — совместим с TelegramClient.Result. */
    typealias Result = TelegramClient.Result

    /**
     * Гарантирует создание клиента и авторизацию.
     * Возвращает текст ошибки или null при успехе.
     */
    private fun ensureInit(): String? {
        if (client != null) return initError

        synchronized(this) {
            if (client != null) return initError

            val app = SmsForwarderApp.instance
            val dir = File(app.filesDir, "tdlib").apply { mkdirs() }
            val authWait = CompletableFuture<TdApi.AuthorizationState>()

            val handler = Client.ResultHandler { update ->
                if (update is TdApi.UpdateAuthorizationState) {
                    when (val state = update.authorizationState) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> send(
                            TdApi.SetTdlibParameters().apply {
                                useTestDc = false
                                databaseDirectory = dir.absolutePath
                                filesDirectory = dir.absolutePath
                                useFileDatabase = true
                                useChatInfoDatabase = false
                                useMessageDatabase = false
                                useSecretChats = false
                                apiId = DEFAULT_API_ID
                                apiHash = DEFAULT_API_HASH
                                systemLanguageCode = "en"
                                deviceModel = "Android"
                                systemVersion = "1.0"
                                applicationVersion = "1.0"
                            },
                            onError = { initError = it }
                        )
                        is TdApi.AuthorizationStateWaitPhoneNumber -> {
                            val token = Prefs.botToken
                            if (token.isNotBlank()) {
                                send(
                                    TdApi.CheckAuthenticationBotToken(token),
                                    onError = { initError = it }
                                )
                            } else {
                                initError = "TDLib: токен бота не задан"
                                authWait.complete(state)
                            }
                        }
                        is TdApi.AuthorizationStateReady -> authWait.complete(state)
                        is TdApi.AuthorizationStateWaitCode ->
                            initError = "TDLib: запрошен SMS-код (боту не нужен)"
                        is TdApi.AuthorizationStateWaitPassword ->
                            initError = "TDLib: запрошен пароль (боту не нужен)"
                        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                            initError = "TDLib: требуется подтверждение с другого устройства"
                        is TdApi.AuthorizationStateWaitRegistration ->
                            initError = "TDLib: требуется регистрация"
                        is TdApi.AuthorizationStateWaitEmailAddress,
                        is TdApi.AuthorizationStateWaitEmailCode ->
                            initError = "TDLib: требуется email"
                        is TdApi.AuthorizationStateClosed ->
                            if (!ready.get()) initError = "TDLib: клиент закрыт"
                        is TdApi.AuthorizationStateClosing,
                        is TdApi.AuthorizationStateLoggingOut -> Unit
                    }
                }
            }

            client = Client.create(handler, ::onException, ::onException)

            // Прокси применяем сразу (до авторизации — стандартный паттерн TDLib).
            applyProxy()

            try {
                authWait.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ready.set(true)
                initError = null
            } catch (e: Exception) {
                initError = initError ?: "TDLib: таймаут инициализации"
            }
            return initError
        }
    }

    /** Отправка текста в чат (числовой user-id или @username). */
    fun sendText(chatIdOrUsername: String, text: String): Result {
        val err = ensureInit()
        if (err != null) return Result.Err(err)

        val c = client ?: return Result.Err("TDLib: клиент не инициализирован")
        val chatId = resolveChatId(c, chatIdOrUsername)
            ?: return Result.Err("TDLib: не удалось найти чат $chatIdOrUsername")

        val f = CompletableFuture<TdApi.Object>()
        val send = TdApi.SendMessage().apply {
            this.chatId = chatId
            inputMessageContent = TdApi.InputMessageText(
                TdApi.FormattedText(text, null), null, false
            )
        }
        c.send(send, { res ->
            when (res) {
                is TdApi.Message -> f.complete(res)
                else -> f.complete(res)
            }
        }, ::onException)

        return try {
            when (val r = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                is TdApi.Message -> Result.Ok(r.id)
                is TdApi.Error -> Result.Err("TDLib: ${r.message}")
                else -> Result.Err("TDLib: неожиданный ответ")
            }
        } catch (e: Exception) {
            Result.Err("TDLib: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Числовой user-id -> CreatePrivateChat; @username -> SearchPublicChat. */
    private fun resolveChatId(c: Client, target: String): Long? {
        val t = target.trim()
        if (t.isEmpty()) return null
        return if (t.startsWith("@")) {
            val f = CompletableFuture<TdApi.Object>()
            c.send(TdApi.SearchPublicChat(t.removePrefix("@")), { r -> f.complete(r) }, ::onException)
            (try { f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }) as? TdApi.Chat
        }?.let { it.id } ?: run {
            val userId = t.toLongOrNull() ?: return null
            val f = CompletableFuture<TdApi.Object>()
            c.send(TdApi.CreatePrivateChat(userId, true), { r -> f.complete(r) }, ::onException)
            (try { f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }) as? TdApi.Chat
        }?.id
    }

    /** Добавление и включение прокси (HTTP / SOCKS5 / MTProto), если настроен. */
    private fun applyProxy() {
        val c = client ?: return
        if (!Prefs.proxyEnabled) return

        val s = ProxyConfig.current()
        if (s.host.isBlank() || s.port <= 0) return

        val type: TdApi.ProxyType? = when (s.type) {
            "http" -> TdApi.ProxyTypeHttp(s.user, s.pass, false)
            "socks5" -> TdApi.ProxyTypeSocks5(s.user, s.pass)
            // Для MTProto-proxy поле «пароль» = секрет прокси (из ссылки t.me/proxy?secret=...)
            "mtproto" -> TdApi.ProxyTypeMtproto(s.pass.ifBlank { s.user })
            else -> null
        } ?: return

        c.send(
            TdApi.AddProxy(TdApi.Proxy(s.host, s.port, type), true, "sms-forwarder"),
            { res ->
                if (res is TdApi.AddedProxy) {
                    c.send(TdApi.EnableProxy(res.id), { }, ::onException)
                }
            },
            ::onException
        )
    }

    private fun onException(e: Throwable) {
        if (e is Client.ExecutionException) {
            initError = "TDLib: ${e.message}"
        }
        // Остальные исключения игнорируем — результат придёт через ResultHandler.
    }

    /** Универсальный send с обработкой TdApi.Error. */
    private fun send(
        fn: TdApi.Function<*>,
        onError: (String) -> Unit = {},
        onOk: (TdApi.Object) -> Unit = {},
    ) {
        client?.send(fn, { res ->
            when (res) {
                is TdApi.Error -> onError(res.message)
                else -> onOk(res)
            }
        }, ::onException)
    }
}