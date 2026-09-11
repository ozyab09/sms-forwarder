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
 * и в кольцевой буфер с максимум [MAX_ENTRIES] записей для UI.
 * UI (MainActivity) подписывается через [addListener].
 */
object LogStore {

    const val MAX_ENTRIES = 200

    enum class Level { INFO, OK, WARN, ERROR }

    data class Entry(
        val time: String,   // HH:mm:ss
        val level: Level,
        val text: String,
    )

    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    @Synchronized
    fun log(level: Level, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val e = Entry(time, level, text)
        entries.addLast(e)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        for (l in listeners) l(e)
        // Дублируем в logcat через Timber (DebugTree печатает только в debug).
        // Без посаженных деревьев Timber молча пропускает — безопасно для release.
        when (level) {
            Level.INFO -> Timber.i(text)
            Level.OK -> Timber.i(text)
            Level.WARN -> Timber.w(text)
            Level.ERROR -> Timber.e(text)
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