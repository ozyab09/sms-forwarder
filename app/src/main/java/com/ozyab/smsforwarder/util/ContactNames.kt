package com.ozyab.smsforwarder.util

import android.content.Context
import android.provider.ContactsContract
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Locale

/** Утилиты: контакты + форматирование времени. */

/** Форматирует timestamp в "2026-09-07 21:35". */
fun formatTimestamp(ts: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val y = cal.get(Calendar.YEAR)
    val mo = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
    val d = String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))
    val h = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
    val mi = String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))
    return "$y-$mo-$d $h:$mi"
}

object ContactNames {
    /**
     * Ищет имя контакта по номеру телефона.
     * Возвращает null, если контакт не найден или нет разрешения READ_CONTACTS.
     */
    fun lookup(context: Context, number: String): String? {
        if (number.isBlank()) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: SecurityException) {
            null // нет разрешения READ_CONTACTS
        } catch (e: Exception) {
            null
        }
    }
}