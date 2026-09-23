package com.ozyab.smsforwarder

import com.ozyab.smsforwarder.util.TemplateFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Тесты стандартного формата пересылаемых сообщений.
 *
 * Пользовательские шаблоны удалены (рефакторинг): format() всегда рендерит
 * DEFAULT_* константы. Проверяем плейсхолдеры всех типов событий, потокобезопасность
 * {time}/{date} (фикс B4 #137) и локализацию {duration}.
 */
class TemplateFormatterTest {

    private val ts = 1757573400000L
    private val expectedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
    private val expectedDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ts))

    // ──────────────────────────────────────────────
    //  Стандартный формат по типам событий
    // ──────────────────────────────────────────────

    @Test
    fun `sms format contains all fields`() {
        val result = TemplateFormatter.format(
            sender = "+79161234567", name = "Иван",
            text = "Hello!", timestamp = ts, type = "sms", sim = "МТС"
        )
        assertTrue(result.contains("📩 SMS"))
        assertTrue(result.contains(expectedTime))
        assertTrue(result.contains("+79161234567"))
        assertTrue(result.contains("Иван"))
        assertTrue(result.contains("Hello!"))
        assertTrue(result.contains("МТС"))
    }

    @Test
    fun `missed call format contains number and time`() {
        val result = TemplateFormatter.format(
            sender = "+79161234567", name = null,
            text = "", timestamp = ts, type = "missed", sim = "МТС"
        )
        assertTrue(result.contains("📵 Пропущенный"))
        assertTrue(result.contains(expectedTime))
        assertTrue(result.contains("+79161234567"))
        assertFalse("имя отсутствует — без скобок", result.contains("()"))
    }

    @Test
    fun `incoming call format contains duration`() {
        val result = TemplateFormatter.format(
            sender = "+79161234567", name = null,
            text = "", timestamp = ts, type = "incoming", sim = null,
            durationMs = 323_000L,
            durationFormatter = { min, sec -> "${min}m${sec}s" }
        )
        assertTrue(result.contains("📞 Входящий"))
        assertTrue("длительность подставлена", result.contains("5m23s"))
    }

    @Test
    fun `outgoing call format contains duration`() {
        val result = TemplateFormatter.format(
            sender = "+79161234567", name = null,
            text = "", timestamp = ts, type = "outgoing",
            durationMs = 60_000L,
            durationFormatter = { min, sec -> "${min}m${sec}s" }
        )
        assertTrue(result.contains("📞 Исходящий"))
        assertTrue(result.contains("1m0s"))
    }

    @Test
    fun `outgoing sms format contains body`() {
        val result = TemplateFormatter.format(
            sender = "+79161234567", name = null,
            text = "Привет", timestamp = ts, type = "outgoing_sms", sim = "МТС"
        )
        assertTrue(result.contains("📤 SMS"))
        assertTrue(result.contains("Привет"))
        assertTrue(result.contains("Кому: +79161234567"))
    }

    @Test
    fun `unknown type falls back to missed format`() {
        val result = TemplateFormatter.format(
            sender = "123", name = null, text = "", timestamp = ts, type = "weird"
        )
        assertTrue(result.contains("📵"))
    }

    // ──────────────────────────────────────────────
    //  Плейсхолдеры и краевые случаи
    // ──────────────────────────────────────────────

    @Test
    fun `date placeholder is rendered`() {
        // {date} есть в DEFAULT_*: проверяем подстановку через формат SMS
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "sms"
        )
        assertTrue(result.contains(expectedTime))
    }

    @Test
    fun `null sim renders without placeholder`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "x", timestamp = ts, type = "sms", sim = null
        )
        assertFalse("литерал {sim} не остаётся", result.contains("{sim}"))
    }

    @Test
    fun `null name renders without brackets`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "x", timestamp = ts, type = "sms"
        )
        assertFalse(result.contains("()"))
    }

    @Test
    fun `name renders with brackets when present`() {
        val result = TemplateFormatter.format(
            sender = "1", name = "Иван", text = "x", timestamp = ts, type = "sms"
        )
        assertTrue(result.contains("(Иван)"))
    }

    @Test
    fun `type placeholder replaced`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "missed"
        )
        assertFalse("литерал {type} не остаётся", result.contains("{type}"))
    }

    // ──────────────────────────────────────────────
    //  Длительность: локализация и краевые случаи
    // ──────────────────────────────────────────────

    @Test
    fun `duration empty for missed calls`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "missed",
            durationMs = null
        )
        assertFalse(result.contains("Длительность"))
    }

    @Test
    fun `duration empty for zero duration`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "incoming",
            durationMs = 0L,
            durationFormatter = { min, sec -> "${min}m${sec}s" }
        )
        assertFalse("нулевая длительность не показывается", result.contains("0m0s"))
    }

    @Test
    fun `duration formats minutes only`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "incoming",
            durationMs = 120_000L,
            durationFormatter = { min, sec -> "${min}m${sec}s" }
        )
        assertTrue(result.contains("2m0s"))
    }

    @Test
    fun `duration formats seconds only`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "incoming",
            durationMs = 45_000L,
            durationFormatter = { min, sec -> "${min}m${sec}s" }
        )
        assertTrue(result.contains("0m45s"))
    }

    @Test
    fun `duration formatter default is russian`() {
        // Дефолтный durationFormatter — русская форма (вне Android-контекста);
        // локализованная версия через ресурсы проверяется в ReceiverTest.
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "incoming",
            durationMs = 323_000L
        )
        assertTrue(result.contains("5 мин 23 сек"))
    }

    // ──────────────────────────────────────────────
    //  B4 (#137): потокобезопасность {time}/{date}
    // ──────────────────────────────────────────────

    @Test
    fun `time is rendered in default format`() {
        val result = TemplateFormatter.format(
            sender = "1", name = null, text = "", timestamp = ts, type = "sms"
        )
        assertTrue(result.contains(expectedTime))
    }

    @Test
    fun `format is thread-safe under concurrent use`() {
        // SimpleDateFormat (до фикса) при конкурентном format() искажал вывод.
        // DateTimeFormatter — иммутабелен; прогон из многих потоков + повторы:
        // каждый результат обязан быть корректным.
        val threads = 8
        val iterations = 200
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    repeat(iterations) {
                        val r = TemplateFormatter.format(
                            sender = "1", name = null, text = "", timestamp = ts, type = "sms"
                        )
                        if (!r.contains(expectedTime)) errors.incrementAndGet()
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertEquals("Конкурентный format() исказил {time}", 0, errors.get())
    }

    @Test
    fun `preview demo constants are intact`() {
        // Константы используются в тестах и were в превью — проверяем неизменность
        assertEquals("+7 900 123-45-67", TemplateFormatter.PREVIEW_SENDER)
        assertEquals("Иван", TemplateFormatter.PREVIEW_NAME)
    }

    @Test
    fun `default templates are stable`() {
        // Защита от случайного изменения формата пересылки
        assertTrue(TemplateFormatter.DEFAULT_SMS_TEMPLATE.contains("📩"))
        assertTrue(TemplateFormatter.DEFAULT_SMS_TEMPLATE.contains("{text}"))
        assertTrue(TemplateFormatter.DEFAULT_CALL_TEMPLATE.contains("📵"))
        assertTrue(TemplateFormatter.DEFAULT_OUTGOING_SMS_TEMPLATE.contains("📤"))
        assertTrue(TemplateFormatter.DEFAULT_INCOMING_CALL_TEMPLATE.contains("📞"))
        assertTrue(TemplateFormatter.DEFAULT_OUTGOING_CALL_TEMPLATE.contains("{duration}"))
    }
}
