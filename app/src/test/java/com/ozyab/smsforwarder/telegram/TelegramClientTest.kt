package com.ozyab.smsforwarder.telegram

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты `TelegramClient` (Bot API) через MockWebServer.
 *
 * Токен/chatId/каналы передаются явно (internal-перегрузки) — ни Prefs
 * (EncryptedSharedPreferences в Robolectric не пишется), ни реальная сеть
 * не нужны: HTTP-ответы подставляет MockWebServer, а база Bot API в тестах
 * указывает на него ([ChannelClientFactory.apiBase]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TelegramClientTest {

    private lateinit var server: MockWebServer
    private val token = "123:TEST"
    private val chatId = "42"
    private val direct = Channel.direct()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        ChannelClientFactory.apiBase = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
        ChannelClientFactory.apiBase = ChannelClientFactory.API_BASE
        ChannelClientFactory.invalidate()
    }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    // ===== sendMessage =====

    @Test
    fun `sendMessage success returns message id`() = runBlocking {
        enqueue(200, """{"ok":true,"result":{"message_id":55}}""")

        val r = TelegramClient.sendMessage("hello", token, chatId, listOf(direct))

        assertTrue(r is TelegramClient.Result.Ok)
        assertEquals(55L, (r as TelegramClient.Result.Ok).messageId)

        val req = server.takeRequest()
        assertEquals("/bot$token/sendMessage", req.path)
        val body = req.body.readUtf8()
        assertTrue("chat_id в запросе", body.contains("chat_id=42"))
        assertTrue("текст в запросе", body.contains("text=hello"))
    }

    @Test
    fun `sendMessage telegram error returns description`() = runBlocking {
        enqueue(400, """{"ok":false,"description":"chat not found"}""")

        val r = TelegramClient.sendMessage("hello", token, chatId, listOf(direct))

        assertTrue(r is TelegramClient.Result.Err)
        val reasons = (r as TelegramClient.Result.Err).reasons.joinToString("; ")
        assertTrue("причина ошибки из ответа: $reasons", reasons.contains("chat not found"))
    }

    @Test
    fun `sendMessage blank token fails without network`() = runBlocking {
        val r = TelegramClient.sendMessage("hello", "", chatId, listOf(direct))

        assertTrue(r is TelegramClient.Result.Err)
        assertTrue((r as TelegramClient.Result.Err).reasons.joinToString().contains("Токен"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sendMessage blank chat id fails without network`() = runBlocking {
        val r = TelegramClient.sendMessage("hello", token, "", listOf(direct))

        assertTrue(r is TelegramClient.Result.Err)
        assertEquals(0, server.requestCount)
    }

    // ===== resolveChatId =====

    @Test
    fun `resolveChatId returns chat id from getUpdates`() = runBlocking {
        enqueue(200, """{"ok":true,"result":[{"message":{"chat":{"id":777}}}]}""")

        val r = TelegramClient.resolveChatId(token, listOf(direct))

        assertTrue(r is TelegramClient.Result.Ok)
        assertEquals(777L, (r as TelegramClient.Result.Ok).messageId)
        assertEquals("/bot$token/getUpdates", server.takeRequest().path)
    }

    @Test
    fun `resolveChatId reports no messages`() = runBlocking {
        enqueue(200, """{"ok":true,"result":[]}""")

        val r = TelegramClient.resolveChatId(token, listOf(direct))

        assertTrue(r is TelegramClient.Result.Err)
        assertTrue(
            "причина: ${(r as TelegramClient.Result.Err).reason}",
            r.reason.contains("нет сообщений"),
        )
    }

    @Test
    fun `resolveChatId blank token fails without network`() = runBlocking {
        val r = TelegramClient.resolveChatId("", listOf(direct))

        assertTrue(r is TelegramClient.Result.Err)
        assertEquals(0, server.requestCount)
    }

    // ===== getBotUsername =====

    @Test
    fun `getBotUsername returns username`() = runBlocking {
        enqueue(200, """{"ok":true,"result":{"username":"my_bot"}}""")

        assertEquals("my_bot", TelegramClient.getBotUsername(token, listOf(direct)))
        assertEquals("/bot$token/getMe", server.takeRequest().path)
    }

    @Test
    fun `getBotUsername null on error`() = runBlocking {
        enqueue(401, """{"ok":false,"description":"Unauthorized"}""")

        assertEquals(null, TelegramClient.getBotUsername(token, listOf(direct)))
    }

    @Test
    fun `getBotUsername null without token`() = runBlocking {
        assertEquals(null, TelegramClient.getBotUsername("", listOf(direct)))
        assertEquals(0, server.requestCount)
    }
}
