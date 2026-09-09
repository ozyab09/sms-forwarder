package com.ozyab.smsforwarder

import android.app.Application
import com.ozyab.smsforwarder.util.Prefs

class SmsForwarderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Prefs инициализируются асинхронно: MasterKey/EncryptedSharedPreferences
        // создаются в фоне, холодный старт не блокируется. Первый доступ к
        // настройкам из любого компонента дождётся завершения инициализации.
        Prefs.init(this)
    }
}