package com.lagradost.cloudstream3.ui.celium

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Event
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

enum class CeliumSection(val label: String) {
    Movies("Movies"), Series("TV Shows"), Anime("Anime"), Adult("Adult")
}

data class CeliumSource(
    val id: String,
    val label: String,
    val plugin: String,
    val repository: String,
    val adult: Boolean = false,
    val aliases: Set<String> = emptySet(),
    val unavailableReason: String? = null,
    val anime: Boolean = false,
)

/** Why a catalog source is not ready, keyed by source id. Written by the plugin loader; a successful load clears the entry. */
object CeliumSourceHealth {
    const val REPOSITORY_FAILED = "Source list unavailable. Retrying next launch."
    const val NOT_OFFERED = "Not offered by its repository."
    const val DOWNLOAD_FAILED = "Download failed. Retrying next launch."
    const val LOAD_FAILED = "Could not start. Retrying next launch."

    val reasons = MutableStateFlow<Map<String, String>>(emptyMap())

    fun reason(id: String): String? = reasons.value[id]

    fun set(id: String, reason: String) = reasons.update { it + (id to reason) }

    fun clear(id: String) = reasons.update { it - id }
}

/** Explicit policy: a long source picker must never become an all-provider search. */
object CeliumSources {
    val ready = Event<Boolean>()
    const val PHISHER = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
    const val CXXX = "https://raw.githubusercontent.com/phisher98/CXXX/builds/CXXX.json"
    const val GIZLI = "https://raw.githubusercontent.com/Kraptor123/Cs-GizliKeyif/refs/heads/master/repo.json"
    const val CNC = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
    const val CSX = "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json"

    val all = listOf(
        CeliumSource("multi", "MultiMovies", "MultiMoviesProvider", PHISHER, aliases = setOf("MultiMovies")),
        CeliumSource("hdhub", "HDhub4u", "HDhub4u", PHISHER),
        CeliumSource("hdmovie2", "HDmovie2", "Hdmovie2", PHISHER),
        CeliumSource("pika", "PikaShow", "PikashowProvider", CNC, aliases = setOf("Pikashow")),
        CeliumSource("netmirror", "NetMirror Mobile", "CNC Verse Mobile", CNC),
        CeliumSource("movies4u", "Movies4u", "Movies4u", PHISHER),
        CeliumSource("moviesmod", "Moviesmod", "Moviesmod", CSX),
        // Anime: AniKoto first, HiAnime second, so the Celium Feed pool starts with them.
        CeliumSource("anikoto", "AniKoto", "AniKoto", PHISHER, anime = true),
        CeliumSource("hianime", "HiAnime", "HiAnime", PHISHER, anime = true),
        CeliumSource("anichi", "Anichi", "Anichi", PHISHER, anime = true),
        CeliumSource("animedekho", "AnimeDekho", "AnimeDekhoProvider", PHISHER, anime = true),
        CeliumSource("beeg", "Beeg", "Beeg", GIZLI, true),
        CeliumSource("pornhat", "PornHat", "PornHat", GIZLI, true),
        CeliumSource("freeporn", "FreePornVideos", "FreePornVideos", GIZLI, true),
        CeliumSource("pornhub", "PornHub", "PornHub", GIZLI, true),
        CeliumSource("fapgoat", "FapGoat", "FapGoat", GIZLI, true),
        CeliumSource("fx", "FX PornHD", "FXPrnHD", CXXX, true),
        CeliumSource("hq", "HQPorner", "HQPorner", GIZLI, true),
        CeliumSource("porn00", "Porn00", "Porn00", GIZLI, true),
        CeliumSource("xhamster", "Xhamster", "Xhamster", GIZLI, true),
        CeliumSource("xvideos", "Xvideos", "xVideos", GIZLI, true),
        CeliumSource("youperv", "Youperv", "Youperv", GIZLI, true, aliases = setOf("Yourprev")),
        CeliumSource("xnxx", "XNXX", "XNXX", CXXX, adult = true),
    ) // 23 entries

    /** First [limit] candidates, in catalog order, whose id is loaded. The Celium Feed entry is never part of a pool. */
    fun orderedPool(candidates: List<CeliumSource>, loadedIds: Set<String>, limit: Int = 3): List<CeliumSource> =
        candidates.asSequence().filter { it.id != "celium" && it.id in loadedIds }.take(limit).toList()

    fun enabled(source: CeliumSource): Boolean = getKey<Boolean>("celium_source_enabled_${source.id}") != false

    fun setEnabled(source: CeliumSource, value: Boolean) {
        setKey("celium_source_enabled_${source.id}", value)
        ready.invoke(false)
    }

    fun curated(section: CeliumSection) = CeliumSource("celium", "Celium Feed", "", "", adult = section == CeliumSection.Adult, anime = section == CeliumSection.Anime)

    fun choices(section: CeliumSection): List<CeliumSource> = listOf(curated(section)) + all.filter { enabled(it) &&
        when (section) {
            CeliumSection.Adult -> it.adult
            CeliumSection.Anime -> it.anime
            else -> !it.adult && !it.anime
        }
    }

    fun selected(section: CeliumSection, savedId: String?): CeliumSource =
        choices(section).firstOrNull { it.id == savedId } ?: choices(section).first()

    fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun allowsPlugin(repository: String, internalName: String): Boolean = all.any {
        it.unavailableReason == null && enabled(it) && (!it.adult || MainAPI.settingsForProvider.enableAdult) && it.repository == repository && it.plugin == internalName
    }

    fun resolve(source: CeliumSource, providers: List<MainAPI>): MainAPI? {
        if (source.unavailableReason != null || !enabled(source) || (source.adult && !MainAPI.settingsForProvider.enableAdult)) return null
        // Prefer the artifact identity; display names can change independently of the plugin name.
        val fileNames = setOf("${source.plugin.replace(" ", "")}.${source.plugin.hashCode()}.cs3", "${source.plugin}.cs3", "${source.plugin}.zip")
        val candidates = providers.filter { api ->
            api.sourcePlugin?.substringAfterLast('/')?.substringAfterLast('\\') in fileNames
        }.ifEmpty {
            val names = (source.aliases + source.label + source.plugin).map(::normalize).toSet()
            providers.filter { it.sourcePlugin == null && normalize(it.name) in names }
        }
        return candidates.firstOrNull { api ->
            if (source.adult) TvType.NSFW in api.supportedTypes
            else if (source.anime) api.supportedTypes.any { it == TvType.Anime || it == TvType.AnimeMovie || it == TvType.OVA }
            else api.supportedTypes.any { it == TvType.Movie || it == TvType.TvSeries }
        }
    }

    fun accepts(section: CeliumSection, type: TvType?): Boolean = when (section) {
        CeliumSection.Adult -> type == TvType.NSFW || type == null
        CeliumSection.Movies -> type == TvType.Movie || type == null
        CeliumSection.Series -> type == TvType.TvSeries || type == TvType.AsianDrama || type == null
        CeliumSection.Anime -> type == TvType.Anime || type == TvType.AnimeMovie || type == TvType.OVA || type == null
    }

    fun isAdult(type: TvType?, apiName: String): Boolean {
        if (type == TvType.NSFW) return true
        if (all.any { it.adult && normalize(apiName) in (it.aliases + it.plugin + it.label).map(::normalize) }) return true
        return APIHolder.apis.withLock { APIHolder.apis.any { it.name == apiName && TvType.NSFW in it.supportedTypes } }
    }

    fun catalogIdentity(card: CeliumTile): String = if (card.year != null && card.type != null) {
        "title:${normalize(card.name)}:${card.type}:${card.year}"
    } else "url:${card.apiName}:${card.url}"

    fun filter(section: CeliumSection, cards: List<SearchResponse>): List<SearchResponse> =
        cards.filter { accepts(section, it.type) }.distinctBy { it.apiName to it.url }
}
