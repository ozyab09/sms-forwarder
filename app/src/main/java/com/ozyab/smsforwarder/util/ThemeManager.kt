package com.ozyab.smsforwarder.util

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Применение темы оформления (светлая / тёмная / по системе).
 *
 * Вызывается в onCreate каждой Activity ДО setContentView: читает выбранный
 * режим из [Prefs.themeMode] и применяет его глобально через
 * [AppCompatDelegate.setDefaultNightMode]. Смена режима в «О приложении»
 * пересоздаёт Activity автоматически.
 */
object ThemeManager {

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    /** Применяет сохранённый режим темы; безопасно вызывать из любой Activity. */
    fun apply(activity: AppCompatActivity) {
        val mode = when (Prefs.themeMode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Сохраняет режим и применяет его (Activity пересоздаётся автоматически). */
    fun setAndApply(activity: AppCompatActivity, mode: String) {
        Prefs.themeMode = mode
        apply(activity)
    }
}