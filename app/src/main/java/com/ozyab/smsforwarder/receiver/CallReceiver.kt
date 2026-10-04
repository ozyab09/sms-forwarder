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
 * Событие звонка: готовый текст, тип и человекочитаемая метка для уведомления.
 */
data class CallEvent(val text: String, val type: String, val label: String, val number: String)

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
    private var callConnectTimeMs: Long = 0L
    /** SIM звонка (subscriptionId из интента) — для верной атрибуции {sim}. */
    private var lastSubId: Int? = null

    /** Окно «свежести» для fallback-поиска пропущенного в CallLog. */
    private const val RECENT_WINDOW_MS = 2 * 60_000L

    /** Ретраи чтения CallLog при IDLE: запись появляется с задержкой (#166). */
    internal var CALL_LOG_RETRIES = 3
    internal var CALL_LOG_RETRY_DELAY_MS = 150L

    /** Сброс состояния (для тестов). */
    @Synchronized
    fun reset() {
        lastSubId = null
        ringingNumber = null
        outgoingNumber = null
        callAnswered = false
        callConnectTimeMs = 0L
    }

    /**
     * Обрабатывает смену состояния телефона. Вызывается из CallReceiver.
     * Возвращает [CallEvent] или null.
     *
     * ВАЖНО: RINGING-броадкасты приходят пачкой, причём EXTRA_INCOMING_NUMBER
     * есть не в каждом (dual-SIM, ряд OEM). Перезаписываем запомненный номер
     * только непустым значением — иначе «пустой» RINGING теряет номер звонящего.
     */
    @Synchronized
    fun onPhoneStateChanged(
        context: Context,
        state: String?,
        number: String?,
        subId: Int? = null
    ): CallEvent? {
        // subId запоминается на RINGING/OFFHOOK и используется при формировании
        // события на IDLE (SIM, на которой был звонок)
        if (subId != null) {
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING,
                TelephonyManager.EXTRA_STATE_OFFHOOK -> lastSubId = subId
            }
        }
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!number.isNullOrBlank()) ringingNumber = number
                callAnswered = false
                outgoingNumber = null
                return null
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                callAnswered = true
                callConnectTimeMs = System.currentTimeMillis()
                if (ringingNumber == null) {
                    outgoingNumber = number
                }
                return null
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val numberAtRinging = ringingNumber
                val numberOutgoing = outgoingNumber
                val wasAnswered = callAnswered
                val connectTime = callConnectTimeMs
                ringingNumber = null
                outgoingNumber = null
                callAnswered = false
                callConnectTimeMs = 0L
                val simSubId = lastSubId
                lastSubId = null

                // Длительность: от OFFHOOK до IDLE (только для принятых/исходящих)
                val durationMs = if (connectTime > 0L) {
                    (System.currentTimeMillis() - connectTime)
                } else null

                return when {
                    // Входящий вызов был (RINGING) и принят
                    numberAtRinging != null && wasAnswered -> {
                        val text = buildEvent(context, numberAtRinging, "incoming", durationMs, simSubId)
                        CallEvent(text, "incoming", context.getString(com.ozyab.smsforwarder.R.string.call_label_incoming), numberAtRinging)
                    }
                    // Входящ��й вызов был, но не принят — пропущенный
                    numberAtRinging != null && !wasAnswered -> {
                        val candidate = if (hasCallLogPermission(context)) {
                            if (looksMissed(context, numberAtRinging)) numberAtRinging else null
                        } else {
                            numberAtRinging
                        }
                        candidate?.let { CallEvent(buildEvent(context, it, "missed", null, simSubId), "missed", context.getString(com.ozyab.smsforwarder.R.string.call_label_missed), it) }
                    }
                    // Исходящий вызов (OFFHOOK без RINGING) — длительность из CallLog
                    // (#163, #166): OFFHOOK — момент набора номера, а не соединения,
                    // поэтому локальный расчёт завышает длительность. DURATION=0 —
                    // недозвон: событие «не дозвонился» без длительности. Запись
                    // CallLog может появиться с задержкой — короткие ретраи. Fallback
                    // на локальный расчёт — только если записи нет/нет разрешения.
                    numberOutgoing != null -> {
                        val result = resolveOutgoing(context, numberOutgoing, durationMs, simSubId)
                        result
                    }
                    // RINGING потерян — ищем свежий пропущенный в CallLog
                    else -> {
                        val recent = findRecentMissed(context)
                        recent?.let { CallEvent(buildEvent(context, it, "missed", null, simSubId), "missed", context.getString(com.ozyab.smsforwarder.R.string.call_label_missed), it) }
                    }
                }
            }
        }
        return null
    }

    /** Результат чтения CallLog для исходящего вызова. */
    private enum class OutgoingLog {
        /** Записи нет (пока) или нет разрешения — использовать fallback. */
        NO_RECORD,
        /** Недозвон (DURATION = 0). */
        UNANSWERED,
        /** Соединение состоялось, длительность известна. */
        ANSWERED,
    }

    /**
     * Длительность/статус исходящего из CallLog (#163, #166).
     *
     * ВАЖНО про DURATION=0: это НЕ «записи нет», а признак недозвона — раньше
     * он ошибочно трактовался как «не найдено» и включал fallback на локальный
     * расчёт, из-за чего недозвоны получали «длительность» от набора до сброса.
     *
     * @param durationMs сюда пишется найденная длительность (мс), если ANSWERED
     * @return статус чтения
     */
    private fun callLogOutgoing(context: Context, number: String, outDurationMs: LongArray): OutgoingLog {
        if (!hasCallLogPermission(context)) return OutgoingLog.NO_RECORD
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return OutgoingLog.NO_RECORD
        return try {
            val cr = context.contentResolver
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE)
            val sort = CallLog.Calls.DATE + " DESC LIMIT 20"
            val now = System.currentTimeMillis()
            var found = OutgoingLog.NO_RECORD
            cr.query(uri, projection, null, null, sort)?.use { c ->
                val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                val numCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                val durCol = c.getColumnIndex(CallLog.Calls.DURATION)
                val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    if (now - c.getLong(dateCol) > RECENT_WINDOW_MS) return found
                    if (c.getInt(typeCol) != CallLog.Calls.OUTGOING_TYPE) continue
                    val callDigits = (c.getString(numCol) ?: "").filter { it.isDigit() }
                    if (callDigits == digits) {
                        val sec = c.getLong(durCol)
                        if (sec > 0) {
                            outDurationMs[0] = sec * 1000
                            return OutgoingLog.ANSWERED
                        }
                        return OutgoingLog.UNANSWERED
                    }
                }
            }
            found
        } catch (e: Exception) {
            OutgoingLog.NO_RECORD
        }
    }

    /**
     * Разрешение исходящего вызова (#166).
     *
     * Запись CallLog появляется не мгновенно при IDLE (гонка записи системой),
     * поэтому чтение ретраится несколько раз. Итоги:
     *  - ANSWERED → событие "outgoing" с фактической длительностью соединения;
     *  - UNANSWERED → событие "unanswered" (недозвон) без длительности;
     *  - NO_RECORD после ретраев → fallback: локальный расчёт, если он > 0
     *    (соединение было), иначе тоже "unanswered".
     */
    private fun resolveOutgoing(
        context: Context,
        number: String,
        localDurationMs: Long?,
        simSubId: Int?,
    ): CallEvent {
        val durationHolder = LongArray(1)
        var status = OutgoingLog.NO_RECORD
        for (attempt in 1..CALL_LOG_RETRIES) {
            status = callLogOutgoing(context, number, durationHolder)
            if (status != OutgoingLog.NO_RECORD) break
            if (attempt < CALL_LOG_RETRIES) {
                try {
                    Thread.sleep(CALL_LOG_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        return when (status) {
            OutgoingLog.ANSWERED -> {
                val text = buildEvent(context, number, "outgoing", durationHolder[0], simSubId)
                CallEvent(text, "outgoing", context.getString(com.ozyab.smsforwarder.R.string.call_label_outgoing), number)
            }
            OutgoingLog.UNANSWERED -> {
                val text = buildEvent(context, number, "unanswered", null, simSubId)
                CallEvent(text, "unanswered", context.getString(com.ozyab.smsforwarder.R.string.call_label_unanswered), number)
            }
            OutgoingLog.NO_RECORD -> {
                // Нет записи (разрешение отозвано/OEM-квирк) — локальный расчёт:
                // > 0 → считаем соединение состоявшимся (лучше приблизительно,
                // чем молчать); 0/нет данных → недозвон.
                if (localDurationMs != null && localDurationMs > 0) {
                    val text = buildEvent(context, number, "outgoing", localDurationMs, simSubId)
                    CallEvent(text, "outgoing", context.getString(com.ozyab.smsforwarder.R.string.call_label_outgoing), number)
                } else {
                    val text = buildEvent(context, number, "unanswered", null, simSubId)
                    CallEvent(text, "unanswered", context.getString(com.ozyab.smsforwarder.R.string.call_label_unanswered), number)
                }
            }
        }
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
        type: String,
        durationMs: Long? = null,
        subId: Int? = null
    ): String {
        val name = ContactNames.lookup(context, number)
        val now = System.currentTimeMillis()
        // T2 (аудит-3): SIM из интента звонка, а не «первая активная»
        val sim = SimInfo.describe(context, subId)
        return TemplateFormatter.format(
            sender = number,
            name = name,
            text = "",
            timestamp = now,
            type = type,
            sim = sim,
            durationMs = durationMs,
            // {duration} по локали устройства, а не всегда по-русски (#139)
            durationFormatter = TemplateFormatter.localizedDuration(context)
        )
    }
}

class CallReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        // SIM, на которой звонили (dual-SIM, #151-аудит-3): раньше всегда брали
        // «первую активную» — неверная атрибуция для второй SIM.
        val subId = intent.getIntExtra("subscription", -1).takeIf { it != -1 }

        ReceiverExecutor.goAsync(this) {
            // Настройки читаем в фоновом потоке: awaitReady() в Prefs может
            // блокировать до 5 c — на main thread это риск ANR.
            // Мастер-выключатель: «Стоп» означает остановку пересылки до «Запустить»
            if (!Prefs.forwardingEnabled) return@goAsync
            if (!Prefs.callsEnabled) return@goAsync

            val result = CallReceiverLogic.onPhoneStateChanged(context, state, number, subId)
                ?: return@goAsync

            // Тихие часы: режим «накопления» (#170) — событие уходит после
            // окончания интервала; режим «отбрасывания» — старое поведение.
            if (QuietHours.isActiveNow()) {
                if (Prefs.quietHoursAccumulate) {
                    com.ozyab.smsforwarder.service.ForwardService.start(
                        context, result.text, type = result.type, sender = result.number,
                        nextRetryAt = QuietHours.endTimestampMs(),
                    )
                }
                return@goAsync
            }

            // Проверяем, включена ли пересылка для данного типа
            val enabled = when (result.type) {
                "incoming" -> Prefs.incomingCallsEnabled
                "outgoing" -> Prefs.outgoingCallsEnabled
                // Недозвон исходящего (#166): привязан к исходящим — отдельного
                // переключателя нет, иначе пользователь не сможет выключить
                // «мусорные» события недозвонов, не выключив исходящие целиком
                "unanswered" -> Prefs.outgoingCallsEnabled
                else -> Prefs.callsEnabled // missed
            }
            if (!enabled) return@goAsync

            com.ozyab.smsforwarder.service.ForwardService.start(
                context, result.text, type = result.type, sender = result.number
            )
        }
    }
}
