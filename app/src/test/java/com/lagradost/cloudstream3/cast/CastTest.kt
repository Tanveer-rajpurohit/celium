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

class CastTest {
    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room &amp; TV</friendlyName>
            <UDN>uuid:1234</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/RenderingControl/control</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>AVTransport/control</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test fun `ssdp location is read from a search reply only`() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.9:1234/desc.xml\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.9:1234/desc.xml", DlnaProtocol.ssdpLocation(reply))
        assertNull(DlnaProtocol.ssdpLocation("M-SEARCH * HTTP/1.1\r\nLOCATION: http://x/\r\n\r\n"))
    }

    @Test fun `device description gives name and resolved control urls`() {
        val device = DlnaProtocol.parseDescription(description, "http://192.168.1.9:1234/dev/desc.xml")!!
        assertEquals("Living Room & TV", device.name)
        assertEquals("uuid:1234", device.id)
        assertEquals("http://192.168.1.9:1234/dev/AVTransport/control", device.avTransportUrl)
        assertEquals("http://192.168.1.9:1234/RenderingControl/control", device.renderingControlUrl)
    }

    @Test fun `devices without AVTransport are ignored`() {
        assertNull(DlnaProtocol.parseDescription(description.replace("AVTransport", "Other"), "http://h/d.xml"))
    }

    @Test fun `soap and didl escape titles and urls`() {
        val didl = DlnaProtocol.didl("Tom & Jerry <\"1\">", "http://p/s/a/video.mp4?x=1&y=2", "video/mp4")
        assertTrue(didl.contains("Tom &amp; Jerry &lt;&quot;1&quot;&gt;"))
        assertTrue(didl.contains("x=1&amp;y=2"))
        val soap = DlnaProtocol.soapEnvelope(DlnaProtocol.AV_TRANSPORT, "SetAVTransportURI", listOf("CurrentURIMetaData" to didl))
        // Metadata is escaped once more as an argument value, never raw XML inside the envelope.
        assertTrue(soap.contains("<CurrentURIMetaData>&lt;DIDL-Lite"))
        assertFalse(soap.contains("<CurrentURIMetaData><DIDL"))
    }

    @Test fun `upnp time round trips`() {
        assertEquals(3_723_000L, DlnaProtocol.parseTime("1:02:03"))
        assertEquals(3_723_500L, DlnaProtocol.parseTime("01:02:03.500"))
        assertNull(DlnaProtocol.parseTime("NOT_IMPLEMENTED"))
        assertEquals("01:02:03", DlnaProtocol.formatTime(3_723_999))
    }

    @Test fun `hls rewriting proxies segments keys and nested playlists`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8?t=1\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\n"
        val calls = mutableListOf<Pair<String, Boolean>>()
        val out = HlsRewriter.rewrite(master, "https://cdn.x/hls/master.m3u8") { u, p -> calls += u to p; "R${calls.size}" }
        assertEquals(listOf("https://cdn.x/hls/low/index.m3u8?t=1" to true, "https://cdn.x/hls/audio.m3u8" to true), calls)
        assertTrue(out.contains("\nR1\n") && out.contains("URI=\"R2\""))

        val media = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/k/key.bin\"\n#EXTINF:4,\nseg1.ts\nhttps://other/seg2.ts\n#EXT-X-ENDLIST"
        calls.clear()
        HlsRewriter.rewrite(media, "https://cdn.x/hls/low/index.m3u8") { u, p -> calls += u to p; "R" }
        assertEquals(listOf(
            "https://cdn.x/k/key.bin" to false,
            "https://cdn.x/hls/low/seg1.ts" to false,
            "https://other/seg2.ts" to false,
        ), calls)
    }

    private fun link(url: String, quality: Int, type: ExtractorLinkType): ExtractorLink =
        runBlocking { newExtractorLink("s", "s", url, type) { this.quality = quality } }

    @Test fun `ranking keeps castable links best quality first`() {
        val ranked = CastLinks.rank(listOf(
            link("https://a/720.mp4", 720, ExtractorLinkType.VIDEO),
            link("https://a/dash.mpd", 2160, ExtractorLinkType.DASH),
            link("https://a/1080.m3u8", 1080, ExtractorLinkType.M3U8),
            link("https://a/1080.mp4", 1080, ExtractorLinkType.VIDEO),
            link("https://a/720.mp4", 720, ExtractorLinkType.VIDEO),
        ))
        assertEquals(listOf("https://a/1080.mp4", "https://a/1080.m3u8", "https://a/720.mp4"), ranked.map { it.url })
        assertEquals("video/x-matroska", CastLinks.mime(ExtractorLinkType.VIDEO, "https://a/b.MKV?x=1"))
        assertEquals("application/vnd.apple.mpegurl", CastLinks.mime(ExtractorLinkType.M3U8, "https://a/b"))
    }

    @Test fun `only a natural end triggers the next episode`() {
        assertTrue(CastLinks.endedNaturally(2_580_000, 2_600_000, userStopped = false))
        assertFalse(CastLinks.endedNaturally(2_580_000, 2_600_000, userStopped = true))
        assertFalse(CastLinks.endedNaturally(600_000, 2_600_000, userStopped = false))
        assertFalse(CastLinks.endedNaturally(0, 0, userStopped = false))
    }

    @Test fun `link problems are explained in plain language`() {
        assertNull(CastLinks.problem(206, "video/mp4", "\u0000\u0000ftyp", playlist = false))
        assertNull(CastLinks.problem(200, "application/x-mpegurl", "#EXTM3U\n", playlist = true))
        assertEquals("The video host blocked this link", CastLinks.problem(403, null, null, playlist = false))
        assertEquals("This link has expired or the video was removed", CastLinks.problem(404, null, null, playlist = false))
        assertEquals("The video host sent a web page instead of the video", CastLinks.problem(200, "text/html", "<html>", playlist = false))
        assertEquals("The video host sent a web page instead of the video", CastLinks.problem(200, "text/plain", "<!DOCTYPE html>", playlist = true))
        assertEquals("The video host didn't respond", CastLinks.problem(null, null, null, playlist = false, timedOut = true))
    }

    @Test fun `webvtt becomes srt for tvs`() {
        val vtt = "WEBVTT\n\nNOTE skip me\n\n1\n00:00:01.500 --> 00:00:03.000 align:start\n<c.yellow>Hello</c> <i>there</i>\n\n01:02:03.004 --> 01:02:04.000\nLine\n"
        assertEquals("1\n00:00:01,500 --> 00:00:03,000\nHello <i>there</i>\n\n2\n01:02:03,004 --> 01:02:04,000\nLine\n\n", CastLinks.vttToSrt(vtt))
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        assertSame(srt, CastLinks.vttToSrt(srt))
    }

    @Test fun `didl carries subtitles for kodi, samsung and lg style tvs`() {
        val didl = DlnaProtocol.didl("T", "http://p/v.mp4", "video/mp4", "http://p/s.srt")
        assertTrue(didl.contains("<res protocolInfo=\"http-get:*:text/srt:*\">http://p/s.srt</res>")) // Kodi
        assertTrue(didl.contains("sec:CaptionInfoEx")) // Samsung
        // LG / Sony: PacketVideo attributes on the video resource, with the namespace declared
        assertTrue(didl.contains("xmlns:pv=\"http://www.pv.com/pvns/\""))
        assertTrue(didl.contains("pv:subtitleFileUri=\"http://p/s.srt\" pv:subtitleFileType=\"srt\">http://p/v.mp4</res>"))
        // Without subtitles nothing extra is sent
        assertFalse(DlnaProtocol.didl("T", "http://p/v.mp4", "video/mp4").contains("pv:subtitleFileUri"))
    }

    @Test fun `relay probe reports blocked links and converts subtitles`() {
        val upstream = ServerSocket(0)
        thread(isDaemon = true) {
            repeat(3) {
                upstream.accept().use { s ->
                    val first = s.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList() }.first()
                    val out = s.getOutputStream()
                    when {
                        first.contains("/blocked") -> out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        first.contains("/ok") -> out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Length: 4\r\nConnection: close\r\n\r\nftyp".toByteArray())
                        else -> {
                            val body = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n".toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/vtt\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                    }
                    out.flush()
                }
            }
        }
        val relay = CastRelay(OkHttpClient())
        relay.start("127.0.0.1")
        try {
            val base = "http://127.0.0.1:${upstream.localPort}"
            assertEquals("The video host blocked this link", relay.probe("$base/blocked.mp4", emptyMap(), false))
            assertNull(relay.probe("$base/ok.mp4", emptyMap(), false))
            val sub = relay.register("$base/sub.vtt", emptyMap(), "text/srt", false, "subtitle", subtitle = true)
            assertTrue(sub.endsWith("/subtitle.srt"))
            assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n", URL(sub).readText())
        } finally {
            relay.stop(); upstream.close()
        }
    }

    @Test fun `relay forwards referer and range and rewrites playlists`() {
        val upstream = ServerSocket(0)
        val seen = mutableListOf<String>()
        thread(isDaemon = true) {
            repeat(2) {
                upstream.accept().use { s ->
                    val head = s.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList() }
                    synchronized(seen) { seen += head }
                    val out = s.getOutputStream()
                    if (head.first().contains(".m3u8")) {
                        val body = "#EXTM3U\n#EXTINF:4,\nseg.ts\n".toByteArray()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                    } else {
                        out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\nContent-Range: bytes 2-4/10\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc".toByteArray())
                    }
                    out.flush()
                }
            }
        }
        val relay = CastRelay(OkHttpClient())
        val errors = mutableListOf<Throwable>()
        relay.onError = { synchronized(errors) { errors += it } }
        relay.start("127.0.0.1")
        try {
            val base = "http://127.0.0.1:${upstream.localPort}"
            val video = relay.register("$base/movie.mp4", mapOf("referer" to "https://site/"), "video/mp4", false)
            val conn = URL(video).openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=2-4")
            val code = runCatching { conn.responseCode }.getOrElse { -1 }
            assertEquals("relay errors: ${synchronized(errors) { errors.map { it.stackTraceToString().take(800) } }}", 206, code)
            assertEquals("video/mp4", conn.getHeaderField("Content-Type"))
            assertEquals("bytes 2-4/10", conn.getHeaderField("Content-Range"))
            assertEquals("abc", conn.inputStream.readBytes().decodeToString())

            val list = relay.register("$base/hls/index.m3u8", mapOf("referer" to "https://site/"), "application/vnd.apple.mpegurl", true)
            val text = URL(list).readText()
            assertTrue(text.startsWith("#EXTM3U"))
            val segment = text.lines().first { it.startsWith("http") }
            assertTrue(segment.startsWith("http://127.0.0.1:") && segment.contains("/s/"))

            synchronized(seen) {
                assertTrue(seen.any { it.equals("referer: https://site/", ignoreCase = true) })
                assertTrue(seen.any { it.equals("range: bytes=2-4", ignoreCase = true) })
            }
            val missing = URL("http://127.0.0.1:${URL(video).port}/s/nope/x.mp4").openConnection() as HttpURLConnection
            assertEquals(404, missing.responseCode)
        } finally {
            relay.stop(); upstream.close()
        }
    }

    @Test fun `hls first segment and encryption are found`() {
        val media = listOf("#EXTM3U", "#EXT-X-KEY:METHOD=NONE", "#EXTINF:4,", "seg/0.ts", "#EXTINF:4,", "seg/1.ts").joinToString("\n")
        assertEquals("https://cdn.example/v/seg/0.ts", HlsRewriter.firstSegment(media, "https://cdn.example/v/index.m3u8"))
        assertFalse(HlsRewriter.isEncrypted(media))
        assertTrue(HlsRewriter.isEncrypted(media.replace("METHOD=NONE", "METHOD=AES-128,URI=\"key.bin\"")))
    }

    @Test fun `hls length is worked out from the playlist`() {
        val media = listOf("#EXTM3U", "#EXT-X-TARGETDURATION:10", "#EXTINF:10.0,", "a.ts", "#EXTINF:9.5,", "b.ts", "#EXTINF:4,", "c.ts", "#EXT-X-ENDLIST")
            .joinToString("\n")
        assertEquals(23_500L, HlsRewriter.duration(media))
        assertNull(HlsRewriter.duration(media.replace("#EXT-X-ENDLIST", ""))) // live: no fixed length
        val master = listOf("#EXTM3U", "#EXT-X-STREAM-INF:BANDWIDTH=800000", "720/index.m3u8", "#EXT-X-STREAM-INF:BANDWIDTH=400000", "480/index.m3u8")
            .joinToString("\n")
        assertEquals("https://cdn.example/v/720/index.m3u8", HlsRewriter.firstVariant(master, "https://cdn.example/v/master.m3u8"))
        assertNull(HlsRewriter.firstVariant(media, "https://cdn.example/v/index.m3u8"))
    }
}
