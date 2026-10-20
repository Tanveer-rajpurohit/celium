package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Live game catalog provider for PornGameHub.
 * Strictly dynamic: parses live web DOM, never serves hardcoded or fake games.
 * Extracts metadata, story descriptions, preview screenshots, and direct play iframes.
 */
object PornGameHubProvider {
    const val MAIN_URL = "https://www.porngamehub.com"
    private const val REQUEST_TIMEOUT = 8_000L

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
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    data class CategorySpec(val key: String, val title: String, val path: String)

    val categories = listOf(
        CategorySpec("pgh_popular", "Popular Story Games", "/games?sort=popular"),
        CategorySpec("pgh_new", "Latest Releases", "/games?sort=new"),
        CategorySpec("pgh_visual_novels", "Visual Novels", "/games/tag/visual-novel"),
        CategorySpec("pgh_rpg", "RPG & Adventure", "/games/tag/rpg")
    )

    private fun cleanUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$MAIN_URL$raw"
        else -> "$MAIN_URL/$raw"
    }

    private fun parseGameCards(htmlDoc: Document): List<CeliumGameTile> {
        val games = mutableListOf<CeliumGameTile>()
        val cardElements = htmlDoc.select(
            "div.game-card, div.game-item, div.thumb-item, div.game, article.game, div.item-game, div.grid-item"
        )

        for (elem in cardElements) {
            val link = elem.selectFirst("a[href]") ?: continue
            val href = link.attr("href").trim()
            val fullUrl = cleanUrl(href)

            if (!fullUrl.contains("/game/") && !fullUrl.contains("/games/")) continue

            val titleElem = elem.selectFirst("h2, h3, h4, .game-title, .title, .name") ?: link
            val title = titleElem.text().trim().ifBlank {
                link.attr("title").trim().ifBlank {
                    elem.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                }
            }
            if (title.isBlank()) continue

            val img = elem.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[src]")
            val posterUrl = img?.let {
                val src = it.attr("data-src").ifBlank {
                    it.attr("data-original").ifBlank {
                        it.attr("data-lazy-src").ifBlank {
                            it.attr("src")
                        }
                    }
                }.trim()
                cleanUrl(src)
            }

            val tagList = elem.select(".tags a, .tag, .genre, .badge").map { it.text().trim() }.filter { it.isNotBlank() }

            games.add(
                CeliumGameTile(
                    name = title,
                    url = fullUrl,
                    apiName = "PornGameHub",
                    type = TvType.NSFW,
                    posterUrl = posterUrl,
                    posterHeaders = mapOf("Referer" to MAIN_URL),
                    engine = if (tagList.any { it.contains("Twine", true) }) "Twine" else "HTML5",
                    tags = tagList
                )
            )
        }
        return games.distinctBy { it.url }
    }

    suspend fun getMainPage(page: Int = 1): List<CeliumShelf> = withContext(Dispatchers.IO) {
        val shelves = mutableListOf<CeliumShelf>()
        for ((index, cat) in categories.withIndex()) {
            try {
                val pageUrl = if (page > 1) "$MAIN_URL${cat.path}&page=$page" else "$MAIN_URL${cat.path}"
                val response = app.get(pageUrl, headers = headers, timeout = REQUEST_TIMEOUT)
                val games = parseGameCards(response.document)
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
                            sourceId = "porngamehub"
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
            val games = parseGameCards(response.document)
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

            val title = doc.selectFirst("h1.game-title, h1, .title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return@withContext null

            // Parse rich story overview with preserved paragraphs
            val descElem = doc.selectFirst(
                ".game-description, #description, .entry-content, .game-info, .description, div[itemprop='description'], .game-story, .story, div.about-game, div.game-details"
            )
            val desc = descElem?.let { elem ->
                elem.select("script, style, iframe, noscript, .tags, .buttons, .share-buttons").remove()
                elem.wholeText().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
            }?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?: doc.selectFirst(".game-poster img, .thumbnail img, img.cover")?.attr("src")?.trim()

            // Parse screenshot gallery previews
            val screenshotElements = doc.select(
                ".game-screenshots img, .screenshots img, .gallery img, .slider img, .preview-images img, #screenshots a, div[class*='screenshot'] img, a[data-fancybox], a.gallery-item, div[class*='preview'] img, div.swiper-slide img, div.carousel img, div.game-media img"
            )
            val badKeywords = listOf("avatar", "icon", "logo", "banner", "advert", "button", "play-icon", "tracker", "facebook", "twitter", "discord")
            val screenshots = screenshotElements.mapNotNull { elem ->
                val rawSrc = elem.attr("data-full").ifBlank {
                    elem.attr("data-highres").ifBlank {
                        elem.attr("data-src").ifBlank {
                            elem.attr("data-original").ifBlank {
                                elem.attr("data-lazy-src").ifBlank {
                                    elem.attr("src").ifBlank {
                                        elem.attr("href")
                                    }
                                }
                            }
                        }
                    }
                }.trim()

                if (rawSrc.isNotBlank() && badKeywords.none { rawSrc.contains(it, ignoreCase = true) }) {
                    val clean = cleanUrl(rawSrc)
                    if (clean.contains(".jpg", true) || clean.contains(".jpeg", true) || clean.contains(".png", true) || clean.contains(".webp", true) || clean.contains(".avif", true)) {
                        clean
                    } else null
                } else null
            }.distinct()

            val author = doc.selectFirst(".developer, .author, .publisher, a[href*='/developer/'], a[href*='/author/']")?.text()?.trim()
            val version = doc.selectFirst(".version, .game-version, span[class*='version']")?.text()?.trim()

            // Locate direct iframe player or canvas link
            val iframe = doc.selectFirst(
                "iframe#game_frame, iframe#game-frame, iframe.game-player, iframe[src*='game'], iframe[src*='embed'], iframe[src*='play'], iframe[src*='html5'], iframe[data-src*='game'], iframe[data-src*='embed'], iframe[data-src*='play'], iframe[data-src*='html5'], div#game-container iframe, div.game-area iframe"
            )
            val rawIframeSrc = iframe?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.trim()
            val iframeSrc = rawIframeSrc?.takeIf { it.isNotBlank() }?.let { cleanUrl(it) }

            val playLink = doc.selectFirst("a#play-button, a.play-game, a.btn-play, a[href*='/play/'], a[href*='/game-play/']")?.attr("href")?.let { cleanUrl(it) }

            val playUrl = iframeSrc ?: playLink ?: url
            val engine = when {
                playUrl.contains("twine", true) || desc.contains("twine", true) || desc.contains("sugarcube", true) -> "Twine SugarCube"
                playUrl.contains("renpy", true) || desc.contains("renpy", true) -> "Ren'Py Web"
                playUrl.contains("unity", true) -> "Unity WebGL"
                playUrl.contains("rpgmaker", true) || desc.contains("rpg maker", true) -> "RPG Maker"
                else -> "HTML5"
            }

            val tags = doc.select(".tags a, .genres a, .categories a, .game-tags a").map { it.text().trim() }.filter { it.isNotBlank() }

            CeliumGameTile(
                name = title,
                url = url,
                apiName = "PornGameHub",
                type = TvType.NSFW,
                posterUrl = poster?.let { cleanUrl(it) },
                posterHeaders = mapOf("Referer" to MAIN_URL),
                engine = engine,
                description = desc,
                playUrl = playUrl,
                author = author,
                version = version,
                tags = tags,
                screenshots = screenshots
            )
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}
