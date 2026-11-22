package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.player.LangMatch
import com.lagradost.cloudstream3.ui.player.StreamChoice
import com.lagradost.cloudstream3.ui.player.StreamOption
import org.junit.Assert.*
import org.junit.Test

class StreamChoiceTest {
    private fun o(key: String, quality: Int, name: String = key, source: String = "S") = StreamOption(key, source, name, quality)

    @Test fun `languages are read from link names`() {
        assertEquals(setOf("hi", "en"), StreamChoice.languages("Moviesmod [Instant] Dual Audio [Hindi-English] 1080p"))
        assertEquals(setOf("hi", "en"), StreamChoice.languages("HubCloud Hin-Eng x264"))
        assertEquals(setOf("ta"), StreamChoice.languages("Tamil 720p"))
        assertEquals(setOf(StreamChoice.MULTI), StreamChoice.languages("Multi Audio 1080p"))
        assertNull(StreamChoice.languages("HubCloud 1080p x264 ESub"))
        // "English Subs" describes subtitles, not audio
        assertEquals(setOf("hi"), StreamChoice.languages("Hindi 720p English Subs"))
        assertNull(StreamChoice.languages("VidCloud"))
    }

    @Test fun `player language codes are normalised`() {
        assertEquals("en", StreamChoice.normalize("eng"))
        assertEquals("en", StreamChoice.normalize("en-US"))
        assertEquals("hi", StreamChoice.normalize("hin"))
        assertNull(StreamChoice.normalize("und"))
        assertNull(StreamChoice.normalize(null))
    }

    @Test fun `quality buckets and menu rows`() {
        assertEquals(2160, StreamChoice.bucket(2160))
        assertEquals(1080, StreamChoice.bucket(1080))
        assertEquals(720, StreamChoice.bucket(720))
        assertNull(StreamChoice.bucket(400)) // Qualities.Unknown
        assertEquals("4K", StreamChoice.label(2160))
        val rows = StreamChoice.available(listOf(o("a", 1080), o("b", 720), o("c", 720), o("d", 400)), failed = setOf("a"))
        assertEquals(listOf(1080, 720), rows.map { it.bucket })
        assertFalse(rows[0].working)
        assertTrue(rows[1].working)
        assertEquals(2, rows[1].count)
    }

    @Test fun `auto cap follows connection speed`() {
        assertEquals(1080, StreamChoice.capFor(null))
        assertEquals(2160, StreamChoice.capFor(40_000_000))
        assertEquals(1080, StreamChoice.capFor(8_000_000))
        assertEquals(720, StreamChoice.capFor(4_000_000))
        assertEquals(480, StreamChoice.capFor(2_000_000))
        assertEquals(360, StreamChoice.capFor(500_000))
    }

    @Test fun `quality candidates keep language and prefer the same source`() {
        val links = listOf(
            o("other-en", 720, "B English 720p", source = "B"),
            o("same-en", 720, "A English 720p", source = "A"),
            o("unknown", 720, "C 720p", source = "C"),
            o("multi", 720, "D Multi Audio 720p", source = "D"),
            o("hindi", 720, "E Hindi 720p", source = "E"),
            o("en-1080", 1080, "A English 1080p", source = "A"),
        )
        val order = StreamChoice.candidates(links, 720, "en", currentSource = "A").map { it.key }
        assertEquals(listOf("same-en", "other-en", "multi", "unknown"), order) // Hindi is never auto-picked
        assertEquals(LangMatch.Other, StreamChoice.match(setOf("hi"), "en"))
    }

    @Test fun `fallback offers other languages and the nearest quality`() {
        val links = listOf(
            o("hi720", 720, "Hindi 720p"),
            o("ta720", 720, "Tamil 720p"),
            o("en480", 480, "English 480p"),
            o("en1080", 1080, "English 1080p"),
        )
        val fallback = StreamChoice.fallback(links, 720, "en")
        assertEquals(listOf("hi", "ta"), fallback.otherLanguages)
        assertEquals(480, fallback.nearestQuality) // lower first
        assertEquals(1080, StreamChoice.fallback(links.filter { it.key != "en480" }, 720, "en").nearestQuality)
    }

    @Test fun `auto order picks highest within cap in the wanted language`() {
        val links = listOf(
            o("en4k", 2160, "English 4K"),
            o("en1080", 1080, "English 1080p"),
            o("unk1080", 1080, "1080p"),
            o("en720", 720, "English 720p"),
            o("hi1080", 1080, "Hindi 1080p"),
            o("unkq", 400, "English"),
        )
        val order = StreamChoice.autoOrder(links, cap = 1080, language = "en").map { it.key }
        assertEquals(listOf("en1080", "en720", "unk1080", "en4k", "unkq", "hi1080"), order)
        // No preference: purely by quality within the cap
        assertEquals("en1080", StreamChoice.autoOrder(links, 1080, null).first().key)
    }
}
