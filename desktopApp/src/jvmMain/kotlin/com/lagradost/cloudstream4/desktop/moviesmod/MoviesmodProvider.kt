// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from CSX, megix; source revision and changes: desktopApp/PROVIDERS.md.
package com.lagradost.cloudstream4.desktop.moviesmod

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import com.lagradost.api.Log

open class MoviesmodProvider(domain: String) : MainAPI() {
    override var mainUrl = domain
    override var name = "Moviesmod"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "/page/" to "Home",
        "/web-series/on-going/page/" to "Latest Web Series",
        "/movies/page/" to "Latest Movies",
        "/animated-web-series/page/" to "Anime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl + request.data + page).document
        val home = document.select("div.post-cards > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("a").attr("title").replace("Download ", "")
        if (title.isBlank()) return null
        val href = this.select("a").attr("href")
        val posterUrl = this.select("div > img").attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get(mainUrl.toHttpUrl().newBuilder().addPathSegment("search").addPathSegment(query).addPathSegment("page").addPathSegment(page.toString()).build().toString()).document
        checkSearchPage(document)
        val results = document.select("div.post-cards > article").mapNotNull { it.toSearchResult() }
        val hasNext = if(results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        var title = document.select("meta[property=og:title]").attr("content").replace("Download ", "")
        val ogTitle = title
        var posterUrl = document.select("meta[property=og:image]").attr("content")
        var description = document.select("div.imdbwp__teaser").text()
        val div = document.select("div.thecontent").text()
        val tvtype = if (div.contains("season", ignoreCase = true) == true) "series" else "movie"

        Log.d("Moviesmod", "tvtype: $tvtype")

        val imdbUrl = document.select("a[href*=\"imdb.com\"]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")
        val responseData = try {
            if (imdbId.isBlank()) null else tryParseJson<ResponseData>(app.get("$cinemeta_url/$tvtype/$imdbId.json", timeout = 8).text)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        var background: String = posterUrl

        if(responseData != null) {
            description = responseData.meta.description ?: description
            cast = responseData.meta.cast ?: emptyList()
            title = responseData.meta.name ?: title
            genre = responseData.meta.genre ?: emptyList()
            imdbRating = responseData.meta.imdbRating ?: ""
            year = responseData.meta.year ?: ""
            posterUrl = responseData.meta.poster ?: posterUrl
            background = responseData.meta.background ?: background
        }

        if(tvtype == "series") {
            if(title != ogTitle) {
                val checkSeason = Regex("""Season\s*\d*1|S\s*\d*1""").find(ogTitle)
                if (checkSeason == null) {
                    val seasonText = Regex("""Season\s*\d+|S\s*\d+""").find(ogTitle)?.value
                    if(seasonText != null) {
                        title = title + " " + seasonText
                    }
                }
            }

            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, MutableList<String>>()
            val buttons = document.select("a.maxbutton-episode-links,.maxbutton-g-drive,.maxbutton-af-download").distinctBy { it.attr("href") }
            val requests = Semaphore(3)

            kotlinx.coroutines.supervisorScope {
                buttons.map { button ->
                    async { requests.withPermit {
                        try {
                            var link = button.attr("href")

                            val seasonText = button.parent()?.previousElementSibling()?.text().orEmpty()
                            val realSeason = Regex("""(?:Season |S)(\d+)""")
                                .find(seasonText)
                                ?.groupValues
                                ?.get(1)
                                ?.toIntOrNull() ?: 0

                            if (link.contains("url=")) {
                                val base64Value = link.substringAfter("url=")
                                link = base64Decode(base64Value)
                            }


                            val doc = app.get(link).document
                            val hTags = doc.select("h3,h4")


                            var e = 1

                            hTags.forEach { hTag ->
                                val epUrl = hTag.select("a").attr("href").takeIf { it.isNotBlank() } ?: return@forEach

    
                                val key = Pair(realSeason, e)
                                episodesMap.compute(key) { _, current ->
                                    (current ?: mutableListOf()).apply { add(epUrl) }
                                }
                                e++
                            }
                        } catch (e: CancellationException) { throw e }
                    } }
                }.forEach { it.await() }
            }

            for ((key, value) in episodesMap) {
                val episodeInfo = responseData?.meta?.videos?.find { it.season == key.first && it.episode == key.second }
                val data = value.map { source->
                    EpisodeLink(
                        source
                    )
                }
                tvSeriesEpisodes.add(
                    newEpisode(data) {
                        this.name = episodeInfo?.name ?: episodeInfo?.title
                        this.season = key.first
                        this.episode = key.second
                        this.posterUrl = episodeInfo?.thumbnail
                        this.description = episodeInfo?.overview
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        }
        else {
            val data = document.select("a.maxbutton-download-links").mapNotNull {
                var link = it.attr("href")

                if(link.contains("url=")) {
                    val base64Value = link.substringAfter("url=")
                    link = base64Decode(base64Value)
                }

                EpisodeLink(
                    link, resolvePage = true
                )
            }
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap {
            var source = it.source
            if (it.resolvePage) {
                source = app.get(source).document.select("a.maxbutton-1, a.maxbutton-5").attr("href")
                check(source.isNotBlank()) { "Moviesmod's link page no longer contains a source link." }
            }
            if(source.contains("unblocked")) {
                source = bypass(source) ?: return@amap
            }
            val host = source.toHttpUrl().host
            if (host == "thenaukriadda.in" || host.endsWith(".thenaukriadda.in")) source = BrowserLinkResolver.resolve(source)

            if(source.contains("driveseed") || source.contains("driveleech")) {
                Driveleech().getUrl(source, "", subtitleCallback, callback)
            } else {
                check(loadExtractor(source, "", subtitleCallback, callback)) {
                    "No desktop extractor supports ${source.toHttpUrl().host}. Try another title or source."
                }
            }
        }
        return true
    }

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val slug: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val released: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?
    )

    data class ResponseData(
        val meta: Meta
    )

    data class EpisodeLink(
        val source: String,
        val resolvePage: Boolean = false,
    )
}
