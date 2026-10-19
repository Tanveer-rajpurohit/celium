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
data class CeliumSnapshot(
    val savedAt: Long, val shelves: List<CeliumShelf>,
    /** Loaded or Empty category slots, so a restored view continues from the next Pending slot (21.6). */
    val loaded: List<CategorySlot> = emptyList(),
) {
    fun usable(now: Long): Boolean = now >= savedAt && now - savedAt < 86_400_000L
    fun fresh(now: Long): Boolean = usable(now) && now - savedAt < 900_000L
}

data class CeliumSourceStatus(val source: CeliumSource, val installed: Boolean, val hasFeed: Boolean, val reason: String? = null)

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
    private val queryCache = linkedMapOf<Triple<CeliumSection, String, String>, Pair<Long, List<CeliumTile>>>()

    /** Plugin_Loaded_Event and policy changes, coalesced so a burst of plugin loads binds once. */
    private val pluginSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Fresh start opens Home (3.1); a saved Section view is restored otherwise.
        val home = getKey<Boolean>("celium_home") ?: true
        val section = if (home) CeliumSection.Movies
            else CeliumSection.entries.firstOrNull { it.name == getKey<String>("celium_section") } ?: CeliumSection.Movies
        _state.value = CeliumState(home = home, section = section,
            source = if (home) CeliumSources.curated(CeliumSection.Movies)
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
        if (home) HOME_CACHE_KEY else "celium_feed_v2_${section.name}_${source.id}"
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
        queryCache.keys.removeAll { it.first == CeliumSection.Adult }
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
        if (!adult && _state.value.section == CeliumSection.Adult) {
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
        if (section == CeliumSection.Adult && !MainAPI.settingsForProvider.enableAdult) return
        if (!_state.value.home && section == _state.value.section) return
        select(section, CeliumSources.selected(section, getKey("celium_source_${section.name}")), home = false)
    }

    /** Opens Home: the Movies/TV Celium Feed with Movies and TV Shows combined (3.1, 15.5). */
    fun selectHome() {
        if (_state.value.home) return
        select(CeliumSection.Movies, CeliumSources.curated(CeliumSection.Movies), home = true)
    }

    /** Picking an individual provider from Home opens that provider's Movies view. */
    fun selectSource(source: CeliumSource) {
        val current = _state.value
        if (source !in CeliumSources.choices(current.section)) return
        if (source == current.source) return
        select(current.section, source, home = current.home && source.id == "celium")
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
        // Home's fixed Celium Feed source does not overwrite the Section's saved source choice.
        if (!home) setKey("celium_source_${section.name}", source.id)
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

    /** Home and the Movies Celium Feed filter differently, so their query results are cached apart. */
    private fun queryKey(state: CeliumState, query: String) =
        Triple(state.section, if (state.home) "home:${state.source.id}" else state.source.id, query)

    /** Search uses only the selected provider; Celium Feed uses its first ready pool source (16.11). */
    private fun searchApi(current: CeliumState): MainAPI? =
        if (current.source.id == "celium") curatedProviders(current.section).firstOrNull()?.second else boundApi

    fun query(value: String) {
        searchJob?.cancel()
        // A new query makes any in-flight search stale before its job is replaced (4.5).
        searchGeneration = gate.next(GATE_SEARCH)
        val current = _state.value
        val sourceId = current.source.id
        val query = SearchRanking.cleanQuery(value).take(200)
        _state.value = current.copy(query = value.take(200), results = emptyList(), searching = query.length >= 2,
            searched = false, searchPage = 1, searchHasNext = false, searchError = null,
            pages = current.pages - CeliumState.SEARCH)
        if (query.length < 2) return
        val api = searchApi(current)
        searchJob = gated(GATE_SEARCH, { searchGeneration = it }) { token ->
            delay(350)
            if (!liveSearch(token, sourceId)) return@gated
            val local = withContext(Dispatchers.Default) {
                SearchRanking.rank(query, current.shelves.flatMap { it.cards })
                    .filter { SearchRanking.score(query, it.name) > 0 }.map(CeliumTile::from)
            }
            if (!liveSearch(token, sourceId)) return@gated
            _state.value = _state.value.copy(results = local)
            if (api == null) {
                // While the provider is preparing keep the loading state; a rebind re-runs this query (16.3, 16.4).
                val failed = _state.value.binding as? BindStatus.Failed
                _state.value = if (failed == null) _state.value.copy(searching = true)
                    else _state.value.copy(searching = false, searched = true, searchError = failed.message)
                return@gated
            }
            val cached = queryCache[queryKey(current, query)]
            if (cached != null && System.currentTimeMillis() - cached.first < 600_000L) {
                // Only single-page results are cached, so the list is complete.
                _state.value = _state.value.copy(results = cached.second, searching = false, searched = true,
                    pages = _state.value.pages + (CeliumState.SEARCH to ListPaging(PageStatus.End)))
                return@gated
            }
            runSearch(api, query, 1, token, local)
        }
    }

    fun moreSearch() {
        val current = _state.value
        val status = current.page(CeliumState.SEARCH).status
        if (!current.searchHasNext || current.searching || status == PageStatus.Loading || status == PageStatus.End) return
        if (searchJob?.isActive == true) return
        // Search stays on the bound provider only (15.3).
        val api = searchApi(current) ?: return
        val token = searchGeneration
        setPage(CeliumState.SEARCH, ListPaging(PageStatus.Loading))
        searchJob = viewModelScope.launch { runSearch(api, SearchRanking.cleanQuery(current.query), current.searchPage + 1, token, current.results) }
    }

    private suspend fun runSearch(api: MainAPI, query: String, page: Int, token: Long, existing: List<CeliumTile>) {
        val current = _state.value
        val sourceId = current.source.id
        val repository = repo(api)
        val failed = "Search failed on ${current.source.label}. Retry or change source."
        val moreFailed = ListPaging(PageStatus.Error, "More results could not load.")
        try {
            val result = withTimeoutOrNull(CeliumPaging.TIMEOUT_MS) {
                permitsFor(api).withPermit { withContext(Dispatchers.IO) { repository.search(query, page) } }
            }
            if (!liveSearch(token, sourceId)) return
            if (result is Resource.Success) {
                // Movies/TV Shows search keeps only results the classifier assigns to this Section; Anime/Adult
                // keep their type filter and only ever query their own Section's sources (4.1–4.3).
                // Home search keeps both Movies and TV Shows results (4.4).
                val classify = splitsFeed(current.home, current.section)
                // Classification, dedup, and ranking run off the main thread (5.3).
                val (items, ranked) = withContext(Dispatchers.Default) {
                    val items = sectionItems(current.section, current.home, classify, null, result.value.items)
                    // Page 1 ranks local + remote; later pages rank only their own items (6.10, 6.11).
                    items to SearchRanking.rank(query, if (page == 1) existing + items else items).map(CeliumTile::from)
                }
                if (!liveSearch(token, sourceId)) return
                val (cards, added) = if (page == 1) ranked to items.size
                    else CeliumPaging.append(_state.value.results, ranked) { it.url }.let { it.items to it.added }
                val after = CeliumPaging.statusAfter(added, result.value.hasNext)
                _state.value = _state.value.copy(results = cards, searched = true, searchPage = page,
                    searchHasNext = after == PageStatus.Idle,
                    pages = _state.value.pages + (CeliumState.SEARCH to ListPaging(after)))
                // Paginated queries are not cached: their continuation cursor must remain intact.
                if (page == 1 && !result.value.hasNext) {
                    queryCache[queryKey(current, query)] = System.currentTimeMillis() to cards
                    while (queryCache.size > 24) queryCache.remove(queryCache.keys.first())
                }
            } else _state.value = if (page == 1) _state.value.copy(searched = true, searchError = failed)
                else _state.value.copy(pages = _state.value.pages + (CeliumState.SEARCH to moreFailed))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            logError(e)
            if (liveSearch(token, sourceId)) _state.value = if (page == 1) _state.value.copy(searchError = failed, searched = true)
                else _state.value.copy(pages = _state.value.pages + (CeliumState.SEARCH to moreFailed))
        } finally {
            if (liveSearch(token, sourceId)) _state.value = _state.value.copy(searching = false)
        }
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
                    if (current.home) CeliumSources.acceptsHome(it.type) else CeliumSources.accepts(current.section, it.type) }.take(20))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e) }
        }
    }
}
