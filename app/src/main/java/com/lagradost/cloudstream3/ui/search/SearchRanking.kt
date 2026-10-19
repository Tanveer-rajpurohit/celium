package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.SearchResponse
import java.text.Normalizer
import java.util.Locale

internal object SearchRanking {
    private val whitespace = Regex("\\s+")
    private val latinMarks = Regex("(?<=[A-Za-z])\\p{M}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    fun cleanQuery(query: String): String = query.trim().replace(whitespace, " ")

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(latinMarks, "").lowercase(Locale.ROOT).replace(punctuation, " ").trim()

    fun score(query: String, title: String): Int {
        return scoreNormalized(normalize(query), normalize(title))
    }

    private fun scoreNormalized(needle: String, candidate: String): Int {
        if (needle.isEmpty()) return 0
        if (candidate == needle) return 10_000
        if (candidate.startsWith("$needle ")) return 8_000
        if (candidate.contains(needle)) return 6_000
        val words = candidate.split(' ').toSet()
        val tokens = needle.split(' ').filter { it.isNotEmpty() }
        val matches = tokens.count { token -> words.any { it.startsWith(token) } }
        return if (matches == tokens.size) 4_000 else matches * 100
    }

    fun rank(query: String, items: List<SearchResponse>): List<SearchResponse> {
        val needle = normalize(query)
        return items
            // Keep alternative sources for a title, including providers sharing the same URL.
            .distinctBy { it.apiName to it.url }
            .map { it to scoreNormalized(needle, normalize(it.name)) }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
