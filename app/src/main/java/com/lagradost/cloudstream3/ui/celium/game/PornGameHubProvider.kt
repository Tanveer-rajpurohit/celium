package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Live game catalog provider for PornGamesHub (https://porngameshub.com).
 * Strictly dynamic: parses live web DOM and embedded JSON payload, never serves mock games.
 * Extracts direct HTML5 iframe runtimes, high-res galleries, story overviews, and save-state hooks.
 */
object PornGameHubProvider {
    const val MAIN_URL = "https://porngameshub.com"
    private const val REQUEST_TIMEOUT = 12_000L

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
        CategorySpec("pgh_popular", "Popular Games", "/popular"),
        CategorySpec("pgh_updated", "Recently Updated", "/updated"),
        CategorySpec("pgh_best", "Top Rated", "/best"),
        CategorySpec("pgh_new", "New Releases", "/new"),
        CategorySpec("pgh_vn", "Visual Novels", "/visual-novels"),
        CategorySpec("pgh_3d", "3D Games", "/3d"),
        CategorySpec("pgh_2d", "2D Games", "/2d"),
        CategorySpec("pgh_adventure", "Adventure", "/adventure"),
        CategorySpec("pgh_choice", "Choice & Dating", "/choice"),
        CategorySpec("pgh_action", "Action", "/action")
    )

    /**
     * Resilient HTML fetcher: attempts direct connection first, with seamless fallback
     * to proxy extraction if regional ISP DNS sinkholing or TCP resets occur.
     */
    private suspend fun fetchHtmlDocument(targetUrl: String): Document? = withContext(Dispatchers.IO) {
        try {
            val response = app.get(targetUrl, headers = headers, timeout = REQUEST_TIMEOUT)
            if (response.code in 200..299 && response.text.length > 500) {
                return@withContext response.document
            }
        } catch (directError: Exception) {
            logError(directError)
        }

        // Mirror fallback for ISP-filtered networks
        try {
            val proxyUrl = "https://r.jina.ai/$targetUrl"
            val fallbackResponse = app.get(
                proxyUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "X-Return-Format" to "html"
                ),
                timeout = REQUEST_TIMEOUT + 4_000L
            )
            if (fallbackResponse.text.length > 500) {
                return@withContext Jsoup.parse(fallbackResponse.text, targetUrl)
            }
        } catch (fallbackError: Exception) {
            logError(fallbackError)
        }
        null
    }

    private fun cleanUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$MAIN_URL$raw"
        else -> "$MAIN_URL/$raw"
    }

    private fun parseGameCards(htmlDoc: Document): List<CeliumGameTile> {
        val games = mutableListOf<CeliumGameTile>()
        val cardElements = htmlDoc.select(
            "div.archive-item, div.single-gallery, .archive-list .single-gallery, div.game-card, div.game-item"
        )

        for (elem in cardElements) {
            val link = elem.selectFirst("a[href]") ?: continue
            val href = link.attr("href").trim()
            val fullUrl = cleanUrl(href)

            if (fullUrl.isBlank() || fullUrl == MAIN_URL || fullUrl == "$MAIN_URL/") continue

            val titleElem = elem.selectFirst(".item-content h4, h4, h3, h2, .title") ?: link
            val rawTitle = titleElem.text().trim().ifBlank {
                link.attr("title").trim().ifBlank {
                    elem.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                }
            }
            if (rawTitle.isBlank()) continue

            val img = elem.selectFirst("img[data-src], source[data-srcset], img[data-original], img[src]")
            val posterUrl = img?.let {
                val src = it.attr("data-src").ifBlank {
                    it.attr("data-srcset").ifBlank {
                        it.attr("data-original").ifBlank {
                            it.attr("src")
                        }
                    }
                }.trim()
                cleanUrl(src)
            }

            val viewsText = elem.selectFirst(".item-icons span.text-primary, .item-icons span[class*='eye']")?.text()?.trim()
            val ratingText = elem.selectFirst(".item-icons span.text-yellow-400, .item-icons span[class*='star']")?.text()?.trim()
            val previewDesc = elem.selectFirst(".preview-hover p")?.text()?.trim().orEmpty()

            // Extract engine tag if discernible from title or description
            val engine = when {
                rawTitle.contains("Ren'Py", true) || previewDesc.contains("Ren'Py", true) -> "Ren'Py Web"
                rawTitle.contains("Twine", true) || previewDesc.contains("Twine", true) -> "Twine"
                rawTitle.contains("Unity", true) || previewDesc.contains("Unity", true) -> "Unity WebGL"
                else -> "HTML5"
            }

            games.add(
                CeliumGameTile(
                    name = rawTitle,
                    url = fullUrl,
                    apiName = "PornGamesHub",
                    type = TvType.NSFW,
                    posterUrl = posterUrl,
                    posterHeaders = mapOf("Referer" to MAIN_URL),
                    engine = engine,
                    description = previewDesc,
                    views = viewsText?.let { "$it views" },
                    rating = ratingText?.let { "$it ★" }
                )
            )
        }
        return games.distinctBy { it.url }
    }

    suspend fun getMainPage(page: Int = 1): List<CeliumShelf> = withContext(Dispatchers.IO) {
        val shelves = mutableListOf<CeliumShelf>()
        for ((index, cat) in categories.withIndex()) {
            try {
                val pageUrl = if (page > 1) "$MAIN_URL${cat.path}/$page" else "$MAIN_URL${cat.path}"
                val doc = fetchHtmlDocument(pageUrl) ?: continue
                val games = parseGameCards(doc)
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
                            sourceId = "porngameshub"
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
            val searchUrl = if (page > 1) {
                "$MAIN_URL/?search=${cleanQuery.replace(" ", "+")}&page=$page"
            } else {
                "$MAIN_URL/?search=${cleanQuery.replace(" ", "+")}"
            }
            val doc = fetchHtmlDocument(searchUrl) ?: return@withContext emptyList()
            val games = parseGameCards(doc)
            games.map { CeliumTile.from(it) }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    suspend fun loadGame(url: String): CeliumGameTile? = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(url) ?: return@withContext null
            val pageHtml = doc.html()

            // 1. Parse window.__INIT_DATA__['TheGame'] JSON
            var jsonTitle: String? = null
            var jsonIframe: String? = null
            var jsonViews: Long? = null
            var jsonPublished: String? = null
            var jsonUpgraded: String? = null
            val jsonImages = mutableListOf<String>()

            val initPattern = Regex("""window\.__INIT_DATA__\['TheGame'\]\s*=\s*(\{.*?\});\s*(?:window|\n|<)""", RegexOption.DOT_MATCHES_ALL)
            val match = initPattern.find(pageHtml)
            if (match != null) {
                try {
                    val jsonStr = match.groupValues[1]
                    val obj = JSONObject(jsonStr)
                    jsonTitle = obj.optString("title").takeIf { it.isNotBlank() }
                    jsonIframe = obj.optString("link_iframe").takeIf { it.isNotBlank() }
                    jsonViews = obj.optLong("view_count", -1L).takeIf { it > 0 }
                    jsonPublished = obj.optString("published_at").takeIf { it.isNotBlank() }
                    jsonUpgraded = obj.optString("upgraded_at").takeIf { it.isNotBlank() }

                    val imagesArr = obj.optJSONArray("images")
                    if (imagesArr != null) {
                        for (i in 0 until imagesArr.length()) {
                            val imgObj = imagesArr.optJSONObject(i)
                            val imgUrl = imgObj?.optString("url")?.trim()
                            if (!imgUrl.isNullOrBlank()) {
                                jsonImages.add(cleanUrl(imgUrl))
                            }
                        }
                    }
                } catch (jsonEx: Exception) {
                    logError(jsonEx)
                }
            }

            val title = jsonTitle
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return@withContext null

            // 2. Parse story description with clean preserved paragraphs
            val descBuilder = StringBuilder()
            val descHeading = doc.selectFirst("h2:contains(Description), h2:contains(About)")
            if (descHeading != null) {
                var sibling = descHeading.nextElementSibling()
                while (sibling != null && !sibling.tagName().startsWith("h", ignoreCase = true)) {
                    if (sibling.tagName().equals("p", ignoreCase = true)) {
                        val pText = sibling.text().trim()
                        if (pText.isNotBlank()) {
                            if (descBuilder.isNotEmpty()) descBuilder.append("\n\n")
                            descBuilder.append(pText)
                        }
                    }
                    sibling = sibling.nextElementSibling()
                }
            }

            if (descBuilder.isBlank()) {
                val descElem = doc.selectFirst(".game-description, #description, .entry-content, .description")
                descElem?.let { elem ->
                    elem.select("script, style, iframe, noscript, .tags, .buttons, .share-buttons").remove()
                    val text = elem.wholeText().lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n\n")
                    descBuilder.append(text)
                }
            }

            val desc = descBuilder.toString().ifBlank {
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
            }

            // 3. Parse poster image
            val poster = jsonImages.firstOrNull()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?: doc.selectFirst(".game-poster img, .thumbnail img, img.cover")?.attr("src")?.trim()

            // 4. Parse full screenshots gallery
            val screenshots = mutableListOf<String>()
            screenshots.addAll(jsonImages)

            val domScreenshots = doc.select("img[data-src*='/uploads/public/'], a[href*='/uploads/public/']")
            val badKeywords = listOf("logo", "avatar", "icon", "banner", "advert", "button", "play-icon")
            for (elem in domScreenshots) {
                val rawSrc = elem.attr("data-src").ifBlank { elem.attr("href") }.trim()
                if (rawSrc.isNotBlank() && badKeywords.none { rawSrc.contains(it, ignoreCase = true) }) {
                    val clean = cleanUrl(rawSrc)
                    if (clean.endsWith(".jpg", true) || clean.endsWith(".jpeg", true) || clean.endsWith(".png", true) || clean.endsWith(".webp", true)) {
                        screenshots.add(clean)
                    }
                }
            }

            // 5. Developer and Studio
            var author: String? = null
            val devHeading = doc.selectFirst("h2:contains(Developer)")
            if (devHeading != null) {
                author = devHeading.nextElementSibling()?.selectFirst("h3, a, p")?.text()?.trim()
            }
            if (author.isNullOrBlank()) {
                author = doc.selectFirst(".developer, .author, a[href*='/developer/']")?.text()?.trim()
            }

            // 6. Tags and Genres
            val tagElements = doc.select("h2:contains(Tags) ~ a, .tags a, .genres a, a[href^='/']")
                .filter { it.attr("href").count { c -> c == '/' } == 1 } // single path segment like /visual-novels
            val tags = tagElements.map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }.distinct()

            // 7. Walkthrough PDF link
            val guideLink = doc.selectFirst("a[href*='.pdf']")?.attr("href")?.trim()?.let { cleanUrl(it) }

            // 8. Views and dates formatting
            val viewsStr = jsonViews?.let {
                when {
                    it >= 1_000_000 -> String.format("%.1fM views", it / 1_000_000.0)
                    it >= 1_000 -> String.format("%.1fK views", it / 1_000.0)
                    else -> "$it views"
                }
            } ?: doc.selectFirst("*:matchesOwn(Views:)")?.text()?.trim()

            val updatedStr = jsonUpgraded ?: doc.selectFirst("*:matchesOwn(updated on)")?.text()?.trim()

            val ratingVal = doc.selectFirst("*:matchesOwn(Rating:)")?.text()?.trim()
                ?.substringAfter("Rating:")?.trim()

            // 9. Play URL
            val playUrl = jsonIframe?.let { cleanUrl(it) } ?: url

            val engine = when {
                tags.any { it.contains("Twine", true) } || desc.contains("Twine", true) || desc.contains("SugarCube", true) -> "Twine SugarCube"
                tags.any { it.contains("RenPy", true) } || desc.contains("Ren'Py", true) || playUrl.contains("renpy", true) -> "Ren'Py Web"
                tags.any { it.contains("Unity", true) } || playUrl.contains("unity", true) -> "Unity WebGL"
                tags.any { it.contains("3D", true) } -> "3D WebGL"
                tags.any { it.contains("2D", true) } -> "2D Canvas"
                else -> "HTML5"
            }

            CeliumGameTile(
                name = title,
                url = url,
                apiName = "PornGamesHub",
                type = TvType.NSFW,
                posterUrl = poster?.let { cleanUrl(it) },
                posterHeaders = mapOf("Referer" to MAIN_URL),
                engine = engine,
                description = desc,
                playUrl = playUrl,
                author = author,
                version = if (title.contains("[")) title.substringAfter("[").substringBefore("]").trim() else null,
                tags = tags,
                screenshots = screenshots.distinct(),
                updatedAt = updatedStr,
                views = viewsStr,
                rating = ratingVal?.let { "$it ★" },
                guideUrl = guideLink
            )
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}
