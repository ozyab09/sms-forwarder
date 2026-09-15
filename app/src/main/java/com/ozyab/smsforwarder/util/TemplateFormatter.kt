package com.ozyab.smsforwarder.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Форматирование сообщений по шаблону.
 *
 * Поддерживаемые плейсхолдеры:
 * - {sender} — номер отправителя (для SMS) / номер звонящего (для звонка)
 * - {name} — имя из контактов (если найдено)
 * - {text} — текст SMS / пусто для звонка
 * - {time} — время в формате HH:mm:ss
 * - {date} — дата в формате dd.MM.yyyy
 * - {type} — "sms" | "missed" | "incoming" | "outgoing" | "outgoing_sms"
 * - {sim} — описание SIM-карты (оператор/слот), если доступно
 * - {number} — синоним {sender} для звонков
 * - {duration} — длительность звонка («5 мин 23 сек»; пусто для пропущенных)
 */
object TemplateFormatter {

    /** Дефолтный шаблон для SMS (сохраняет текущее поведение). */
    val DEFAULT_SMS_TEMPLATE = """📩 SMS [{time}]
{sim}
От: {sender}{name}
──────────────────
{text}""".trimIndent()

    /** Дефолтный шаблон для пропущенных вызовов. */
    val DEFAULT_CALL_TEMPLATE = """📵 Пропущенный [{time}]
{sim}
От: {number}{name}""".trimIndent()

    /** Дефолтный шаблон для исходящих SMS. */
    val DEFAULT_OUTGOING_SMS_TEMPLATE = """📤 SMS [{time}]
{sim}
Кому: {sender}{name}
──────────────────
{text}""".trimIndent()

    /** Дефолтный шаблон для принятых (входящих) звонков. */
    val DEFAULT_INCOMING_CALL_TEMPLATE = """📞 Входящий [{time}]
{sim}
От: {number}{name}
Длительность: {duration}""".trimIndent()

    /** Дефолтный шаблон для исходящих звонков. */
    val DEFAULT_OUTGOING_CALL_TEMPLATE = """📞 Исходящий [{time}]
{sim}
Кому: {number}{name}
Длительность: {duration}""".trimIndent()

    /** Демонстрационные данные для предпросмотра (реальные SMS не используются). */
    const val PREVIEW_SENDER = "+7 900 123-45-67"
    const val PREVIEW_NAME = "Иван"
    const val PREVIEW_TEXT = "Пример текста SMS-сообщения"
    const val PREVIEW_SIM = "Sim1 beeline"

    /** Демонстрационная длительность звонка для предпросмотра (5 мин 23 сек). */
    const val PREVIEW_DURATION_MS = 323_000L

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    /**
     * Форматирует сообщение по шаблону.
     *
     * @param template Строка шаблона (пустая = дефолт)
     * @param sender Номер отправителя/звонящего
     * @param name Имя из контактов (может быть null)
     * @param text Текст сообщения (для SMS)
     * @param timestamp Временная метка события
     * @param type Тип события: "sms", "missed", "incoming", "outgoing", "outgoing_sms"
     * @param sim Описание SIM (может быть null)
     * @param durationMs Длительность звонка в миллисекундах (null = нет данных,
     *        напр. для пропущенных)
     * @return Отформатированный текст
     */
    fun format(
        template: String,
        sender: String,
        name: String?,
        text: String,
        timestamp: Long,
        type: String,
        sim: String? = null,
        durationMs: Long? = null
    ): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp

        val t = if (template.isBlank()) {
            when (type) {
                "sms" -> DEFAULT_SMS_TEMPLATE
                "outgoing_sms" -> DEFAULT_OUTGOING_SMS_TEMPLATE
                "incoming" -> DEFAULT_INCOMING_CALL_TEMPLATE
                "outgoing" -> DEFAULT_OUTGOING_CALL_TEMPLATE
                else -> DEFAULT_CALL_TEMPLATE
            }
        } else template

        var result = t
            .replace("{sender}", sender)
            .replace("{number}", sender)
            .replace("{name}", name?.let { " ($it)" } ?: "")
            .replace("{text}", text)
            .replace("{time}", timeFormat.format(cal.time))
            .replace("{date}", dateFormat.format(cal.time))
            .replace("{type}", type)
            .replace("{sim}", sim ?: "")
            .replace("{duration}", formatDuration(durationMs))

        return result
    }

    /**
     * Форматирует длительность звонка в человекочитаемый вид.
     *
     * @param durationMs Длительность в миллисекундах или null (null = нет данных)
     * @return «5 мин 23 сек», «45 сек» или пустая строка (для пропущенных)
     */
    private fun formatDuration(durationMs: Long?): String {
        val ms = durationMs ?: return ""
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return when {
            min > 0 && sec > 0 -> "$min мин $sec сек"
            min > 0 -> "$min мин"
            sec > 0 -> "$sec сек"
            else -> "0 сек"
        }
    }

    /**
     * Предпросмотр сообщения по шаблону с демонстрационными данными
     * (кнопка «Проверить» рядом с шаблонами). Пустой шаблон → дефолтный формат
     * — то же поведение, что и при реальной отправке.
     *
     * @param type Тип события
     */
    fun preview(template: String, type: String, timestamp: Long = System.currentTimeMillis()): String =
        format(
            template = template,
            sender = PREVIEW_SENDER,
            name = PREVIEW_NAME,
            text = PREVIEW_TEXT,
            timestamp = timestamp,
            type = type,
            sim = PREVIEW_SIM,
            // Демонстрационная длительность — только для звонков
            durationMs = if (type == "incoming" || type == "outgoing") PREVIEW_DURATION_MS else null,
        )
}