package com.ozyab.smsforwarder.util

import java.util.Calendar

/**
 * Тихие часы — период, когда пересылка SMS/вызовов не выполняется.
 *
 * Хранятся как минуты от полуночи (0..1439) в Prefs:
 *  - quietHoursEnabled: Boolean
 *  - quietHoursStart: Int (минуты, напр. 23*60+0 = 1380)
 *  - quietHoursEnd: Int   (минуты, напр. 8*60 = 480)
 *
 * Интервал может пересекать полночь (23:00–08:00). Чистая функция
 * [isActive] тестируется без Android.
 */
object QuietHours {

    fun isEnabled(): Boolean = Prefs.quietHoursEnabled

    fun startMinutes(): Int = Prefs.quietHoursStart
    fun endMinutes(): Int = Prefs.quietHoursEnd

    fun setEnabled(v: Boolean) { Prefs.quietHoursEnabled = v }
    fun setStartMinutes(v: Int) { Prefs.quietHoursStart = v }
    fun setEndMinutes(v: Int) { Prefs.quietHoursEnd = v }

    /** Минуты от полуночи для [hour]:[minute]. */
    fun toMinutes(hour: Int, minute: Int): Int = hour * 60 + minute

    /** true, если [nowMinutes] попадает в интервал [startMinutes]..[endMinutes] (может пересекать полночь). */
    fun isActive(nowMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
        if (nowMinutes < 0 || nowMinutes > 1439) return false
        val start = startMinutes.coerceIn(0, 1439)
        val end = endMinutes.coerceIn(0, 1439)
        return if (start == end) {
            // одинаковые — трактуем как пустой интервал (см. подсказку в UI:
            // равные start/end ничего не приглушают)
            false
        } else if (start < end) {
            nowMinutes in start until end
        } else {
            // пересекает полночь: 23:00..08:00 → до полуночи или после
            nowMinutes >= start || nowMinutes < end
        }
    }

    /** Текущий момент в минутах от полуночи (использует системную TZ). */
    fun nowMinutes(timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(timeZone)
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Проверка «сейчас тихие часы» (удобно для ресиверов). */
    fun isActiveNow(timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): Boolean =
        isEnabled() && isActive(nowMinutes(timeZone), startMinutes(), endMinutes())

    /**
     * Проверка, попадает ли заданный timestamp в тихие часы.
     */
    fun isActiveAt(timestamp: Long, timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): Boolean {
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = timestamp
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return isEnabled() && isActive(minutes, startMinutes(), endMinutes())
    }

    /**
     * Timestamp (ms) окончания текущего периода тихих часов.
     *
     * Если тихие часы не активны — возвращает [now] (событие можно отправлять
     * немедленно). Если активны — время окончания интервала с учётом пересечения
     * полуночи: для 23:00–08:00 в 02:00 вернёт сегодняшние 08:00, в 23:30 —
     * завтрашние 08:00.
     *
     * Используется режимом «накопления» (issue #170): событие ставится в
     * очередь с nextRetryAt = конец тихих часов и уходит после их окончания.
     */
    fun endTimestampMs(
        now: Long = System.currentTimeMillis(),
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Long {
        if (!isActiveAt(now, timeZone)) return now
        val end = endMinutes().coerceIn(0, 1439)
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, end / 60)
        cal.set(Calendar.MINUTE, end % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var endMs = cal.timeInMillis
        if (endMs <= now) {
            // Конец интервала уже прошёл сегодня — завтра (интервал через полночь)
            endMs += 24 * 60 * 60 * 1000L
        }
        return endMs
    }
}