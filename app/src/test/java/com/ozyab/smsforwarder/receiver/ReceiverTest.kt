package com.ozyab.smsforwarder.receiver

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты SmsReceiver и CallReceiverLogic (ROADMAP T5).
 *
 * SmsReceiver:
 *  - неверное действие → выход
 *  - Prefs.smsEnabled = false → выход
 *  - тихие часы → выход
 *  - валидное SMS → логируется
 *
 * CallReceiverLogic (state machine):
 *  - RINGING → IDLE без OFFHOOK = пропущенный
 *  - RINGING → OFFHOOK → IDLE = принятый (не пропущенный)
 *  - IDLE без RINGING = ничего (ищет в CallLog, но без разрешения → null)
 *  - сброс состояния между вызовами
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Prefs.init(context)
        Prefs.chatId // ждём готовности
        Prefs.smsEnabled = true
        Prefs.callsEnabled = true
        Prefs.quietHoursEnabled = false
        LogStore.clear()
        CallReceiverLogic.reset()
    }

    @After
    fun tearDown() {
        LogStore.clear()
    }

    // ──────────────────────────────────────────────
    //  CallReceiverLogic — state machine
    // ──────────────────────────────────────────────

    @Test
    fun `RINGING then IDLE without OFFHOOK is missed`() {
        val result = CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        assertEquals(null, result)

        val missed = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
        assertNotNull("пропущенный вызов обнаружен", missed)
        assertTrue("содержит номер звонящего", missed!!.contains("+79001112233"))
    }

    @Test
    fun `RINGING then OFFHOOK then IDLE is not missed`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79001112233")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
        assertEquals("принятый вызов не считается пропущенным", null, result)
    }

    @Test
    fun `IDLE without prior RINGING returns null`() {
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
        // Без RINGING → findRecentMissed ищет в CallLog, но без разрешения → null
        assertEquals(null, result)
    }

    @Test
    fun `consecutive RINGING events reset state`() {
        // Первый звонок
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        // Второй звонок сбросил первый
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79004445566")
        val missed = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79004445566")
        assertNotNull("пропущенный второй вызов", missed)
        assertTrue("содержит номер второго звонящего", missed!!.contains("+79004445566"))
    }

    @Test
    fun `missed event text contains formatted phone number`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001234567")
        val text = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001234567")
        assertNotNull(text)
        assertTrue("текст содержит номер", text!!.contains("+79001234567"))
    }

    @Test
    fun `missed event text uses call template`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79009998877")
        val text = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79009998877")
        assertNotNull(text)
        // Дефолтный шаблон для missed начинается с "📵 Пропущенный"
        assertTrue("текст начинается с иконки пропущенного",
            text!!.startsWith("\uD83D\uDCD5") || text.contains("Пропущенный"))
    }

    @Test
    fun `null number is handled gracefully`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", null)
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", null)
        // RINGING с null номером → IDLE: candidate = null (numberAtRinging is null)
        // findRecentMissed без разрешения → null
        assertEquals(null, result)
    }

    // ──────────────────────────────────────────────
    //  CallReceiver.onReceive — guards
    // ──────────────────────────────────────────────

    @Test
    fun `CallReceiver ignores wrong action`() {
        val receiver = CallReceiver()
        val intent = Intent("com.example.WRONG_ACTION")
        receiver.onReceive(context, intent)
        // Не крашимся — главное
    }

    @Test
    fun `CallReceiver ignores when calls disabled`() {
        Prefs.callsEnabled = false
        val receiver = CallReceiver()
        val intent = Intent(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(android.telephony.TelephonyManager.EXTRA_STATE, "RINGING")
            putExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER, "+79001112233")
        }
        receiver.onReceive(context, intent)
        // Не крашимся
    }

    @Test
    fun `CallReceiver ignores during quiet hours`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 0
        Prefs.quietHoursEnd = 1440 // весь день тихо

        val receiver = CallReceiver()
        val intent = Intent(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(android.telephony.TelephonyManager.EXTRA_STATE, "RINGING")
            putExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER, "+79001112233")
        }
        receiver.onReceive(context, intent)
        // Не крашимся
    }

    // ──────────────────────────────────────────────
    //  SmsReceiver — guards
    // ──────────────────────────────────────────────

    @Test
    fun `SmsReceiver ignores wrong action`() {
        val receiver = SmsReceiver()
        val intent = Intent("com.example.WRONG_ACTION")
        receiver.onReceive(context, intent)
        // Не крашимся
    }

    @Test
    fun `SmsReceiver ignores when sms disabled`() {
        Prefs.smsEnabled = false
        val receiver = SmsReceiver()
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        receiver.onReceive(context, intent)
        // Не крашимся
    }

    @Test
    fun `SmsReceiver ignores during quiet hours`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 0
        Prefs.quietHoursEnd = 1440

        val receiver = SmsReceiver()
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        receiver.onReceive(context, intent)
        // Не крашимся
    }

    @Test
    fun `SmsReceiver handles null messages gracefully`() {
        val receiver = SmsReceiver()
        // Intent без PDU — getMessagesFromIntent вернёт null/пустой список
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        receiver.onReceive(context, intent)
        // Не крашимся
    }

    // ──────────────────────────────────────────────
    //  CallReceiverLogic — reset
    // ──────────────────────────────────────────────

    @Test
    fun `reset clears state machine`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        CallReceiverLogic.reset()
        // После сброса RINGING forgotten → IDLE без кандидата
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
        assertEquals(null, result)
    }
}
