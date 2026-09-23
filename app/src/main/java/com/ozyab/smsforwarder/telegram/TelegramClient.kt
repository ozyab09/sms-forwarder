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
    suspend fun sendMessage(text: String): Result =
        sendMessage(text, Prefs.botToken, Prefs.chatId, ChannelStore.enabled())

    /**
     * Отправка с явными параметрами — тестируемо (токен/chatId/каналы снаружи,
     * без обращения к Prefs). Продовая версия читает их из настроек.
     */
    internal suspend fun sendMessage(
        text: String,
        token: String,
        chatId: String,
        channels: List<Channel>,
    ): Result = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.Err("Токен бота не задан")
        if (chatId.isBlank()) return@withContext Result.Err("Chat ID не задан")

        when (val r = ChannelSender.send(text, token, chatId, channels)) {
            is ChannelSender.Result.Ok -> Result.Ok(r.messageId)
            is ChannelSender.Result.Err -> Result.Err("Все каналы не вышли: ${r.reasons.joinToString("; ")}")
        }
    }

    /**
     * Определяет chat_id пользователя через getUpdates (каскадом по каналам).
     * Требование: пользователь уже написал боту /start.
     */
    suspend fun resolveChatId(): Result =
        resolveChatId(Prefs.botToken, ChannelStore.enabled())

    /** Версия с явными параметрами (тестируемо, см. [sendMessage]). */
    internal suspend fun resolveChatId(token: String, channels: List<Channel>): Result = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.Err("Токен бота не задан")

        val failures = mutableListOf<String>()
        var foundChatId: Long? = null
        try {
            withTimeout(CASCADE_TIMEOUT_MS) {
                for (ch in channels) {
                    val (client, buildErr) = ChannelClientFactory.build(ch)
                    if (buildErr != null) { failures += buildErr; continue }
                    try {
                        val req = Request.Builder()
                            .url("${ChannelClientFactory.apiBase}/bot$token/getUpdates")
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
                            // Берём самое СВЕЖЕЕ личное сообщение боту: result[0] —
                            // старейшее обновление; личные чаты бота могут идти после
                            // групповых, где бот когда-то побывал (см. N3 аудита-2).
                            var candidate: Long? = null
                            for (i in arr.length() - 1 downTo 0) {
                                val u = arr.optJSONObject(i) ?: continue
                                val msg = u.optJSONObject("message") ?: u.optJSONObject("edited_message") ?: continue
                                val chat = msg.optJSONObject("chat") ?: continue
                                // Только private: ID группы/канала не подходит для
                                // персональной пересылки
                                if (chat.optString("type") != "private") continue
                                val id = chat.optLong("id")
                                if (id != 0L) { candidate = id; break }
                            }
                            if (candidate == null) {
                                failures += "«${ch.name}»: личных сообщений боту нет"
                                return@use
                            }
                            foundChatId = candidate
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
    suspend fun getBotUsername(): String? =
        getBotUsername(Prefs.botToken, ChannelStore.enabled())

    /** Версия с явными параметрами (тестируемо, см. [sendMessage]). */
    internal suspend fun getBotUsername(token: String, channels: List<Channel>): String? = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext null
        var found: String? = null
        try {
            withTimeout(CASCADE_TIMEOUT_MS) {
                for (ch in channels) {
                    val (client, buildErr) = ChannelClientFactory.build(ch)
                    if (buildErr != null) continue
                    try {
                        val req = Request.Builder()
                            .url("${ChannelClientFactory.apiBase}/bot$token/getMe")
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