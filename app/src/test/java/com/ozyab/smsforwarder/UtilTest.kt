package com.ozyab.smsforwarder

import com.ozyab.smsforwarder.update.UpdateChecker
import com.ozyab.smsforwarder.util.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {

    @Test
    fun `formatTimestamp formats correctly`() {
        // 2026-09-07 21:35:00 UTC+0 = 1788809700000 ms (пример)
        val ts = 1788809700000L
        val s = formatTimestamp(ts)
        // формат: YYYY-MM-DD HH:MM (локальное время)
        assertTrue("формат должен быть YYYY-MM-DD HH:MM, было: $s", s.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    // ===== Сравнение версий для автообновления =====

    @Test
    fun `compareVersions detects newer`() {
        assertTrue(UpdateChecker.compareVersions("0.3.0", "0.2.1") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.2.10", "0.2.9") > 0)
    }

    @Test
    fun `compareVersions equal`() {
        assertEquals(0, UpdateChecker.compareVersions("0.2.1", "0.2.1"))
    }

    @Test
    fun `compareVersions older`() {
        assertTrue(UpdateChecker.compareVersions("0.1.0", "0.2.0") < 0)
    }
}
