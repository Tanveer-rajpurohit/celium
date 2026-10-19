package com.lagradost.cloudstream3.ui.celium

import androidx.compose.ui.unit.Dp

/**
 * User-selectable card size for Celium shelves and grids (Requirement 9).
 * [factor] scales the Medium card width; grid column counts derive from the scaled width.
 */
enum class CeliumLayoutSize(val key: String, val factor: Float) {
    Small("small", 0.8f),
    Medium("medium", 1f),
    Large("large", 1.25f);

    /** Scales the Medium card width by this size's factor. */
    fun cardWidth(medium: Dp): Dp = medium * factor

    companion object {
        /** Resolves a stored preference key; unknown or missing keys fall back to [Medium]. */
        fun fromKey(k: String?): CeliumLayoutSize =
            entries.firstOrNull { it.key == k } ?: Medium
    }
}
