package com.ozyab.smsforwarder.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Монитор зарядного устройства (#171): слушает ACTION_POWER_CONNECTED /
 * ACTION_POWER_DISCONNECTED и отдаёт события подключения/отключения зарядки.
 *
 * В отличие от BatteryMonitor (ACTION_BATTERY_CHANGED), эти броадкасты
 * приходят редко (только при реальном изменении состояния), поэтому
 * анти-спам не нужен — каждое событие уникально.
 */
class ChargerMonitor(
    private val context: Context,
    private val onEvent: (kind: EventKind) -> Unit,
) {

    enum class EventKind { CONNECTED, DISCONNECTED }

    private var registered = false

    private val connectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                onEvent(EventKind.CONNECTED)
            }
        }
    }

    private val disconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
                onEvent(EventKind.DISCONNECTED)
            }
        }
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