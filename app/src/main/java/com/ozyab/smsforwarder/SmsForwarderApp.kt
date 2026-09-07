package com.ozyab.smsforwarder

import android.app.Application
import com.ozyab.smsforwarder.util.Prefs

class SmsForwarderApp : Application() {

    companion object {
        lateinit var instance: SmsForwarderApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Prefs должны быть готовы до любого компонента (Activity/Receiver/Service)
        Prefs.init(this)
    }
}