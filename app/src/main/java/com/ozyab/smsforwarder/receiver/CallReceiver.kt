package com.ozyab.smsforwarder.receiver

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.ozyab.smsforwarder.util.ContactNames
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.SimInfo
import com.ozyab.smsforwarder.util.formatTimestamp

/**
 * Отслеживание пропущенных вызовов.
 *
 * Логика: вызов перешёл в состояние RINGING, а затем завершился (IDLE),
 * не будучи принятым. Используем только реально пропущенные:
 * звонящий не дождался ответа (telephony-состояние + CallLog DISCONNECTED,
 * где тип = MISSED).
 *
 * (Имя из контактов подтягивается из CallLog/контактов на устройстве.)
 */
object CallReceiverLogic {

    private var ringingNumber: String? = null

    /**
     * Обрабатывает смену состояния телефона. Вызывается из CallReceiver.
     * Возвращает текст события пропущенного вызова или null.
     */
    fun onPhoneStateChanged(context: Context, state: String?, number: String?): String? {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                ringingNumber = number
                return null
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val missed = ringingNumber
                ringingNumber = null
                if (missed != null && looksMissed(context, missed)) {
                    val name = ContactNames.lookup(context, missed)
                    val time = formatTimestamp(System.currentTimeMillis())
                    // SIM: из PHONE_STATE нет subscriptionId — берём первую активную SIM
                    val sim = SimInfo.describe(context, null)
                    return buildString {
                        appendLine("📵 Пропущенный [$time]")
                        if (sim != null) appendLine("SIM: $sim")
                        appendLine("От: $missed${if (name != null) " ($name)" else ""}")
                    }
                }
                return null
            }
        }
        return null
    }

    /** Проверка по CallLog: последний вызов с этого номера — MISSED. */
    private fun looksMissed(context: Context, number: String): Boolean {
        return try {
            val cr = context.contentResolver
            val uri = android.provider.CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.NUMBER,
            )
            val sort = android.provider.CallLog.Calls.DATE + " DESC LIMIT 5"
            cr.query(uri, projection, null, null, sort)?.use { c ->
                val typeCol = c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val numCol = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                while (c.moveToNext()) {
                    if (c.getString(numCol)?.let { it.contains(number, ignoreCase = true) } == true) {
                        return c.getInt(typeCol) == android.provider.CallLog.Calls.MISSED_TYPE
                    }
                }
                false
            } ?: false
        } catch (e: Exception) {
            // Нет разрешения READ_CALL_LOG — считаем пропущенным (fallback)
            true
        }
    }
}

class CallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs.callsEnabled) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        val text = CallReceiverLogic.onPhoneStateChanged(context, state, number) ?: return
        com.ozyab.smsforwarder.service.ForwardService.start(context, text)
    }
}