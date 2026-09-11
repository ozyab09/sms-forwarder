package com.ozyab.smsforwarder

import com.ozyab.smsforwarder.util.TemplateFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TemplateFormatterTest {

    // Фиксированная метка; ожидаемые время/дата вычисляются в той же локали/таймзоне,
    // что и у TemplateFormatter, поэтому тесты не зависят от таймзоны CI.
    private val ts = 1757573400000L
    private val expectedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
    private val expectedDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ts))

    @Test
    fun `default SMS template uses fallback when blank`() {
        val result = TemplateFormatter.format(
            template = "", sender = "+79161234567", name = "Иван",
            text = "Hello!", timestamp = ts, type = "sms", sim = "МТС"
        )
        assertTrue(result.contains("📩 SMS"))
        assertTrue(result.contains("+79161234567"))
        assertTrue(result.contains("Иван"))
        assertTrue(result.contains("Hello!"))
        assertTrue(result.contains("МТС"))
    }

    @Test
    fun `default call template uses fallback when blank`() {
        val result = TemplateFormatter.format(
            template = "", sender = "+79161234567", name = "Иван",
            text = "", timestamp = ts, type = "missed", sim = "МТС"
        )
        assertTrue(result.contains("📵 Пропущенный"))
        assertTrue(result.contains("+79161234567"))
        assertTrue(result.contains("Иван"))
    }

    @Test
    fun `custom template replaces all placeholders`() {
        // {name} подставляется как " (Имя)" (с ведущим пробелом и скобками)
        val tpl = "{type} from {sender}{name}: {text} at {time} on {date} via {sim}"
        val result = TemplateFormatter.format(
            template = tpl, sender = "+79990001122", name = "Bob",
            text = "Test msg", timestamp = ts, type = "sms", sim = "Beeline"
        )
        assertEquals("sms from +79990001122 (Bob): Test msg at $expectedTime on $expectedDate via Beeline", result)
    }

    @Test
    fun `name is wrapped in parentheses when present`() {
        val result = TemplateFormatter.format(
            template = "{sender}{name}", sender = "+79161234567", name = "Аня",
            text = "", timestamp = ts, type = "sms", sim = null
        )
        assertEquals("+79161234567 (Аня)", result)
    }

    @Test
    fun `name is empty string when null`() {
        val result = TemplateFormatter.format(
            template = "{sender}{name}", sender = "+79161234567", name = null,
            text = "", timestamp = ts, type = "sms", sim = null
        )
        assertEquals("+79161234567", result)
    }

    @Test
    fun `sim line is empty when null`() {
        val result = TemplateFormatter.format(
            template = "[{sim}]", sender = "+79161234567", name = null,
            text = "", timestamp = ts, type = "sms", sim = null
        )
        assertEquals("[]", result)
    }

    @Test
    fun `{number} is alias for {sender}`() {
        val result = TemplateFormatter.format(
            template = "{number}", sender = "+79991112233", name = null,
            text = "", timestamp = ts, type = "missed", sim = null
        )
        assertEquals("+79991112233", result)
    }

    @Test
    fun `text is empty for missed call`() {
        val result = TemplateFormatter.format(
            template = "Text: [{text}]", sender = "+79161234567", name = null,
            text = "", timestamp = ts, type = "missed", sim = null
        )
        assertEquals("Text: []", result)
    }

    @Test
    fun `user can build JSON template`() {
        val tpl = """{"sender":"{sender}","text":"{text}","time":"{time}"}"""
        val result = TemplateFormatter.format(
            template = tpl, sender = "12345", name = null,
            text = "hi", timestamp = ts, type = "sms", sim = null
        )
        assertEquals("""{"sender":"12345","text":"hi","time":"$expectedTime"}""", result)
    }
}
