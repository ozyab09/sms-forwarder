package com.ozyab.smsforwarder

import com.ozyab.smsforwarder.update.UpdateChecker
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

    // ===== Очистка release notes от GitHub-автоссылок (PR/compare/issues) =====

    @Test
    fun `stripGitHubAutoLinks removes PR link`() {
        val notes = "## What's Changed\n* fix: тёмная тема by @ozyab09 in https://github.com/ozyab09/sms-forwarder/pull/34"
        val cleaned = UpdateChecker.stripGitHubAutoLinks(notes)
        assertFalse(cleaned.contains("pull/"))
        assertFalse(cleaned.contains("https://github.com"))
    }

    @Test
    fun `stripGitHubAutoLinks removes compare link`() {
        val notes = "**Full Changelog**: https://github.com/ozyab09/sms-forwarder/compare/v0.4.13...v0.4.14"
        val cleaned = UpdateChecker.stripGitHubAutoLinks(notes)
        assertFalse(cleaned.contains("compare/"))
        assertFalse(cleaned.contains("https://github.com"))
    }

    @Test
    fun `stripGitHubAutoLinks removes issues link and collapses blank lines`() {
        val notes = "Что нового\n\n\n* мелкие фиксы https://github.com/ozyab09/sms-forwarder/issues/12\n\n"
        val cleaned = UpdateChecker.stripGitHubAutoLinks(notes)
        assertFalse(cleaned.contains("issues/"))
        assertFalse(cleaned.contains("\n\n\n"))
        assertTrue(cleaned.contains("Что нового"))
    }

    @Test
    fun `stripGitHubAutoLinks keeps project link and regular text`() {
        val notes = "Скачать: https://github.com/ozyab09/sms-forwarder — проект на GitHub"
        val cleaned = UpdateChecker.stripGitHubAutoLinks(notes)
        assertTrue(cleaned.contains("https://github.com/ozyab09/sms-forwarder"))
        assertTrue(cleaned.contains("проект на GitHub"))
    }

    @Test
    fun `stripGitHubAutoLinks handles blank input`() {
        assertEquals("", UpdateChecker.stripGitHubAutoLinks(""))
        assertEquals("", UpdateChecker.stripGitHubAutoLinks("   \n\n  "))
        assertEquals("", UpdateChecker.stripGitHubAutoLinks("https://github.com/ozyab09/sms-forwarder/pull/34"))
    }
}