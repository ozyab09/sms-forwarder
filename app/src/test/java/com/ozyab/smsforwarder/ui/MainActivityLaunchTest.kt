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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Регрессионный тест главного экрана.
 *
 * Страховка от бага #44 (чёрный экран при старте): MainActivity должна
 * открываться без краша и без зависания главного потока. Создаём активность
 * через [Robolectric.buildActivity] (не требует exported/intent-filter),
 * прогоняем Looper (разрешаем init-колбэки), затем проверяем, что активность
 * жива, видима и content view построен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertNotNull("MainActivity должна быть создана", activity)
        assertFalse("Activity не должна быть finishing", activity.isFinishing)
        assertFalse("Activity не должна быть destroyed", activity.isDestroyed)
        // Главный экран видим: content view реально построен (не чёрный экран)
        assertNotNull(
            "content view должен быть установлен (не чёрный экран)",
            activity.findViewById(com.ozyab.smsforwarder.R.id.panel_settings)
        )
        // Экран настроек активен по умолчанию
        assertTrue(
            "панель настроек должна быть видима",
            activity.findViewById<android.view.View>(com.ozyab.smsforwarder.R.id.panel_settings).visibility ==
                android.view.View.VISIBLE
        )

        controller.pause().stop().destroy()
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