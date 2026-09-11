package com.ozyab.smsforwarder

import android.app.Application
import com.ozyab.smsforwarder.util.Prefs
import timber.log.Timber

class SmsForwarderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Timber: в debug-сборках пишем в logcat (тег = класс), в release — молча.
        // LogStore дублирует записи в Timber, так что в debug всё видно в logcat.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Prefs инициализируются асинхронно: MasterKey/EncryptedSharedPreferences
        // создаются в фоне, холодный старт не блокируется. Первый доступ к
        // настройкам из любого компонента дождётся завершения инициализации.
        Prefs.init(this)
    }
}