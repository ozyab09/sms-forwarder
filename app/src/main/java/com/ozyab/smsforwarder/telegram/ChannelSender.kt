package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
     * Общий бюджет времени на весь каскад каналов. Каждый канал ограничен
     * callTimeout (30с) внутри ChannelClientFactory; этот лимит — верхняя граница
     * суммы попыток (защита от N×30с при большом числе прокси).
     */
    private const val CASCADE_TIMEOUT_MS = 120_000L

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
        var sent: Result.Ok? = null
        try {
            // withTimeout — crossinline, поэтому результат возвращаем через var + break
            withTimeout(CASCADE_TIMEOUT_MS) {
                for (ch in channels) {
                    when (val out = sender(ch)) {
                        is ChannelOutcome.Sent -> {
                            LogStore.info("Отправка через «${ch.name}» — успех")
                            sent = Result.Ok(ch.name, out.messageId)
                            break
                        }
                        is ChannelOutcome.Failed -> {
                            failures += "«${ch.name}»: ${out.reason}"
                            LogStore.warn("Ошибка через «${ch.name}»: ${out.reason}")
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            failures += "Общий таймаут каскада (${CASCADE_TIMEOUT_MS / 1000}с)"
            LogStore.warn("Каскад прерван по общему таймауту")
        }
        sent?.let { return@withContext it }
        Result.Err(failures)
    }

    /** Результат теста одного канала. */
    data class ChannelTestResult(
        val channel: Channel,
        val ok: Boolean,
        val messageId: Long? = null,
        val error: String? = null,
    )

    /**
     * Параллельная проверка ВСЕХ каналов (для кнопки «Проверить связь»).
     *
     * Тестовое сообщение отправляется через каждый канал одновременно;
     * результат собирается по каждому отдельно (не останавливаемся на первом
     * успешном). Каждый канал ограничен callTimeout (30с) внутри
     * [ChannelClientFactory], поэтому общее время ≈ худшему каналу, а не сумме.
     *
     * @return результаты в том же порядке, что и [channels].
     */
    /** Потолок одновременных проверок: каждый канал поднимает свой OkHttp-клиент
     *  с thread-pool, поэтому при десятках каналов ограничиваем параллельность. */
    private const val MAX_PARALLEL_TESTS = 4

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testAll(
        text: String,
        token: String,
        chatId: String,
        channels: List<Channel>,
        sender: suspend (Channel) -> ChannelOutcome = { realSender(text, token, chatId, it) },
    ): List<ChannelTestResult> = withContext(Dispatchers.IO) {
        val limiter = Dispatchers.IO.limitedParallelism(MAX_PARALLEL_TESTS)
        coroutineScope {
            channels.map { ch ->
                async(limiter) {
                    when (val r = sender(ch)) {
                        is ChannelOutcome.Sent -> ChannelTestResult(ch, true, messageId = r.messageId)
                        is ChannelOutcome.Failed -> ChannelTestResult(ch, false, error = r.reason)
                    }
                }
            }.awaitAll()
        }
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