package com.ozyab.smsforwarder.util

import android.content.Context
import com.ozyab.smsforwarder.R
import java.util.Calendar
import java.util.Locale

/**
 * Форматирование пересылаемых сообщений (стандартный формат).
 *
 * Пользовательские шаблоны удалены (рефакторинг: функционал признан лишним) —
 * формат фиксирован константами DEFAULT_* ниже и одинаков для всех событий
 * одного типа. Внутренние плейсхолдеры по-прежнему подставляются здесь:
 * {sender}/{name}/{text}/{time}/{date}/{sim}/{duration}.
 */
object TemplateFormatter {

    /** Формат входящего SMS. */
    val DEFAULT_SMS_TEMPLATE = """📩 SMS [{time}]
{sim}
От: {sender}{name}
──────────────────
{text}""".trimIndent()

    /** Формат пропущенного вызова. */
    val DEFAULT_CALL_TEMPLATE = """📵 Пропущенный [{time}]
{sim}
От: {number}{name}""".trimIndent()

    /** Формат исходящего SMS. */
    val DEFAULT_OUTGOING_SMS_TEMPLATE = """📤 SMS [{time}]
{sim}
Кому: {sender}{name}
──────────────────
{text}""".trimIndent()

    /** Формат принятого (входящего) звонка. */
    val DEFAULT_INCOMING_CALL_TEMPLATE = """📞 Входящий [{time}]
{sim}
От: {number}{name}
Длительность: {duration}""".trimIndent()

    /** Формат исходящего звонка. */
    val DEFAULT_OUTGOING_CALL_TEMPLATE = """📞 Исходящий [{time}]
{sim}
Кому: {number}{name}
Длительность: {duration}""".trimIndent()

    /** Демонстрационные данные (использовались превью; оставлены для тестов). */
    const val PREVIEW_SENDER = "+7 900 123-45-67"
    const val PREVIEW_NAME = "Иван"
    const val PREVIEW_TEXT = "Пример текста SMS-сообщения"
    const val PREVIEW_SIM = "Sim1 beeline"

    /** Демонстрационная длительность звонка (5 мин 23 сек). */
    const val PREVIEW_DURATION_MS = 323_000L

    // DateTimeFormatter потокобезопасен: format() зовётся параллельно из
    // receiver-worker, outgoing-sms-worker и UI (фикс B4, #137 — SimpleDateFormat
    // при гонке искажал {time}/{date} в пересланных сообщениях).
    private val timeFormat: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateFormat: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * Форматирует сообщение в стандартном формате для типа события.
     *
     * @param sender Номер отправителя/звонящего
     * @param name Имя из контактов (может быть null)
     * @param text Текст сообщения (для SMS)
     * @param timestamp Временная метка события
     * @param type Тип события: "sms", "missed", "incoming", "outgoing", "outgoing_sms"
     * @param sim Описание SIM (может быть null)
     * @param durationMs Длительность звонка в миллисекундах (null = нет данных,
     *        напр. для пропущенных)
     * @param durationFormatter локализованное «X мин Y сек» ([localizedDuration] —
     *        готовая реализация через строковые ресурсы).
     * @return Отформатированный текст
     */
    fun format(
        sender: String,
        name: String?,
        text: String,
        timestamp: Long,
        type: String,
        sim: String? = null,
        durationMs: Long? = null,
        durationFormatter: (min: Int, sec: Int) -> String = { min, sec -> formatDurationRu(min, sec) },
    ): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp

        val t = when (type) {
            "sms" -> DEFAULT_SMS_TEMPLATE
            "outgoing_sms" -> DEFAULT_OUTGOING_SMS_TEMPLATE
            "incoming" -> DEFAULT_INCOMING_CALL_TEMPLATE
            "outgoing" -> DEFAULT_OUTGOING_CALL_TEMPLATE
            else -> DEFAULT_CALL_TEMPLATE
        }

        val zoned = cal.toInstant().atZone(java.time.ZoneId.systemDefault())
        return t
            .replace("{sender}", sender)
            .replace("{number}", sender)
            .replace("{name}", name?.let { " ($it)" } ?: "")
            .replace("{text}", text)
            .replace("{time}", timeFormat.format(zoned))
            .replace("{date}", dateFormat.format(zoned))
            .replace("{type}", type)
            .replace("{sim}", sim ?: "")
            .replace("{duration}", formatDuration(durationMs, durationFormatter))
    }

    /**
     * Форматирует длительность звонка в человекочитаемый вид.
     *
     * @param durationMs Длительность в миллисекундах или null (null = нет данных)
     * @param durationFormatter локализованные формы (см. [format])
     * @return «5 мин 23 сек», «45 сек» или пустая строка (для пропущенных)
     */
    private fun formatDuration(durationMs: Long?, durationFormatter: (Int, Int) -> String): String {
        val ms = durationMs ?: return ""
        if (ms <= 0) return ""
        val totalSec = (ms / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return when {
            min > 0 && sec > 0 -> durationFormatter(min, sec)
            min > 0 -> durationFormatter(min, 0)
            sec > 0 -> durationFormatter(0, sec)
            else -> durationFormatter(0, 0)
        }
    }

    /** Дефолтная (русская) форма длительности — используется, если локаль не передана. */
    private fun formatDurationRu(min: Int, sec: Int): String = when {
        min > 0 && sec > 0 -> "$min мин $sec сек"
        min > 0 -> "$min мин"
        else -> "$sec сек"
    }

    /**
     * Локализованный форматтер длительности через строковые ресурсы
     * (duration_min_sec / duration_min / duration_sec, ru + en).
     * Вызывать из UI/ресиверов, где есть Context.
     */
    fun localizedDuration(context: Context): (min: Int, sec: Int) -> String =
        { min, sec ->
            when {
                min > 0 && sec > 0 -> context.getString(R.string.duration_min_sec, min, sec)
                min > 0 -> context.getString(R.string.duration_min, min)
                else -> context.getString(R.string.duration_sec, sec)
            }
        }
}
