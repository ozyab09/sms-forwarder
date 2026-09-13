package com.ozyab.smsforwarder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты кольцевого буфера логов (вкладка «Логи»).
 *
 * Логика чистая: максимум [LogStore.MAX_ENTRIES] записей, порядок FIFO,
 * слушатели получают каждую запись, уровни INFO/OK/WARN/ERROR.
 * Timber без посаженных деревьев молча пропускает вывод — безопасно для unit.
 */
class LogStoreTest {

    @Before
    fun setUp() {
        LogStore.clear()
    }

    @Test
    fun `all returns entries in insertion order`() {
        LogStore.info("first")
        LogStore.ok("second")
        LogStore.warn("third")
        LogStore.error("fourth")

        val entries = LogStore.all()
        assertEquals(4, entries.size)
        assertEquals(listOf("first", "second", "third", "fourth"), entries.map { it.text })
        assertEquals(
            listOf(
                LogStore.Level.INFO,
                LogStore.Level.OK,
                LogStore.Level.WARN,
                LogStore.Level.ERROR,
            ),
            entries.map { it.level },
        )
    }

    @Test
    fun `buffer is capped at MAX_ENTRIES`() {
        repeat(LogStore.MAX_ENTRIES + 50) { i -> LogStore.info("msg-$i") }

        val entries = LogStore.all()
        assertEquals(LogStore.MAX_ENTRIES, entries.size)
        // Вытесняются самые старые — первый оставшийся это msg-50
        assertEquals("msg-50", entries.first().text)
        assertEquals("msg-${LogStore.MAX_ENTRIES + 49}", entries.last().text)
    }

    @Test
    fun `entry has HH mm ss time format`() {
        LogStore.info("x")
        val e = LogStore.all().single()
        assertTrue(
            "время должно быть HH:mm:ss, было: ${e.time}",
            e.time.matches(Regex("\\d{2}:\\d{2}:\\d{2}")),
        )
    }

    @Test
    fun `clear empties buffer`() {
        LogStore.info("a")
        LogStore.clear()
        assertTrue(LogStore.all().isEmpty())
    }

    @Test
    fun `listener receives every entry and can be removed`() {
        val seen = mutableListOf<String>()
        val listener: (LogStore.Entry) -> Unit = { seen.add(it.text) }

        LogStore.addListener(listener)
        LogStore.info("one")
        LogStore.warn("two")
        LogStore.removeListener(listener)
        LogStore.error("three")

        assertEquals(listOf("one", "two"), seen)
    }
}