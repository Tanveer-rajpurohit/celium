package com.lagradost.cloudstream3.ui.celium

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SearchQuery
import com.lagradost.cloudstream3.ui.search.SearchRanking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** One source that offers a tile's title, kept so a grouped search result can list where it is available. */
@Serializable
data class CeliumTileSource(val sourceId: String, val apiName: String, val url: String)

@Serializable
data class CeliumTile(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    val year: Int? = null,
    /** Other sources with the same title (search results only). Empty by default, so feeds cached by older builds still load. */
    val sources: List<CeliumTileSource> = emptyList(),
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
data class CeliumSnapshot(
    val savedAt: Long, val shelves: List<CeliumShelf>,
    /** Loaded or Empty category slots, so a restored view continues from the next Pending slot (21.6). */
    val loaded: List<CategorySlot> = emptyList(),
) {
    fun usable(now: Long): Boolean = now >= savedAt && now - savedAt < 86_400_000L
    fun fresh(now: Long): Boolean = usable(now) && now - savedAt < 900_000L
}

data class CeliumSourceStatus(val source: CeliumSource, val installed: Boolean, val hasFeed: Boolean, val reason: String? = null)

/** Progress of one source in the running or last category search. */
data class CeliumSearchSourceUi(val id: String, val label: String, val state: SourceState, val count: Int)

data class CeliumState(
    /** Home_Feed is the landing screen: the Movies/TV Celium Feed with Movies and TV Shows combined (3.1, 15.5). */
    val home: Boolean = true,
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
    /** Per-source progress of the category search, in priority order; one entry when a single source is searched. */
    val searchSources: List<CeliumSearchSourceUi> = emptyList(),
    /** Source id the results are narrowed to by a chip; null shows every result. */
    val searchFilter: String? = null,
    /** False when the Settings switch limits search to the first ready source. */
    val searchAllSources: Boolean = false,
    /** Paging per list: "search", "sections", and each shelf.key. Missing entry = Idle (Requirement 6). */
    val pages: Map<String, ListPaging> = emptyMap(),
    val adultEnabled: Boolean = false,
    /** Pending category slots not yet requested (Requirement 21). */
    val remainingSections: Int = 0,
    /** Every main-page category of the provider or curated pool, in display order (21.1, 21.7). */
    val slots: List<CategorySlot> = emptyList(),
    val slotStatus: Map<CategorySlot, SlotStatus> = emptyMap(),
    val slotTitles: Map<CategorySlot, String> = emptyMap(),
    /** Whether the selected provider is registered (Requirement 16.3, 16.7). */
    val binding: BindStatus = BindStatus.Waiting,
    /** Feed request failure; shown with Retry while existing shelves stay visible (16.8). */
    val feedError: String? = null,
) {
    fun page(key: String): ListPaging = pages[key] ?: ListPaging()
    fun slotOf(shelf: CeliumShelf) = CategorySlot(shelf.sourceId ?: source.id, shelf.sectionIndex)
    /** Categories that failed and show a retry entry (21.8). */
    val failedSlots: List<CategorySlot> get() = slots.filter { slotStatus[it] == SlotStatus.Failed }

    companion object {
        const val SEARCH = "search"
        const val SECTIONS = "sections"
    }
}

/** CeliumRequestGate keys for the feed (provider/category/Section) and the search query. */
private const val GATE_FEED = "feed"
private const val GATE_SEARCH = "search"
/** Per-slot main-page request deadline; a slow provider never holds the others back (6.5, 6.7). */
private const val FEED_TIMEOUT_MS = 15_000L
/** Cache key of the Home_Feed (the Movies/TV Celium Feed, unsplit). */
private const val HOME_CACHE_KEY = "celium_feed_v2_Home_celium"
/** Settings switch: search every ready source of the category (default) or only the first ready one. */
internal const val SEARCH_ALL_KEY = "celium_search_all"
/** Typing waits this long before a search starts; the keyboard's Search key and Retry start at once. */
private const val SEARCH_DEBOUNCE_MS = 250L
/** Shortest query that starts a search by typing; two letters need the Search key. */
private const val SEARCH_MIN_TYPED = 3
/** Results on screen update at most this often while sources are still answering. */
private const val SEARCH_PUBLISH_MS = 400L
/** Most search results kept in memory. */
private const val SEARCH_MAX_RESULTS = 300

@OptIn(FlowPreview::class)
class CeliumViewModel : ViewModel() {
    private val _state = MutableStateFlow(CeliumState())
    val state = _state.asStateFlow()
    /** At most 2 requests per provider at once; different providers run concurrently (6.4). */
    private val providerPermits = mutableMapOf<String, Semaphore>()
    private val repositories = mutableMapOf<MainAPI, APIRepository>()
    private var feedJob: Job? = null
    private var searchJob: Job? = null
    private var resumeJob: Job? = null
    private var bindWatchdog: Job? = null
    private var sectionJob: Job? = null
    private val pageJobs = mutableMapOf<String, Job>()
    /** Every feed and search load runs through this gate; only the latest token per key may apply results (4.5, 10.3–10.6). */
    private val gate = CeliumRequestGate()
    private var generation = 0L
    private var searchGeneration = 0L
    private var boundApi: MainAPI? = null
    /** Providers backing the current catalogue, by source id. */
    private var catalogApis: Map<String, MainAPI> = emptyMap()
    private var catalogForce = false
    private var feedSignature = ""
    private var selectedAt = SystemClock.elapsedRealtime()
    @Volatile private var lastPassAt = 0L
    private var adultDiscarded = false
    /**
     * Category search (Plan 1): asks every ready source of the category at once, with a per-source cache,
     * timeout and health tracking. [searchApis] maps a target id to its provider for the running search.
     */
    private val searchEngine = CeliumSearchEngine(::searchFetch)
    private var searchSession: SearchSession? = null
    @Volatile private var searchApis: Map<String, MainAPI> = emptyMap()

    /** Plugin_Loaded_Event and policy changes, coalesced so a burst of plugin loads binds once. */
    private val pluginSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Fresh start opens Home (3.1); a saved Section view is restored otherwise.
        val home = getKey<Boolean>("celium_home") ?: true
        val section = if (home) CeliumSection.Movies
            else CeliumSection.entries.firstOrNull { it.name == getKey<String>("celium_section") } ?: CeliumSection.Movies
        val savedHomeSourceId = getKey<String>("celium_source_home")
        _state.value = CeliumState(home = home, section = section,
            source = if (home) CeliumSources.selected(CeliumSection.Movies, savedHomeSourceId)
                else CeliumSources.selected(section, getKey("celium_source_${section.name}")))
        // Subscribed for the view model's whole lifetime, not only while a fragment is started (16.4).
        CeliumSources.ready += ::sourcePolicyChanged
        MainActivity.afterPluginsLoadedEvent += ::pluginsLoaded
        viewModelScope.launch { pluginSignal.debounce(250L).collect { sourcesChanged() } }
        startWatchdog()
    }

    // Events fire on background threads; the collector above runs on the main thread.
    private fun sourcePolicyChanged(@Suppress("UNUSED_PARAMETER") force: Boolean) {
        pluginSignal.tryEmit(Unit)
    }

    private fun pluginsLoaded(@Suppress("UNUSED_PARAMETER") force: Boolean) {
        lastPassAt = SystemClock.elapsedRealtime()
        pluginSignal.tryEmit(Unit)
    }

    override fun onCleared() {
        CeliumSources.ready -= ::sourcePolicyChanged
        MainActivity.afterPluginsLoadedEvent -= ::pluginsLoaded
        super.onCleared()
    }

    private fun providers(): List<MainAPI> = APIHolder.apis.withLock { APIHolder.apis.toList() }
    private fun repo(api: MainAPI): APIRepository = repositories.getOrPut(api) { APIRepository(api) }
    private fun permitsFor(api: MainAPI): Semaphore =
        synchronized(providerPermits) { providerPermits.getOrPut(api.name) { Semaphore(2) } }
    private fun key(section: CeliumSection, source: CeliumSource, home: Boolean = false) =
        if (home) "celium_feed_v2_Home_${source.id}" else "celium_feed_v2_${section.name}_${source.id}"
    private fun key(state: CeliumState) = key(state.section, state.source, state.home)

    /** A result may be shown only if its request is current and still for the selected source (16.6, 16.11). */
    private fun live(token: Long, sourceId: String) =
        token == generation && gate.isCurrent(GATE_FEED, token) && _state.value.source.id == sourceId
    private fun liveSearch(token: Long, sourceId: String) =
        token == searchGeneration && gate.isCurrent(GATE_SEARCH, token) && _state.value.source.id == sourceId

    /**
     * Starts [block] as the only current request for [key]: the gate cancels the previous Job for the key
     * and issues a new token before the block runs, so stale results are dropped (10.3, 10.5, 10.6).
     */
    private fun gated(key: String, onToken: (Long) -> Unit, block: suspend CoroutineScope.(Long) -> Unit): Job {
        var token = 0L
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { block(token) }
        token = gate.next(key, job)
        onToken(token)
        job.start()
        return job
    }

    /**
     * Movies/TV Shows Section views (provider or Celium Feed) split each category with the classifier.
     * Home is never split: it keeps the unsplit Celium 6 categories (15.5).
     */
    private fun splitsFeed(home: Boolean, section: CeliumSection) =
        !home && (section == CeliumSection.Movies || section == CeliumSection.Series)

    /**
     * Items of one category (or one search page when [category] is null) that belong to the view.
     * Home accepts every Movies and TV Shows item (3.5). With [classify] the Content_Classifier assigns
     * every item to exactly one of Movies/TV Shows (TV-series type → TV Shows); otherwise the type
     * filter applies (Anime, Adult). Call off the main thread (5.3).
     */
    private fun sectionItems(section: CeliumSection, home: Boolean, classify: Boolean, category: String?, items: List<SearchResponse>): List<SearchResponse> = when {
        home -> CeliumSources.homeFilter(items)
        classify -> CeliumDedup.distinctByUrl(CeliumContentClassifier.split(category.orEmpty(), items)[section].orEmpty(), null) { it.url }
        else -> CeliumSources.filter(section, items)
    }

    private fun startWatchdog() {
        selectedAt = SystemClock.elapsedRealtime()
        bindWatchdog?.cancel()
        bindWatchdog = viewModelScope.launch {
            delay(CeliumBinding.BIND_TIMEOUT_MS)
            sourcesChanged()
        }
    }

    private fun poolSignature(pool: List<Pair<CeliumSource, MainAPI>>) =
        pool.joinToString { "${it.first.id}:${it.second.name}:${System.identityHashCode(it.second)}" }

    /** Adult toggled off: drop in-memory and stored Adult content (Requirement 16.10). */
    private fun discardAdult() {
        if (adultDiscarded) return
        adultDiscarded = true
        // Cached search answers are keyed by source, not section, so clear them all.
        searchEngine.cache.clear()
        val adultSources = listOf(CeliumSources.curated(CeliumSection.Adult)) + CeliumSources.all.filter { it.adult }
        viewModelScope.launch(Dispatchers.IO) {
            try { adultSources.forEach {
                    removeKey(key(CeliumSection.Adult, it))
                    removeKey("celium_feed_v1_${CeliumSection.Adult.name}_${it.id}")
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e) }
        }
    }

    fun sourcesChanged() { syncSources(force = false) }

    /** Refreshes picker entries and re-resolves the selected provider. Returns true if it started a load. */
    private fun syncSources(force: Boolean): Boolean {
        val adult = MainAPI.settingsForProvider.enableAdult
        if (adult) adultDiscarded = false else discardAdult()
        if (!adult && (_state.value.section == CeliumSection.Adult || _state.value.section == CeliumSection.AdultGames)) {
            _state.value = _state.value.copy(shelves = emptyList(), results = emptyList(), resume = emptyList())
            select(CeliumSection.Movies, CeliumSources.selected(CeliumSection.Movies, getKey("celium_source_${CeliumSection.Movies.name}")))
            return true
        }
        val current = _state.value
        if (current.source !in CeliumSources.choices(current.section)) {
            select(current.section, CeliumSources.selected(current.section, null))
            return true
        }
        val available = providers()
        val passDone = PluginManager.loadedOnlinePlugins && PluginManager.loadedLocalPlugins
        _state.value = current.copy(adultEnabled = adult, sources = CeliumSources.choices(current.section).map { source ->
            val api = CeliumSources.resolve(source, available)
            val installed = source.id == "celium" || api != null
            // A reason is shown only once the load pass has finished; before that the source is still preparing.
            val reason = if (installed || !passDone) null
                else source.unavailableReason ?: CeliumSourceHealth.reason(source.id)
            CeliumSourceStatus(source, installed, source.id == "celium" || api?.hasMainPage == true, reason)
        })
        val started = bind(available, passDone, force)
        loadResume()
        return started
    }

    private fun bind(available: List<MainAPI>, passDone: Boolean, force: Boolean): Boolean {
        val current = _state.value
        if (feedJob == null) { reload(force); return true }
        val resolvedName: String?
        val boundName: String?
        if (current.source.id == "celium") {
            val pool = curatedProviders(current.section)
            resolvedName = poolSignature(pool).takeIf { pool.isNotEmpty() }
            boundName = feedSignature.takeIf { it.isNotEmpty() }
        } else {
            val resolved = CeliumSources.resolve(current.source, available)
            resolvedName = resolved?.name
            // A re-registered provider is a new instance: treat it as unbound so it rebinds.
            boundName = boundApi?.takeIf { it === resolved }?.name
        }
        val elapsed = SystemClock.elapsedRealtime() - selectedAt
        val action = CeliumBinding.decide(current.source.label, resolvedName, boundName,
            loadPassDone = passDone && lastPassAt > selectedAt, elapsedMs = elapsed)
        when (action) {
            BindAction.Rebind -> { reload(force); return true }
            BindAction.Keep -> bindWatchdog?.cancel()
            BindAction.Wait -> if (current.binding !is BindStatus.Waiting) _state.value = _state.value.copy(binding = BindStatus.Waiting)
            is BindAction.Fail -> _state.value = _state.value.copy(binding = BindStatus.Failed(action.message), busy = false,
                searching = false, searched = current.query.isNotBlank() || current.searched,
                searchError = if (current.query.isNotBlank()) action.message else current.searchError)
        }
        if (action == BindAction.Keep && current.binding != BindStatus.Ready) _state.value = _state.value.copy(binding = BindStatus.Ready)
        return false
    }

    private fun reload(force: Boolean) {
        val pendingQuery = _state.value.query
        load(force)
        if (pendingQuery.isNotBlank()) query(pendingQuery)
    }

    /** Opens a Section view; this always leaves Home. */
    fun selectSection(section: CeliumSection) {
        if ((section == CeliumSection.Adult || section == CeliumSection.AdultGames) && !MainAPI.settingsForProvider.enableAdult) return
        if (!_state.value.home && section == _state.value.section) return
        select(section, CeliumSources.selected(section, getKey("celium_source_${section.name}")), home = false)
    }

    /** Opens Home: the Movies/TV Celium Feed with Movies and TV Shows combined (3.1, 15.5). */
    fun selectHome() {
        if (_state.value.home) return
        val savedHomeSourceId = getKey<String>("celium_source_home")
        val source = CeliumSources.selected(CeliumSection.Movies, savedHomeSourceId)
        select(CeliumSection.Movies, source, home = true)
    }

    /** Picking an individual provider from Home opens that provider's Movies view. */
    fun selectSource(source: CeliumSource) {
        val current = _state.value
        if (source !in CeliumSources.choices(current.section)) return
        if (source == current.source) return
        select(current.section, source, home = current.home)
    }

    private fun cancelJobs() {
        feedJob?.cancel()
        searchJob?.cancel()
        resumeJob?.cancel()
        sectionJob?.cancel()
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        // New tokens make every in-flight feed and search result stale (10.3, 10.6).
        generation = gate.next(GATE_FEED)
        searchGeneration = gate.next(GATE_SEARCH)
    }

    private fun select(section: CeliumSection, source: CeliumSource, home: Boolean = false) {
        val previous = _state.value
        val pendingQuery = previous.query.takeIf { previous.section == section && previous.home == home }.orEmpty()
        setKey("celium_home", home)
        setKey("celium_section", section.name)
        if (!home) setKey("celium_source_${section.name}", source.id)
        else setKey("celium_source_home", source.id)
        cancelJobs()
        boundApi = null
        feedSignature = ""
        _state.value = CeliumState(home = home, section = section, source = source, adultEnabled = MainAPI.settingsForProvider.enableAdult)
        startWatchdog()
        load()
        sourcesChanged()
        if (pendingQuery.isNotBlank()) query(pendingQuery)
    }

    fun refresh() = reload(false)

    fun forceRefresh() = reload(true)

    /** Retry control for binding failures and feed/search errors (Requirement 16.9). */
    fun retry() {
        startWatchdog()
        _state.value = _state.value.copy(binding = BindStatus.Waiting, feedError = null, searchError = null, message = null)
        if (!syncSources(force = true)) reload(true)
    }

    private fun setPage(key: String, paging: ListPaging) {
        _state.value = _state.value.copy(pages = _state.value.pages + (key to paging))
    }

    private fun sectionsPaging(state: CeliumState) =
        ListPaging(if (state.remainingSections == 0) PageStatus.End else PageStatus.Idle)

    /** UI retry for a list in Error: "search", "sections", or a shelf key (Requirement 6.6). */
    fun retryPaging(key: String) {
        when (key) {
            CeliumState.SEARCH -> moreSearch()
            CeliumState.SECTIONS -> moreSections()
            else -> _state.value.shelves.firstOrNull { it.key == key }?.let(::more)
        }
    }

    /** Next batch of up to 8 Pending categories; never cancels shelf paging or reloads (21.2, 21.7). */
    fun moreSections() {
        val current = _state.value
        val status = current.page(CeliumState.SECTIONS).status
        if (current.busy || sectionJob?.isActive == true || status == PageStatus.Loading) return
        val batch = CeliumCatalog.nextBatch(current.slots, current.slotStatus)
        if (batch.isEmpty()) { setPage(CeliumState.SECTIONS, ListPaging(PageStatus.End)); return }
        val token = generation
        val sourceId = current.source.id
        setPage(CeliumState.SECTIONS, ListPaging(PageStatus.Loading))
        sectionJob = viewModelScope.launch {
            try { fetchSlots(token, sourceId, current.section, batch, catalogForce)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e)
            } finally {
                if (live(token, sourceId)) setPage(CeliumState.SECTIONS, sectionsPaging(_state.value))
            }
        }
    }

    /** Re-requests only one failed category (21.8). */
    fun retrySlot(slot: CategorySlot) {
        val current = _state.value
        if (current.slotStatus[slot] != SlotStatus.Failed) return
        val jobKey = "slot:${slot.providerId}:${slot.index}"
        if (pageJobs[jobKey]?.isActive == true) return
        val token = generation
        val sourceId = current.source.id
        pageJobs[jobKey] = viewModelScope.launch {
            try { fetchSlots(token, sourceId, current.section, listOf(slot), catalogForce)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e) }
        }
    }

    private fun ordered(shelves: List<CeliumShelf>, state: CeliumState): List<CeliumShelf> {
        val position = state.slots.withIndex().associate { it.value to it.index }
        return shelves.distinctBy { it.key }.sortedBy { position[state.slotOf(it)] ?: Int.MAX_VALUE }
    }

    /** One category request: its rows as shelves, or null on failure/timeout. */
    private suspend fun fetchSlot(slot: CategorySlot, section: CeliumSection, home: Boolean, split: Boolean, force: Boolean): List<CeliumShelf>? {
        val api = catalogApis[slot.providerId] ?: return null
        val repository = repo(api)
        return try {
            // Only this provider's own limit applies; its home-delay throttle never blocks another
            // provider, and the 15 s deadline covers this one request (6.4, 6.5, 6.7).
            val response = permitsFor(api).withPermit {
                withContext(Dispatchers.IO) {
                    repository.waitForHomeDelay()
                    withTimeoutOrNull(FEED_TIMEOUT_MS) {
                        repository.getMainPage(1, slot.index.takeIf { it >= 0 }, forceRefresh = force)
                    }
                }
            }
            if (response !is Resource.Success) return null
            // Classification and dedup run off the main thread (5.3).
            withContext(Dispatchers.Default) {
                var row = 0
                response.value.filterNotNull().flatMap { page ->
                    page.items.map { list ->
                        // Movies/TV Shows: the same category is split by the classifier, so this Section's
                        // Shelf holds only its own items (2.9–2.11). Duplicates are removed only within a
                        // category; no item cap (21.3).
                        // Home keeps the unsplit Celium 6 category with its original label (15.2, 15.6).
                        val cards = sectionItems(section, home, split, list.name, list.list).map(CeliumTile::from).distinctBy { it.url }
                        CeliumShelf("${slot.providerId}:${slot.index}:${row++}", list.name, cards, slot.index,
                            hasNext = page.hasNext, sourceId = slot.providerId)
                    }
                }.filter { it.cards.isNotEmpty() } // A category with no items for this Section is hidden (2.12).
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { logError(e); null }
    }

    /**
     * Fetches [batch] concurrently (per-provider limits only); each slot lands as soon as it loads,
     * replacing that slot's Shelves by key so the scroll position stays (6.5, 6.9). Returns failures.
     */
    private suspend fun fetchSlots(token: Long, sourceId: String, section: CeliumSection, batch: List<CategorySlot>, force: Boolean): Int {
        if (!live(token, sourceId)) return 0
        val home = _state.value.home
        val split = splitsFeed(home, section)
        val loading = _state.value.slotStatus + batch.associateWith { SlotStatus.Loading }
        _state.value = _state.value.copy(slotStatus = loading, remainingSections = CeliumCatalog.remaining(_state.value.slots, loading))
        var failures = 0
        coroutineScope {
            batch.forEach { slot ->
                launch {
                    val shelves = fetchSlot(slot, section, home, split, force)
                    if (!live(token, sourceId)) return@launch
                    if (shelves == null) failures++
                    val s = _state.value
                    val status = s.slotStatus + (slot to when {
                        shelves == null -> SlotStatus.Failed
                        shelves.isEmpty() -> SlotStatus.Empty
                        else -> SlotStatus.Loaded
                    })
                    // A failed slot keeps any stale snapshot shelves it had.
                    val next = if (shelves == null) s.shelves else ordered(s.shelves.filterNot { s.slotOf(it) == slot } + shelves, s)
                    _state.value = s.copy(shelves = next, slotStatus = status, remainingSections = CeliumCatalog.remaining(s.slots, status))
                }
            }
        }
        // The cache is overwritten only when this batch had at least one success (6.6, 6.8).
        if (failures < batch.size && live(token, sourceId)) persist()
        return failures
    }

    /**
     * Overwrites the Feed_Cache with the shown Shelves in pool order and the save time (6.6, 14.9).
     * Shelves of failed slots and of providers no longer in the pool are not re-stamped.
     */
    private suspend fun persist() {
        val s = _state.value
        val loaded = s.slots.filter { s.slotStatus[it] == SlotStatus.Loaded || s.slotStatus[it] == SlotStatus.Empty }
        if (loaded.isEmpty()) return
        val slotSet = s.slots.toSet()
        val shelves = s.shelves.filter { s.slotOf(it) in slotSet && s.slotStatus[s.slotOf(it)] != SlotStatus.Failed }
        val stored = CeliumSnapshot(System.currentTimeMillis(), shelves, loaded)
        val cacheKey = key(s)
        withContext(Dispatchers.IO) { CeliumFeedCache.write(cacheKey, stored) }
    }

    /**
     * Loads the provider view or the Celium Feed as a full catalogue: every main-page category becomes a
     * slot; the first batch of 8 loads now, the rest via [moreSections] (21.1, 21.2, 21.7).
     */
    fun load(force: Boolean = false) {
        feedJob?.cancel()
        sectionJob?.cancel()
        searchJob?.cancel()
        searchGeneration = gate.next(GATE_SEARCH)
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        val current = _state.value
        val sourceId = current.source.id
        if (current.section == CeliumSection.AdultGames) {
            bindWatchdog?.cancel()
            _state.value = current.copy(
                busy = true,
                binding = BindStatus.Ready,
                slots = emptyList(),
                slotStatus = emptyMap(),
                remainingSections = 0,
                feedError = null,
                message = null
            )
            feedJob = gated(GATE_FEED, { generation = it }) { token ->
                try {
                    val shelves = com.lagradost.cloudstream3.ui.celium.game.CeliumGameRepository.getMainPage(sourceId, 1)
                    if (!live(token, sourceId)) return@gated
                    if (shelves.isNotEmpty()) {
                        _state.value = _state.value.copy(shelves = shelves, busy = false, feedError = null, message = null)
                    } else {
                        _state.value = _state.value.copy(shelves = emptyList(), busy = false, message = "No games feed returned. Check network connection or try search.")
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    logError(e)
                    if (live(token, sourceId)) {
                        _state.value = _state.value.copy(busy = false, feedError = "Adult Games feed could not load. Tap to retry.")
                    }
                }
            }
            return
        }
        val curated = sourceId == "celium"
        val pool: List<Pair<CeliumSource, MainAPI>> = if (curated) curatedProviders(current.section)
            else CeliumSources.resolve(current.source, providers())?.let { listOf(current.source to it) }.orEmpty()
        boundApi = pool.firstOrNull()?.second
        if (curated) feedSignature = if (pool.isEmpty()) "" else poolSignature(pool)
        val permanent = if (curated) null else current.source.unavailableReason
        val binding: BindStatus = when {
            pool.isNotEmpty() -> BindStatus.Ready
            permanent != null -> BindStatus.Failed(permanent)
            current.binding is BindStatus.Failed -> current.binding
            else -> BindStatus.Waiting
        }
        if (pool.isNotEmpty()) bindWatchdog?.cancel()
        val apis = pool.filter { it.second.hasMainPage }.associate { it.first.id to it.second }
        catalogApis = apis
        catalogForce = force
        // Curated pool: round-robin across providers so the feed stays mixed (21.7).
        val slots = CeliumCatalog.slots(apis.map { (id, api) -> id to api.mainPage.size })
        val titles = slots.associateWith { slot ->
            val api = apis.getValue(slot.providerId)
            api.mainPage.getOrNull(slot.index)?.name ?: api.name
        }
        _state.value = current.copy(busy = pool.isNotEmpty(), message = null, feedError = null, binding = binding,
            pages = emptyMap(), query = "", results = emptyList(), searching = false, searched = false,
            slots = slots, slotStatus = emptyMap(), slotTitles = titles, remainingSections = slots.size)
        val cacheKey = key(current)
        val failedMessage = if (curated) "Celium Feed could not load." else "${current.source.label} feed could not load."
        val emptyMessage = if (curated) "No feed returned. Choose an individual source or try Search."
            else "No ${current.section.label.lowercase()} feed returned. Try search or choose another source."
        // The gate cancels the previous feed load and only this token's results apply (10.3–10.6).
        feedJob = gated(GATE_FEED, { generation = it }) { token ->
            try {
                // A cache younger than 6 h shows at once; an older or missing one leaves the Shelves empty so
                // the UI shows Placeholders (6.1, 6.3). Shelves already on screen (refresh, late-ready rebind)
                // stay until fresh ones replace them by key (6.9, 14.7).
                if (_state.value.shelves.isEmpty()) {
                    val snapshot = withContext(Dispatchers.IO) { CeliumFeedCache.read(cacheKey) }
                    if (!live(token, sourceId)) return@gated
                    if (snapshot != null && CeliumFeedCache.isFresh(snapshot, System.currentTimeMillis())) {
                        val s = _state.value
                        val shelves = snapshot.shelves.map { if (it.sourceId == null) it.copy(sourceId = sourceId) else it }
                        _state.value = s.copy(shelves = ordered(shelves, s), cachedAt = snapshot.savedAt)
                    }
                }
                // Not registered yet: the binding status shows Preparing until a plugin event rebinds (16.3, 16.4).
                if (pool.isEmpty()) return@gated
                if (!curated && !pool.first().second.hasMainPage) {
                    _state.value = _state.value.copy(message = "This adapter supports search but does not provide a home feed.")
                    return@gated
                }
                // Always refresh in the background, whatever the cache age (6.2).
                var failures = 0
                // Keep requesting batches while no category has loaded yet, so all-empty first categories don't strand the view.
                do {
                    val batch = CeliumCatalog.nextBatch(slots, _state.value.slotStatus)
                    if (batch.isEmpty()) break
                    failures += fetchSlots(token, sourceId, current.section, batch, force)
                    if (!live(token, sourceId)) return@gated
                } while (_state.value.slotStatus.values.none { it == SlotStatus.Loaded } && failures == 0)
                val s = _state.value
                val succeeded = s.slotStatus.values.any { it == SlotStatus.Loaded || it == SlotStatus.Empty }
                // After a success, Shelves of providers that left the pool (replaced by a higher-priority
                // late-ready provider) are dropped; if everything failed the shown Shelves stay (6.8, 14.6).
                val slotSet = slots.toSet()
                val shelves = if (succeeded) s.shelves.filter { s.slotOf(it) in slotSet } else s.shelves
                val stale = shelves.any { s.slotStatus[s.slotOf(it)] != SlotStatus.Loaded }
                _state.value = s.copy(
                    shelves = shelves,
                    cachedAt = if (stale) s.cachedAt else null,
                    feedError = if (shelves.isEmpty() && failures > 0) failedMessage else null,
                    message = if (shelves.isEmpty() && failures == 0) emptyMessage else null,
                    pages = s.pages + (CeliumState.SECTIONS to sectionsPaging(s)))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                logError(e)
                if (live(token, sourceId)) _state.value = _state.value.copy(feedError = failedMessage)
            } finally {
                if (live(token, sourceId)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Next page of one shelf; always re-reads the current shelf by key (Requirement 6). */
    fun more(shelf: CeliumShelf) {
        val current = _state.value
        val target = current.shelves.firstOrNull { it.key == shelf.key } ?: return
        val status = current.page(target.key).status
        if (!target.hasNext || status == PageStatus.Loading || status == PageStatus.End || pageJobs[target.key]?.isActive == true) return
        if (current.section == CeliumSection.AdultGames) {
            val key = target.key
            val token = generation
            setPage(key, ListPaging(PageStatus.Loading))
            pageJobs[key] = viewModelScope.launch {
                try {
                    val nextPage = target.page + 1
                    val sid = target.sourceId?.takeIf { it.isNotBlank() } ?: current.source.id
                    val nextShelves = com.lagradost.cloudstream3.ui.celium.game.CeliumGameRepository.getMainPage(sid, nextPage)
                    val matching = nextShelves.firstOrNull { it.sectionIndex == target.sectionIndex }
                    if (matching != null && matching.cards.isNotEmpty()) {
                        val merged = CeliumPaging.append(target.cards, matching.cards) { it.url }
                        val replacement = target.copy(cards = merged.items, page = nextPage, hasNext = matching.hasNext)
                        _state.value = _state.value.copy(shelves = _state.value.shelves.map { if (it.key == key) replacement else it })
                        setPage(key, ListPaging(if (matching.hasNext) PageStatus.Idle else PageStatus.End))
                    } else {
                        setPage(key, ListPaging(PageStatus.End))
                    }
                } catch (e: Exception) {
                    logError(e)
                    setPage(key, ListPaging(PageStatus.Error))
                }
            }
            return
        }
        val api = target.sourceId?.let { catalogApis[it] } ?: boundApi?.takeIf { current.source.id != "celium" } ?: return
        val repository = repo(api)
        val sourceId = current.source.id
        val token = generation
        val key = target.key
        setPage(key, ListPaging(PageStatus.Loading))
        pageJobs[key] = viewModelScope.launch {
            var next = ListPaging(PageStatus.Error, "More titles could not load.")
            try {
                val response = permitsFor(api).withPermit {
                    withContext(Dispatchers.IO) {
                        repository.waitForHomeDelay()
                        withTimeoutOrNull(CeliumPaging.TIMEOUT_MS) {
                            repository.getMainPage(target.page + 1, target.sectionIndex.takeIf { it >= 0 })
                        }
                    }
                }
                if (!live(token, sourceId)) return@launch
                if (response is Resource.Success) {
                    val matching = response.value.filterNotNull().flatMap { page -> page.items.filter { it.name == target.title }.map { it to page.hasNext } }
                    // Later pages use the same Home filter or classifier split as page 1, off the main thread (2.10, 5.3).
                    val split = splitsFeed(current.home, current.section)
                    val extra = withContext(Dispatchers.Default) {
                        sectionItems(current.section, current.home, split, target.title, matching.flatMap { it.first.list }).map(CeliumTile::from)
                    }
                    if (!live(token, sourceId)) return@launch
                    val latest = _state.value.shelves.firstOrNull { it.key == key }
                    if (latest == null) next = ListPaging()
                    else {
                        // Existing cards keep their order; only new urls are appended (6.10).
                        val merged = CeliumPaging.append(latest.cards, extra) { it.url }
                        val after = CeliumPaging.statusAfter(merged.added, matching.any { it.second })
                        val replacement = latest.copy(cards = merged.items, page = latest.page + 1, hasNext = after == PageStatus.Idle)
                        _state.value = _state.value.copy(shelves = _state.value.shelves.map { if (it.key == key) replacement else it })
                        next = ListPaging(after)
                        persist()
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                logError(e)
            } finally {
                if (live(token, sourceId)) setPage(key, next)
            }
        }
    }

    /** One source's search call; the engine wraps it with the timeout, cache and health tracking. */
    private suspend fun searchFetch(target: SearchTarget, query: String, page: Int): FetchResult {
        val api = searchApis[target.id] ?: return FetchResult.Failed
        val result = permitsFor(api).withPermit { withContext(Dispatchers.IO) { repo(api).search(query, page) } }
        return if (result is Resource.Success) FetchResult.Ok(result.value.items, result.value.hasNext) else FetchResult.Failed
    }

    /**
     * Sources a search asks. A chosen provider searches only itself. Celium Feed searches every ready, enabled
     * source of the category (Home and Movies/TV Shows: the Movies/TV Shows catalogue), best-known first, unless
     * the Settings switch limits it to the first ready one.
     */
    private fun searchTargets(current: CeliumState): Pair<List<SearchTarget>, Map<String, MainAPI>> {
        val available = providers()
        val chosen: List<Pair<CeliumSource, MainAPI>> = when {
            current.source.id != "celium" ->
                CeliumSources.resolve(current.source, available)?.let { listOf(current.source to it) }.orEmpty()
            getKey<Boolean>(SEARCH_ALL_KEY) != true -> curatedProviders(current.section).take(1)
            else -> {
                val candidates = CeliumSources.choices(current.section).filter { it.id != "celium" }
                val ordered = if (current.section == CeliumSection.Movies || current.section == CeliumSection.Series)
                    CeliumHomePool.orderPool(candidates) else candidates
                ordered.mapNotNull { s -> CeliumSources.resolve(s, available)?.let { s to it } }
            }
        }
        // Two catalogue entries can resolve to one provider; ask it once.
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MainAPI, Boolean>())
        val unique = chosen.filter { seen.add(it.second) }
        val targets = unique.mapIndexed { index, (source, _) -> SearchTarget(source.id, source.label, index) }
        return targets to unique.associate { it.first.id to it.second }
    }

    fun query(value: String) {
        searchJob?.cancel()
        // A new query makes any in-flight search stale before its job is replaced (4.5).
        searchGeneration = gate.next(GATE_SEARCH)
        val current = _state.value
        val sourceId = current.source.id
        val query = SearchRanking.cleanQuery(value).take(200)
        // The Search key and Retry send the same text again: they start at once and clear skipped sources.
        val submit = value.isNotBlank() && value == current.query
        val start = query.length >= 2 && (query.length >= SEARCH_MIN_TYPED || submit)
        val wait = if (submit) 0L else SEARCH_DEBOUNCE_MS
        searchSession = null
        _state.value = current.copy(query = value.take(200), results = emptyList(), searching = start,
            searched = false, searchPage = 1, searchHasNext = false, searchError = null,
            pages = current.pages - CeliumState.SEARCH, searchSources = emptyList(), searchFilter = null,
            searchAllSources = getKey<Boolean>(SEARCH_ALL_KEY) == true)
        if (!start) return
        if (current.section == CeliumSection.AdultGames) {
            searchJob = gated(GATE_SEARCH, { searchGeneration = it }) { token ->
                delay(wait)
                if (!liveSearch(token, sourceId)) return@gated

                // Immediately seed local matching games with 0ms latency
                val localMatches = withContext(Dispatchers.Default) {
                    SearchRanking.rank(query, current.shelves.flatMap { it.cards })
                        .filter { com.lagradost.cloudstream3.ui.celium.game.PornGameHubProvider.isGame(it.name, it.url) && SearchRanking.score(query, it.name) > 0 }
                        .map(CeliumTile::from)
                }
                _state.value = _state.value.copy(
                    results = localMatches,
                    searching = true,
                    searched = localMatches.isNotEmpty(),
                    searchError = null
                )

                try {
                    val remote = com.lagradost.cloudstream3.ui.celium.game.CeliumGameRepository.search(sourceId, query, 1)
                    if (!liveSearch(token, sourceId)) return@gated
                    val combined = (remote + localMatches)
                        .filter { com.lagradost.cloudstream3.ui.celium.game.PornGameHubProvider.isGame(it.name, it.url) }
                        .distinctBy { it.url.ifBlank { it.name } }
                    val results = withContext(Dispatchers.Default) {
                        combined.sortedByDescending { SearchRanking.score(query, it.name) }
                    }
                    _state.value = _state.value.copy(
                        results = results,
                        searching = false,
                        searched = true,
                        searchError = if (results.isEmpty()) "No games matching \"$query\" found." else null
                    )
                } catch (e: Exception) {
                    logError(e)
                    if (liveSearch(token, sourceId)) {
                        _state.value = _state.value.copy(
                            results = localMatches,
                            searching = false,
                            searched = true,
                            searchError = if (localMatches.isEmpty()) "Game search failed. Check your connection." else null
                        )
                    }
                } finally {
                    if (liveSearch(token, sourceId)) {
                        _state.value = _state.value.copy(searching = false)
                    }
                }
            }
            return
        }
        val parsed = SearchQuery.parse(query)
        searchJob = gated(GATE_SEARCH, { searchGeneration = it }) { token ->
            delay(wait)
            if (!liveSearch(token, sourceId)) return@gated
            if (submit) searchEngine.health.reset()
            val (targets, apis) = searchTargets(current)
            searchApis = apis
            val session = SearchSession(targets, parsed)
            // Titles already loaded on screen that match are shown at once and never lost.
            val local = withContext(Dispatchers.Default) {
                current.shelves.flatMap { it.cards }.filter {
                    SearchRanking.score(parsed.title.ifBlank { query }, it.name) > 0 || SearchRanking.score(query, it.name) > 0
                }
            }
            session.seed(local)
            searchSession = session
            if (!liveSearch(token, sourceId)) return@gated
            if (targets.isEmpty()) {
                publishSearch(token, sourceId, session, current, finished = false)
                // While the provider is preparing keep the loading state; a rebind re-runs this query (16.3, 16.4).
                val failed = _state.value.binding as? BindStatus.Failed
                _state.value = if (failed == null) _state.value.copy(searching = true)
                    else _state.value.copy(searching = false, searched = true, searchError = failed.message)
                return@gated
            }
            publishSearch(token, sourceId, session, current, finished = false)
            collectSearch(token, sourceId, session, current, targets)
        }
    }

    /** Runs [targets] through the engine, applying each source as it answers; the screen updates at most every 300 ms. */
    private suspend fun collectSearch(token: Long, sourceId: String, session: SearchSession, current: CeliumState, targets: List<SearchTarget>) {
        var lastPublish = 0L
        try {
            searchEngine.run(targets, session.parsed) { session.pageToLoad(it) }.collect { outcome ->
                if (!liveSearch(token, sourceId)) return@collect
                session.apply(outcome)
                val now = SystemClock.elapsedRealtime()
                if (session.pending && now - lastPublish >= SEARCH_PUBLISH_MS) {
                    lastPublish = now
                    publishSearch(token, sourceId, session, current, finished = false)
                }
            }
            if (liveSearch(token, sourceId)) publishSearch(token, sourceId, session, current, finished = true)
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            logError(e)
            if (liveSearch(token, sourceId)) publishSearch(token, sourceId, session, current, finished = true)
        } finally {
            if (liveSearch(token, sourceId)) _state.value = _state.value.copy(searching = false)
        }
    }

    /** Groups, ranks and shows everything the session has so far. Grouping and ranking run off the main thread (5.3). */
    private suspend fun publishSearch(token: Long, sourceId: String, session: SearchSession, current: CeliumState, finished: Boolean) {
        val classify = splitsFeed(current.home, current.section)
        val fits = CeliumSearchMerge.typeFits(current.section, current.home)
        val fresh = withContext(Dispatchers.Default) { buildSearchResults(session, current, classify, fits) }
        if (!liveSearch(token, sourceId)) return
        // While sources are still answering, titles already on screen keep their place and new ones join the end,
        // so the list does not reshuffle under the finger. The final ranking applies once everything has answered.
        val tiles = if (finished) fresh else keepOrder(_state.value.results, fresh)
        val statuses = session.statuses
        val ui = statuses.map { CeliumSearchSourceUi(it.target.id, it.target.label, it.state, it.count) }
        val anyAnswered = statuses.any { it.state == SourceState.Done }
        val paging = if (!finished) _state.value.page(CeliumState.SEARCH)
            else ListPaging(if (session.nextTargets().isNotEmpty()) PageStatus.Idle else PageStatus.End)
        _state.value = _state.value.copy(
            results = tiles,
            searchSources = ui,
            searched = finished || _state.value.searched,
            searchHasNext = finished && session.nextTargets().isNotEmpty(),
            searchPage = statuses.maxOfOrNull { it.page }?.coerceAtLeast(1) ?: 1,
            searchError = if (finished && tiles.isEmpty() && !anyAnswered && statuses.isNotEmpty())
                "Search failed on every source. Retry or change source." else null,
            pages = if (finished) _state.value.pages + (CeliumState.SEARCH to paging) else _state.value.pages,
        )
    }

    private fun searchKey(tile: CeliumTile) = tile.apiName + "\u0000" + tile.url

    private fun keepOrder(previous: List<CeliumTile>, fresh: List<CeliumTile>): List<CeliumTile> {
        if (previous.isEmpty()) return fresh
        val byKey = fresh.associateBy(::searchKey)
        val kept = previous.mapNotNull { byKey[searchKey(it)] }
        val keptKeys = kept.mapTo(HashSet(), ::searchKey)
        return kept + fresh.filter { searchKey(it) !in keptKeys }
    }

    private fun buildSearchResults(session: SearchSession, current: CeliumState, classify: Boolean, fits: (SearchResponse) -> Boolean): List<CeliumTile> {
        val hits = session.hits
        // The view keeps only the hits its category accepts (4.1-4.4). Only hits not yet decided are classified.
        val undecided = hits.filter { session.allowed[it.item] == null }
        if (undecided.isNotEmpty()) {
            val accepted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SearchResponse, Boolean>())
            accepted.addAll(sectionItems(current.section, current.home, classify, null, undecided.map { it.item }))
            undecided.forEach { session.allowed[it.item] = it.item in accepted }
        }
        val kept = hits.filter { session.allowed[it.item] == true }
        return CeliumSearchMerge.results(session.parsed, kept, fits)
            .distinctBy { "${it.best.item.apiName}:${it.best.item.url}" }
            .take(SEARCH_MAX_RESULTS).map { merged ->
            val base = CeliumTile.from(merged.best.item)
            base.copy(
                year = merged.year ?: base.year,
                sources = merged.hits.filter { it.sourceId.isNotEmpty() }
                    .map { CeliumTileSource(it.sourceId, it.item.apiName, it.item.url) }.distinctBy { it.sourceId to it.url },
            )
        }
    }

    /** Next page from every source that has one; sources that already answered are not asked again. */
    fun moreSearch() {
        val current = _state.value
        val status = current.page(CeliumState.SEARCH).status
        if (!current.searchHasNext || current.searching || status == PageStatus.Loading || status == PageStatus.End) return
        if (searchJob?.isActive == true) return
        val session = searchSession ?: return
        val next = session.nextTargets()
        if (next.isEmpty()) return
        val token = searchGeneration
        val sourceId = current.source.id
        session.beginNextPage(next)
        setPage(CeliumState.SEARCH, ListPaging(PageStatus.Loading))
        _state.value = _state.value.copy(searching = true)
        searchJob = viewModelScope.launch { collectSearch(token, sourceId, session, current, next) }
    }

    /** Narrows the shown results to one source (a chip), or null for all. Counts and order stay as ranked. */
    fun filterSearch(sourceId: String?) {
        _state.value = _state.value.copy(searchFilter = sourceId)
    }

    /** Runs the typed query in another category (the empty-state shortcut). */
    fun searchIn(section: CeliumSection) {
        val text = _state.value.query
        selectSection(section)
        if (text.isNotBlank()) query(text)
    }


    private fun curatedProviders(section: CeliumSection): List<Pair<CeliumSource, MainAPI>> {
        val available = providers()
        val candidates = CeliumSources.choices(section).filter { it.id != "celium" }
        val resolved = candidates.mapNotNull { source -> CeliumSources.resolve(source, available)?.let { source.id to it } }.toMap()
        // Movies/TV pool (Home and the Section Celium Feed): MultiMovies → CineStream → Moviesmod → the rest,
        // first 3 ready, so a not-ready provider's place goes to the next one (14.1–14.5, 15.4).
        // Anime/Adult keep their catalogue order.
        val ordered = if (section == CeliumSection.Movies || section == CeliumSection.Series) CeliumHomePool.orderPool(candidates) else candidates
        return CeliumSources.orderedPool(ordered, resolved.keys).map { it to resolved.getValue(it.id) }
    }

    private val scrollOffsets = mutableMapOf<String, Pair<Int, Int>>()

    fun getScroll(key: String): Pair<Int, Int> = scrollOffsets[key] ?: Pair(0, 0)

    fun setScroll(key: String, index: Int, offset: Int) {
        if (index >= 0 && offset >= 0) {
            scrollOffsets[key] = Pair(index, offset)
        }
    }

    private fun loadResume() {
        resumeJob?.cancel()
        val current = _state.value
        val sourceId = current.source.id
        val token = generation
        resumeJob = viewModelScope.launch {
            try {
                val resume = HomeViewModel.getResumeWatching().orEmpty()
                if (!live(token, sourceId)) return@launch
                val names = if (current.source.id == "celium") curatedProviders(current.section).map { it.second.name }.toSet() else setOfNotNull(boundApi?.name)
                _state.value = _state.value.copy(resume = resume.filter { it.apiName in names &&
                    if (current.home) CeliumSources.acceptsHome(it.type, it.apiName) else CeliumSources.accepts(current.section, it.type, it.apiName) }.take(20))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e) }
        }
    }
}
