package com.ozyab.smsforwarder.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.CountDownLatch

/**
 * Хранилище настроек.
 *
 * Чувствительные поля (токен бота, пароль прокси) — в EncryptedSharedPreferences.
 * Обычные настройки — в обычных SharedPreferences.
 *
 * Инициализация асинхронная: [init] лишь запускает фоновый поток, в котором
 * создаются MasterKey и EncryptedSharedPreferences (самые медленные операции).
 * Холодный старт приложения не блокируется; любой доступ к настройкам через
 * [awaitReady] дожидается завершения инициализации (один раз, обычно десятки
 * миллисекунд), поэтому чтения/записи безопасны из любого потока.
 */
object Prefs {

    private const val FILE_SECURE = "secure_prefs"
    private const val FILE_PLAIN = "plain_prefs"

    // Ключи (secure)
    const val KEY_BOT_TOKEN = "bot_token"
    const val KEY_PROXY_PASS = "proxy_pass"

    // Ключи (plain)
    const val KEY_CHAT_ID = "chat_id"
    const val KEY_SMS_ENABLED = "sms_enabled"
    const val KEY_CALLS_ENABLED = "calls_enabled"
    const val KEY_SHORT_CODES_FILTER = "short_codes_filter"
    const val KEY_PROXY_ENABLED = "proxy_enabled"
    const val KEY_PROXY_TYPE = "proxy_type" // "http" | "socks5"
    const val KEY_PROXY_HOST = "proxy_host"
    const val KEY_PROXY_PORT = "proxy_port"
    const val KEY_PROXY_USER = "proxy_user"
    const val KEY_SENT_COUNT = "sent_count"
    const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    const val KEY_LAST_UPDATE_CHECK = "last_update_check"

    // Детальные фильтры SMS
    const val KEY_FILTER_MODE = "filter_mode" // "all" | "contacts" | "whitelist"
    const val KEY_SMS_WHITELIST = "sms_whitelist" // число через запятую
    const val KEY_SMS_BLOCK_REGEX = "sms_block_regex" // regex (однострочный)

    // Тема оформления: "system" | "light" | "dark"
    const val KEY_THEME_MODE = "theme_mode"

    // Каналы отправки (JSON в secure prefs)
    const val KEY_CHANNELS_JSON = "channels_json"

    private val initLock = Any()
    private val readyLatch = CountDownLatch(1)
    @Volatile private var initStarted = false
    @Volatile private var initDone = false

    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences

    /**
     * Запускает асинхронную инициализацию (идемпотентно, не блокирует поток).
     * Вызывается из Application.onCreate; далее любой компонент (Activity,
     * Receiver, Service) получает готовые Prefs через [awaitReady].
     */
    fun init(context: Context) {
        synchronized(initLock) {
            if (initStarted) return
            initStarted = true
        }
        val appContext = context.applicationContext
        val t = Thread({
            try {
                doInit(appContext)
            } catch (e: Throwable) {
                LogStore.error("Prefs init failed: ${e.message}")
            } finally {
                initDone = true
                readyLatch.countDown()
            }
        }, "prefs-init")
        t.isDaemon = true
        t.start()
    }

    private fun doInit(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        secure = EncryptedSharedPreferences.create(
            context, FILE_SECURE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        plain = context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
    }

    /**
     * Блокирует вызывающий поток до готовности Prefs; после инициализации —
     * мгновенно. Вызывается в начале каждого аксессора.
     */
    private fun awaitReady() {
        if (initDone) return
        // init() ещё не вызывался — такого быть не должно (Application.onCreate
        // стартует раньше любых компонентов); возвращаемся и падаем честно,
        // а не зависаем навсегда.
        if (!initStarted) return
        try {
            readyLatch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // --- secure ---
    var botToken: String
        get() {
            awaitReady()
            return secure.getString(KEY_BOT_TOKEN, "") ?: ""
        }
        set(v) {
            awaitReady()
            secure.edit().putString(KEY_BOT_TOKEN, v).apply()
        }

    var proxyPass: String
        get() {
            awaitReady()
            return secure.getString(KEY_PROXY_PASS, "") ?: ""
        }
        set(v) {
            awaitReady()
            secure.edit().putString(KEY_PROXY_PASS, v).apply()
        }

    /** JSON-массив каналов отправки (secure). */
    var channelsJson: String
        get() {
            awaitReady()
            return secure.getString(KEY_CHANNELS_JSON, "") ?: ""
        }
        set(v) {
            awaitReady()
            secure.edit().putString(KEY_CHANNELS_JSON, v).apply()
        }

    // --- plain ---
    var chatId: String
        get() {
            awaitReady()
            return plain.getString(KEY_CHAT_ID, "") ?: ""
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_CHAT_ID, v).apply()
        }

    var smsEnabled: Boolean
        get() {
            awaitReady()
            return plain.getBoolean(KEY_SMS_ENABLED, true)
        }
        set(v) {
            awaitReady()
            plain.edit().putBoolean(KEY_SMS_ENABLED, v).apply()
        }

    var callsEnabled: Boolean
        get() {
            awaitReady()
            return plain.getBoolean(KEY_CALLS_ENABLED, true)
        }
        set(v) {
            awaitReady()
            plain.edit().putBoolean(KEY_CALLS_ENABLED, v).apply()
        }

    var shortCodesFilter: Boolean
        get() {
            awaitReady()
            return plain.getBoolean(KEY_SHORT_CODES_FILTER, true)
        }
        set(v) {
            awaitReady()
            plain.edit().putBoolean(KEY_SHORT_CODES_FILTER, v).apply()
        }

    var proxyEnabled: Boolean
        get() {
            awaitReady()
            return plain.getBoolean(KEY_PROXY_ENABLED, false)
        }
        set(v) {
            awaitReady()
            plain.edit().putBoolean(KEY_PROXY_ENABLED, v).apply()
        }

    var proxyType: String
        get() {
            awaitReady()
            return plain.getString(KEY_PROXY_TYPE, "http") ?: "http"
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_PROXY_TYPE, v).apply()
        }

    var proxyHost: String
        get() {
            awaitReady()
            return plain.getString(KEY_PROXY_HOST, "") ?: ""
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_PROXY_HOST, v).apply()
        }

    var proxyPort: Int
        get() {
            awaitReady()
            return plain.getInt(KEY_PROXY_PORT, 0)
        }
        set(v) {
            awaitReady()
            plain.edit().putInt(KEY_PROXY_PORT, v).apply()
        }

    var proxyUser: String
        get() {
            awaitReady()
            return plain.getString(KEY_PROXY_USER, "") ?: ""
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_PROXY_USER, v).apply()
        }

    var sentCount: Int
        get() {
            awaitReady()
            return plain.getInt(KEY_SENT_COUNT, 0)
        }
        set(v) {
            awaitReady()
            plain.edit().putInt(KEY_SENT_COUNT, v).apply()
        }

    /** Прошёл ли пользователь онбординг. */
    var onboardingComplete: Boolean
        get() {
            awaitReady()
            return plain.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        }
        set(v) {
            awaitReady()
            plain.edit().putBoolean(KEY_ONBOARDING_COMPLETE, v).apply()
        }

    // --- Миграция старого одиночного прокси (v0.4.x) в канал ---

    /** Возвращает канал из старых настроек прокси, если они заполнены и включены. */
    fun migrateLegacyProxyToChannel(): com.ozyab.smsforwarder.telegram.Channel? {
        awaitReady()
        if (!proxyEnabled) return null
        if (proxyHost.isBlank() || proxyPort <= 0) return null
        return com.ozyab.smsforwarder.telegram.Channel(
            id = "proxy-legacy",
            type = proxyType,
            name = "Прокси",
            host = proxyHost,
            port = proxyPort,
            user = proxyUser,
            pass = proxyPass,
            enabled = true,
        )
    }

    /** Очистка старых полей одиночного прокси после миграции. */
    fun clearLegacyProxy() {
        awaitReady()
        plain.edit().remove(KEY_PROXY_ENABLED).apply()
        plain.edit().remove(KEY_PROXY_TYPE).apply()
        plain.edit().remove(KEY_PROXY_HOST).apply()
        plain.edit().remove(KEY_PROXY_PORT).apply()
        plain.edit().remove(KEY_PROXY_USER).apply()
        secure.edit().remove(KEY_PROXY_PASS).apply()
    }

    // --- Детальные фильтры SMS ---
    var filterMode: String
        get() {
            awaitReady()
            return plain.getString(KEY_FILTER_MODE, "all") ?: "all"
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_FILTER_MODE, v).apply()
        }

    /** Белый список номеров (через запятую, допускаются шаблоны с *). */
    var smsWhitelist: String
        get() {
            awaitReady()
            return plain.getString(KEY_SMS_WHITELIST, "") ?: ""
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_SMS_WHITELIST, v).apply()
        }

    /** Regex: если совпал — SMS не пересылаем. */
    var smsBlockRegex: String
        get() {
            awaitReady()
            return plain.getString(KEY_SMS_BLOCK_REGEX, "") ?: ""
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_SMS_BLOCK_REGEX, v).apply()
        }

    fun isConfigured(): Boolean {
        awaitReady()
        return botToken.isNotBlank() && chatId.isNotBlank()
    }

    /** Тема оформления: "system" (по системе) | "light" | "dark". */
    var themeMode: String
        get() {
            awaitReady()
            return plain.getString(KEY_THEME_MODE, "system") ?: "system"
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_THEME_MODE, v).apply()
        }

    /** Время последней проверки обновлений (throttle сетевых запросов). */
    var lastUpdateCheck: Long
        get() {
            awaitReady()
            return plain.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        }
        set(v) {
            awaitReady()
            plain.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()
        }

    // --- Шаблоны сообщений ---
    const val KEY_MESSAGE_TEMPLATE_SMS = "message_template_sms"
    const val KEY_MESSAGE_TEMPLATE_CALL = "message_template_call"

    /** Шаблон для SMS (plain). Пусто = дефолтный формат. */
    var messageTemplateSms: String
        get() {
            awaitReady()
            return plain.getString(KEY_MESSAGE_TEMPLATE_SMS, "") ?: ""
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_MESSAGE_TEMPLATE_SMS, v).apply()
        }

    /** Шаблон для пропущенных вызовов (plain). Пусто = дефолтный формат. */
    var messageTemplateCall: String
        get() {
            awaitReady()
            return plain.getString(KEY_MESSAGE_TEMPLATE_CALL, "") ?: ""
        }
        set(v) {
            awaitReady()
            plain.edit().putString(KEY_MESSAGE_TEMPLATE_CALL, v).apply()
        }
}