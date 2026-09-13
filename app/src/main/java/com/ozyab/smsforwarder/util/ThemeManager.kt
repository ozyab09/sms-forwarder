package com.ozyab.smsforwarder.util

import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.ozyab.smsforwarder.R

/**
 * Применение темы оформления (светлая / тёмная / по системе) + акцентный цвет.
 *
 * Вызывается в onCreate каждой Activity ДО setContentView: читает выбранный
 * режим из [Prefs.themeMode] и акцент из [Prefs.accentColor], применяет
 * тему глобально через [AppCompatDelegate.setDefaultNightMode] и
 * накладывает стиль акцента через [AppCompatDelegate.setDefaultNightMode].
 */
object ThemeManager {

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    const val ACCENT_TEAL = "teal"
    const val ACCENT_GREEN = "green"
    const val ACCENT_RED = "red"
    const val ACCENT_BLUE = "blue"
    const val ACCENT_PURPLE = "purple"
    const val ACCENT_ORANGE = "orange"
    const val ACCENT_GREY = "grey"

    /** Все доступные акценты для UI (порядок = порядок отображения). */
    val ACCENT_COLORS = listOf(
        ACCENT_TEAL, ACCENT_GREEN, ACCENT_RED, ACCENT_BLUE,
        ACCENT_PURPLE, ACCENT_ORANGE, ACCENT_GREY
    )

    /** Применяет сохранённый режим темы + акцент; безопасно вызывать из любой Activity. */
    fun apply(activity: AppCompatActivity) {
        val mode = when (Prefs.themeMode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)

        // Накладываем акцентный стиль
        @StyleRes val accentStyle = when (Prefs.accentColor) {
            ACCENT_GREEN -> R.style.Accent_Green
            ACCENT_RED -> R.style.Accent_Red
            ACCENT_BLUE -> R.style.Accent_Blue
            ACCENT_PURPLE -> R.style.Accent_Purple
            ACCENT_ORANGE -> R.style.Accent_Orange
            ACCENT_GREY -> R.style.Accent_Grey
            else -> R.style.Accent_Teal
        }
        activity.theme.applyStyle(accentStyle, true)
    }

    /** Сохраняет режим и применяет его (Activity пересоздаётся автоматически). */
    fun setAndApply(activity: AppCompatActivity, mode: String) {
        if (mode == Prefs.themeMode) return
        Prefs.themeMode = mode
        apply(activity)
        activity.recreate()
    }

    /** Сохраняет акцент и применяет его (отложенное recreate для безопасности). */
    fun setAccentAndApply(activity: AppCompatActivity, accent: String) {
        if (accent == Prefs.accentColor) return
        Prefs.accentColor = accent
        apply(activity)
        // Откладываем recreate, чтобы выйти из текущего callback listener
        activity.window.decorView.post { activity.recreate() }
    }
}
