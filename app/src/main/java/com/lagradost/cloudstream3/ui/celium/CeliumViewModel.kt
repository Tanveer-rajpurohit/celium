package com.lagradost.cloudstream3.ui.celium

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SearchRanking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class CeliumTile(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    val year: Int? = null,
) : SearchResponse {
    @Transient override var id: Int? = null
    @Transient override var quality: SearchQuality? = null
    @Transient override var score: Score? = null

    companion object {
        fun from(card: SearchResponse) = CeliumTile(card.name, card.url, card.apiName, card.type, card.posterUrl, card.posterHeaders?.toMap(), when (card) {
            is CeliumTile -> card.year
            is MovieSearchResponse -> card.year
            is TvSeriesSearchResponse -> card.year
            is AnimeSearchResponse -> card.year
            else -> null
        })
    }
}

@Serializable
data class CeliumShelf(
    val key: String, val title: String, val cards: List<CeliumTile>,
    val sectionIndex: Int, val page: Int = 1, val hasNext: Boolean = false,
    val sourceId: String? = null,
)

@Serializable
data class CeliumSnapshot(val savedAt: Long, val shelves: List<CeliumShelf>) {
    fun usable(now: Long): Boolean = now >= savedAt && now - savedAt < 86_400_000L
    fun fresh(now: Long): Boolean = usable(now) && now - savedAt < 900_000L
}

data class CeliumSourceStatus(val source: CeliumSource, val installed: Boolean, val hasFeed: Boolean)

data class CeliumState(
    val section: CeliumSection = CeliumSection.Movies,
    val source: CeliumSource = CeliumSources.selected(CeliumSection.Movies, null),
    val sources: List<CeliumSourceStatus> = emptyList(),
    val shelves: List<CeliumShelf> = emptyList(),
    val resume: List<SearchResponse> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val cachedAt: Long? = null,
    val query: String = "",
    val results: List<CeliumTile> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val searchPage: Int = 1,
    val searchHasNext: Boolean = false,
    val searchError: String? = null,
    val paging: Set<String> = emptySet(),
    val adultEnabled: Boolean = false,
    val remainingSections: Int = 0,
)

class CeliumViewModel : ViewModel() {
    private val _state = MutableStateFlow(CeliumState())
    val state = _state.asStateFlow()
    private val permits = Semaphore(2)
    private val repositories = mutableMapOf<MainAPI, APIRepository>()
    private var feedJob: Job? = null
    private var searchJob: Job? = null
    private var resumeJob: Job? = null
    private val pageJobs = mutableMapOf<String, Job>()
    private var generation = 0
    private var searchGeneration = 0
    private var sectionLimit = 8
    private var boundApi: MainAPI? = null
    private var feedSignature = ""
    private val queryCache = linkedMapOf<Triple<CeliumSection, String, String>, Pair<Long, List<CeliumTile>>>()

    init {
        val section = CeliumSection.entries.firstOrNull { it.name == getKey<String>("celium_section") } ?: CeliumSection.Movies
        _state.value = CeliumState(section = section,
            source = CeliumSources.selected(section, getKey("celium_source_${section.name}")))
        CeliumSources.ready += ::sourcePolicyChanged
    }

    private fun sourcePolicyChanged(@Suppress("UNUSED_PARAMETER") force: Boolean) {
        viewModelScope.launch { sourcesChanged() }
    }

    override fun onCleared() {
        CeliumSources.ready -= ::sourcePolicyChanged
        super.onCleared()
    }

    private fun providers(): List<MainAPI> = APIHolder.apis.withLock { APIHolder.apis.toList() }
    private fun repo(api: MainAPI): APIRepository = repositories.getOrPut(api) { APIRepository(api) }
    private fun key(section: CeliumSection, source: CeliumSource) = "celium_feed_v1_${section.name}_${source.id}"

    fun sourcesChanged() {
        if (!MainAPI.settingsForProvider.enableAdult && _state.value.section == CeliumSection.Adult) {
            selectSection(CeliumSection.Movies)
            return
        }
        val current = _state.value
        if (current.source !in CeliumSources.choices(current.section)) {
            select(current.section, CeliumSources.selected(current.section, null))
            return
        }
        val available = providers()
        val signature = available.joinToString { "${it.name}:${it.sourcePlugin}" }
        _state.value = current.copy(adultEnabled = MainAPI.settingsForProvider.enableAdult, sources = CeliumSources.choices(current.section).map { source ->
            val api = CeliumSources.resolve(source, available)
            CeliumSourceStatus(source, source.id == "celium" || api != null, source.id == "celium" || api?.hasMainPage == true)
        })
        val resolved = CeliumSources.resolve(current.source, available)
        if (feedJob == null || (current.source.id != "celium" && resolved !== boundApi) || (current.source.id == "celium" && signature != feedSignature)) {
            feedSignature = signature
            refresh()
        }
        loadResume()
    }

    fun selectSection(section: CeliumSection) {
        if (section == CeliumSection.Adult && !MainAPI.settingsForProvider.enableAdult) return
        if (section == _state.value.section) return
        select(section, CeliumSources.selected(section, getKey("celium_source_${section.name}")))
    }

    fun selectSource(source: CeliumSource) {
        if (source !in CeliumSources.choices(_state.value.section)) return
        if (source == _state.value.source) return
        select(_state.value.section, source)
    }

    private fun select(section: CeliumSection, source: CeliumSource) {
        sectionLimit = 8
        val pendingQuery = _state.value.query.takeIf { _state.value.section == section }.orEmpty()
        setKey("celium_section", section.name)
        setKey("celium_source_${section.name}", source.id)
        _state.value = CeliumState(section = section, source = source, adultEnabled = MainAPI.settingsForProvider.enableAdult)
        load()
        sourcesChanged()
        if (pendingQuery.isNotBlank()) query(pendingQuery)
    }

    fun refresh() {
        val pendingQuery = _state.value.query
        load()
        if (pendingQuery.isNotBlank()) query(pendingQuery)
    }

    fun forceRefresh() {
        val pendingQuery = _state.value.query
        load(true)
        if (pendingQuery.isNotBlank()) query(pendingQuery)
    }

    fun moreSections() {
        if (_state.value.busy || _state.value.remainingSections == 0) return
        sectionLimit += 8
        load(force = true, extend = true)
    }

    fun load(force: Boolean = false, extend: Boolean = false) {
        if (_state.value.source.id == "celium") { loadCurated(force); return }
        val token = ++generation
        feedJob?.cancel()
        searchJob?.cancel()
        searchGeneration++
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        val current = _state.value
        val api = CeliumSources.resolve(current.source, providers())
        boundApi = api
        _state.value = current.copy(busy = true, message = null, paging = emptySet(), query = "", results = emptyList(), searching = false, searched = false)
        feedJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { getKey<CeliumSnapshot>(key(current.section, current.source)) }
                if (token != generation) return@launch
                val now = System.currentTimeMillis()
                if (!extend && snapshot?.usable(now) == true) {
                    _state.value = _state.value.copy(shelves = snapshot.shelves, cachedAt = snapshot.savedAt)
                }
                if (api == null) {
                    _state.value = _state.value.copy(message = current.source.unavailableReason ?: "Source is not loaded yet. Install selected sources, or choose another source.")
                    return@launch
                }
                if (!api.hasMainPage) {
                    _state.value = _state.value.copy(message = "This adapter supports search but does not provide a home feed.")
                    return@launch
                }
                _state.value = _state.value.copy(remainingSections = (api.mainPage.size - sectionLimit).coerceAtLeast(0))
                if (!force && snapshot?.fresh(now) == true) return@launch
                val repository = repo(api)
                // Fetch sections progressively; never wait for hero detail extraction to show a shelf.
                val existing = if (extend) current.shelves else emptyList()
                val existingIndices = existing.map { it.sectionIndex }.toSet()
                val sections = repository.mainPage.indices.take(sectionLimit).filter { it !in existingIndices }.ifEmpty {
                    if (repository.mainPage.isEmpty()) listOf(-1) else emptyList()
                }
                val loaded = existing.toMutableList()
                val completed = mutableSetOf<Int>()
                val previous = snapshot?.takeIf { it.usable(now) }?.shelves.orEmpty()
                val deadline = System.nanoTime() + 30_000_000_000L
                var failures = 0
                for (index in sections) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) { failures += sections.size - completed.size - failures; break }
                    val response = withTimeoutOrNull(minOf(remaining, 12_000L)) {
                        permits.withPermit {
                            withContext(Dispatchers.IO) {
                                if (api.sequentialMainPage && index != sections.first()) delay(api.sequentialMainPageDelay)
                                repository.getMainPage(1, index.takeIf { it >= 0 }, forceRefresh = force)
                            }
                        }
                    }
                    if (token != generation) return@launch
                    if (response is Resource.Success) {
                        completed += index
                        response.value.filterNotNull().forEach { page ->
                            page.items.forEachIndexed { rowIndex, row ->
                                val cards = CeliumSources.filter(current.section, row.list).take(80).map(CeliumTile::from)
                                if (cards.isNotEmpty()) loaded += CeliumShelf("$index:$rowIndex:${row.name}", row.name, cards, index, hasNext = page.hasNext)
                            }
                        }
                    val merged = (loaded + previous.filter { it.sectionIndex !in completed && it.sectionIndex !in existingIndices }).distinctBy { it.key }.sortedBy { it.sectionIndex }
                        _state.value = _state.value.copy(shelves = merged, cachedAt = if (merged.size > loaded.size) snapshot?.savedAt else null)
                    } else failures++
                }
                // Do not mark retained failed sections fresh by overwriting their timestamp.
                if (loaded.isNotEmpty() && failures == 0) {
                    val stored = CeliumSnapshot(System.currentTimeMillis(), loaded.map { it.copy(cards = it.cards.take(80)) })
                    withContext(Dispatchers.IO) { setKey(key(current.section, current.source), stored) }
                }
                if (token == generation) _state.value = _state.value.copy(message = when {
                    loaded.isEmpty() -> "No ${current.section.label.lowercase()} feed returned. Try search or choose another source."
                    failures > 0 -> "$failures feed sections could not load. Available sections are shown."
                    else -> null
                })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                logError(e)
                if (token == generation) _state.value = _state.value.copy(message = "Feed could not refresh. Retry or choose another source.")
            } finally {
                if (token == generation) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun more(shelf: CeliumShelf) {
        if (!shelf.hasNext || pageJobs[shelf.key]?.isActive == true) return
        val api = shelf.sourceId?.let { id -> CeliumSources.all.firstOrNull { it.id == id }?.let { CeliumSources.resolve(it, providers()) } } ?: boundApi ?: return
        val repository = repo(api)
        val current = _state.value
        val token = generation
        _state.value = current.copy(paging = current.paging + shelf.key)
        pageJobs[shelf.key] = viewModelScope.launch {
            try {
                val response = withTimeoutOrNull(15_000L) {
                    permits.withPermit { withContext(Dispatchers.IO) {
                        repository.waitForHomeDelay()
                        repository.getMainPage(shelf.page + 1, shelf.sectionIndex.takeIf { it >= 0 })
                    } }
                }
                if (token != generation) return@launch
                if (response is Resource.Success) {
                    val matching = response.value.filterNotNull().flatMap { page -> page.items.filter { it.name == shelf.title }.map { it to page.hasNext } }
                    val extra = CeliumSources.filter(current.section, matching.flatMap { it.first.list }).map(CeliumTile::from)
                    val replacement = shelf.copy(cards = (shelf.cards + extra).distinctBy { it.apiName to it.url },
                        page = shelf.page + 1, hasNext = matching.any { it.second } && extra.any { next -> shelf.cards.none { it.apiName == next.apiName && it.url == next.url } })
                    _state.value = _state.value.copy(shelves = _state.value.shelves.map { if (it.key == shelf.key) replacement else it }, message = null)
                } else _state.value = _state.value.copy(message = "More titles could not load. You can retry.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                logError(e)
                if (token == generation) _state.value = _state.value.copy(message = "More titles could not load. You can retry.")
            } finally {
                if (token == generation) _state.value = _state.value.copy(paging = _state.value.paging - shelf.key)
            }
        }
    }

    fun query(value: String) {
        searchJob?.cancel()
        val token = ++searchGeneration
        val current = _state.value
        val query = SearchRanking.cleanQuery(value).take(200)
        _state.value = current.copy(query = value.take(200), results = emptyList(), searching = query.length >= 2,
            searched = false, searchPage = 1, searchHasNext = false, searchError = null)
        if (query.length < 2) return
        val api = boundApi ?: curatedProviders(current.section).firstOrNull()?.second
        searchJob = viewModelScope.launch {
            delay(350)
            if (token != searchGeneration) return@launch
            val local = withContext(Dispatchers.Default) {
                SearchRanking.rank(query, current.shelves.flatMap { it.cards })
                    .filter { SearchRanking.score(query, it.name) > 0 }.map(CeliumTile::from)
            }
            if (token != searchGeneration) return@launch
            _state.value = _state.value.copy(results = local)
            if (api == null) {
                _state.value = _state.value.copy(searching = false, searched = true, searchError = "Selected source is not loaded. Choose an installed source.")
                return@launch
            }
            val cacheKey = Triple(current.section, current.source.id, query)
            val cached = queryCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.first < 600_000L) {
                _state.value = _state.value.copy(results = cached.second, searching = false, searched = true)
                return@launch
            }
            runSearch(api, query, 1, token, local)
        }
    }

    fun moreSearch() {
        val current = _state.value
        if (!current.searchHasNext || current.searching) return
        val api = boundApi ?: return
        val token = searchGeneration
        _state.value = current.copy(searching = true, searchError = null)
        searchJob = viewModelScope.launch { runSearch(api, SearchRanking.cleanQuery(current.query), current.searchPage + 1, token, current.results) }
    }

    private suspend fun runSearch(api: MainAPI, query: String, page: Int, token: Int, existing: List<CeliumTile>) {
        val current = _state.value
        val repository = repo(api)
        try {
            val result = withTimeoutOrNull(15_000L) {
                permits.withPermit { withContext(Dispatchers.IO) { repository.search(query, page) } }
            }
            if (token != searchGeneration) return
            if (result is Resource.Success) {
                val cards = withContext(Dispatchers.Default) {
                    SearchRanking.rank(query, existing + CeliumSources.filter(current.section, result.value.items)).map(CeliumTile::from)
                }
                if (token != searchGeneration) return
                val hasMore = result.value.hasNext && result.value.items.isNotEmpty() && (page == 1 || cards.size > existing.size)
                _state.value = _state.value.copy(results = cards, searched = true, searchPage = page, searchHasNext = hasMore)
                // Paginated queries are not cached: their continuation cursor must remain intact.
                if (page == 1 && !result.value.hasNext) {
                    queryCache[Triple(current.section, current.source.id, query)] = System.currentTimeMillis() to cards
                    while (queryCache.size > 24) queryCache.remove(queryCache.keys.first())
                }
            } else _state.value = _state.value.copy(searched = true, searchError = "Search failed on ${current.source.label}. Retry or change source.")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            logError(e)
            if (token == searchGeneration) _state.value = _state.value.copy(searchError = "Search failed. You can retry.", searched = true)
        } finally {
            if (token == searchGeneration) _state.value = _state.value.copy(searching = false)
        }
    }


    private fun curatedProviders(section: CeliumSection): List<Pair<CeliumSource, MainAPI>> {
        val available = providers()
        return CeliumSources.choices(section).asSequence()
            .filter { it.id != "celium" }
            .mapNotNull { source -> CeliumSources.resolve(source, available)?.let { source to it } }
            .take(3).toList()
    }

    private fun loadCurated(force: Boolean) {
        val token = ++generation
        feedJob?.cancel()
        searchJob?.cancel()
        searchGeneration++
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        val current = _state.value
        val pool = curatedProviders(current.section)
        boundApi = pool.firstOrNull()?.second
        _state.value = current.copy(busy = true, message = null, paging = emptySet(), query = "", results = emptyList(),
            searching = false, searched = false, remainingSections = 0)
        feedJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { getKey<CeliumSnapshot>(key(current.section, current.source)) }
                if (token != generation) return@launch
                if (snapshot?.usable(System.currentTimeMillis()) == true) _state.value = _state.value.copy(shelves = snapshot.shelves, cachedAt = snapshot.savedAt)
                if (pool.isEmpty()) {
                    _state.value = _state.value.copy(message = "Sources are preparing. Refresh the feed once they are ready.")
                    return@launch
                }
                if (!force && snapshot?.fresh(System.currentTimeMillis()) == true) return@launch
                val shelves = mutableListOf<CeliumShelf>()
                val seen = mutableSetOf<String>()
                val deadline = System.nanoTime() + 30_000_000_000L
                var failures = 0
                for ((source, api) in pool) {
                    if (!api.hasMainPage) continue
                    val repository = repo(api)
                    // Use a small audited source pool. A catalog must never turn into global fan-out.
                    val sections = repository.mainPage.indices.take(3).toList().ifEmpty { listOf(-1) }
                    for (index in sections) {
                        val remaining = (deadline - System.nanoTime()) / 1_000_000L
                        if (remaining <= 0) { failures++; break }
                        val response = withTimeoutOrNull(minOf(remaining, 8_000L)) {
                            permits.withPermit { withContext(Dispatchers.IO) {
                                repository.waitForHomeDelay()
                                repository.getMainPage(1, index.takeIf { it >= 0 }, forceRefresh = force)
                            } }
                        }
                        if (token != generation) return@launch
                        if (response !is Resource.Success) { failures++; continue }
                        response.value.filterNotNull().forEach { page ->
                            page.items.forEachIndexed { rowIndex, row ->
                                val cards = CeliumSources.filter(current.section, row.list).map(CeliumTile::from).filter { seen.add(CeliumSources.catalogIdentity(it)) }.take(40)
                                if (cards.isNotEmpty()) shelves += CeliumShelf("${source.id}:$index:$rowIndex", row.name, cards, index, hasNext = page.hasNext, sourceId = source.id)
                            }
                        }
                        _state.value = _state.value.copy(shelves = shelves.toList(), cachedAt = null)
                    }
                }
                if (shelves.isNotEmpty() && failures == 0) withContext(Dispatchers.IO) {
                    setKey(key(current.section, current.source), CeliumSnapshot(System.currentTimeMillis(), shelves))
                }
                if (token == generation) _state.value = _state.value.copy(message = when {
                    shelves.isEmpty() -> "No feed returned. Choose an individual source or try Search."
                    failures > 0 -> "Some collections are unavailable. Available titles are shown."
                    else -> null
                })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                logError(e)
                if (token == generation) _state.value = _state.value.copy(message = "The feed could not refresh. Try an individual source.")
            } finally {
                if (token == generation) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun loadResume() {
        resumeJob?.cancel()
        val current = _state.value
        val token = generation
        resumeJob = viewModelScope.launch {
            try {
                val resume = HomeViewModel.getResumeWatching().orEmpty()
                if (token != generation) return@launch
                val names = if (current.source.id == "celium") curatedProviders(current.section).map { it.second.name }.toSet() else setOfNotNull(boundApi?.name)
                _state.value = _state.value.copy(resume = resume.filter { it.apiName in names && CeliumSources.accepts(current.section, it.type) }.take(20))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e) }
        }
    }
}
