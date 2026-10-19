package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Splits Movies/TV provider content into the Movies and Series Sections.
 * Pure logic, JVM-testable. Only ever returns [CeliumSection.Movies] or [CeliumSection.Series].
 */
object CeliumContentClassifier {
    /** Category name hints (matched case-insensitively as substrings). */
    val categoryHints = listOf(
        "web series", "tv shows", "tv series", "series", "shows", "episodes", "drama", "season"
    )

    /** Title hints: the word "season", the word "episode", or an `S1`/`S01` marker. */
    private val titleHint = Regex("""\b(season|episode)\b|\bS\d{1,2}\b""", RegexOption.IGNORE_CASE)

    /** Returns Series if any signal says series; otherwise Movies. */
    fun classify(type: TvType?, categoryName: String?, title: String): CeliumSection {
        if (type == TvType.TvSeries) return CeliumSection.Series
        val category = categoryName?.lowercase()
        if (category != null && categoryHints.any { category.contains(it) }) return CeliumSection.Series
        if (titleHint.containsMatchIn(title)) return CeliumSection.Series
        return CeliumSection.Movies
    }

    /**
     * Classifies every item of one main-page category. Each item lands in exactly one Section,
     * input order is kept within a Section, and a Section with zero items gets no entry.
     */
    fun split(category: String, items: List<SearchResponse>): Map<CeliumSection, List<SearchResponse>> {
        val movies = ArrayList<SearchResponse>()
        val series = ArrayList<SearchResponse>()
        for (item in items) {
            when (classify(item.type, category, item.name)) {
                CeliumSection.Series -> series.add(item)
                else -> movies.add(item)
            }
        }
        val result = LinkedHashMap<CeliumSection, List<SearchResponse>>(2)
        if (movies.isNotEmpty()) result[CeliumSection.Movies] = movies
        if (series.isNotEmpty()) result[CeliumSection.Series] = series
        return result
    }
}
