package com.ozyab.smsforwarder.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База данных истории событий.
 *
 * Singleton: одна БД на приложение. Создаётся лениво при первом обращении.
 * Privacy-first: хранится локально, шифрование на уровне файла не требуется —
 * здесь нет токенов, только история пересылки.
 */
@Database(entities = [EventEntity::class], version = 2, exportSchema = false)
abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var instance: EventDatabase? = null

        /**
         * v1 → v2: добавлены колонки «кому» и «через какого бота» — без потери
         * уже накопленной истории (диалог деталей события).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN chatId TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN botUsername TEXT")
            }
        }

        fun get(context: Context): EventDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "event_history.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // История не критична: падение БД не должно ронять приложение
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}