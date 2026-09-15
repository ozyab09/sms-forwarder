package com.ozyab.smsforwarder.receiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ozyab.smsforwarder.util.ContactNames
import com.ozyab.smsforwarder.util.QuietHours
import com.ozyab.smsforwarder.util.ReceiverExecutor
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.SimInfo
import com.ozyab.smsforwarder.util.TemplateFormatter

/**
 * Отслеживание вызовов: пропущенные, принятые входящие, исходящие.
 *
 * Логика (state machine по PHONE_STATE):
 *  - RINGING  → запоминаем номер, сбрасываем флаг «трубка поднята»
 *  - OFFHOOK  → вызов принят (трубка поднята); если RINGING не было — исходящий
 *  - IDLE     → вызов завершён:
 *    * RINGING + не OFFHOOK → пропущенный
 *    * RINGING + OFFHOOK   → принятый входящий
 *    * OFFHOOK без RINGING → исходящий
 */
object CallReceiverLogic {

    private var ringingNumber: String? = null
    private var outgoingNumber: String? = null
    private var callAnswered = false

    /** Окно «свежести» для fallback-поиска пропущенного в CallLog. */
    private const val RECENT_WINDOW_MS = 2 * 60_000L

    /** Сброс состояния (для тестов). */
    @Synchronized
    fun reset() {
        ringingNumber = null
        outgoingNumber = null
        callAnswered = false
    }

    /**
     * Обрабатывает смену состояния телефона. Вызывается из CallReceiver.
     * Возвращает Triple(text, type, label) или null.
     */
    @Synchronized
    fun onPhoneStateChanged(
        context: Context,
        state: String?,
        number: String?
    ): Triple<String, String, String>? {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                ringingNumber = number
                callAnswered = false
                outgoingNumber = null
                return null
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                callAnswered = true
                if (ringingNumber == null) {
                    outgoingNumber = number
                }
                return null
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val numberAtRinging = ringingNumber
                val numberOutgoing = outgoingNumber
                val wasAnswered = callAnswered
                ringingNumber = null
                outgoingNumber = null
                callAnswered = false

                return when {
                    // Входящий вызов был (RINGING) и принят
                    numberAtRinging != null && wasAnswered -> {
                        val text = buildEvent(context, numberAtRinging, "incoming")
                        Triple(text, "incoming", "Входящий")
                    }
                    // Входящий вызов был, но не принят — пропущенный
                    numberAtRinging != null && !wasAnswered -> {
                        val candidate = if (hasCallLogPermission(context)) {
                            if (looksMissed(context, numberAtRinging)) numberAtRinging else null
                        } else {
                            numberAtRinging
                        }
                        candidate?.let {
                            Triple(buildEvent(context, it, "missed"), "missed", "Пропущенный")
                        }
                    }
                    // Исходящий вызов (OFFHOOK без RINGING)
                    numberOutgoing != null -> {
                        val text = buildEvent(context, numberOutgoing, "outgoing")
                        Triple(text, "outgoing", "Исходящий")
                    }
                    // RINGING потерян — ищем свежий пропущенный в CallLog
                    else -> {
                        val recent = findRecentMissed(context)
                        recent?.let {
                            Triple(buildEvent(context, it, "missed"), "missed", "Пропущенный")
                        }
                    }
                }
            }
        }
        return null
    }

    /** Проверка по CallLog: последний вызов с этого номера — MISSED. */
    private fun looksMissed(context: Context, number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        return try {
            val cr = context.contentResolver
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls.TYPE, CallLog.Calls.NUMBER)
            val sort = CallLog.Calls.DATE + " DESC LIMIT 20"
            cr.query(uri, projection, null, null, sort)?.use { c ->
                val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                val numCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                while (c.moveToNext()) {
                    val callDigits = (c.getString(numCol) ?: "").filter { it.isDigit() }
                    if (callDigits == digits) {
                        return c.getInt(typeCol) == CallLog.Calls.MISSED_TYPE
                    }
                }
                false
            } ?: false
        } catch (e: SecurityException) {
            true
        } catch (e: Exception) {
            true
        }
    }

    /** Свежий пропущенный вызов из CallLog (для случая потерянного RINGING). */
    private fun findRecentMissed(context: Context): String? {
        return try {
            val cr = context.contentResolver
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.DATE)
            val sort = CallLog.Calls.DATE + " DESC"
            val now = System.currentTimeMillis()
            cr.query(uri, projection, null, null, sort)?.use { c ->
                val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                val numCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    if (now - c.getLong(dateCol) > RECENT_WINDOW_MS) return null
                    if (c.getInt(typeCol) == CallLog.Calls.MISSED_TYPE) {
                        return c.getString(numCol)
                    }
                }
                null
            } ?: null
        } catch (e: Exception) {
            null
        }
    }

    private fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildEvent(
        context: Context,
        number: String,
        type: String
    ): String {
        val name = ContactNames.lookup(context, number)
        val now = System.currentTimeMillis()
        val sim = SimInfo.describe(context, null)
        val template = when (type) {
            "incoming" -> Prefs.messageTemplateIncomingCall
            "outgoing" -> Prefs.messageTemplateOutgoingCall
            else -> Prefs.messageTemplateCall
        }
        return TemplateFormatter.format(
            template = template,
            sender = number,
            name = name,
            text = "",
            timestamp = now,
            type = type,
            sim = sim
        )
    }
}

class CallReceiver : android.content.BroadcastReceiver() {

    /** Результат обработки: текст, тип, уведомление. */
    private data class CallResult(val text: String, val type: String, val notifyTitle: String)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs.callsEnabled) return
        if (QuietHours.isActiveNow()) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        ReceiverExecutor.goAsync(this) {
            val result = CallReceiverLogic.onPhoneStateChanged(context, state, number)
                ?: return@goAsync

            val (text, type, label) = result

            // Проверяем, включена ли пересылка для данного типа
            val enabled = when (type) {
                "incoming" -> Prefs.incomingCallsEnabled
                "outgoing" -> Prefs.outgoingCallsEnabled
                else -> Prefs.callsEnabled // missed
            }
            if (!enabled) return@goAsync

            // Локальное уведомление
            com.ozyab.smsforwarder.util.LocalNotifier.notify(
                context,
                title = "📵 $label: $number",
                text = label,
            )

            com.ozyab.smsforwarder.service.ForwardService.start(
                context, text, type = type, sender = number ?: ""
            )
        }
    }
}
