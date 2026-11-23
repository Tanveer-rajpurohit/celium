package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/** What Chromecast needs from the relay and the link choice; DLNA behaviour must stay the same. */
class ChromecastSupportTest {
    private fun link(url: String, quality: Int, type: ExtractorLinkType = ExtractorLinkType.VIDEO): ExtractorLink =
        runBlocking { newExtractorLink("s", "s", url, type) { this.quality = quality } }

    @Test fun `chromecast skips files it cannot play`() {
        val links = listOf(
            link("https://h/a.mkv", 1080),
            link("https://h/b.mp4", 720),
            link("https://h/c.m3u8", 1080, ExtractorLinkType.M3U8),
            link("https://h/d.webm", 480),
            link("https://h/e.avi", 480),
        )
        assertEquals(listOf("https://h/c.m3u8", "https://h/b.mp4", "https://h/d.webm"), CastLinks.rank(links, chromecast = true).map { it.url })
        assertEquals(5, CastLinks.rank(links).size) // DLNA TVs still get everything
    }

    @Test fun `srt subtitles become webvtt`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\nthere\r\n\r\n2\r\n01:02:03,040 --> 01:02:04,000\r\nBye\r\n"
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello\nthere\n\n01:02:03.040 --> 01:02:04.000\nBye\n\n",
            CastLinks.srtToVtt(srt),
        )
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n"
        assertEquals(vtt, CastLinks.srtToVtt(vtt))
    }

    @Test fun `relay answers chromecast's permission checks and serves webvtt`() {
        val upstream = ServerSocket(0)
        thread(isDaemon = true) {
            upstream.accept().use { s ->
                s.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList() }
                val body = "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray()
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body)
                    flush()
                }
            }
        }
        val relay = CastRelay(OkHttpClient())
        relay.start("127.0.0.1")
        try {
            val sub = relay.register("http://127.0.0.1:${upstream.localPort}/sub.srt", emptyMap(), "text/vtt", false, "subtitle", subtitle = true)
            assertTrue(sub.endsWith("/subtitle.vtt"))

            // Browser-style preflight before the real request.
            val preflight = (URL(sub).openConnection() as HttpURLConnection).apply { requestMethod = "OPTIONS" }
            assertEquals(204, preflight.responseCode)
            assertEquals("*", preflight.getHeaderField("Access-Control-Allow-Origin"))
            assertTrue(preflight.getHeaderField("Access-Control-Allow-Headers").contains("Range"))

            val get = URL(sub).openConnection() as HttpURLConnection
            assertEquals(200, get.responseCode)
            assertEquals("*", get.getHeaderField("Access-Control-Allow-Origin"))
            assertTrue(get.getHeaderField("Access-Control-Expose-Headers").contains("Content-Range"))
            assertTrue(get.contentType.startsWith("text/vtt"))
            assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n\n", get.inputStream.bufferedReader().readText())
        } finally {
            relay.stop(); upstream.close()
        }
    }
}
