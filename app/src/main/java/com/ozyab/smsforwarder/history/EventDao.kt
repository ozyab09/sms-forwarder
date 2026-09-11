package com.ozyab.smsforwarder.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

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
          AND (:query IS NULL OR sender LIKE '%' || :query || '%'
               OR body LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun search(type: String?, query: String?, limit: Int): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE status = 'sent'")
    suspend fun sentCount(): Int

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM events")
    suspend fun clear()
}