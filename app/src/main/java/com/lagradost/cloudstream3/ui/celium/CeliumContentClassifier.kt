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
        "web series", "web-series", "webseries", "tv shows", "tv-shows", "tv series", "tv-series",
        "series", "shows", "episodes", "episode", "drama", "dramas", "season", "seasons",
        "serial", "serials", "k-drama", "kdrama", "c-drama", "cdrama", "anime series",
        "animation series", "mini series", "miniseries", "netflix series", "prime series",
        "amazon series", "hotstar series", "disney series", "ott series"
    )

    val movieCategoryHints = listOf(
        "movie", "movies", "film", "films", "cinema", "hollywood", "bollywood", "tollywood",
        "kollywood", "netflix movies", "prime movies", "amazon movies", "disney movies"
    )

    /** Title hints: season/episode markers, part numbers, or series keywords. */
    private val titleHint = Regex(
        """\b(season|seasons|episode|episodes|series|serial)\b|\bS\d{1,2}\b|\bEP?\d{1,3}\b|\bS\d{1,2}\s*(?:E\d{1,2}|EP\d{1,3}|-\s*S\d{1,2})|\b(?:Complete|Full)\s+(?:Season|Series)|\b(?:Part|Vol|Volume)\s*\d+""",
        RegexOption.IGNORE_CASE
    )

    private val movieTitleHint = Regex("""\b(movie|film|cinema)\b""", RegexOption.IGNORE_CASE)

    private val seriesUrlHints = listOf(
        "/series/", "/tv/", "/tvshows/", "/tv-shows/", "/show/", "/shows/",
        "/season/", "/episodes/", "/episode/", "/drama/", "/web-series/"
    )

    private val movieUrlHints = listOf(
        "/movie/", "/movies/", "/film/", "/films/"
    )

    /** Returns Series if any signal says series; otherwise Movies. */
    fun classify(type: TvType?, categoryName: String?, title: String, url: String? = null): CeliumSection {
        if (type == TvType.TvSeries || type == TvType.AsianDrama) return CeliumSection.Series
        val category = categoryName?.lowercase()
        val lowerUrl = url?.lowercase()

        // Explicit category signals
        if (category != null) {
            if (categoryHints.any { category.contains(it) }) return CeliumSection.Series
            if (movieCategoryHints.any { category.contains(it) }) return CeliumSection.Movies
        }

        // Title and URL signals for series
        if (titleHint.containsMatchIn(title)) return CeliumSection.Series
        if (lowerUrl != null && seriesUrlHints.any { lowerUrl.contains(it) }) return CeliumSection.Series

        // Explicit movie signals
        if (type == TvType.Movie || type == TvType.AnimeMovie) return CeliumSection.Movies
        if (movieTitleHint.containsMatchIn(title)) return CeliumSection.Movies
        if (lowerUrl != null && movieUrlHints.any { lowerUrl.contains(it) }) return CeliumSection.Movies

        return CeliumSection.Movies
    }

    /**
     * Classifies every item of one main-page category. Each item lands in appropriate Section(s).
     * Input order is kept within a Section, and a Section with zero items gets no entry.
     */
    fun split(category: String, items: List<SearchResponse>): Map<CeliumSection, List<SearchResponse>> {
        val movies = ArrayList<SearchResponse>()
        val series = ArrayList<SearchResponse>()
        val lowerCategory = category.lowercase()
        val isExplicitMovieCategory = movieCategoryHints.any { lowerCategory.contains(it) }
        val isExplicitSeriesCategory = categoryHints.any { lowerCategory.contains(it) }

        for (item in items) {
            if (isExplicitSeriesCategory) {
                series.add(item)
                continue
            }
            if (isExplicitMovieCategory) {
                movies.add(item)
                continue
            }
            when (classify(item.type, category, item.name, item.url)) {
                CeliumSection.Series -> series.add(item)
                else -> {
                    movies.add(item)
                    // In general OTT categories (e.g. "Netflix", "Prime Video", "Disney+") where items may be tagged Anime or null by builders,
                    // ensure items without explicit movie markers are also populated in Series so TV Shows feed stays rich.
                    val isCarrierType = item.type == null || item.type == TvType.Anime || item.type == TvType.AnimeMovie || item.type == TvType.OVA
                    if (isCarrierType && !movieTitleHint.containsMatchIn(item.name) &&
                        (item.url.contains("netflix", true) || item.url.contains("prime", true) || item.url.contains("hotstar", true) ||
                         item.url.contains("disney", true) || lowerCategory.contains("netflix") || lowerCategory.contains("prime") ||
                         lowerCategory.contains("disney") || lowerCategory.contains("hotstar") || lowerCategory.contains("trending") ||
                         lowerCategory.contains("popular") || lowerCategory.contains("latest") || lowerCategory.contains("top"))) {
                        series.add(item)
                    }
                }
            }
        }
        val result = LinkedHashMap<CeliumSection, List<SearchResponse>>(2)
        if (movies.isNotEmpty()) result[CeliumSection.Movies] = movies
        if (series.isNotEmpty()) result[CeliumSection.Series] = series
        return result
    }
}
