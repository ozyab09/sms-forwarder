package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.util.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Конфигурация прокси для OkHttp (Bot API).
 *
 * Поддерживает HTTP и SOCKS5.
 */
object ProxyConfig {

    data class ProxySettings(
        val type: String,   // "http" | "socks5"
        val host: String,
        val port: Int,
        val user: String,
        val pass: String,
    )

    fun current(): ProxySettings = ProxySettings(
        type = Prefs.proxyType,
        host = Prefs.proxyHost,
        port = Prefs.proxyPort,
        user = Prefs.proxyUser,
        pass = Prefs.proxyPass,
    )

    /** OkHttp-прокси для HTTP/SOCKS5. */
    fun okHttpProxy(s: ProxySettings): Pair<Proxy?, String?> {
        return when (s.type) {
            "http" -> Proxy(Proxy.Type.HTTP, InetSocketAddress(s.host, s.port)) to null
            "socks5" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(s.host, s.port)) to null
            else -> null to "Неизвестный тип прокси: ${s.type}"
        }
    }

    fun httpClient(): Pair<OkHttpClient, String?> {
        val s = current()
        val (proxy, err) = okHttpProxy(s)
        if (err != null) return OkHttpClient.Builder().build() to err

        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (proxy != null) b.proxy(proxy)

        // Базовая авторизация для HTTP-прокси (если заданы логин/пароль)
        if (proxy != null && s.user.isNotBlank()) {
            val creds = okhttp3.Credentials.basic(s.user, s.pass)
            b.proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", creds)
                    .build()
            }
        }
        return b.build() to null
    }
}