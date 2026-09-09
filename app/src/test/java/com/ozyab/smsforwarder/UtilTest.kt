package com.ozyab.smsforwarder

import com.ozyab.smsforwarder.update.UpdateChecker
import com.ozyab.smsforwarder.util.SmsFilter
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

    // ===== Белый список (чистая логика без Prefs) =====

    @Test
    fun `whitelist empty does not match`() {
        assertFalse(SmsFilter.whitelistMatches("+79161234567", emptyList()))
    }

    @Test
    fun `whitelist exact number matches`() {
        assertTrue(SmsFilter.whitelistMatches("+79161234567", listOf("+79161234567")))
    }

    @Test
    fun `whitelist exact digits match regardless of formatting`() {
        assertTrue(SmsFilter.whitelistMatches("+7 (916) 123-45-67", listOf("79161234567")))
    }

    @Test
    fun `whitelist wildcard prefix matches`() {
        assertTrue(SmsFilter.whitelistMatches("+79161234567", listOf("+79*")))
    }

    @Test
    fun `whitelist wildcard contains matches`() {
        assertTrue(SmsFilter.whitelistMatches("+79161234567", listOf("*123*")))
    }

    @Test
    fun `whitelist wildcard does not match wrong prefix`() {
        assertFalse(SmsFilter.whitelistMatches("+79161234567", listOf("*888*")))
    }

    @Test
    fun `whitelist multiple patterns any matches`() {
        val list = listOf("+7900*", "+7916*")
        assertTrue(SmsFilter.whitelistMatches("+79161234567", list))
        assertFalse(SmsFilter.whitelistMatches("+79261234567", list))
    }

    @Test
    fun `whitelist ignores empty entries`() {
        val list = listOf("", "  ", "+79161234567")
        assertTrue(SmsFilter.whitelistMatches("+79161234567", list))
    }

    // ===== Защита от ReDoS: опасные block-regex отклоняются =====

    @Test
    fun `dangerous nested quantifier regex is rejected`() {
        assertTrue(SmsFilter.isDangerousRegex("(a+)+"))
        assertTrue(SmsFilter.isDangerousRegex("([a-zA-Z]+)*"))
        assertTrue(SmsFilter.isDangerousRegex("(a*)*"))
        assertTrue(SmsFilter.isDangerousRegex("(a|aa)+"))
        assertTrue(SmsFilter.isDangerousRegex("(\\d+)+"))
    }

    @Test
    fun `safe regexes are allowed`() {
        assertFalse(SmsFilter.isDangerousRegex("SPAM"))
        assertFalse(SmsFilter.isDangerousRegex("\\d{4}"))
        assertFalse(SmsFilter.isDangerousRegex("(ab)+"))
        assertFalse(SmsFilter.isDangerousRegex("\\+79[0-9]{9}"))
        assertFalse(SmsFilter.isDangerousRegex(""))
    }

    @Test
    fun `malformed regex does not crash detector`() {
        assertFalse(SmsFilter.isDangerousRegex("((("))
        assertFalse(SmsFilter.isDangerousRegex("a+b*c?"))
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