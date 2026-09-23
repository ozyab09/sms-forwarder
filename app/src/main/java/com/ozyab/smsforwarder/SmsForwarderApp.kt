package com.ozyab.smsforwarder

import android.app.Application
import android.app.NotificationManager
import android.content.Context
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
        deleteOrphanNotificationChannel()
    }

    /**
     * Канал «Входящие сообщения» остался у существующих установок после
     * удаления фичи «Уведомления на телефоне» (#151-аудит-3) — убираем его
     * из системных настроек, чтобы не висел мёртвым.
     */
    private fun deleteOrphanNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.deleteNotificationChannel("local_notifications") }
    }
}