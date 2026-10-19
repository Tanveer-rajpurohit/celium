package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.search.SearchRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankingTest {
    private fun result(providerName: String, title: String, url: String): SearchResponse =
        object : MainAPI() { override var name = providerName }
            .newMovieSearchResponse(title, url, fix = false)

    @Test
    fun `normalizes whitespace without rewriting provider query punctuation`() {
        assertEquals("Spider-Man 2", SearchRanking.cleanQuery("  Spider-Man\n\t2  "))
    }

    @Test
    fun `ranks exact titles before prefixes and substring matches`() {
        val results = listOf(
            result("A", "The Making of Arrival", "https://a/3"),
            result("A", "Arrival 2", "https://a/2"),
            result("A", "Arrival", "https://a/1"),
        )
        assertEquals(listOf("Arrival", "Arrival 2", "The Making of Arrival"),
            SearchRanking.rank("arrival", results).map { it.name })
    }

    @Test
    fun `matches accents punctuation and non Latin titles`() {
        assertEquals(10_000, SearchRanking.score("amelie", "Amélie"))
        assertEquals(10_000, SearchRanking.score("spider man", "Spider-Man"))
        assertEquals(10_000, SearchRanking.score("千と千尋の神隠し", "千と千尋の神隠し"))
        assertEquals(0, SearchRanking.score("---", "Anything"))
        assertTrue(SearchRanking.score("का", "कि") < 10_000)
    }

    @Test
    fun `matches reordered word prefixes above unrelated results`() {
        assertTrue(SearchRanking.score("dark knight", "The Knight of Darkness") >
            SearchRanking.score("dark knight", "The Dark Tower"))
    }

    @Test
    fun `removes repeated source URLs but preserves alternative sources and versions`() {
        val sharedUrl = "https://shared/title"
        val items = listOf(
            result("A", "Arrival", sharedUrl),
            result("A", "Arrival", sharedUrl),
            result("B", "Arrival", sharedUrl),
            result("A", "Arrival", "https://a/another-version"),
        )
        assertEquals(3, SearchRanking.rank("Arrival", items).size)
    }
}
