package com.ozyab.smsforwarder.util

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Хранилище настроек.
 *
 * Два уровня, privacy-first:
 * - **plain**: Preference DataStore (async, атомарные правки, Flow-наблюдение).
 *   При первом запуске мигрируются старые SharedPreferences «plain_prefs».
 * - **secure**: EncryptedSharedPreferences (AES256-GCM) — только секреты
 *   (токен бота, пароль прокси, каналы). В DataStore их не кладём: он
 *   не шифрованный.
 *
 * API синхронный (как и раньше), чтобы не переписывать UI: аксессоры читают
 * in-memory кэш (первая DataStore-эмиссия), а записи идут в кэш сразу
 * (optimistic) и асинхронно — в DataStore. Цена: чтение всегда мгновенное,
 * без блокировок главного потока.
 *
 * Инициализация асинхронная: [init] лишь запускает фоновый поток (secure)
 * и корутину (DataStore); любой доступ через [awaitReady] дожидается готовности
 * обоих (обычно десятки миллисекунд).
 */
object Prefs {

    private const val FILE_SECURE = "secure_prefs" // НЕ менять: существующие данные
    private const val FILE_PLAIN = "plain_prefs"

    // Ключи (secure)
    const val KEY_BOT_TOKEN = "bot_token"
    const val KEY_PROXY_PASS = "proxy_pass"

    // Ключи (plain)
    const val KEY_CHAT_ID = "chat_id"
    const val KEY_SMS_ENABLED = "sms_enabled"
    const val KEY_CALLS_ENABLED = "calls_enabled"
    const val KEY_PROXY_ENABLED = "proxy_enabled"
    const val KEY_PROXY_TYPE = "proxy_type" // "http" | "socks5"
    const val KEY_PROXY_HOST = "proxy_host"
    const val KEY_PROXY_PORT = "proxy_port"
    const val KEY_PROXY_USER = "proxy_user"
    const val KEY_SENT_COUNT = "sent_count"
    const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    const val KEY_LAST_UPDATE_CHECK = "last_update_check"

    // Тема оформления: "system" | "light" | "dark"
    const val KEY_THEME_MODE = "theme_mode"

    // Каналы отправки (JSON в secure prefs)
    const val KEY_CHANNELS_JSON = "channels_json"

    // Шаблоны сообщений (plain)
    const val KEY_MESSAGE_TEMPLATE_SMS = "message_template_sms"
    const val KEY_MESSAGE_TEMPLATE_CALL = "message_template_call"

    private val initLock = Any()
    // Два счётчика: secure-инициализация + первая DataStore-эмиссия.
    private val readyLatch = CountDownLatch(2)
    @Volatile private var initStarted = false
    @Volatile private var initDone = false

    private lateinit var secure: SharedPreferences

    /** DataStore для plain-настроек (создаётся в [init]). */
    private lateinit var plainStore: DataStore<Preferences>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-memory кэш plain-настроек (актуальный снимок DataStore). */
    @Volatile private lateinit var cache: Preferences

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

        // Готовность наступает только когда ОБА уровня инициализированы:
        // secure (EncryptedSharedPreferences) и первая эмиссия DataStore.
        fun markReady() {
            readyLatch.countDown()
            if (readyLatch.count == 0L) initDone = true
        }

        // 1) Secure: MasterKey + EncryptedSharedPreferences (как раньше).
        val t = Thread({
            try {
                doInitSecure(appContext)
            } catch (e: Throwable) {
                LogStore.error("Prefs secure init failed: ${e.message}")
            } finally {
                markReady()
            }
        }, "prefs-init-secure")
        t.isDaemon = true
        t.start()

        // 2) Plain: DataStore c миграцией из старых SharedPreferences.
        // Первая эмиссия загружает файл (и миграцию), после неё кэш готов.
        // ВАЖНО: markReady() вызывается при ПЕРВОЙ эмиссии (collect на DataStore
        // бесконечен и finally не выполнится при здоровом потоке) и в finally
        // как страховка на случай падения/отмены до первой эмиссии.
        plainStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(FILE_PLAIN) },
            migrations = listOf(SharedPreferencesMigration(appContext, FILE_PLAIN)),
        )
        scope.launch {
            try {
                plainStore.data
                    .catch { e -> LogStore.error("DataStore read failed: ${e.message}") }
                    .collect { prefs ->
                        cache = prefs
                        markReady()
                    }
            } catch (e: Throwable) {
                LogStore.error("DataStore init failed: ${e.message}")
            } finally {
                markReady()
            }
        }
    }

    private fun doInitSecure(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        secure = EncryptedSharedPreferences.create(
            context, FILE_SECURE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Блокирует вызывающий поток до готовности Prefs; после инициализации —
     * мгновенно. Вызывается в начале каждого аксессора.
     *
     * Страховочный таймаут: даже если что-то пошло не так (DataStore не
     * эмитит, secure упал), главный поток не должен висеть — старт приложения
     * важнее идеальной готовности настроек. Возвращаемся с тем, что есть
     * (кэш может быть пустым — аксессоры вернут дефолты).
     */
    private fun awaitReady() {
        if (initDone) return
        // init() ещё не вызывался — такого быть не должно (Application.onCreate
        // стартует раньше любых компонентов); возвращаемся и падаем честно,
        // а не зависаем навсегда.
        if (!initStarted) return
        try {
            if (!readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                LogStore.warn("Prefs init timeout — использую дефолтные значения")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // --- secure (EncryptedSharedPreferences) ---

    var botToken: String
        get() {
            awaitReady()
            return if (::secure.isInitialized) secure.getString(KEY_BOT_TOKEN, "") ?: "" else ""
        }
        set(v) {
            awaitReady()
            if (::secure.isInitialized) secure.edit().putString(KEY_BOT_TOKEN, v).apply()
        }

    var proxyPass: String
        get() {
            awaitReady()
            return if (::secure.isInitialized) secure.getString(KEY_PROXY_PASS, "") ?: "" else ""
        }
        set(v) {
            awaitReady()
            if (::secure.isInitialized) secure.edit().putString(KEY_PROXY_PASS, v).apply()
        }

    /** JSON-массив каналов отправки (secure). */
    var channelsJson: String
        get() {
            awaitReady()
            return if (::secure.isInitialized) secure.getString(KEY_CHANNELS_JSON, "") ?: "" else ""
        }
        set(v) {
            awaitReady()
            if (::secure.isInitialized) secure.edit().putString(KEY_CHANNELS_JSON, v).apply()
        }

    // --- plain (DataStore, через кэш) ---

    // ВАЖНО: имена ключей совпадают со старыми SharedPreferences,
    // поэтому миграция переносит значения автоматически.

    var chatId: String
        get() = getString(KEY_CHAT_ID, "")
        set(v) = setString(KEY_CHAT_ID, v)

    var smsEnabled: Boolean
        get() = getBoolean(KEY_SMS_ENABLED, true)
        set(v) = setBoolean(KEY_SMS_ENABLED, v)

    var callsEnabled: Boolean
        get() = getBoolean(KEY_CALLS_ENABLED, true)
        set(v) = setBoolean(KEY_CALLS_ENABLED, v)

    var proxyEnabled: Boolean
        get() = getBoolean(KEY_PROXY_ENABLED, false)
        set(v) = setBoolean(KEY_PROXY_ENABLED, v)

    var proxyType: String
        get() = getString(KEY_PROXY_TYPE, "http")
        set(v) = setString(KEY_PROXY_TYPE, v)

    var proxyHost: String
        get() = getString(KEY_PROXY_HOST, "")
        set(v) = setString(KEY_PROXY_HOST, v)

    var proxyPort: Int
        get() = getInt(KEY_PROXY_PORT, 0)
        set(v) = setInt(KEY_PROXY_PORT, v)

    var proxyUser: String
        get() = getString(KEY_PROXY_USER, "")
        set(v) = setString(KEY_PROXY_USER, v)

    var sentCount: Int
        get() = getInt(KEY_SENT_COUNT, 0)
        set(v) = setInt(KEY_SENT_COUNT, v)

    /** Прошёл ли пользователь онбординг. */
    var onboardingComplete: Boolean
        get() = getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(v) = setBoolean(KEY_ONBOARDING_COMPLETE, v)

    /** Тема оформления: "system" (по системе) | "light" | "dark". */
    var themeMode: String
        get() = getString(KEY_THEME_MODE, "system")
        set(v) = setString(KEY_THEME_MODE, v)

    /** Время последней проверки обновлений (throttle сетевых запросов). */
    var lastUpdateCheck: Long
        get() = getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = setLong(KEY_LAST_UPDATE_CHECK, v)

    /** Шаблон для SMS (plain). Пусто = дефолтный формат. */
    var messageTemplateSms: String
        get() = getString(KEY_MESSAGE_TEMPLATE_SMS, "")
        set(v) = setString(KEY_MESSAGE_TEMPLATE_SMS, v)

    /** Шаблон для пропущенных вызовов (plain). Пусто = дефолтный формат. */
    var messageTemplateCall: String
        get() = getString(KEY_MESSAGE_TEMPLATE_CALL, "")
        set(v) = setString(KEY_MESSAGE_TEMPLATE_CALL, v)

    fun isConfigured(): Boolean {
        awaitReady()
        return botToken.isNotBlank() && chatId.isNotBlank()
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
        scope.launch {
            runCatching {
                plainStore.edit { p ->
                    p.remove(booleanPreferencesKey(KEY_PROXY_ENABLED))
                    p.remove(stringPreferencesKey(KEY_PROXY_TYPE))
                    p.remove(stringPreferencesKey(KEY_PROXY_HOST))
                    p.remove(intPreferencesKey(KEY_PROXY_PORT))
                    p.remove(stringPreferencesKey(KEY_PROXY_USER))
                }
            }.onFailure { e -> LogStore.error("clearLegacyProxy failed: ${e.message}") }
        }
        secure.edit().remove(KEY_PROXY_PASS).apply()
        // Сразу отражаем в кэше, чтобы чтения после вызова вернули дефолты.
        if (::cache.isInitialized) {
            val mut = cache.toMutablePreferences()
            mut.remove(booleanPreferencesKey(KEY_PROXY_ENABLED))
            mut.remove(stringPreferencesKey(KEY_PROXY_TYPE))
            mut.remove(stringPreferencesKey(KEY_PROXY_HOST))
            mut.remove(intPreferencesKey(KEY_PROXY_PORT))
            mut.remove(stringPreferencesKey(KEY_PROXY_USER))
            cache = mut.toPreferences()
        }
    }

    // --- Кэш-хелперы (plain) ---

    private fun getString(key: String, default: String): String {
        awaitReady()
        return if (::cache.isInitialized) cache[stringPreferencesKey(key)] ?: default else default
    }

    private fun getBoolean(key: String, default: Boolean): Boolean {
        awaitReady()
        return if (::cache.isInitialized) cache[booleanPreferencesKey(key)] ?: default else default
    }

    private fun getInt(key: String, default: Int): Int {
        awaitReady()
        return if (::cache.isInitialized) cache[intPreferencesKey(key)] ?: default else default
    }

    private fun getLong(key: String, default: Long): Long {
        awaitReady()
        return if (::cache.isInitialized) cache[longPreferencesKey(key)] ?: default else default
    }

    private fun setString(key: String, value: String) {
        awaitReady()
        val k = stringPreferencesKey(key)
        if (::cache.isInitialized) updateCache(k, value)
        scope.launch {
            runCatching { plainStore.edit { it[k] = value } }
                .onFailure { e -> LogStore.error("DataStore write $key failed: ${e.message}") }
        }
    }

    private fun setBoolean(key: String, value: Boolean) {
        awaitReady()
        val k = booleanPreferencesKey(key)
        if (::cache.isInitialized) updateCache(k, value)
        scope.launch {
            runCatching { plainStore.edit { it[k] = value } }
                .onFailure { e -> LogStore.error("DataStore write $key failed: ${e.message}") }
        }
    }

    private fun setInt(key: String, value: Int) {
        awaitReady()
        val k = intPreferencesKey(key)
        if (::cache.isInitialized) updateCache(k, value)
        scope.launch {
            runCatching { plainStore.edit { it[k] = value } }
                .onFailure { e -> LogStore.error("DataStore write $key failed: ${e.message}") }
        }
    }

    private fun setLong(key: String, value: Long) {
        awaitReady()
        val k = longPreferencesKey(key)
        if (::cache.isInitialized) updateCache(k, value)
        scope.launch {
            runCatching { plainStore.edit { it[k] = value } }
                .onFailure { e -> LogStore.error("DataStore write $key failed: ${e.message}") }
        }
    }

    /** Optimistic-обновление кэша: чтение сразу видит новое значение. */
    private fun <T> updateCache(key: Preferences.Key<T>, value: T) {
        val mut = cache.toMutablePreferences()
        mut[key] = value
        cache = mut.toPreferences()
    }
}