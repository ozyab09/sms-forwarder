package com.ozyab.smsforwarder.update

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.ozyab.smsforwarder.BuildConfig
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Проверка обновлений из UI: throttle авто-проверок, ручная проверка,
 * диалог с новой версией, загрузка APK через DownloadManager.
 *
 * Логика отделена от MainActivity, чтобы «О приложении» и авто-проверка
 * при запуске пользовались одним механизмом.
 */
object UpdateManager {

    private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 часа

    /**
     * Проверка новой версии на GitHub.
     *
     * @param force true — ручная кнопка: проверяем всегда. false — авто-проверка
     *   при запуске: не чаще раза в сутки (бережём сеть/трафик).
     *
     * Важно: метка «последняя проверка» ставится ТОЛЬКО при успешном ответе API
     * ([UpdateChecker.CheckResult.Unavailable] не считается) — иначе один сбой
     * сети или rate-limit GitHub блокировал бы проверки на 24 часа.
     */
    fun checkForUpdates(context: Context, scope: CoroutineScope, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = Prefs.lastUpdateCheck
        if (!force && now - last < UPDATE_CHECK_INTERVAL_MS) return
        scope.launch {
            when (val res = UpdateChecker.check()) {
                is UpdateChecker.CheckResult.Update -> {
                    Prefs.lastUpdateCheck = now
                    showUpdateDialog(context, res.info)
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    Prefs.lastUpdateCheck = now
                    if (force) Toast.makeText(
                        context, R.string.update_none_available, Toast.LENGTH_LONG
                    ).show()
                }
                is UpdateChecker.CheckResult.Unavailable -> {
                    // Сеть/API недоступны — НЕ ставим метку, попробуем в следующий раз
                    if (force) Toast.makeText(
                        context, R.string.update_check_failed, Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(context: Context, info: UpdateChecker.UpdateInfo) {
        val msg = buildString {
            appendLine(context.getString(R.string.update_available, info.latestVersion))
            appendLine(context.getString(R.string.update_current, BuildConfig.VERSION_NAME))
            appendLine()
            if (info.notes.isNotBlank()) {
                appendLine(info.notes.take(500))
            }
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.update_dialog_title)
            .setMessage(msg)
            .setPositiveButton(R.string.update_download_install) { _, _ ->
                downloadApk(context, info.apkUrl)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadApk(context: Context, url: String) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("SMS Forwarder ${BuildConfig.VERSION_NAME} → обновление")
                setDescription(context.getString(R.string.update_downloading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "sms-forwarder-update.apk")
            }
            dm.enqueue(req)
            Toast.makeText(context, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.update_download_failed, e.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}