package com.lagradost.cloudstream3.ui.celium

/**
 * Pure URL-based dedup helpers used by Celium shelves, paging, and the
 * details Recommendations_Row. No Android dependencies, JVM-testable.
 */
object CeliumDedup {

    /**
     * Normalized stable key for a Title URL: trimmed, scheme and host lowercased,
     * query and fragment dropped, trailing '/' removed.
     */
    fun key(url: String): String {
        var s = url.trim()
        // Drop fragment and query (fragment first so '?' inside it is ignored).
        s.indexOf('#').let { if (it >= 0) s = s.substring(0, it) }
        s.indexOf('?').let { if (it >= 0) s = s.substring(0, it) }

        val schemeEnd = s.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = s.substring(0, schemeEnd).lowercase()
            val rest = s.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            val host = (if (slash >= 0) rest.substring(0, slash) else rest).lowercase()
            val path = if (slash >= 0) rest.substring(slash) else ""
            s = "$scheme://$host$path"
        }
        return s.trimEnd('/')
    }

    /**
     * Keeps the first item for each normalized key, and drops every item whose
     * key equals [excludeUrl]'s key (the Title currently being viewed).
     */
    fun <T> distinctByUrl(items: List<T>, excludeUrl: String?, url: (T) -> String): List<T> {
        val excluded = excludeUrl?.let { key(it) }
        val seen = HashSet<String>()
        val out = ArrayList<T>(items.size)
        for (item in items) {
            val k = key(url(item))
            if (k == excluded) continue
            if (seen.add(k)) out.add(item)
        }
        return out
    }

    /** Result of [appendNew]: merged list and how many page items were new. */
    data class Merge<T>(val items: List<T>, val newCount: Int)

    /**
     * Appends only page items whose key is not already in [existing] (or earlier
     * in the page). [existing] is kept unchanged as the prefix. `newCount == 0`
     * signals the page added nothing, so paging should stop.
     */
    fun <T> appendNew(existing: List<T>, page: List<T>, url: (T) -> String): Merge<T> {
        val seen = HashSet<String>(existing.size + page.size)
        existing.forEach { seen.add(key(url(it))) }
        val added = ArrayList<T>()
        for (item in page) {
            if (seen.add(key(url(item)))) added.add(item)
        }
        if (added.isEmpty()) return Merge(existing, 0)
        return Merge(existing + added, added.size)
    }
}
