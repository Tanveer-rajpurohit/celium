package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.CeliumLinkFilter
import org.junit.Assert.*
import org.junit.Test

class CeliumLinkFilterTest {
    @Test fun `dudefilms links are hidden`() {
        assertTrue(CeliumLinkFilter.isBlocked("Dudefilms", "Dudefilms 1080p"))
        assertTrue(CeliumLinkFilter.isBlocked("HubCloud", "[Dude Films] HubCloud 720p"))
        assertFalse(CeliumLinkFilter.isBlocked("Moviesmod", "Moviesmod [Instant] 1080p"))
    }
}
