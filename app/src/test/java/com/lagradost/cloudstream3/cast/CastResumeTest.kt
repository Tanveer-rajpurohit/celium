package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class CastResumeTest {
    private fun episode(n: Int) = ResultEpisode(
        "Show", "Episode $n", null, n, 0, 1, "data-$n", "Api", 100 + n, n, 60_000, 3_600_000,
        Score.from10(8), "desc", false, TvType.TvSeries, 7, VideoWatchState.None,
    )

    @Test fun `saved cast session survives a round trip`() {
        val saved = CastResume(
            "uuid:1", "Kodi (Guru)", "http://192.168.1.7:1599/AVTransport/control.xml", null,
            "Api", "Show", "http://poster", listOf(episode(1), episode(2)), 1,
            "Src", "Src 1080p", "https://host/video.mp4", "https://ref/", 1080, ExtractorLinkType.VIDEO,
            mapOf("User-Agent" to "x"), "https://host/sub.vtt", "192.168.1.5", 41234,
            listOf(CastRelay.Saved("abc", "https://host/video.mp4", mapOf("referer" to "r"), "video/mp4", false, false, null)),
            720, "en",
        )
        val back = tryParseJson<CastResume>(saved.toJson())
        assertEquals(saved, back)
    }

    @Test fun `restored relay serves the same URL on the same port`() {
        val upstream = ServerSocket(0)
        thread(isDaemon = true) {
            upstream.accept().use { s ->
                s.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList() }
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc".toByteArray())
                    flush()
                }
            }
        }
        val client = OkHttpClient()
        val first = CastRelay(client)
        first.start("127.0.0.1")
        val url = first.register("http://127.0.0.1:${upstream.localPort}/movie.mp4", emptyMap(), "video/mp4", false, "video")
        val port = first.port!!
        val saved = first.saved()
        first.stop() // Celium was closed

        val second = CastRelay(client)
        second.restore(saved)
        second.start("127.0.0.1", port)
        try {
            assertEquals(port, second.port)
            // The TV's old URL works again.
            val conn = URL(url).openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals("abc", conn.inputStream.bufferedReader().readText())
        } finally {
            second.stop(); upstream.close()
        }
    }
}
