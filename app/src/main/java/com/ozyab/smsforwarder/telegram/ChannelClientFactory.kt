package com.ozyab.smsforwarder.telegram

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Построение OkHttp-клиента для канала отправки.
 *
 * - direct — клиент без прокси (напрямую).
 * - http/socks5 — клиент с прокси (+ basic auth для HTTP при заданных логине/пароле).
 */
object ChannelClientFactory {

    fun build(channel: Channel): Pair<OkHttpClient, String?> {
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
        val b = baseBuilder().proxy(proxy)
        if (channel.user.isNotBlank()) {
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
}