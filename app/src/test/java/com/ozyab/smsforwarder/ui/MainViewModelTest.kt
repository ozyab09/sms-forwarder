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

    private fun TestScope.buildVm(): MainViewModel {
        // Main и IO должны шарить планировщик runTest, иначе advanceUntilIdle
        // не прокрутит задачи viewModelScope (они на Main).
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = MainViewModel(ApplicationProvider.getApplicationContext())
        v.ioDispatcher = StandardTestDispatcher(testScheduler)
        return v
    }

    /** Собирает события VM в список (для проверки одноразовых UiEvent). */
    private fun TestScope.collectEvents(v: MainViewModel): MutableList<UiEvent> {
        val out = mutableListOf<UiEvent>()
        launch { v.events.collect { out.add(it) } }
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

        assertEquals("4242", Prefs.chatId) // trim
        assertFalse(Prefs.smsEnabled)
        assertTrue(Prefs.quietHoursEnabled)
        assertEquals(21 * 60, Prefs.quietHoursStart)
    }

    // ===== testConnection =====

    @Test
    fun `test connection without token emits toast`() = runTest {
        vm = buildVm()
        val events = collectEvents(vm)

        vm.testConnection()
        advanceUntilIdle()

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
        val events = collectEvents(vm)

        vm.testConnection()
        // testing=true сразу после вызова (до ответа сети)
        assertTrue(vm.state.value.testing)
        advanceUntilIdle()

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
        val events = collectEvents(vm)

        vm.testConnection()
        advanceUntilIdle()

        val finished = events.filterIsInstance<UiEvent.TestFinished>()
        assertEquals(1, finished.size)
        assertEquals(0, finished[0].okCount)
    }

    // ===== resolveChatId =====

    @Test
    fun `resolve chat id without token emits hint toast`() = runTest {
        vm = buildVm()
        val events = collectEvents(vm)

        vm.resolveChatId()
        advanceUntilIdle()

        assertTrue(events.any { it is UiEvent.ToastRes && it.resId == R.string.pref_bot_token_hint })
    }

    @Test
    fun `resolve chat id success updates state and prefs`() = runTest {
        vm = buildVm()
        vm.setBotToken("123:abc")
        vm.resolveChatIdImpl = { TelegramClient.Result.Ok(987654321L) }
        val events = collectEvents(vm)

        vm.resolveChatId()
        assertTrue(vm.state.value.resolvingChatId)
        advanceUntilIdle()

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
        val events = collectEvents(vm)

        vm.resolveChatId()
        advanceUntilIdle()

        assertFalse(vm.state.value.resolvingChatId)
        val failed = events.filterIsInstance<UiEvent.ChatIdFailed>()
        assertEquals(1, failed.size)
        assertEquals("нет сообщений", failed[0].reason)
        assertEquals("my_bot", failed[0].botUsername)
    }
}