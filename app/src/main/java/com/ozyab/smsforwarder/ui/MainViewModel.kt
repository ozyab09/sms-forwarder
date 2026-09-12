package com.ozyab.smsforwarder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.telegram.ChannelSender
import com.ozyab.smsforwarder.telegram.TelegramClient
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
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
    val callsEnabled: Boolean = true,
    val templateSms: String = "",
    val templateCall: String = "",
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

    /** Загружает текущие настройки из [Prefs] в состояние. */
    fun load() {
        _state.value = SettingsUiState(
            botToken = Prefs.botToken,
            chatId = Prefs.chatId,
            smsEnabled = Prefs.smsEnabled,
            callsEnabled = Prefs.callsEnabled,
            templateSms = Prefs.messageTemplateSms,
            templateCall = Prefs.messageTemplateCall,
            quietHoursEnabled = Prefs.quietHoursEnabled,
            quietHoursStart = Prefs.quietHoursStart,
            quietHoursEnd = Prefs.quietHoursEnd,
        )
    }

    // --- Изменения состояния (persist — отдельным save()) ---

    fun setBotToken(v: String) { _state.value = _state.value.copy(botToken = v) }
    fun setChatId(v: String) { _state.value = _state.value.copy(chatId = v) }
    fun setSmsEnabled(v: Boolean) { _state.value = _state.value.copy(smsEnabled = v) }
    fun setCallsEnabled(v: Boolean) { _state.value = _state.value.copy(callsEnabled = v) }
    fun setTemplateSms(v: String) { _state.value = _state.value.copy(templateSms = v) }
    fun setTemplateCall(v: String) { _state.value = _state.value.copy(templateCall = v) }
    fun setQuietHoursEnabled(v: Boolean) { _state.value = _state.value.copy(quietHoursEnabled = v) }
    fun setQuietHoursStart(v: Int) { _state.value = _state.value.copy(quietHoursStart = v) }
    fun setQuietHoursEnd(v: Int) { _state.value = _state.value.copy(quietHoursEnd = v) }

    /** Сохраняет текущее состояние в [Prefs]. */
    fun save() {
        val s = _state.value
        Prefs.botToken = s.botToken.trim()
        Prefs.chatId = s.chatId.trim()
        Prefs.smsEnabled = s.smsEnabled
        Prefs.callsEnabled = s.callsEnabled
        Prefs.messageTemplateSms = s.templateSms
        Prefs.messageTemplateCall = s.templateCall
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
        val token = Prefs.botToken
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
            val results = withContext(Dispatchers.IO) { ChannelSender.testAll(token, channels) }
            _state.value = _state.value.copy(testing = false)
            val mapped = results.map {
                TestChannel(it.channel.name, it.ok, it.botUsername, it.error)
            }
            for (r in mapped) {
                if (r.ok) LogStore.ok("Тест «${r.name}» — бот @${r.botUsername ?: "?"} доступен")
                else LogStore.error("Тест «${r.name}» — ${r.error ?: "ошибка"}")
            }
            emit(UiEvent.TestFinished(mapped, mapped.count { it.ok }))
        }
    }

    /** Определяет Chat ID через getMe; при ошибке — предлагает открыть бота. */
    fun resolveChatId() {
        save()
        val token = Prefs.botToken
        if (token.isBlank()) {
            emit(UiEvent.ToastRes(com.ozyab.smsforwarder.R.string.pref_bot_token_hint))
            return
        }
        _state.value = _state.value.copy(resolvingChatId = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { TelegramClient.resolveChatId() }
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
                    val username = withContext(Dispatchers.IO) { TelegramClient.getBotUsername() }
                    emit(UiEvent.ChatIdFailed(result.reason, username))
                }
            }
        }
    }

    private fun emit(e: UiEvent) {
        _events.tryEmit(e)
    }
}