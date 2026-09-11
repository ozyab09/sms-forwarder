package com.ozyab.smsforwarder.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты Room-слоя истории событий (in-memory БД, Robolectric для Context).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventDaoTest {

    private lateinit var db: EventDatabase
    private lateinit var dao: EventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.eventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and recent returns newest first`() = runBlocking {
        dao.insert(EventEntity(sender = "A", body = "a", timestamp = 100, type = "sms", status = "sent", channelName = "direct", attempts = 1, formattedText = "a"))
        dao.insert(EventEntity(sender = "B", body = "b", timestamp = 200, type = "missed", status = "failed", channelName = null, attempts = 2, formattedText = "b"))

        val all = dao.recent(10)
        assertEquals(2, all.size)
        assertEquals("B", all[0].sender) // newest first
        assertEquals("A", all[1].sender)
    }

    @Test
    fun `search filters by type`() = runBlocking {
        dao.insert(EventEntity(sender = "A", body = "sms body", timestamp = 100, type = "sms", status = "sent", channelName = null, attempts = 1, formattedText = ""))
        dao.insert(EventEntity(sender = "B", body = "call body", timestamp = 200, type = "missed", status = "sent", channelName = null, attempts = 1, formattedText = ""))

        val sms = dao.search("sms", null, 10)
        assertEquals(1, sms.size)
        assertEquals("A", sms[0].sender)

        val missed = dao.search("missed", null, 10)
        assertEquals(1, missed.size)
        assertEquals("B", missed[0].sender)
    }

    @Test
    fun `search matches sender or body`() = runBlocking {
        dao.insert(EventEntity(sender = "+79161234567", body = "hello", timestamp = 100, type = "sms", status = "sent", channelName = null, attempts = 1, formattedText = ""))
        dao.insert(EventEntity(sender = "+79990000000", body = "bonjour", timestamp = 200, type = "sms", status = "sent", channelName = null, attempts = 1, formattedText = ""))

        val bySender = dao.search(null, "7916", 10)
        assertEquals(1, bySender.size)

        val byBody = dao.search(null, "bonjour", 10)
        assertEquals(1, byBody.size)
        assertEquals("+79990000000", byBody[0].sender)
    }

    @Test
    fun `limit caps result size`() = runBlocking {
        repeat(5) { i ->
            dao.insert(EventEntity(sender = "S$i", body = "b", timestamp = i.toLong(), type = "sms", status = "sent", channelName = null, attempts = 1, formattedText = ""))
        }
        val limited = dao.recent(3)
        assertEquals(3, limited.size)
    }

    @Test
    fun `clear removes everything`() = runBlocking {
        dao.insert(EventEntity(sender = "A", body = "a", timestamp = 100, type = "sms", status = "sent", channelName = null, attempts = 1, formattedText = ""))
        dao.clear()
        assertTrue(dao.recent(10).isEmpty())
    }

    @Test
    fun `deleteOlderThan removes old records`() = runBlocking {
        dao.insert(EventEntity(sender = "old", body = "x", timestamp = 100, type = "sms", status = "sent", channelName = null, attempts = 1, formattedText = ""))
        dao.insert(EventEntity(sender = "new", body = "y", timestamp = 500, type = "sms", status = "sent", channelName = null, attempts = 1, formattedText = ""))

        dao.deleteOlderThan(300)
        val rest = dao.recent(10)
        assertEquals(1, rest.size)
        assertEquals("new", rest[0].sender)
    }
}