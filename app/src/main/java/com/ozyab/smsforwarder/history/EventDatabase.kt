package com.ozyab.smsforwarder.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * База данных истории событий.
 *
 * Singleton: одна БД на приложение. Создаётся лениво при первом обращении.
 * Privacy-first: хранится локально, шифрование на уровне файла не требуется —
 * здесь нет токенов, только история пересылки.
 */
@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var instance: EventDatabase? = null

        fun get(context: Context): EventDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "event_history.db"
                )
                    // История не критична: падение БД не должно ронять приложение
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}