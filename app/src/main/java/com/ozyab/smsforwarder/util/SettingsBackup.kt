package com.ozyab.smsforwarder.util

import android.content.Context
import android.net.Uri
import com.ozyab.smsforwarder.telegram.ChannelStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт/импорт настроек в JSON-файл (privacy-first).
 *
 * В файл НЕ попадают секреты: токен бота и пароли прокси. При импорте
 * они запрашиваются заново (или остаются текущие).
 *
 * Формат:
 * {
 *   "version": 1,
 *   "exportedAt": "2026-09-12T03:30:00Z",
 *   "app": "sms-forwarder",
 *   "settings": { "chatId": ..., "smsEnabled": ..., ... },
 *   "channels": [ { "name": ..., "type": ..., "host": ..., "port": ..., "user": ..., "enabled": ... }, ... ]
 * }
 */
object SettingsBackup {

    const val FORMAT_VERSION = 1

    /** Дефолтное имя файла: sms-forwarder-backup-YYYY-MM-DD.json */
    fun defaultFileName(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "sms-forwarder-backup-$ts.json"
    }

    /**
     * Собирает JSON настроек. Секреты (токен, пароли) НЕ включаются.
     * Каналы экспортируются без id (при импорте генерируются новые — direct не трогаем)
     * и без pass.
     */
    fun export(): JSONObject {
        val settings = JSONObject()
            .put("chatId", Prefs.chatId)
            .put("smsEnabled", Prefs.smsEnabled)
            .put("callsEnabled", Prefs.callsEnabled)
            .put("themeMode", Prefs.themeMode)
            .put("messageTemplateSms", Prefs.messageTemplateSms)
            .put("messageTemplateCall", Prefs.messageTemplateCall)

        // Каналы: не-direct, без секретов (pass) и без id (при импорте новые)
        val channels = JSONArray()
        ChannelStore.all()
            .filter { !it.isDirect }
            .forEach { ch ->
                channels.put(
                    JSONObject()
                        .put("type", ch.type)
                        .put("name", ch.name)
                        .put("host", ch.host)
                        .put("port", ch.port)
                        .put("user", ch.user)
                        .put("enabled", ch.enabled)
                )
            }

        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("app", "sms-forwarder")
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            .put("settings", settings)
            .put("channels", channels)
    }

    /**
     * Применяет настройки из JSON. Возвращает описание, что импортировано
     * (для тоста), или бросает IllegalArgumentException при неверном формате.
     *
     * Токен бота и пароли прокси НЕ трогаются (остаются текущие) — секреты
     * не хранятся в файле.
     */
    @Throws(IllegalArgumentException::class)
    fun import(json: JSONObject): ImportResult {
        if (json.optString("app") != "sms-forwarder") {
            throw IllegalArgumentException("Не похоже на файл настроек SMS Forwarder")
        }
        if (json.optInt("version", 0) > FORMAT_VERSION) {
            throw IllegalArgumentException("Файл создан более новой версией приложения")
        }
        val settings = json.optJSONObject("settings") ?: throw IllegalArgumentException("Нет секции settings")

        Prefs.chatId = settings.optString("chatId", Prefs.chatId)
        Prefs.smsEnabled = settings.optBoolean("smsEnabled", Prefs.smsEnabled)
        Prefs.callsEnabled = settings.optBoolean("callsEnabled", Prefs.callsEnabled)
        Prefs.themeMode = settings.optString("themeMode", Prefs.themeMode)
        Prefs.messageTemplateSms = settings.optString("messageTemplateSms", Prefs.messageTemplateSms)
        Prefs.messageTemplateCall = settings.optString("messageTemplateCall", Prefs.messageTemplateCall)

        // Каналы: заменяем все прокси-каналы (direct остаётся всегда первым).
        val arr = json.optJSONArray("channels")
        if (arr != null) {
            val proxies = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = o.optString("type", "http")
                    if (type == "direct") continue
                    add(
                        com.ozyab.smsforwarder.telegram.Channel(
                            id = java.util.UUID.randomUUID().toString(),
                            type = type,
                            name = o.optString("name", "Прокси"),
                            host = o.optString("host", ""),
                            port = o.optInt("port", 0),
                            user = o.optString("user", ""),
                            pass = "", // секрет не хранится — вводится заново
                            enabled = o.optBoolean("enabled", true),
                        )
                    )
                }
            }
            ChannelStore.setAll(listOf(ChannelStore.all().first()) + proxies)
            ChannelStore.invalidate()
        }

        return ImportResult(
            channelsImported = arr?.length() ?: 0,
            tokenKept = Prefs.botToken.isNotBlank(),
        )
    }

    /** Результат импорта для UI. */
    data class ImportResult(
        val channelsImported: Int,
        val tokenKept: Boolean,
    )

    // --- SAF: запись/чтение ---

    /** Пишет [content] в [uri] через ContentResolver (SAF). */
    fun write(context: Context, uri: Uri, content: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    }.getOrDefault(false)

    /** Читает содержимое [uri] (SAF). */
    fun read(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { inp ->
            inp.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()
}