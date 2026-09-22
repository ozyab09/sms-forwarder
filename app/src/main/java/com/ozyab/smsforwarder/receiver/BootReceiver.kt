package com.ozyab.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ozyab.smsforwarder.service.ForwardService
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.ReceiverExecutor

/**
 * Автостарт сервиса после перезагрузки устройства и после обновления приложения.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReceiverExecutor.goAsync(this) {
                // Prefs.isConfigured() читает secure prefs/DataStore — не на main thread
                if (Prefs.isConfigured()) {
                    ForwardService.start(context)
                }
            }
        }
    }
}
