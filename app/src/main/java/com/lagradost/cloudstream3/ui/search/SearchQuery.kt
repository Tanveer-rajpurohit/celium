package com.lagradost.cloudstream3.ui.search

import java.util.Calendar

/**
 * A search text split into the parts providers and ranking need (Celium search, step 2).
 * No Android dependencies, so it is unit tested on the JVM.
 */
internal data class ParsedQuery(
    /** The text as typed, with whitespace cleaned. */
    val raw: String,
    /** [raw] without a trailing or bracketed year and without a season/episode marker. Never blank when [raw] is not. */
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
) {
    /** True when the reader asked for a season or an episode, so a series is meant. */
    val seriesHint: Boolean get() = season != null || episode != null
}

internal object SearchQuery {
    private val seasonEpisode = Regex("""(?<![\p{L}\p{N}])s(\d{1,2})\s*e(\d{1,3})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    private val seasonWord = Regex("""(?<![\p{L}\p{N}])season\s*(\d{1,2})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    private val seasonShort = Regex("""(?<![\p{L}\p{N}])s(\d{1,2})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    private val episodeWord = Regex("""(?<![\p{L}\p{N}])ep(?:isode)?\s*(\d{1,3})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    private val bracketedYear = Regex("""[\(\[]\s*((?:19|20)\d{2})\s*[\)\]]""")
    private val trailingYear = Regex("""(?<=\s)((?:19|20)\d{2})$""")
    private val emptyBrackets = Regex("""[\(\[]\s*[\)\]]""")
    private val whitespace = Regex("\\s+")

    fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    /**
     * A year counts only when bracketed ("Dune (2021)") or last ("Dune 2021"), and only when other
     * words remain. "1917", "2001 A Space Odyssey" and "Blade Runner 2049" therefore stay whole.
     */
    fun parse(query: String, maxYear: Int = currentYear() + 1): ParsedQuery {
        val raw = SearchRanking.cleanQuery(query)
        var text = raw
        var season: Int? = null
        var episode: Int? = null

        seasonEpisode.find(text)?.let {
            season = it.groupValues[1].toInt()
            episode = it.groupValues[2].toInt()
            text = text.removeRange(it.range)
        }
        if (season == null) seasonWord.find(text)?.let {
            season = it.groupValues[1].toInt()
            text = text.removeRange(it.range)
        }
        if (season == null) seasonShort.find(text)?.let {
            season = it.groupValues[1].toInt()
            text = text.removeRange(it.range)
        }
        if (episode == null) episodeWord.find(text)?.let {
            episode = it.groupValues[1].toInt()
            text = text.removeRange(it.range)
        }
        text = tidy(text)

        var year: Int? = null
        val bracket = bracketedYear.findAll(text).lastOrNull { it.groupValues[1].toInt() <= maxYear }
        if (bracket != null) {
            val without = tidy(text.removeRange(bracket.range))
            if (without.isNotEmpty()) {
                year = bracket.groupValues[1].toInt()
                text = without
            }
        } else {
            val trailing = trailingYear.find(text)
            if (trailing != null && trailing.groupValues[1].toInt() <= maxYear) {
                val without = tidy(text.removeRange(trailing.range))
                if (without.isNotEmpty()) {
                    year = trailing.groupValues[1].toInt()
                    text = without
                }
            }
        }
        return ParsedQuery(raw, text.ifBlank { raw }, year, season, episode)
    }

    private fun tidy(value: String): String =
        value.replace(emptyBrackets, " ").replace(whitespace, " ").trim()
}
