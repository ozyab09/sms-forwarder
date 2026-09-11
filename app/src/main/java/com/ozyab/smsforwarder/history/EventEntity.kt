package com.ozyab.smsforwarder.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Событие пересылки (SMS / пропущенный вызов).
 *
 * Privacy-first: хранится только на устройстве; тексты сообщений не
 * отправляются наружу (кроме самого факта пересылки в Telegram).
 * Запись делается после формирования текста пересылки.
 */
@Entity(
    tableName = "events",
    indices = [
        Index("timestamp"),
        Index("type"),
        Index("status"),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val type: String,          // "sms" | "missed"
    val status: String,        // "sent" | "failed" | "dropped" | "queued"
    val channelName: String?,  // через какой канал ушло (для sent)
    val attempts: Int,
    val formattedText: String, // итоговый текст (что ушло в Telegram)
)