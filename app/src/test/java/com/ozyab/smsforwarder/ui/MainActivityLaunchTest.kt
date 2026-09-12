package com.ozyab.smsforwarder.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.util.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Регрессионный тест главного экрана.
 *
 * Страховка от бага #44 (чёрный экран при старте): MainActivity должна
 * открываться без краша и без зависания главного потока. Создаём активность
 * через [ActivityScenario], прогоняем Looper (разрешаем init-колбэки), затем
 * проверяем, что активность жива и видима.
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("MainActivity должна быть создана", activity)
                assertFalse("Activity не должна быть finishing", activity.isFinishing)
                assertFalse("Activity не должна быть destroyed", activity.isDestroyed)
                // Главный экран видим: content view реально построен
                assertNotNull(
                    "content view должен быть установлен (не чёрный экран)",
                    activity.findViewById(com.ozyab.smsforwarder.R.id.panel_settings)
                )
            }
        }
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