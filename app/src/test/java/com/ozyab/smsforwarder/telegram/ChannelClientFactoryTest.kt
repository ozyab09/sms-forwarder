package com.ozyab.smsforwarder.telegram

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты кэширования OkHttp-клиентов в [ChannelClientFactory].
 *
 * Клиент на канал создаётся один раз и переиспользуется (connection pool +
 * keep-alive); при изменении каналов кэш сбрасывается [ChannelClientFactory.invalidate].
 */
class ChannelClientFactoryTest {

    private fun proxyChannel(
        id: String,
        host: String = "127.0.0.1",
        port: Int = 8080,
        user: String = "",
        pass: String = "",
        type: String = Channel.TYPE_HTTP,
    ) = Channel(
        id = id, type = type, name = "$host:$port",
        host = host, port = port, user = user, pass = pass, enabled = true,
    )

    @Test
    fun `same channel returns cached client`() {
        val ch = proxyChannel("p1")
        val (a, e1) = ChannelClientFactory.build(ch)
        val (b, e2) = ChannelClientFactory.build(ch)
        assertNull(e1)
        assertNull(e2)
        assertTrue("клиент для одного канала должен кэшироваться", a === b)
    }

    @Test
    fun `invalidate clears cache`() {
        val ch = proxyChannel("p1")
        val (a, _) = ChannelClientFactory.build(ch)
        ChannelClientFactory.invalidate()
        val (b, _) = ChannelClientFactory.build(ch)
        assertTrue("после invalidate клиент пересоздаётся", a !== b)
    }

    @Test
    fun `different channels have different clients`() {
        val (a, _) = ChannelClientFactory.build(proxyChannel("p1", host = "1.2.3.4"))
        val (b, _) = ChannelClientFactory.build(proxyChannel("p2", host = "5.6.7.8"))
        assertTrue("разные прокси — разные клиенты", a !== b)
    }

    @Test
    fun `direct channel is cached too`() {
        val d = Channel.direct()
        val (a, e1) = ChannelClientFactory.build(d)
        val (b, e2) = ChannelClientFactory.build(d)
        assertNull(e1)
        assertNull(e2)
        assertTrue("direct-клиент тоже кэшируется", a === b)
    }

    @Test
    fun `socks5 with credentials is a configuration error`() {
        val ch = proxyChannel("s1", user = "u", pass = "p", type = Channel.TYPE_SOCKS5)
        val (_, err) = ChannelClientFactory.build(ch)
        assertNotNull("SOCKS5 с логином — ошибка конфигурации, а не тихий игнор", err)
        assertTrue(err!!.contains("SOCKS5"))
    }

    @Test
    fun `blank host is a configuration error`() {
        val ch = proxyChannel("bad", host = "", port = 0)
        val (_, err) = ChannelClientFactory.build(ch)
        assertNotNull(err)
    }
}