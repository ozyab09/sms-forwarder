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
    private val onEvent: (kind: EventKind, level: Int) -> Unit,
) {

    enum class EventKind { FULL, LOW }

    /** Анти-спам: событие уже отправлено и порог ещё не был «разряжен» обратно. */
    private var fullFired = false
    private var lowFired = false

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            handleLevel(pct)
        }
    }

    fun handleLevel(pct: Int) {
        val fullEnabled = Prefs.batteryFullEnabled
        val fullThreshold = Prefs.batteryFullThreshold
        val lowEnabled = Prefs.batteryLowEnabled
        val lowThreshold = Prefs.batteryLowThreshold

        // Полный заряд: событие при подъёме ДО/выше порога, повтор — после
        // падения ниже порога (гистерезис).
        if (fullEnabled && !fullFired && pct >= fullThreshold) {
            fullFired = true
            onEvent(EventKind.FULL, pct)
        } else if (pct < fullThreshold) {
            fullFired = false
        }

        // Низкий заряд: событие при падении ДО/ниже порога, повтор — после
        // подъёма выше порога.
        if (lowEnabled && !lowFired && pct <= lowThreshold) {
            lowFired = true
            onEvent(EventKind.LOW, pct)
        } else if (pct > lowThreshold) {
            lowFired = false
        }
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
