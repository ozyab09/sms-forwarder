package com.ozyab.smsforwarder.telegram

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Построение OkHttp-клиентов для каналов отправки.
 *
 * - direct — клиент без прокси (напрямую).
 * - http/socks5 — клиент с прокси (+ basic auth для HTTP при заданных логине/пароле).
 *
 * Клиенты КЭШИРУЮТСЯ по конфигурации канала и переиспользуются (OkHttp-клиент
 * рассчитан на долгую жизнь: connection pool + keep-alive). Раньше на каждое
 * сообщение создавался новый клиент со своим thread-pool и закрывался после
 * отправки — при пачке событий это давало постоянный churn потоков и
 * «холодные» TCP-соединения. Кэш инвалидируется при изменении каналов
 * ([ChannelStore] вызывает [invalidate]).
 */
object ChannelClientFactory {

    /** Единая точка входа Bot API (используется и отправкой, и тестами). */
    const val API_BASE = "https://api.telegram.org"

    private val lock = Any()
    private var cache: Map<String, OkHttpClient> = emptyMap()

    /** Клиент для случаев с ошибкой конфигурации — общий, в кэш не попадает. */
    private val fallbackClient: OkHttpClient by lazy { baseBuilder().build() }

    /**
     * Возвращает клиент для канала (кэшированный). Вторым элементом — ошибка
     * конфигурации канала (тогда клиент использовать не нужно).
     */
    fun build(channel: Channel): Pair<OkHttpClient, String?> {
        val key = fingerprint(channel)
        synchronized(lock) { cache[key]?.let { return it to null } }
        val (client, err) = create(channel)
        if (err != null) return fallbackClient to err
        synchronized(lock) { cache = cache + (key to client) }
        return client to null
    }

    /** Сброс кэша — вызывается при любом изменении каналов (ChannelStore). */
    fun invalidate() {
        synchronized(lock) { cache = emptyMap() }
    }

    private fun fingerprint(channel: Channel): String = when {
        channel.isDirect -> "direct"
        else -> "${channel.type}|${channel.host}|${channel.port}|${channel.user}|${channel.pass}"
    }

    private fun create(channel: Channel): Pair<OkHttpClient, String?> {
        if (channel.isDirect) {
            return baseBuilder().build() to null
        }
        if (channel.host.isBlank() || channel.port <= 0) {
            return baseBuilder().build() to "Канал «${channel.name}»: не задан хост/порт"
        }
        val proxy = when (channel.type) {
            Channel.TYPE_HTTP -> Proxy(Proxy.Type.HTTP, InetSocketAddress(channel.host, channel.port))
            Channel.TYPE_SOCKS5 -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(channel.host, channel.port))
            else -> return baseBuilder().build() to "Канал «${channel.name}»: неизвестный тип ${channel.type}"
        }
        // OkHttp поддерживает basic-auth только для HTTP-прокси.
        // Для SOCKS5 логин/пароль нельзя передать — честная ошибка вместо тихого игнора.
        if (channel.type == Channel.TYPE_SOCKS5 && channel.user.isNotBlank()) {
            return baseBuilder().build() to "Канал «${channel.name}»: SOCKS5 не поддерживает логин/пароль — уберите их"
        }
        val b = baseBuilder().proxy(proxy)
        if (channel.type == Channel.TYPE_HTTP && channel.user.isNotBlank()) {
            val creds = okhttp3.Credentials.basic(channel.user, channel.pass)
            b.proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", creds)
                    .build()
            }
        }
        return b.build() to null
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // Жёсткий лимит на весь запрос (connect+write+read) — защита от зависаний
        // между фазами, которые не покрываются отдельными таймаутами.
        .callTimeout(30, TimeUnit.SECONDS)
}