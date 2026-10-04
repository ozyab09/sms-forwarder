package com.ozyab.smsforwarder.ui

import android.app.TimePickerDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ozyab.smsforwarder.BuildConfig
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.history.EventEntity
import com.ozyab.smsforwarder.history.EventHistory
import com.ozyab.smsforwarder.service.ForwardService
import com.ozyab.smsforwarder.telegram.Channel
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.update.UpdateChecker
import com.ozyab.smsforwarder.update.UpdateManager
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.QuietHours
import com.ozyab.smsforwarder.util.SettingsBackup
import com.ozyab.smsforwarder.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Главный экран: настройки (токен, chat ID, КАНАЛЫ отправки) + вкладка «Логи».
 *
 * Каналы: (1) «Без прокси» — всегда; (2) любое число прокси-каналов HTTP/SOCKS5.
 * Отправка каскадом: пробуем каждый канал по порядку, ретраи с удвоением интервала
 * (15с → 30с → … → 10 мин кап) суммарно ~15 минут — см. ForwardService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etToken: TextInputEditText
    private lateinit var etChatId: TextInputEditText
    private lateinit var btnGetMyId: MaterialButton
    private lateinit var btnTest: MaterialButton
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var swSms: SwitchMaterial
    private lateinit var swOutgoingSms: SwitchMaterial
    private lateinit var swCalls: SwitchMaterial
    private lateinit var swIncomingCalls: SwitchMaterial
    private lateinit var swOutgoingCalls: SwitchMaterial
    private lateinit var btnServiceToggle: MaterialButton

    // Каналы отправки (рендер и диалоги — в ChannelsPanel)
    private lateinit var channelsPanel: ChannelsPanel

    // Шаблоны сообщений
    private lateinit var swQuietHours: SwitchMaterial
    private lateinit var swQuietHoursAccumulate: SwitchMaterial
    private lateinit var layoutQuietTimes: View
    private lateinit var btnQuietStart: MaterialButton
    private lateinit var btnQuietEnd: MaterialButton

    // Батарея (#161)
    private lateinit var swBatteryFull: SwitchMaterial
    private lateinit var layoutBatteryFull: View
    private lateinit var tvBatteryFullThreshold: TextView
    private lateinit var sliderBatteryFull: com.google.android.material.slider.Slider
    private lateinit var swBatteryLow: SwitchMaterial
    private lateinit var layoutBatteryLow: View
    private lateinit var tvBatteryLowThreshold: TextView
    private lateinit var sliderBatteryLow: com.google.android.material.slider.Slider

    // Логи
    private lateinit var panelSettings: ScrollView
    private lateinit var panelLogs: View
    private lateinit var logsText: TextView
    private lateinit var etLogsSearch: TextInputEditText
    private lateinit var chipGroupLogs: com.google.android.material.chip.ChipGroup
    private var logsFilterLevel: LogStore.Level? = null // null = все

    // История (рендер и фильтры — в HistoryPanel)
    private lateinit var panelHistory: View
    private lateinit var historyPanel: HistoryPanel

    // О приложении
    private lateinit var panelAbout: View
    private lateinit var tvAboutVersion: TextView
    private lateinit var rgTheme: RadioGroup
    private lateinit var chipGroupAccent: com.google.android.material.chip.ChipGroup
    private lateinit var btnGithub: MaterialButton
    private lateinit var btnExportSettings: MaterialButton
    private lateinit var btnImportSettings: MaterialButton

    /** ViewModel: состояние настроек и операции (переживает поворот экрана). */
    private val viewModel: MainViewModel by viewModels()

    /** true — тест запущен кнопкой «Запустить» (без отдельного тоста об успехе). */
    private var startServiceAfterTest = false

    // Логи пишутся из фоновых потоков (сервис/ресиверы) — рендер только на main
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logListener: (LogStore.Entry) -> Unit = { mainHandler.post { renderLogs() } }

    // Запрос разрешений (SMS + телефон + контакты) — один раз при старте
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.READ_CALL_LOG] == false && Prefs.callsEnabled) {
            // Без READ_CALL_LOG номера пропущенных не приходят (EXTRA_INCOMING_NUMBER)
            LogStore.warn(getString(R.string.warn_call_log_permission))
            Toast.makeText(this, R.string.warn_call_log_permission, Toast.LENGTH_LONG).show()
        }
    }

    // Экспорт настроек (SAF: CreateDocument)
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = SettingsBackup.write(this, uri, SettingsBackup.export().toString())
            if (ok) {
                LogStore.ok(getString(R.string.toast_export_ok))
                Toast.makeText(this, R.string.toast_export_ok, Toast.LENGTH_LONG).show()
            } else {
                LogStore.error(getString(R.string.toast_export_failed))
                Toast.makeText(this, R.string.toast_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Импорт настроек (SAF: OpenDocument)
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importSettings(uri)
        }
    }

    // Экспорт логов (SAF: CreateDocument)
    private val exportLogsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val ok = writeLogsToUri(uri)
            if (ok) {
                LogStore.ok(getString(R.string.logs_export_ok))
                Toast.makeText(this, R.string.logs_export_ok, Toast.LENGTH_LONG).show()
            } else {
                LogStore.error(getString(R.string.logs_export_failed))
                Toast.makeText(this, R.string.logs_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Экспорт истории (SAF: CreateDocument) — CSV
    private val exportHistoryCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val ok = writeHistoryToUri(uri, true)
            if (ok) {
                LogStore.ok(getString(R.string.history_export_ok))
                Toast.makeText(this, R.string.history_export_ok, Toast.LENGTH_LONG).show()
            } else {
                LogStore.error(getString(R.string.history_export_failed))
                Toast.makeText(this, R.string.history_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Экспорт истории (SAF: CreateDocument) — JSON
    private val exportHistoryJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = writeHistoryToUri(uri, false)
            if (ok) {
                LogStore.ok(getString(R.string.history_export_ok))
                Toast.makeText(this, R.string.history_export_ok, Toast.LENGTH_LONG).show()
            } else {
                LogStore.error(getString(R.string.history_export_failed))
                Toast.makeText(this, R.string.history_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        // Тема (светлая/тёмная/по системе) применяется до построения UI
        ThemeManager.apply(this)
        setContentView(R.layout.activity_main)

        bindViews()
        loadPrefs()
        loadAbout()
        setupActions()
        channelsPanel.renderChannels()
        setupBottomNav()
        collectViewModel()

        requestNeededPermissions()
        UpdateManager.checkForUpdates(this, lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        channelsPanel.renderChannels()
        historyPanel.onResume()
        renderLogs()
    }

    override fun onPause() {
        super.onPause()
        // Сохраняем ввод (токен/chatId) при уходе с экрана, а не только по кнопкам
        savePrefs()
    }

    override fun onStart() {
        super.onStart()
        LogStore.addListener(logListener)
    }

    override fun onStop() {
        super.onStop()
        LogStore.removeListener(logListener)
    }

    private fun bindViews() {
        etToken = findViewById(R.id.et_bot_token)
        etChatId = findViewById(R.id.et_chat_id)
        btnGetMyId = findViewById(R.id.btn_get_my_id)
        btnTest = findViewById(R.id.btn_test)
        swSms = findViewById(R.id.sw_sms)
        swOutgoingSms = findViewById(R.id.sw_outgoing_sms)
        swCalls = findViewById(R.id.sw_calls)
        swIncomingCalls = findViewById(R.id.sw_incoming_calls)
        swOutgoingCalls = findViewById(R.id.sw_outgoing_calls)
        btnServiceToggle = findViewById(R.id.btn_service_toggle)

        channelsPanel = ChannelsPanel(this, findViewById(R.id.channels_container))
        findViewById<MaterialButton>(R.id.btn_add_proxy).setOnClickListener { channelsPanel.showProxyDialog(null) }

        swQuietHours = findViewById(R.id.sw_quiet_hours)
        swQuietHoursAccumulate = findViewById(R.id.sw_quiet_hours_accumulate)
        layoutQuietTimes = findViewById(R.id.layout_quiet_hours_times)
        btnQuietStart = findViewById(R.id.btn_quiet_start)
        btnQuietEnd = findViewById(R.id.btn_quiet_end)

        swBatteryFull = findViewById(R.id.sw_battery_full)
        layoutBatteryFull = findViewById(R.id.layout_battery_full)
        tvBatteryFullThreshold = findViewById(R.id.tv_battery_full_threshold)
        sliderBatteryFull = findViewById(R.id.slider_battery_full)
        swBatteryLow = findViewById(R.id.sw_battery_low)
        layoutBatteryLow = findViewById(R.id.layout_battery_low)
        tvBatteryLowThreshold = findViewById(R.id.tv_battery_low_threshold)
        sliderBatteryLow = findViewById(R.id.slider_battery_low)

        panelSettings = findViewById(R.id.panel_settings)
        panelLogs = findViewById(R.id.panel_logs)
        logsText = findViewById(R.id.logs_text)
        etLogsSearch = findViewById(R.id.et_logs_search)
        chipGroupLogs = findViewById(R.id.chip_group_logs)
        findViewById<MaterialButton>(R.id.btn_clear_logs).setOnClickListener {
            LogStore.clear()
            renderLogs()
        }
        etLogsSearch.addTextChangedListener(textWatcher { renderLogs() })
        chipGroupLogs.setOnCheckedStateChangeListener { _, _ ->
            logsFilterLevel = when (chipGroupLogs.checkedChipId) {
                R.id.chip_logs_ok -> LogStore.Level.OK
                R.id.chip_logs_warn -> LogStore.Level.WARN
                R.id.chip_logs_error -> LogStore.Level.ERROR
                R.id.chip_logs_info -> LogStore.Level.INFO
                else -> null // все
            }
            renderLogs()
        }
        findViewById<MaterialButton>(R.id.btn_export_logs).setOnClickListener {
            exportLogsLauncher.launch("sms_forwarder_logs.txt")
        }

        // История (панель владеет фильтрами, поиском и очисткой)
        panelHistory = findViewById(R.id.panel_history)
        historyPanel = HistoryPanel(this, viewModel, findViewById(R.id.panel_history) as LinearLayout)

        btnCheckUpdate = findViewById(R.id.btn_check_update)
        panelAbout = findViewById(R.id.panel_about)
        tvAboutVersion = findViewById(R.id.tv_about_version)
        rgTheme = findViewById(R.id.rg_theme)
        chipGroupAccent = findViewById(R.id.chipGroupAccent)
        btnGithub = findViewById(R.id.btn_github)
        btnExportSettings = findViewById(R.id.btn_export_settings)
        btnImportSettings = findViewById(R.id.btn_import_settings)
    }

    private fun loadPrefs() {
        viewModel.load()
        renderPrefs()
    }

    /** Рендер состояния настроек из ViewModel в виджеты. */
    private fun renderPrefs() {
        val s = viewModel.state.value
        etToken.setText(s.botToken)
        etChatId.setText(s.chatId)
        swSms.isChecked = s.smsEnabled
        swOutgoingSms.isChecked = s.outgoingSmsEnabled
        swCalls.isChecked = s.callsEnabled
        swIncomingCalls.isChecked = s.incomingCallsEnabled
        swOutgoingCalls.isChecked = s.outgoingCallsEnabled

        // Тихие часы
        renderServiceToggle(s)
        swQuietHours.isChecked = s.quietHoursEnabled
        swQuietHoursAccumulate.isChecked = s.quietHoursAccumulate
        layoutQuietTimes.visibility = if (s.quietHoursEnabled) View.VISIBLE else View.GONE
        btnQuietStart.text = formatTime(s.quietHoursStart)
        btnQuietEnd.text = formatTime(s.quietHoursEnd)

        // Батарея (#161)
        renderBattery(s)
    }

    private fun renderBattery(s: SettingsUiState) {
        swBatteryFull.isChecked = s.batteryFullEnabled
        layoutBatteryFull.visibility = if (s.batteryFullEnabled) View.VISIBLE else View.GONE
        tvBatteryFullThreshold.text = getString(R.string.pref_battery_full_threshold, s.batteryFullThreshold)
        if (sliderBatteryFull.value != s.batteryFullThreshold.toFloat()) {
            sliderBatteryFull.value = s.batteryFullThreshold.toFloat()
        }
        swBatteryLow.isChecked = s.batteryLowEnabled
        layoutBatteryLow.visibility = if (s.batteryLowEnabled) View.VISIBLE else View.GONE
        tvBatteryLowThreshold.text = getString(R.string.pref_battery_low_threshold, s.batteryLowThreshold)
        if (sliderBatteryLow.value != s.batteryLowThreshold.toFloat()) {
            sliderBatteryLow.value = s.batteryLowThreshold.toFloat()
        }
    }

    /**
     * Подписка на ViewModel: состояние (кнопки «занято») и одноразовые
     * события (тосты, диалоги проверки каналов, определение Chat ID).
     */
    private fun collectViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { s ->
                        renderServiceToggle(s)
                        btnTest.isEnabled = !s.testing
                        btnTest.text = getString(if (s.testing) R.string.testing else R.string.btn_test_connection)
                        btnGetMyId.isEnabled = !s.resolvingChatId
                    }
                }
                launch {
                    viewModel.events.collect { e -> handleUiEvent(e) }
                }
                launch {
                    // История: рендер по состоянию из ViewModel (MVVM, без гонок)
                    viewModel.history.collect { h ->
                        historyPanel.renderHistoryList(h.events)
                        historyPanel.renderStats(h.stats, h.queueDroppedAfterAttempts, h.queueDroppedOverflow, h.queueSize)
                    }
                }
            }
        }
    }

    private fun handleUiEvent(e: UiEvent) {
        when (e) {
            is UiEvent.ToastRes -> Toast.makeText(this, e.resId, Toast.LENGTH_LONG).show()
            is UiEvent.TestFinished -> showTestResult(e.channels, e.okCount, toastOnSuccess = !startServiceAfterTest)
            is UiEvent.ChatIdResolved ->
                Toast.makeText(this, getString(R.string.toast_chat_id_resolved, e.id), Toast.LENGTH_LONG).show()
            is UiEvent.ChatIdFailed -> {
                // Ссылку на бота показываем текстом, но НЕ открываем автоматически —
                // неожиданный уход из приложения раздражает.
                val botHint = e.botUsername?.let { getString(R.string.toast_chat_id_failed_bot, it) } ?: ""
                Toast.makeText(this, getString(R.string.toast_chat_id_failed, e.reason, botHint), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Диалог с результатами проверки каналов (общий для «Тест» и «Запуск»). */
    private fun showTestResult(channels: List<TestChannel>, okCount: Int, toastOnSuccess: Boolean) {
        val summary = buildString {
            for (r in channels) {
                if (r.ok) {
                    val bot = r.botUsername ?: "?"
                    appendLine(getString(R.string.test_channel_ok, r.name, bot))
                    if (toastOnSuccess) {
                        Toast.makeText(this@MainActivity, getString(R.string.test_connection_ok, bot, r.name), Toast.LENGTH_LONG).show()
                    }
                } else {
                    appendLine(getString(R.string.test_channel_fail, r.name, r.error ?: getString(R.string.test_error_unknown)))
                }
            }
        }
        if (okCount == 0) {
            Toast.makeText(this, R.string.test_all_none, Toast.LENGTH_LONG).show()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.test_dialog_title)
            .setMessage(summary)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun setupActions() {
        btnTest.setOnClickListener {
            startServiceAfterTest = false
            viewModel.testConnection()
        }
        btnCheckUpdate.setOnClickListener { UpdateManager.checkForUpdates(this, lifecycleScope, force = true) }
        btnGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.PROJECT_URL)))
        }
        btnExportSettings.setOnClickListener {
            viewModel.save()
            exportLauncher.launch(SettingsBackup.defaultFileName())
        }
        btnImportSettings.setOnClickListener {
            viewModel.save()
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        // Ввод токена/chatId синхронизируем в состояние ViewModel
        etToken.addTextChangedListener(textWatcher { viewModel.setBotToken(etToken.text?.toString().orEmpty()) })
        etChatId.addTextChangedListener(textWatcher { viewModel.setChatId(etChatId.text?.toString().orEmpty()) })
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_theme_light -> ThemeManager.MODE_LIGHT
                R.id.rb_theme_dark -> ThemeManager.MODE_DARK
                else -> ThemeManager.MODE_SYSTEM
            }
            ThemeManager.setAndApply(this, mode)
            LogStore.info("Тема: $mode")
        }
        chipGroupAccent.setOnCheckedStateChangeListener { _, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { findViewById<com.google.android.material.chip.Chip>(it) }
            val accent = chip?.tag?.toString() ?: ThemeManager.ACCENT_TEAL
            ThemeManager.setAccentAndApply(this, accent)
            LogStore.info("Акцент: $accent")
        }
        swQuietHours.setOnCheckedChangeListener { _, checked ->
            layoutQuietTimes.visibility = if (checked) View.VISIBLE else View.GONE
            viewModel.setQuietHoursEnabled(checked)
        }
        swQuietHoursAccumulate.setOnCheckedChangeListener { _, checked ->
            viewModel.setQuietHoursAccumulate(checked)
            viewModel.save()
        }
        // Батарея (#161): тумблеры + слайдеры порогов (80..100 / 0..30)
        swBatteryFull.setOnCheckedChangeListener { _, checked ->
            layoutBatteryFull.visibility = if (checked) View.VISIBLE else View.GONE
            viewModel.setBatteryFullEnabled(checked)
            viewModel.save()
        }
        swBatteryLow.setOnCheckedChangeListener { _, checked ->
            layoutBatteryLow.visibility = if (checked) View.VISIBLE else View.GONE
            viewModel.setBatteryLowEnabled(checked)
            viewModel.save()
        }
        sliderBatteryFull.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setBatteryFullThreshold(value.toInt())
            tvBatteryFullThreshold.text = getString(R.string.pref_battery_full_threshold, value.toInt())
            viewModel.save()
        }
        sliderBatteryLow.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setBatteryLowThreshold(value.toInt())
            tvBatteryLowThreshold.text = getString(R.string.pref_battery_low_threshold, value.toInt())
            viewModel.save()
        }
        btnQuietStart.setOnClickListener { showTimePicker(isStart = true) }
        btnQuietEnd.setOnClickListener { showTimePicker(isStart = false) }
        btnGetMyId.setOnClickListener { viewModel.resolveChatId() }
        swSms.setOnCheckedChangeListener { _, v -> viewModel.setSmsEnabled(v) }
        swOutgoingSms.setOnCheckedChangeListener { _, v -> viewModel.setOutgoingSmsEnabled(v) }
        swCalls.setOnCheckedChangeListener { _, v -> viewModel.setCallsEnabled(v) }
        swIncomingCalls.setOnCheckedChangeListener { _, v -> viewModel.setIncomingCallsEnabled(v) }
        swOutgoingCalls.setOnCheckedChangeListener { _, v -> viewModel.setOutgoingCallsEnabled(v) }
        btnServiceToggle.setOnClickListener {
            // Одна кнопка: «Запустить» ↔ «Остановить» по факту forwardingEnabled
            val willRun = !viewModel.state.value.forwardingEnabled
            viewModel.setForwardingEnabled(willRun)
            if (willRun) {
                viewModel.save()
                if (!Prefs.isConfigured()) {
                    viewModel.setForwardingEnabled(false)
                    Toast.makeText(this, R.string.toast_enter_token_and_chatid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // Сервис запускаем сразу; затем проверяем связь по каналам (без отправки)
                startServiceAfterTest = true
                ForwardService.start(this)
                Toast.makeText(this, R.string.status_running, Toast.LENGTH_SHORT).show()
                requestBatteryExemption()
                viewModel.testConnection()
            } else {
                // Мастер-выключатель (#151-аудит-3): «Стоп» = пересылка остановлена
                // до следующего «Запустить» — новые SMS/звонки не возобновят её
                ForwardService.stop(this)
                Toast.makeText(this, R.string.status_stopped, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBottomNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            showPanelFor(item.itemId)
            true
        }
        // post {} — после onRestoreInstanceState(), когда BottomNav уже восстановил selectedItemId
        nav.post {
            showPanelFor(nav.selectedItemId)
        }
    }

    private fun showPanelFor(itemId: Int) {
        panelSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        panelLogs.visibility = if (itemId == R.id.nav_logs) View.VISIBLE else View.GONE
        panelHistory.visibility = if (itemId == R.id.nav_history) View.VISIBLE else View.GONE
        panelAbout.visibility = if (itemId == R.id.nav_about) View.VISIBLE else View.GONE
        when (itemId) {
            R.id.nav_logs -> renderLogs()
            R.id.nav_history -> historyPanel.renderHistory()
        }
    }

    private fun savePrefs() {
        viewModel.save()
    }


    private fun renderLogs() {
        val query = etLogsSearch.text?.toString()?.trim().orEmpty()
        val entries = LogStore.all().filter { e ->
            val matchesLevel = logsFilterLevel == null || e.level == logsFilterLevel
            val matchesText = query.isEmpty() || e.text.contains(query, ignoreCase = true)
            matchesLevel && matchesText
        }
        val sb = StringBuilder()
        for (e in entries) {
            val icon = when (e.level) {
                LogStore.Level.OK -> "✅"
                LogStore.Level.WARN -> "⚠️"
                LogStore.Level.ERROR -> "❌"
                LogStore.Level.INFO -> "ℹ️"
            }
            sb.append(e.time).append("  ").append(icon).append(' ').append(e.text).append('\n')
        }
        if (sb.isEmpty()) sb.append(getString(R.string.logs_empty))
        logsText.text = sb.toString()
    }

    /**
     * Запрос истории через ViewModel: предыдущий запрос отменяется — гонки
     * фильтров нет (медленный запрос не может перезаписать быстрый).
     * Рендер — по подписке на viewModel.history (collectViewModel).
     */


    private fun writeLogsToUri(uri: android.net.Uri): Boolean {
        return try {
            val sb = StringBuilder()
            for (e in LogStore.all()) {
                val level = when (e.level) {
                    LogStore.Level.OK -> "OK"
                    LogStore.Level.WARN -> "WARN"
                    LogStore.Level.ERROR -> "ERROR"
                    LogStore.Level.INFO -> "INFO"
                }
                sb.appendLine("${e.time}  [$level] ${e.text}")
            }
            // openOutputStream может вернуть null (нет обработчика) — это НЕ успех
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(sb.toString().toByteArray())
            } ?: return false
            true
        } catch (e: Exception) {
            LogStore.error("Ошибка экспорта логов: ${e.message}")
            false
        }
    }

    private fun writeHistoryToUri(uri: android.net.Uri, isCsv: Boolean): Boolean {
        return try {
            // Получаем события через EventHistory (нужен контекст)
            // Используем runBlocking для IO операции
            val events = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val filter = viewModel.historyFilter
                    com.ozyab.smsforwarder.history.EventHistory.search(
                        this@MainActivity,
                        filter.type,
                        filter.query.takeIf { it.isNotBlank() },
                        com.ozyab.smsforwarder.history.EventHistory.MAX_EVENTS
                    )
                }
            }
            if (isCsv) {
                val sb = StringBuilder()
                for (e in events) {
                    sb.append(e.sender).append(',')
                    sb.append(escapeCsv(e.body)).append(',')
                    sb.append(e.timestamp).append(',')
                    sb.append(e.type).append(',')
                    sb.append(e.status).append(',')
                    sb.append(escapeCsv(e.channelName ?: "")).append(',')
                    sb.append(e.attempts).append(',')
                    sb.append(escapeCsv(e.chatId ?: "")).append(',')
                    sb.append(escapeCsv(e.botUsername ?: "")).append(',')
                    sb.append(escapeCsv(e.formattedText)).append('\n')
                }
                // Header
                val header = "sender,body,timestamp,type,status,channelName,attempts,chatId,botUsername,formattedText\n"
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(header.toByteArray())
                    out.write(sb.toString().toByteArray())
                } ?: return false
            } else {
                // JSON format
                val arr = org.json.JSONArray()
                for (e in events) {
                    arr.put(
                        org.json.JSONObject()
                            .put("sender", e.sender)
                            .put("body", e.body)
                            .put("timestamp", e.timestamp)
                            .put("type", e.type)
                            .put("status", e.status)
                            .put("channelName", e.channelName)
                            .put("attempts", e.attempts)
                            .put("chatId", e.chatId)
                            .put("botUsername", e.botUsername)
                            .put("formattedText", e.formattedText)
                    )
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(arr.toString(2).toByteArray())
                } ?: return false
            }
            true
        } catch (e: Exception) {
            LogStore.error("Ошибка экспорта истории: ${e.message}")
            false
        }
    }

    private fun escapeCsv(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }

    /** Экспорт истории — диалог выбора формата и запуск SAF. */
    fun exportHistory() {
        val formats = arrayOf("CSV", "JSON")
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_export_history)
            .setSingleChoiceItems(formats, 0) { _, which ->
                val isCsv = which == 0
                val ext = if (isCsv) "csv" else "json"
                val fileName = "sms_forwarder_history-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.$ext"
                if (isCsv) {
                    exportHistoryCsvLauncher.launch(fileName)
                } else {
                    exportHistoryJsonLauncher.launch(fileName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun textWatcher(onChange: () -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onChange()
        }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )
        // POST_NOTIFICATIONS при старте НЕ запрашиваем (уведомление сервиса и так
        // невидимое). Разрешение запрашивается при включении локальных уведомлений
        // (см. swLocalNotifications listener) — оно нужно именно этой фиче.
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** Просим исключение из оптимизации батареи (Doze) — чтобы службу не убивали. */
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                // не критично — можно через настройки вручную
            }
        }
    }

    /**
     * Кнопка «Запустить/Остановить» + подпись состояния: рендер по факту
     * forwardingEnabled (мастер-выключатель пересылки).
     */
    private fun renderServiceToggle(s: SettingsUiState) {
        btnServiceToggle.text = getString(if (s.forwardingEnabled) R.string.btn_stop else R.string.btn_start)
    }

    /** Заполнение панели «О приложении»: версия и выбранная тема. */
    private fun loadAbout() {
        tvAboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        val checked = when (Prefs.themeMode) {
            ThemeManager.MODE_LIGHT -> R.id.rb_theme_light
            ThemeManager.MODE_DARK -> R.id.rb_theme_dark
            else -> R.id.rb_theme_system
        }
        rgTheme.check(checked)
        // Акцентный цвет
        val accentChipId = when (Prefs.accentColor) {
            ThemeManager.ACCENT_GREEN -> R.id.chip_accent_green
            ThemeManager.ACCENT_RED -> R.id.chip_accent_red
            ThemeManager.ACCENT_BLUE -> R.id.chip_accent_blue
            ThemeManager.ACCENT_PURPLE -> R.id.chip_accent_purple
            ThemeManager.ACCENT_ORANGE -> R.id.chip_accent_orange
            ThemeManager.ACCENT_GREY -> R.id.chip_accent_grey
            else -> R.id.chip_accent_teal
        }
        chipGroupAccent.check(accentChipId)
    }

    /** Читает файл и применяет настройки; обновляет UI. */
    private fun importSettings(uri: android.net.Uri) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { SettingsBackup.read(this@MainActivity, uri) }
            if (json == null) {
                LogStore.error(getString(R.string.toast_import_failed))
                Toast.makeText(this@MainActivity, R.string.toast_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = try {
                withContext(Dispatchers.IO) { SettingsBackup.import(JSONObject(json)) }
            } catch (e: Exception) {
                LogStore.error("Импорт: ${e.message}")
                Toast.makeText(this@MainActivity, R.string.toast_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            LogStore.ok(
                getString(R.string.toast_import_ok) +
                    " — каналов: ${result.channelsImported}" +
                    if (result.tokenKept) ", токен сохранён" else ", токен не задан"
            )
            Toast.makeText(this@MainActivity, R.string.toast_import_ok, Toast.LENGTH_LONG).show()
            // Обновляем UI после импорта
            loadPrefs()
            channelsPanel.renderChannels()
            loadAbout()
            // Тема могла измениться — применяем глобально (Activity пересоздаётся автоматически)
            ThemeManager.apply(this@MainActivity)
        }
    }

    /** Пикер времени для начала/конца тихих часов. */
    private fun showTimePicker(isStart: Boolean) {
        val current = if (isStart) viewModel.state.value.quietHoursStart else viewModel.state.value.quietHoursEnd
        val hour = current / 60
        val minute = current % 60
        TimePickerDialog(
            this,
            { _, h, m ->
                val mins = QuietHours.toMinutes(h, m)
                if (isStart) {
                    viewModel.setQuietHoursStart(mins)
                    btnQuietStart.text = formatTime(mins)
                } else {
                    viewModel.setQuietHoursEnd(mins)
                    btnQuietEnd.text = formatTime(mins)
                }
                viewModel.save()
                // Равные start/end = пустой интервал (тишина не работает) — предупреждаем
                val s = viewModel.state.value
                if (s.quietHoursStart == s.quietHoursEnd) {
                    Toast.makeText(this, R.string.quiet_hours_equal_warning, Toast.LENGTH_LONG).show()
                }
            },
            hour, minute, true
        ).show()
    }

    /** Минуты от полуночи → «ЧЧ:ММ». */
    private fun formatTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format(java.util.Locale.US, "%02d:%02d", h, m)
    }
}