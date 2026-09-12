package com.ozyab.smsforwarder.ui

import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.util.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Регрессионный тест главного экрана.
 *
 * Страховка от бага #44 (чёрный экран при старте): MainActivity должна
 * открываться без краша и без зависания главного потока. Используем
 * Robolectric.buildActivity() — он корректно работает с non-exported активностями,
 * в отличие от ActivityScenario.launch().
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    qualifiers = "port",
    manifest = Config.NONE,
    resourceDir = "../main/res"
)
class MainActivityLaunchTest {

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        // Даём асинхронному init (secure + DataStore) записаться в кэш до старта
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `main activity opens without crash`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .get()
        assertNotNull("MainActivity должна быть создана", activity)
        assertFalse("Activity не должна быть finishing", activity.isFinishing)
        assertFalse("Activity не должна быть destroyed", activity.isDestroyed)
        // Главный экран видим: content view реально построен (не чёрный экран)
        val panel = activity.findViewById<android.view.View>(com.ozyab.smsforwarder.R.id.panel_settings)
        assertNotNull("content view должен быть установлен (не чёрный экран)", panel)
        assertTrue(
            "панель настроек должна быть видима",
            panel!!.visibility == android.view.View.VISIBLE
        )
    }

    @Test
    fun `prefs init completes and defaults are usable`() {
        // Если init зависнет (баг #44), awaitReady() повиснет на 5с — тест упадёт по таймауту
        val token = Prefs.botToken
        val chatId = Prefs.chatId
        assertNotNull(token)
        assertNotNull(chatId)
    }
}