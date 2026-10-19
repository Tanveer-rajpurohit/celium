package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper

/** A feed adapter over installed providers; every card retains its original apiName and URL. */
internal object DiscoveryProvider : MainAPI() {
    override var name = "Discover"
    override var mainUrl = "https://cloudstream.local"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = TvType.entries.toSet()

    override val mainPage: List<MainPageData>
        get() {
            val pinned = DataStoreHelper.pinnedProviders.reversedArray().toList()
            val types = DataStoreHelper.homePreference.toSet()
            return context?.filterProviderByPreferredMedia().orEmpty()
                .filter { it !== this }
                .filter { types.isEmpty() || it.supportedTypes.any { type -> type in types } }
                .filter { settingsForProvider.enableAdult || it.supportedTypes.any { type -> type != TvType.NSFW } }
                .sortedWith(compareBy<MainAPI> { pinned.indexOf(it.name).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                    .thenBy { it.name })
                .take(8)
                .map { MainPageData(it.name, it.name) }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val provider = APIHolder.getApiFromNameNull(request.data)
            ?.takeIf { it !== this } ?: throw ErrorLoadingException("Source is no longer installed")
        return when (val result = APIRepository(provider).getMainPage(page, forceRefresh = true)) {
            is Resource.Success -> {
                val pages = result.value.filterNotNull()
                newHomePageResponse(
                    pages.flatMap { it.items }.filter { it.list.isNotEmpty() }.map { row ->
                        row.copy(
                            name = "${provider.name} · ${row.name}",
                            list = row.list.filter { settingsForProvider.enableAdult || it.type != TvType.NSFW },
                        )
                    }.filter { it.list.isNotEmpty() },
                    pages.any { it.hasNext },
                )
            }
            else -> throw ErrorLoadingException("Unable to load ${provider.name}")
        }
    }

    fun sectionIndex(rowName: String, sections: List<MainPageData>): Int =
        sections.indexOfFirst { rowName.startsWith("${it.name} · ") }
}
