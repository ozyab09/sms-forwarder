package com.ozyab.smsforwarder.service

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs

/**
 * Наблюдатель за исходящими SMS.
 *
 * Регистрируется на content://sms/sent и при обнаружении нового SMS
 * отправляет его через ForwardService.
 *
 * Требует READ_SMS permission.
 */
class OutgoingSmsObserver(context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val appContext = context.applicationContext
    private var lastSeenId: Long = -1L

    /** Старт наблюдения: запоминаем текущее последнее SMS и подписываемся. */
    fun start() {
        if (!Prefs.outgoingSmsEnabled) return
        // Запоминаем ID последнего отправленного SMS, чтобы не пересылать старые
        lastSeenId = getLastSentSmsId()
        appContext.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            this
        )
        LogStore.info("OutgoingSmsObserver: наблюдение запущено (lastSeenId=$lastSeenId)")
    }

    /** Останов наблюдения. */
    fun stop() {
        try {
            appContext.contentResolver.unregisterContentObserver(this)
        } catch (_: Exception) { }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (!Prefs.outgoingSmsEnabled) return
        // Читаем последнее SMS из провайдера
        val sms = readLastSentSms() ?: return
        if (sms.id <= lastSeenId) return // уже видели
        lastSeenId = sms.id

        LogStore.info("OutgoingSmsObserver: исходящий SMS → ${sms.address}")

        val formatted = com.ozyab.smsforwarder.util.TemplateFormatter.format(
            template = Prefs.messageTemplateOutgoingSms,
            sender = sms.address,
            name = null,
            text = sms.body,
            timestamp = sms.date,
            type = "outgoing_sms",
            sim = null
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
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(Telephony.Sms._ID)
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 1"
            appContext.contentResolver.query(uri, projection, null, null, sort)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun readLastSentSms(): SentSms? {
        return try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            )
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 1"
            appContext.contentResolver.query(uri, projection, null, null, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val type = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    // TYPE_SENT = 2
                    if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                        SentSms(
                            id = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms._ID)),
                            address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                            body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                            date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))
                        )
                    } else null
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
        val date: Long
    )
}
