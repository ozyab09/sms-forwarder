package com.ozyab.smsforwarder.ui

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.ozyab.smsforwarder.BuildConfig
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.service.ForwardService
import com.ozyab.smsforwarder.telegram.TelegramClient
import com.ozyab.smsforwarder.update.UpdateChecker
import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Главный экран: настройки (токен, chat ID, прокси) + статус сервиса.
 * Токен вводится в UI и хранится в EncryptedSharedPreferences.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etToken: TextInputEditText
    private lateinit var etChatId: TextInputEditText
    private lateinit var btnGetMyId: MaterialButton
    private lateinit var btnTest: MaterialButton
    private lateinit var swSms: SwitchMaterial
    private lateinit var swCalls: SwitchMaterial
    private lateinit var swShortCodes: SwitchMaterial
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    // Прокси
    private lateinit var swProxy: SwitchMaterial
    private lateinit var actProxyType: AutoCompleteTextView
    private lateinit var etProxyHost: TextInputEditText
    private lateinit var etProxyPort: TextInputEditText
    private lateinit var etProxyUser: TextInputEditText
    private lateinit var etProxyPass: TextInputEditText
    private lateinit var proxyTypeValues: Array<String>

    // Фильтры SMS
    private lateinit var actFilterMode: AutoCompleteTextView
    private lateinit var etWhitelist: TextInputEditText
    private lateinit var etBlockRegex: TextInputEditText
    private lateinit var filterModeValues: Array<String>

    private val scope = CoroutineScope(Dispatchers.Main)

    // Запрос разрешений (SMS + телефон + контакты) — один раз при старте
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* состояние можно игнорировать — предупреждаем в UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_main)

        bindViews()
        loadPrefs()
        setupActions()

        requestNeededPermissions()
        checkForUpdates()
    }

    private fun bindViews() {
        etToken = findViewById(R.id.et_bot_token)
        etChatId = findViewById(R.id.et_chat_id)
        btnGetMyId = findViewById(R.id.btn_get_my_id)
        btnTest = findViewById(R.id.btn_test)
        swSms = findViewById(R.id.sw_sms)
        swCalls = findViewById(R.id.sw_calls)
        swShortCodes = findViewById(R.id.sw_short_codes)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        swProxy = findViewById(R.id.sw_proxy)
        actProxyType = findViewById(R.id.act_proxy_type)
        etProxyHost = findViewById(R.id.et_proxy_host)
        etProxyPort = findViewById(R.id.et_proxy_port)
        etProxyUser = findViewById(R.id.et_proxy_user)
        etProxyPass = findViewById(R.id.et_proxy_pass)
        proxyTypeValues = resources.getStringArray(R.array.proxy_type_values)

        val labels = resources.getStringArray(R.array.proxy_type_labels)
        actProxyType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )

        actFilterMode = findViewById(R.id.act_filter_mode)
        etWhitelist = findViewById(R.id.et_whitelist)
        etBlockRegex = findViewById(R.id.et_block_regex)
        filterModeValues = resources.getStringArray(R.array.filter_mode_values)
        val filterLabels = resources.getStringArray(R.array.filter_mode_labels)
        actFilterMode.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, filterLabels)
        )
    }

    private fun loadPrefs() {
        etToken.setText(Prefs.botToken)
        etChatId.setText(Prefs.chatId)
        swSms.isChecked = Prefs.smsEnabled
        swCalls.isChecked = Prefs.callsEnabled
        swShortCodes.isChecked = Prefs.shortCodesFilter

        swProxy.isChecked = Prefs.proxyEnabled
        val typeIdx = proxyTypeValues.indexOf(Prefs.proxyType).coerceAtLeast(0)
        actProxyType.setText(resources.getStringArray(R.array.proxy_type_labels)[typeIdx], false)
        etProxyHost.setText(Prefs.proxyHost)
        etProxyPort.setText(if (Prefs.proxyPort > 0) Prefs.proxyPort.toString() else "")
        etProxyUser.setText(Prefs.proxyUser)
        etProxyPass.setText(Prefs.proxyPass)

        val modeIdx = filterModeValues.indexOf(Prefs.filterMode).coerceAtLeast(0)
        actFilterMode.setText(resources.getStringArray(R.array.filter_mode_labels)[modeIdx], false)
        etWhitelist.setText(Prefs.smsWhitelist)
        etBlockRegex.setText(Prefs.smsBlockRegex)
    }

    private fun setupActions() {
        btnTest.setOnClickListener {
            savePrefs()
            testConnection()
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
                        Toast.makeText(this@MainActivity, "Chat ID: ${result.messageId}", Toast.LENGTH_LONG).show()
                    }
                    is TelegramClient.Result.Err -> {
                        // Не нашли — предлагаем написать боту /start (открываем чат бота)
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
        swShortCodes.setOnCheckedChangeListener { _, v -> Prefs.shortCodesFilter = v }
        btnStart.setOnClickListener {
            savePrefs()
            if (!Prefs.isConfigured()) {
                Toast.makeText(this, R.string.toast_enter_token_and_chatid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ForwardService.start(this, "🟢 SMS Forwarder запущен")
            Toast.makeText(this, R.string.status_running, Toast.LENGTH_SHORT).show()
            requestBatteryExemption()
        }
        btnStop.setOnClickListener {
            ForwardService.stop(this)
            Toast.makeText(this, R.string.status_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePrefs() {
        Prefs.botToken = etToken.text?.toString()?.trim().orEmpty()
        Prefs.chatId = etChatId.text?.toString()?.trim().orEmpty()
        Prefs.smsEnabled = swSms.isChecked
        Prefs.callsEnabled = swCalls.isChecked
        Prefs.shortCodesFilter = swShortCodes.isChecked

        Prefs.proxyEnabled = swProxy.isChecked
        val label = actProxyType.text?.toString()?.trim().orEmpty()
        val labels = resources.getStringArray(R.array.proxy_type_labels)
        val idx = labels.indexOf(label).coerceAtLeast(0)
        Prefs.proxyType = proxyTypeValues[idx]
        Prefs.proxyHost = etProxyHost.text?.toString()?.trim().orEmpty()
        Prefs.proxyPort = etProxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0
        Prefs.proxyUser = etProxyUser.text?.toString()?.trim().orEmpty()
        Prefs.proxyPass = etProxyPass.text?.toString()?.trim().orEmpty()

        Prefs.filterMode = filterModeValues[filterLabelsIndexOf(actFilterMode)]
        Prefs.smsWhitelist = etWhitelist.text?.toString()?.trim().orEmpty()
        Prefs.smsBlockRegex = etBlockRegex.text?.toString()?.trim().orEmpty()
    }

    private fun filterLabelsIndexOf(act: AutoCompleteTextView): Int {
        val label = act.text?.toString()?.trim().orEmpty()
        val labels = resources.getStringArray(R.array.filter_mode_labels)
        return labels.indexOf(label).coerceAtLeast(0)
    }

    private fun testConnection() {
        savePrefs()
        val token = Prefs.botToken
        val chatId = Prefs.chatId
        if (token.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, R.string.toast_enter_token_and_chatid, Toast.LENGTH_LONG).show()
            return
        }
        btnTest.isEnabled = false
        btnTest.text = getString(R.string.testing)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                TelegramClient.sendMessage("✅ SMS Forwarder: проверка связи")
            }
            btnTest.isEnabled = true
            btnTest.text = getString(R.string.btn_test_connection)
            when (result) {
                is TelegramClient.Result.Ok ->
                    Toast.makeText(this@MainActivity, "✅ Успешно! Сообщение отправлено", Toast.LENGTH_LONG).show()
                is TelegramClient.Result.Err ->
                    Toast.makeText(this@MainActivity, "❌ ${result.reason}", Toast.LENGTH_LONG).show()
            }
        }
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

    /** Проверка новой версии на GitHub при каждом запуске. */
    private fun checkForUpdates() {
        scope.launch {
            val info = UpdateChecker.check() ?: return@launch
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val msg = buildString {
            appendLine("Доступна новая версия ${info.latestVersion}")
            appendLine("Текущая: ${BuildConfig.VERSION_NAME}")
            appendLine()
            if (info.notes.isNotBlank()) {
                appendLine(info.notes.take(500))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Обновление")
            .setMessage(msg)
            .setPositiveButton("Скачать и установить") { _, _ ->
                downloadApk(info.apkUrl)
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun downloadApk(url: String) {
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("SMS Forwarder ${BuildConfig.VERSION_NAME} → обновление")
                setDescription("Загрузка APK…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "sms-forwarder-update.apk")
            }
            dm.enqueue(req)
            Toast.makeText(this, "Загрузка обновления…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось начать загрузку: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}