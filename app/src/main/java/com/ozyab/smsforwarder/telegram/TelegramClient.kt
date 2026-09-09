package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Отправка/запросы в Telegram через Bot API (HTTPS).
 *
 * Мультиканальная отправка: пробуем каналы по порядку (direct → прокси 1 → ...)
 * через [ChannelSender]. Для getUpdates/getMe — по первому рабочему каналу.
 *
 * Никаких секретов в логах: ошибки возвращаются текстом без токена.
 */
object TelegramClient {

    /** Верхняя граница на каскад каналов для getUpdates/getMe (каждый канал уже ограничен callTimeout). */
    private const val CASCADE_TIMEOUT_MS = 120_000L

    sealed class Result {
        data class Ok(val messageId: Long) : Result()
        data class Err(val reason: String) : Result()
    }

    /** Отправка через Bot API каскадом по каналам. Returns Result. */
    suspend fun sendMessage(text: String): Result = withContext(Dispatchers.IO) {
        val token = Prefs.botToken
        val chatId = Prefs.chatId
        if (token.isBlank()) return@withContext Result.Err("Токен бота не задан")
        if (chatId.isBlank()) return@withContext Result.Err("Chat ID не задан")

        val channels = ChannelStore.enabled()
        when (val r = ChannelSender.send(text, token, chatId, channels)) {
            is ChannelSender.Result.Ok -> Result.Ok(r.messageId)
            is ChannelSender.Result.Err -> Result.Err("Все каналы не вышли: ${r.reasons.joinToString("; ")}")
        }
    }

    /**
     * Определяет chat_id пользователя через getUpdates (каскадом по каналам).
     * Требование: пользователь уже написал боту /start.
     */
    suspend fun resolveChatId(): Result = withContext(Dispatchers.IO) {
        val token = Prefs.botToken
        if (token.isBlank()) return@withContext Result.Err("Токен бота не задан")

        val channels = ChannelStore.enabled()
        val failures = mutableListOf<String>()
        var foundChatId: Long? = null
        try {
            withTimeout(CASCADE_TIMEOUT_MS) {
                for (ch in channels) {
                    val (client, buildErr) = ChannelClientFactory.build(ch)
                    if (buildErr != null) { failures += buildErr; continue }
                    try {
                        val req = Request.Builder()
                            .url("${ChannelClientFactory.API_BASE}/bot$token/getUpdates")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val json = JSONObject(resp.body?.string().orEmpty())
                            if (!json.optBoolean("ok", false)) {
                                failures += "«${ch.name}»: ${json.optString("description", "HTTP ${resp.code}")}"
                                return@use
                            }
                            val arr = json.optJSONArray("result") ?: run {
                                failures += "«${ch.name}»: пустой ответ"
                                return@use
                            }
                            if (arr.length() == 0) {
                                failures += "«${ch.name}»: нет сообщений"
                                return@use
                            }
                            val u = arr.optJSONObject(0) ?: run { failures += "«${ch.name}»: нет данных"; return@use }
                            val msg = u.optJSONObject("message") ?: u.optJSONObject("edited_message") ?: run {
                                failures += "«${ch.name}»: нет сообщения"; return@use
                            }
                            val chat = msg.optJSONObject("chat") ?: run { failures += "«${ch.name}»: нет chat"; return@use }
                            foundChatId = chat.optLong("id")
                        }
                        if (foundChatId != null) break
                    } catch (e: Exception) {
                        failures += "«${ch.name}»: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            failures += "Общий таймаут каскада (${CASCADE_TIMEOUT_MS / 1000}с)"
        }
        foundChatId?.let { return@withContext Result.Ok(it) }
        Result.Err(failures.joinToString("; "))
    }

    /** Возвращает username бота (getMe) через каскад каналов, или null. */
    suspend fun getBotUsername(): String? = withContext(Dispatchers.IO) {
        val token = Prefs.botToken
        if (token.isBlank()) return@withContext null
        val channels = ChannelStore.enabled()
        var found: String? = null
        try {
            withTimeout(CASCADE_TIMEOUT_MS) {
                for (ch in channels) {
                    val (client, buildErr) = ChannelClientFactory.build(ch)
                    if (buildErr != null) continue
                    try {
                        val req = Request.Builder()
                            .url("${ChannelClientFactory.API_BASE}/bot$token/getMe")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val json = JSONObject(resp.body?.string().orEmpty())
                            if (json.optBoolean("ok", false)) {
                                val username = json.optJSONObject("result")?.optString("username", "")
                                if (!username.isNullOrBlank()) found = username
                            }
                        }
                        if (found != null) break
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // общий таймаут — возвращаем null
        }
        found
    }
}