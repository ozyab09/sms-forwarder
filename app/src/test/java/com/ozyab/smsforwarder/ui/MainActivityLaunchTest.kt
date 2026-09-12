package com.ozyab.smsforwarder.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.util.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Регрессионный тест главного экрана.
 *
 * Страховка от бага #44 (чёрный экран при старте): MainActivity должна
 * открываться без краша и без зависания главного потока. Запускаем активность
 * через [ActivityScenario] с явным Intent (MainActivity не exported, поэтому
 * без флага NEW_TASK Robolectric не может её стартовать), прогоняем Looper
 * (разрешаем init-колбэки), затем проверяем, что активность жива, видима
 * и content view построен (не чёрный экран).
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
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