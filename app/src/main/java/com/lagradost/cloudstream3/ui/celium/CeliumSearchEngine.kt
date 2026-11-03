package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.search.ParsedQuery
import com.lagradost.cloudstream3.ui.search.SearchRanking
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One source a category search asks. [order] is its priority (lower first), used for ties. */
internal data class SearchTarget(val id: String, val label: String, val order: Int)

/** What the app-side fetch returns for one source and page; the engine adds timeout, cache and health around it. */
internal sealed interface FetchResult {
    data class Ok(val items: List<SearchResponse>, val hasNext: Boolean) : FetchResult
    object Failed : FetchResult
}

/** Public because the search UI state ([CeliumSearchSourceUi]) carries it. */
enum class SourceState { Pending, Done, Failed, TimedOut, Skipped }

internal data class SourceStatus(
    val target: SearchTarget,
    val state: SourceState = SourceState.Pending,
    val count: Int = 0,
    val hasNext: Boolean = false,
    /** Last page that landed; 0 before the first. */
    val page: Int = 0,
    val cached: Boolean = false,
)

internal data class SourceOutcome(
    val target: SearchTarget,
    val page: Int,
    val state: SourceState,
    val items: List<SearchResponse> = emptyList(),
    val hasNext: Boolean = false,
    val fromCache: Boolean = false,
)

/** Starting values from Plan 1; tune them on a phone. */
internal data class SearchConfig(
    /** One budget per source, covering its request and the one relaxed retry. */
    val sourceTimeoutMs: Long = 10_000L,
    val cacheTtlMs: Long = 600_000L,
    val emptyTtlMs: Long = 120_000L,
    val cacheEntries: Int = 100,
    /** Failed or timed-out searches in a row before a source is skipped. */
    val failureLimit: Int = 3,
    val skipMs: Long = 300_000L,
)

/** Per-source answer cache keyed by (source, normalized query, page); least recently used, with a short life for empty answers. */
internal class SearchSourceCache(
    private val clock: () -> Long,
    private val ttlMs: Long,
    private val emptyTtlMs: Long,
    private val maxEntries: Int,
) {
    data class Key(val sourceId: String, val query: String, val page: Int)
    private class Entry(val at: Long, val result: FetchResult.Ok)

    private val map = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: Key): FetchResult.Ok? {
        val entry = map[key] ?: return null
        val ttl = if (entry.result.items.isEmpty()) emptyTtlMs else ttlMs
        if (clock() - entry.at >= ttl) { map.remove(key); return null }
        return entry.result
    }

    @Synchronized fun put(key: Key, result: FetchResult.Ok) { map[key] = Entry(clock(), result) }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size
}

/** Skips a source after repeated failures, so a dead source stops costing every search its full wait. */
internal class SearchSourceHealth(
    private val clock: () -> Long,
    private val failureLimit: Int,
    private val skipMs: Long,
) {
    private val streak = HashMap<String, Int>()
    private val skipUntil = HashMap<String, Long>()

    @Synchronized fun isSkipped(id: String): Boolean {
        val until = skipUntil[id] ?: return false
        if (clock() >= until) { skipUntil.remove(id); streak.remove(id); return false }
        return true
    }

    @Synchronized fun success(id: String) { streak.remove(id); skipUntil.remove(id) }

    @Synchronized fun failure(id: String) {
        val n = (streak[id] ?: 0) + 1
        if (n >= failureLimit) { skipUntil[id] = clock() + skipMs; streak.remove(id) } else streak[id] = n
    }

    /** A manual retry clears every skip. */
    @Synchronized fun reset() { streak.clear(); skipUntil.clear() }
}

/**
 * Asks many sources at once and reports each as it finishes (Celium search, steps 6 and 7).
 * A slow or failing source never blocks or cancels another. Cancelling the collector cancels every request.
 * The Android parts (provider lookup, permits, the repository call) are passed in as [fetch].
 */
internal class CeliumSearchEngine(
    private val fetch: suspend (target: SearchTarget, query: String, page: Int) -> FetchResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: SearchConfig = SearchConfig(),
) {
    val cache = SearchSourceCache(clock, config.cacheTtlMs, config.emptyTtlMs, config.cacheEntries)
    val health = SearchSourceHealth(clock, config.failureLimit, config.skipMs)

    private fun key(target: SearchTarget, query: String, page: Int) =
        SearchSourceCache.Key(target.id, SearchRanking.cleanQuery(query).lowercase(Locale.ROOT), page)

    /** One outcome per target, in completion order. [pageOf] gives the page each target should load. */
    fun run(targets: List<SearchTarget>, parsed: ParsedQuery, pageOf: (SearchTarget) -> Int = { 1 }): Flow<SourceOutcome> =
        channelFlow {
            for (target in targets) launch { send(searchOne(target, parsed, pageOf(target))) }
        }

    private suspend fun searchOne(target: SearchTarget, parsed: ParsedQuery, page: Int): SourceOutcome {
        // The title goes first; if a source finds nothing for it, the text as typed gets one try (page 1 only).
        val first = parsed.title.ifBlank { parsed.raw }
        val queries = if (page == 1 && parsed.raw.isNotBlank() && parsed.raw != first) listOf(first, parsed.raw) else listOf(first)
        if (cache.get(key(target, first, page)) == null && health.isSkipped(target.id)) {
            return SourceOutcome(target, page, SourceState.Skipped)
        }
        val outcome = withTimeoutOrNull(config.sourceTimeoutMs) { attempts(target, queries, page) }
        if (outcome == null) {
            health.failure(target.id)
            return SourceOutcome(target, page, SourceState.TimedOut)
        }
        return outcome
    }

    private suspend fun attempts(target: SearchTarget, queries: List<String>, page: Int): SourceOutcome {
        var last: SourceOutcome? = null
        for (query in queries) {
            val key = key(target, query, page)
            val hit = cache.get(key)
            val result: FetchResult = hit ?: fetchSafely(target, query, page)
            when (result) {
                is FetchResult.Ok -> {
                    if (hit == null) { cache.put(key, result); health.success(target.id) }
                    last = SourceOutcome(target, page, SourceState.Done, result.items, result.hasNext, fromCache = hit != null)
                    if (result.items.isNotEmpty()) return last
                }
                FetchResult.Failed -> {
                    health.failure(target.id)
                    return last ?: SourceOutcome(target, page, SourceState.Failed)
                }
            }
        }
        return last ?: SourceOutcome(target, page, SourceState.Failed)
    }

    private suspend fun fetchSafely(target: SearchTarget, query: String, page: Int): FetchResult =
        try { fetch(target, query, page) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { FetchResult.Failed }
}

/**
 * The running state of one search: per-source status and cursor, and every distinct hit so far.
 * Used from one coroutine at a time (the collector), so it needs no locking.
 */
internal class SearchSession(val targets: List<SearchTarget>, val parsed: ParsedQuery) {
    private val byId = LinkedHashMap<String, SourceStatus>().also { m -> targets.forEach { m[it.id] = SourceStatus(it) } }
    private val seen = HashSet<String>()
    private val all = ArrayList<SearchHit>()
    /** Whether each hit belongs to the category, decided once per hit instead of on every screen update. */
    val allowed = java.util.IdentityHashMap<SearchResponse, Boolean>()

    val statuses: List<SourceStatus> get() = byId.values.toList()
    val hits: List<SearchHit> get() = all.toList()
    val pending: Boolean get() = byId.values.any { it.state == SourceState.Pending }
    val answered: Int get() = byId.values.count { it.state != SourceState.Pending }

    private fun add(hit: SearchHit): Boolean =
        seen.add(hit.item.apiName + "\u0000" + CeliumDedup.key(hit.item.url)).also { if (it) all.add(hit) }

    /** Adds results already on screen (loaded shelves) so they are never lost; they do not count as a source. */
    fun seed(items: List<SearchResponse>) { items.forEach { add(SearchHit(it, "", Int.MAX_VALUE)) } }

    /** Applies one source's outcome and returns how many new hits it added. */
    fun apply(o: SourceOutcome): Int {
        val prev = byId[o.target.id] ?: return 0
        return when {
            o.state == SourceState.Done -> {
                var added = 0
                for (item in o.items) if (add(SearchHit(item, o.target.id, o.target.order))) added++
                // A page that adds nothing new ends that source, as the shelves do (CeliumPaging.statusAfter).
                byId[o.target.id] = prev.copy(state = SourceState.Done, count = prev.count + added,
                    hasNext = o.hasNext && added > 0, page = o.page, cached = o.fromCache)
                added
            }
            // A failed later page keeps what page 1 gave; the source just has no more.
            o.page > 1 -> { byId[o.target.id] = prev.copy(state = SourceState.Done, hasNext = false); 0 }
            else -> { byId[o.target.id] = prev.copy(state = o.state, page = o.page); 0 }
        }
    }

    /** Sources that finished with a next page available. */
    fun nextTargets(): List<SearchTarget> = byId.values.filter { it.state == SourceState.Done && it.hasNext }.map { it.target }

    /** Marks sources as loading their next page. */
    fun beginNextPage(next: List<SearchTarget>) {
        next.forEach { t -> byId[t.id]?.let { byId[t.id] = it.copy(state = SourceState.Pending) } }
    }

    fun pageToLoad(target: SearchTarget): Int = (byId[target.id]?.page ?: 0) + 1
}
