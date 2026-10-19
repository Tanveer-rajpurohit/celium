package com.lagradost.cloudstream3.ui.celium

import kotlin.random.Random

/**
 * Pure picker for the Celium Feed Random button (Requirement 12.6, 12.8).
 * Candidates come only from shelves already loaded in memory, so no network request is made.
 */
object CeliumRandom {
    /** Every loaded title of the current Section's shelves, de-duplicated by url, in shelf order. */
    fun candidates(shelves: List<CeliumShelf>): List<CeliumTile> =
        shelves.flatMap { it.cards }.distinctBy { it.url }

    /** Maps any int onto a candidate; every candidate is reachable for some `r`. Null when empty. */
    fun pickAt(candidates: List<CeliumTile>, r: Int): CeliumTile? =
        if (candidates.isEmpty()) null else candidates[Math.floorMod(r, candidates.size)]

    fun pick(candidates: List<CeliumTile>, random: Random = Random.Default): CeliumTile? =
        pickAt(candidates, random.nextInt())
}
