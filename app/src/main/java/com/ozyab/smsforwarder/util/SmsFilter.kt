package com.ozyab.smsforwarder.util

import android.content.Context
import java.util.regex.Pattern

/**
 * Детальные фильтры входящих SMS.
 *
 * Режимы (Prefs.filterMode):
 *  - "all"       — пересылать все SMS (кроме коротких номеров, если включено)
 *  - "contacts"  — только от отправителей из контактов
 *  - "whitelist" — только номера из белого списка (шаблоны с *, разделитель — запятая)
 *
 * Дополнительно: block-regex — если текст/номер совпал, SMS не пересылается.
 */
object SmsFilter {

    /** Проверяет, нужно ли переслать SMS. */
    fun shouldForward(
        context: Context,
        sender: String,
        body: String,
    ): Boolean {
        // Фильтр коротких номеров (банки/реклама) — < 5 цифр, не начинается с +
        if (Prefs.shortCodesFilter) {
            val digits = sender.filter { it.isDigit() }
            if (digits.length in 1..4) return false
        }

        // Block-regex: совпал в номере или тексте → не пересылаем
        val regex = Prefs.smsBlockRegex.trim()
        if (regex.isNotEmpty()) {
            val p = try {
                Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
            } catch (e: Exception) {
                null // кривой regex — игнорируем блокировку
            }
            if (p != null && (p.matcher(sender).find() || p.matcher(body).find())) {
                return false
            }
        }

        return when (Prefs.filterMode) {
            "contacts" -> ContactNames.lookup(context, sender) != null
            "whitelist" -> whitelistMatches(sender)
            else -> true // "all"
        }
    }

    /** Совпадает ли номер с белым списком (шаблоны через запятую, * — любая последовательность). */
    fun whitelistMatches(sender: String): Boolean {
        val list = Prefs.smsWhitelist
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (list.isEmpty()) return false // белый список пуст — ничего не пересылаем

        val digits = sender.filter { it.isDigit() }
        return list.any { pattern ->
            if (pattern.contains('*')) {
                // Шаблон: +79* → начинается с +79; *123* → содержит 123
                val p = pattern.replace(".", "\\.").replace("*", ".*")
                try {
                    Pattern.compile("^$p$").matcher(sender).find() ||
                        Pattern.compile("^$p$").matcher(digits).find()
                } catch (e: Exception) {
                    false
                }
            } else {
                // Точный номер: сравниваем и как есть, и по цифрам
                sender == pattern || digits == pattern.filter { it.isDigit() }
            }
        }
    }
}