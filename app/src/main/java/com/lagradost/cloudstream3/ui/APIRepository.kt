package com.lagradost.cloudstream3.ui

import android.os.SystemClock
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class APIRepository(val api: MainAPI) {
    companion object {
        // 2 minute timeout to prevent bad extensions/extractors from hogging the resources
        // No real provider should take longer, so we hard kill them.
        private const val DEFAULT_TIMEOUT = 120_000L
        private const val MAX_TIMEOUT = 4 * DEFAULT_TIMEOUT
        private const val MIN_TIMEOUT = 5_000L

        var dubStatusActive = HashSet<DubStatus>()

        val noneApi = object : MainAPI() {
            override var name = "None"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }
        val randomApi = object : MainAPI() {
            override var name = "Random"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }

        fun isInvalidData(data: String): Boolean {
            return data.isEmpty() || data == "[]" || data == "about:blank"
        }

        data class SavedLoadResponse(
            val unixTime: Long,
            val response: LoadResponse,
            val hash: Pair<String, String>
        )

        private val cache = atomicListOf<SavedLoadResponse>()
        private var cacheIndex: Int = 0
        const val CACHE_SIZE = 20

        private data class HomeCacheEntry(val time: Long, val pages: List<HomePageResponse?>)
        private val homeCache = linkedMapOf<Pair<MainAPI, MainPageData?>, HomeCacheEntry>()
        private const val HOME_CACHE_TTL_MS = 120_000L
        private const val HOME_CACHE_STALE_MS = 1_800_000L

        private fun copyHome(pages: List<HomePageResponse?>): List<HomePageResponse?> = pages.map { home ->
            home?.copy(items = home.items.map { row -> row.copy(list = row.list.toList()) })
        }

        fun getTimeout(desired: Long?): Long {
            return (desired ?: DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        }
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        if (forceReload) {
            cache.clear()
            synchronized(homeCache) { homeCache.clear() }
        }
    }

    init {
        afterPluginsLoadedEvent += ::afterPluginsLoaded
    }

    val hasMainPage = api.hasMainPage
    val providerType = api.providerType
    val name = api.name
    val mainUrl = api.mainUrl
    val mainPage = api.mainPage
    val hasQuickSearch = api.hasQuickSearch
    val vpnStatus = api.vpnStatus

    suspend fun load(url: String): Resource<LoadResponse> {
        return safeApiCall {
            withTimeout(getTimeout(api.loadTimeoutMs)) {
                if (isInvalidData(url)) throw ErrorLoadingException()
                val fixedUrl = api.fixUrl(url)
                val lookingForHash = Pair(api.name, fixedUrl)

                val cached = cache.withLock {
                    var found: LoadResponse? = null
                    for (item in cache) {
                        // 10 min save
                        if (item.hash == lookingForHash && (unixTime - item.unixTime) < 60 * 10) {
                            found = item.response
                            break
                        }
                    }
                    found
                }

                if (cached != null) return@withTimeout cached
                api.load(fixedUrl)?.also { response ->
                    // Remove all blank tags as early as possible
                    response.tags = response.tags?.filter { it.isNotBlank() }
                    val add = SavedLoadResponse(unixTime, response, lookingForHash)

                    cache.withLock {
                        if (cache.size > CACHE_SIZE) {
                            cache[cacheIndex] = add // rolling cache
                            cacheIndex = (cacheIndex + 1) % CACHE_SIZE
                        } else {
                            cache.add(add)
                        }
                    }
                } ?: throw ErrorLoadingException()
            }
        }
    }

    suspend fun search(query: String, page: Int): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList()))

        return safeApiCall {
            withTimeout(getTimeout(api.searchTimeoutMs ?: 30_000L)) {
                (api.search(query, page)
                    ?: throw ErrorLoadingException())
                //                .filter { typesActive.contains(it.type) }
            }
        }
    }

    suspend fun quickSearch(query: String): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList()))

        return safeApiCall {
            withTimeout(getTimeout(api.quickSearchTimeoutMs ?: 10_000L)) {
                newSearchResponseList(
                    api.quickSearch(query) ?: throw ErrorLoadingException(),
                    false
                )
            }
        }
    }

    suspend fun waitForHomeDelay() {
        val delta = api.sequentialMainPageScrollDelay + api.lastHomepageRequest - unixTimeMS
        if (delta < 0) return
        delay(delta)
    }

    suspend fun getMainPage(
        page: Int,
        nameIndex: Int? = null,
    ): Resource<List<HomePageResponse?>> = getMainPage(page, nameIndex, false)

    suspend fun getMainPage(
        page: Int,
        nameIndex: Int? = null,
        forceRefresh: Boolean,
    ): Resource<List<HomePageResponse?>> {
        val section = nameIndex?.let { mainPage.getOrNull(it) }
        val key = api to section
        val cached = if (page == 1) synchronized(homeCache) { homeCache[key] } else null
        if (!forceRefresh && cached != null && SystemClock.elapsedRealtime() - cached.time < HOME_CACHE_TTL_MS) {
            return Resource.Success(copyHome(cached.pages))
        }
        val result = safeApiCall {
            withTimeout(getTimeout(api.getMainPageTimeoutMs ?: 45_000L)) {
                api.lastHomepageRequest = unixTimeMS
                val requests = section?.let { listOf(it) } ?: mainPage
                val permits = Semaphore(4)
                suspend fun fetch(data: MainPageData): Resource<HomePageResponse?> =
                    safeApiCall {
                        permits.withPermit {
                            api.getMainPage(
                                page,
                                MainPageRequest(data.name, data.data, data.horizontalImages)
                            )
                        }
                    }
                val responses = if (api.sequentialMainPage) {
                    requests.mapIndexed { index, data ->
                        if (index > 0) delay(api.sequentialMainPageDelay)
                        fetch(data)
                    }
                } else {
                    with(CoroutineScope(coroutineContext)) {
                        requests.map { data -> async { fetch(data) } }.map { it.await() }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (responses.isNotEmpty() && responses.none { it is Resource.Success }) {
                    throw ErrorLoadingException("Unable to load ${api.name}. Check your connection and retry.")
                }
                responses.mapNotNull { (it as? Resource.Success)?.value }
            }
        }
        currentCoroutineContext().ensureActive()
        if (result is Resource.Success && page == 1 && result.value.any { home -> home.items.any { it.list.isNotEmpty() } }) {
            synchronized(homeCache) {
                homeCache[key] = HomeCacheEntry(SystemClock.elapsedRealtime(), copyHome(result.value))
                while (homeCache.size > 24) homeCache.remove(homeCache.keys.first())
            }
        }
        if (result is Resource.Failure && cached != null && SystemClock.elapsedRealtime() - cached.time < HOME_CACHE_STALE_MS) {
            return Resource.Success(copyHome(cached.pages))
        }
        return result
    }

    suspend fun extractorVerifierJob(extractorData: String?) {
        safeApiCall {
            api.extractorVerifierJob(extractorData)
        }
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (isInvalidData(data)) return false // this makes providers cleaner
        return try {
            withTimeout(getTimeout(api.loadLinksTimeoutMs)) {
                api.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        } catch (throwable: Throwable) {
            logError(throwable)
            return false
        }
    }
}
