package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject

/**
 * Каскадная отправка сообщения в Telegram через каналы.
 *
 * Пробует каналы ПО ПОРЯДКУ (direct первый, затем прокси в порядке списка).
 * Первый успешный канал — победа. Все не вышли — агрегированная ошибка.
 *
 * Для тестов функция отправки [sender] инжектится (см. [realSender]).
 */
object ChannelSender {

    sealed class Result {
        data class Ok(val channelName: String, val messageId: Long) : Result()
        data class Err(val reasons: List<String>) : Result()
    }

    sealed class ChannelOutcome {
        data class Sent(val messageId: Long) : ChannelOutcome()
        data class Failed(val reason: String) : ChannelOutcome()
    }

    /**
     * Общий бюджет времени на весь каскад каналов. Каждый канал ограничен
     * callTimeout (30с) внутри ChannelClientFactory; этот лимит — верхняя граница
     * суммы попыток (защита от N×30с при большом числе прокси).
     */
    private const val CASCADE_TIMEOUT_MS = 120_000L

    /**
     * Отправка текста через каналы (каскад: первый успешный — победа).
     *
     * @param channels каналы в порядке приоритета (уже отфильтрованы enabled).
     * @param sender функция отправки через один канал (по умолчанию — Bot API).
     * @param onSuccess колбэк, вызываемый при успешной отправке каналом
     *   (имя канала). Используется для promote-on-success в сервисе.
     * @param onFailure колбэк, вызываемый при неуспешной попытке через канал.
     *   Используется для demote-on-failure в сервисе (канал уходит в конец
     *   списка, переключатель не меняется).
     */
    suspend fun send(
        text: String,
        token: String,
        chatId: String,
        channels: List<Channel>,
        onSuccess: (Channel) -> Unit = {},
        onFailure: (Channel) -> Unit = {},
        onRateLimit: (retryAfterSec: Long) -> Unit = {},
        sender: suspend (Channel) -> ChannelOutcome = { realSender(text, token, chatId, it, onRateLimit) },
    ): Result = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext Result.Err(listOf("Нет включённых каналов"))
        sendCascade(text, token, chatId, channels, onSuccess, onFailure, sender)
    }

    /** Каскадная отправка: первый успешный канал — победа. */
    private suspend fun sendCascade(
        text: String,
        token: String,
        chatId: String,
        channels: List<Channel>,
        onSuccess: (Channel) -> Unit,
        onFailure: (Channel) -> Unit,
        sender: suspend (Channel) -> ChannelOutcome,
    ): Result {
        val failures = mutableListOf<String>()
        var sent: Result.Ok? = null
        try {
            withTimeout(CASCADE_TIMEOUT_MS) {
                for (ch in channels) {
                    when (val out = sender(ch)) {
                        is ChannelOutcome.Sent -> {
                            LogStore.info("Отправка через «${ch.name}» — успех")
                            sent = Result.Ok(ch.name, out.messageId)
                            onSuccess(ch)
                            break
                        }
                        is ChannelOutcome.Failed -> {
                            failures += "«${ch.name}»: ${out.reason}"
                            LogStore.warn("Ошибка через «${ch.name}»: ${out.reason}")
                            onFailure(ch)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            failures += "Общий таймаут каскада (${CASCADE_TIMEOUT_MS / 1000}с)"
            LogStore.warn("Каскад прерван по общему таймауту")
        }
        sent?.let { return it }
        return Result.Err(failures)
    }

    /** Результат теста подключения одного канала (через getMe). */
    data class ChannelTestResult(
        val channel: Channel,
        val ok: Boolean,
        val botUsername: String? = null,
        val error: String? = null,
    )

    /** Результат проверки getMe через один канал. */
    sealed class ChannelTestOutcome {
        data class Ok(val botUsername: String) : ChannelTestOutcome()
        data class Failed(val reason: String) : ChannelTestOutcome()
    }

    /** Потолок одновременных проверок: каждый канал поднимает свой OkHttp-клиент
     *  с thread-pool, поэтому при десятках каналов ограничиваем параллельность. */
    private const val MAX_PARALLEL_TESTS = 4

    /**
     * Параллельная проверка подключения ко ВСЕМ каналам (для кнопки «Проверить связь»).
     *
     * Через каждый канал запрашивается getMe (информация о боте) — сообщение
     * пользователю НЕ отправляется. Результат собирается по каждому каналу
     * отдельно (не останавливаемся на первом успешном). Каждый канал ограничен
     * callTimeout (30с) внутри [ChannelClientFactory], поэтому общее время
     * ≈ худшему каналу, а не сумме.
     *
     * @return результаты в том же порядке, что и [channels].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testAll(
        token: String,
        channels: List<Channel>,
        tester: suspend (Channel) -> ChannelTestOutcome = { realGetMe(token, it) },
    ): List<ChannelTestResult> = withContext(Dispatchers.IO) {
        val limiter = Dispatchers.IO.limitedParallelism(MAX_PARALLEL_TESTS)
        coroutineScope {
            channels.map { ch ->
                async(limiter) {
                    when (val r = tester(ch)) {
                        is ChannelTestOutcome.Ok -> ChannelTestResult(ch, true, botUsername = r.botUsername)
                        is ChannelTestOutcome.Failed -> ChannelTestResult(ch, false, error = r.reason)
                    }
                }
            }.awaitAll()
        }
    }

    /** Реальная проверка getMe через Bot API по одному каналу (без отправки сообщений). */
    private suspend fun realGetMe(
        token: String,
        channel: Channel,
    ): ChannelTestOutcome {
        val (client, buildErr) = ChannelClientFactory.build(channel)
        if (buildErr != null) return ChannelTestOutcome.Failed(buildErr)
        return try {
            val req = Request.Builder()
                .url("${ChannelClientFactory.apiBase}/bot$token/getMe")
                .build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (resp.isSuccessful && json.optBoolean("ok", false)) {
                    val username = json.optJSONObject("result")?.optString("username", "")
                    if (username.isNullOrBlank()) {
                        ChannelTestOutcome.Failed("getMe: пустой username")
                    } else {
                        ChannelTestOutcome.Ok(username)
                    }
                } else {
                    ChannelTestOutcome.Failed(json.optString("description", "HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            ChannelTestOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Реальная отправка через Bot API по одному каналу. */
    private suspend fun realSender(
        text: String,
        token: String,
        chatId: String,
        channel: Channel,
        onRateLimit: (retryAfterSec: Long) -> Unit = {},
    ): ChannelOutcome {
        val (client, buildErr) = ChannelClientFactory.build(channel)
        if (buildErr != null) return ChannelOutcome.Failed(buildErr)
        // N4 (аудит-2): Telegram режет сообщения >4096 символов ошибкой 400.
        // Длинный SMS/шаблон раньше ретраился 8 раз и отбрасывался — теперь
        // нарезаем на части по границам строк и шлём последовательно.
        val parts = splitForTelegram(text)
        var firstMessageId = 0L
        for ((idx, part) in parts.withIndex()) {
            val outcome = sendSingle(part, token, chatId, channel, onRateLimit)
            if (outcome is ChannelOutcome.Failed) return outcome
            if (idx == 0 && outcome is ChannelOutcome.Sent) firstMessageId = outcome.messageId
            if (parts.size > 1 && idx < parts.size - 1) {
                // небольшая пауза между частями — не триггерим флуд-лимит
                delay(RATE_LIMIT_PAUSE_MS)
            }
        }
        // messageId первой части (у истории событий он только информационный)
        return ChannelOutcome.Sent(firstMessageId)
    }

    /** Пауза между частями длинного сообщения, мс. */
    private const val RATE_LIMIT_PAUSE_MS = 300L

    /** Лимит Telegram на одно сообщение. */
    private const val TELEGRAM_MAX_LENGTH = 4096

    /**
     * Нарезает текст на части <= [TELEGRAM_MAX_LENGTH] по границам строк
     * (если строка сама длиннее лимита — жёстко по символам).
     */
    internal fun splitForTelegram(text: String, max: Int = TELEGRAM_MAX_LENGTH): List<String> {
        if (text.length <= max) return listOf(text)
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            // Ищем ближайший перенос строки не дальше лимита
            val cut = rest.lastIndexOf('\n', max - 1)
            val chunkEnd = if (cut > 0) cut + 1 else max
            parts += rest.substring(0, chunkEnd)
            rest = rest.substring(chunkEnd)
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /** Одна sendMessage-операция (после нарезки). */
    private suspend fun sendSingle(
        text: String,
        token: String,
        chatId: String,
        channel: Channel,
        onRateLimit: (retryAfterSec: Long) -> Unit,
    ): ChannelOutcome {
        val (client, buildErr) = ChannelClientFactory.build(channel)
        if (buildErr != null) return ChannelOutcome.Failed(buildErr)
        return try {
            val body = okhttp3.FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("disable_notification", "true")
                .build()
            val req = Request.Builder()
                .url("${ChannelClientFactory.apiBase}/bot$token/sendMessage")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (resp.isSuccessful && json.optBoolean("ok", false)) {
                    val mid = json.getJSONObject("result").optLong("message_id")
                    ChannelOutcome.Sent(mid)
                } else {
                    val desc = json.optString("description", "HTTP ${resp.code}")
                    // Флуд-лимит Telegram (429): выделяем retry_after явно —
                    // healthy канал не должен demote'иться, а ретрай должен
                    // учитывать рекомендованную паузу.
                    if (resp.code == 429) {
                        val retryAfter = json.optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L
                        LogStore.warn("Флуд-лимит Telegram: retry_after=${retryAfter}с («${channel.name}»)")
                        // Колбэк вместо глобальной переменной: стейт 429 принадлежит
                        // конкретной отправке, а не процессу (#139)
                        onRateLimit(retryAfter)
                        return ChannelOutcome.Failed("Флуд-лимит, повтор через ${retryAfter}с")
                    }
                    ChannelOutcome.Failed(desc)
                }
            }
        } catch (e: Exception) {
            ChannelOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
