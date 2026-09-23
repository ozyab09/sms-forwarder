package com.ozyab.smsforwarder.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ozyab.smsforwarder.BuildConfig
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.history.EventHistory
import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Регрессионные тесты главного экрана — защита от бага #44 (чёрный экран
 * при старте: MainActivity навсегда вешала главный поток, ожидая готовности
 * Prefs через бесконечный awaitReady()).
 *
 * Ключевые сценарии:
 * - Activity открывается без краша и без зависания главного потока;
 * - «холодный» старт: Activity создаётся сразу после [Prefs.init] и БЕЗ
 *   предварительного прогрева Looper — та самая гонка инициализации из #44.
 *   Если awaitReady() снова начнёт висеть — тест упадёт по таймауту, а не
 *   заблокирует CI;
 * - Activity переживает пересоздание (поворот экрана);
 * - переключение всех вкладок нижней навигации не падает.
 *
 * Все тесты с явным [Test.timeout]: зависание главного потока превращается
 * в быстрое падение теста вместо висящего навсегда CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityLaunchTest {

    @Before
    fun setUp() {
        // Application (SmsForwarderApp) Robolectric создаёт и init вызывает сам —
        // здесь повторный вызов просто идемпотентен.
        Prefs.init(ApplicationProvider.getApplicationContext())
        // ВАЖНО: Looper здесь не прогреваем. «Холодный» сценарий — часть
        // регрессионной проверки #44. Тестам, которым прогрев нужен
        // (стабильный порядок), вызываем warmUp() явно.
    }

    /** Прогоняет отложенные задачи главного потока (дают корутинам завершиться). */
    private fun warmUp() {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        ShadowLooper.idleMainLooper()
    }

    private fun assertMainScreenVisible(activity: MainActivity) {
        assertFalse("Activity не должна быть finishing", activity.isFinishing)
        assertFalse("Activity не должна быть destroyed", activity.isDestroyed)
        val panel = activity.findViewById<View>(R.id.panel_settings)
        assertNotNull("content view должен быть установлен (не чёрный экран)", panel)
        assertTrue(
            "панель настроек должна быть видима",
            panel.visibility == View.VISIBLE,
        )
    }

    @Test(timeout = 20_000)
    fun `main activity opens without crash`() {
        warmUp()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertMainScreenVisible(activity)

        controller.pause().stop().destroy()
    }

    @Test(timeout = 20_000)
    fun `prefs init completes and defaults are usable`() {
        // Если init зависнет (баг #44), awaitReady() вернётся по таймауту 5с,
        // а при регрессии без таймаута — тест упадёт по @Test(timeout).
        val token = Prefs.botToken
        val chatId = Prefs.chatId
        assertNotNull(token)
        assertNotNull(chatId)
    }

    @Test(timeout = 20_000)
    fun `main activity opens on cold start without pre-warmed looper`() {
        // Без warmUp(): Activity стартует сразу после Prefs.init — гонка,
        // которая в баге #44 вешала главный поток навсегда. С фиксом
        // awaitReady() имеет таймаут 5с, поэтому тест проходит быстро
        // и не блокирует CI.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertMainScreenVisible(activity)

        // После успешного старта даём корутинам (ViewModel, UpdateManager)
        // отработать и убеждаемся, что экран жив.
        warmUp()
        assertFalse("Activity не должна завершиться после обработки корутин", activity.isFinishing)
        assertMainScreenVisible(activity)

        controller.pause().stop().destroy()
    }

    @Test(timeout = 20_000)
    fun `main activity survives recreation like screen rotation`() {
        warmUp()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        // Пересоздание (поворот экрана): ViewModel переживает, Prefs.init
        // идемпотентен, экран не должен упасть/почернеть.
        val activity = controller.recreate().get()

        assertMainScreenVisible(activity)

        controller.pause().stop().destroy()
    }

    @Test(timeout = 20_000)
    fun `bottom nav switches all tabs without crash`() {
        warmUp()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
        assertNotNull("bottom nav должна существовать", nav)

        val tabs = listOf(
            R.id.nav_settings to R.id.panel_settings,
            R.id.nav_history to R.id.panel_history,
            R.id.nav_logs to R.id.panel_logs,
            R.id.nav_about to R.id.panel_about,
        )
        for ((navId, panelId) in tabs) {
            nav.selectedItemId = navId
            ShadowLooper.idleMainLooper()
            val panel = activity.findViewById<View>(panelId)
            assertNotNull("панель $panelId должна существовать", panel)
            assertTrue(
                "панель $panelId должна стать видимой после таба $navId",
                panel.visibility == View.VISIBLE,
            )
        }

        controller.pause().stop().destroy()
    }

    @Test(timeout = 20_000)
    fun `history tab renders recorded events into list`() {
        // Регрессия #144 (декомпозиция): HistoryPanel.renderHistoryList строил
        // строки, но не добавлял их в контейнер — вкладка «История» всегда
        // показывала пустой список. Записываем события в Room и убеждаемся,
        // что они реально отображаются.
        warmUp()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking {
            EventHistory.record(
                context, sender = "+79001234567", body = "test body",
                timestamp = System.currentTimeMillis(), type = EventHistory.TYPE_SMS,
                status = EventHistory.STATUS_SENT, channelName = "direct",
                attempts = 1, formattedText = "formatted"
            )
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.selectedItemId = R.id.nav_history

        // Ждём, пока ViewModel выполнит запрос Room (Dispatchers.IO) и state
        // дойдёт до подписки: крутим main looper с паузами до результата.
        val historyList = activity.findViewById<LinearLayout>(R.id.history_list)
        assertNotNull("контейнер history_list должен существовать", historyList)
        var attempts = 0
        while (historyList.childCount == 0 && attempts < 100) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
            attempts++
        }
        assertTrue(
            "записанное событие должно отобразиться в списке истории (регрессия #144)",
            historyList.childCount > 0,
        )
        // Строка события — вертикальный LinearLayout с несколькими TextView
        val row = historyList.getChildAt(0) as LinearLayout
        val rowTexts = (0 until row.childCount).mapNotNull { (row.getChildAt(it) as? TextView)?.text?.toString() }
        assertTrue(
            "в строке должен быть номер отправителя",
            rowTexts.any { it.contains("+79001234567") },
        )

        controller.pause().stop().destroy()
    }

    @Test(timeout = 20_000)
    fun `about tab shows current app version`() {
        warmUp()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
        assertNotNull("bottom nav должна существовать", nav)

        nav.selectedItemId = R.id.nav_about
        ShadowLooper.idleMainLooper()

        val tvVersion = activity.findViewById<TextView>(R.id.tv_about_version)
        assertNotNull("текст версии должен существовать", tvVersion)
        assertEquals(
            "в панели должна показываться текущая версия",
            activity.getString(R.string.about_version, BuildConfig.VERSION_NAME),
            tvVersion.text.toString(),
        )

        controller.pause().stop().destroy()
    }
}