package com.ozyab.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ozyab.smsforwarder.service.ForwardService
import com.ozyab.smsforwarder.util.ContactNames
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.ReceiverExecutor
import com.ozyab.smsforwarder.util.SimInfo
import com.ozyab.smsforwarder.util.TemplateFormatter

/**
 * Перехват входящих SMS.
 *
 * Срабатывает на каждое входящее сообщение (не требует статуса
 * Default SMS Handler). Форматирует и передаёт в ForwardService.
 *
 * Тяжёлая часть (контакты, SIM, фильтры) вынесена с main thread через
 * [ReceiverExecutor] — под пачкой SMS главный поток не блокируется (ANR).
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.smsEnabled) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        ReceiverExecutor.goAsync(this) {
            val sb = StringBuilder()
            for (m in messages) sb.append(m.messageBody ?: "")
            val body = sb.toString()

            val sender = messages.firstOrNull()?.originatingAddress ?: "Неизвестный"
            val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

            val name = ContactNames.lookup(context, sender)
            // SIM-слот и оператор: берём subscriptionId из интента (на какую SIM пришло)
            val subId = if (android.os.Build.VERSION.SDK_INT >= 24)
                intent.getIntExtra("subscription", -1).takeIf { it > 0 }
            else null
            val sim = SimInfo.describe(context, subId)

            val text = TemplateFormatter.format(
                template = Prefs.messageTemplateSms,
                sender = sender,
                name = name,
                text = body,
                timestamp = ts,
                type = "sms",
                sim = sim
            )

            ForwardService.start(context, text, type = "sms", sender = sender, eventTime = ts)
        }
    }
}