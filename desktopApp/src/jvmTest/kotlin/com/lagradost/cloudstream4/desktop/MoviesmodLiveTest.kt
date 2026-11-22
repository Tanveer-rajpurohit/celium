package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.desktop.moviesmod.DesktopMoviesmod
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

/** Opt-in integration checks; provider availability must not affect ordinary offline unit tests. */
class MoviesmodLiveTest {
    @Test fun movieSearchDetailsAndExtraction() = checkTitle("inception", false)
    @Test fun seriesSearchDetailsAndExtraction() = checkTitle("breaking bad", true)

    private fun checkTitle(query: String, series: Boolean) {
        assumeTrue(System.getenv("CELIUM_LIVE_TESTS") == "true")
        runBlocking(Dispatchers.IO) {
            withTimeout(120_000) {
                val provider = DesktopMoviesmod.initialize()
                assertSame(provider, DesktopMoviesmod.initialize())
                val results = provider.search(query, 1)?.items.orEmpty()
                println("Search '$query': ${results.size} results")
                assertTrue("Search returned no titles", results.isNotEmpty())
                val result = results.first()
                val details = provider.load(result.url) ?: error("No details")
                println("Details: ${details.name}, ${details.javaClass.simpleName}")
                val data = if (series) {
                    assertTrue("Expected series details", details is TvSeriesLoadResponse)
                    (details as TvSeriesLoadResponse).episodes.sortedWith(compareBy({ it.season }, { it.episode }))
                        .firstOrNull()?.data ?: error("No episodes")
                } else {
                    assertTrue("Expected movie details", details is MovieLoadResponse)
                    (details as MovieLoadResponse).dataUrl
                }
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                provider.loadLinks(data, false, {}, { links.add(it) })
                println("Extracted ${links.size} media sources")
                assertTrue("No media sources extracted", links.isNotEmpty())
                assertTrue("No source supported by current player", links.any { runCatching { mediaOptions(it) }.isSuccess })
            }
        }
    }
}
