package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.SearchResponse

/**
 * Pure details-screen logic (no Android imports) so it can be unit-tested on the JVM.
 */

/** One entry in a Details selector (season, episode range, dub status). */
data class SelectorOption(val label: String)

object CeliumSelector {
    /**
     * Label shown on a selector button. Prefers the text resolved from model state, then the
     * option at [selectedIndex], then [fallback] (Requirement 8.7).
     */
    fun label(
        selectedText: String?,
        options: List<SelectorOption>,
        selectedIndex: Int,
        fallback: String,
    ): String = selectedText ?: options.getOrNull(selectedIndex)?.label ?: fallback

    /** A selector is only shown when there is a real choice; zero options hides it (Requirement 8.10). */
    fun visible(options: List<SelectorOption>): Boolean = options.size > 1
}

object CeliumRecommendations {
    /**
     * Filters recommendations for display (Requirements 1.1, 1.2, 9.7, 9.8):
     * keeps source order, drops adult items when [adultEnabled] is false, then keeps the
     * first item per normalised Title URL and drops every item matching [selfUrl]
     * (compared with [CeliumDedup.key], so trailing '/', query, or host case do not repeat).
     */
    fun filter(
        items: List<SearchResponse>,
        selfUrl: String,
        adultEnabled: Boolean,
        isAdult: (SearchResponse) -> Boolean,
    ): List<SearchResponse> {
        val allowed = if (adultEnabled) items else items.filter { !isAdult(it) }
        return CeliumDedup.distinctByUrl(allowed, excludeUrl = selfUrl) { it.url }
    }
}
