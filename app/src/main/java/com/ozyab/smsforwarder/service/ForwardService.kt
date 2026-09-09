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
import com.ozyab.smsforwarder.telegram.ChannelSender
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Фоновый сервис пересылки.
 *
 * - Foreground service с НЕВИДИМЫМ уведомлением (IMPORTANCE_MIN).
 * - START_STICKY: перезапускается системой при убийстве.
 * - Очередь событий [SendQueue]: новые события обрабатываются немедленно,
 *   ретраи упавших НЕ блокируют новые (нет head-of-line blocking).
 * - Ретраи per-event: 15с → 30с → … кап 10 мин; после [SendQueue.MAX_ATTEMPTS]
 *   попыток событие отбрасывается с записью в лог.
 * - Персистентность очереди ([EventQueueStore]) — события не теряются при смерти
 *   процесса, восстанавливаются при следующем старте.
 * - Воркер спит до появления события/созревания ретрая (без polling).
 */
class ForwardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = SendQueue()

    /** Пробуждение воркера при появлении нового события. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var workerStarted = false

    companion object {
        private const val CHANNEL_ID = "forward_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.ozyab.smsforwarder.START"
        const val ACTION_STOP = "com.ozyab.smsforwarder.STOP"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context) {
            val i = Intent(context, ForwardService::class.java).setAction(ACTION_START)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                // Android 12+: запуск FGS из фона ограничен — не роняем приложение
                LogStore.warn("Не удалось запустить сервис из фона: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        /** Старт сервиса и постановка события в очередь (из ресиверов). */
        fun start(context: Context, text: String) {
            val i = Intent(context, ForwardService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TEXT, text)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                // Например, PHONE_STATE на Android 12+: FGS из фона запрещён.
                // Событие не теряем — сохраняем в персистентную очередь,
                // сервис подхватит его при следующем старте.
                LogStore.warn("Фоновая пересылка временно недоступна: ${e.message ?: e.javaClass.simpleName}")
                EventQueueStore.persistSingle(context, text)
            }
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
                // «Стоп» — пользователь хочет остановить пересылку: очередь не держим
                val dropped = queue.size
                EventQueueStore.clear(this)
                LogStore.info("Сервис остановлен${if (dropped > 0) " (отброшено неотправленных событий: $dropped)" else ""}")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startAsForeground()

        // Логируем первый старт (не каждое событие)
        if (!workerStarted) {
            LogStore.info("Сервис запущен")
        }

        intent?.getStringExtra(EXTRA_TEXT)?.let { enqueue(it) }

        // Постоянный цикл обработки очереди — только один раз за жизнь сервиса
        if (!workerStarted) {
            workerStarted = true
            scope.launch {
                val restored = EventQueueStore.load(this@ForwardService)
                if (restored.isNotEmpty()) {
                    if (Prefs.isConfigured()) {
                        queue.restore(restored)
                        LogStore.warn("Восстановлено ${queue.size} неотправленных событий из прошлой сессии")
                        persist()
                    } else {
                        // Настройки очищены — старые события отправлять некуда
                        EventQueueStore.clear(this@ForwardService)
                        LogStore.warn("Очередь из прошлой сессии отброшена: не задан токен/chatId")
                    }
                }
                runWorker()
            }
        }
        return START_STICKY
    }

    /** Добавить событие в очередь (вызывается из ресиверов). */
    fun enqueue(text: String) {
        queue.enqueue(text)
        persist()
        wake.trySend(Unit)
    }

    private suspend fun runWorker() {
        while (coroutineContext.isActive) {
            val ev = queue.pollReady()
            if (ev != null) {
                process(ev)
                persist()
                continue
            }
            // Нечего отправлять — ждём новое событие или созревание ретрая
            val waitMs = queue.nextRetryDelayMs()
            if (waitMs == null) {
                wake.receive()
            } else {
                withTimeoutOrNull(waitMs) { wake.receive() }
            }
        }
    }

    private suspend fun process(ev: QueuedEvent) {
        val token = Prefs.botToken
        val chatId = Prefs.chatId
        if (token.isBlank() || chatId.isBlank()) {
            LogStore.warn("Не задан токен/chatId — событие отложено")
            if (queue.fail(ev)) {
                LogStore.error("Событие отброшено после ${SendQueue.MAX_ATTEMPTS} попыток (не задан токен/chatId)")
            }
            return
        }

        val channels = ChannelStore.enabled()
        LogStore.info("Отправка: каналов ${channels.size} (${channels.joinToString { it.name }})")

        when (val result = ChannelSender.send(ev.text, token, chatId, channels)) {
            is ChannelSender.Result.Ok -> {
                Prefs.sentCount = Prefs.sentCount + 1
                LogStore.ok("Отправлено через «${result.channelName}» (id ${result.messageId})")
            }
            is ChannelSender.Result.Err -> {
                val joined = result.reasons.joinToString("; ")
                LogStore.error("Все каналы не вышли: $joined")
                if (queue.fail(ev)) {
                    LogStore.error("Событие отброшено после ${SendQueue.MAX_ATTEMPTS} попыток")
                }
            }
        }
    }

    private fun persist() {
        val snapshot = queue.snapshot()
        if (snapshot.isEmpty()) {
            // Не храним тексты SMS на диске без необходимости (privacy-first)
            EventQueueStore.clear(this)
        } else {
            EventQueueStore.saveAsync(this, snapshot)
        }
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