package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private const val API_BASE = "https://api.telegram.org"

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
        for (ch in channels) {
            val (client, buildErr) = ChannelClientFactory.build(ch)
            if (buildErr != null) { failures += buildErr; continue }
            try {
                val req = Request.Builder()
                    .url("$API_BASE/bot$token/getUpdates")
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
                    return@withContext Result.Ok(chat.optLong("id"))
                }
            } catch (e: Exception) {
                failures += "«${ch.name}»: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                client.dispatcher.executorService.shutdown()
            }
        }
        Result.Err(failures.joinToString("; "))
    }

    /** Возвращает username бота (getMe) через каскад каналов, или null. */
    suspend fun getBotUsername(): String? = withContext(Dispatchers.IO) {
        val token = Prefs.botToken
        if (token.isBlank()) return@withContext null
        val channels = ChannelStore.enabled()
        for (ch in channels) {
            val (client, buildErr) = ChannelClientFactory.build(ch)
            if (buildErr != null) continue
            try {
                val req = Request.Builder()
                    .url("$API_BASE/bot$token/getMe")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string().orEmpty())
                    if (json.optBoolean("ok", false)) {
                        val username = json.optJSONObject("result")?.optString("username", "")
                        username?.takeIf { it.isNotBlank() }?.let { return@withContext it }
                    }
                }
            } catch (_: Exception) {
            } finally {
                client.dispatcher.executorService.shutdown()
            }
        }
        null
    }
}