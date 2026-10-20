package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * High-speed catalog provider for in-browser playable adult story games and visual novels from itch.io.
 * Strictly dynamic: parses live web DOM, never serves hardcoded or mock games.
 * Extracts metadata, story overviews, preview screenshot galleries, and direct HTML5/WebGL iframes.
 */
object ItchAdultProvider {
    const val MAIN_URL = "https://itch.io"
    private const val REQUEST_TIMEOUT = 15_000L

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1"
    )

    data class CategorySpec(val key: String, val title: String, val path: String)

    val categories = listOf(
        CategorySpec("itch_popular", "Popular Adult Games", "/games/platform-web/tag-adult"),
        CategorySpec("itch_vn", "Visual Novels & Stories", "/games/platform-web/genre-visual-novel/tag-adult"),
        CategorySpec("itch_erotic", "Erotic & Dating Sims", "/games/platform-web/tag-erotic"),
        CategorySpec("itch_twine", "Interactive Fiction & Twine", "/games/platform-web/tag-interactive-fiction/tag-adult"),
        CategorySpec("itch_eroge", "Anime & Eroge", "/games/platform-web/tag-eroge")
    )

    private fun cleanUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$MAIN_URL$raw"
        else -> "$MAIN_URL/$raw"
    }

    private fun parseGameCells(htmlDoc: Document): List<CeliumGameTile> {
        val games = mutableListOf<CeliumGameTile>()
        val cellElements = htmlDoc.select("div.game_cell")

        for (elem in cellElements) {
            val titleLink = elem.selectFirst("a.title.game_link") ?: elem.selectFirst("a.title") ?: elem.selectFirst("a.thumb_link") ?: continue
            val href = titleLink.attr("href").trim()
            if (href.isBlank()) continue
            val fullUrl = cleanUrl(href)

            val title = titleLink.text().trim().ifBlank {
                elem.selectFirst("div.game_title")?.text()?.trim().orEmpty()
            }
            if (title.isBlank()) continue

            val img = elem.selectFirst("img[data-lazy_src], img[src]")
            val posterUrl = img?.let {
                val src = it.attr("data-lazy_src").ifBlank { it.attr("src") }.trim()
                if (src.isNotBlank()) cleanUrl(src) else null
            }

            val author = elem.selectFirst("div.game_author a")?.text()?.trim()
            val genre = elem.selectFirst("div.game_genre")?.text()?.trim()
            val desc = elem.selectFirst("div.game_text")?.text()?.trim().orEmpty()

            val tags = mutableListOf<String>()
            genre?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
            tags.add("Web")
            if (fullUrl.contains("twine", true) || desc.contains("twine", true)) tags.add("Twine")

            val engine = when {
                desc.contains("twine", true) || desc.contains("sugarcube", true) -> "Twine SugarCube"
                desc.contains("renpy", true) -> "Ren'Py Web"
                desc.contains("unity", true) -> "Unity WebGL"
                else -> "HTML5"
            }

            games.add(
                CeliumGameTile(
                    name = title,
                    url = fullUrl,
                    apiName = "ItchAdult",
                    type = TvType.NSFW,
                    posterUrl = posterUrl,
                    posterHeaders = mapOf("Referer" to MAIN_URL),
                    engine = engine,
                    description = desc,
                    author = author,
                    tags = tags
                )
            )
        }
        return games.distinctBy { it.url }
    }

    suspend fun getMainPage(page: Int = 1): List<CeliumShelf> = withContext(Dispatchers.IO) {
        val shelves = mutableListOf<CeliumShelf>()
        for ((index, cat) in categories.withIndex()) {
            try {
                val pageUrl = if (page > 1) "$MAIN_URL${cat.path}?page=$page" else "$MAIN_URL${cat.path}"
                val response = app.get(pageUrl, headers = headers, timeout = REQUEST_TIMEOUT)
                val games = parseGameCells(response.document)
                if (games.isNotEmpty()) {
                    val cards = games.map { CeliumTile.from(it) }
                    shelves.add(
                        CeliumShelf(
                            key = "${cat.key}_$page",
                            title = cat.title,
                            cards = cards,
                            sectionIndex = index,
                            page = page,
                            hasNext = cards.size >= 12,
                            sourceId = "itch_adult"
                        )
                    )
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        shelves
    }

    suspend fun search(query: String, page: Int = 1): List<CeliumTile> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        try {
            val searchUrl = "$MAIN_URL/search?q=${cleanQuery.replace(" ", "+")}&page=$page"
            val response = app.get(searchUrl, headers = headers, timeout = REQUEST_TIMEOUT)
            val games = parseGameCells(response.document)
            games.map { CeliumTile.from(it) }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    suspend fun loadGame(url: String): CeliumGameTile? = withContext(Dispatchers.IO) {
        try {
            val response = app.get(url, headers = headers, timeout = REQUEST_TIMEOUT)
            val doc = response.document

            val title = doc.selectFirst("h1.game_title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return@withContext null

            // Parse formatted narrative story description
            val descElem = doc.selectFirst("div.formatted_description, div.game_description, div.entry-content")
            val desc = descElem?.let { elem ->
                elem.select("script, style, iframe, noscript").remove()
                elem.wholeText().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
            }?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?: doc.selectFirst("div.header img, div.cover img, img.screenshot")?.attr("src")?.trim()

            // Parse high-res screenshot previews
            val screenshotElements = doc.select(
                "div.screenshot_list a[href], a.screenshot[href], div.screenshot_list img, meta[property='og:image']"
            )
            val screenshots = screenshotElements.mapNotNull { elem ->
                val src = elem.attr("href").ifBlank { elem.attr("src").ifBlank { elem.attr("content") } }.trim()
                if (src.isNotBlank() && (src.contains(".png", true) || src.contains(".jpg", true) || src.contains(".jpeg", true) || src.contains(".webp", true))) {
                    cleanUrl(src)
                } else null
            }.distinct()

            val author = doc.selectFirst("span.game_author a, div.game_author a, a[href*='/author/']")?.text()?.trim()

            // Resolve direct iframe canvas URL from load button or embed container
            var playUrl: String? = null
            val loadBtn = doc.selectFirst("button.load_iframe_btn, div.load_iframe_btn, a.load_iframe_btn")
            val dataIframe = loadBtn?.attr("data-iframe")?.trim().orEmpty()
            if (dataIframe.isNotBlank()) {
                val unescaped = Parser.unescapeEntities(dataIframe, false)
                val iframeDoc = Jsoup.parse(unescaped)
                playUrl = iframeDoc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let { cleanUrl(it) }
            }

            if (playUrl == null) {
                val directIframe = doc.selectFirst("div.game_frame iframe, iframe.game_frame, iframe[src*='html-classic.itch.zone'], iframe[src*='html']")
                playUrl = directIframe?.attr("src")?.takeIf { it.isNotBlank() }?.let { cleanUrl(it) }
            }

            val finalPlayUrl = playUrl ?: url
            val engine = when {
                finalPlayUrl.contains("twine", true) || desc.contains("twine", true) || desc.contains("sugarcube", true) -> "Twine SugarCube"
                finalPlayUrl.contains("renpy", true) || desc.contains("renpy", true) -> "Ren'Py Web"
                finalPlayUrl.contains("unity", true) || desc.contains("unity", true) -> "Unity WebGL"
                else -> "HTML5"
            }

            val tags = doc.select("div.game_tags a, div.tags a, a.tag").map { it.text().trim() }.filter { it.isNotBlank() }

            CeliumGameTile(
                name = title,
                url = url,
                apiName = "ItchAdult",
                type = TvType.NSFW,
                posterUrl = poster?.let { cleanUrl(it) },
                posterHeaders = mapOf("Referer" to MAIN_URL),
                engine = engine,
                description = desc,
                playUrl = finalPlayUrl,
                author = author,
                tags = tags,
                screenshots = screenshots
            )
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}
