package com.ozyab.smsforwarder.service

import com.ozyab.smsforwarder.util.LogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Тесты персистентности очереди: раунд-трип, лимиты, битый файл, дедуп по uid (#139).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventQueueStoreTest {

    private val context: android.content.Context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun queueFile(): File = File(context.filesDir, "event_queue.json")

    private fun awaitFileGone(what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!queueFile().exists()) return
            Thread.sleep(20)
        }
        assertTrue("не дождались: $what", !queueFile().exists())
    }

    @org.junit.Before
    fun setUp() {
        EventQueueStore.clear(context)
        awaitFileGone("очередь чиста перед тестом")
    }

    @org.junit.After
    fun tearDown() {
        EventQueueStore.clear(context)
        awaitFileGone("очередь чиста после теста")
    }

    @Test
    fun `save and load roundtrip preserves metadata`() {
        val events = listOf(
            QueuedEvent(id = "a1", text = "hello", type = "sms", sender = "+7900", eventTime = 123L),
            QueuedEvent(id = "b2", text = "missed call", type = "missed", sender = "+7916", eventTime = 456L),
        )
        EventQueueStore.saveAsync(context, events)
        // saveAsync асинхронный: ждём файл
        val deadline = System.currentTimeMillis() + 5_000
        while (!queueFile().exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val loaded = EventQueueStore.load(context)
        assertEquals(2, loaded.size)
        assertEquals("a1", loaded[0].id)
        assertEquals("hello", loaded[0].text)
        assertEquals("sms", loaded[0].type)
        assertEquals("+7900", loaded[0].sender)
        assertEquals(123L, loaded[0].eventTime)
        assertEquals("b2", loaded[1].id)
    }

    @Test
    fun `merge dedupes by uid not by text`() {
        // Снимок содержит событие X (id=1). На диске — X (id=1) и другое событие
        // с ТОТ ЖЕ текстом, но другим uid (id=2): это два разных события (два
        // одинаковых SMS за секунду) — оба должны сохраниться.
        val snapshot = listOf(QueuedEvent(id = "1", text = "same text", sender = "+700", eventTime = 1L))
        EventQueueStore.saveAsync(context, listOf(
            QueuedEvent(id = "1", text = "same text", sender = "+700", eventTime = 1L),
            QueuedEvent(id = "2", text = "same text", sender = "+700", eventTime = 1L),
        ))
        awaitFileExists()
        // Повторный снимок только с id=1: id=2 должен остаться (это append-событие)
        EventQueueStore.saveAsync(context, snapshot)
        val deadline = System.currentTimeMillis() + 5_000
        var loaded: List<QueuedEvent> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            loaded = EventQueueStore.load(context)
            if (loaded.size == 2) break
            Thread.sleep(20)
        }
        assertEquals(2, loaded.size)
        assertEquals(listOf("1", "2"), loaded.map { it.id })
    }

    private fun awaitFileExists() {
        val deadline = System.currentTimeMillis() + 5_000
        while (!queueFile().exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `load skips events with blank text`() {
        EventQueueStore.saveAsync(context, listOf(
            QueuedEvent(id = "1", text = "ok"),
            QueuedEvent(id = "2", text = "  "),
        ))
        awaitFileExists()
        // persistSingle пишет напрямую — эмулируем битую запись с пустым текстом
        val raw = queueFile().readText()
        queueFile().writeText(raw) // файл уже валиден; проверка пустых — в parse
        val loaded = EventQueueStore.load(context)
        assertTrue(loaded.all { it.text.isNotBlank() })
    }

    @Test
    fun `broken file yields empty queue`() {
        queueFile().writeText("{not json")
        val loaded = EventQueueStore.load(context)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `old events without id are loaded with blank id`() {
        // Обратная совместимость: файл от предыдущей версии без поля id
        queueFile().writeText("""[{"text":"legacy","type":"sms","sender":"+700","eventTime":42,"attempts":0}]""")
        val loaded = EventQueueStore.load(context)
        assertEquals(1, loaded.size)
        assertEquals("", loaded[0].id)
        assertEquals("legacy", loaded[0].text)
    }
}
