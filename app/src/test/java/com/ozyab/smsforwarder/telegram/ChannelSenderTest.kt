package com.ozyab.smsforwarder.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты каскадной отправки через каналы (ChannelSender).
 *
 * Проверяем порядок каналов, fallback на следующий канал и агрегацию ошибок.
 * Функция отправки инжектится — сеть не нужна.
 */
class ChannelSenderTest {

    private val token = "123:TEST"
    private val chatId = "42"

    private fun channel(id: String, name: String, type: String = Channel.TYPE_DIRECT) =
        Channel(id = id, type = type, name = name, host = "", port = 0, user = "", pass = "", enabled = true)

    @Test
    fun `first channel success wins`() = runTest {
        var calls = 0
        val r = ChannelSender.send(
            "hi", token, chatId,
            listOf(channel("direct", "Без прокси"), channel("p1", "Прокси 1", Channel.TYPE_HTTP)),
        ) {
            calls++
            if (it.id == "direct") ChannelSender.ChannelOutcome.Sent(111) else ChannelSender.ChannelOutcome.Failed("n/a")
        }
        assertTrue(r is ChannelSender.Result.Ok)
        assertEquals("Без прокси", (r as ChannelSender.Result.Ok).channelName)
        assertEquals(111L, r.messageId)
        assertEquals("второй канал не должен вызываться", 1, calls)
    }

    @Test
    fun `fallback to second channel when first fails`() = runTest {
        val r = ChannelSender.send(
            "hi", token, chatId,
            listOf(channel("direct", "Без прокси"), channel("p1", "Прокси 1", Channel.TYPE_HTTP)),
        ) {
            if (it.id == "direct") ChannelSender.ChannelOutcome.Failed("timeout")
            else ChannelSender.ChannelOutcome.Sent(222)
        }
        assertTrue(r is ChannelSender.Result.Ok)
        assertEquals("Прокси 1", (r as ChannelSender.Result.Ok).channelName)
    }

    @Test
    fun `empty channels returns error`() = runTest {
        val r = ChannelSender.send("hi", token, chatId, emptyList()) {
            ChannelSender.ChannelOutcome.Failed("never")
        }
        assertTrue(r is ChannelSender.Result.Err)
    }

    @Test
    fun `all channels failed aggregates reasons`() = runTest {
        val channels = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
        )
        val r = ChannelSender.send("hi", token, chatId, channels) {
            ChannelSender.ChannelOutcome.Failed("boom ${it.name}")
        }
        assertTrue(r is ChannelSender.Result.Err)
        val reasons = (r as ChannelSender.Result.Err).reasons
        assertEquals(2, reasons.size)
        assertTrue(reasons[0].contains("Без прокси"))
        assertTrue(reasons[1].contains("Прокси 1"))
    }

    private fun runTest(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}