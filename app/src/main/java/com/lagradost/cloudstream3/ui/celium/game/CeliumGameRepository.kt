package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance game catalog repository for Celium.
 * Focuses exclusively on directly playable in-browser HTML5, WebGL, and Ren'Py story games
 * with persistent save sync, rich gallery metadata, and zero popups.
 */
object CeliumGameRepository {

    suspend fun getMainPage(sourceId: String, page: Int = 1): List<CeliumShelf> = withContext(Dispatchers.IO) {
        when (sourceId) {
            "porngamehub", "porngameshub" -> PornGameHubProvider.getMainPage(page)
            else -> {
                // Curated Celium Feed: ultra-fast flagship shelves from PornGamesHub
                PornGameHubProvider.getCuratedShelves(page)
            }
        }
    }

    suspend fun search(sourceId: String, query: String, page: Int = 1): List<CeliumTile> = withContext(Dispatchers.IO) {
        PornGameHubProvider.search(query, page)
    }

    suspend fun loadGame(url: String): CeliumGameTile? = withContext(Dispatchers.IO) {
        PornGameHubProvider.loadGame(url)
    }
}
