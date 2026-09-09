package com.ozyab.smsforwarder.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Персистентность очереди пересылки.
 *
 * Очередь живёт в памяти (быстро), но при смерти процесса/перезагрузке не должна
 * теряться: снимок пишется в `filesDir/event_queue.json` асинхронно после каждого
 * изменения. При старте сервиса очередь восстанавливается из файла.
 *
 * Политика: at-least-once. При сбое записи события теряются только в худшем
 * случае; дубликаты при повторной отправке допустимы (лучше, чем потеря).
 */
object EventQueueStore {

    private const val FILE_NAME = "event_queue.json"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "queue-store").apply { isDaemon = true }
    }

    /** Последний запрошенный снимок (коалесценция пачки изменений). */
    @Volatile
    private var pendingSave: List<QueuedEvent>? = null

    /** Чтение очереди из файла (вызывается при старте сервиса). */
    fun load(context: Context): List<QueuedEvent> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val text = o.optString("text", "")
                    if (text.isBlank()) continue
                    // nextRetryAt сбрасывается при restore — события отправляются сразу после рестарта
                    add(QueuedEvent(text = text, attempts = o.optInt("attempts", 0)))
                }
            }
        } catch (e: Exception) {
            // Битый/старый файл — начинаем с чистой очереди
            emptyList()
        }
    }

    /**
     * Асинхронное сохранение снимка. Вызовы из разных потоков безопасны;
     * запись сериализована одним потоком, при пачке изменений пишется последний снимок.
     */
    fun saveAsync(context: Context, events: List<QueuedEvent>) {
        pendingSave = events
        executor.execute {
            val snapshot = pendingSave ?: return@execute
            pendingSave = null
            try {
                val arr = JSONArray()
                for (e in snapshot) {
                    arr.put(
                        JSONObject()
                            .put("text", e.text)
                            .put("attempts", e.attempts)
                            .put("nextRetryAt", e.nextRetryAt)
                    )
                }
                file(context).writeText(arr.toString())
            } catch (e: Exception) {
                // Не критично: при следующем изменении очереди попробуем снова
            }
        }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}