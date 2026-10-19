package com.lagradost.cloudstream3.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.DiscoveryProvider
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext


data class ExpandableSearchList(
    var list: List<SearchResponse>, var currentPage: Int, var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

data class SearchProgress(val completed: Int = 0, val total: Int = 0, val failed: Int = 0)

class SearchViewModel : ViewModel() {
    private val _progress = MutableLiveData(SearchProgress())
    val progress: LiveData<SearchProgress> get() = _progress
    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    private var suggestionJob: Job? = null
    private var suggestionGeneration = 0

    private var repos = apis.withLock { apis.filter { it !== DiscoveryProvider }.map { APIRepository(it) } }

    fun clearSearch() {
        currentSearchIndex++
        onGoingSearch?.cancel()
        lastQuery = null
        expandableSearches.clear()
        _progress.value = SearchProgress()
        publishSearch()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    private val expandableSearches: MutableMap<String, ExpandableSearchList> = mutableMapOf()

    private var currentSearchIndex = 0
    private var onGoingSearch: Job? = null
    private val searchPermits = Semaphore(6)

    fun reloadRepos() {
        repos = apis.withLock { apis.filter { it !== DiscoveryProvider }.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) = startSearch(query, providersActive, ignoreSettings, isQuickSearch, saveHistory = true)

    fun searchWhileTyping(query: String, providersActive: Set<String>) =
        startSearch(query, providersActive, ignoreSettings = false, isQuickSearch = false, saveHistory = false)

    private fun startSearch(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean,
        isQuickSearch: Boolean,
        saveHistory: Boolean,
    ) {
        currentSearchIndex++
        onGoingSearch?.cancel()
        val normalizedQuery = SearchRanking.cleanQuery(query)
        if (normalizedQuery.length < 2) {
            clearSearch()
            return
        }
        onGoingSearch = search(normalizedQuery, providersActive, ignoreSettings, isQuickSearch, saveHistory)
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        val generation = ++suggestionGeneration
        suggestionJob?.cancel()
        
        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }
        
        suggestionJob = viewModelScope.launchSafe {
            delay(300) // Debounce
            val suggestions = withContext(Dispatchers.IO) { SearchSuggestionApi.getSuggestions(query) }
            if (generation == suggestionGeneration) _searchSuggestions.value = suggestions
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionGeneration++
        suggestionJob?.cancel()
        _searchSuggestions.postValue(emptyList())
    }

    private val lock = mutableSetOf<Pair<Int, String>>()

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? =
        withContext(Dispatchers.Main) {
            val query = lastQuery ?: return@withContext null
            val repo = repos.find { it.name == name } ?: return@withContext null
            val current = expandableSearches[name] ?: return@withContext null
            val generation = currentSearchIndex
            val request = generation to name
            if (!current.hasNext || !lock.add(request)) return@withContext null
            try {
                val nextPage = current.currentPage + 1
                val next = searchPermits.withPermit {
                    withContext(Dispatchers.IO) { repo.search(query, nextPage) }
                }
                if (generation != currentSearchIndex) return@withContext null
                // A failed page stays retryable; only the provider can signal the end.
                if (next is Resource.Success) {
                    expandableSearches[name] = ExpandableSearchList(
                        SearchRanking.rank(query, current.list + next.value.items),
                        nextPage,
                        next.value.hasNext,
                    )
                    publishSearch()
                }
                val item = expandableSearches[name] ?: return@withContext null
                HomeViewModel.ExpandableHomepageList(
                    HomePageList(name, item.list), item.currentPage, item.hasNext
                )
            } finally {
                lock -= request
            }
        }

    private fun publishSearch() {
        // Never expose the live map to observers while provider jobs are completing.
        val snapshot = expandableSearches.mapValues { (_, value) ->
            value.copy(list = value.list.filter { MainAPI.settingsForProvider.enableAdult || it.type != TvType.NSFW })
        }
        _currentSearch.value = snapshot
        val only = snapshot.values.singleOrNull()
        _searchResponse.value = Resource.Success(
            ExpandableSearchList(
                SearchRanking.rank(lastQuery.orEmpty(), snapshot.values.flatMap { it.list }),
                only?.currentPage ?: 1,
                only?.hasNext ?: false,
            )
        )
    }

    private fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
        saveHistory: Boolean = true,
    ) =
        viewModelScope.launchSafe {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return@launchSafe
            }

            if (!isQuickSearch && saveHistory) {
                val key = query.hashCode().toString()
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(), // TODO implement tv type
                        key = key,
                    )
                )
            }

            _searchResponse.value = Resource.Loading()
            _currentSearch.value = emptyMap()
            expandableSearches.clear()

            lastQuery = query

            val selectedRepos = repos.filter { a ->
                (ignoreSettings || providersActive.isEmpty() || providersActive.contains(a.name)) &&
                    (!isQuickSearch || a.hasQuickSearch) &&
                    (MainAPI.settingsForProvider.enableAdult || a.api.supportedTypes.any { it != TvType.NSFW })
            }
            _progress.value = SearchProgress(total = selectedRepos.size)
            coroutineScope {
                selectedRepos.forEach { a ->
                    launch {
                        val result = searchPermits.withPermit {
                            withContext(Dispatchers.IO) {
                                if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                            }
                        }
                        if (currentSearchIndex != currentIndex) return@launch
                        val previous = _progress.value ?: SearchProgress(total = selectedRepos.size)
                        _progress.value = previous.copy(
                            completed = previous.completed + 1,
                            failed = previous.failed + if (result is Resource.Failure) 1 else 0,
                        )
                        if (result is Resource.Success) {
                            expandableSearches[a.name] = ExpandableSearchList(
                                SearchRanking.rank(query, result.value.items), 1, result.value.hasNext
                            )
                            publishSearch()
                        }
                    }
                }
            }
            if (currentSearchIndex == currentIndex) publishSearch()
        }
}
