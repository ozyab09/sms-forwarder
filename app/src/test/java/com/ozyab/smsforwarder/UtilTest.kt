package com.ozyab.smsforwarder

import com.ozyab.smsforwarder.util.formatTimestamp
import org.junit.Assert.assertEquals
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
}