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

@OptIn(FlowPreview::class)
class CeliumViewModel : ViewModel() {
    private val _state = MutableStateFlow(CeliumState())
    val state = _state.asStateFlow()
    private val permits = Semaphore(2)
    private val repositories = mutableMapOf<MainAPI, APIRepository>()
    private var feedJob: Job? = null
    private var searchJob: Job? = null
    private var resumeJob: Job? = null
    private var bindWatchdog: Job? = null
    private var sectionJob: Job? = null
    private val pageJobs = mutableMapOf<String, Job>()
    private var generation = 0
    private var searchGeneration = 0
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
        val section = CeliumSection.entries.firstOrNull { it.name == getKey<String>("celium_section") } ?: CeliumSection.Movies
        _state.value = CeliumState(section = section,
            source = CeliumSources.selected(section, getKey("celium_source_${section.name}")))
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
    private fun key(section: CeliumSection, source: CeliumSource) = "celium_feed_v1_${section.name}_${source.id}"

    /** A result may be shown only if its request is current and still for the selected source (16.6, 16.11). */
    private fun live(token: Int, sourceId: String) = token == generation && _state.value.source.id == sourceId
    private fun liveSearch(token: Int, sourceId: String) = token == searchGeneration && _state.value.source.id == sourceId

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
            try { adultSources.forEach { removeKey(key(CeliumSection.Adult, it)) }
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

    private fun cancelJobs() {
        feedJob?.cancel()
        searchJob?.cancel()
        resumeJob?.cancel()
        sectionJob?.cancel()
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        generation++
        searchGeneration++
    }

    private fun select(section: CeliumSection, source: CeliumSource) {
        val pendingQuery = _state.value.query.takeIf { _state.value.section == section }.orEmpty()
        setKey("celium_section", section.name)
        setKey("celium_source_${section.name}", source.id)
        cancelJobs()
        boundApi = null
        feedSignature = ""
        _state.value = CeliumState(section = section, source = source, adultEnabled = MainAPI.settingsForProvider.enableAdult)
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
    private suspend fun fetchSlot(slot: CategorySlot, section: CeliumSection, force: Boolean): List<CeliumShelf>? {
        val api = catalogApis[slot.providerId] ?: return null
        val repository = repo(api)
        return try {
            val response = withTimeoutOrNull(CeliumPaging.TIMEOUT_MS) {
                permits.withPermit { withContext(Dispatchers.IO) {
                    repository.waitForHomeDelay()
                    repository.getMainPage(1, slot.index.takeIf { it >= 0 }, forceRefresh = force)
                } }
            }
            if (response !is Resource.Success) return null
            var row = 0
            response.value.filterNotNull().flatMap { page ->
                page.items.map { list ->
                    // Duplicates are removed only within a category; no item cap (21.3).
                    val cards = CeliumSources.filter(section, list.list).map(CeliumTile::from).distinctBy { it.url }
                    CeliumShelf("${slot.providerId}:${slot.index}:${row++}", list.name, cards, slot.index,
                        hasNext = page.hasNext, sourceId = slot.providerId)
                }
            }.filter { it.cards.isNotEmpty() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { logError(e); null }
    }

    /** Fetches [batch] concurrently (bounded by permits); each slot lands as soon as it loads. Returns failures. */
    private suspend fun fetchSlots(token: Int, sourceId: String, section: CeliumSection, batch: List<CategorySlot>, force: Boolean): Int {
        if (!live(token, sourceId)) return 0
        val loading = _state.value.slotStatus + batch.associateWith { SlotStatus.Loading }
        _state.value = _state.value.copy(slotStatus = loading, remainingSections = CeliumCatalog.remaining(_state.value.slots, loading))
        var failures = 0
        coroutineScope {
            batch.forEach { slot ->
                launch {
                    val shelves = fetchSlot(slot, section, force)
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
        if (live(token, sourceId)) persist()
        return failures
    }

    /** Stores every loaded shelf and the Loaded/Empty slots (21.6). Stale fallback shelves are not re-stamped. */
    private suspend fun persist() {
        val s = _state.value
        val loaded = s.slots.filter { s.slotStatus[it] == SlotStatus.Loaded || s.slotStatus[it] == SlotStatus.Empty }
        if (loaded.isEmpty()) return
        val shelves = s.shelves.filter { s.slotStatus[s.slotOf(it)] == SlotStatus.Loaded }
        val stored = CeliumSnapshot(System.currentTimeMillis(), shelves, loaded)
        try { withContext(Dispatchers.IO) { setKey(key(s.section, s.source), stored) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { logError(e) }
    }

    /**
     * Loads the provider view or the Celium Feed as a full catalogue: every main-page category becomes a
     * slot; the first batch of 8 loads now, the rest via [moreSections] (21.1, 21.2, 21.7).
     */
    fun load(force: Boolean = false) {
        val token = ++generation
        feedJob?.cancel()
        sectionJob?.cancel()
        searchJob?.cancel()
        searchGeneration++
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
        val failedMessage = if (curated) "Celium Feed could not load." else "${current.source.label} feed could not load."
        val emptyMessage = if (curated) "No feed returned. Choose an individual source or try Search."
            else "No ${current.section.label.lowercase()} feed returned. Try search or choose another source."
        feedJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { getKey<CeliumSnapshot>(key(current.section, current.source)) }
                if (!live(token, sourceId)) return@launch
                val now = System.currentTimeMillis()
                var restored = emptyMap<CategorySlot, SlotStatus>()
                if (snapshot?.usable(now) == true) {
                    val s = _state.value
                    val shelves = snapshot.shelves.map { if (it.sourceId == null) it.copy(sourceId = sourceId) else it }
                    val slotSet = slots.toSet()
                    restored = snapshot.loaded.filter { it in slotSet }.associateWith { slot ->
                        if (shelves.any { s.slotOf(it) == slot }) SlotStatus.Loaded else SlotStatus.Empty
                    }
                    _state.value = s.copy(shelves = ordered(shelves, s), cachedAt = snapshot.savedAt)
                }
                // Not registered yet: the binding status shows Preparing until a plugin event rebinds (16.3, 16.4).
                if (pool.isEmpty()) return@launch
                if (!curated && !pool.first().second.hasMainPage) {
                    _state.value = _state.value.copy(message = "This adapter supports search but does not provide a home feed.")
                    return@launch
                }
                if (!force && snapshot?.fresh(now) == true && restored.isNotEmpty()) {
                    // Restored view continues from the next Pending slot (21.6).
                    val s = _state.value
                    _state.value = s.copy(slotStatus = restored, remainingSections = CeliumCatalog.remaining(slots, restored))
                    setPage(CeliumState.SECTIONS, sectionsPaging(_state.value))
                    return@launch
                }
                var failures = 0
                // Keep requesting batches while nothing is shown yet, so all-empty first categories don't strand the view.
                do {
                    val batch = CeliumCatalog.nextBatch(slots, _state.value.slotStatus)
                    if (batch.isEmpty()) break
                    failures += fetchSlots(token, sourceId, current.section, batch, force)
                    if (!live(token, sourceId)) return@launch
                } while (_state.value.shelves.isEmpty() && failures == 0)
                val s = _state.value
                val stale = s.shelves.any { s.slotStatus[s.slotOf(it)] != SlotStatus.Loaded }
                _state.value = s.copy(
                    cachedAt = if (stale) s.cachedAt else null,
                    feedError = if (s.shelves.isEmpty() && failures > 0) failedMessage else null,
                    message = if (s.shelves.isEmpty() && failures == 0) emptyMessage else null,
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
                val response = withTimeoutOrNull(CeliumPaging.TIMEOUT_MS) {
                    permits.withPermit { withContext(Dispatchers.IO) {
                        repository.waitForHomeDelay()
                        repository.getMainPage(target.page + 1, target.sectionIndex.takeIf { it >= 0 })
                    } }
                }
                if (!live(token, sourceId)) return@launch
                if (response is Resource.Success) {
                    val matching = response.value.filterNotNull().flatMap { page -> page.items.filter { it.name == target.title }.map { it to page.hasNext } }
                    val extra = CeliumSources.filter(current.section, matching.flatMap { it.first.list }).map(CeliumTile::from)
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

    /** Search uses only the selected provider; Celium Feed uses its first ready pool source (16.11). */
    private fun searchApi(current: CeliumState): MainAPI? =
        if (current.source.id == "celium") curatedProviders(current.section).firstOrNull()?.second else boundApi

    fun query(value: String) {
        searchJob?.cancel()
        val token = ++searchGeneration
        val current = _state.value
        val sourceId = current.source.id
        val query = SearchRanking.cleanQuery(value).take(200)
        _state.value = current.copy(query = value.take(200), results = emptyList(), searching = query.length >= 2,
            searched = false, searchPage = 1, searchHasNext = false, searchError = null,
            pages = current.pages - CeliumState.SEARCH)
        if (query.length < 2) return
        val api = searchApi(current)
        searchJob = viewModelScope.launch {
            delay(350)
            if (!liveSearch(token, sourceId)) return@launch
            val local = withContext(Dispatchers.Default) {
                SearchRanking.rank(query, current.shelves.flatMap { it.cards })
                    .filter { SearchRanking.score(query, it.name) > 0 }.map(CeliumTile::from)
            }
            if (!liveSearch(token, sourceId)) return@launch
            _state.value = _state.value.copy(results = local)
            if (api == null) {
                // While the provider is preparing keep the loading state; a rebind re-runs this query (16.3, 16.4).
                val failed = _state.value.binding as? BindStatus.Failed
                _state.value = if (failed == null) _state.value.copy(searching = true)
                    else _state.value.copy(searching = false, searched = true, searchError = failed.message)
                return@launch
            }
            val cacheKey = Triple(current.section, sourceId, query)
            val cached = queryCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.first < 600_000L) {
                // Only single-page results are cached, so the list is complete.
                _state.value = _state.value.copy(results = cached.second, searching = false, searched = true,
                    pages = _state.value.pages + (CeliumState.SEARCH to ListPaging(PageStatus.End)))
                return@launch
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

    private suspend fun runSearch(api: MainAPI, query: String, page: Int, token: Int, existing: List<CeliumTile>) {
        val current = _state.value
        val sourceId = current.source.id
        val repository = repo(api)
        val failed = "Search failed on ${current.source.label}. Retry or change source."
        val moreFailed = ListPaging(PageStatus.Error, "More results could not load.")
        try {
            val result = withTimeoutOrNull(CeliumPaging.TIMEOUT_MS) {
                permits.withPermit { withContext(Dispatchers.IO) { repository.search(query, page) } }
            }
            if (!liveSearch(token, sourceId)) return
            if (result is Resource.Success) {
                val items = CeliumSources.filter(current.section, result.value.items)
                // Page 1 ranks local + remote; later pages rank only their own items (6.10, 6.11).
                val ranked = withContext(Dispatchers.Default) {
                    SearchRanking.rank(query, if (page == 1) existing + items else items).map(CeliumTile::from)
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
                    queryCache[Triple(current.section, sourceId, query)] = System.currentTimeMillis() to cards
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
        return CeliumSources.orderedPool(candidates, resolved.keys).map { it to resolved.getValue(it.id) }
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
                _state.value = _state.value.copy(resume = resume.filter { it.apiName in names && CeliumSources.accepts(current.section, it.type) }.take(20))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e) }
        }
    }
}
