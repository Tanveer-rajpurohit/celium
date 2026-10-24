package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.celium.CeliumContentClassifier
import com.lagradost.cloudstream3.ui.celium.CeliumSearchMerge
import com.lagradost.cloudstream3.ui.celium.CeliumSection
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import com.lagradost.cloudstream3.ui.celium.SearchHit
import com.lagradost.cloudstream3.ui.search.SearchQuery
import com.lagradost.cloudstream3.ui.search.SearchRanking
import com.lagradost.cloudstream3.ui.search.TitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 1: query parser, title cleaner, ranking v2, grouping, classifier regressions, and the 20-query set. */
class CeliumSearchLogicTest {
    private val maxYear = 2027

    private fun tile(title: String, source: String = "A", type: TvType? = TvType.Movie, year: Int? = null, poster: String? = null) =
        CeliumTile(title, "https://${source.lowercase()}/${title.hashCode()}", source, type, poster, null, year)

    // ---------------------------------------------------------------- query parser

    @Test fun `a trailing year is split off`() {
        val p = SearchQuery.parse("Dune 2021", maxYear)
        assertEquals("Dune", p.title)
        assertEquals(2021, p.year)
        assertEquals("Dune 2021", p.raw)
    }

    @Test fun `a bracketed year is split off`() {
        val p = SearchQuery.parse("Dune (2021)", maxYear)
        assertEquals("Dune", p.title)
        assertEquals(2021, p.year)
    }

    @Test fun `titles that are years or carry a year inside stay whole`() {
        assertNull(SearchQuery.parse("1917", maxYear).year)
        assertEquals("1917", SearchQuery.parse("1917", maxYear).title)
        assertNull(SearchQuery.parse("2001 A Space Odyssey", maxYear).year)
        assertNull(SearchQuery.parse("Blade Runner 2049", maxYear).year)
        assertEquals("Blade Runner 2049", SearchQuery.parse("Blade Runner 2049", maxYear).title)
    }

    @Test fun `season and episode markers are split off and mean a series`() {
        val a = SearchQuery.parse("Breaking Bad S02E05", maxYear)
        assertEquals("Breaking Bad", a.title)
        assertEquals(2, a.season)
        assertEquals(5, a.episode)
        assertTrue(a.seriesHint)
        val b = SearchQuery.parse("breaking bad season 3", maxYear)
        assertEquals("breaking bad", b.title)
        assertEquals(3, b.season)
        assertNull(b.episode)
        assertFalse(SearchQuery.parse("Fargo", maxYear).seriesHint)
    }

    @Test fun `whitespace is cleaned and the title is never blank`() {
        assertEquals("Spider-Man 2", SearchQuery.parse("  Spider-Man\n\t2  ", maxYear).raw)
        assertEquals("S01", SearchQuery.parse("S01", maxYear).title)
    }

    // ---------------------------------------------------------------- title cleaner

    @Test fun `quality language and size tags are removed`() {
        val c = TitleCleaner.clean("Dune Part Two (2024) 1080p WEB-DL Hindi Dual Audio [x264] 2.5GB", maxYear)
        assertEquals("dune part two", c.text)
        assertEquals(2024, c.year)
    }

    @Test fun `a parenthesis that is part of the title is kept`() {
        assertEquals("spider man no way home", TitleCleaner.clean("Spider-Man (No Way Home)", maxYear).text)
    }

    @Test fun `season tags are removed and recorded`() {
        val c = TitleCleaner.clean("Breaking Bad Season 1-5 Complete 720p", maxYear)
        assertEquals("breaking bad", c.text)
        assertEquals(1, c.season)
    }

    @Test fun `a title is never cleaned down to nothing`() {
        assertEquals("hindi", TitleCleaner.clean("Hindi", maxYear).text)
    }

    @Test fun `compact form joins words`() {
        assertEquals("spiderman", TitleCleaner.clean("Spider-Man", maxYear).compact)
    }

    // ---------------------------------------------------------------- ranking: v1 behaviour is kept

    @Test fun `v1 tier values are unchanged`() {
        assertEquals(10_000, SearchRanking.score("amelie", "Amélie"))
        assertEquals(10_000, SearchRanking.score("spider man", "Spider-Man"))
        assertEquals(8_000, SearchRanking.score("arrival", "Arrival 2"))
        assertEquals(6_000, SearchRanking.score("arrival", "The Making of Arrival"))
        assertEquals(0, SearchRanking.score("---", "Anything"))
    }

    // ---------------------------------------------------------------- ranking: v2

    @Test fun `punctuation is ignored in either direction`() {
        assertEquals(9_500, SearchRanking.score("spiderman", "Spider-Man"))
        assertEquals(9_500, SearchRanking.score("oceans eleven", "Ocean's Eleven"))
        assertTrue(SearchRanking.score("spider-man", "Spiderman") >= 9_500)
    }

    @Test fun `one typo still matches and ranks below a clean match`() {
        val typo = SearchRanking.score("avngers", "Avengers: Endgame")
        assertTrue(typo in 2_500..3_500)
        assertTrue(typo > SearchRanking.score("avngers", "Avatar"))
        assertTrue(SearchRanking.score("avengers", "Avengers: Endgame") > typo)
    }

    @Test fun `short words are never fuzzy`() {
        assertEquals(0, SearchRanking.score("cat", "Cut"))
    }

    @Test fun `tags in provider titles do not hide an exact title`() {
        assertEquals(10_000, SearchRanking.score("interstellar", "Interstellar (2014) Hindi Dubbed 720p BluRay"))
    }

    @Test fun `a typed language word still finds the title that carries it`() {
        val ranked = SearchRanking.rank("hindi medium", listOf(tile("English Medium (2020)"), tile("Hindi Medium (2017) 1080p")))
        assertEquals("Hindi Medium (2017) 1080p", ranked.first().name)
    }

    @Test fun `the year in the query decides between equal titles`() {
        val ranked = SearchRanking.rank("dune 2021", listOf(
            tile("Dune", year = 1984), tile("Dune", source = "B", year = 2021)))
        assertEquals(2021, (ranked.first() as CeliumTile).year)
    }

    @Test fun `a year that is part of the title is not a mismatch`() {
        val ranked = SearchRanking.rank("wonder woman 1984", listOf(
            tile("Wonder Woman", year = 2017), tile("Wonder Woman 1984", source = "B", year = 2020)))
        assertEquals("Wonder Woman 1984", ranked.first().name)
    }

    @Test fun `ties go to more sources then source order then the shorter title then arrival order`() {
        val q = SearchQuery.parse("dune", maxYear)
        val c = listOf(
            com.lagradost.cloudstream3.ui.search.RankCandidate("late", listOf("Dune"), sourceOrder = 5),
            com.lagradost.cloudstream3.ui.search.RankCandidate("early", listOf("Dune"), sourceOrder = 1),
            com.lagradost.cloudstream3.ui.search.RankCandidate("agreed", listOf("Dune"), sourceCount = 2, sourceOrder = 9),
        )
        assertEquals(listOf("agreed", "early", "late"), SearchRanking.rankCandidates(q, c))
        // The same input in another order gives the same ranking: arrival timing does not matter.
        assertEquals(listOf("agreed", "early", "late"), SearchRanking.rankCandidates(q, c.reversed()))
    }

    @Test fun `ranking an empty list and an unrelated list is safe`() {
        assertTrue(SearchRanking.rank("dune", emptyList()).isEmpty())
        assertEquals(2, SearchRanking.rank("dune", listOf(tile("Zebra"), tile("Yak"))).size)
    }

    // ---------------------------------------------------------------- grouping

    private fun hit(item: CeliumTile, order: Int) = SearchHit(item, item.apiName.lowercase(), order)

    @Test fun `the same title from several sources becomes one group`() {
        val groups = CeliumSearchMerge.group(listOf(
            hit(tile("Dune (2021) 1080p WEB-DL", "A", year = 2021), 0),
            hit(tile("Dune 2021 Hindi Dual Audio", "B", year = 2021, poster = "p"), 1),
            hit(tile("Dune (1984)", "C", year = 1984), 2),
        ))
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].sourceCount)
        assertEquals("B", groups[0].best.item.apiName) // artwork first
        assertEquals(1984, groups[1].year)
    }

    @Test fun `a movie and a series with the same name are never merged`() {
        val groups = CeliumSearchMerge.group(listOf(
            hit(tile("Fargo", "A", TvType.Movie), 0), hit(tile("Fargo", "B", TvType.TvSeries), 1)))
        assertEquals(2, groups.size)
    }

    @Test fun `a missing year joins the group that has one`() {
        val groups = CeliumSearchMerge.group(listOf(
            hit(tile("Dune", "A", year = 2021), 0), hit(tile("Dune", "B", year = null), 1)))
        assertEquals(1, groups.size)
        assertEquals(2021, groups[0].year)
    }

    @Test fun `grouped results are ranked once over the whole set`() {
        val hits = listOf(
            hit(tile("The Making of Dune", "A"), 0),
            hit(tile("Dune", "A", year = 2021), 0),
            hit(tile("Dune (2021) 1080p", "B", year = 2021), 1),
        )
        val ranked = CeliumSearchMerge.results(SearchQuery.parse("dune", maxYear), hits) { false }
        assertEquals(2, ranked.size)
        assertEquals(2, ranked.first().sourceCount)
    }

    // ---------------------------------------------------------------- classifier regressions

    @Test fun `a film with Part or Vol in its title stays a movie`() {
        assertEquals(CeliumSection.Movies, CeliumContentClassifier.classify(TvType.Movie, null, "Dune Part 2", null))
        assertEquals(CeliumSection.Movies, CeliumContentClassifier.classify(TvType.Movie, null, "Kill Bill Vol. 1", null))
    }

    @Test fun `season and episode markers still mean TV`() {
        assertEquals(CeliumSection.Series, CeliumContentClassifier.classify(TvType.Movie, null, "Breaking Bad S01E02", null))
        assertEquals(CeliumSection.Series, CeliumContentClassifier.classify(null, null, "Breaking Bad Season 2", null))
        assertEquals(CeliumSection.Series, CeliumContentClassifier.classify(TvType.TvSeries, null, "Anything", null))
    }

    // ---------------------------------------------------------------- the 20-query set (Plan 1, step 1)

    private class Case(val query: String, val expected: String, vararg val titles: String)

    private val cases = listOf(
        Case("dune", "Dune (2021) 1080p WEB-DL", "Dune: Part Two (2024)", "Dunkirk (2017)", "Dune (2021) 1080p WEB-DL", "Sand Dunes"),
        Case("dune 2021", "Dune (2021)", "Dune (1984)", "Dune (2021)", "Dune: Part Two (2024)"),
        Case("spiderman", "Spider-Man", "Spider-Man: No Way Home", "The Amazing Spider-Man", "Spider-Man", "Spider Verse"),
        Case("spider-man no way home", "Spider-Man: No Way Home (2021) Hindi 720p", "Spider-Man: Homecoming", "Spider-Man: No Way Home (2021) Hindi 720p"),
        Case("avngers endgame", "Avengers: Endgame", "Avatar", "Avengers: Infinity War", "Avengers: Endgame"),
        Case("oceans eleven", "Ocean's Eleven", "Ocean's Twelve", "Oceans of Fire", "Ocean's Eleven"),
        Case("hindi medium", "Hindi Medium (2017) 1080p", "English Medium (2020)", "Hindi Medium (2017) 1080p"),
        Case("interstellar", "Interstellar (2014) Hindi Dubbed 720p BluRay", "Interstellar Wars", "Interstellar (2014) Hindi Dubbed 720p BluRay"),
        Case("the dark knight", "The Dark Knight (2008)", "The Dark Knight Rises (2012)", "Dark Knight Returns", "The Dark Knight (2008)"),
        Case("breaking bad", "Breaking Bad Season 1-5 Complete 720p", "Breaking Bad: El Camino", "Better Call Saul", "Breaking Bad Season 1-5 Complete 720p"),
        Case("breaking bad s02", "Breaking Bad Season 2", "Breaking Bad: El Camino", "Breaking Bad Season 2"),
        Case("kill bill vol 1", "Kill Bill: Vol. 1 (2003)", "Kill Bill: Vol. 2 (2004)", "Kill Bill: Vol. 1 (2003)"),
        Case("fast and furious", "Fast & Furious (2009)", "Furious 7", "Fast Five", "Fast & Furious (2009)"),
        Case("amelie", "Amélie (2001)", "Amelia (2009)", "Amélie (2001)"),
        Case("千と千尋の神隠し", "千と千尋の神隠し", "ハウルの動く城", "千と千尋の神隠し"),
        Case("3 idiots", "3 Idiots (2009) 1080p", "Three Idiots Documentary", "3 Idiots (2009) 1080p"),
        Case("rrr", "RRR (2022)", "RRR: Behind and Beyond", "RRR (2022)"),
        Case("jawan 2023", "Jawan (2023)", "Jawan (2022)", "Jawan (2023)"),
        Case("game of thrones", "Game of Thrones S01 1080p", "Game of Thrones: House of the Dragon", "Games People Play", "Game of Thrones S01 1080p"),
        Case("lord of the rngs", "The Lord of the Rings: The Fellowship of the Ring", "Lord of War", "The Lord of the Rings: The Fellowship of the Ring"),
    )

    @Test fun `the expected title is in the top 3 for every query in the set`() {
        val missed = ArrayList<String>()
        for (c in cases) {
            val items = c.titles.mapIndexed { i, t -> tile(t, "S$i") }
            val top = SearchRanking.rank(c.query, items).take(3).map { it.name }
            if (c.expected !in top) missed.add("${c.query} -> $top")
        }
        assertTrue("missed: $missed", missed.isEmpty())
    }

    @Test fun `the expected title is first for the clear-cut queries`() {
        val strict = listOf("dune 2021", "spiderman", "oceans eleven", "hindi medium", "interstellar",
            "avngers endgame", "amelie", "rrr", "jawan 2023", "3 idiots", "kill bill vol 1")
        val wrong = ArrayList<String>()
        for (c in cases.filter { it.query in strict }) {
            val items = c.titles.mapIndexed { i, t -> tile(t, "S$i") }
            val first = SearchRanking.rank(c.query, items).first().name
            if (first != c.expected) wrong.add("${c.query} -> $first")
        }
        assertTrue("wrong first: $wrong", wrong.isEmpty())
    }
}
