package com.ozyab.smsforwarder.receiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ozyab.smsforwarder.util.ContactNames
import com.ozyab.smsforwarder.util.ReceiverExecutor
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.SimInfo
import com.ozyab.smsforwarder.util.TemplateFormatter

/**
 * Отслеживание пропущенных вызовов.
 *
 * Логика (state machine по PHONE_STATE):
 *  - RINGING  → запоминаем номер, сбрасываем флаг «трубка поднята»
 *  - OFFHOOK  → вызов принят (трубка поднята)
 *  - IDLE     → вызов завершён: если был RINGING и не было OFFHOOK — пропущенный.
 *    Если RINGING не видели (бродкаст потерян) — ищем свежий пропущенный в CallLog.
 *
 * Когда есть READ_CALL_LOG, решение подтверждается CallLog'ом (точное сравнение
 * по цифрам), чтобы принятые вызовы не пересылались как пропущенные (например,
 * при потерянном OFFHOOK-бродкасте). Без разрешения полагаемся на state machine.
 */
object CallReceiverLogic {

    private var ringingNumber: String? = null
    private var callAnswered = false

    /** Окно «свежести» для fallback-поиска пропущенного в CallLog. */
    private const val RECENT_WINDOW_MS = 2 * 60_000L

    /**
     * Обрабатывает смену состояния телефона. Вызывается из CallReceiver.
     * Возвращает текст события пропущенного вызова или null.
     */
    @Synchronized
    fun onPhoneStateChanged(context: Context, state: String?, number: String?): String? {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                ringingNumber = number
                callAnswered = false
                return null
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Трубка поднята — вызов принят
                callAnswered = true
                return null
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val numberAtRinging = ringingNumber
                val wasAnswered = callAnswered
                ringingNumber = null
                callAnswered = false

                val candidate: String? = when {
                    // Видели RINGING: пропущенный, если трубку не подняли
                    numberAtRinging != null && !wasAnswered -> numberAtRinging
                    // RINGING потерян — ищем свежий пропущенный в CallLog
                    numberAtRinging == null -> findRecentMissed(context)
                    // RINGING был и вызов принят — не пропущенный
                    else -> null
                }

                // С READ_CALL_LOG подтверждаем по CallLog (защита от потерянного OFFHOOK)
                val missed = if (candidate != null && hasCallLogPermission(context)) {
                    if (looksMissed(context, candidate)) candidate else null
                } else {
                    candidate
                }

                return missed?.let { buildEvent(context, it) }
            }
        }
        return null
    }

    /** Проверка по CallLog: последний вызов с этого номера — MISSED. Точное сравнение по цифрам. */
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
            // Разрешение отозвано между проверкой и запросом — полагаемся на state machine
            true
        } catch (e: Exception) {
            // Любая другая ошибка — решение state machine остаётся в силе
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
                    if (now - c.getLong(dateCol) > RECENT_WINDOW_MS) return null // дальше только старые
                    if (c.getInt(typeCol) == CallLog.Calls.MISSED_TYPE) {
                        return c.getString(numCol)
                    }
                }
                null
            } ?: null
        } catch (e: Exception) {
            null // нет разрешения / ошибка — не пересылаем ничего
        }
    }

    private fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildEvent(context: Context, number: String): String {
        val name = ContactNames.lookup(context, number)
        val now = System.currentTimeMillis()
        // SIM: из PHONE_STATE нет subscriptionId — берём первую активную SIM
        val sim = SimInfo.describe(context, null)
        return TemplateFormatter.format(
            template = Prefs.messageTemplateCall,
            sender = number,
            name = name,
            text = "",
            timestamp = now,
            type = "missed",
            sim = sim
        )
    }
}

class CallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs.callsEnabled) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        // Тяжёлая часть (CallLog, контакты) — не на главном потоке
        ReceiverExecutor.goAsync(this) {
            val text = CallReceiverLogic.onPhoneStateChanged(context, state, number) ?: return@goAsync
            com.ozyab.smsforwarder.service.ForwardService.start(context, text)
        }
    }
}