package com.ozyab.smsforwarder.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider

/**
 * Приём завершения загрузки APK обновления.
 *
 * После DOWNLOAD_COMPLETE находит скачанный файл и запускает установку
 * (через FileProvider — иначе не даст открыть файл на Android 7+).
 */
class DownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return

        val dm = context.getSystemService(DownloadManager::class.java)
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q).use { c ->
            if (!c.moveToFirst()) return
            val colStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (c.getInt(colStatus) != DownloadManager.STATUS_SUCCESSFUL) return

            val colUri = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val localUri = c.getString(colUri) ?: return
            val file = Uri.parse(localUri).path?.let { java.io.File(it) } ?: return

            // Открываем установщик
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                    "application/vnd.android.package-archive"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        }
    }
}