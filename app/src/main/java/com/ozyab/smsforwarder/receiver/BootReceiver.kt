package com.ozyab.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ozyab.smsforwarder.service.ForwardService

/**
 * Автостарт сервиса после перезагрузки устройства и после обновления приложения.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (com.ozyab.smsforwarder.util.Prefs.isConfigured()) {
                    ForwardService.start(context)
                }
            }
        }
    }
}