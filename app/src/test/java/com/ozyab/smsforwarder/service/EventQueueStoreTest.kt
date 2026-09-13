package com.ozyab.smsforwarder.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Тесты персистентности очереди (`EventQueueStore`, ROADMAP T3).
 *
 * Файл `filesDir/event_queue.json` — at-least-once: при смерти процесса события
 * не должны теряться. Запись асинхронная (один поток), поэтому тесты ждут
 * результат [await] с таймаутом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EventQueueStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        queueFile().delete()
    }

    @After
    fun tearDown() {
        EventQueueStore.clear(context)
        await("файл очереди удалён") { !queueFile().exists() }
    }

    private fun queueFile(): File = File(context.filesDir, "event_queue.json")

    /** Ждёт выполнения условия (асинхронная запись в отдельном потоке). */
    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("не дождались: $what", condition())
    }

    private fun event(text: String, type: String = "sms") = QueuedEvent(
        text = text,
        attempts = 0,
        type = type,
        sender = "+79001234567",
        eventTime = 1_700_000_000_000L,
    )

    @Test
    fun `load returns empty list when file does not exist`() {
        assertTrue(EventQueueStore.load(context).isEmpty())
    }

    @Test
    fun `saved events are restored with metadata`() {
        EventQueueStore.saveAsync(context, listOf(event("первое"), event("второе", type = "missed")))

        await("файл записан") { queueFile().exists() }
        await("оба события в файле") { EventQueueStore.load(context).size == 2 }

        val restored = EventQueueStore.load(context)
        assertEquals(listOf("первое", "второе"), restored.map { it.text })
        assertEquals("missed", restored[1].type)
        assertEquals("+79001234567", restored[0].sender)
        assertEquals(1_700_000_000_000L, restored[0].eventTime)
        // nextRetryAt при восстановлении сбрасывается — события уходят сразу
        assertEquals(0L, restored[0].nextRetryAt)
        // временный файл атомарной записи не остаётся
        assertFalse(File(context.filesDir, "event_queue.json.tmp").exists())
    }

    @Test
    fun `empty snapshot is persisted as empty queue`() {
        EventQueueStore.saveAsync(context, listOf(event("событие")))
        await("файл создан") { queueFile().exists() }

        // Штатно пустая очередь удаляется через clear(); saveAsync(empty) пишет "[]"
        EventQueueStore.saveAsync(context, emptyList())

        await("очередь пуста") { EventQueueStore.load(context).isEmpty() }
    }

    @Test
    fun `persistSingle appends to existing queue`() {
        EventQueueStore.saveAsync(context, listOf(event("первое")))
        await("первое событие на диске") { EventQueueStore.load(context).size == 1 }

        EventQueueStore.persistSingle(context, "второе", type = "missed", sender = "+79990000000")

        await("оба события на диске") { EventQueueStore.load(context).size == 2 }
        assertEquals(listOf("первое", "второе"), EventQueueStore.load(context).map { it.text })
    }

    @Test
    fun `persistSingle keeps only the last MAX_EVENTS`() {
        repeat(105) { i -> EventQueueStore.persistSingle(context, "event-$i") }

        await("самое старое событие — event-5") {
            EventQueueStore.load(context).firstOrNull()?.text == "event-5"
        }

        val restored = EventQueueStore.load(context)
        assertEquals(100, restored.size)
        // остаются самые свежие: 5..104
        assertEquals("event-5", restored.first().text)
        assertEquals("event-104", restored.last().text)
    }

    @Test
    fun `broken file yields empty queue instead of crash`() {
        queueFile().writeText("{ это не JSON-массив")

        assertTrue(EventQueueStore.load(context).isEmpty())
    }

    @Test
    fun `events with blank text are skipped`() {
        queueFile().writeText(
            """[{"text":"","type":"sms"},{"text":"нормальное","type":"sms"}]"""
        )

        val restored = EventQueueStore.load(context)
        assertEquals(1, restored.size)
        assertEquals("нормальное", restored[0].text)
    }

    @Test
    fun `clear deletes the queue file`() {
        EventQueueStore.saveAsync(context, listOf(event("событие")))
        await("файл создан") { queueFile().exists() }

        EventQueueStore.clear(context)

        await("файл удалён") { !queueFile().exists() }
    }

    @Test
    fun `load ignores nextRetryAt from file`() {
        // В файле может лежать будущий retry — после рестарта событие уходит сразу
        queueFile().writeText(
            """[{"text":"событие","attempts":3,"nextRetryAt":99999999999999,"type":"sms","sender":"x","eventTime":5}]"""
        )

        val restored = EventQueueStore.load(context)
        assertEquals(1, restored.size)
        assertEquals(0L, restored[0].nextRetryAt)
        assertEquals(3, restored[0].attempts)
    }
}
