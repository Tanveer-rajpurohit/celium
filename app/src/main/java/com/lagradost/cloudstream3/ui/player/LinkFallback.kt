package com.lagradost.cloudstream3.ui.player

/**
 * State of one playback attempt over the Player_Source_List (Requirement 17).
 *
 * @param order   link keys in Player_Source_List order (QualityPick.order)
 * @param tried   keys started during this attempt (auto or manual); never auto-started again
 * @param failed  keys that had a Link failure during this attempt (shown as "failed")
 * @param current key that is playing or loading, null when nothing is left to try
 */
data class LinkAttempt(
    val order: List<String>,
    val tried: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    val current: String? = null,
)

/** Pure link fallback model. No Android imports; unit-tested on the JVM. */
object LinkFallback {
    /** A link that shows no first frame within this time counts as a Link failure. */
    const val FIRST_FRAME_TIMEOUT_MS = 15_000L

    /** First untried key in [LinkAttempt.order], or null when all were tried. */
    private fun nextUntried(a: LinkAttempt): String? = a.order.firstOrNull { it !in a.tried }

    /** Starts an attempt with the first link in order (17.2). Returns the key to play, or null. */
    fun start(order: List<String>): Pair<LinkAttempt, String?> {
        val first = order.firstOrNull()
        val attempt = LinkAttempt(
            order = order,
            tried = if (first != null) setOf(first) else emptySet(),
            current = first,
        )
        return attempt to first
    }

    /** Replaces the order while links are still arriving; keeps tried, failed and current. */
    fun withOrder(a: LinkAttempt, order: List<String>): LinkAttempt = a.copy(order = order)

    /**
     * Marks the current link as failed and picks the first untried link in order (17.1, 17.7).
     * Returns null as the next key once every link was tried. When [LinkAttempt.current] is
     * null (fresh attempt from [retry]), this simply picks the first untried link.
     */
    fun onFailure(a: LinkAttempt): Pair<LinkAttempt, String?> {
        val failed = a.current?.let { a.failed + it } ?: a.failed
        val marked = a.copy(failed = failed)
        val next = nextUntried(marked)
        val updated = marked.copy(
            tried = if (next != null) marked.tried + next else marked.tried,
            current = next,
        )
        return updated to next
    }

    /** The user picked a link (also a failed one, 17.5). Failed marks are kept (17.4). */
    fun onUserSelect(a: LinkAttempt, key: String): LinkAttempt =
        a.copy(current = key, tried = a.tried + key)

    /** New playback attempt after Retry: no tried or failed marks, nothing playing (17.8). */
    fun retry(order: List<String>): LinkAttempt = LinkAttempt(order = order)
}
