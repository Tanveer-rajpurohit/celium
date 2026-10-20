package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class CeliumGameTile(
    override val name: String,
    override val url: String,
    override val apiName: String = "PornGamesHub",
    override var type: TvType? = TvType.NSFW,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    val engine: String = "HTML5",
    val description: String = "",
    val playUrl: String? = null,
    val author: String? = null,
    val version: String? = null,
    val tags: List<String> = emptyList(),
    val screenshots: List<String> = emptyList(),
    val updatedAt: String? = null,
    val views: String? = null,
    val rating: String? = null,
    val guideUrl: String? = null,
) : SearchResponse {
    @Transient override var id: Int? = null
    @Transient override var quality: SearchQuality? = null
    @Transient override var score: Score? = null
}

@Serializable
data class CeliumGameSaveSnapshot(
    val gameId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val localStorageDump: Map<String, String> = emptyMap(),
    val exportCode: String = "",
)
