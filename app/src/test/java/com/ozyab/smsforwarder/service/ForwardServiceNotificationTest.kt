package com.ozyab.smsforwarder.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Требование «без всплывающих уведомлений» (ROADMAP T7).
 *
 * Сервис создаёт канал с IMPORTANCE_MIN и уведомление с PRIORITY_MIN:
 * heads-up показываются только для HIGH/MAX, поэтому такое уведомление
 * не всплывает; обычных уведомлений сервис не создаёт.
 */
// Проверяем именно legacy-поле Notification.priority (PRIORITY_MIN) —
// депрекейшн осознанный.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@Suppress("DEPRECATION")
class ForwardServiceNotificationTest {

    private fun notificationManager(): NotificationManager =
        ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `foreground notification uses invisible channel`() {
        val controller = Robolectric.buildService(ForwardService::class.java, Intent())
        controller.create().startCommand(0, 0)

        val channel = notificationManager().getNotificationChannel("forward_service")
        assertNotNull("канал уведомления сервиса создан", channel)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel!!.importance)
        assertFalse("канал без вибрации", channel.shouldVibrate())
        assertNull("канал без звука", channel.sound)
        assertFalse("канал без бейджа", channel.canShowBadge())

        controller.destroy()
    }

    @Test
    fun `foreground notification is ongoing and low priority`() {
        val controller = Robolectric.buildService(ForwardService::class.java, Intent())
        controller.create().startCommand(0, 0)

        val service = controller.get()
        val posted = shadowOf(service).lastForegroundNotification
        assertNotNull("сервис вызвал startForeground", posted)
        assertEquals(NotificationCompat.PRIORITY_MIN, posted!!.priority)
        assertTrue(
            "уведомление ongoing",
            posted.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )

        controller.destroy()
    }

    @Test
    fun `service does not post heads-up notifications`() {
        val controller = Robolectric.buildService(ForwardService::class.java, Intent())
        controller.create().startCommand(0, 0)

        // Ни одного уведомления с важностью HIGH/MAX (именно они всплывают)
        val headsUp = shadowOf(notificationManager()).allNotifications
            .filter { it.priority >= NotificationCompat.PRIORITY_HIGH }
        assertTrue("нет heads-up уведомлений: ${headsUp.map { it.tickerText }}", headsUp.isEmpty())

        controller.destroy()
    }
}
