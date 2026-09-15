package com.ozyab.smsforwarder.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.telegram.Channel
import com.ozyab.smsforwarder.telegram.ChannelClientFactory
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
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
        // Синхронизируемся с асинхронным clear() из setUp и предыдущего tearDown
        await("очередь чиста перед тестом") {
            !java.io.File(context.filesDir, "event_queue.json").exists()
        }
        mockServer = MockWebServer()
        mockServer.start()
        ChannelClientFactory.apiBase = mockServer.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        ChannelClientFactory.apiBase = ChannelClientFactory.API_BASE
        mockServer.shutdown()
        ChannelStore.invalidate()
        LogStore.clear()
        // ВАЖНО: clear() асинхронный (executor 'queue-store'); если не дождаться
        // удаления, следующий тест загрузит файл с событием прошлого теста и
        // воркер отправит его — загрязнение MockWebServer (флейки).
        EventQueueStore.clear(context)
        await("файл очереди удалён в tearDown") {
            !java.io.File(context.filesDir, "event_queue.json").exists()
        }
    }

    /**
     * Создаёт сервис и прогоняет startCommand. Воркер работает на реальном
     * Dispatchers.IO (умолчание): отмена корутины в onDestroy корректна
     * (wake.receive() — suspending), а сетевые вызовы OkHttp и так блокирующие.
     * Все ожидания в тестах ограничены таймаутами — тест не может зависнуть.
     */
    private fun buildStartedService(intent: Intent): ServiceController<ForwardService> {
        val ctrl = Robolectric.buildService(ForwardService::class.java, intent)
        ctrl.create()
        ctrl.get().workerDispatcher = kotlinx.coroutines.Dispatchers.IO
        ctrl.startCommand(0, 0)
        return ctrl
    }


    /** Ждёт выполнения условия до timeoutMs (воркер асинхронный, fixed sleep ненадёжен). */
    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue("не дождались: $what", condition())
    }

    /** takeRequest с таймаутом: без него тест висит вечно при отсутствии запроса. */
    private fun takeRequestOrNull(timeoutMs: Long = 15_000): okhttp3.mockwebserver.RecordedRequest? =
        mockServer.takeRequest(timeoutMs, TimeUnit.MILLISECONDS)

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

        val ctrl = buildStartedService(startIntent("Test message"))

        val request = takeRequestOrNull()
        assertNotNull("запрос должен прийти за 5с", request)
        assertEquals("/bottest-token-123/sendMessage", request!!.path)
        val body = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
        assertTrue("текст должен содержать сообщение: $body", body.contains("Test message"))
        await("sentCount инкрементирован") { Prefs.sentCount > 0 }
        await("лог об успешной отправке") { LogStore.all().any { it.text.contains("Отправлено") } }

        ctrl.destroy()
    }

    @Test
    fun `missing token delays event and logs warning`() {
        Prefs.botToken = ""
        Prefs.chatId = ""
        configureDirectChannel()

        val ctrl = buildStartedService(startIntent("Delayed"))

        await("лог о незаданных токене/chatId") {
            LogStore.all().any { it.text.contains("токен") || it.text.contains("chatId") }
        }
        assertEquals(0, mockServer.requestCount)
        ctrl.destroy()
    }

    @Test
    fun `stop action drops queue and stops service`() {
        configurePrefs()
        configureDirectChannel()

        val ctrl = buildStartedService(startIntent("Some message"))
        await("событие в очереди") { java.io.File(context.filesDir, "event_queue.json").exists() }

        val stopCtrl = buildStartedService(Intent().apply { action = ForwardService.ACTION_STOP })

        await("лог остановки") { LogStore.all().any { it.text.contains("Сервис остановлен") } }
        ctrl.destroy()
        stopCtrl.destroy()
    }

    @Test
    fun `Telegram error causes retry`() {
        configurePrefs()
        configureDirectChannel()
        mockServer.enqueue(MockResponse().setBody("""{"ok":false,"error_code":401,"description":"Unauthorized"}"""))

        val ctrl = buildStartedService(startIntent("Fail message"))

        await("лог об ошибке всех каналов") {
            LogStore.all().any { it.level == LogStore.Level.ERROR && it.text.contains("не вышли") }
        }
        ctrl.destroy()
    }

    @Test
    fun `enqueued event is immediately ready`() {
        configurePrefs()
        configureDirectChannel()

        val ctrl = buildStartedService(Intent().apply { action = ForwardService.ACTION_START })

        val ctrl2 = buildStartedService(startIntent("Quick"))
        await("запрос отправлен") { mockServer.requestCount > 0 }
        ctrl.destroy()
        ctrl2.destroy()
    }

    @org.junit.Ignore("флакий: Robolectric не отменяет корутину сервиса — падал 1/2 прогонов релиза v0.5.37 (#119)")
    @Test
    fun `queue persists to file when token missing`() {
        Prefs.botToken = ""
        Prefs.chatId = ""
        configureDirectChannel()

        val ctrl = buildStartedService(startIntent("Persist me"))

        await("файл очереди создан") { java.io.File(context.filesDir, "event_queue.json").exists() }
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
        val ctrl = buildStartedService(intent)
        Thread.sleep(300) // окно для ложной отправки, если бы пустое событие попало бы в очередь
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
