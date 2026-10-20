package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Live game catalog provider for AdultGamesWorld (https://adultgamesworld.com).
 * Specializes in Android, Ren'Py, PC narrative and visual novel releases.
 */
object AdultGamesWorldProvider {
    const val MAIN_URL = "https://adultgamesworld.com"
    private const val REQUEST_TIMEOUT = 12_000L

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    data class CategorySpec(val key: String, val title: String, val path: String)

    val categories = listOf(
        CategorySpec("agw_latest", "Latest Adult Games", ""),
        CategorySpec("agw_android", "Android Games", "/category/android"),
        CategorySpec("agw_windows", "Windows PC Games", "/category/windows"),
        CategorySpec("agw_mac", "Mac OS Games", "/category/mac")
    )

    private suspend fun fetchHtmlDocument(targetUrl: String): Document? = withContext(Dispatchers.IO) {
        try {
            val response = app.get(targetUrl, headers = headers, timeout = REQUEST_TIMEOUT)
            if (response.code in 200..299 && response.text.length > 500) {
                return@withContext response.document
            }
        } catch (directError: Exception) {
            logError(directError)
        }

        // Proxy fallback
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
        val panels = htmlDoc.select(".masonry-layout__panel-content, .masonry-layout__panel, article.post")

        for (elem in panels) {
            val link = elem.selectFirst("h2 a, a[href*='/202']") ?: elem.selectFirst("a[href]") ?: continue
            val href = link.attr("href").trim()
            val fullUrl = cleanUrl(href)

            if (fullUrl == MAIN_URL || fullUrl == "$MAIN_URL/" || fullUrl.contains("animeapp.org")) continue

            val titleElem = elem.selectFirst("h2 a, h2") ?: link
            val rawTitle = titleElem.text().trim()
            if (rawTitle.isBlank()) continue

            val img = elem.selectFirst(".forimg img, img")
            val rawImg = img?.attr("src")?.ifBlank { img.attr("data-src") }?.trim().orEmpty()
            // Enhance image resolution by stripping thumbnail crop suffix
            val posterUrl = if (rawImg.isNotBlank()) {
                cleanUrl(rawImg.replace(Regex("""-\d+x\d+(?=\.\w+$)"""), ""))
            } else null

            // Extract developer from brackets like [Eroshi] or [DS23Games]
            val devMatch = Regex("""\[([^\]]+)\]""").find(rawTitle)
            val author = devMatch?.groupValues?.get(1)?.trim()

            // Extract version from title
            val verMatch = Regex("""(?:v|Version|Ver)\s*([\d\.]+)""", RegexOption.IGNORE_CASE).find(rawTitle)
            val version = verMatch?.groupValues?.get(1)?.trim()

            // Extract date
            val dateText = elem.ownText().trim().ifBlank {
                elem.selectFirst(".posted-on, .date, time")?.text()?.trim().orEmpty()
            }

            games.add(
                CeliumGameTile(
                    name = rawTitle,
                    url = fullUrl,
                    apiName = "AdultGamesWorld",
                    type = TvType.NSFW,
                    posterUrl = posterUrl,
                    posterHeaders = mapOf("Referer" to MAIN_URL),
                    engine = if (rawTitle.contains("Ren'Py", true)) "Ren'Py" else "Visual Novel",
                    author = author,
                    version = version,
                    updatedAt = dateText.ifBlank { null }
                )
            )
        }
        return games.distinctBy { it.url }
    }

    suspend fun getMainPage(page: Int = 1): List<CeliumShelf> = withContext(Dispatchers.IO) {
        val shelves = mutableListOf<CeliumShelf>()
        for ((index, cat) in categories.withIndex()) {
            try {
                val pageUrl = if (page > 1) {
                    if (cat.path.isBlank()) "$MAIN_URL/page/$page/" else "$MAIN_URL${cat.path}/page/$page/"
                } else {
                    if (cat.path.isBlank()) "$MAIN_URL/" else "$MAIN_URL${cat.path}/"
                }
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
                            hasNext = cards.size >= 8,
                            sourceId = "adultgamesworld"
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
                "$MAIN_URL/page/$page/?s=${cleanQuery.replace(" ", "+")}"
            } else {
                "$MAIN_URL/?s=${cleanQuery.replace(" ", "+")}"
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
            val title = doc.selectFirst("h1.entry-title, h1, .title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return@withContext null

            val contentElem = doc.selectFirst(".entry-content, article .content, .post-content")

            // Collect story text
            val descBuilder = StringBuilder()
            val paragraphs = contentElem?.select("p") ?: emptyList()
            for (p in paragraphs) {
                val text = p.text().trim()
                if (text.isNotBlank() && !text.contains("Download", true) && !text.contains("Patreon", true)) {
                    if (descBuilder.isNotEmpty()) descBuilder.append("\n\n")
                    descBuilder.append(text)
                }
            }
            val desc = descBuilder.toString().ifBlank {
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
            }

            // Collect gallery images
            val screenshots = mutableListOf<String>()
            val imgElements = contentElem?.select("img") ?: emptyList()
            for (img in imgElements) {
                val rawSrc = img.attr("src").ifBlank { img.attr("data-src") }.trim()
                if (rawSrc.isNotBlank() && !rawSrc.contains("logo", true) && !rawSrc.contains("banner", true)) {
                    val clean = cleanUrl(rawSrc.replace(Regex("""-\d+x\d+(?=\.\w+$)"""), ""))
                    screenshots.add(clean)
                }
            }

            val poster = screenshots.firstOrNull()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

            val devMatch = Regex("""\[([^\]]+)\]""").find(title)
            val author = devMatch?.groupValues?.get(1)?.trim()

            val verMatch = Regex("""(?:v|Version|Ver)\s*([\d\.]+)""", RegexOption.IGNORE_CASE).find(title)
            val version = verMatch?.groupValues?.get(1)?.trim()

            val dateText = doc.selectFirst(".posted-on, .date, time")?.text()?.trim()

            val tags = doc.select(".tags a, .tag-links a, .cat-links a").map { it.text().trim() }.filter { it.isNotBlank() }

            // Download or play links
            val actionLink = contentElem?.selectFirst("a[href*='mega'], a[href*='drive'], a[href*='download'], a[href*='f95'], a.btn")?.attr("href")?.trim()

            CeliumGameTile(
                name = title,
                url = url,
                apiName = "AdultGamesWorld",
                type = TvType.NSFW,
                posterUrl = poster?.let { cleanUrl(it) },
                posterHeaders = mapOf("Referer" to MAIN_URL),
                engine = if (title.contains("Ren'Py", true)) "Ren'Py" else "Visual Novel",
                description = desc,
                playUrl = actionLink ?: url,
                author = author,
                version = version,
                tags = tags,
                screenshots = screenshots.distinct(),
                updatedAt = dateText
            )
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}
