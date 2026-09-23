package com.ozyab.smsforwarder.service

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import java.util.concurrent.Executors

/**
 * Наблюдатель за исходящими SMS.
 *
 * Регистрируется на `content://sms/sent` и при обнаружении нового отправленного SMS
 * отправляет его через ForwardService.
 *
 * Требует READ_SMS permission.
 *
 * Потоки: onChange() приходит на main thread — здесь ТОЛЬКО планирование.
 * Вся тяжёлая работа (запросы к провайдеру, контакты, SIM) выполняется в
 * фоновом однопоточном executor — иначе риск ANR под пачкой изменений провайдера.
 *
 * Дебаунс: строка SMS появляется в провайдере со статусом QUEUED/OUTBOX, и тип
 * SENT проставляется чуть позже. Планируем проверку с задержкой и, если последнее
 * сообщение ещё не SENT, перепроверяем — с ограничением числа перепроверок
 * ([MAX_SENT_CHECKS]): перепроверки заканчиваются, а не крутятся бесконечно
 * (см. фикс B1 в issue #137: раньше наблюдение висло на входящем SMS из-за
 * запроса без фильтра по типу).
 */
class OutgoingSmsObserver(context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {

    /** Только таблица Sent: входящие SMS не дёргают onChange (фикс B1, #137). */
    private val SENT_URI = Telephony.Sms.Sent.CONTENT_URI

    private val appContext = context.applicationContext

    /** Вся работа с провайдером — здесь (строго по порядку, как в ресиверах). */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "outgoing-sms-worker").apply { isDaemon = true }
    }

    /** Handler main thread — только для планирования дебаунса/ретраев проверки. */
    private val scheduler = Handler(Looper.getMainLooper())

    private var lastSeenId: Long = -1L

    /** Задержка перед проверкой после onChange: сливаем пачку изменений, ждём SENT. */
    private val debounceMs = 500L

    /** Повторная проверка, если последнее SMS ещё не в статусе SENT. */
    private val retryMs = 1_000L

    /** Сколько раз подряд перепроверяем статус SENT, прежде чем сдаться. */
    private val maxSentChecks = 5

    /** Сколько перепроверок статуса осталось в текущей серии. */
    private var sentChecksLeft = 0

    private val checkRunnable = Runnable {
        executor.execute { checkForNewSentSms() }
    }

    /** Останов наблюдения. */
    fun stop() {
        try {
            scheduler.removeCallbacks(checkRunnable)
            appContext.contentResolver.unregisterContentObserver(this)
        } catch (_: Exception) { }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // Только планирование: тяжёлое — в executor (см. KDoc класса).
        // Новый цикл дебаунса — новый бюджет перепроверок статуса SENT.
        sentChecksLeft = maxSentChecks
        scheduler.removeCallbacks(checkRunnable)
        scheduler.postDelayed(checkRunnable, debounceMs)
    }

    /** Старт наблюдения: запоминаем текущее последнее SMS и подписываемся. */
    fun start() {
        if (!Prefs.outgoingSmsEnabled) return
        // Запоминаем ID последнего отправленного SMS, чтобы не пересылать старые
        executor.execute {
            lastSeenId = getLastSentSmsId()
            LogStore.info("OutgoingSmsObserver: наблюдение запущено (lastSeenId=$lastSeenId)")
        }
        appContext.contentResolver.registerContentObserver(
            SENT_URI,
            true,
            this
        )
    }

    /** Проверка последнего отправленного SMS (фоновый поток). */
    private fun checkForNewSentSms() {
        if (!Prefs.outgoingSmsEnabled) return
        // Читаем последнее SMS из провайдера
        val sms = readLastSentSms() ?: return
        if (sms.id <= lastSeenId) return // уже видели

        if (sms.type != Telephony.Sms.MESSAGE_TYPE_SENT) {
            // Ещё QUEUED/OUTBOX — статус SENT проставится позже; перепроверяем
            // с лимитом: если SENT так и не наступил (сбой отправки), серия
            // перепроверок заканчивается, а не крутится бесконечно (#137).
            if (sentChecksLeft <= 0) return
            sentChecksLeft--
            scheduler.removeCallbacks(checkRunnable)
            scheduler.postDelayed(checkRunnable, retryMs)
            return
        }
        lastSeenId = sms.id
        sentChecksLeft = 0

        LogStore.info("OutgoingSmsObserver: исходящий SMS → ${sms.address}")

        val name = com.ozyab.smsforwarder.util.ContactNames.lookup(appContext, sms.address)
        // SIM, с которой отправлено: sub_id из строки SMS (null → не показываем,
        // вместо «первая попавшаяся» — неверная атрибуция)
        val sim = com.ozyab.smsforwarder.util.SimInfo.describe(appContext, sms.subscriptionId)

        val formatted = com.ozyab.smsforwarder.util.TemplateFormatter.format(
            template = Prefs.messageTemplateOutgoingSms,
            sender = sms.address,
            name = name,
            text = sms.body,
            timestamp = sms.date,
            type = "outgoing_sms",
            sim = sim
        )

        ForwardService.start(
            appContext,
            formatted,
            type = "outgoing_sms",
            sender = sms.address,
            eventTime = sms.date
        )
    }

    private fun getLastSentSmsId(): Long {
        return try {
            val projection = arrayOf(Telephony.Sms._ID)
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 1"
            appContext.contentResolver.query(SENT_URI, projection, null, null, sort)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /** Последняя строка из content://sms/sent (таблица Sent — фильтр по типу не нужен). */
    private fun readLastSentSms(): SentSms? {
        return try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.SUBSCRIPTION_ID
            )
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 1"
            appContext.contentResolver.query(SENT_URI, projection, null, null, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                    val subIdx = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                    SentSms(
                        id = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms._ID)),
                        address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                        body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                        date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                        type = if (typeIdx >= 0) c.getInt(typeIdx) else -1,
                        // INVALID_SUBSCRIPTION_ID = -1 → не показываем SIM
                        subscriptionId = if (subIdx >= 0) c.getInt(subIdx).takeIf { it != -1 } else null
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class SentSms(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val subscriptionId: Int?,
    )
}
