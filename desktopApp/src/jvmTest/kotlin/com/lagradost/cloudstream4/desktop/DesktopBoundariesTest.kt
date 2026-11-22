package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.desktop.moviesmod.*
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

class DesktopBoundariesTest {
    @Test fun domainMustBeAnHttpsOrigin() {
        assertEquals("https://movies.example", validateDomain("https://movies.example/"))
        for (bad in listOf("http://movies.example", "https://user:pass@movies.example", "https://movies.example/path", "https://movies.example/?key=secret")) {
            assertThrows(IllegalArgumentException::class.java) { validateDomain(bad) }
        }
    }

    @Test fun unfamiliarHtmlCannotBecomeEmptySearchResults() {
        checkSearchPage(Jsoup.parse("<div class='post-cards'><article>Movie</article></div>"))
        checkSearchPage(Jsoup.parse("<h1>Nothing Found</h1>"))
        assertThrows(IOException::class.java) { checkSearchPage(Jsoup.parse("<h1>Please verify your browser</h1>")) }
    }

    @Test fun httpChallengeProducesAnExplicitCapabilityError() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.responseHeaders.add("cf-mitigated", "challenge")
            val bytes = "<title>Just a moment...</title>".toByteArray()
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(DesktopResponseCheck).build()
        try {
            val error = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()).execute().close()
            }
            assertTrue(error.message.orEmpty().contains("browser verification"))
        } finally { server.stop(0); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Suppress("DEPRECATION")
    private fun source(headers: Map<String, String> = emptyMap()) = ExtractorLink(
        "host", "source", "https://media.example/video.mp4", "https://movies.example/", 1080,
        headers = headers, type = ExtractorLinkType.VIDEO,
    )

    @Test fun playbackKeepsExplicitRefererAndUserAgent() {
        val options = mediaOptions(source(mapOf("Referer" to "https://host.example/", "User-Agent" to "Celium")))
        assertTrue(options.contains(":http-referrer=https://host.example/"))
        assertTrue(options.contains(":http-user-agent=Celium"))
        assertEquals(2, options.size)
    }

    @Test fun unsupportedHeadersAreRejectedInsteadOfSilentlyDropped() {
        assertThrows(IllegalArgumentException::class.java) { mediaOptions(source(mapOf("Cookie" to "secret=value"))) }
        assertThrows(IllegalArgumentException::class.java) { mediaOptions(source(mapOf("User-Agent" to "bad\r\nvalue"))) }
    }

    @Test fun browserCookiesStayWithinTheirHostPathAndExpiry() {
        BrowserLinkResolver.remember("TestBrowser", listOf(
            Cookie.Builder().name("session").value("public-test").hostOnlyDomain("source.example").path("/video").secure().build(),
            Cookie.Builder().name("expired").value("old").hostOnlyDomain("source.example").path("/").expiresAt(1).build(),
        ))
        assertEquals("session=public-test", BrowserLinkResolver.headers("https://source.example/video/1")["Cookie"])
        assertEquals("TestBrowser", BrowserLinkResolver.headers("https://source.example/video/1")["User-Agent"])
        assertTrue(BrowserLinkResolver.headers("https://other.example/video/1").isEmpty())
        assertTrue(BrowserLinkResolver.headers("https://source.example/other").isEmpty())
        assertTrue(BrowserLinkResolver.headers("http://source.example/video/1").isEmpty())
    }

    @Test fun browserDestinationUsesTheActualHttpsHost() {
        assertTrue(supportedBrowserTarget("https://driveseed.example/file"))
        assertTrue(supportedBrowserTarget("https://www.driveleech.example/file"))
        assertFalse(supportedBrowserTarget("https://other.example/?driveleech"))
        assertFalse(supportedBrowserTarget("https://notdriveleech.example/file"))
        assertFalse(supportedBrowserTarget("http://driveseed.example/file"))
        assertFalse(supportedBrowserTarget("about:blank"))
    }

}
