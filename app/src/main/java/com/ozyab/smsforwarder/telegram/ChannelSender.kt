package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Каскадная отправка сообщения в Telegram через каналы.
 *
 * Пробует каналы ПО ПОРЯДКУ (direct первый, затем прокси в порядке списка).
 * Первый успешный канал — победа. Все не вышли — агрегированная ошибка.
 *
 * Для тестов функция отправки [sender] инжектится (см. [realSender]).
 */
object ChannelSender {

    sealed class Result {
        data class Ok(val channelName: String, val messageId: Long) : Result()
        data class Err(val reasons: List<String>) : Result()
    }

    sealed class ChannelOutcome {
        data class Sent(val messageId: Long) : ChannelOutcome()
        data class Failed(val reason: String) : ChannelOutcome()
    }

    private const val API_BASE = "https://api.telegram.org"

    /**
     * Отправка текста через каналы каскадом.
     *
     * @param channels каналы в порядке приоритета (уже отфильтрованы enabled).
     * @param sender функция отправки через один канал (по умолчанию — Bot API).
     */
    suspend fun send(
        text: String,
        token: String,
        chatId: String,
        channels: List<Channel>,
        sender: suspend (Channel) -> ChannelOutcome = { realSender(text, token, chatId, it) },
    ): Result = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext Result.Err(listOf("Нет включённых каналов"))
        val failures = mutableListOf<String>()
        for (ch in channels) {
            when (val out = sender(ch)) {
                is ChannelOutcome.Sent -> {
                    LogStore.info("Отправка через «${ch.name}» — успех")
                    return@withContext Result.Ok(ch.name, out.messageId)
                }
                is ChannelOutcome.Failed -> {
                    failures += "«${ch.name}»: ${out.reason}"
                    LogStore.warn("Ошибка через «${ch.name}»: ${out.reason}")
                }
            }
        }
        Result.Err(failures)
    }

    /** Реальная отправка через Bot API по одному каналу. */
    private suspend fun realSender(
        text: String,
        token: String,
        chatId: String,
        channel: Channel,
    ): ChannelOutcome {
        val (client, buildErr) = ChannelClientFactory.build(channel)
        if (buildErr != null) return ChannelOutcome.Failed(buildErr)
        return try {
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
                    val mid = json.getJSONObject("result").optLong("message_id")
                    ChannelOutcome.Sent(mid)
                } else {
                    val desc = json.optString("description", "HTTP ${resp.code}")
                    ChannelOutcome.Failed(desc)
                }
            }
        } catch (e: Exception) {
            ChannelOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}