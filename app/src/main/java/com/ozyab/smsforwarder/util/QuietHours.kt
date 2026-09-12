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
            // одинаковые — интервал 24ч (всегда тихо)… либо пустой; трактуем как пустой
            false
        } else if (start < end) {
            nowMinutes in start until end
        } else {
            // пересекает полночь: 23:00..08:00 → до полуночи или после
            nowMinutes >= start || nowMinutes < end
        }
    }

    /** Текущий момент в минутах от полуночи. */
    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Проверка «сейчас тихие часы» (удобно для ресиверов). */
    fun isActiveNow(): Boolean = isEnabled() && isActive(nowMinutes(), startMinutes(), endMinutes())
}