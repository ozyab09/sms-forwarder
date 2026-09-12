package com.ozyab.smsforwarder.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты promote-on-success (умный приоритет каналов).
 *
 * [promoteOrder] — чистая функция перестановки: канал, через который удалось
 * отправить сообщение, становится первым среди прокси (сразу после direct).
 */
class ChannelStoreTest {

    private fun channel(id: String, name: String, type: String = Channel.TYPE_DIRECT) =
        Channel(id = id, type = type, name = name, host = "", port = 0, user = "", pass = "", enabled = true)

    @Test
    fun `promote moves channel to first proxy position`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = promoteOrder(cur, "p3")!!
        assertEquals(listOf("direct", "p3", "p1", "p2"), next.map { it.id })
    }

    @Test
    fun `promote already first proxy returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        assertNull(promoteOrder(cur, "p1"))
    }

    @Test
    fun `promote direct channel returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
        )
        assertNull(promoteOrder(cur, "direct"))
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
    fun `promote with single proxy returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
        )
        assertNull(promoteOrder(cur, "p1"))
    }

    @Test
    fun `promote empty list returns null`() {
        assertNull(promoteOrder(emptyList(), "p1"))
    }

    @Test
    fun `promote preserves direct first and keeps channel set`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = promoteOrder(cur, "p2")!!
        assertEquals(setOf("direct", "p1", "p2", "p3"), next.map { it.id }.toSet())
        assertEquals("direct", next.first().id)
        assertEquals("p2", next[1].id)
    }
}