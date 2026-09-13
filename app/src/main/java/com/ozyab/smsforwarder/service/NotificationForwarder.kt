package com.ozyab.smsforwarder.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.TemplateFormatter
import org.json.JSONArray

/**
 * Сервис пересылки уведомлений других приложений в Telegram.
 *
 * Требует включения NotificationListenerService в настройках системы.
 * Фильтрует по списку выбранных приложений (Prefs.notificationApps).
 * Пустой список = все приложения.
 */
class NotificationForwarder : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.notificationsEnabled) return
        if (sbn.packageName == packageName) return // не пересылаем свои уведомления

        // Фильтр по приложениям
        val allowedApps = getAllowedApps()
        if (allowedApps.isNotEmpty() && sbn.packageName !in allowedApps) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val appLabel = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        LogStore.info("NotificationForwarder: $appLabel: $title")

        val formatted = TemplateFormatter.format(
            template = Prefs.messageTemplateNotification,
            sender = "",
            name = null,
            text = text,
            timestamp = sbn.postTime,
            type = "notification",
            appName = appLabel,
            title = title
        )

        ForwardService.start(
            applicationContext,
            formatted,
            type = "notification",
            sender = sbn.packageName,
            eventTime = sbn.postTime
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // ничего — пересылка при появлении, а не при удалении
    }

    /** Парсит JSON-массив package names из Prefs. Пустой = все. */
    private fun getAllowedApps(): Set<String> {
        val json = Prefs.notificationApps
        if (json.isBlank()) return emptySet()
        return try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }
}
