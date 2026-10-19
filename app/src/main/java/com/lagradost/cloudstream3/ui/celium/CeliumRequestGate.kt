package com.lagradost.cloudstream3.ui.celium

import kotlinx.coroutines.Job

/**
 * Generation-token gate for Celium loads (Req 4.5, 10.3, 10.5, 10.6).
 *
 * Each call to [next] for a key cancels the Job registered by the previous call for
 * that key and returns a new, strictly increasing token. A result is only applied
 * when [isCurrent] confirms its token is still the latest for the key, so stale
 * results from cancelled or superseded requests are dropped.
 *
 * Thread-safe: all state is guarded by a single lock.
 */
class CeliumRequestGate {
    private val lock = Any()
    private var counter = 0L
    private val tokens = HashMap<String, Long>()
    private val jobs = HashMap<String, Job>()

    /** Cancels the previous Job for [key], registers [job] (if any), and returns a new token. */
    fun next(key: String, job: Job? = null): Long {
        val previous: Job?
        val token: Long
        synchronized(lock) {
            token = ++counter
            tokens[key] = token
            previous = if (job != null) jobs.put(key, job) else jobs.remove(key)
        }
        // Cancel outside the lock so completion handlers cannot deadlock against the gate.
        if (previous != null && previous !== job) previous.cancel()
        return token
    }

    /** True when [token] is the latest token issued for [key]. */
    fun isCurrent(key: String, token: Long): Boolean =
        synchronized(lock) { tokens[key] == token }
}
