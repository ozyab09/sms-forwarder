package com.ozyab.smsforwarder.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Монитор зарядного устройства (#171): слушает ACTION_POWER_CONNECTED /
 * ACTION_POWER_DISCONNECTED и отдаёт события подключения/отключения зарядки.
 *
 * В отличие от BatteryMonitor (ACTION_BATTERY_CHANGED), эти броадкасты
 * приходят редко (только при реальном изменении состояния), поэтому
 * анти-спам не нужен — каждое событие уникально.
 *
 * Вместе с событием отдаётся текущий процент заряда (может быть null, если
 * система не вернула значение) — чтобы в сообщении было видно, при каком
 * уровне подключили/отключили зарядку.
 */
class ChargerMonitor(
    private val context: Context,
    private val onEvent: (kind: EventKind, level: Int?) -> Unit,
) {

    enum class EventKind { CONNECTED, DISCONNECTED }

    private var registered = false

    private val connectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                onEvent(EventKind.CONNECTED, currentLevel())
            }
        }
    }

    private val disconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
                onEvent(EventKind.DISCONNECTED, currentLevel())
            }
        }
    }

    /**
     * Текущий заряд в процентах (0..100) или null, если система не отдала
     * значение. Сначала BatteryManager, затем sticky-интент ACTION_BATTERY_CHANGED.
     */
    fun currentLevel(): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (capacity in 0..100) return capacity

        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    /** Регистрирует receivers (идемпотентно). Вызывать при старте сервиса. */
    fun start() {
        if (registered) return
        registered = true
        context.registerReceiver(connectedReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        context.registerReceiver(disconnectedReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
    }

    /** Снимает receivers. */
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(connectedReceiver) }
            .onFailure { e -> LogStore.warn("ChargerMonitor stop connected: ${e.message}") }
        runCatching { context.unregisterReceiver(disconnectedReceiver) }
            .onFailure { e -> LogStore.warn("ChargerMonitor stop disconnected: ${e.message}") }
    }
}
