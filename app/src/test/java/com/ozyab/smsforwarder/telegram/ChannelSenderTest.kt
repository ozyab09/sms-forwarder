package com.ozyab.smsforwarder.telegram

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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

    // --- testAll: параллельная проверка подключения (getMe) по всем каналам ---

    @Test
    fun `testAll runs channels in parallel`() = runTest {
        val channels = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val r = ChannelSender.testAll(token, channels) {
            val cur = active.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, cur) }
            delay(150)
            active.decrementAndGet()
            ChannelSender.ChannelTestOutcome.Ok("test_bot")
        }
        assertEquals(3, r.size)
        assertTrue("каналы должны тестироваться параллельно", maxConcurrent.get() >= 3)
        assertTrue(r.all { it.ok })
    }

    @Test
    fun `testAll reports each channel result independently`() = runTest {
        val channels = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        val r = ChannelSender.testAll(token, channels) {
            when (it.id) {
                "direct" -> ChannelSender.ChannelTestOutcome.Ok("bot_a")
                "p1" -> ChannelSender.ChannelTestOutcome.Failed("timeout")
                else -> ChannelSender.ChannelTestOutcome.Ok("bot_c")
            }
        }
        assertEquals(3, r.size)
        assertTrue(r[0].ok)
        assertEquals("bot_a", r[0].botUsername)
        assertTrue(!r[1].ok)
        assertEquals("timeout", r[1].error)
        assertTrue(r[2].ok)
        assertEquals("bot_c", r[2].botUsername)
        assertEquals("неудача одного канала не должна останавливать остальные", 1, r.count { !it.ok })
    }

    @Test
    fun `testAll preserves channel order in results`() = runTest {
        val channels = listOf(
            channel("a", "A"),
            channel("b", "B"),
            channel("c", "C"),
        )
        val r = ChannelSender.testAll(token, channels) {
            if (it.id == "b") delay(200) else delay(10)
            ChannelSender.ChannelTestOutcome.Ok("bot")
        }
        assertEquals(listOf("A", "B", "C"), r.map { it.channel.name })
    }

    @Test
    fun `testAll with no channels returns empty list`() = runTest {
        val r = ChannelSender.testAll(token, emptyList()) {
            ChannelSender.ChannelTestOutcome.Ok("bot")
        }
        assertTrue(r.isEmpty())
    }

    @Test
    fun `testAll getMe with blank username is a failure`() = runTest {
        val channels = listOf(channel("direct", "Без прокси"))
        val r = ChannelSender.testAll(token, channels) {
            ChannelSender.ChannelTestOutcome.Failed("getMe: пустой username")
        }
        assertEquals(1, r.size)
        assertTrue(!r[0].ok)
        assertTrue(r[0].error!!.contains("пустой username"))
    }

    private fun runTest(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}