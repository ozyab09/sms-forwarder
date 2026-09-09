package com.ozyab.smsforwarder

import android.app.Application
import com.ozyab.smsforwarder.util.Prefs

class SmsForwarderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Prefs должны быть готовы до любого компонента (Activity/Receiver/Service)
        Prefs.init(this)
    }
}