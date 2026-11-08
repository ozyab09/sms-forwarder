package com.ozyab.smsforwarder.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Монитор батареи (#161): слушает ACTION_BATTERY_CHANGED и отдаёт события
 * «полный заряд» (>= порога 80..100) и «низкий заряд» (<= порога 0..30).
 *
 * Анти-спам: события формируются на ПЕРЕХОДЕ порога, а не на каждом изменении
 * уровня (ACTION_BATTERY_CHANGED приходит очень часто). Повторное событие —
 * только после выхода за порог обратно:
 *  - полный заряд: после достижения порога молчим, пока заряд не упадёт ниже порога;
 *  - низкий заряд: после срабатывания молчим, пока заряд не поднимется выше порога
 *    (иначе на 15% шло бы сообщение на каждое обновление состояния батареи).
 *
 * Состояние — in-memory процесса: после перезагрузки/смерти процесса одно
 * возможное повторное сообщение не критично, а персистентность усложнила бы код.
 */
class BatteryMonitor(
    private val context: Context,
    private val onEvent: (event: BatteryEvent) -> Unit,
) {

    /** Запись зарядной истории: уровень и timestamp (мс). */
    private data class ChargeRecord(val level: Int, val epochMs: Long)

    sealed class BatteryEvent(val kind: Kind) {
        data class Full(val level: Int) : BatteryEvent(Kind.FULL)
        data class Low(val level: Int) : BatteryEvent(Kind.LOW)
        data class ChargeStep(
            val level: Int,
            val voltageMv: Int,
            val currentMa: Int,
            val etaMinutes: Int? = null,
        ) : BatteryEvent(Kind.CHARGE_STEP)
    }

    enum class Kind { FULL, LOW, CHARGE_STEP }

    /** Анти-спам: событие уже отправлено и порог ещё не был «разряжен» обратно. */
    private var fullFired = false
    private var lowFired = false

    // --- Трекинг прогресса зарядки (#NEW) ---
    /** Последний уровень, после которого сработал шаг зарядки. -1 = не отслеживаем. */
    private var lastChargeStepLevel = -1

    /** История зарядки для ETA: последние записи (уровень, timestamp). */
    private val chargeHistory = mutableListOf<ChargeRecord>()
    /** Максимальное число записей в истории (скользящее окно ~30 минут при шаге 10% каждые ~10 мин). */
    private val maxChargeHistory = 50

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            // EXTRA_VOLTAGE — мВ (доступно при зарядке). CURRENT не доступен через BatteryManager на всех устройствах.
            val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val currentMa = tryLoadCurrentFromSticky(intent)
            handleLevel(pct, voltageMv, currentMa)
        }
    }

    fun handleLevel(pct: Int) {
        handleLevel(pct, -1, -1)
    }

    fun handleLevel(pct: Int, voltageMv: Int, currentMa: Int) {
        val fullEnabled = Prefs.batteryFullEnabled
        val fullThreshold = Prefs.batteryFullThreshold
        val lowEnabled = Prefs.batteryLowEnabled
        val lowThreshold = Prefs.batteryLowThreshold

        // Полный заряд: событие при подъёме ДО/выше порога, повтор — после
        // падения ниже порога (гистерезис).
        if (fullEnabled && !fullFired && pct >= fullThreshold) {
            fullFired = true
            onEvent(BatteryEvent.Full(pct))
        } else if (pct < fullThreshold) {
            fullFired = false
        }

        // Низкий заряд: событие при падении ДО/ниже порога, повтор — после
        // подъёма выше порога.
        if (lowEnabled && !lowFired && pct <= lowThreshold) {
            lowFired = true
            onEvent(BatteryEvent.Low(pct))
        } else if (pct > lowThreshold) {
            lowFired = false
        }

        // --- Трекинг прогресса зарядки (#ETA) ---
        // Записываем точку истории для расчёта скорости зарядки.
        // Обновляем только при увеличении уровня (зарядка).
        if (lastChargeStepLevel >= 0 && pct > lastChargeStepLevel || 
            (lastChargeStepLevel == 0 && chargeHistory.isNotEmpty())) {
            // Не дублируем запись с тем же уровнем в пределах ~5 секунд
            val lastRecord = chargeHistory.lastOrNull()
            if (lastRecord == null || 
                lastRecord.level != pct || 
                System.currentTimeMillis() - lastRecord.epochMs > 5000) {
                if (chargeHistory.size >= maxChargeHistory) chargeHistory.removeAt(0)
                chargeHistory.add(ChargeRecord(pct, System.currentTimeMillis()))
            }
        }
        // Событие «шаг» срабатывает когда зарядка активна и накопилось N%
        // от последнего сработавшего шага (или от начального уровня, если это первый).
        val stepEnabled = Prefs.chargingStepEnabled
        val stepThreshold = Prefs.chargingStepThreshold
        if (stepEnabled && lastChargeStepLevel >= 0 && pct > lastChargeStepLevel &&
            (pct - lastChargeStepLevel) >= stepThreshold
        ) {
            val eta = estimateEtaMinutes()
            onEvent(BatteryEvent.ChargeStep(pct, voltageMv, currentMa, eta))
            lastChargeStepLevel = pct
        }
    }

    /**
     * Ток зарядки в мА.
     *
     *  BatteryManager.EXTRA_CURRENT // todo workaround официально есть только в Android 5–7;
     * на более новых версиях intent не содержит EXTRA_CURRENT на некоторых OEM.
     * Пробуя читать через защищённое поле, мы ломаемся с NoSuchFieldError на
     * чистых SDK. Достаём ток из sticky-интента ACTION_BATTERY_CHANGED
     * (результат кешируется на время жизни процесса), чтобы не ловить
     * ошибку только по току.
     */
    /**
     * Ток зарядки в мА.
     */
    /**
     * Ток зарядки в мА.
     */
    private fun tryLoadCurrentFromSticky(intent: Intent): Int {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return if (sticky != null) tryReflectionCurrent(sticky) else -1
    }

    private fun tryReflectionCurrent(sticky: Intent): Int {
        val field = try {
            java.lang.Class.forName("android.os.BatteryManager").getDeclaredField("EXTRA_CURRENT")
        } catch (_: ClassNotFoundException) {
            return -1
        } catch (_: NoSuchFieldException) {
            return -1
        }
        return try {
            field.isAccessible = true
            val value = field.get(null)
            if (value is Int) value else -1
        } catch (_: IllegalAccessException) {
            -1
        }.also { raw -> if (raw in 0..Int.MAX_VALUE) return raw }
        return -1
    }

    /**
     * Зарядное устройство подключено: сбрасываем счётчик шагов,
     * чтобы первый шаг считался от текущего уровня.
     */
    /**
     * Зарядное устройство подключено: сбрасываем счётчик шагов,
     * чтобы первый шаг считался от текущего уровня.
     * Очищаем также историю зарядки — она привязана к конкретной сессии зарядки.
     */
    fun onPowerConnected(startLevel: Int) {
        lastChargeStepLevel = startLevel
        chargeHistory.clear()
        // Начинаем запись с начального уровня
        if (startLevel >= 0) {
            chargeHistory.add(ChargeRecord(startLevel, System.currentTimeMillis()))
        }
    }

    /**
     * Зарядное устройство отключено: сбрасываем step-счётчик
     * (чтобы на следующей зарядке шаги шли заново, а не продолжались).
     * Сбрасываем историю зарядки.
     */
    fun onPowerDisconnected() {
        lastChargeStepLevel = -1
        chargeHistory.clear()
    }

    /**
     * Оценка ETA (минут до 100%) на основе скорости зарядки.
     *
     * Использует последние данные истории (по умолчанию последние 30 минут).
     * Если данных недостаточно — возвращает null.
     */
    internal fun estimateEtaMinutes(): Int? {
        val now = System.currentTimeMillis()
        // Берём только записи из последних 30 минут
        val windowStart = now - 30L * 60 * 1000
        val recent = chargeHistory.filter { it.epochMs >= windowStart }

        if (recent.size < 2) return null

        // Находим первую и последнюю запись в окне
        val first = recent.first()
        val last = recent.last()

        val deltaPct = last.level - first.level
        val deltaMin = (last.epochMs - first.epochMs) / 60_000.0

        if (deltaPct <= 0 || deltaMin <= 0.5) return null // слишком мало данных / обратная тяга

        val pctPerMin = deltaPct / deltaMin
        val remaining = 100 - last.level
        if (remaining <= 0) return 0

        val etaMin = remaining / pctPerMin
        // Ограничиваем разумными значениями
        return etaMin.toInt().coerceIn(0, 999)
    }

    /** Регистрирует receiver (идемпотентно). Вызывать при старте сервиса. */
    fun start() {
        if (registered) return
        registered = true
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /** Снимает receiver. */
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { e -> LogStore.warn("BatteryMonitor stop: ${e.message}") }
    }
}
