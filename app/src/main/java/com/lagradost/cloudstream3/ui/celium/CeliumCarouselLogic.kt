package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.SearchResponse

/**
 * Pure selection and index helpers for the home Featured_Carousel.
 * No Android dependencies, JVM-testable.
 */
object CeliumCarouselLogic {

    /** Largest carousel size. */
    const val MAX_FEATURED = 4

    /** Smallest carousel size; fewer unique items hides the carousel. */
    const val MIN_FEATURED = 3

    /**
     * Picks Featured items: dedups by [CeliumDedup.key] of [url] (first wins),
     * then returns 4 items when at least 4 are unique, 3 when exactly 3 are
     * unique, and an empty list (carousel hidden) otherwise.
     */
    fun <T> pickFeatured(items: List<T>, url: (T) -> String): List<T> {
        val unique = CeliumDedup.distinctByUrl(items, excludeUrl = null, url = url)
        return when {
            unique.size >= MAX_FEATURED -> unique.take(MAX_FEATURED)
            unique.size == MIN_FEATURED -> unique
            else -> emptyList()
        }
    }

    /** Convenience overload for any [SearchResponse] (including CeliumTile). */
    fun <T : SearchResponse> pickFeatured(items: List<T>): List<T> =
        pickFeatured(items) { it.url }

    /**
     * Wraps [i] into `[0, size)` for prev/next navigation, so `size` maps to 0
     * and `-1` maps to `size - 1`. Returns 0 when [size] is not positive.
     */
    fun wrapIndex(i: Int, size: Int): Int {
        if (size <= 0) return 0
        return Math.floorMod(i, size)
    }
}
