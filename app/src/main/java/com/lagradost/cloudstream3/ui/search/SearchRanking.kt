package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.ui.celium.CeliumTile

/** One result, or one group of the same title from several sources, with what ranking needs besides the text. */
internal class RankCandidate<T>(
    val item: T,
    /** Every title the candidate is known by; the best match counts. */
    val titles: List<String>,
    val year: Int? = null,
    val hasPoster: Boolean = false,
    /** How many different sources returned this title. */
    val sourceCount: Int = 1,
    /** True when the item's own type fits the category being searched. */
    val typeFit: Boolean = false,
    /** Lower is better; the home pool order (MultiMovies, CineStream, Moviesmod, then catalog order). */
    val sourceOrder: Int = Int.MAX_VALUE,
)

/**
 * Celium search ranking, version 2. The five text tiers are unchanged; v2 adds a space-free comparison,
 * typo tolerance, year, type fit, source agreement and tie-breaks. Pure Kotlin, JVM tested.
 */
internal object SearchRanking {
    private val whitespace = Regex("\\s+")

    // Text tiers (unchanged from v1) and the new in-between steps.
    private const val EXACT = 10_000
    private const val COMPACT_EXACT = 9_500
    private const val PREFIX = 8_000
    private const val COMPACT_PREFIX = 7_500
    private const val CONTAINS = 6_000
    private const val COMPACT_CONTAINS = 5_500
    private const val ALL_WORDS = 4_000
    private const val FUZZY_ALL_BASE = 3_500
    private const val FUZZY_ALL_FLOOR = 2_500
    private const val FUZZY_EDIT_COST = 300
    private const val WORD_MATCH = 100
    private const val FUZZY_WORD_MATCH = 80

    // Adjustments. Smaller than a tier step (2,000), so they reorder within a tier, not across tiers.
    private const val YEAR_MATCH = 600
    private const val YEAR_MISMATCH = 900
    private const val TYPE_FIT = 150
    private const val AGREEMENT_EACH = 150
    private const val AGREEMENT_MAX = 3
    private const val POSTER = 50

    fun cleanQuery(query: String): String = query.trim().replace(whitespace, " ")

    private fun normalize(value: String): String = TitleCleaner.normalize(value)

    /** Text score of [title] for [query]: 0 means unrelated. Keeps the v1 meaning of the tier values. */
    fun score(query: String, title: String): Int {
        val needle = normalize(query)
        if (needle.isEmpty()) return 0
        return textScore(needle, TitleCleaner.forms(title))
    }

    /** Best of the title as written and the title cleaned of tags. */
    private fun textScore(needle: String, forms: TitleForms): Int {
        val a = scoreForm(needle, forms.normalized)
        val b = if (forms.cleaned.text != forms.normalized) scoreForm(needle, forms.cleaned.text) else 0
        return maxOf(a, b)
    }

    private fun scoreForm(needle: String, candidate: String): Int {
        if (needle.isEmpty() || candidate.isEmpty()) return 0
        val compactNeedle = TitleCleaner.compact(needle)
        val compactCandidate = TitleCleaner.compact(candidate)
        val compact = if (compactNeedle.length >= 4) when {
            compactCandidate == compactNeedle -> COMPACT_EXACT
            compactCandidate.startsWith(compactNeedle) -> COMPACT_PREFIX
            compactCandidate.contains(compactNeedle) -> COMPACT_CONTAINS
            else -> 0
        } else 0
        return maxOf(tiers(needle, candidate), compact)
    }

    private fun tiers(needle: String, candidate: String): Int {
        if (candidate == needle) return EXACT
        if (candidate.startsWith("$needle ")) return PREFIX
        if (candidate.contains(needle)) return CONTAINS
        val words = candidate.split(' ').filter { it.isNotEmpty() }
        val tokens = needle.split(' ').filter { it.isNotEmpty() }.take(8)
        if (tokens.isEmpty()) return 0
        var exact = 0
        var fuzzy = 0
        var edits = 0
        for (token in tokens) {
            if (words.any { it.startsWith(token) }) exact++
            else {
                val e = fuzzyEdits(token, words)
                if (e != null) { fuzzy++; edits += e }
            }
        }
        val matched = exact + fuzzy
        return when {
            matched == tokens.size && fuzzy == 0 -> ALL_WORDS
            matched == tokens.size -> maxOf(FUZZY_ALL_FLOOR, FUZZY_ALL_BASE - FUZZY_EDIT_COST * edits)
            else -> exact * WORD_MATCH + fuzzy * FUZZY_WORD_MATCH
        }
    }

    /** One edit for words of 4 to 7 letters, two for 8 or more; shorter words are never fuzzy. */
    private fun fuzzyEdits(token: String, words: List<String>): Int? {
        if (token.length < 4) return null
        val limit = if (token.length <= 7) 1 else 2
        var best = Int.MAX_VALUE
        for (w in words) {
            if (w.length < 3) continue
            // Compared whole, and against the same-length start of the word (a half-typed word with a typo).
            if (kotlin.math.abs(w.length - token.length) <= limit) best = minOf(best, distance(token, w, limit))
            if (w.length > token.length) best = minOf(best, distance(token, w.substring(0, token.length), limit))
            if (best == 1) break
        }
        return if (best <= limit) best else null
    }

    /** Optimal string alignment distance (insert, delete, replace, swap of neighbours), capped at limit + 1. */
    internal fun distance(a: String, b: String, limit: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return limit + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Full relevance of a candidate for a parsed query; 0 means unrelated, otherwise at least 1. */
    fun relevance(query: ParsedQuery, c: RankCandidate<*>): Int {
        val needleRaw = normalize(query.raw)
        val needleTitle = normalize(query.title)
        if (needleRaw.isEmpty()) return 0
        var text = 0
        var carriesYear = false
        for (title in c.titles) {
            val forms = TitleCleaner.forms(title)
            var s = textScore(needleRaw, forms)
            if (needleTitle != needleRaw && needleTitle.isNotEmpty()) s = maxOf(s, textScore(needleTitle, forms))
            text = maxOf(text, s)
            val qy = query.year
            if (qy != null && forms.normalized.split(' ').contains(qy.toString())) carriesYear = true
        }
        if (text == 0) return 0
        var score = text
        val qy = query.year
        if (qy != null && c.year != null) score += if (c.year == qy || carriesYear) YEAR_MATCH else -YEAR_MISMATCH
        if (c.typeFit) score += TYPE_FIT
        score += AGREEMENT_EACH * (c.sourceCount - 1).coerceIn(0, AGREEMENT_MAX)
        if (c.hasPoster) score += POSTER
        return score.coerceAtLeast(1)
    }

    /**
     * Ranks candidates. Ties go to more sources, then the better source order, then the shorter title,
     * then the incoming order, so the result never depends on arrival timing.
     */
    fun <T> rankScored(query: ParsedQuery, candidates: List<RankCandidate<T>>): List<Pair<T, Int>> {
        class Scored(val c: RankCandidate<T>, val score: Int, val index: Int)
        return candidates.mapIndexed { i, c -> Scored(c, relevance(query, c), i) }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.c.sourceCount }
                    .thenBy { it.c.sourceOrder }
                    .thenBy { it.c.titles.firstOrNull()?.length ?: 0 }
                    .thenBy { it.index }
            )
            .map { it.c.item to it.score }
    }

    fun <T> rankCandidates(query: ParsedQuery, candidates: List<RankCandidate<T>>): List<T> =
        rankScored(query, candidates).map { it.first }

    /** The release year a provider reported, when its response type carries one. */
    fun yearOf(item: SearchResponse): Int? = when (item) {
        is CeliumTile -> item.year
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        is AnimeSearchResponse -> item.year
        else -> null
    }

    /** v1 entry point, kept for the inherited search screen: ranks provider results, keeping alternative sources. */
    fun rank(query: String, items: List<SearchResponse>): List<SearchResponse> {
        val parsed = SearchQuery.parse(query)
        return rankCandidates(parsed,
            // Keep alternative sources for a title, including providers sharing the same URL.
            items.distinctBy { it.apiName to it.url }.map {
                RankCandidate(it, listOf(it.name), yearOf(it) ?: TitleCleaner.clean(it.name).year, it.posterUrl != null)
            })
    }
}
