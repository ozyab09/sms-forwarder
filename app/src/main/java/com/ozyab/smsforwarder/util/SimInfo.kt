package com.ozyab.smsforwarder.util

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Определение SIM-карты, на которую поступило сообщение/вызов.
 *
 * Возвращает строку вида "Sim1 beeline" / "Sim2 MTS" / null (SIM не найдена).
 */
object SimInfo {

    /**
     * @param subscriptionId subscriptionId из SMS (или null для вызовов —
     *                      берём первую активную SIM).
     */
    fun describe(context: Context, subscriptionId: Int?): String? {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val sub = subscriptionId?.takeIf { it > 0 }
            ?.let { id -> runCatching { sm.getActiveSubscriptionInfo(id) }.getOrNull() }
            ?: runCatching { sm.activeSubscriptionInfoList?.firstOrNull() }.getOrNull()
        if (sub == null) return null

        val slot = sub.simSlotIndex + 1
        val carrier = sub.carrierName?.toString().orEmpty()
            .ifBlank { operatorNameForSlot(context, sub.simSlotIndex) }
        if (carrier.isBlank()) return "Sim$slot"
        return "Sim$slot $carrier"
    }

    private fun operatorNameForSlot(context: Context, slotIndex: Int): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.getSimOperatorNameForPhone(slotIndex)
        } catch (e: Exception) {
            ""
        }
    }
}