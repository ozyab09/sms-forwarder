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
import com.ozyab.smsforwarder.util.TemplateFormatter
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
    private lateinit var swLocalNotifications: SwitchMaterial
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    // Каналы отправки
    private lateinit var channelsContainer: LinearLayout

    // Шаблоны сообщений
    private lateinit var etTemplateSms: TextInputEditText
    private lateinit var etTemplateOutgoingSms: TextInputEditText
    private lateinit var etTemplateCall: TextInputEditText
    private lateinit var etTemplateIncomingCall: TextInputEditText
    private lateinit var etTemplateOutgoingCall: TextInputEditText
    private lateinit var swQuietHours: SwitchMaterial
    private lateinit var layoutQuietTimes: View
    private lateinit var btnQuietStart: MaterialButton
    private lateinit var btnQuietEnd: MaterialButton

    // Логи
    private lateinit var panelSettings: ScrollView
    private lateinit var panelLogs: View
    private lateinit var logsText: TextView
    private lateinit var etLogsSearch: TextInputEditText
    private lateinit var chipGroupLogs: com.google.android.material.chip.ChipGroup
    private var logsFilterLevel: LogStore.Level? = null // null = все

    // История
    private lateinit var panelHistory: View
    private lateinit var historyList: LinearLayout
    private lateinit var etHistorySearch: TextInputEditText
    private lateinit var chipGroupHistory: com.google.android.material.chip.ChipGroup

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

    // Debounce поиска по истории (запрос к Room не на каждый символ)
    private val historySearchHandler = Handler(Looper.getMainLooper())
    private val historySearchRunnable = Runnable { renderHistory() }

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

    // POST_NOTIFICATIONS (Android 13+): запрос при включении локальных уведомлений
    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            LogStore.warn(getString(R.string.warn_post_notifications))
            Toast.makeText(this, R.string.warn_post_notifications, Toast.LENGTH_LONG).show()
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
        renderChannels()
        setupBottomNav()
        collectViewModel()

        requestNeededPermissions()
        UpdateManager.checkForUpdates(this, lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        renderChannels()
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
        swLocalNotifications = findViewById(R.id.sw_local_notifications)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        channelsContainer = findViewById(R.id.channels_container)
        findViewById<MaterialButton>(R.id.btn_add_proxy).setOnClickListener { showProxyDialog(null) }

        etTemplateSms = findViewById(R.id.et_template_sms)
        etTemplateCall = findViewById(R.id.et_template_call)
        etTemplateOutgoingSms = findViewById(R.id.et_template_outgoing_sms)
        etTemplateIncomingCall = findViewById(R.id.et_template_incoming_call)
        etTemplateOutgoingCall = findViewById(R.id.et_template_outgoing_call)
        etTemplateSms.addTextChangedListener(textWatcher {
            viewModel.setTemplateSms(etTemplateSms.text?.toString() ?: "")
        })
        etTemplateCall.addTextChangedListener(textWatcher {
            viewModel.setTemplateCall(etTemplateCall.text?.toString() ?: "")
        })
        etTemplateOutgoingSms.addTextChangedListener(textWatcher {
            viewModel.setTemplateOutgoingSms(etTemplateOutgoingSms.text?.toString() ?: "")
        })
        etTemplateIncomingCall.addTextChangedListener(textWatcher {
            viewModel.setTemplateIncomingCall(etTemplateIncomingCall.text?.toString() ?: "")
        })
        etTemplateOutgoingCall.addTextChangedListener(textWatcher {
            viewModel.setTemplateOutgoingCall(etTemplateOutgoingCall.text?.toString() ?: "")
        })
        findViewById<MaterialButton>(R.id.btn_preview_templates).setOnClickListener {
            showTemplatePreview()
        }
        findViewById<MaterialButton>(R.id.btn_save_templates).setOnClickListener {
            savePrefs()
            Toast.makeText(this, R.string.toast_templates_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_reset_templates).setOnClickListener {
            etTemplateSms.setText("")
            etTemplateOutgoingSms.setText("")
            etTemplateCall.setText("")
            etTemplateIncomingCall.setText("")
            etTemplateOutgoingCall.setText("")
            viewModel.setTemplateSms("")
            viewModel.setTemplateOutgoingSms("")
            viewModel.setTemplateCall("")
            viewModel.setTemplateIncomingCall("")
            viewModel.setTemplateOutgoingCall("")
            viewModel.save()
            Toast.makeText(this, R.string.toast_templates_reset, Toast.LENGTH_SHORT).show()
        }

        swQuietHours = findViewById(R.id.sw_quiet_hours)
        layoutQuietTimes = findViewById(R.id.layout_quiet_hours_times)
        btnQuietStart = findViewById(R.id.btn_quiet_start)
        btnQuietEnd = findViewById(R.id.btn_quiet_end)

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

        // История
        panelHistory = findViewById(R.id.panel_history)
        historyList = findViewById(R.id.history_list)
        etHistorySearch = findViewById(R.id.et_history_search)
        chipGroupHistory = findViewById(R.id.chip_group_history)
        findViewById<MaterialButton>(R.id.btn_clear_history).setOnClickListener {
            confirmClearHistory()
        }
        etHistorySearch.addTextChangedListener(textWatcher {
            // Debounce: запрос к Room не на каждый символ
            historySearchHandler.removeCallbacks(historySearchRunnable)
            historySearchHandler.postDelayed(historySearchRunnable, 300)
        })
        chipGroupHistory.setOnCheckedStateChangeListener { _, _ -> renderHistory() }

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
        swLocalNotifications.isChecked = s.localNotificationsEnabled

        etTemplateSms.setText(s.templateSms)
        etTemplateOutgoingSms.setText(s.templateOutgoingSms)
        etTemplateCall.setText(s.templateCall)
        etTemplateIncomingCall.setText(s.templateIncomingCall)
        etTemplateOutgoingCall.setText(s.templateOutgoingCall)

        // Тихие часы
        swQuietHours.isChecked = s.quietHoursEnabled
        layoutQuietTimes.visibility = if (s.quietHoursEnabled) View.VISIBLE else View.GONE
        btnQuietStart.text = formatTime(s.quietHoursStart)
        btnQuietEnd.text = formatTime(s.quietHoursEnd)
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
                        btnTest.isEnabled = !s.testing
                        btnTest.text = getString(if (s.testing) R.string.testing else R.string.btn_test_connection)
                        btnGetMyId.isEnabled = !s.resolvingChatId
                    }
                }
                launch {
                    viewModel.events.collect { e -> handleUiEvent(e) }
                }
            }
        }
    }

    private fun handleUiEvent(e: UiEvent) {
        when (e) {
            is UiEvent.ToastRes -> Toast.makeText(this, e.resId, Toast.LENGTH_LONG).show()
            is UiEvent.TestFinished -> showTestResult(e.channels, e.okCount, toastOnSuccess = !startServiceAfterTest)
            is UiEvent.ChatIdResolved -> Toast.makeText(this, "Chat ID: ${e.id}", Toast.LENGTH_LONG).show()
            is UiEvent.ChatIdFailed -> {
                // Ссылку на бота показываем текстом, но НЕ открываем автоматически —
                // неожиданный уход из приложения раздражает.
                val botHint = e.botUsername?.let { " (t.me/$it)" } ?: ""
                Toast.makeText(this, "${e.reason}$botHint — напиши боту /start", Toast.LENGTH_LONG).show()
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
        btnQuietStart.setOnClickListener { showTimePicker(isStart = true) }
        btnQuietEnd.setOnClickListener { showTimePicker(isStart = false) }
        btnGetMyId.setOnClickListener { viewModel.resolveChatId() }
        swSms.setOnCheckedChangeListener { _, v -> viewModel.setSmsEnabled(v) }
        swOutgoingSms.setOnCheckedChangeListener { _, v -> viewModel.setOutgoingSmsEnabled(v) }
        swCalls.setOnCheckedChangeListener { _, v -> viewModel.setCallsEnabled(v) }
        swIncomingCalls.setOnCheckedChangeListener { _, v -> viewModel.setIncomingCallsEnabled(v) }
        swOutgoingCalls.setOnCheckedChangeListener { _, v -> viewModel.setOutgoingCallsEnabled(v) }
        swLocalNotifications.setOnCheckedChangeListener { _, v ->
            viewModel.setLocalNotificationsEnabled(v)
            if (v && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        btnStart.setOnClickListener {
            viewModel.save()
            if (!Prefs.isConfigured()) {
                Toast.makeText(this, R.string.toast_enter_token_and_chatid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Сервис запускаем сразу; затем проверяем связь по каналам (без отправки)
            startServiceAfterTest = true
            ForwardService.start(this)
            Toast.makeText(this, R.string.status_running, Toast.LENGTH_SHORT).show()
            requestBatteryExemption()
            viewModel.testConnection()
        }
        btnStop.setOnClickListener {
            ForwardService.stop(this)
            Toast.makeText(this, R.string.status_stopped, Toast.LENGTH_SHORT).show()
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
            R.id.nav_history -> renderHistory()
        }
    }

    private fun renderChannels() {
        channelsContainer.removeAllViews()
        val channels = ChannelStore.all()
        if (channels.size == 1) {
            val empty = TextView(this).apply {
                text = getString(R.string.channels_no_proxies)
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                textSize = 14f
                setPadding(4, 8, 4, 8)
            }
            channelsContainer.addView(empty)
        }
        for ((i, ch) in channels.withIndex()) {
            channelsContainer.addView(buildChannelRow(ch, i, channels.size))
        }
    }

    /**
     * Строит строку канала (имя, детали, switch, up/down/edit/delete для прокси).
     * Порядок каналов = приоритет каскадной отправки, поэтому прокси можно
     * менять местами кнопками «выше/ниже».
     */
    private fun buildChannelRow(ch: Channel, index: Int, total: Int): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_channel, channelsContainer, false)
        val sw = row.findViewById<SwitchMaterial>(R.id.ch_switch)
        val name = row.findViewById<TextView>(R.id.ch_name)
        val detail = row.findViewById<TextView>(R.id.ch_detail)
        val up = row.findViewById<ImageButton>(R.id.ch_up)
        val down = row.findViewById<ImageButton>(R.id.ch_down)
        val edit = row.findViewById<ImageButton>(R.id.ch_edit)
        val del = row.findViewById<ImageButton>(R.id.ch_delete)

        name.text = if (ch.isDirect) "🔒 ${getString(R.string.channels_direct)}" else "${ch.host}:${ch.port}"
        detail.text = when {
            ch.isDirect -> getString(R.string.channel_direct_detail)
            else -> ch.type
        }
        if (ch.isDirect) {
            // direct всегда включён и не изменяется (порядок двигается автоматически)
            sw.isChecked = true
            sw.isEnabled = false
        } else {
            sw.isChecked = ch.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                ChannelStore.upsert(ch.copy(enabled = checked))
            }
        }
        // Кнопки порядка — для всех каналов: порядок динамический, «Без прокси»
        // тоже двигается (promote/demote по результату отправки)
        setEnabled(up, index > 0)
        setEnabled(down, index < total - 1)
        up.setOnClickListener {
            ChannelStore.move(ch.id, -1)
            renderChannels()
        }
        down.setOnClickListener {
            ChannelStore.move(ch.id, +1)
            renderChannels()
        }
        edit.setOnClickListener { showProxyDialog(ch) }
        if (ch.isDirect) {
            // edit для direct бессмыслен (нет настроек прокси)
            edit.visibility = View.GONE
        }
        del.setOnClickListener { confirmDelete(ch) }
        return row
    }

    private fun setEnabled(btn: ImageButton, enabled: Boolean) {
        btn.isEnabled = enabled
        btn.alpha = if (enabled) 1f else 0.3f
    }

    private fun confirmDelete(ch: Channel) {
        // «Без прокси» удалить нельзя (должен остаться хотя бы один канал) — no-op
        if (ch.isDirect) return
        AlertDialog.Builder(this)
            .setTitle(R.string.channels_proxy_delete)
            .setMessage(getString(R.string.channels_proxy_delete_confirm, ch.name))
            .setPositiveButton(R.string.ok) { _, _ ->
                ChannelStore.remove(ch.id)
                renderChannels()
                LogStore.info("Канал «${ch.name}» удалён")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Диалог добавления/редактирования прокси-канала. */
    private fun showProxyDialog(existing: Channel?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 24, 60, 0)
        }

        // Тип: выпадающий список — Без прокси / HTTP / SOCKS5
        val types = arrayOf(
            getString(R.string.proxy_type_none),
            getString(R.string.proxy_type_http),
            getString(R.string.proxy_type_socks5)
        )
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_1,
                types
            )
            setSelection(
                when (existing?.type) {
                    Channel.TYPE_SOCKS5 -> 2
                    Channel.TYPE_HTTP -> 1
                    else -> 0 // Channel.TYPE_DIRECT
                }
            )
        }
        layout.addView(TextView(this).apply { setPadding(0, 8, 0, 4); text = getString(R.string.pref_proxy_type) })
        layout.addView(typeSpinner)

        fun field(hint: String, value: String, singleLine: Boolean = true) =
            EditText(this).apply { this.hint = hint; setText(value); isSingleLine = singleLine }

        val etHost = field(getString(R.string.pref_proxy_host), existing?.host ?: "")
        val etPort = field(getString(R.string.pref_proxy_port), existing?.port?.toString() ?: "")
        val etUser = field(getString(R.string.pref_proxy_user), existing?.user ?: "")
        etPort.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        // Пароль — маскированный, с переключателем видимости (глазик)
        val passLayout = TextInputLayout(this).apply {
            hint = getString(R.string.pref_proxy_pass)
            // END_ICON_PASSWORD_TOGGLE включает глазик; deprecated
            // isPasswordVisibilityToggleEnabled больше не используется
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val etPass = TextInputEditText(this).apply {
            setText(existing?.pass ?: "")
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        passLayout.addView(etPass)

        // Контейнер для полей прокси (скрываем для "Без прокси")
        val proxyFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (v in listOf(etHost, etPort, etUser, passLayout)) proxyFields.addView(v)
        layout.addView(proxyFields)

        // Показываем/скрываем поля в зависимости от выбранного типа
        fun updateFieldsVisibility() {
            val isDirect = typeSpinner.selectedItemPosition == 0
            proxyFields.visibility = if (isDirect) View.GONE else View.VISIBLE
        }
        updateFieldsVisibility()
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldsVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.channels_add_proxy else R.string.channels_proxy_edit)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val selectedType = typeSpinner.selectedItemPosition
                if (selectedType == 0) {
                    // «Без прокси» всегда есть отдельным каналом:
                    // при редактировании прокси это означает удаление канала
                    if (existing != null) {
                        ChannelStore.remove(existing.id)
                        renderChannels()
                        LogStore.info("Канал «${existing.name}» удалён")
                    } else {
                        Toast.makeText(this, R.string.channel_direct_exists, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // HTTP или SOCKS5
                    val type = if (selectedType == 2) Channel.TYPE_SOCKS5 else Channel.TYPE_HTTP
                    val port = etPort.text.toString().trim().toIntOrNull() ?: 0
                    if (etHost.text.isNullOrBlank() || port <= 0) {
                        Toast.makeText(this, R.string.proxy_need_host_port, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    val host = etHost.text.toString().trim()
                    val ch = Channel(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        type = type,
                        name = "$host:$port",
                        host = host,
                        port = port,
                        user = etUser.text.toString().trim(),
                        pass = etPass.text.toString(),
                        enabled = true,
                    )
                    ChannelStore.upsert(ch)
                    renderChannels()
                    LogStore.info("Канал «${ch.name}» сохранён")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun savePrefs() {
        // Шаблоны вводились в EditText — синхронизируем в состояние и сохраняем
        viewModel.setTemplateSms(etTemplateSms.text?.toString() ?: "")
        viewModel.setTemplateOutgoingSms(etTemplateOutgoingSms.text?.toString() ?: "")
        viewModel.setTemplateCall(etTemplateCall.text?.toString() ?: "")
        viewModel.setTemplateIncomingCall(etTemplateIncomingCall.text?.toString() ?: "")
        viewModel.setTemplateOutgoingCall(etTemplateOutgoingCall.text?.toString() ?: "")
        viewModel.save()
    }

    /**
     * Предпросмотр шаблонов: как будет выглядеть пересылаемое SMS и
     * пропущенный вызов при текущих шаблонах (демо-данные, ничего не отправляется).
     */
    private fun showTemplatePreview() {
        val sms = TemplateFormatter.preview(
            etTemplateSms.text?.toString().orEmpty(),
            EventHistory.TYPE_SMS,
        )
        val outgoingSms = TemplateFormatter.preview(
            etTemplateOutgoingSms.text?.toString().orEmpty(),
            EventHistory.TYPE_OUTGOING_SMS,
        )
        val call = TemplateFormatter.preview(
            etTemplateCall.text?.toString().orEmpty(),
            EventHistory.TYPE_MISSED,
        )
        val incomingCall = TemplateFormatter.preview(
            etTemplateIncomingCall.text?.toString().orEmpty(),
            EventHistory.TYPE_INCOMING,
        )
        val outgoingCall = TemplateFormatter.preview(
            etTemplateOutgoingCall.text?.toString().orEmpty(),
            EventHistory.TYPE_OUTGOING,
        )
        val message = buildString {
            append(getString(R.string.preview_sms_label)).append(":\n").append(sms)
            append("\n\n")
            append(getString(R.string.preview_outgoing_sms_label)).append(":\n").append(outgoingSms)
            append("\n\n")
            append(getString(R.string.preview_call_label)).append(":\n").append(call)
            append("\n\n")
            append(getString(R.string.preview_incoming_call_label)).append(":\n").append(incomingCall)
            append("\n\n")
            append(getString(R.string.preview_outgoing_call_label)).append(":\n").append(outgoingCall)
            append("\n\n")
            append(getString(R.string.preview_sample_note))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.preview_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
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
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(sb.toString().toByteArray())
            }
            true
        } catch (e: Exception) {
            LogStore.error("Ошибка экспорта логов: ${e.message}")
            false
        }
    }

    /** Загрузка истории из Room в фоне и рендер списка. */
    private fun renderHistory() {
        // Фильтр «Звонки» — все типы звонков (пропущенные/входящие/исходящие)
        val type = when (chipGroupHistory.checkedChipId) {
            R.id.chip_history_sms -> EventHistory.TYPE_SMS
            R.id.chip_history_calls -> null // фильтрация по типам звонков ниже
            else -> null
        }
        val callsOnly = chipGroupHistory.checkedChipId == R.id.chip_history_calls
        val query = etHistorySearch.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            try {
                var events = EventHistory.search(this@MainActivity, type, query)
                if (callsOnly) {
                    events = events.filter {
                        it.type == EventHistory.TYPE_MISSED ||
                            it.type == EventHistory.TYPE_INCOMING ||
                            it.type == EventHistory.TYPE_OUTGOING
                    }
                }
                historyList.removeAllViews()
                if (events.isEmpty()) {
                    val empty = TextView(this@MainActivity).apply {
                        text = getString(R.string.history_empty)
                        setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                        textSize = 14f
                        setPadding(4, 24, 4, 8)
                    }
                    historyList.addView(empty)
                    return@launch
                }
                for (e in events) {
                    historyList.addView(buildHistoryRow(e))
                }
            } catch (e: Exception) {
                LogStore.error("Ошибка загрузки истории: ${e.message}")
                historyList.removeAllViews()
                val empty = TextView(this@MainActivity).apply {
                    text = getString(R.string.history_empty)
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                    textSize = 14f
                    setPadding(4, 24, 4, 8)
                }
                historyList.addView(empty)
            }
        }
    }

    private fun buildHistoryRow(e: EventEntity): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 10, 4, 10)
        }
        // Клик по событию — полные детали: кому/через какого бота, полный текст
        row.isClickable = true
        row.setOnClickListener { showEventDetails(e) }
        val icon = when (e.type) {
            EventHistory.TYPE_SMS -> "📨"
            else -> "📵"
        }
        val statusIcon = when (e.status) {
            EventHistory.STATUS_SENT -> "✅"
            EventHistory.STATUS_FAILED -> "⚠️"
            EventHistory.STATUS_DROPPED -> "❌"
            else -> "⏳"
        }
        val sender = e.sender.ifBlank { "—" }
        val title = "$icon $sender  $statusIcon ${dateTime(e.timestamp)}"
        row.addView(
            TextView(this).apply {
                text = title
                setTextSize(13f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        )
        val body = e.body.ifBlank { e.formattedText.ifBlank { "—" } }
        if (body.isNotBlank() && body != "—") {
            row.addView(
                TextView(this).apply {
                    text = body
                    setTextSize(12f)
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
        }
        val status = when (e.status) {
            EventHistory.STATUS_SENT -> getString(R.string.history_status_sent) + (e.channelName?.let { " · $it" } ?: "")
            EventHistory.STATUS_FAILED -> getString(R.string.history_status_failed)
            EventHistory.STATUS_DROPPED -> getString(R.string.history_status_dropped)
            else -> getString(R.string.history_status_queued)
        }
        row.addView(
            TextView(this).apply {
                text = status
                setTextSize(11f)
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        when (e.status) {
                            EventHistory.STATUS_SENT -> android.R.color.holo_green_dark
                            EventHistory.STATUS_DROPPED -> android.R.color.holo_red_dark
                            EventHistory.STATUS_FAILED -> android.R.color.holo_orange_dark
                            else -> android.R.color.darker_gray
                        }
                    )
                )
            }
        )
        return row
    }

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

    /**
     * Диалог с полной информацией о событии: получатель (Chat ID), бот, канал,
     * статус, попытки, исходный текст и полный текст отправленного сообщения.
     * Текст скроллируется — длинные SMS видны целиком.
     */
    private fun showEventDetails(e: EventEntity) {
        val statusText = when (e.status) {
            EventHistory.STATUS_SENT -> getString(R.string.history_status_sent)
            EventHistory.STATUS_FAILED -> getString(R.string.history_status_failed)
            EventHistory.STATUS_DROPPED -> getString(R.string.history_status_dropped)
            else -> getString(R.string.history_status_queued)
        }
        val typeText = when (e.type) {
            EventHistory.TYPE_SMS -> getString(R.string.history_detail_type_sms)
            else -> getString(R.string.history_detail_type_call)
        }
        val dash = getString(R.string.history_detail_none)
        val sb = StringBuilder()
        sb.append(getString(R.string.history_detail_type)).append(": ").append(typeText).append('\n')
        sb.append(getString(R.string.history_detail_time)).append(": ")
            .append(dateTimeFull(e.timestamp)).append('\n')
        sb.append(getString(R.string.history_detail_sender)).append(": ")
            .append(e.sender.ifBlank { dash }).append('\n')
        sb.append(getString(R.string.history_detail_status)).append(": ").append(statusText).append('\n')
        sb.append(getString(R.string.history_detail_chat)).append(": ")
            .append(e.chatId ?: dash).append('\n')
        sb.append(getString(R.string.history_detail_bot)).append(": ")
            .append(e.botUsername?.let { "@$it" } ?: dash).append('\n')
        sb.append(getString(R.string.history_detail_channel)).append(": ")
            .append(e.channelName ?: dash).append('\n')
        sb.append(getString(R.string.history_detail_attempts)).append(": ").append(e.attempts).append('\n')
        if (e.body.isNotBlank() && e.body != e.formattedText) {
            sb.append('\n').append(getString(R.string.history_detail_original)).append(":\n")
                .append(e.body).append('\n')
        }
        sb.append('\n').append(getString(R.string.history_detail_message)).append(":\n")
            .append(e.formattedText.ifBlank { dash })

        val tv = TextView(this).apply {
            text = sb.toString()
            setTextIsSelectable(true)
            textSize = 13f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_detail_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun dateTimeFull(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    private fun dateTime(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    try {
                        EventHistory.clear(this@MainActivity)
                    } catch (e: Exception) {
                        LogStore.error("Ошибка очистки истории: ${e.message}")
                    }
                    renderHistory()
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
            renderChannels()
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