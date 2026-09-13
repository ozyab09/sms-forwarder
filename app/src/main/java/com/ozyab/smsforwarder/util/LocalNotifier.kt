package com.ozyab.smsforwarder.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
 */
object LocalNotifier {

    private const val CHANNEL_ID = "local_notifications"
    private const val CHANNEL_NAME = "Входящие сообщения"
    private const val CHANNEL_DESC = "Уведомления о входящих SMS и звонках"
    private val notificationId = AtomicInteger(1000)

    fun notify(context: Context, title: String, text: String) {
        if (!Prefs.localNotificationsEnabled) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
