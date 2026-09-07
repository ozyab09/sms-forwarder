package com.ozyab.smsforwarder.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранилище настроек.
 *
 * Чувствительные поля (токен бота, пароль прокси) — в EncryptedSharedPreferences.
 * Обычные настройки — в обычных SharedPreferences.
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
    const val KEY_PROXY_TYPE = "proxy_type" // "none" | "http" | "socks5" | "mtproto"
    const val KEY_PROXY_HOST = "proxy_host"
    const val KEY_PROXY_PORT = "proxy_port"
    const val KEY_PROXY_USER = "proxy_user"
    const val KEY_SENT_COUNT = "sent_count"
    const val KEY_USE_MTProto = "use_mtproto"

    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences

    fun init(context: Context) {
        if (::secure.isInitialized) return
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

    // --- secure ---
    var botToken: String
        get() = secure.getString(KEY_BOT_TOKEN, "") ?: ""
        set(v) = secure.edit().putString(KEY_BOT_TOKEN, v).apply()

    var proxyPass: String
        get() = secure.getString(KEY_PROXY_PASS, "") ?: ""
        set(v) = secure.edit().putString(KEY_PROXY_PASS, v).apply()

    // --- plain ---
    var chatId: String
        get() = plain.getString(KEY_CHAT_ID, "") ?: ""
        set(v) = plain.edit().putString(KEY_CHAT_ID, v).apply()

    var smsEnabled: Boolean
        get() = plain.getBoolean(KEY_SMS_ENABLED, true)
        set(v) = plain.edit().putBoolean(KEY_SMS_ENABLED, v).apply()

    var callsEnabled: Boolean
        get() = plain.getBoolean(KEY_CALLS_ENABLED, true)
        set(v) = plain.edit().putBoolean(KEY_CALLS_ENABLED, v).apply()

    var shortCodesFilter: Boolean
        get() = plain.getBoolean(KEY_SHORT_CODES_FILTER, true)
        set(v) = plain.edit().putBoolean(KEY_SHORT_CODES_FILTER, v).apply()

    var proxyEnabled: Boolean
        get() = plain.getBoolean(KEY_PROXY_ENABLED, false)
        set(v) = plain.edit().putBoolean(KEY_PROXY_ENABLED, v).apply()

    var proxyType: String
        get() = plain.getString(KEY_PROXY_TYPE, "none") ?: "none"
        set(v) = plain.edit().putString(KEY_PROXY_TYPE, v).apply()

    var proxyHost: String
        get() = plain.getString(KEY_PROXY_HOST, "") ?: ""
        set(v) = plain.edit().putString(KEY_PROXY_HOST, v).apply()

    var proxyPort: Int
        get() = plain.getInt(KEY_PROXY_PORT, 0)
        set(v) = plain.edit().putInt(KEY_PROXY_PORT, v).apply()

    var proxyUser: String
        get() = plain.getString(KEY_PROXY_USER, "") ?: ""
        set(v) = plain.edit().putString(KEY_PROXY_USER, v).apply()

    var sentCount: Int
        get() = plain.getInt(KEY_SENT_COUNT, 0)
        set(v) = plain.edit().putInt(KEY_SENT_COUNT, v).apply()

    /** Режим отправки: true = MTProto (TDLib) основной + Bot API fallback; false = только Bot API. */
    var useMtproto: Boolean
        get() = plain.getBoolean(KEY_USE_MTProto, true)
        set(v) = plain.edit().putBoolean(KEY_USE_MTProto, v).apply()

    fun isConfigured(): Boolean = botToken.isNotBlank() && chatId.isNotBlank()

    /** Прокси настроен полностью? */
    fun isProxyComplete(): Boolean {
        if (!proxyEnabled || proxyType == "none") return true
        return proxyHost.isNotBlank() && proxyPort > 0
    }
}