package com.ozyab.smsforwarder.util

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Тесты [ChargerMonitor]: событие подключения/отключения зарядки и
 * процент заряда на момент события (#171).
 */
@RunWith(RobolectricTestRunner::class)
class ChargerMonitorTest {

    private lateinit var context: Context
    private lateinit var monitor: ChargerMonitor
    private val events = mutableListOf<Pair<ChargerMonitor.EventKind, Int?>>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        monitor = ChargerMonitor(context) { kind, level -> events.add(kind to level) }
    }

    @After
    fun tearDown() {
        monitor.stop()
    }

    private fun setCapacity(pct: Int) {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(manager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, pct)
    }

    @Test
    fun `currentLevel returns battery capacity`() {
        setCapacity(42)
        assertEquals(42, monitor.currentLevel())
    }

    @Test
    fun `currentLevel returns null when capacity unavailable`() {
        setCapacity(-1)
        assertNull(monitor.currentLevel())
    }

    @Test
    fun `power connected event carries battery level`() {
        setCapacity(63)
        monitor.start()

        context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(ChargerMonitor.EventKind.CONNECTED to 63), events)
    }

    @Test
    fun `power disconnected event carries battery level`() {
        setCapacity(17)
        monitor.start()

        context.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(ChargerMonitor.EventKind.DISCONNECTED to 17), events)
    }

    @Test
    fun `no events after stop`() {
        setCapacity(50)
        monitor.start()
        monitor.stop()

        context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(events.isEmpty())
    }
}
