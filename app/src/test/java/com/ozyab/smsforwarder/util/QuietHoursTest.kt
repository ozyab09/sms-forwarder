package com.ozyab.smsforwarder.util

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class QuietHoursTest {

    private lateinit var originalTz: TimeZone

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        // Ждём инициализации Prefs (до 5 сек)
        val deadline = System.currentTimeMillis() + 5_000
        while (!Prefs.isInitDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Ждёт применения асинхронной записи в DataStore (до 3 с). */
    private fun awaitPrefs(expected: String, actual: () -> String) {
        val deadline = System.currentTimeMillis() + 3_000
        while (actual() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(expected, actual())
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
    }

    /**
     * Хелпер: timestamp для заданной даты/времени в фиксированной временной зоне
     * (детерминированно для тестов, независимо от TZ устройства).
     */
    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month, day, hour, minute, 0)
        return cal.timeInMillis
    }

    /** Хелпер: минуты от полуночи для timestamp в UTC. */
    private fun minutesOf(epochMs: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    // Интервал не пересекает полночь: 08:00–23:00
    @Test
    fun `active within same-day interval`() {
        // 10:00 между 08:00 и 23:00
        assertTrue(QuietHours.isActive(10 * 60, 8 * 60, 23 * 60))
    }

    @Test
    fun `inactive outside same-day interval`() {
        // 02:00 до начала 08:00
        assertFalse(QuietHours.isActive(2 * 60, 8 * 60, 23 * 60))
        // 23:30 после конца 23:00
        assertFalse(QuietHours.isActive(23 * 60 + 30, 8 * 60, 23 * 60))
    }

    @Test
    fun `boundary start inclusive end exclusive`() {
        // Ровно в начало — активно
        assertTrue(QuietHours.isActive(8 * 60, 8 * 60, 23 * 60))
        // Ровно в конец — уже не активно
        assertFalse(QuietHours.isActive(23 * 60, 8 * 60, 23 * 60))
    }

    // Интервал пересекает полночь: 23:00–08:00
    @Test
    fun `active before midnight when interval crosses midnight`() {
        // 23:30 — после начала, до полуночи
        assertTrue(QuietHours.isActive(23 * 60 + 30, 23 * 60, 8 * 60))
    }

    @Test
    fun `active after midnight when interval crosses midnight`() {
        // 03:00 — после полуночи, до конца
        assertTrue(QuietHours.isActive(3 * 60, 23 * 60, 8 * 60))
    }

    @Test
    fun `inactive in middle of day for overnight interval`() {
        // 12:00 — не тихий час
        assertFalse(QuietHours.isActive(12 * 60, 23 * 60, 8 * 60))
    }

    @Test
    fun `same start and end is inactive`() {
        assertFalse(QuietHours.isActive(12 * 60, 10 * 60, 10 * 60))
    }

    // --- endTimestampMs (#170) ---

    @Test
    fun `endTimestampMs returns now when quiet hours inactive`() {
        // Тихие часы выключены — событие можно отправлять немедленно
        Prefs.quietHoursEnabled = false
        awaitPrefs("false") { Prefs.quietHoursEnabled.toString() }
        val now = ts(2026, Calendar.OCTOBER, 4, 12, 0)
        assertEquals(now, QuietHours.endTimestampMs(now, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `endTimestampMs returns now when outside interval`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 23 * 60
        Prefs.quietHoursEnd = 8 * 60
        awaitPrefs("true") { Prefs.quietHoursEnabled.toString() }
        awaitPrefs("1380") { Prefs.quietHoursStart.toString() }
        awaitPrefs("480") { Prefs.quietHoursEnd.toString() }
        // 12:00 — не тихие часы
        val now = ts(2026, Calendar.OCTOBER, 4, 12, 0)
        assertEquals(now, QuietHours.endTimestampMs(now, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `endTimestampMs same-day interval returns end today`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 10 * 60
        Prefs.quietHoursEnd = 14 * 60
        awaitPrefs("true") { Prefs.quietHoursEnabled.toString() }
        awaitPrefs("600") { Prefs.quietHoursStart.toString() }
        awaitPrefs("840") { Prefs.quietHoursEnd.toString() }
        // 12:00 — внутри интервала 10:00–14:00
        val now = ts(2026, Calendar.OCTOBER, 4, 12, 0)
        val end = QuietHours.endTimestampMs(now, TimeZone.getTimeZone("UTC"))
        assertEquals(14 * 60, minutesOf(end))
        assertTrue("конец должен быть в будущем", end > now)
    }

    @Test
    fun `endTimestampMs overnight interval before midnight returns end tomorrow`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 23 * 60
        Prefs.quietHoursEnd = 8 * 60
        awaitPrefs("true") { Prefs.quietHoursEnabled.toString() }
        awaitPrefs("1380") { Prefs.quietHoursStart.toString() }
        awaitPrefs("480") { Prefs.quietHoursEnd.toString() }
        // 23:30 — после начала, до полуночи → конец завтра в 08:00
        val now = ts(2026, Calendar.OCTOBER, 4, 23, 30)
        val end = QuietHours.endTimestampMs(now, TimeZone.getTimeZone("UTC"))
        assertEquals(8 * 60, minutesOf(end))
        // Конец должен быть на следующий день (примерно через 8.5 часа)
        val diffHours = (end - now) / (60 * 60 * 1000)
        assertEquals(8, diffHours)
    }

    @Test
    fun `endTimestampMs overnight interval after midnight returns end today`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 23 * 60
        Prefs.quietHoursEnd = 8 * 60
        awaitPrefs("true") { Prefs.quietHoursEnabled.toString() }
        awaitPrefs("1380") { Prefs.quietHoursStart.toString() }
        awaitPrefs("480") { Prefs.quietHoursEnd.toString() }
        // 02:00 — после полуночи, до конца → конец сегодня в 08:00
        val now = ts(2026, Calendar.OCTOBER, 4, 2, 0)
        val end = QuietHours.endTimestampMs(now, TimeZone.getTimeZone("UTC"))
        assertEquals(8 * 60, minutesOf(end))
        val diffHours = (end - now) / (60 * 60 * 1000)
        assertEquals(6, diffHours)
    }

    @Test
    fun `endTimestampMs at exact start returns end`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 23 * 60
        Prefs.quietHoursEnd = 8 * 60
        awaitPrefs("true") { Prefs.quietHoursEnabled.toString() }
        awaitPrefs("1380") { Prefs.quietHoursStart.toString() }
        awaitPrefs("480") { Prefs.quietHoursEnd.toString() }
        // Ровно 23:00 — начало интервала (включительно)
        val now = ts(2026, Calendar.OCTOBER, 4, 23, 0)
        val end = QuietHours.endTimestampMs(now, TimeZone.getTimeZone("UTC"))
        assertEquals(8 * 60, minutesOf(end))
    }

    @Test
    fun `endTimestampMs at exact end returns now`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 23 * 60
        Prefs.quietHoursEnd = 8 * 60
        awaitPrefs("true") { Prefs.quietHoursEnabled.toString() }
        awaitPrefs("1380") { Prefs.quietHoursStart.toString() }
        awaitPrefs("480") { Prefs.quietHoursEnd.toString() }
        // Ровно 08:00 — конец интервала (исключительно), тихие часы уже не активны
        val now = ts(2026, Calendar.OCTOBER, 4, 8, 0)
        assertEquals(now, QuietHours.endTimestampMs(now, TimeZone.getTimeZone("UTC")))
    }
}