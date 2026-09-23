package com.ozyab.smsforwarder.ui

import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.telegram.Channel
import com.ozyab.smsforwarder.telegram.ChannelSender
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.telegram.TelegramClient
import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты MainViewModel — мозг экрана настроек (MVVM).
 *
 * Покрывают: загрузку/сохранение состояния, проверку связи по каналам
 * (в т.ч. отсутствие токена), определение Chat ID (успех/ошибка).
 *
 * Сеть и TelegramClient инжектируются (testAllImpl / resolveChatIdImpl /
 * getBotUsernameImpl), диспетчер корутин — тестовый, поэтому тесты
 * детерминированные и не ходят в сеть.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainViewModelTest {

    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        // Принудительно дожидаемся готовности Prefs (latch), чтобы аксессоры
        // не блокировались внутри теста.
        Prefs.chatId
        ChannelStore.invalidate()
        // Кэш каналов чистый; сбрасываем настройки к дефолтам.
        Prefs.chatId = ""
        Prefs.smsEnabled = true
        Prefs.callsEnabled = true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Ждёт применения асинхронной записи в DataStore (до 3 с). */
    private fun awaitPrefs(expected: String, actual: () -> String) {
        val deadline = System.currentTimeMillis() + 3_000
        while (actual() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(expected, actual())
    }

    private fun TestScope.buildVm(): MainViewModel {
        // Main и IO должны шарить планировщик runTest, иначе advanceUntilIdle
        // не прокрутит задачи viewModelScope (они на Main).
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = MainViewModel(ApplicationProvider.getApplicationContext())
        v.ioDispatcher = StandardTestDispatcher(testScheduler)
        return v
    }

    /**
     * Подписывается на события VM, выполняет [block], затем отписывается.
     *
     * Важно: сначала даём коллектору подписаться ([runCurrent]) — `UiEvent`
     * отдаются через `tryEmit` без replay, поэтому события, отправленные до
     * подписки, теряются. И обязательно отменяем коллектор ([Job.cancel]):
     * `events` — бесконечный SharedFlow, иначе runTest ждёт его таймаутом.
     */
    private fun TestScope.withEvents(v: MainViewModel, block: () -> Unit): List<UiEvent> {
        val out = mutableListOf<UiEvent>()
        val job = launch { v.events.collect { out.add(it) } }
        runCurrent()
        block()
        advanceUntilIdle()
        job.cancel()
        return out
    }

    // ===== load / save =====

    @Test
    fun `load reads settings from prefs into state`() {
        Prefs.chatId = "12345"
        Prefs.smsEnabled = false
        Prefs.callsEnabled = true
        Prefs.messageTemplateSms = "[{time}] {text}"
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 23 * 60
        Prefs.quietHoursEnd = 8 * 60

        vm = MainViewModel(ApplicationProvider.getApplicationContext())
        vm.load()

        val s = vm.state.value
        assertEquals("12345", s.chatId)
        assertFalse(s.smsEnabled)
        assertTrue(s.callsEnabled)
        assertEquals("[{time}] {text}", s.templateSms)
        assertTrue(s.quietHoursEnabled)
        assertEquals(23 * 60, s.quietHoursStart)
        assertEquals(8 * 60, s.quietHoursEnd)
    }

    @Test
    fun `setters update state immediately`() {
        vm = MainViewModel(ApplicationProvider.getApplicationContext())
        vm.setBotToken("tok")
        vm.setChatId("777")
        vm.setSmsEnabled(false)
        vm.setTemplateCall("📵 {number}")

        val s = vm.state.value
        assertEquals("tok", s.botToken)
        assertEquals("777", s.chatId)
        assertFalse(s.smsEnabled)
        assertEquals("📵 {number}", s.templateCall)
    }

    @Test
    fun `save persists state to prefs`() = runTest {
        vm = buildVm()
        vm.setChatId(" 4242 ")
        vm.setSmsEnabled(false)
        vm.setQuietHoursEnabled(true)
        vm.setQuietHoursStart(21 * 60)

        vm.save()

        // Prefs пишет в DataStore асинхронно и читает из кэша — ждём, пока
        // значение реально применится, иначе тест ловит промежуточное состояние.
        awaitPrefs("4242") { Prefs.chatId } // trim
        awaitPrefs("false") { Prefs.smsEnabled.toString() }
        awaitPrefs("true") { Prefs.quietHoursEnabled.toString() }
        awaitPrefs("1260") { Prefs.quietHoursStart.toString() }
    }

    // ===== testConnection =====

    @Test
    fun `test connection without token emits toast`() = runTest {
        vm = buildVm()

        val events = withEvents(vm) { vm.testConnection() }

        assertTrue(events.any { it is UiEvent.ToastRes && it.resId == R.string.toast_enter_token })
    }

    @Test
    fun `test connection success emits TestFinished with ok channels`() = runTest {
        vm = buildVm()
        vm.setBotToken("123:abc")
        vm.testAllImpl = { _, _ ->
            listOf(
                ChannelSender.ChannelTestResult(Channel.direct(), true, botUsername = "my_bot"),
                ChannelSender.ChannelTestResult(
                    Channel("p1", Channel.TYPE_HTTP, "Прокси", "h", 1, "", "", true),
                    false,
                    error = "timeout",
                ),
            )
        }
        lateinit var events: List<UiEvent>
        events = withEvents(vm) {
            vm.testConnection()
            // testing=true сразу после вызова (до ответа сети)
            assertTrue(vm.state.value.testing)
        }

        assertFalse(vm.state.value.testing)
        val finished = events.filterIsInstance<UiEvent.TestFinished>()
        assertEquals(1, finished.size)
        assertEquals(1, finished[0].okCount)
        assertEquals(2, finished[0].channels.size)
        assertEquals("my_bot", finished[0].channels[0].botUsername)
        assertTrue(finished[0].channels[1].error?.contains("timeout") == true)
    }

    @Test
    fun `test connection all failed emits TestFinished with zero ok`() = runTest {
        vm = buildVm()
        vm.setBotToken("123:abc")
        vm.testAllImpl = { _, channels ->
            channels.map { ChannelSender.ChannelTestResult(it, false, error = "down") }
        }

        val events = withEvents(vm) { vm.testConnection() }

        val finished = events.filterIsInstance<UiEvent.TestFinished>()
        assertEquals(1, finished.size)
        assertEquals(0, finished[0].okCount)
    }

    // ===== resolveChatId =====

    @Test
    fun `resolve chat id without token emits hint toast`() = runTest {
        vm = buildVm()

        val events = withEvents(vm) { vm.resolveChatId() }

        assertTrue(events.any { it is UiEvent.ToastRes && it.resId == R.string.pref_bot_token_hint })
    }

    @Test
    fun `resolve chat id success updates state and prefs`() = runTest {
        vm = buildVm()
        vm.setBotToken("123:abc")
        vm.resolveChatIdImpl = { TelegramClient.Result.Ok(987654321L) }

        lateinit var events: List<UiEvent>
        events = withEvents(vm) {
            vm.resolveChatId()
            assertTrue(vm.state.value.resolvingChatId)
        }

        assertFalse(vm.state.value.resolvingChatId)
        assertEquals("987654321", vm.state.value.chatId)
        assertEquals("987654321", Prefs.chatId)
        assertTrue(events.any { it is UiEvent.ChatIdResolved && it.id == 987654321L })
    }

    @Test
    fun `resolve chat id failure emits ChatIdFailed with bot username`() = runTest {
        vm = buildVm()
        vm.setBotToken("123:abc")
        vm.resolveChatIdImpl = { TelegramClient.Result.Err("нет сообщений") }
        vm.getBotUsernameImpl = { "my_bot" }

        val events = withEvents(vm) { vm.resolveChatId() }

        assertFalse(vm.state.value.resolvingChatId)
        val failed = events.filterIsInstance<UiEvent.ChatIdFailed>()
        assertEquals(1, failed.size)
        assertEquals("нет сообщений", failed[0].reason)
        assertEquals("my_bot", failed[0].botUsername)
    }

    // ===== история (MVVM, #139-A1) =====

    /**
     * Ждёт реального завершения загрузки истории: EventHistory внутри использует
     * настоящий Dispatchers.IO; резюме после IO постится в тестовый планировщик,
     * поэтому в цикле ожидания его нужно прокручивать (runCurrent), иначе
     * корутина загрузки навсегда застрянет с loading=true.
     */
    private fun TestScope.awaitHistory(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            if (condition()) return
            Thread.sleep(25)
        }
        testScheduler.runCurrent()
        assertTrue("не дождались истории", condition())
    }

    @Test
    fun `loadHistory returns recorded events newest first`() = runTest {
        vm = buildVm()
        // Запись напрямую в Room (тот же файл БД, что увидит VM)
        kotlinx.coroutines.runBlocking {
            com.ozyab.smsforwarder.history.EventHistory.record(
                ApplicationProvider.getApplicationContext(),
                sender = "+7900", body = "older", timestamp = 100,
                type = com.ozyab.smsforwarder.history.EventHistory.TYPE_SMS,
                status = com.ozyab.smsforwarder.history.EventHistory.STATUS_SENT,
                channelName = "direct", attempts = 1, formattedText = "older",
            )
            com.ozyab.smsforwarder.history.EventHistory.record(
                ApplicationProvider.getApplicationContext(),
                sender = "+7916", body = "newer", timestamp = 200,
                type = com.ozyab.smsforwarder.history.EventHistory.TYPE_MISSED,
                status = com.ozyab.smsforwarder.history.EventHistory.STATUS_DROPPED,
                channelName = null, attempts = 8, formattedText = "newer",
            )
        }

        vm.loadHistory(null, "")
        advanceUntilIdle() // запускает корутину загрузки до точки реального IO
        // Ждём конкретные записи: БД — синглтон на процесс, из параллельных
        // тестов в ней могут быть чужие строки (счётчик ненадёжен)
        awaitHistory { vm.history.value.events.any { it.sender == "+7916" } &&
                       vm.history.value.events.any { it.sender == "+7900" } }

        val events = vm.history.value.events
        // Наши записи на месте; "newest first" проверяем на своих же данных
        val ours = events.filter { it.sender == "+7900" || it.sender == "+7916" }
        assertEquals(2, ours.size)
        assertEquals("+7916", ours[0].sender) // newest first
        assertFalse(vm.history.value.loading)
    }

    @Test
    fun `loadHistory with callsOnly filters calls types`() = runTest {
        vm = buildVm()
        kotlinx.coroutines.runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            val eh = com.ozyab.smsforwarder.history.EventHistory
            eh.record(ctx, sender = "1", body = "sms", timestamp = 1, type = eh.TYPE_SMS, status = eh.STATUS_SENT, channelName = null, attempts = 1, formattedText = "")
            eh.record(ctx, sender = "2", body = "missed", timestamp = 2, type = eh.TYPE_MISSED, status = eh.STATUS_DROPPED, channelName = null, attempts = 1, formattedText = "")
            eh.record(ctx, sender = "3", body = "incoming", timestamp = 3, type = eh.TYPE_INCOMING, status = eh.STATUS_SENT, channelName = null, attempts = 1, formattedText = "")
        }

        vm.loadHistory(null, "", callsOnly = true)
        advanceUntilIdle()
        // Важно: Room-записи и запрос идут на реальном IO; резюме после IO
        // постится в тестовый планировщик — прокручиваем его в awaitHistory.
        // В отличие от первого теста, записи были сделаны из runBlocking ДО
        // loadHistory, поэтому ждать нужно только ответа запроса.
        awaitHistory { vm.history.value.events.any { it.type == "missed" } &&
                       vm.history.value.events.any { it.type == "incoming" } }

        val types = vm.history.value.events.map { it.type }.toSet()
        assertEquals(setOf("missed", "incoming"), types)
    }

    @Test
    fun `clearHistory empties the list`() = runTest {
        vm = buildVm()
        kotlinx.coroutines.runBlocking {
            com.ozyab.smsforwarder.history.EventHistory.record(
                ApplicationProvider.getApplicationContext(),
                sender = "x", body = "y", timestamp = 5,
                type = com.ozyab.smsforwarder.history.EventHistory.TYPE_SMS,
                status = com.ozyab.smsforwarder.history.EventHistory.STATUS_SENT,
                channelName = null, attempts = 1, formattedText = "y",
            )
        }
        vm.loadHistory(null, "")
        advanceUntilIdle()
        awaitHistory { vm.history.value.events.any { it.sender == "x" } }

        vm.clearHistory()
        awaitHistory { vm.history.value.events.none { it.sender == "x" } }

        assertTrue(vm.history.value.events.isEmpty())
    }
}