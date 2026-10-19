package com.lagradost.cloudstream3.ui.celium

/**
 * Home feed provider pool (Req 14). Pure logic: decides provider order and where
 * each ready provider's Shelves go, independent of completion order.
 */
object CeliumHomePool {
    /** MultiMovies, CineStream, Moviesmod, in that order. */
    val priority = listOf("multi", "cinestream", "moviesmod")

    /** Priority providers first (in [priority] order, when present), then the rest in the given order. */
    fun orderPool(candidates: List<CeliumSource>): List<CeliumSource> {
        val distinct = candidates.distinctBy { it.id }
        val byId = distinct.associateBy { it.id }
        val head = priority.mapNotNull { byId[it] }
        val headIds = head.mapTo(HashSet()) { it.id }
        return head + distinct.filter { it.id !in headIds }
    }

    /**
     * Shelves of ready providers in pool order. A provider not in [ready] is skipped, so the
     * next one takes its place. The result depends only on the inputs, never on arrival order,
     * and adding a newly ready provider keeps the relative order of Shelves already placed.
     */
    fun placeShelves(
        pool: List<CeliumSource>,
        ready: Set<String>,
        shelvesBySource: Map<String, List<CeliumShelf>>,
    ): List<CeliumShelf> =
        orderPool(pool)
            .filter { it.id in ready }
            .flatMap { shelvesBySource[it.id].orEmpty() }
}
