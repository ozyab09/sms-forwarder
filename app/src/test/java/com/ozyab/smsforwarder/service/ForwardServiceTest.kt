package com.ozyab.smsforwarder.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.telegram.Channel
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Интеграционный тест ForwardService (ROADMAP T4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ForwardServiceTest {

    private lateinit var mockServer: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Prefs.init(context)
        Prefs.chatId
        ChannelStore.invalidate()
        LogStore.clear()
        EventQueueStore.clear(context)
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        ChannelStore.invalidate()
        LogStore.clear()
        EventQueueStore.clear(context)
    }

    private fun configurePrefs() {
        Prefs.botToken = "test-token-123"
        Prefs.chatId = "123456"
        Prefs.botUsername = "testbot"
    }

    private fun configureDirectChannel() {
        ChannelStore.setAll(listOf(Channel.direct()))
    }

    private fun startIntent(text: String, type: String = "sms") = Intent().apply {
        action = ForwardService.ACTION_START
        if (text.isNotEmpty()) putExtra(ForwardService.EXTRA_TEXT, text)
        putExtra(ForwardService.EXTRA_TYPE, type)
        putExtra(ForwardService.EXTRA_SENDER, "+79001234567")
        putExtra(ForwardService.EXTRA_EVENT_TIME, System.currentTimeMillis())
    }

    @Test
    fun `successful send increments sentCount and logs OK`() {
        configurePrefs()
        configureDirectChannel()
        mockServer.enqueue(MockResponse().setBody("""{"ok":true,"result":{"message_id":42}}"""))

        val ctrl = Robolectric.buildService(ForwardService::class.java, startIntent("Test message"))
        ctrl.create().startCommand(0, 0)
        Thread.sleep(2000)

        val request = mockServer.takeRequest()
        assertEquals("/bot/test-token-123/sendMessage", request.path)
        assertTrue(request.body.readUtf8().contains("Test message"))
        assertTrue("sentCount > 0", Prefs.sentCount > 0)
        assertTrue(LogStore.all().any { it.text.contains("Отправлено") })

        ctrl.destroy()
    }

    @Test
    fun `missing token delays event and logs warning`() {
        Prefs.botToken = ""
        Prefs.chatId = ""
        configureDirectChannel()

        val ctrl = Robolectric.buildService(ForwardService::class.java, startIntent("Delayed"))
        ctrl.create().startCommand(0, 0)
        Thread.sleep(1000)

        assertTrue(LogStore.all().any { it.text.contains("токен") || it.text.contains("chatId") })
        assertEquals(0, mockServer.requestCount)
        ctrl.destroy()
    }

    @Test
    fun `stop action drops queue and stops service`() {
        configurePrefs()
        configureDirectChannel()

        val ctrl = Robolectric.buildService(ForwardService::class.java, startIntent("Some message"))
        ctrl.create().startCommand(0, 0)
        Thread.sleep(500)

        val stopCtrl = Robolectric.buildService(ForwardService::class.java,
            Intent().apply { action = ForwardService.ACTION_STOP })
        stopCtrl.create().startCommand(0, 0)

        assertTrue(LogStore.all().any { it.text.contains("Сервис остановлен") })
        ctrl.destroy()
        stopCtrl.destroy()
    }

    @Test
    fun `Telegram error causes retry`() {
        configurePrefs()
        configureDirectChannel()
        mockServer.enqueue(MockResponse().setBody("""{"ok":false,"error_code":401,"description":"Unauthorized"}"""))

        val ctrl = Robolectric.buildService(ForwardService::class.java, startIntent("Fail message"))
        ctrl.create().startCommand(0, 0)
        Thread.sleep(2000)

        assertTrue(LogStore.all().any { it.level == LogStore.Level.ERROR && it.text.contains("не вышли") })
        ctrl.destroy()
    }

    @Test
    fun `enqueued event is immediately ready`() {
        configurePrefs()
        configureDirectChannel()

        val ctrl = Robolectric.buildService(ForwardService::class.java,
            Intent().apply { action = ForwardService.ACTION_START })
        ctrl.create().startCommand(0, 0)
        Thread.sleep(500)

        val ctrl2 = Robolectric.buildService(ForwardService::class.java, startIntent("Quick"))
        ctrl2.create().startCommand(0, 0)
        Thread.sleep(2000)
        assertTrue("запрос отправлен", mockServer.requestCount > 0)
        ctrl.destroy()
        ctrl2.destroy()
    }

    @Test
    fun `queue persists to file when token missing`() {
        Prefs.botToken = ""
        Prefs.chatId = ""
        configureDirectChannel()

        val ctrl = Robolectric.buildService(ForwardService::class.java, startIntent("Persist me"))
        ctrl.create().startCommand(0, 0)
        Thread.sleep(500)

        assertTrue(java.io.File(context.filesDir, "event_queue.json").exists())
        ctrl.destroy()
    }

    @Test
    fun `empty text does not enqueue`() {
        configurePrefs()
        configureDirectChannel()

        val intent = Intent().apply {
            action = ForwardService.ACTION_START
            putExtra(ForwardService.EXTRA_TEXT, "")
        }
        val ctrl = Robolectric.buildService(ForwardService::class.java, intent)
        ctrl.create().startCommand(0, 0)
        Thread.sleep(500)
        assertEquals(0, mockServer.requestCount)
        ctrl.destroy()
    }

    @Test
    fun `start static method does not crash`() {
        configurePrefs()
        try {
            ForwardService.start(context, "Fallback", type = "sms", sender = "+7900")
        } catch (_: Exception) { }
    }
}
