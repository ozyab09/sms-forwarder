package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Отправка сообщений в Telegram.
 *
 * Режимы:
 *  1. Bot API (HTTPS, с прокси HTTP/SOCKS5) — лёгкий, работает сейчас.
 *  2. TDLib / MTProto — этап 4 (заглушка, интерфейс готов).
 *
 * Никаких секретов в логах: ошибки возвращаются текстом без токена.
 */
object TelegramClient {

    private const val API_BASE = "https://api.telegram.org"

    sealed class Result {
        data class Ok(val messageId: Long) : Result()
        data class Err(val reason: String) : Result()
    }

    /** Отправка через Bot API. Returns Result. */
    suspend fun sendMessage(text: String): Result = withContext(Dispatchers.IO) {
        val token = Prefs.botToken
        val chatId = Prefs.chatId
        if (token.isBlank()) return@withContext Result.Err("Токен бота не задан")
        if (chatId.isBlank()) return@withContext Result.Err("Chat ID не задан")

        val (client, proxyErr) = ProxyConfig.httpClient()
        if (proxyErr != null) return@withContext Result.Err(proxyErr)

        try {
            val body = okhttp3.FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("disable_notification", "true")
                .build()
            val req = Request.Builder()
                .url("$API_BASE/bot$token/sendMessage")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (resp.isSuccessful && json.optBoolean("ok", false)) {
                    Result.Ok(json.getJSONObject("result").optLong("message_id"))
                } else {
                    val desc = json.optString("description", "HTTP ${resp.code}")
                    Result.Err("Telegram: $desc")
                }
            }
        } catch (e: Exception) {
            Result.Err(e.message ?: e.javaClass.simpleName)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    /**
     * MTProto-путь через TDLib. Реализуется на этапе 4.
     * Интерфейс уже согласован с [sendMessage].
     */
    suspend fun sendMessageMtproto(text: String): Result {
        // TODO(этап 4): TDLib + addProxy/setProxy (HTTP/SOCKS5/MTProto-proxy)
        return Result.Err("MTProto-режим появится на этапе 4 (TDLib)")
    }

    /**
     * Определяет chat_id пользователя через getUpdates.
     * Требование: пользователь уже написал боту /start.
     * Возвращает chat_id строкой или ошибку.
     */
    suspend fun resolveChatId(): Result = withContext(Dispatchers.IO) {
        val token = Prefs.botToken
        if (token.isBlank()) return@withContext Result.Err("Токен бота не задан")

        val (client, proxyErr) = ProxyConfig.httpClient()
        if (proxyErr != null) return@withContext Result.Err(proxyErr)
        try {
            val req = Request.Builder()
                .url("$API_BASE/bot$token/getUpdates")
                .build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!json.optBoolean("ok", false)) {
                    return@withContext Result.Err("Telegram: ${json.optString("description", "HTTP ${resp.code}")}")
                }
                val arr = json.optJSONArray("result") ?: return@withContext Result.Err("Пустой ответ getUpdates")
                if (arr.length() == 0) {
                    return@withContext Result.Err("Напиши боту /start, затем нажми ещё раз")
                }
                val u = arr.optJSONObject(0) ?: return@withContext Result.Err("Нет данных")
                val msg = u.optJSONObject("message") ?: u.optJSONObject("edited_message")
                    ?: return@withContext Result.Err("Нет сообщения")
                val chat = msg.optJSONObject("chat") ?: return@withContext Result.Err("Нет chat")
                Result.Ok(chat.optLong("id"))
            }
        } catch (e: Exception) {
            Result.Err(e.message ?: e.javaClass.simpleName)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    /** Возвращает username бота (getMe) или null при ошибке. Используется, чтобы открыть чат: https://t.me/<username>. */
    suspend fun getBotUsername(): String? = withContext(Dispatchers.IO) {
        val token = Prefs.botToken
        if (token.isBlank()) return@withContext null
        val (client, proxyErr) = ProxyConfig.httpClient()
        if (proxyErr != null) return@withContext null
        try {
            val req = Request.Builder()
                .url("$API_BASE/bot$token/getMe")
                .build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!json.optBoolean("ok", false)) {
                    null
                } else {
                    val username = json.optJSONObject("result")?.optString("username", "")
                    username?.takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}