package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.serialization.Serializable

/** Viewing history survives completion; resume state can still advance or expire independently. */
object CeliumHistory {
    @Serializable
    data class Entry(val watchedAt: Long, val card: DataStoreHelper.ResumeWatchingResult)
    private fun folder() = "${DataStoreHelper.currentAccount}/celium_history_v1"

    fun record(parentId: Int?, id: Int?, episode: Int?, season: Int?, position: Long, duration: Long, downloaded: Boolean) {
        if (parentId == null || id == null || duration <= 0 || position < 1000L) return
        val header = getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, parentId.toString())
            ?: getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE_BACKUP, parentId.toString()) ?: return
        val card = DataStoreHelper.ResumeWatchingResult(header.name, header.url, header.apiName, header.type, header.poster,
            DataStoreHelper.PosDur(position, duration), id, parentId, episode, season, downloaded)
        setKey(folder(), parentId.toString(), Entry(System.currentTimeMillis(), card))
    }

    fun recordGame(name: String, url: String, posterUrl: String?) {
        val gameId = url.hashCode()
        val card = DataStoreHelper.ResumeWatchingResult(
            name = name,
            url = url,
            apiName = "PornGamesHub",
            type = TvType.NSFW,
            posterUrl = posterUrl,
            watchPos = DataStoreHelper.PosDur(1000L, 2000L),
            id = gameId,
            parentId = gameId,
            episode = null,
            season = null,
            isFromDownload = false
        )
        setKey(folder(), gameId.toString(), Entry(System.currentTimeMillis(), card))
    }

    fun all(): List<DataStoreHelper.ResumeWatchingResult> {
        val allowAdult = MainAPI.settingsForProvider.enableAdult
        return getKeys(folder()).orEmpty()
            .mapNotNull { getKey<Entry>(it) }
            .sortedByDescending { it.watchedAt }
            .map { it.card }
            .filter { allowAdult || it.type != TvType.NSFW }
    }

    fun remove(parentId: Int?) {
        if (parentId != null) removeKey(folder(), parentId.toString())
        DataStoreHelper.removeLastWatched(parentId)
    }
}

