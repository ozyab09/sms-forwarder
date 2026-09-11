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
    const val STATUS_QUEUED = "queued"

    /** Типы события. */
    const val TYPE_SMS = "sms"
    const val TYPE_MISSED = "missed"

    /** Сколько событий максимум держим в истории. */
    const val MAX_EVENTS = 1000

    /** Хранилище живёт в памяти процесса (данные уже в Room). */
    private suspend fun dao(context: Context) = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao()
    }

    /** Записать событие. Возвращает id записи. */
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
            EventDatabase.get(context).eventDao().search(type, query?.takeIf { it.isNotBlank() }, limit)
        }

    /** Сколько всего отправлено (для статуса на главной). */
    suspend fun sentCount(context: Context): Int = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao().sentCount()
    }

    /** Удалить запись по id. */
    suspend fun delete(context: Context, id: Long) = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao().delete(id)
    }

    /** Удалить всё. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        EventDatabase.get(context).eventDao().clear()
    }

    /** Обрезка до [MAX_EVENTS] — удаляем самые старые. */
    private suspend fun pruneOld(context: Context, dao: EventDao) {
        val count = dao.recent(MAX_EVENTS + 1)
        if (count.size > MAX_EVENTS) {
            val oldestToKeep = count.last().timestamp
            dao.deleteOlderThan(oldestToKeep)
        }
    }
}