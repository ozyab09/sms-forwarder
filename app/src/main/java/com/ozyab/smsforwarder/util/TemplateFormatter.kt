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
 * - {type} — "sms" | "missed"
 * - {sim} — описание SIM-карты (оператор/слот), если доступно
 * - {number} — синоним {sender} для звонков
 */
object TemplateFormatter {

    /** Дефолтный шаблон для SMS (сохраняет текущее поведение). */
    const val DEFAULT_SMS_TEMPLATE = """📩 SMS [{time}]
{sim}
От: {sender}{name}
──────────────────
{text}""".trimIndent()

    /** Дефолтный шаблон для пропущенных вызовов. */
    const val DEFAULT_CALL_TEMPLATE = """📵 Пропущенный [{time}]
{sim}
От: {number}{name}""".trimIndent()

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
     * @param type Тип события: "sms" или "missed"
     * @param sim Описание SIM (может быть null)
     * @return Отформатированный текст
     */
    fun format(
        template: String,
        sender: String,
        name: String?,
        text: String,
        timestamp: Long,
        type: String,
        sim: String?
    ): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp

        val t = if (template.isBlank()) {
            if (type == "sms") DEFAULT_SMS_TEMPLATE else DEFAULT_CALL_TEMPLATE
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

        return result
    }
}