package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.mvvm.logError
import kotlin.coroutines.cancellation.CancellationException

/**
 * Feed cache for Home and Section feeds (Req 6.1, 6.3, 6.6, 6.8, 14.9).
 * Reuses [CeliumSnapshot] (savedAt + Shelves in pool order + loaded slots) and the existing
 * Celium prefs storage (getKey/setKey). Call [read]/[write] off the main thread.
 */
object CeliumFeedCache {
    /** A snapshot younger than 6 h is published at once; older ones show Placeholders. */
    const val FRESH_MS: Long = 6 * 60 * 60 * 1000L

    /** Pure freshness rule: true when 0 ≤ now − savedAt < [FRESH_MS]. */
    fun isFresh(savedAt: Long, now: Long): Boolean = now >= savedAt && now - savedAt < FRESH_MS

    fun isFresh(snapshot: CeliumSnapshot, now: Long): Boolean = isFresh(snapshot.savedAt, now)

    /** Returns the stored snapshot, or null when missing or corrupt. */
    fun read(key: String): CeliumSnapshot? = try {
        getKey<CeliumSnapshot>(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logError(e)
        null
    }

    /** Overwrites the stored snapshot. Call only after a refresh with at least one successful provider. */
    fun write(key: String, snapshot: CeliumSnapshot) {
        try {
            setKey(key, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
        }
    }
}
