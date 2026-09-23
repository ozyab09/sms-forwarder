package com.ozyab.smsforwarder.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Тонкая обёртка над Room для записи/чтения истории событий.
 *
 * Вызывается из сервиса (пересылка) и из UI (вкладка «История»).
 * Все операции suspend — выполняются на IO.
 */
object EventHistory {

    /** Статусы события. */
    const val STATUS_SENT = "sent"
    const val STATUS_FAILED = "failed"
    const val STATUS_DROPPED = "dropped"

    /** Типы события. */
    const val TYPE_SMS = "sms"
    const val TYPE_MISSED = "missed"
    const val TYPE_INCOMING = "incoming"
    const val TYPE_OUTGOING = "outgoing"
    const val TYPE_OUTGOING_SMS = "outgoing_sms"

    /** Сколько событий максимум держим в истории. */
    const val MAX_EVENTS = 1000

    /** Хранилище живёт в памяти процесса (данные уже в Room). */
    private suspend fun dao(context: Context) = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao()
    }

    /**
     * Записать событие. Возвращает id записи.
     *
     * @param chatId кому ушло (Telegram chat id) — показывается в деталях события.
     * @param botUsername через какого бота ушло (@username).
     */
    suspend fun record(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        type: String,
        status: String,
        channelName: String?,
        attempts: Int,
        formattedText: String,
        chatId: String? = null,
        botUsername: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val d = EventDatabase.get(context).eventDao()
        d.insert(
            EventEntity(
                sender = sender,
                body = body,
                timestamp = timestamp,
                type = type,
                status = status,
                channelName = channelName,
                attempts = attempts,
                formattedText = formattedText,
                chatId = chatId,
                botUsername = botUsername,
            )
        ).also {
            // Держим историю ограниченной: удаляем старые записи при переполнении
            pruneOld(context, d)
        }
    }

    /** Последние N событий. */
    suspend fun recent(context: Context, limit: Int = 200): List<EventEntity> = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao().recent(limit)
    }

    /** Поиск по типу и тексту/номеру. */
    suspend fun search(context: Context, type: String?, query: String?, limit: Int = 200): List<EventEntity> =
        withContext(Dispatchers.IO) {
            // Экранируем LIKE-спецсимволы: % и _ в запросе — литералы
            // (ESCape '\' задан в EventDao.search).
            val safeQuery = query
                ?.replace("\\", "\\\\")
                ?.replace("%", "\\%")
                ?.replace("_", "\\_")
            EventDatabase.get(context).eventDao().search(type, safeQuery?.takeIf { it.isNotBlank() }, limit)
        }

    /** Удалить запись по id. */
    suspend fun delete(context: Context, id: Long) = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao().delete(id)
    }

    /** Удалить всё. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao().clear()
    }

    /**
     * Обрезка до [MAX_EVENTS] — одним SQL-запросом (без чтения 1001 строки в память).
     * Если записей больше лимита, удаляем всё строго старше минимального timestamp
     * среди MAX_EVENTS самых свежих (равные timestamp остаются — запас на пачку).
     */
    private suspend fun pruneOld(context: Context, dao: EventDao) {
        val cutoffTs = dao.oldestKeptTimestamp(MAX_EVENTS)
        if (cutoffTs != null) {
            dao.deleteOlderThan(cutoffTs)
        }
    }
}