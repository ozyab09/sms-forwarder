package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Канал отправки сообщения в Telegram.
 *
 * - [type]="direct" — прямое соединение без прокси (всегда существует, id="direct").
 * - [type]="http" / "socks5" — прокси-канал.
 *
 * Хранится в EncryptedSharedPreferences как JSON-массив (см. [ChannelStore]).
 */
data class Channel(
    val id: String,
    val type: String,   // "direct" | "http" | "socks5"
    val name: String,   // пользовательская подпись (например "Прокси 1", "Без прокси")
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val enabled: Boolean,
) {
    val isDirect: Boolean get() = type == "direct"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("user", user)
        .put("pass", pass)
        .put("enabled", enabled)

    companion object {
        const val DIRECT_ID = "direct"
        const val TYPE_DIRECT = "direct"
        const val TYPE_HTTP = "http"
        const val TYPE_SOCKS5 = "socks5"

        fun fromJson(o: JSONObject): Channel = Channel(
            id = o.optString("id", UUID.randomUUID().toString()),
            type = o.optString("type", TYPE_HTTP),
            name = o.optString("name", ""),
            host = o.optString("host", ""),
            port = o.optInt("port", 0),
            user = o.optString("user", ""),
            pass = o.optString("pass", ""),
            enabled = o.optBoolean("enabled", true),
        )

        /** Канал «без прокси» — всегда первый, всегда включён. */
        fun direct(): Channel = Channel(
            id = DIRECT_ID,
            type = TYPE_DIRECT,
            name = "Без прокси",
            host = "",
            port = 0,
            user = "",
            pass = "",
            enabled = true,
        )
    }
}

/**
 * Хранилище каналов отправки.
 *
 * Сериализация — JSON-массив в EncryptedSharedPreferences (пароли прокси
 * не должны лежать в plain-префсах). Канал direct добавляется автоматически
 * при чтении, если его нет.
 */
object ChannelStore {

    private const val KEY_CHANNELS = "channels_json"

    @Volatile
    private var cache: List<Channel>? = null

    /** Все каналы: direct всегда первым, затем прокси в порядке добавления. */
    fun all(): List<Channel> {
        cachedOrLoad()?.let { return it }
        return listOf(Channel.direct())
    }

    /** Только включённые каналы, direct первым. */
    fun enabled(): List<Channel> = all().filter { it.enabled }

    fun get(id: String): Channel? = all().find { it.id == id }

    fun setAll(channels: List<Channel>) {
        // Нормализация: канал «Без прокси» всегда первый, всегда включён и не может
        // быть выключен/изменён из хранилища (иначе можно остаться без каналов)
        val normalized = listOf(Channel.direct()) + channels.filterNot { it.isDirect }
        val arr = JSONArray()
        for (c in normalized) arr.put(c.toJson())
        Prefs.channelsJson = arr.toString()
        cache = normalized
    }

    /** Добавить/обновить канал (по id). */
    fun upsert(channel: Channel) {
        val cur = all()
        val idx = cur.indexOfFirst { it.id == channel.id }
        val next = if (idx >= 0) cur.toMutableList().also { it[idx] = channel } else cur + channel
        setAll(next)
    }

    fun remove(id: String) {
        setAll(all().filterNot { it.id == id })
    }

    /**
     * Переместить канал вверх/вниз относительно других прокси-каналов.
     * Канал «Без прокси» (direct) всегда первый и не двигается.
     *
     * @param delta -1 = выше, +1 = ниже.
     * @return true, если перестановка выполнена.
     */
    fun move(id: String, delta: Int): Boolean {
        val cur = all()
        val direct = cur.first()
        val proxies = cur.drop(1).toMutableList()
        val idx = proxies.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val newIdx = idx + delta
        if (newIdx < 0 || newIdx >= proxies.size) return false
        val tmp = proxies[idx]
        proxies[idx] = proxies[newIdx]
        proxies[newIdx] = tmp
        setAll(listOf(direct) + proxies)
        return true
    }

    /** Первое чтение: миграция старых одиночных прокси-настроек (v0.4.x) в канал. */
    private fun cachedOrLoad(): List<Channel>? {
        cache?.let { return it }
        val raw = Prefs.channelsJson
        if (raw.isNotBlank()) {
            val arr = JSONArray(raw)
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val c = Channel.fromJson(arr.getJSONObject(i))
                    if (!c.isDirect) add(c)
                }
            }
            val result = listOf(Channel.direct()) + list
            cache = result
            return result
        }
        // Миграция со старых Prefs.proxyEnabled/proxyHost/...
        val legacy = Prefs.migrateLegacyProxyToChannel() ?: return listOf(Channel.direct())
        val result = listOf(Channel.direct()) + legacy
        saveMigrated(result)
        return result
    }

    private fun saveMigrated(result: List<Channel>) {
        val arr = JSONArray()
        for (c in result) arr.put(c.toJson())
        Prefs.channelsJson = arr.toString()
        cache = result
        // очищаем старые поля, чтобы миграция не повторилась
        Prefs.clearLegacyProxy()
    }

    /** Сброс кэша (для тестов). */
    fun invalidate() {
        cache = null
    }
}