package com.ozyab.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ozyab.smsforwarder.service.ForwardService
import com.ozyab.smsforwarder.util.ContactNames
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.formatTimestamp

/**
 * Перехват входящих SMS.
 *
 * Срабатывает на каждое входящее сообщение (не требует статуса
 * Default SMS Handler). Форматирует и передаёт в ForwardService.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.smsEnabled) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sb = StringBuilder()
        for (m in messages) sb.append(m.messageBody ?: "")
        val body = sb.toString()

        val sender = messages.firstOrNull()?.originatingAddress ?: "Неизвестный"
        val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        // Фильтр коротких номеров (банки/реклама) — < 5 цифр, не начинается с +
        if (Prefs.shortCodesFilter) {
            val digits = sender.filter { it.isDigit() }
            if (digits.length in 1..4) return
        }

        val name = ContactNames.lookup(context, sender)
        val time = formatTimestamp(ts)

        val text = buildString {
            appendLine("📩 SMS [$time]")
            appendLine("От: $sender${if (name != null) " ($name)" else ""}")
            appendLine("─".repeat(30))
            append(body)
        }

        ForwardService.start(context, text)
    }
}