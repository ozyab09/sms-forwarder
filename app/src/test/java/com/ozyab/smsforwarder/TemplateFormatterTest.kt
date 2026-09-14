package com.ozyab.smsforwarder

import com.ozyab.smsforwarder.util.TemplateFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TemplateFormatterTest {

    private val ts = 1757573400000L
    private val expectedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
    private val expectedDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ts))

    // ──────────────────────────────────────────────
    //  Existing tests (sms, missed)
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    //  New types: outgoing_sms, incoming, outgoing, notification
    // ──────────────────────────────────────────────

    @Test
    fun `default outgoing SMS template`() {
        val result = TemplateFormatter.format(
            template = "", sender = "+79161234567", name = null,
            text = "Hello!", timestamp = ts, type = "outgoing_sms"
        )
        assertTrue(result.contains("📤 SMS"))
        assertTrue(result.contains("+79161234567"))
        assertTrue(result.contains("Hello!"))
    }

    @Test
    fun `default incoming call template`() {
        val result = TemplateFormatter.format(
            template = "", sender = "+79161234567", name = "Иван",
            text = "", timestamp = ts, type = "incoming"
        )
        assertTrue(result.contains("📞 Входящий"))
        assertTrue(result.contains("+79161234567"))
        assertTrue(result.contains("Иван"))
    }

    @Test
    fun `default outgoing call template`() {
        val result = TemplateFormatter.format(
            template = "", sender = "+79161234567", name = "Иван",
            text = "", timestamp = ts, type = "outgoing"
        )
        assertTrue(result.contains("📞 Исходящий"))
        assertTrue(result.contains("+79161234567"))
        assertTrue(result.contains("Иван"))
    }

    @Test
    fun `default notification template`() {
        val result = TemplateFormatter.format(
            template = "", sender = "", name = null,
            text = "New message", timestamp = ts, type = "notification",
            appName = "Telegram", title = "Новое сообщение"
        )
        assertTrue(result.contains("Telegram"))
        assertTrue(result.contains("Новое сообщение"))
        assertTrue(result.contains("New message"))
    }

    @Test
    fun `notification template replaces app and title placeholders`() {
        val tpl = "[{app}] {title}: {text}"
        val result = TemplateFormatter.format(
            template = tpl, sender = "", name = null,
            text = "Hello", timestamp = ts, type = "notification",
            appName = "WhatsApp", title = "John"
        )
        assertEquals("[WhatsApp] John: Hello", result)
    }

    @Test
    fun `outgoing SMS template with custom template`() {
        val tpl = "OUTGOING to {sender}: {text}"
        val result = TemplateFormatter.format(
            template = tpl, sender = "+79990001122", name = null,
            text = "Hi", timestamp = ts, type = "outgoing_sms"
        )
        assertEquals("OUTGOING to +79990001122: Hi", result)
    }

    @Test
    fun `incoming call template with custom template`() {
        val tpl = "INCOMING from {sender}{name}"
        val result = TemplateFormatter.format(
            template = tpl, sender = "+79990001122", name = "Bob",
            text = "", timestamp = ts, type = "incoming"
        )
        assertEquals("INCOMING from +79990001122 (Bob)", result)
    }

    @Test
    fun `outgoing call template with custom template`() {
        val tpl = "DIAL to {sender}{name}"
        val result = TemplateFormatter.format(
            template = tpl, sender = "+79990001122", name = "Bob",
            text = "", timestamp = ts, type = "outgoing"
        )
        assertEquals("DIAL to +79990001122 (Bob)", result)
    }

    // ──────────────────────────────────────────────
    //  Preview for new types
    // ──────────────────────────────────────────────

    @Test
    fun `preview uses sample data for outgoing SMS`() {
        val result = TemplateFormatter.preview("", type = "outgoing_sms", timestamp = ts)
        assertTrue(result.contains("📤 SMS"))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_SENDER))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_TEXT))
    }

    @Test
    fun `preview uses sample data for incoming call`() {
        val result = TemplateFormatter.preview("", type = "incoming", timestamp = ts)
        assertTrue(result.contains("📞 Входящий"))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_SENDER))
    }

    @Test
    fun `preview uses sample data for outgoing call`() {
        val result = TemplateFormatter.preview("", type = "outgoing", timestamp = ts)
        assertTrue(result.contains("📞 Исходящий"))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_SENDER))
    }

    @Test
    fun `preview uses sample data for notification`() {
        val result = TemplateFormatter.preview("", type = "notification", timestamp = ts)
        assertTrue(result.contains("Telegram"))
        assertTrue(result.contains("Новое сообщение"))
    }

    @Test
    fun `preview respects custom template for notification`() {
        val result = TemplateFormatter.preview("NOTIF {app}: {title}", type = "notification", timestamp = ts)
        assertEquals("NOTIF Telegram: Новое сообщение", result)
    }

    // ──────────────────────────────────────────────
    //  Misc
    // ──────────────────────────────────────────────

    @Test
    fun `preview uses sample data for SMS`() {
        val result = TemplateFormatter.preview("", type = "sms", timestamp = ts)
        assertTrue(result.contains("📩 SMS"))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_SENDER))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_NAME))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_TEXT))
        assertTrue(result.contains(TemplateFormatter.PREVIEW_SIM))
    }

    @Test
    fun `preview uses sample data for missed call`() {
        val result = TemplateFormatter.preview("", type = "missed", timestamp = ts)
        assertTrue(result.contains("📵 Пропущенный"))
        assertTrue(!result.contains(TemplateFormatter.PREVIEW_TEXT))
    }

    @Test
    fun `preview respects custom template`() {
        val result = TemplateFormatter.preview("CALL {type} {time}", type = "missed", timestamp = ts)
        assertEquals("CALL missed $expectedTime", result)
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
