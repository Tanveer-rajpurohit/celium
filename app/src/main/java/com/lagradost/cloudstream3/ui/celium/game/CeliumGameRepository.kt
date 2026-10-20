package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Unified repository routing game requests across all game providers.
 * Merges curated shelves when viewing the Celium Feed, providing seamless fallback
 * if any individual web domain experiences regional or network latency.
 */
object CeliumGameRepository {

    suspend fun getMainPage(sourceId: String, page: Int = 1): List<CeliumShelf> = withContext(Dispatchers.IO) {
        when (sourceId) {
            "itch_adult" -> ItchAdultProvider.getMainPage(page)
            "porngamehub" -> PornGameHubProvider.getMainPage(page)
            else -> {
                // Curated Celium Feed: concurrently fetch both providers and combine
                val itchDeferred = async {
                    try { ItchAdultProvider.getMainPage(page) } catch (e: Exception) { logError(e); emptyList() }
                }
                val pghDeferred = async {
                    try { PornGameHubProvider.getMainPage(page) } catch (e: Exception) { logError(e); emptyList() }
                }

                val itchShelves = itchDeferred.await()
                val pghShelves = pghDeferred.await()

                val combined = mutableListOf<CeliumShelf>()
                combined.addAll(itchShelves)
                combined.addAll(pghShelves)
                combined
            }
        }
    }

    suspend fun search(sourceId: String, query: String, page: Int = 1): List<CeliumTile> = withContext(Dispatchers.IO) {
        when (sourceId) {
            "itch_adult" -> ItchAdultProvider.search(query, page)
            "porngamehub" -> PornGameHubProvider.search(query, page)
            else -> {
                val itchDeferred = async {
                    try { ItchAdultProvider.search(query, page) } catch (e: Exception) { logError(e); emptyList() }
                }
                val pghDeferred = async {
                    try { PornGameHubProvider.search(query, page) } catch (e: Exception) { logError(e); emptyList() }
                }
                val results = mutableListOf<CeliumTile>()
                results.addAll(itchDeferred.await())
                results.addAll(pghDeferred.await())
                results.distinctBy { it.url }
            }
        }
    }

    suspend fun loadGame(url: String): CeliumGameTile? = withContext(Dispatchers.IO) {
        if (url.contains("itch.io", ignoreCase = true)) {
            ItchAdultProvider.loadGame(url)
        } else {
            PornGameHubProvider.loadGame(url)
        }
    }
}
