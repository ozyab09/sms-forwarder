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
 * Регекс компилируется один раз; потенциально опасные паттерны (ReDoS) отклоняются.
 */
object SmsFilter {

    @Volatile
    private var cachedBlockRegex: Pattern? = null
    @Volatile
    private var cachedBlockSource: String? = null

    /**
     * Группа с квантификатором или альтернацией, за которой идёт ещё один
     * квантификатор — классический катастрофический backtracking (ReDoS),
     * способный повесить поток на длинном тексте. Такие паттерны отклоняем.
     */
    private val DANGEROUS_REGEX = Regex("[(][^()]*(?:[*+?]|[|])[^()]*[)][ \t]*[*+{]")

    /** Компилируем block-regex один раз (а не на каждое SMS). */
    private fun blockPattern(): Pattern? {
        val src = Prefs.smsBlockRegex.trim()
        if (src.isEmpty()) {
            cachedBlockRegex = null
            cachedBlockSource = null
            return null
        }
        cachedBlockSource?.let { if (it == src) return cachedBlockRegex }
        if (isDangerousRegex(src)) {
            LogStore.warn("Block-regex отклонён как потенциально опасный (ReDoS): $src")
            cachedBlockRegex = null
            cachedBlockSource = src
            return null
        }
        val p = try {
            Pattern.compile(src, Pattern.CASE_INSENSITIVE)
        } catch (e: Exception) {
            null // кривой regex — игнорируем блокировку
        }
        cachedBlockRegex = p
        cachedBlockSource = src
        return p
    }

    /** true, если паттерн похож на ReDoS (консервативная эвристика). */
    internal fun isDangerousRegex(pattern: String): Boolean =
        DANGEROUS_REGEX.containsMatchIn(pattern)

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
        val p = blockPattern()
        if (p != null && (p.matcher(sender).find() || p.matcher(body).find())) {
            return false
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
        return whitelistMatches(sender, list)
    }

    /** Чистая версия без Prefs — удобно тестировать. */
    fun whitelistMatches(sender: String, whitelist: List<String>): Boolean {
        if (whitelist.isEmpty()) return false // белый список пуст — ничего не пересылаем

        val digits = sender.filter { it.isDigit() }
        return whitelist.any { pattern ->
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