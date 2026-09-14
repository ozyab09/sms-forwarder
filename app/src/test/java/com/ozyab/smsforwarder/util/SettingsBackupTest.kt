package com.ozyab.smsforwarder.util

import androidx.test.core.app.ApplicationProvider
import com.ozyab.smsforwarder.telegram.Channel
import com.ozyab.smsforwarder.telegram.ChannelStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты экспорта/импорта настроек (privacy-first).
 *
 * Ключевые контракты:
 * - секреты (токен бота, пароли прокси) НЕ попадают в файл;
 * - direct-канал не экспортируется (при импорте генерируются новые id);
 * - импорт применяет настройки, не трогая токен;
 * - неверный формат и более новая версия файла отклоняются;
 * - новые настройки (исходящие SMS, звонки) экспортируются/импортируются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SettingsBackupTest {

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.botToken = ""
        Prefs.chatId = ""
        Prefs.smsEnabled = true
        Prefs.outgoingSmsEnabled = false
        Prefs.callsEnabled = true
        Prefs.incomingCallsEnabled = false
        Prefs.outgoingCallsEnabled = false
        Prefs.themeMode = "system"
        Prefs.messageTemplateSms = ""
        Prefs.messageTemplateOutgoingSms = ""
        Prefs.messageTemplateCall = ""
        Prefs.messageTemplateIncomingCall = ""
        Prefs.messageTemplateOutgoingCall = ""
        Prefs.messageTemplateNotification = ""
        ChannelStore.setAll(listOf(Channel.direct()))
    }

    @Test
    fun `export does not include secrets`() {
        Prefs.botToken = "123456789:SECRET_TOKEN"
        ChannelStore.setAll(
            listOf(
                Channel.direct(),
                Channel(
                    id = "p1", type = Channel.TYPE_HTTP, name = "Прокси",
                    host = "1.2.3.4", port = 8080, user = "user",
                    pass = "SUPERSECRET", enabled = true,
                ),
            )
        )

        val json = SettingsBackup.export()
        val s = json.toString()

        assertFalse("токен не должен попадать в бэкап", s.contains("SECRET_TOKEN"))
        assertFalse("пароль прокси не должен попадать в бэкап", s.contains("SUPERSECRET"))

        val channels = json.getJSONArray("channels")
        for (i in 0 until channels.length()) {
            val ch = channels.getJSONObject(i)
            assertNotEquals("direct не экспортируется", "direct", ch.optString("type"))
            assertFalse("pass не экспортируется", ch.has("pass"))
            assertFalse("id не экспортируется (новые при импорте)", ch.has("id"))
        }
    }

    @Test
    fun `export has app marker and version`() {
        val json = SettingsBackup.export()
        assertEquals("sms-forwarder", json.optString("app"))
        assertEquals(SettingsBackup.FORMAT_VERSION, json.optInt("version"))
        assertTrue(json.has("settings"))
        assertTrue(json.has("channels"))
    }

    @Test
    fun `export includes new forwarding settings`() {
        Prefs.outgoingSmsEnabled = true
        Prefs.incomingCallsEnabled = true
        Prefs.outgoingCallsEnabled = true

        val json = SettingsBackup.export()
        val settings = json.getJSONObject("settings")

        assertTrue(settings.getBoolean("outgoingSmsEnabled"))
        assertTrue(settings.getBoolean("incomingCallsEnabled"))
        assertTrue(settings.getBoolean("outgoingCallsEnabled"))
        assertFalse(settings.has("notificationsEnabled"))
        assertFalse(settings.has("notificationApps"))
    }

    @Test
    fun `export includes new templates`() {
        Prefs.messageTemplateOutgoingSms = "OUT: {sender}"
        Prefs.messageTemplateIncomingCall = "IN: {sender}"
        Prefs.messageTemplateOutgoingCall = "DIAL: {sender}"
        Prefs.messageTemplateNotification = "NOTIF: {app}"

        val json = SettingsBackup.export()
        val settings = json.getJSONObject("settings")

        assertEquals("OUT: {sender}", settings.getString("messageTemplateOutgoingSms"))
        assertEquals("IN: {sender}", settings.getString("messageTemplateIncomingCall"))
        assertEquals("DIAL: {sender}", settings.getString("messageTemplateOutgoingCall"))
        assertEquals("NOTIF: {app}", settings.getString("messageTemplateNotification"))
    }

    @Test
    fun `import applies settings and keeps bot token`() {
        val tokenBefore = Prefs.botToken
        Prefs.chatId = "old"

        val body = JSONObject()
            .put("app", "sms-forwarder")
            .put("version", 1)
            .put(
                "settings",
                JSONObject()
                    .put("chatId", "12345")
                    .put("smsEnabled", false)
                    .put("outgoingSmsEnabled", true)
                    .put("incomingCallsEnabled", true)
                    .put("outgoingCallsEnabled", true)
                    .put("themeMode", "dark"),
            )
            .put(
                "channels",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("type", "socks5")
                        .put("name", "Импорт")
                        .put("host", "10.0.0.1")
                        .put("port", 1080)
                        .put("user", "u")
                        .put("enabled", true),
                ),
            )

        val result = SettingsBackup.import(body)

        waitForPrefs({
            Prefs.chatId == "12345" && !Prefs.smsEnabled &&
                Prefs.outgoingSmsEnabled && Prefs.incomingCallsEnabled &&
                Prefs.outgoingCallsEnabled && Prefs.themeMode == "dark"
        })

        assertEquals("12345", Prefs.chatId)
        assertFalse(Prefs.smsEnabled)
        assertTrue(Prefs.outgoingSmsEnabled)
        assertTrue(Prefs.incomingCallsEnabled)
        assertTrue(Prefs.outgoingCallsEnabled)
        assertEquals("dark", Prefs.themeMode)
        assertEquals("токен не трогается импортом", tokenBefore, Prefs.botToken)
        assertEquals("канал импортирован", 1, result.channelsImported)
    }

    @Test
    fun `import applies new templates`() {
        val body = JSONObject()
            .put("app", "sms-forwarder")
            .put("version", 1)
            .put(
                "settings",
                JSONObject()
                    .put("messageTemplateOutgoingSms", "OUT: {sender}")
                    .put("messageTemplateIncomingCall", "IN: {sender}")
                    .put("messageTemplateOutgoingCall", "DIAL: {sender}")
                    .put("messageTemplateNotification", "NOTIF: {app}"),
            )

        SettingsBackup.import(body)

        waitForPrefs({
            Prefs.messageTemplateOutgoingSms == "OUT: {sender}" &&
                Prefs.messageTemplateIncomingCall == "IN: {sender}" &&
                Prefs.messageTemplateOutgoingCall == "DIAL: {sender}" &&
                Prefs.messageTemplateNotification == "NOTIF: {app}"
        })

        assertEquals("OUT: {sender}", Prefs.messageTemplateOutgoingSms)
        assertEquals("IN: {sender}", Prefs.messageTemplateIncomingCall)
        assertEquals("DIAL: {sender}", Prefs.messageTemplateOutgoingCall)
        assertEquals("NOTIF: {app}", Prefs.messageTemplateNotification)
    }

    @Test
    fun `import without channels keeps existing channels`() {
        ChannelStore.setAll(
            listOf(
                Channel.direct(),
                Channel("p1", Channel.TYPE_HTTP, "Старый", "h", 1, "", "", true),
            )
        )

        val body = JSONObject()
            .put("app", "sms-forwarder")
            .put("version", 1)
            .put("settings", JSONObject().put("chatId", "42"))

        SettingsBackup.import(body)
        waitForPrefs({ Prefs.chatId == "42" })
        assertEquals(2, ChannelStore.all().size)
        assertEquals("42", Prefs.chatId)
    }

    @Test
    fun `import rejects unknown app`() {
        val body = JSONObject()
            .put("app", "other-app")
            .put("version", 1)
            .put("settings", JSONObject())
        try {
            SettingsBackup.import(body)
            fail("должен бросить IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `import rejects newer format version`() {
        val body = JSONObject()
            .put("app", "sms-forwarder")
            .put("version", SettingsBackup.FORMAT_VERSION + 1)
            .put("settings", JSONObject())
        try {
            SettingsBackup.import(body)
            fail("должен бросить IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `import rejects missing settings section`() {
        val body = JSONObject()
            .put("app", "sms-forwarder")
            .put("version", 1)
        try {
            SettingsBackup.import(body)
            fail("должен бросить IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `default file name is dated`() {
        val name = SettingsBackup.defaultFileName()
        assertTrue(
            "имя файла должно быть sms-forwarder-backup-YYYY-MM-DD.json, было: $name",
            name.matches(Regex("sms-forwarder-backup-\\d{4}-\\d{2}-\\d{2}\\.json")),
        )
    }

    private fun waitForPrefs(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        assertTrue("Prefs не достигли ожидаемого состояния за 5с", predicate())
    }
}
