package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.network.AppProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class AppProxyTest {
    @Test
    fun `empty proxy preserves system networking`() {
        assertNull(AppProxy.parse("  "))
    }

    @Test
    fun `accepts HTTP SOCKS and IPv6 proxies`() {
        val http = requireNotNull(AppProxy.parse("http://localhost:8080"))
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(8080, (http.address() as InetSocketAddress).port)
        assertEquals(Proxy.Type.SOCKS, AppProxy.parse("socks://127.0.0.1:1080")?.type())
        assertEquals(Proxy.Type.HTTP, AppProxy.parse("http://[::1]:3128")?.type())
    }

    @Test
    fun `rejects credentials missing ports and unsupported schemes`() {
        listOf("https://proxy:8080", "http://proxy", "http://proxy:0", "http://proxy:65536",
            "http://user:password@proxy:8080", "http://proxy:8080/path", "http://proxy:8080?q=1",
            "http://proxy:8080#fragment").forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { AppProxy.parse(input) }
        }
    }
}
