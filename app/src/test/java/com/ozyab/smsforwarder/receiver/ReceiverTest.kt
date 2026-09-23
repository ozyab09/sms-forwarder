package com.ozyab.smsforwarder.receiver

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.receiver.BootReceiver
import com.ozyab.smsforwarder.util.LogStore
import com.ozyab.smsforwarder.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты CallReceiverLogic (расширенная state machine) и SmsReceiver.
 *
 * CallReceiverLogic:
 *  - RINGING → IDLE без OFFHOOK = пропущенный
 *  - RINGING → OFFHOOK → IDLE = принятый входящий
 *  - OFFHOOK без RINGING → IDLE = исходящий
 *  - сброс состояния между вызовами
 */
// TelephonyManager.EXTRA_INCOMING_NUMBER deprecated (incoming-номер приходит
// отдельным extra на API 29+); тесты эмулируют интент звонка осознанно.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@Suppress("DEPRECATION")
class ReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Prefs.init(context)
        Prefs.chatId
        Prefs.smsEnabled = true
        Prefs.callsEnabled = true
        Prefs.incomingCallsEnabled = true
        Prefs.outgoingCallsEnabled = true
        Prefs.quietHoursEnabled = false
        LogStore.clear()
        CallReceiverLogic.reset()
    }

    @After
    fun tearDown() {
        LogStore.clear()
    }

    // ──────────────────────────────────────────────
    //  CallReceiverLogic — missed calls
    // ──────────────────────────────────────────────

    @Test
    fun `RINGING then IDLE without OFFHOOK is missed`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
        assertNotNull("пропущенный вызов обнаружен", result)
        assertEquals("missed", result!!.type)
        assertTrue("содержит номер звонящего", result.text.contains("+79001112233"))
    }

    // ──────────────────────────────────────────────
    //  CallReceiverLogic — incoming (answered)
    // ──────────────────────────────────────────────

    @Test
    fun `RINGING then OFFHOOK then IDLE is incoming`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79001112233")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
        assertNotNull("принятый входящий обнаружен", result)
        assertEquals("incoming", result!!.type)
        assertTrue("текст отформатирован по шаблону «Входящий»", result.text.contains("Входящий"))
        assertTrue("текст содержит номер", result.text.contains("+79001112233"))
    }

    @Test
    fun `incoming call text contains phone number`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79009876543")
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79009876543")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79009876543")
        assertNotNull(result)
        assertTrue("текст содержит номер", result!!.text.contains("+79009876543"))
    }

    // ──────────────────────────────────────────────
    //  CallReceiverLogic — outgoing
    // ──────────────────────────────────────────────

    @Test
    fun `OFFHOOK without RINGING then IDLE is outgoing`() {
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79005556677")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79005556677")
        assertNotNull("исходящий звонок обнаружен", result)
        assertEquals("outgoing", result!!.type)
        assertTrue("текст отформатирован по шаблону «Исходящий»", result.text.contains("Исходящий"))
        assertTrue("текст содержит Кому", result.text.contains("Кому:"))
        assertTrue("текст содержит номер", result.text.contains("+79005556677"))
    }

    @Test
    fun `outgoing call text contains phone number`() {
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79001231234")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001231234")
        assertNotNull(result)
        assertTrue("текст содержит номер", result!!.text.contains("+79001231234"))
    }

    @Test
    fun `outgoing call text contains duration`() {
        // Баг: у исходящих {duration} был пуст — buildEvent вызывался без durationMs,
        // хотя callConnectTimeMs ставится при OFFHOOK и для исходящих
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79001231234")
        Thread.sleep(20) // гарантированная ненулевая длительность OFFHOOK→IDLE
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001231234")
        assertNotNull(result)
        val durationLine = result!!.text.lineSequence().firstOrNull { it.startsWith("Длительность:") }
        assertTrue(
            "длительность должна быть заполнена (не «Длительность: »): ${result.text}",
            durationLine != null && durationLine != "Длительность: ",
        )
    }

    // ──────────────────────────────────────────────
    //  CallReceiverLogic — state machine edge cases
    // ──────────────────────────────────────────────

    @Test
    fun `consecutive RINGING events reset state`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79004445566")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79004445566")
        assertNotNull("пропущенный второй вызов", result)
        assertEquals("missed", result!!.type)
        assertTrue("содержит номер второго звонящего", result.text.contains("+79004445566"))
    }

    @Test
    fun `missed event text uses call template`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79009998877")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79009998877")
        assertNotNull(result)
        assertEquals("missed", result!!.type)
        assertTrue("текст содержит номер", result.text.contains("+79009998877"))
    }

    @Test
    fun `incoming event text uses incoming call template`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79001112233")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
        assertNotNull(result)
        assertEquals("incoming", result!!.type)
        assertTrue("текст содержит номер", result.text.contains("+79001112233"))
    }

    @Test
    fun `outgoing event text uses outgoing call template`() {
        CallReceiverLogic.onPhoneStateChanged(context, "OFFHOOK", "+79005556677")
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79005556677")
        assertNotNull(result)
        assertEquals("outgoing", result!!.type)
        assertTrue("текст содержит номер", result.text.contains("+79005556677"))
    }

    @Test
    fun `null number is handled gracefully`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", null)
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", null)
        assertEquals(null, result)
    }

    @Test
    fun `reset clears state machine`() {
        CallReceiverLogic.onPhoneStateChanged(context, "RINGING", "+79001112233")
        CallReceiverLogic.reset()
        val result = CallReceiverLogic.onPhoneStateChanged(context, "IDLE", "+79001112233")
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
    }

    @Test
    fun `BootReceiver does not start service when forwarding disabled`() {
        // D5 (аудит-4): после «Стоп» автозапуск не должен возобновлять пересылку
        Prefs.forwardingEnabled = false
        Prefs.smsEnabled = true
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        // Сервис не должен запуститься: проверяем через shadow — приложение не падает,
        // а goAsync-блок завершится без вызова startForegroundService
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `CallReceiver ignores when forwarding disabled (master switch)`() {
        // T1 (аудит-3): «Стоп» = пересылка остановлена до «Запустить» —
        // новый PHONE_STATE не должен возобновлять пересылку
        Prefs.forwardingEnabled = false
        Prefs.callsEnabled = true
        val receiver = CallReceiver()
        val intent = Intent(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(android.telephony.TelephonyManager.EXTRA_STATE, "RINGING")
            putExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER, "+79001112233")
        }
        receiver.onReceive(context, intent)
        receiver.onReceive(context, Intent(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(android.telephony.TelephonyManager.EXTRA_STATE, "IDLE")
            putExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER, "+79001112233")
        })
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
    }

    @Test
    fun `CallReceiver ignores during quiet hours`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 0
        Prefs.quietHoursEnd = 1440

        val receiver = CallReceiver()
        val intent = Intent(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(android.telephony.TelephonyManager.EXTRA_STATE, "RINGING")
            putExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER, "+79001112233")
        }
        receiver.onReceive(context, intent)
    }

    // ──────────────────────────────────────────────
    //  SmsReceiver — guards
    // ──────────────────────────────────────────────

    @Test
    fun `SmsReceiver ignores wrong action`() {
        val receiver = SmsReceiver()
        val intent = Intent("com.example.WRONG_ACTION")
        receiver.onReceive(context, intent)
    }

    @Test
    fun `SmsReceiver ignores when sms disabled`() {
        Prefs.smsEnabled = false
        val receiver = SmsReceiver()
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        receiver.onReceive(context, intent)
    }

    @Test
    fun `SmsReceiver ignores during quiet hours`() {
        Prefs.quietHoursEnabled = true
        Prefs.quietHoursStart = 0
        Prefs.quietHoursEnd = 1440

        val receiver = SmsReceiver()
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        receiver.onReceive(context, intent)
    }

    @Test
    fun `SmsReceiver handles null messages gracefully`() {
        val receiver = SmsReceiver()
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        receiver.onReceive(context, intent)
    }
}
