package com.ozyab.smsforwarder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты очереди с ретраями (SendQueue).
 *
 * Ключевые проверки:
 *  - новые события обрабатываются немедленно, не дожидаясь бэк-оффа упавших;
 *  - бэк-офф per-event удваивается и упирается в кап;
 *  - событие отбрасывается после исчерпания попыток;
 *  - переполнение очереди отбрасывает самое старое;
 *  - восстановление после рестарта делает события готовыми сразу.
 */
class SendQueueTest {

    private var clock = 0L
    private val queue = SendQueue(now = { clock })

    @Test
    fun `fresh events are processed in FIFO order`() {
        queue.enqueue("A")
        queue.enqueue("B")
        assertEquals("A", queue.pollReady()?.text)
        assertEquals("B", queue.pollReady()?.text)
        assertNull(queue.pollReady())
    }

    @Test
    fun `failed event retried with backoff and does not block new events`() {
        queue.enqueue("A")
        val a = queue.pollReady()!!
        clock += 1
        queue.fail(a) // retry at clock + 15s

        // Новое событие должно быть готово немедленно
        queue.enqueue("B")
        assertEquals("B", queue.pollReady()?.text)

        // A недоступно, пока не прошёл бэк-офф
        assertNull(queue.pollReady())
        clock += 14_999
        assertNull(queue.pollReady())
        clock += 1
        assertEquals("A", queue.pollReady()?.text)
    }

    @Test
    fun `backoff doubles per attempt`() {
        queue.enqueue("A")
        var ev = queue.pollReady()!!
        clock += 1
        queue.fail(ev) // +15s
        assertEquals(15_000L, queue.nextRetryDelayMs())
        clock += 15_000
        ev = queue.pollReady()!!
        clock += 1
        queue.fail(ev) // +30s
        assertEquals(30_000L, queue.nextRetryDelayMs())
        clock += 30_000
        ev = queue.pollReady()!!
        clock += 1
        queue.fail(ev) // +60s
        assertEquals(60_000L, queue.nextRetryDelayMs())
    }

    @Test
    fun `retry delay is capped at max`() {
        val q = SendQueue(
            now = { clock },
            maxAttempts = 20,
            initialDelayMs = 15_000L,
            maxDelayMs = 600_000L,
        )
        q.enqueue("A")
        var ev = q.pollReady()!!
        var prevDelay = 0L
        repeat(12) {
            clock += 1
            q.fail(ev)
            val delay = q.nextRetryDelayMs()!!
            assertTrue("задержка должна расти: $prevDelay → $delay", delay >= prevDelay)
            prevDelay = delay
            clock += delay
            ev = q.pollReady()!!
        }
        assertEquals(600_000L, prevDelay)
    }

    @Test
    fun `event dropped after max attempts`() {
        val q = SendQueue(now = { clock }, maxAttempts = 3)
        q.enqueue("A")
        repeat(3) {
            val ev = q.pollReady()!!
            assertTrue(!q.fail(ev))
            clock += 60_000
        }
        val last = q.pollReady()!!
        assertTrue("4-я неудача должна отбросить событие", q.fail(last))
        assertEquals(1, q.droppedAfterAttempts)
        assertNull(q.pollReady())
        assertTrue(q.isEmpty())
    }

    @Test
    fun `queue overflow drops oldest event`() {
        val q = SendQueue(now = { clock }, maxSize = 3)
        q.enqueue("1")
        q.enqueue("2")
        q.enqueue("3")
        q.enqueue("4") // переполнение — отбрасывается "1"
        assertEquals(1, q.droppedOverflow)
        assertEquals("2", q.pollReady()?.text)
        assertEquals("3", q.pollReady()?.text)
        assertEquals("4", q.pollReady()?.text)
    }

    @Test
    fun `restored events become ready immediately`() {
        queue.restore(
            listOf(
                QueuedEvent("A", attempts = 3, nextRetryAt = 999_999_999L),
                QueuedEvent("B", attempts = 0, nextRetryAt = 999_999_999L),
            )
        )
        assertEquals("A", queue.pollReady()?.text)
        assertEquals("B", queue.pollReady()?.text)
    }

    @Test
    fun `nextRetryDelayMs is null when no retries pending`() {
        assertNull(queue.nextRetryDelayMs())
        queue.enqueue("A")
        assertNull(queue.nextRetryDelayMs()) // в pending, не в retries
        val ev = queue.pollReady()!!
        clock += 1
        queue.fail(ev)
        assertNotNull(queue.nextRetryDelayMs())
    }

    @Test
    fun `pollReady promotes due retries to the end of pending`() {
        queue.enqueue("A")
        val a = queue.pollReady()!!
        clock += 1
        queue.fail(a) // ретрай A через 15с
        queue.enqueue("B")

        clock += 15_000
        // Сначала B (новое), потом созревший ретрай A
        assertEquals("B", queue.pollReady()?.text)
        assertEquals("A", queue.pollReady()?.text)
    }
}