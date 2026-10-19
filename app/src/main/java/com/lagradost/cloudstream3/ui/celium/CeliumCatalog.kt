package com.lagradost.cloudstream3.ui.celium

/**
 * Pure catalogue logic for loading every main-page category of a provider
 * (or the curated pool) in batches (Requirement 21).
 * No Android dependencies so it can be unit tested on the JVM.
 */

/**
 * One main-page category of a provider. [index] is the position in `api.mainPage`;
 * -1 means the provider has no mainPage list and is loaded as a single default category.
 */
@kotlinx.serialization.Serializable
data class CategorySlot(val providerId: String, val index: Int)

/** Load state of one category slot. A missing map entry is treated as [Pending]. */
enum class SlotStatus { Pending, Loading, Loaded, Empty, Failed }

object CeliumCatalog {
    /** Number of categories requested per batch (Requirements 21.1, 21.2). */
    const val BATCH = 8

    /**
     * Builds the ordered category slots.
     * [providers] holds (providerId, mainPage size) pairs in catalogue order.
     * A single provider yields its categories in `mainPage` order. Several providers
     * (the curated pool) are interleaved round-robin by index, so the feed stays mixed.
     * A provider with no mainPage entries contributes one slot with index -1.
     */
    fun slots(providers: List<Pair<String, Int>>): List<CategorySlot> {
        val perProvider = providers.map { (id, count) ->
            if (count <= 0) listOf(CategorySlot(id, -1))
            else (0 until count).map { CategorySlot(id, it) }
        }
        val rounds = perProvider.maxOfOrNull { it.size } ?: 0
        val result = ArrayList<CategorySlot>(perProvider.sumOf { it.size })
        for (round in 0 until rounds) {
            for (list in perProvider) {
                list.getOrNull(round)?.let(result::add)
            }
        }
        return result.distinct()
    }

    /** The first up to [BATCH] Pending slots, in slot order. */
    fun nextBatch(
        slots: List<CategorySlot>,
        status: Map<CategorySlot, SlotStatus>,
    ): List<CategorySlot> =
        slots.asSequence().filter { statusOf(it, status) == SlotStatus.Pending }.take(BATCH).toList()

    /** How many slots are still Pending (not yet requested). */
    fun remaining(slots: List<CategorySlot>, status: Map<CategorySlot, SlotStatus>): Int =
        slots.count { statusOf(it, status) == SlotStatus.Pending }

    private fun statusOf(slot: CategorySlot, status: Map<CategorySlot, SlotStatus>): SlotStatus =
        status[slot] ?: SlotStatus.Pending
}
