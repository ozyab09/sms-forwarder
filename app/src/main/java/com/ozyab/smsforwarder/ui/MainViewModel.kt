package com.ozyab.smsforwarder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ozyab.smsforwarder.telegram.Channel
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.telegram.ChannelSender
import com.ozyab.smsforwarder.telegram.TelegramClient
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Неизменяемое состояние экрана настроек.
 * UI подписывается на [MainViewModel.state] и рендерит его — логика отделена
 * от Activity и переживает поворот экрана.
 */
data class SettingsUiState(
    val botToken: String = "",
    val chatId: String = "",
    val smsEnabled: Boolean = true,
    val outgoingSmsEnabled: Boolean = false,
    val callsEnabled: Boolean = true,
    val incomingCallsEnabled: Boolean = false,
    val outgoingCallsEnabled: Boolean = false,
    val localNotificationsEnabled: Boolean = false,
    val templateSms: String = "",
    val templateOutgoingSms: String = "",
    val templateCall: String = "",
    val templateIncomingCall: String = "",
    val templateOutgoingCall: String = "",
    val templateNotification: String = "",
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: Int = 23 * 60,
    val quietHoursEnd: Int = 8 * 60,
    val testing: Boolean = false,
    val resolvingChatId: Boolean = false,
)

/** Результат проверки одного канала (для форматирования в UI). */
data class TestChannel(
    val name: String,
    val ok: Boolean,
    val botUsername: String?,
    val error: String?,
)

/** Одноразовые события экрана (тосты, диалоги, переходы). */
sealed interface UiEvent {
    data class ToastRes(val resId: Int) : UiEvent
    data class TestFinished(val channels: List<TestChannel>, val okCount: Int) : UiEvent
    data class ChatIdResolved(val id: Long) : UiEvent
    data class ChatIdFailed(val reason: String, val botUsername: String?) : UiEvent
}

/**
 * ViewModel экрана настроек (MVVM).
 *
 * Владеет состоянием [SettingsUiState] и операциями над настройками:
 * загрузка/сохранение, проверка связи по каналам, определение Chat ID.
 * Все корутины — в [viewModelScope], поэтому переживают пересоздание Activity.
 * Activity остаётся тонким «видом»: подписка на state/events + вью-биндинг.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // Инжектируемые точки для тестов (internal — виден из test-сетовета через friend module).
    // В проде — реальные реализации (сеть, каналы); в тестах подменяются фейками.
    internal var testAllImpl: suspend (String, List<Channel>) -> List<ChannelSender.ChannelTestResult> =
        { token, channels -> ChannelSender.testAll(token, channels) }
    internal var resolveChatIdImpl: suspend () -> TelegramClient.Result = {
        TelegramClient.resolveChatId()
    }
    internal var getBotUsernameImpl: suspend () -> String? = { TelegramClient.getBotUsername() }

    /** Диспетчер для сетевых вызовов — в тестах подменяется тестовым (один планировщик). */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Загружает текущие настройки из [Prefs] в состояние. */
    fun load() {
        _state.value = SettingsUiState(
            botToken = Prefs.botToken,
            chatId = Prefs.chatId,
            smsEnabled = Prefs.smsEnabled,
            outgoingSmsEnabled = Prefs.outgoingSmsEnabled,
            callsEnabled = Prefs.callsEnabled,
            incomingCallsEnabled = Prefs.incomingCallsEnabled,
            outgoingCallsEnabled = Prefs.outgoingCallsEnabled,
            localNotificationsEnabled = Prefs.localNotificationsEnabled,
            templateSms = Prefs.messageTemplateSms,
            templateOutgoingSms = Prefs.messageTemplateOutgoingSms,
            templateCall = Prefs.messageTemplateCall,
            templateIncomingCall = Prefs.messageTemplateIncomingCall,
            templateOutgoingCall = Prefs.messageTemplateOutgoingCall,
            templateNotification = Prefs.messageTemplateNotification,
            quietHoursEnabled = Prefs.quietHoursEnabled,
            quietHoursStart = Prefs.quietHoursStart,
            quietHoursEnd = Prefs.quietHoursEnd,
        )
    }

    // --- Изменения состояния (persist — отдельным save()) ---

    fun setBotToken(v: String) { _state.value = _state.value.copy(botToken = v) }
    fun setChatId(v: String) { _state.value = _state.value.copy(chatId = v) }
    fun setSmsEnabled(v: Boolean) { _state.value = _state.value.copy(smsEnabled = v) }
    fun setOutgoingSmsEnabled(v: Boolean) { _state.value = _state.value.copy(outgoingSmsEnabled = v) }
    fun setCallsEnabled(v: Boolean) { _state.value = _state.value.copy(callsEnabled = v) }
    fun setIncomingCallsEnabled(v: Boolean) { _state.value = _state.value.copy(incomingCallsEnabled = v) }
    fun setOutgoingCallsEnabled(v: Boolean) { _state.value = _state.value.copy(outgoingCallsEnabled = v) }
    fun setLocalNotificationsEnabled(v: Boolean) { _state.value = _state.value.copy(localNotificationsEnabled = v) }
    fun setTemplateSms(v: String) { _state.value = _state.value.copy(templateSms = v) }
    fun setTemplateOutgoingSms(v: String) { _state.value = _state.value.copy(templateOutgoingSms = v) }
    fun setTemplateCall(v: String) { _state.value = _state.value.copy(templateCall = v) }
    fun setTemplateIncomingCall(v: String) { _state.value = _state.value.copy(templateIncomingCall = v) }
    fun setTemplateOutgoingCall(v: String) { _state.value = _state.value.copy(templateOutgoingCall = v) }
    fun setTemplateNotification(v: String) { _state.value = _state.value.copy(templateNotification = v) }
    fun setQuietHoursEnabled(v: Boolean) { _state.value = _state.value.copy(quietHoursEnabled = v) }
    fun setQuietHoursStart(v: Int) { _state.value = _state.value.copy(quietHoursStart = v) }
    fun setQuietHoursEnd(v: Int) { _state.value = _state.value.copy(quietHoursEnd = v) }

    /** Сохраняет текущее состояние в [Prefs]. */
    fun save() {
        val s = _state.value
        Prefs.botToken = s.botToken.trim()
        Prefs.chatId = s.chatId.trim()
        Prefs.smsEnabled = s.smsEnabled
        Prefs.outgoingSmsEnabled = s.outgoingSmsEnabled
        Prefs.callsEnabled = s.callsEnabled
        Prefs.incomingCallsEnabled = s.incomingCallsEnabled
        Prefs.outgoingCallsEnabled = s.outgoingCallsEnabled
        Prefs.localNotificationsEnabled = s.localNotificationsEnabled
        Prefs.messageTemplateSms = s.templateSms
        Prefs.messageTemplateOutgoingSms = s.templateOutgoingSms
        Prefs.messageTemplateCall = s.templateCall
        Prefs.messageTemplateIncomingCall = s.templateIncomingCall
        Prefs.messageTemplateOutgoingCall = s.templateOutgoingCall
        Prefs.messageTemplateNotification = s.templateNotification
        Prefs.quietHoursEnabled = s.quietHoursEnabled
        Prefs.quietHoursStart = s.quietHoursStart
        Prefs.quietHoursEnd = s.quietHoursEnd
    }

    /**
     * Проверка связи со ВСЕМИ включёнными каналами (getMe, без отправки).
     * Сначала сохраняет настройки, затем тестирует. Результат — событием.
     */
    fun testConnection() {
        save()
        // Токен берём из state (актуальный ввод пользователя), а не из Prefs:
        // save() уже синхронизировал state → Prefs, а state не зависит от
        // secure-хранилища (в тестах Robolectric EncryptedSharedPreferences не пишет).
        val token = _state.value.botToken.trim()
        if (token.isBlank()) {
            emit(UiEvent.ToastRes(com.ozyab.smsforwarder.R.string.toast_enter_token))
            return
        }
        val channels = ChannelStore.enabled()
        if (channels.isEmpty()) {
            emit(UiEvent.ToastRes(com.ozyab.smsforwarder.R.string.test_no_channels))
            return
        }
        _state.value = _state.value.copy(testing = true)
        LogStore.info("Проверка связи через каналы: ${channels.joinToString { it.name }}")
        viewModelScope.launch {
            val results: List<ChannelSender.ChannelTestResult> = withContext(ioDispatcher) { testAllImpl(token, channels) }
            _state.value = _state.value.copy(testing = false)
            val mapped: List<TestChannel> = results.map {
                TestChannel(it.channel.name, it.ok, it.botUsername, it.error)
            }
            for (r in mapped) {
                if (r.ok) LogStore.ok("Тест «${r.name}» — бот @${r.botUsername ?: "?"} доступен")
                else LogStore.error("Тест «${r.name}» — ${r.error ?: "ошибка"}")
            }
            // Кэшируем username бота — история показывает, через какого бота ушло
            mapped.firstOrNull { it.ok }?.botUsername?.let { name ->
                if (name.isNotBlank() && name != Prefs.botUsername) Prefs.botUsername = name
            }
            emit(UiEvent.TestFinished(mapped, mapped.count { it.ok }))
        }
    }

    /** Определяет Chat ID через getMe; при ошибке — предлагает открыть бота. */
    fun resolveChatId() {
        save()
        // Токен из state (см. комментарий в testConnection).
        val token = _state.value.botToken.trim()
        if (token.isBlank()) {
            emit(UiEvent.ToastRes(com.ozyab.smsforwarder.R.string.pref_bot_token_hint))
            return
        }
        _state.value = _state.value.copy(resolvingChatId = true)
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { resolveChatIdImpl() }
            _state.value = _state.value.copy(resolvingChatId = false)
            when (result) {
                is TelegramClient.Result.Ok -> {
                    setChatId(result.messageId.toString())
                    Prefs.chatId = result.messageId.toString()
                    LogStore.ok("Chat ID определён: ${result.messageId}")
                    emit(UiEvent.ChatIdResolved(result.messageId))
                }
                is TelegramClient.Result.Err -> {
                    LogStore.error("Chat ID не определён: ${result.reason}")
                    val username = withContext(ioDispatcher) { getBotUsernameImpl() }
                    emit(UiEvent.ChatIdFailed(result.reason, username))
                }
            }
        }
    }

    private fun emit(e: UiEvent) {
        _events.tryEmit(e)
    }
}