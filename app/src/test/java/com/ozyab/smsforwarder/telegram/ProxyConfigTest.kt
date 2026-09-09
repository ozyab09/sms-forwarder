package com.ozyab.smsforwarder.telegram

import com.ozyab.smsforwarder.telegram.ProxyConfig.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Тесты конфигурации прокси.
 *
 * Покрывают регрессию коммита 5075c74: при выключенном прокси
 * (proxyEnabled == false -> current() == null) OkHttp-клиент должен
 * ходить НАПРЯМУЮ, без прокси.
 */
class ProxyConfigTest {

    @Test
    fun `null settings means no proxy`() {
        val (proxy, err) = ProxyConfig.okHttpProxy(null)
        assertNull("при отключённом прокси proxy должен быть null", proxy)
        assertNull("при отключённом прокси ошибки быть не должно", err)
    }

    @Test
    fun `http proxy returns HTTP proxy with host and port`() {
        val s = ProxySettings("http", "proxy.example.com", 8080, "", "")
        val (proxy, err) = ProxyConfig.okHttpProxy(s)
        assertNull("ошибки быть не должно", err)
        assertEquals(Proxy.Type.HTTP, proxy?.type())
        val addr = proxy?.address() as InetSocketAddress
        assertEquals("proxy.example.com", addr.hostName)
        assertEquals(8080, addr.port)
    }

    @Test
    fun `socks5 proxy returns SOCKS proxy`() {
        val s = ProxySettings("socks5", "127.0.0.1", 1080, "", "")
        val (proxy, err) = ProxyConfig.okHttpProxy(s)
        assertNull("ошибки быть не должно", err)
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        val addr = proxy?.address() as InetSocketAddress
        assertEquals("127.0.0.1", addr.hostName)
        assertEquals(1080, addr.port)
    }

    @Test
    fun `unknown proxy type returns error`() {
        val s = ProxySettings("ftp", "proxy.example.com", 21, "", "")
        val (proxy, err) = ProxyConfig.okHttpProxy(s)
        assertNull("unknown type не должен давать proxy", proxy)
        assertTrue("должна быть ошибка", err?.contains("Неизвестный тип прокси") == true)
    }
}