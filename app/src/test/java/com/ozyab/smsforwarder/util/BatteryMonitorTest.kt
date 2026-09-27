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
    }

    private fun full(enabled: Boolean = true, threshold: Int = 100) {
        Prefs.batteryFullEnabled = enabled
        Prefs.batteryFullThreshold = threshold
    }

    private fun low(enabled: Boolean = true, threshold: Int = 15) {
        Prefs.batteryLowEnabled = enabled
        Prefs.batteryLowThreshold = threshold
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
}
