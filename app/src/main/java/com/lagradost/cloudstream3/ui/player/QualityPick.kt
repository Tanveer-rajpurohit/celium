package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.Qualities

/**
 * Minimal, Android-free description of a playable link used for quality ranking.
 *
 * @param key stable link key, e.g. "${link.name}|${link.url}"
 * @param height ranking height, see [QualityPick.height]
 * @param downloadable true when the link can be downloaded in-app
 */
data class LinkInfo(val key: String, val height: Int, val downloadable: Boolean)

/** Pure highest-quality selection (Requirement 18). */
object QualityPick {
    /** Unknown quality ([Qualities.Unknown], value 400) ranks as 0 so known resolutions win. */
    fun height(quality: Int): Int = if (quality == Qualities.Unknown.value) 0 else quality

    /** Highest height first; `sortedByDescending` is stable, so ties keep source order. */
    fun order(links: List<LinkInfo>): List<LinkInfo> = links.sortedByDescending { it.height }

    /**
     * First link in source order with maximal height among links not in [failed]
     * (and downloadable when [downloadOnly]), or null when none qualify.
     */
    fun best(
        links: List<LinkInfo>,
        failed: Set<String> = emptySet(),
        downloadOnly: Boolean = false,
    ): LinkInfo? {
        var best: LinkInfo? = null
        for (link in links) {
            if (link.key in failed) continue
            if (downloadOnly && !link.downloadable) continue
            // Strict '>' keeps the earliest link on ties.
            if (best == null || link.height > best.height) best = link
        }
        return best
    }

    /** The playing link when it is downloadable, otherwise the best downloadable link. */
    fun preselect(links: List<LinkInfo>, playingKey: String?): LinkInfo? {
        val playing = playingKey?.let { key -> links.firstOrNull { it.key == key } }
        return if (playing != null && playing.downloadable) playing else best(links, downloadOnly = true)
    }
}
