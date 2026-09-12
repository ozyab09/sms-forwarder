package com.ozyab.smsforwarder.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

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
}