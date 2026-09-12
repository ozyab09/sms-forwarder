package com.ozyab.smsforwarder.ui

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.ozyab.smsforwarder.telegram.ChannelSender
import com.ozyab.smsforwarder.telegram.TelegramClient
import com.ozyab.smsforwarder.update.UpdateChecker
import com.ozyab.smsforwarder.update.UpdateManager
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.SettingsBackup
import com.ozyab.smsforwarder.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
    private lateinit var swCalls: SwitchMaterial
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    // Каналы отправки
    private lateinit var channelsContainer: LinearLayout

    // Шаблоны сообщений
    private lateinit var etTemplateSms: TextInputEditText
    private lateinit var etTemplateCall: TextInputEditText

    // Логи
    private lateinit var panelSettings: ScrollView
    private lateinit var panelLogs: View
    private lateinit var logsText: TextView

    // История
    private lateinit var panelHistory: View
    private lateinit var historyList: LinearLayout
    private lateinit var etHistorySearch: TextInputEditText
    private lateinit var chipGroupHistory: com.google.android.material.chip.ChipGroup

    // О приложении
    private lateinit var panelAbout: View
    private lateinit var tvAboutVersion: TextView
    private lateinit var rgTheme: RadioGroup
    private lateinit var btnGithub: MaterialButton
    private lateinit var btnExportSettings: MaterialButton
    private lateinit var btnImportSettings: MaterialButton

    private val scope = CoroutineScope(Dispatchers.Main)

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

        requestNeededPermissions()
        UpdateManager.checkForUpdates(this, scope)
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
        swCalls = findViewById(R.id.sw_calls)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        channelsContainer = findViewById(R.id.channels_container)
        findViewById<MaterialButton>(R.id.btn_add_proxy).setOnClickListener { showProxyDialog(null) }

        etTemplateSms = findViewById(R.id.et_template_sms)
        etTemplateCall = findViewById(R.id.et_template_call)
        findViewById<MaterialButton>(R.id.btn_reset_templates).setOnClickListener {
            etTemplateSms.setText("")
            etTemplateCall.setText("")
            savePrefs()
            Toast.makeText(this, R.string.btn_reset_templates, Toast.LENGTH_SHORT).show()
        }

        panelSettings = findViewById(R.id.panel_settings)
        panelLogs = findViewById(R.id.panel_logs)
        logsText = findViewById(R.id.logs_text)
        findViewById<MaterialButton>(R.id.btn_clear_logs).setOnClickListener {
            LogStore.clear()
            renderLogs()
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
            renderHistory()
        })
        chipGroupHistory.setOnCheckedStateChangeListener { _, _ -> renderHistory() }

        btnCheckUpdate = findViewById(R.id.btn_check_update)
        panelAbout = findViewById(R.id.panel_about)
        tvAboutVersion = findViewById(R.id.tv_about_version)
        rgTheme = findViewById(R.id.rg_theme)
        btnGithub = findViewById(R.id.btn_github)
        btnExportSettings = findViewById(R.id.btn_export_settings)
        btnImportSettings = findViewById(R.id.btn_import_settings)
    }

    private fun loadPrefs() {
        etToken.setText(Prefs.botToken)
        etChatId.setText(Prefs.chatId)
        swSms.isChecked = Prefs.smsEnabled
        swCalls.isChecked = Prefs.callsEnabled

        etTemplateSms.setText(Prefs.messageTemplateSms)
        etTemplateCall.setText(Prefs.messageTemplateCall)
    }

    private fun setupActions() {
        btnTest.setOnClickListener {
            savePrefs()
            testConnection()
        }
        btnCheckUpdate.setOnClickListener { UpdateManager.checkForUpdates(this, scope, force = true) }
        btnGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.PROJECT_URL)))
        }
        btnExportSettings.setOnClickListener {
            savePrefs()
            exportLauncher.launch(SettingsBackup.defaultFileName())
        }
        btnImportSettings.setOnClickListener {
            savePrefs()
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_theme_light -> ThemeManager.MODE_LIGHT
                R.id.rb_theme_dark -> ThemeManager.MODE_DARK
                else -> ThemeManager.MODE_SYSTEM
            }
            ThemeManager.setAndApply(this, mode)
            LogStore.info("Тема: $mode")
        }
        btnGetMyId.setOnClickListener {
            savePrefs()
            val token = etToken.text?.toString()?.trim().orEmpty()
            if (token.isBlank()) {
                Toast.makeText(this, R.string.pref_bot_token_hint, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnGetMyId.isEnabled = false
            scope.launch {
                val result = withContext(Dispatchers.IO) { TelegramClient.resolveChatId() }
                btnGetMyId.isEnabled = true
                when (result) {
                    is TelegramClient.Result.Ok -> {
                        etChatId.setText(result.messageId.toString())
                        Prefs.chatId = result.messageId.toString()
                        LogStore.ok("Chat ID определён: ${result.messageId}")
                        Toast.makeText(this@MainActivity, "Chat ID: ${result.messageId}", Toast.LENGTH_LONG).show()
                    }
                    is TelegramClient.Result.Err -> {
                        LogStore.error("Chat ID не определён: ${result.reason}")
                        scope.launch {
                            val username = withContext(Dispatchers.IO) { TelegramClient.getBotUsername() }
                            if (username != null) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username")))
                            }
                            Toast.makeText(this@MainActivity, "${result.reason} — напиши боту /start", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        swSms.setOnCheckedChangeListener { _, v -> Prefs.smsEnabled = v }
        swCalls.setOnCheckedChangeListener { _, v -> Prefs.callsEnabled = v }
        btnStart.setOnClickListener {
            savePrefs()
            if (!Prefs.isConfigured()) {
                Toast.makeText(this, R.string.toast_enter_token_and_chatid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Сначала проверяем связь через все каналы (как в "Тестировать"),
            // но сервис запускаем в любом случае
            testConnectionAndStartService()
        }
        btnStop.setOnClickListener {
            ForwardService.stop(this)
            Toast.makeText(this, R.string.status_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    panelSettings.visibility = View.VISIBLE
                    panelLogs.visibility = View.GONE
                    panelHistory.visibility = View.GONE
                    panelAbout.visibility = View.GONE
                    true
                }
                R.id.nav_history -> {
                    savePrefs()
                    panelSettings.visibility = View.GONE
                    panelLogs.visibility = View.GONE
                    panelHistory.visibility = View.VISIBLE
                    panelAbout.visibility = View.GONE
                    renderHistory()
                    true
                }
                R.id.nav_logs -> {
                    savePrefs()
                    panelSettings.visibility = View.GONE
                    panelLogs.visibility = View.GONE
                    panelHistory.visibility = View.GONE
                    panelAbout.visibility = View.GONE
                    panelLogs.visibility = View.VISIBLE
                    renderLogs()
                    true
                }
                R.id.nav_about -> {
                    savePrefs()
                    panelSettings.visibility = View.GONE
                    panelLogs.visibility = View.GONE
                    panelHistory.visibility = View.GONE
                    panelAbout.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
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
            // direct всегда первый, всегда включён и не изменяется
            sw.isChecked = true
            sw.isEnabled = false
        } else {
            sw.isChecked = ch.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                ChannelStore.upsert(ch.copy(enabled = checked))
            }
        }
        if (ch.isDirect) {
            // direct всегда первый и не перемещается
            up.visibility = View.GONE
            down.visibility = View.GONE
            edit.visibility = View.GONE
        } else {
            // index 0 — direct, первый прокси начинается с 1
            setEnabled(up, index > 1)
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
        }
        del.setOnClickListener { confirmDelete(ch) }
        return row
    }

    private fun setEnabled(btn: ImageButton, enabled: Boolean) {
        btn.isEnabled = enabled
        btn.alpha = if (enabled) 1f else 0.3f
    }

    private fun confirmDelete(ch: Channel) {
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
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            isPasswordVisibilityToggleEnabled = true
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
        Prefs.botToken = etToken.text?.toString()?.trim().orEmpty()
        Prefs.chatId = etChatId.text?.toString()?.trim().orEmpty()
        Prefs.smsEnabled = swSms.isChecked
        Prefs.callsEnabled = swCalls.isChecked

        Prefs.messageTemplateSms = etTemplateSms.text?.toString() ?: ""
        Prefs.messageTemplateCall = etTemplateCall.text?.toString() ?: ""
    }

    private fun testConnection() {
        savePrefs()
        val token = Prefs.botToken
        if (token.isBlank()) {
            Toast.makeText(this, R.string.toast_enter_token, Toast.LENGTH_LONG).show()
            return
        }
        val channels = ChannelStore.enabled()
        if (channels.isEmpty()) {
            Toast.makeText(this, R.string.test_no_channels, Toast.LENGTH_LONG).show()
            return
        }
        btnTest.isEnabled = false
        btnTest.text = getString(R.string.testing)
        LogStore.info("Проверка связи через каналы: ${channels.joinToString { it.name }}")
        scope.launch {
            // Параллельно проверяем ВСЕ каналы через getMe (без отправки сообщений),
            // результат — по каждому отдельно
            val results = withContext(Dispatchers.IO) {
                ChannelSender.testAll(token, channels)
            }
            btnTest.isEnabled = true
            btnTest.text = getString(R.string.btn_test_connection)

            val okCount = results.count { it.ok }
            val summary = buildString {
                for (r in results) {
                    if (r.ok) {
                        val bot = r.botUsername ?: "?"
                        LogStore.ok("Тест «${r.channel.name}» — бот @$bot доступен")
                        appendLine(getString(R.string.test_channel_ok, r.channel.name, bot))
                        // Тост про успех: соединение бота через этот канал
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.test_connection_ok, bot, r.channel.name),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        LogStore.error("Тест «${r.channel.name}» — ${r.error ?: "ошибка"}")
                        appendLine(getString(R.string.test_channel_fail, r.channel.name, r.error ?: getString(R.string.test_error_unknown)))
                    }
                }
            }
            if (okCount == 0) {
                Toast.makeText(this@MainActivity, R.string.test_all_none, Toast.LENGTH_LONG).show()
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.test_dialog_title)
                .setMessage(summary)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    /**
     * Запуск сервиса + проверка связи с ботом (getMe), как в «Тестировать»,
     * но БЕЗ отправки приветственного сообщения в Telegram.
     */
    private fun testConnectionAndStartService() {
        // Сервис запускается сразу и без отправки сообщений
        ForwardService.start(this)
        Toast.makeText(this, R.string.status_running, Toast.LENGTH_SHORT).show()
        requestBatteryExemption()

        val token = Prefs.botToken
        if (token.isBlank()) return
        val channels = ChannelStore.enabled()
        if (channels.isEmpty()) return

        LogStore.info("Проверка связи при запуске через каналы: ${channels.joinToString { it.name }}")
        scope.launch {
            // Параллельно проверяем ВСЕ каналы через getMe (без отправки сообщений),
            // результат — по каждому отдельно
            val results = withContext(Dispatchers.IO) {
                ChannelSender.testAll(token, channels)
            }

            val okCount = results.count { it.ok }
            val summary = buildString {
                for (r in results) {
                    if (r.ok) {
                        val bot = r.botUsername ?: "?"
                        LogStore.ok("Тест «${r.channel.name}» — бот @$bot доступен")
                        appendLine(getString(R.string.test_channel_ok, r.channel.name, bot))
                    } else {
                        LogStore.error("Тест «${r.channel.name}» — ${r.error ?: "ошибка"}")
                        appendLine(getString(R.string.test_channel_fail, r.channel.name, r.error ?: getString(R.string.test_error_unknown)))
                    }
                }
            }
            if (okCount == 0) {
                Toast.makeText(this@MainActivity, R.string.test_all_none, Toast.LENGTH_LONG).show()
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.test_dialog_title)
                .setMessage(summary)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun renderLogs() {
        val sb = StringBuilder()
        for (e in LogStore.all()) {
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

    /** Загрузка истории из Room в фоне и рендер списка. */
    private fun renderHistory() {
        val type = when (chipGroupHistory.checkedChipId) {
            R.id.chip_history_sms -> EventHistory.TYPE_SMS
            R.id.chip_history_calls -> EventHistory.TYPE_MISSED
            else -> null
        }
        val query = etHistorySearch.text?.toString()?.trim().orEmpty()
        scope.launch {
            val events = EventHistory.search(this@MainActivity, type, query)
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
        }
    }

    private fun buildHistoryRow(e: EventEntity): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 10, 4, 10)
        }
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

    private fun dateTime(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                scope.launch {
                    EventHistory.clear(this@MainActivity)
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
        if (Build.VERSION.SDK_INT >= 33) {
            // POST_NOTIFICATIONS: НЕ запрашиваем — уведомление и так невидимое,
            // а отказ не мешает сервису. (Требование: без всплывающих уведомлений.)
        }
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
    }

    /** Читает файл и применяет настройки; обновляет UI. */
    private fun importSettings(uri: android.net.Uri) {
        scope.launch {
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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}