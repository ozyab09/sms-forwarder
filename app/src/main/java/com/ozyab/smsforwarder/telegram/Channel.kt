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
 * не должны лежать в plain-префсах).
 *
 * Канал direct добавляется автоматически при чтении, если его нет. Порядок
 * списка = приоритет каскадной отправки и полностью динамический: канал,
 * через который прошла отправка, поднимается наверх (promote-on-success),
 * а неуспешный уходит в конец (demote-on-failure) — в том числе direct.
 */
object ChannelStore {

    private const val KEY_CHANNELS = "channels_json"

    @Volatile
    private var cache: List<Channel>? = null

    /** Все каналы в текущем порядке приоритета (успешный — выше, неуспешный — ниже). */
    fun all(): List<Channel> {
        cachedOrLoad()?.let { return it }
        return listOf(Channel.direct())
    }

    /** Только включённые каналы. */
    fun enabled(): List<Channel> = all().filter { it.enabled }

    fun get(id: String): Channel? = all().find { it.id == id }

    fun setAll(channels: List<Channel>) {
        // Нормализация: direct всегда включён и не может быть выключен/изменён
        // из хранилища (иначе можно остаться без каналов). Позиция direct в списке
        // НЕ фиксируется — порядок динамический (promote/demote/move). Если direct
        // во входном списке нет (импорт, старые версии) — добавляется первым.
        val normalized = if (channels.any { it.isDirect }) {
            channels.map { if (it.isDirect && !it.enabled) it.copy(enabled = true) else it }
        } else {
            listOf(Channel.direct()) + channels
        }
        val arr = JSONArray()
        for (c in normalized) arr.put(c.toJson())
        Prefs.channelsJson = arr.toString()
        cache = normalized
        // Конфигурация каналов изменилась — OkHttp-клиенты пересоздадутся при следующем использовании
        ChannelClientFactory.invalidate()
    }

    /** Добавить/обновить канал (по id). */
    fun upsert(channel: Channel) {
        val cur = all()
        val idx = cur.indexOfFirst { it.id == channel.id }
        val next = if (idx >= 0) cur.toMutableList().also { it[idx] = channel } else cur + channel
        setAll(next)
    }

    fun remove(id: String): Boolean {
        // «Без прокси» удалить нельзя — удаляются только пользовательские прокси
        if (id == Channel.DIRECT_ID) return false
        val cur = all()
        if (cur.none { it.id == id }) return false
        setAll(cur.filterNot { it.id == id })
        return true
    }

    /**
     * Переместить канал вверх/вниз по списку приоритета.
     *
     * @param delta -1 = выше, +1 = ниже.
     * @return true, если перестановка выполнена.
     */
    fun move(id: String, delta: Int): Boolean {
        val cur = all().toMutableList()
        val idx = cur.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val newIdx = idx + delta
        if (newIdx < 0 || newIdx >= cur.size) return false
        val tmp = cur[idx]
        cur[idx] = cur[newIdx]
        cur[newIdx] = tmp
        setAll(cur)
        return true
    }

    /**
     * Поднять канал на первое место списка.
     *
     * Используется при promote-on-success: после успешной отправки через канал
     * он становится приоритетным для следующих сообщений.
     *
     * @return true, если порядок был изменён.
     */
    fun promote(id: String): Boolean {
        val next = promoteOrder(all(), id) ?: return false
        setAll(next)
        return true
    }

    /**
     * Опустить канал в конец списка.
     *
     * Используется при demote-on-failure: после неуспешной отправки канал
     * пробуется последним, пока снова не увенчается успехом. Работает и для
     * direct — порядок динамический.
     *
     * @return true, если порядок был изменён.
     */
    fun demote(id: String): Boolean {
        val next = demoteOrder(all(), id) ?: return false
        setAll(next)
        return true
    }

    /** Первое чтение: миграция старых одиночных прокси-настроек (v0.4.x) в канал. */
    private fun cachedOrLoad(): List<Channel>? {
        cache?.let { return it }
        val raw = Prefs.channelsJson
        if (raw.isNotBlank()) {
            val arr = JSONArray(raw)
            val stored = buildList {
                for (i in 0 until arr.length()) {
                    add(Channel.fromJson(arr.getJSONObject(i)))
                }
            }
            // direct хранится в общем порядке: если есть — оставляем на его позиции
            // (принудительно включённым), если нет (старые версии) — добавляем первым.
            val result = if (stored.any { it.isDirect }) {
                stored.map { if (it.isDirect && !it.enabled) it.copy(enabled = true) else it }
            } else {
                listOf(Channel.direct()) + stored
            }
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

/**
 * Чистая функция promote-on-success (без хранилища, тестируется отдельно):
 * возвращает новый порядок каналов с каналом [id] на первом месте или null,
 * если порядок менять не надо (пустой список, канал не найден или уже первый).
 */
internal fun promoteOrder(cur: List<Channel>, id: String): List<Channel>? {
    if (cur.size < 2) return null
    val idx = cur.indexOfFirst { it.id == id }
    if (idx <= 0) return null // не найден или уже первый
    val ch = cur[idx]
    return listOf(ch) + cur.filterIndexed { i, _ -> i != idx }
}

/**
 * Чистая функция demote-on-failure (без хранилища, тестируется отдельно):
 * возвращает новый порядок каналов с каналом [id] в конце списка или null,
 * если порядок менять не надо (пустой список, один канал, канал не найден
 * или уже последний).
 */
internal fun demoteOrder(cur: List<Channel>, id: String): List<Channel>? {
    if (cur.size < 2) return null
    val idx = cur.indexOfFirst { it.id == id }
    if (idx < 0 || idx == cur.size - 1) return null // не найден или уже последний
    val ch = cur[idx]
    return cur.filterIndexed { i, _ -> i != idx } + ch
}