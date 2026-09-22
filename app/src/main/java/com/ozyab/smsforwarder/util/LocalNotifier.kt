package com.ozyab.smsforwarder.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ozyab.smsforwarder.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Локальные уведомления на телефоне.
 *
 * Опциональная фича: уведомляет пользователя о входящих SMS/звонках
 * прямо на устройстве, даже если Telegram-бот недоступен.
 *
 * Канал "local_notifications" с IMPORTANCE_DEFAULT — звук + вибрация.
 *
 * ВАЖНО (Android 13+): без POST_NOTIFICATIONS уведомления молча не показываются.
 * MainActivity запрашивает разрешение при включении фичи — здесь только
 * диагностический лог, чтобы причина «фича включена, а уведомлений нет» была видна.
 */
object LocalNotifier {

    private const val CHANNEL_ID = "local_notifications"
    private const val CHANNEL_NAME = "Входящие сообщения"
    private const val CHANNEL_DESC = "Уведомления о входящих SMS и звонках"
    private val notificationId = AtomicInteger(1000)

    /** Main handler: notify() может вызываться из фоновых потоков ресиверов. */
    private val mainHandler = Handler(Looper.getMainLooper())

    fun notify(context: Context, title: String, text: String) {
        if (!Prefs.localNotificationsEnabled) return

        // Постим на main thread (ресиверы вызывают из фоновых потоков)
        val appContext = context.applicationContext
        mainHandler.post { postNotification(appContext, title, text) }
    }

    private fun postNotification(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Пересоздание существующего канала — no-op: настройки канала
            // пользователь менял сам, их не перетираем.
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = CHANNEL_DESC
                enableLights(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        // Android 13+: диагностика отсутствия разрешения (фича молча не работает)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            LogStore.warn("Локальные уведомления: нет разрешения POST_NOTIFICATIONS (Android 13+)")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(notificationId.incrementAndGet(), notification)
    }
}
