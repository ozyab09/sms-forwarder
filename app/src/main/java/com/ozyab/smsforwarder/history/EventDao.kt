package com.ozyab.smsforwarder.history

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Простая пара ключ-значение для статистики (Room-friendly).
 * Используется вместо Pair<String, Int> который не поддерживается Room KSP.
 * Колонка `stat_key` вместо `key`, т.к. `key` — зарезервированное слово в SQL.
 */
@Entity(tableName = "stat_entry")
data class StatEntry(
    @PrimaryKey @ColumnInfo(name = "stat_key") val statKey: String,
    val count: Int,
)

/**
 * DAO для истории событий.
 *
 * Все методы suspend — вызовы идут через coroutines (IO-диспетчер),
 * UI не блокируется.
 */
@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE (:type IS NULL OR type = :type)
          AND (:query IS NULL OR sender LIKE '%' || :query || '%' ESCAPE '\'
               OR body LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun search(type: String?, query: String?, limit: Int): List<EventEntity>

    /** Минимальный timestamp среди MAX_EVENTS самых свежих записей (для обрезки), null если записей меньше лимита. */
    @Query(
        """
        SELECT MIN(timestamp) FROM (
            SELECT timestamp FROM events ORDER BY timestamp DESC LIMIT :keep
        )
        """
    )
    suspend fun oldestKeptTimestamp(keep: Int): Long?

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM events")
    suspend fun clear()

    // --- Статистика (F-E) ---

    /** Всего событий в истории. */
    @Query("SELECT COUNT(*) FROM events")
    suspend fun countAll(): Int

    /** Событий за сегодня (по локальному времени устройства). */
    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE date(timestamp / 1000, 'unixepoch', 'localtime') = date('now', 'localtime')
        """
    )
    suspend fun countToday(): Int

    /** Событий по статусу за сегодня. */
    @Query(
        """
        SELECT status as stat_key, COUNT(*) as count FROM events
        WHERE date(timestamp / 1000, 'unixepoch', 'localtime') = date('now', 'localtime')
        GROUP BY status
        """
    )
    suspend fun countTodayByStatus(): List<StatEntry>

    /** Событий по типу за сегодня. */
    @Query(
        """
        SELECT type as stat_key, COUNT(*) as count FROM events
        WHERE date(timestamp / 1000, 'unixepoch', 'localtime') = date('now', 'localtime')
        GROUP BY type
        """
    )
    suspend fun countTodayByType(): List<StatEntry>

    /** Событий по каналу за сегодня (только sent). */
    @Query(
        """
        SELECT channelName as stat_key, COUNT(*) as count FROM events
        WHERE status = 'sent'
          AND date(timestamp / 1000, 'unixepoch', 'localtime') = date('now', 'localtime')
          AND channelName IS NOT NULL
        GROUP BY channelName
        """
    )
    suspend fun countTodayByChannel(): List<StatEntry>

    /** Всего событий по статусу (все время). */
    @Query(
        """
        SELECT status as stat_key, COUNT(*) as count FROM events
        GROUP BY status
        """
    )
    suspend fun countAllByStatus(): List<StatEntry>

    /** Всего событий по типу (все время). */
    @Query(
        """
        SELECT type as stat_key, COUNT(*) as count FROM events
        GROUP BY type
        """
    )
    suspend fun countAllByType(): List<StatEntry>

    /** Всего событий по каналу (все время, только sent). */
    @Query(
        """
        SELECT channelName as stat_key, COUNT(*) as count FROM events
        WHERE status = 'sent'
          AND channelName IS NOT NULL
        GROUP BY channelName
        """
    )
    suspend fun countAllByChannel(): List<StatEntry>
}