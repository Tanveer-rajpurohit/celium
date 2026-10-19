package com.lagradost.cloudstream3.ui.celium

/**
 * Pure paging logic for Celium infinite scroll (Requirement 6).
 * No Android dependencies so it can be unit tested on the JVM.
 */

/** Paging state of one list (search results, a shelf, or the collections list). */
enum class PageStatus { Idle, Loading, Error, End }

/** Per-list paging state. An empty map entry is treated as the default (Idle). */
data class ListPaging(val status: PageStatus = PageStatus.Idle, val error: String? = null)

/** Result of merging a new page into a list: the merged items and how many were new. */
data class AppendResult<T>(val items: List<T>, val added: Int)

object CeliumPaging {
    /** Items from the end of a card list at which the next page is requested. */
    const val ITEM_THRESHOLD = 6

    /** Shelves from the end of the collections list at which more sections are requested. */
    const val SHELF_THRESHOLD = 2

    /** Timeout for a single page request. */
    const val TIMEOUT_MS = 30_000L

    /**
     * Appends [page] after [existing]. Existing items keep their order and are never
     * removed or moved. Page items whose key is already present (in [existing] or
     * earlier in [page]) are dropped, so the first occurrence wins.
     */
    fun <T> append(existing: List<T>, page: List<T>, key: (T) -> String): AppendResult<T> {
        val seen = HashSet<String>(existing.size + page.size)
        existing.forEach { seen.add(key(it)) }
        val fresh = page.filter { seen.add(key(it)) }
        return AppendResult(existing + fresh, fresh.size)
    }

    /** Status after a page lands: End when nothing new arrived or the provider has no next page. */
    fun statusAfter(added: Int, providerHasNext: Boolean): PageStatus =
        if (added <= 0 || !providerHasNext) PageStatus.End else PageStatus.Idle

    /**
     * True when the next page should be requested: the list is Idle, has items, and the
     * last visible item is within [threshold] items of the end.
     */
    fun shouldRequest(
        lastVisibleIndex: Int?,
        loadedCount: Int,
        threshold: Int,
        status: PageStatus,
    ): Boolean =
        status == PageStatus.Idle &&
            loadedCount > 0 &&
            lastVisibleIndex != null &&
            lastVisibleIndex >= loadedCount - 1 - threshold
}
