package com.ozyab.smsforwarder.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/**
 * Лог событий приложения (вкладка «Логи») + мост в Timber.
 *
 * Каждая запись пишется в [Timber] (logcat, DebugTree в debug-сборках)
 * и в буфер для UI. Логи хранятся 7 дней, старые удаляются автоматически.
 * Очистка происходит при добавлении новых записей и вручную через clear().
 */
object LogStore {

    const val MAX_ENTRIES = 200
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 // 7 дней

    enum class Level { INFO, OK, WARN, ERROR }

    data class Entry(
        val time: String,        // HH:mm:ss для отображения
        val timestamp: Long,     // epoch ms для очистки старых
        val level: Level,
        val text: String,
    )

    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    @Synchronized
    fun log(level: Level, text: String) {
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        val e = Entry(time, now, level, text)
        entries.addLast(e)
        trimOld()
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        for (l in listeners) l(e)
        when (level) {
            Level.INFO -> Timber.i(text)
            Level.OK -> Timber.i(text)
            Level.WARN -> Timber.w(text)
            Level.ERROR -> Timber.e(text)
        }
    }

    private fun trimOld() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        while (entries.isNotEmpty() && entries.first().timestamp < cutoff) {
            entries.removeFirst()
        }
    }

    fun info(text: String) = log(Level.INFO, text)
    fun ok(text: String) = log(Level.OK, text)
    fun warn(text: String) = log(Level.WARN, text)
    fun error(text: String) = log(Level.ERROR, text)

    @Synchronized
    fun all(): List<Entry> = entries.toList()

    fun addListener(l: (Entry) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Entry) -> Unit) {
        listeners.remove(l)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}