package com.ozyab.smsforwarder.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Тесты [BatteryMonitor]: события формируются на переходе порога,
 * анти-спам (без повторов, пока порог не «разряжен» обратно), respect настроек.
 */
@RunWith(RobolectricTestRunner::class)
class BatteryMonitorTest {

    private lateinit var monitor: BatteryMonitor
    private val events = mutableListOf<Pair<BatteryMonitor.EventKind, Int>>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        monitor = BatteryMonitor(context) { kind, pct -> events.add(kind to pct) }
    }

    @After
    fun tearDown() {
        Prefs.batteryFullEnabled = false
        Prefs.batteryLowEnabled = false
        Prefs.batteryFullThreshold = 100
        Prefs.batteryLowThreshold = 15
        Prefs.chargingStepEnabled = false
        Prefs.chargingStepThreshold = 10
    }

    private fun full(enabled: Boolean = true, threshold: Int = 100) {
        Prefs.batteryFullEnabled = enabled
        Prefs.batteryFullThreshold = threshold
    }

    private fun low(enabled: Boolean = true, threshold: Int = 15) {
        Prefs.batteryLowEnabled = enabled
        Prefs.batteryLowThreshold = threshold
    }

    private fun step(enabled: Boolean = true, threshold: Int = 10) {
        Prefs.chargingStepEnabled = enabled
        Prefs.chargingStepThreshold = threshold
    }

    @Test
    fun `full event fires once at threshold and not repeated`() {
        full(threshold = 100)
        monitor.handleLevel(99) // ещё не порог
        assertEquals(emptyList<Pair<BatteryMonitor.EventKind, Int>>(), events)
        monitor.handleLevel(100) // порог достигнут
        assertEquals(listOf(BatteryMonitor.EventKind.FULL to 100), events)
        monitor.handleLevel(100) // анти-спам: повтор нет
        monitor.handleLevel(95)  // всё ещё выше гистерезиса? ниже порога — сброс
        assertEquals(1, events.size)
    }

    @Test
    fun `full event refires after dropping below threshold`() {
        full(threshold = 90)
        monitor.handleLevel(90)
        assertEquals(1, events.size)
        monitor.handleLevel(80) // ниже порога — гистерезис сброшен
        monitor.handleLevel(85) // ниже порога — события нет
        assertEquals(1, events.size)
        monitor.handleLevel(91) // снова порог — повтор
        assertEquals(2, events.size)
        assertEquals(BatteryMonitor.EventKind.FULL, events.last().first)
    }

    @Test
    fun `low event fires once below threshold and not repeated`() {
        low(threshold = 15)
        monitor.handleLevel(50)
        assertTrue(events.isEmpty())
        monitor.handleLevel(15)
        assertEquals(listOf(BatteryMonitor.EventKind.LOW to 15), events)
        monitor.handleLevel(10) // анти-спам
        monitor.handleLevel(5)  // анти-спам
        assertEquals(1, events.size)
    }

    @Test
    fun `low event refires after rising above threshold`() {
        low(threshold = 20)
        monitor.handleLevel(20)
        assertEquals(1, events.size)
        monitor.handleLevel(25) // выше порога — сброс гистерезиса
        monitor.handleLevel(22) // нет события
        assertEquals(1, events.size)
        monitor.handleLevel(19) // снова ниже порога — повтор
        assertEquals(2, events.size)
        assertEquals(BatteryMonitor.EventKind.LOW, events.last().first)
    }

    @Test
    fun `disabled features produce no events`() {
        full(enabled = false)
        low(enabled = false)
        monitor.handleLevel(100)
        monitor.handleLevel(0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `custom thresholds are respected`() {
        full(threshold = 80)
        low(threshold = 30)
        monitor.handleLevel(80) // full по кастомному порогу
        assertEquals(1, events.size)
        monitor.handleLevel(30) // low по кастомному порогу
        assertEquals(2, events.size)
        assertEquals(
            listOf(
                BatteryMonitor.EventKind.FULL to 80,
                BatteryMonitor.EventKind.LOW to 30,
            ),
            events,
        )
    }

    // --- ШАГ ЗАРЯДКИ (#NEW) ---

    @Test
    fun `charge step fires at threshold delta`() {
        step(enabled = true, threshold = 10)
        monitor.onPowerConnected(50) // start tracking from 50%
        assertEquals(emptyList<Pair<BatteryMonitor.EventKind, Int>>(), events) // no event on connect
        monitor.handleLevel(55) // within threshold — no event
        assertEquals(emptyList<Pair<BatteryMonitor.EventKind, Int>>(), events)
        monitor.handleLevel(60) // 60 - 50 = 10 ≥ threshold — EVENT
        assertEquals(listOf(BatteryMonitor.EventKind.CHARGE_STEP to 60), events)
    }

    @Test
    fun `charge step fires repeatedly for each threshold delta`() {
        step(enabled = true, threshold = 10)
        monitor.onPowerConnected(0)
        monitor.handleLevel(10) // 0→10 = 10 — step 1
        assertEquals(1, events.size)
        monitor.handleLevel(19) // within next 10 — no event
        assertEquals(1, events.size)
        monitor.handleLevel(20) // 10→20 = 10 — step 2
        assertEquals(2, events.size)
        monitor.handleLevel(35) // 20→35 = 15 ≥ 10 — step 3 (accumulated) should fire once at 30 boundary equivalent
        // Actually the logic fires when pct > last && delta >= threshold, so 20→35 gives 15 >= 10 → event
        assertEquals(3, events.size)
    }

    @Test
    fun `charge step resets on power disconnect`() {
        step(enabled = true, threshold = 10)
        monitor.onPowerConnected(20)
        monitor.handleLevel(30) // 20→30 = 10 — step
        assertEquals(1, events.size)
        monitor.onPowerDisconnected() // reset
        // After disconnect, lastChargeStepLevel becomes -1, so no more steps until reconnect
        monitor.handleLevel(35) // no event since disconnected
        assertEquals(1, events.size)
        // Reconnect
        monitor.onPowerConnected(35)
        monitor.handleLevel(45) // 35→45 = 10 — step again
        assertEquals(2, events.size)
    }

    @Test
    fun `disabled charging step produces no events`() {
        step(enabled = false)
        monitor.onPowerConnected(0)
        monitor.handleLevel(10)
        monitor.handleLevel(20)
        monitor.handleLevel(30)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `no charging step without prior onPowerConnected`() {
        step(enabled = true, threshold = 10)
        // never called onPowerConnected — lastChargeStepLevel stays -1
        monitor.handleLevel(10)
        monitor.handleLevel(20)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `charge step ignores small fluctuations below threshold`() {
        step(enabled = true, threshold = 10)
        monitor.onPowerConnected(0)
        monitor.handleLevel(5) // 0→5 = 5 < 10 — no event
        assertEquals(emptyList<Pair<BatteryMonitor.EventKind, Int>>(), events)
        monitor.handleLevel(7) // still < 10
        monitor.handleLevel(9) // still < 10
        assertEquals(emptyList<Pair<BatteryMonitor.EventKind, Int>>(), events)
        monitor.handleLevel(10) // 0→10 = 10 — finally
        assertEquals(1, events.size)
    }

    @Test
    fun `custom step threshold is respected`() {
        step(enabled = true, threshold = 25)
        monitor.onPowerConnected(0)
        monitor.handleLevel(20) // 0→20 = 20 < 25 — no event
        assertEquals(emptyList<Pair<BatteryMonitor.EventKind, Int>>(), events)
        monitor.handleLevel(25) // 0→25 = 25 ≥ 25 — step
        assertEquals(1, events.size)
        monitor.handleLevel(40) // 25→40 = 15 < 25 — no event
        assertEquals(1, events.size)
        monitor.handleLevel(50) // 25→50 = 25 ≥ 25 — second step
        assertEquals(2, events.size)
    }
}
