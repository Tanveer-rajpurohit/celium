package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.player.StreamLabels
import com.lagradost.cloudstream3.ui.player.LinkFallback
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.ui.celium.DownloadBook
import com.lagradost.cloudstream3.ui.celium.DlState
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import org.junit.Assert.*
import org.junit.Test

class PlayerChoicesTest {
    @Test fun `source labels remove decoration but retain host language and quality`() {
        assertEquals("Vidrift VOE English 1080p", StreamLabels.source("⚡ Vidrift [VOE] 🇬🇧 English 1080p 🔥"))
        assertEquals("فيلم", StreamLabels.source("🎬 فيلم"))
        assertEquals("Source", StreamLabels.source("🔥"))
        assertNotEquals(StreamLabels.choice("Vidrift", 1080, 0), StreamLabels.choice("Vidrift", 1080, 1))
    }

    @Test fun `unknown quality is explicit and common heights use p labels`() {
        assertEquals("Quality unknown", StreamLabels.quality(400))
        assertEquals("Quality unknown", StreamLabels.quality(null))
        assertEquals("Quality unknown", StreamLabels.quality(-1))
        assertEquals("Auto", StreamLabels.quality(0))
        assertEquals("1080p", StreamLabels.quality(1080))
        assertEquals("1440p", StreamLabels.quality(1440))
    }

    @Test fun `subtitle MIME follows file type including URL query strings`() {
        assertEquals("text/vtt", "https://example.test/sub.vtt?token=123".toSubtitleMimeType())
        assertEquals("text/x-ssa", "English.ASS".toSubtitleMimeType())
        assertEquals("application/ttml+xml", "English.ttml".toSubtitleMimeType())
    }

    @Test fun `failed download is retained when no longer queued while explicit cancel removes it`() {
        assertEquals(DlState.Failed, DownloadBook.of(DownloadType.IsFailed, false, false, 0, 0))
        assertNull(DownloadBook.of(DownloadType.IsStopped, false, false, 0, 0))
    }

    @Test fun `late sources remain usable after the first source failed`() {
        val (started, first) = LinkFallback.start(listOf("first"))
        assertEquals("first", first)
        val (waiting, next) = LinkFallback.onFailure(started)
        assertNull(next)
        val (resumed, late) = LinkFallback.onFailure(LinkFallback.withOrder(waiting, listOf("first", "late")))
        assertEquals("late", late)
        assertEquals(setOf("first"), resumed.failed)
        assertNull(LinkFallback.onFailure(resumed).second)
    }
}
