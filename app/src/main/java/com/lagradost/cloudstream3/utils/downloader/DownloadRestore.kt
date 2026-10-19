package com.lagradost.cloudstream3.utils.downloader

import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper

/**
 * A stored download entry considered for restore on app start.
 *
 * @param id download id (resume id or queue item id).
 * @param wrapper restored wrapper, or null when the entry is corrupt/unrestorable.
 * @param complete true when the file is already fully downloaded.
 */
data class RestoreCandidate(
    val id: Int,
    val wrapper: DownloadQueueWrapper?,
    val complete: Boolean,
)

/** Pure restore planning for the download queue (Requirement 7). No Android dependencies. */
object DownloadRestore {
    /**
     * Valid incomplete entries in order (resume entries first, then queue), unique by id.
     * Entries without a wrapper (corrupt), completed entries, and ids already in [active]
     * are dropped (Requirements 7.1, 7.2, 7.7).
     */
    fun plan(
        resume: List<RestoreCandidate>,
        queued: List<RestoreCandidate>,
        active: Set<Int>,
    ): List<DownloadQueueWrapper> {
        val seen = HashSet<Int>()
        val result = ArrayList<DownloadQueueWrapper>()
        for (candidate in resume + queued) {
            val wrapper = candidate.wrapper ?: continue
            if (candidate.complete) continue
            if (candidate.id in active) continue
            if (!seen.add(candidate.id)) continue
            result.add(wrapper)
        }
        return result
    }

    /** The queue service starts only when there is real work to restore. */
    fun shouldStartService(restored: List<DownloadQueueWrapper>): Boolean = restored.isNotEmpty()
}
