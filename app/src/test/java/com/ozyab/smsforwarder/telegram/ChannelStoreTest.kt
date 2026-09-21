package com.ozyab.smsforwarder.telegram

import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты динамической сортировки каналов (issue #123).
 *
 * [promoteOrder] — чистая функция promote-on-success: канал, через который
 * удалось отправить сообщение, поднимается на первое место списка.
 * [demoteOrder] — чистая функция demote-on-failure: неуспешный канал опускается
 * в конец списка. Порядок полностью динамический — в том числе для direct
 * («Без прокси»). Переключатель [Channel.enabled] сортировка не трогает.
 *
 * Контракты хранилища ([ChannelStore.setAll]/[ChannelStore.remove]) проверяются
 * на Robolectric — им нужен инициализированный [Prefs] (secure prefs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ChannelStoreTest {

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        ChannelStore.invalidate()
        ChannelStore.setAll(listOf(Channel.direct()))
    }

    private fun channel(id: String, name: String, type: String = Channel.TYPE_DIRECT, enabled: Boolean = true) =
        Channel(id = id, type = type, name = name, host = "", port = 0, user = "", pass = "", enabled = enabled)

    // --- promoteOrder: успех → наверх (в т.ч. direct) ---

    @Test
    fun `promote moves proxy to first place`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = promoteOrder(cur, "p3")!!
        assertEquals(listOf("p3", "direct", "p1", "p2"), next.map { it.id })
    }

    @Test
    fun `promote direct moves it to first place`() {
        // direct demote'нут в конец после неудач — успех снова возвращает его наверх
        val cur = listOf(
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("direct", "Без прокси"),
        )
        val next = promoteOrder(cur, "direct")!!
        assertEquals(listOf("direct", "p1", "p2"), next.map { it.id })
    }

    @Test
    fun `promote already first returns null`() {
        val cur = listOf(
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("direct", "Без прокси"),
        )
        assertNull(promoteOrder(cur, "p1"))
    }

    @Test
    fun `promote unknown channel returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
        )
        assertNull(promoteOrder(cur, "nope"))
    }

    @Test
    fun `promote single channel returns null`() {
        assertNull(promoteOrder(listOf(channel("direct", "Без прокси")), "direct"))
    }

    @Test
    fun `promote empty list returns null`() {
        assertNull(promoteOrder(emptyList(), "p1"))
    }

    @Test
    fun `promote keeps channel set`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = promoteOrder(cur, "p2")!!
        assertEquals(setOf("direct", "p1", "p2", "p3"), next.map { it.id }.toSet())
        assertEquals("p2", next.first().id)
    }

    // --- demoteOrder: неудача → в конец (в т.ч. direct) ---

    @Test
    fun `demote moves proxy to last place`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = demoteOrder(cur, "p1")!!
        assertEquals(listOf("direct", "p2", "p3", "p1"), next.map { it.id })
    }

    @Test
    fun `demote direct moves it to last place`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        val next = demoteOrder(cur, "direct")!!
        assertEquals(listOf("p1", "p2", "direct"), next.map { it.id })
    }

    @Test
    fun `demote already last returns null`() {
        val cur = listOf(
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("direct", "Без прокси"),
        )
        assertNull(demoteOrder(cur, "direct"))
    }

    @Test
    fun `demote unknown channel returns null`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
        )
        assertNull(demoteOrder(cur, "nope"))
    }

    @Test
    fun `demote single channel returns null`() {
        assertNull(demoteOrder(listOf(channel("direct", "Без прокси")), "direct"))
    }

    @Test
    fun `demote empty list returns null`() {
        assertNull(demoteOrder(emptyList(), "p1"))
    }

    @Test
    fun `demote keeps channel set and order of others`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
            channel("p3", "Прокси 3", Channel.TYPE_HTTP),
        )
        val next = demoteOrder(cur, "p2")!!
        assertEquals(listOf("direct", "p1", "p3", "p2"), next.map { it.id })
    }

    // --- Регрессия (issue #123): сортировка не влияет на enabled ---

    @Test
    fun `promote does not change enabled flags`() {
        val cur = listOf(
            channel("direct", "Без прокси", enabled = true),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP, enabled = true),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5, enabled = false),
        )
        val next = promoteOrder(cur, "p2")!!
        // порядок: p2 первым, остальные в исходном порядке; enabled НЕ меняется
        assertEquals(listOf("p2", "direct", "p1"), next.map { it.id })
        assertEquals(listOf(false, true, true), next.map { it.enabled })
    }

    @Test
    fun `demote does not change enabled flags`() {
        val cur = listOf(
            channel("direct", "Без прокси", enabled = true),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP, enabled = false),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5, enabled = true),
        )
        val next = demoteOrder(cur, "p1")!!
        assertEquals(listOf(true, true, false), next.map { it.enabled })
        assertEquals(listOf("direct", "p2", "p1"), next.map { it.id })
    }

    // --- move: ручная сортировка тоже для всех каналов ---

    @Test
    fun `promote and demote are inverse for same channel`() {
        val cur = listOf(
            channel("direct", "Без прокси"),
            channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            channel("p2", "Прокси 2", Channel.TYPE_SOCKS5),
        )
        val promoted = promoteOrder(cur, "p2")!!   // p2 → начало
        val back = demoteOrder(promoted, "p2")!!   // p2 → конец
        assertEquals(listOf("direct", "p1", "p2"), back.map { it.id })
    }

    // --- Ранние контракты нормализации: enabled direct не выключается через setAll ---

    @Test
    fun `setAll forces direct enabled`() {
        ChannelStore.invalidate()
        ChannelStore.setAll(
            listOf(
                channel("direct", "Без прокси", enabled = false),
                channel("p1", "Прокси 1", Channel.TYPE_HTTP, enabled = true),
            )
        )
        assertTrue(ChannelStore.get("direct")!!.enabled)
        ChannelStore.setAll(listOf(Channel.direct()))
    }

    @Test
    fun `setAll keeps direct position when present`() {
        ChannelStore.invalidate()
        ChannelStore.setAll(
            listOf(
                channel("p1", "Прокси 1", Channel.TYPE_HTTP, enabled = true),
                channel("direct", "Без прокси"),
                channel("p2", "Прокси 2", Channel.TYPE_SOCKS5, enabled = true),
            )
        )
        // Позиция direct НЕ фиксировится — demote'нутый direct остаётся где был
        assertEquals(listOf("p1", "direct", "p2"), ChannelStore.all().map { it.id })
        ChannelStore.setAll(listOf(Channel.direct()))
    }

    @Test
    fun `setAll without direct adds it first`() {
        ChannelStore.invalidate()
        ChannelStore.setAll(listOf(channel("p1", "Прокси 1", Channel.TYPE_HTTP)))
        assertEquals(listOf("direct", "p1"), ChannelStore.all().map { it.id })
        assertTrue(ChannelStore.get("direct")!!.enabled)
        ChannelStore.setAll(listOf(Channel.direct()))
    }

    @Test
    fun `remove direct is a no-op`() {
        ChannelStore.invalidate()
        ChannelStore.setAll(
            listOf(
                channel("direct", "Без прокси"),
                channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            )
        )
        assertFalse(ChannelStore.remove("direct"))
        assertEquals(2, ChannelStore.all().size)
        ChannelStore.setAll(listOf(Channel.direct()))
    }

    @Test
    fun `remove proxy works`() {
        ChannelStore.invalidate()
        ChannelStore.setAll(
            listOf(
                channel("direct", "Без прокси"),
                channel("p1", "Прокси 1", Channel.TYPE_HTTP),
            )
        )
        assertTrue(ChannelStore.remove("p1"))
        assertEquals(listOf("direct"), ChannelStore.all().map { it.id })
        ChannelStore.setAll(listOf(Channel.direct()))
    }
}
