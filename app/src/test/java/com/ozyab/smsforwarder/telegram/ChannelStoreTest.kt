package com.ozyab.smsforwarder.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты promote-on-success (умный приоритет каналов).
 *
 * [promoteOrder] — чистая функция перестановки: канал, через который удалось
 * отправить сообщение, становится первым в списке. Порядок полностью динамический,
 * в т.ч. для direct-канала.
 */
class ChannelStoreTest {

    private fun channel(id: String, name: String, type: String = Channel.TYPE_DIRECT) =
        Channel(id = id, type = type, name = name, host = "", port = 0, user = "", pass = "", enabled = true)

    @Test
    fun `promote moves channel to first position`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = promoteOrder(cur, "p3")!!
        assertEquals(listOf("p3", "direct", "p1", "p2"), next.map { it.id })
    }

    @Test
    fun `promote already first returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        // direct уже первый
        assertNull(promoteOrder(cur, "direct"))
    }

    @Test
    fun `promote direct channel moves it to first`() {
        val cur = listOf(
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("direct", "Без прокси"),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        val next = promoteOrder(cur, "direct")!!
        assertEquals(listOf("direct", "p1", "p2"), next.map { it.id })
    }

    @Test
    fun `promote unknown channel returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        assertNull(promoteOrder(cur, "nope"))
    }

    @Test
    fun `promote with single channel returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
        )
        assertNull(promoteOrder(cur, "direct")) // direct уже первый
    }

    @Test
    fun `promote empty list returns null`() {
        assertNull(promoteOrder(emptyList(), "p1"))
    }

    @Test
    fun `promote preserves channel set`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = promoteOrder(cur, "p2")!!
        assertEquals(setOf("direct", "p1", "p2", "p3"), next.map { it.id }.toSet())
        assertEquals("p2", next.first().id)
    }

    // --- demoteOrder tests ---

    @Test
    fun `demote moves channel to last position`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = demoteOrder(cur, "direct")!!
        assertEquals(listOf("p1", "p2", "p3", "direct"), next.map { it.id })
    }

    @Test
    fun `demote proxy channel to last`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        val next = demoteOrder(cur, "p1")!!
        assertEquals(listOf("direct", "p2", "p1"), next.map { it.id })
    }

    @Test
    fun `demote already last returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        assertNull(demoteOrder(cur, "p2"))
    }

    @Test
    fun `demote unknown channel returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
        )
        assertNull(demoteOrder(cur, "nope"))
    }

    @Test
    fun `demote with single channel returns null`() {
        val cur = listOf(channel("direct", "Без прокси"))
        assertNull(demoteOrder(cur, "direct"))
    }

    @Test
    fun `demote empty list returns null`() {
        assertNull(demoteOrder(emptyList(), "p1"))
    }

    @Test
    fun `demote preserves channel set`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        val next = demoteOrder(cur, "direct")!!
        assertEquals(setOf("direct", "p1", "p2"), next.map { it.id }.toSet())
        assertEquals("direct", next.last().id)
    }
}