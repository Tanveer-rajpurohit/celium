package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Unified repository routing game requests across all game providers:
 * - PornGamesHub (HTML5 in-browser interactive narrative gameplay, direct iframes, saves)
 * - AdultGamesWorld (Ren'Py, Android, Visual Novel releases, story overviews)
 */
object CeliumGameRepository {

    suspend fun getMainPage(sourceId: String, page: Int = 1): List<CeliumShelf> = withContext(Dispatchers.IO) {
        when (sourceId) {
            "adultgamesworld" -> AdultGamesWorldProvider.getMainPage(page)
            "porngamehub", "porngameshub" -> PornGameHubProvider.getMainPage(page)
            else -> {
                // Curated Celium Feed: concurrently fetch both providers and combine
                val pghDeferred = async {
                    try { PornGameHubProvider.getMainPage(page) } catch (e: Exception) { logError(e); emptyList() }
                }
                val agwDeferred = async {
                    try { AdultGamesWorldProvider.getMainPage(page) } catch (e: Exception) { logError(e); emptyList() }
                }

                val pghShelves = pghDeferred.await()
                val agwShelves = agwDeferred.await()

                val combined = mutableListOf<CeliumShelf>()
                combined.addAll(pghShelves)
                combined.addAll(agwShelves)
                combined
            }
        }
    }

    suspend fun search(sourceId: String, query: String, page: Int = 1): List<CeliumTile> = withContext(Dispatchers.IO) {
        when (sourceId) {
            "adultgamesworld" -> AdultGamesWorldProvider.search(query, page)
            "porngamehub", "porngameshub" -> PornGameHubProvider.search(query, page)
            else -> {
                val pghDeferred = async {
                    try { PornGameHubProvider.search(query, page) } catch (e: Exception) { logError(e); emptyList() }
                }
                val agwDeferred = async {
                    try { AdultGamesWorldProvider.search(query, page) } catch (e: Exception) { logError(e); emptyList() }
                }
                val results = mutableListOf<CeliumTile>()
                results.addAll(pghDeferred.await())
                results.addAll(agwDeferred.await())
                results.distinctBy { it.url }
            }
        }
    }

    suspend fun loadGame(url: String): CeliumGameTile? = withContext(Dispatchers.IO) {
        when {
            url.contains("adultgamesworld.com", ignoreCase = true) -> AdultGamesWorldProvider.loadGame(url)
            else -> PornGameHubProvider.loadGame(url)
        }
    }
}
