package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Live game catalog provider for PornGamesHub (https://porngameshub.com).
 * High-performance parallel async fetching, deep category coverage, direct HTML5 iframe runtimes,
 * rich galleries, story overviews, and save-state hooks.
 */
object PornGameHubProvider {
    const val MAIN_URL = "https://porngameshub.com"
    private const val REQUEST_TIMEOUT = 10_000L

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
        "Upgrade-Insecure-Requests" to "1",
        "Cookie" to "age_verified=1; over18=1; age_gate=1; compliance=1; consent=1; has_verified_age=true; adult_consent=1; age_check=1; age=18; is_adult=1; pgh_age=1; age_verification=1; legal_age=1; user_age_verified=1"
    )

    private val BANNED_KEYWORDS = listOf(
        "policy", "verification", "verify", "terms", "privacy", "dmca", "compliance",
        "2257", "rta", "disclaimer", "login", "register", "cookie", "cookies",
        "contact", "about", "faq", "copyright", "report", "parental", "advertising",
        "affiliate", "affiliates", "webmaster", "law enforcement", "support"
    )

    fun isGame(title: String, url: String): Boolean {
        val cleanTitle = title.trim().lowercase(java.util.Locale.ROOT)
        val cleanUrl = url.trim().lowercase(java.util.Locale.ROOT)
        if (cleanTitle.isBlank() || cleanUrl.isBlank()) return false
        if (cleanTitle in listOf("home", "games", "play", "all games", "menu", "search", "adult games")) return false

        for (kw in BANNED_KEYWORDS) {
            if (cleanTitle.contains(kw)) return false
            if (cleanUrl.contains("/$kw") || cleanUrl.contains("-$kw") || cleanUrl.contains("$kw-") || cleanUrl.contains("=$kw")) return false
        }

        val path = cleanUrl.removePrefix(MAIN_URL.lowercase(java.util.Locale.ROOT)).trimStart('/')
        if (path.isBlank() || path.startsWith("#") || path.startsWith("?") || path.startsWith("javascript:")) return false
        if (path.startsWith("tag/") || path.startsWith("tags/") || path.startsWith("category/") || path.startsWith("categories/")) return false
        if (path.startsWith("page/") || path.startsWith("developer/")) return false

        return true
    }

    data class CategorySpec(val key: String, val title: String, val path: String)

    val categories = listOf(
        CategorySpec("pgh_popular", "Popular Games", "/popular"),
        CategorySpec("pgh_updated", "Recently Updated", "/updated"),
        CategorySpec("pgh_best", "Top Rated", "/best"),
        CategorySpec("pgh_new", "New Releases", "/new"),
        CategorySpec("pgh_cheating", "Cheating Stories", "/cheating"),
        CategorySpec("pgh_corruption", "Corruption & Moral Decline", "/corruption"),
        CategorySpec("pgh_stepmom", "StepMom Stories", "/stepmom"),
        CategorySpec("pgh_stepsister", "StepSister Stories", "/stepsister"),
        CategorySpec("pgh_college", "College & Campus Life", "/college"),
        CategorySpec("pgh_school", "School & Academy", "/school"),
        CategorySpec("pgh_teacher", "Teacher & Student", "/teacher"),
        CategorySpec("pgh_threesome", "Threesome & Group", "/threesome"),
        CategorySpec("pgh_bdsm", "BDSM & Femdom", "/bdsm"),
        CategorySpec("pgh_futanari", "Futanari", "/futanari"),
        CategorySpec("pgh_succubus", "Monster Girl & Succubus", "/succubus"),
        CategorySpec("pgh_incest", "Taboo Family Stories", "/incest"),
        CategorySpec("pgh_female_lead", "Female Protagonist", "/female-protagonist"),
        CategorySpec("pgh_male_lead", "Male Protagonist & Harem", "/harem"),
        CategorySpec("pgh_erotic", "Erotic & Romance", "/erotic"),
        CategorySpec("pgh_dating", "Dating Simulation", "/dating"),
        CategorySpec("pgh_milf", "MILF Stories", "/milf"),
        CategorySpec("pgh_vn", "Visual Novels", "/visual-novels"),
        CategorySpec("pgh_rpg", "RPG & Adventure", "/rpg")
    )

    // Curated flagship selection for ultra-fast Celium Feed loading
    private val curatedCategories = listOf(
        CategorySpec("pgh_popular", "Popular Games", "/popular"),
        CategorySpec("pgh_updated", "Recently Updated", "/updated"),
        CategorySpec("pgh_best", "Top Rated", "/best"),
        CategorySpec("pgh_cheating", "Cheating Stories", "/cheating"),
        CategorySpec("pgh_vn", "Visual Novels", "/visual-novels")
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
            "div.archive-item, div.single-gallery, .archive-list .single-gallery, div.game-card, div.game-item, " +
            "div.search-item, div.search-result, a.search-item, a.search-result, a.single-gallery, " +
            ".search-results a, .search-popup a, .search-list a, .search-suggestions a, .live-search a, " +
            ".autocomplete-suggestion, li.search-item, div[class*='search-item'], div[class*='search-card'], .item-content"
        )

        for (elem in cardElements) {
            // Strictly exclude footer, header, nav, menu, or sidebar links
            val inBadContainer = elem.parents().any { p ->
                val tag = p.tagName().lowercase(java.util.Locale.ROOT)
                val cls = (p.className() + " " + p.id()).lowercase(java.util.Locale.ROOT)
                tag == "footer" || tag == "header" || tag == "nav" ||
                cls.contains("footer") || cls.contains("header") || cls.contains("nav") || cls.contains("sidebar")
            }
            if (inBadContainer) continue

            val link = if (elem.tagName().equals("a", ignoreCase = true)) elem else elem.selectFirst("a[href]") ?: continue
            val href = link.attr("href").trim()
            val fullUrl = cleanUrl(href)

            if (fullUrl.isBlank() || fullUrl == MAIN_URL || fullUrl == "$MAIN_URL/" || fullUrl.endsWith("/search") || fullUrl.contains("javascript:")) continue

            val titleElem = elem.selectFirst(".item-content h4, h4, h3, h2, .title, .name, [class*='title']") ?: link
            val rawTitle = titleElem.text().trim().ifBlank {
                link.attr("title").trim().ifBlank {
                    elem.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                }
            }
            if (rawTitle.isBlank() || !isGame(rawTitle, fullUrl)) continue

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
            val previewDesc = elem.selectFirst(".preview-hover p, .desc, p")?.text()?.trim().orEmpty()

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

    private fun parseJsonGames(jsonText: String): List<CeliumGameTile> {
        val games = mutableListOf<CeliumGameTile>()
        try {
            val trimmed = jsonText.trim()
            val jsonArray: org.json.JSONArray? = when {
                trimmed.startsWith("[") -> org.json.JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("games")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONArray("results")
                        ?: root.optJSONArray("items")
                        ?: root.optJSONArray("posts")
                        ?: root.optJSONArray("suggestions")
                        ?: root.optJSONArray("hits")
                        ?: root.optJSONObject("data")?.optJSONArray("games")
                        ?: root.optJSONObject("data")?.optJSONArray("items")
                }
                else -> null
            }

            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val title = item.optString("title").ifBlank {
                        item.optString("name").ifBlank {
                            item.optString("post_title").ifBlank {
                                item.optString("game_name")
                            }
                        }
                    }.trim()
                    if (title.isBlank()) continue

                    val rawUrl = item.optString("url").ifBlank {
                        item.optString("link").ifBlank {
                            item.optString("slug").ifBlank {
                                item.optString("href").ifBlank {
                                    item.optString("permalink")
                                }
                            }
                        }
                    }.trim()
                    if (rawUrl.isBlank()) continue
                    val fullUrl = cleanUrl(rawUrl)
                    if (!isGame(title, fullUrl)) continue

                    val posterUrl = item.optString("poster").ifBlank {
                        item.optString("image").ifBlank {
                            item.optString("cover").ifBlank {
                                item.optString("thumb").ifBlank {
                                    item.optString("thumbnail").ifBlank {
                                        item.optString("poster_url").ifBlank {
                                            item.optString("img").ifBlank {
                                                item.optJSONObject("images")?.optString("poster")
                                                    ?: item.optJSONObject("image")?.optString("url").orEmpty()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.trim().takeIf { it.isNotBlank() }?.let { cleanUrl(it) }

                    val engine = when {
                        title.contains("Ren'Py", true) -> "Ren'Py Web"
                        title.contains("Twine", true) -> "Twine"
                        title.contains("Unity", true) -> "Unity WebGL"
                        else -> item.optString("engine").ifBlank { "HTML5" }
                    }

                    val desc = item.optString("description").ifBlank {
                        item.optString("overview").ifBlank {
                            item.optString("excerpt").ifBlank {
                                item.optString("synopsis")
                            }
                        }
                    }.trim()

                    val views = item.optLong("view_count", -1L).takeIf { it > 0 }?.let { "$it views" }
                        ?: item.optString("views").takeIf { it.isNotBlank() }

                    val rating = item.optString("rating").takeIf { it.isNotBlank() }?.let { "$it ★" }

                    games.add(
                        CeliumGameTile(
                            name = title,
                            url = fullUrl,
                            apiName = "PornGamesHub",
                            type = TvType.NSFW,
                            posterUrl = posterUrl,
                            posterHeaders = mapOf("Referer" to MAIN_URL),
                            engine = engine,
                            description = desc,
                            views = views,
                            rating = rating
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return games.distinctBy { it.url }
    }

    private suspend fun fetchSearchResponse(targetUrl: String, isApi: Boolean = false): List<CeliumGameTile> = withContext(Dispatchers.IO) {
        val reqHeaders = if (isApi) {
            headers + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$MAIN_URL/"
            )
        } else {
            headers + mapOf("Referer" to "$MAIN_URL/")
        }

        try {
            val response = app.get(targetUrl, headers = reqHeaders, timeout = REQUEST_TIMEOUT)
            val text = response.text.trim()
            if (response.code in 200..299 && text.length > 20) {
                // 1. Direct JSON check
                if (text.startsWith("{") || text.startsWith("[")) {
                    val jsonGames = parseJsonGames(text).filter { isGame(it.name, it.url) }
                    if (jsonGames.isNotEmpty()) return@withContext jsonGames
                }

                // 2. window.__INIT_DATA__ check
                val initMatches = Regex("""window\.__INIT_DATA__\[.*?\]\s*=\s*(\{.*?\}|\[.*?\]);\s*(?:window|\n|<)""", RegexOption.DOT_MATCHES_ALL).findAll(text)
                for (m in initMatches) {
                    val jsonStr = m.groupValues[1]
                    val games = parseJsonGames(jsonStr).filter { isGame(it.name, it.url) }
                    if (games.isNotEmpty()) return@withContext games
                }

                // 3. HTML parsing
                val doc = response.document
                val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim().orEmpty()
                val docTitle = doc.title().trim()
                val isHomeRedirect = canonical == MAIN_URL || canonical == "$MAIN_URL/" ||
                        docTitle.startsWith("Free Porn Games - Play", ignoreCase = true) ||
                        docTitle.equals("PornGamesHub", ignoreCase = true)
                if (!isHomeRedirect) {
                    val htmlGames = parseGameCards(doc).filter { isGame(it.name, it.url) }
                    if (htmlGames.isNotEmpty()) return@withContext htmlGames
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (!isApi) {
            try {
                val proxyUrl = "https://r.jina.ai/$targetUrl"
                val fallbackResponse = app.get(
                    proxyUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "X-Return-Format" to "html"),
                    timeout = REQUEST_TIMEOUT + 4_000L
                )
                val text = fallbackResponse.text.trim()
                if (text.length > 50) {
                    if (text.startsWith("{") || text.startsWith("[")) {
                        val jsonGames = parseJsonGames(text).filter { isGame(it.name, it.url) }
                        if (jsonGames.isNotEmpty()) return@withContext jsonGames
                    }
                    val doc = Jsoup.parse(text, targetUrl)
                    val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim().orEmpty()
                    val docTitle = doc.title().trim()
                    val isHomeRedirect = canonical == MAIN_URL || canonical == "$MAIN_URL/" ||
                            docTitle.startsWith("Free Porn Games - Play", ignoreCase = true) ||
                            docTitle.equals("PornGamesHub", ignoreCase = true)
                    if (!isHomeRedirect) {
                        val htmlGames = parseGameCards(doc).filter { isGame(it.name, it.url) }
                        if (htmlGames.isNotEmpty()) return@withContext htmlGames
                    }
                }
            } catch (fallbackError: Exception) {
                logError(fallbackError)
            }
        }

        emptyList()
    }

    private suspend fun fetchShelves(specs: List<CategorySpec>, page: Int): List<CeliumShelf> = withContext(Dispatchers.IO) {
        val deferredShelves = specs.mapIndexed { index, cat ->
            async {
                try {
                    val pageUrl = if (page > 1) "$MAIN_URL${cat.path}/$page" else "$MAIN_URL${cat.path}"
                    val doc = fetchHtmlDocument(pageUrl) ?: return@async null
                    val games = parseGameCards(doc).filter { isGame(it.name, it.url) }
                    if (games.isNotEmpty()) {
                        val cards = games.map { CeliumTile.from(it) }
                        CeliumShelf(
                            key = "${cat.key}_$page",
                            title = cat.title,
                            cards = cards,
                            sectionIndex = index,
                            page = page,
                            hasNext = cards.size >= 12,
                            sourceId = "porngameshub"
                        )
                    } else null
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }
        }
        deferredShelves.awaitAll().filterNotNull()
    }

    suspend fun getMainPage(page: Int = 1): List<CeliumShelf> = fetchShelves(categories, page)

    suspend fun getCuratedShelves(page: Int = 1): List<CeliumShelf> = fetchShelves(curatedCategories, page)

    suspend fun search(query: String, page: Int = 1): List<CeliumTile> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        try {
            val encoded = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            val slug = cleanQuery.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val plusQuery = cleanQuery.replace(" ", "+")

            // 1. Direct game slug probe (e.g. /heritage, /medical-species-care, /medical-species-care-2)
            val directSlugs = listOfNotNull(
                slug,
                if (!slug.endsWith("-2")) "$slug-2" else null,
                if (!slug.endsWith("-game")) "$slug-game" else null
            )
            val directMatches = directSlugs.map { s ->
                async {
                    try {
                        val gameUrl = "$MAIN_URL/$s"
                        val doc = fetchHtmlDocument(gameUrl) ?: return@async null
                        val docTitle = doc.title().trim()
                        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim().orEmpty()
                        if (canonical == MAIN_URL || canonical == "$MAIN_URL/" ||
                            docTitle.startsWith("Free Porn Games - Play", ignoreCase = true) ||
                            docTitle.equals("PornGamesHub", ignoreCase = true)
                        ) {
                            return@async null
                        }
                        val title = doc.selectFirst("h1")?.text()?.trim()
                            ?: docTitle.substringBefore("|").substringBefore("- Porn").trim()
                        if (isGame(title, gameUrl) && (
                            com.lagradost.cloudstream3.ui.search.SearchRanking.score(cleanQuery, title) > 0 ||
                            title.contains(cleanQuery, ignoreCase = true)
                        )) {
                            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                                ?: doc.selectFirst(".game-poster img, .thumbnail img, img.cover, img")?.attr("src")?.trim()
                            val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
                            CeliumGameTile(
                                name = title,
                                url = gameUrl,
                                apiName = "PornGamesHub",
                                type = TvType.NSFW,
                                posterUrl = poster?.let { cleanUrl(it) },
                                posterHeaders = mapOf("Referer" to MAIN_URL),
                                description = desc
                            )
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            // 2. Live search & API candidate endpoints
            val apiEndpoints = listOf(
                "$MAIN_URL/api/search?q=$encoded",
                "$MAIN_URL/api/search?keyword=$encoded",
                "$MAIN_URL/api/search?search=$encoded",
                "$MAIN_URL/api/games?search=$encoded",
                "$MAIN_URL/api/games?q=$encoded",
                "$MAIN_URL/search?q=$encoded",
                "$MAIN_URL/ajax/search?q=$encoded",
                "$MAIN_URL/live-search?q=$encoded"
            )
            for (apiUrl in apiEndpoints) {
                val results = fetchSearchResponse(apiUrl, isApi = true).filter { isGame(it.name, it.url) }
                val matches = results.filter {
                    com.lagradost.cloudstream3.ui.search.SearchRanking.score(cleanQuery, it.name) > 0 ||
                    it.name.contains(cleanQuery, ignoreCase = true) ||
                    it.description.contains(cleanQuery, ignoreCase = true)
                }
                if (matches.isNotEmpty()) {
                    val allFound = (directMatches + matches).distinctBy { it.url }
                    return@withContext allFound.sortedByDescending {
                        com.lagradost.cloudstream3.ui.search.SearchRanking.score(cleanQuery, it.name)
                    }.map { CeliumTile.from(it) }
                }
            }

            // 3. Standard site search URLs (HTML or SSR pages)
            val matchedCat = categories.firstOrNull {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                it.path.trim('/').equals(slug, ignoreCase = true)
            }

            val htmlEndpoints = mutableListOf<String>()
            if (page > 1) {
                htmlEndpoints.add("$MAIN_URL/?search=$plusQuery&page=$page")
                htmlEndpoints.add("$MAIN_URL/search/$slug/$page")
                htmlEndpoints.add("$MAIN_URL/search/$encoded/$page")
                htmlEndpoints.add("$MAIN_URL/search?q=$encoded&page=$page")
                htmlEndpoints.add("$MAIN_URL/?s=$encoded&page=$page")
                if (matchedCat != null) htmlEndpoints.add("$MAIN_URL${matchedCat.path}/$page")
            } else {
                htmlEndpoints.add("$MAIN_URL/?search=$plusQuery")
                htmlEndpoints.add("$MAIN_URL/search/$slug")
                htmlEndpoints.add("$MAIN_URL/search/$encoded")
                htmlEndpoints.add("$MAIN_URL/search?q=$encoded")
                htmlEndpoints.add("$MAIN_URL/?s=$encoded")
                if (matchedCat != null) htmlEndpoints.add("$MAIN_URL${matchedCat.path}")
            }

            for (targetUrl in htmlEndpoints) {
                val results = fetchSearchResponse(targetUrl, isApi = false).filter { isGame(it.name, it.url) }
                val matches = results.filter {
                    com.lagradost.cloudstream3.ui.search.SearchRanking.score(cleanQuery, it.name) > 0 ||
                    it.name.contains(cleanQuery, ignoreCase = true) ||
                    it.description.contains(cleanQuery, ignoreCase = true)
                }
                if (matches.isNotEmpty()) {
                    val allFound = (directMatches + matches).distinctBy { it.url }
                    return@withContext allFound.sortedByDescending {
                        com.lagradost.cloudstream3.ui.search.SearchRanking.score(cleanQuery, it.name)
                    }.map { CeliumTile.from(it) }
                }
            }

            // 4. Deep parallel search across flagship story / VN / RPG / popular categories
            val deepCategories = listOf(
                CategorySpec("pgh_vn", "Visual Novels", "/visual-novels"),
                CategorySpec("pgh_vn_2", "Visual Novels P2", "/visual-novels/2"),
                CategorySpec("pgh_popular", "Popular Games", "/popular"),
                CategorySpec("pgh_updated", "Recently Updated", "/updated"),
                CategorySpec("pgh_best", "Top Rated", "/best"),
                CategorySpec("pgh_new", "New Releases", "/new"),
                CategorySpec("pgh_rpg", "RPG", "/rpg"),
                CategorySpec("pgh_cheating", "Cheating Stories", "/cheating")
            )
            val parallelCards = deepCategories.map { cat ->
                async {
                    val pageUrl = if (page > 1) "$MAIN_URL${cat.path}/$page" else "$MAIN_URL${cat.path}"
                    fetchSearchResponse(pageUrl, isApi = false).filter { isGame(it.name, it.url) }
                }
            }.awaitAll().flatten().distinctBy { it.url }

            val filtered = parallelCards.filter {
                com.lagradost.cloudstream3.ui.search.SearchRanking.score(cleanQuery, it.name) > 0 ||
                it.name.contains(cleanQuery, ignoreCase = true) ||
                it.description.contains(cleanQuery, ignoreCase = true)
            }

            val combined = (directMatches + filtered).distinctBy { it.url }.sortedByDescending {
                com.lagradost.cloudstream3.ui.search.SearchRanking.score(cleanQuery, it.name)
            }

            if (combined.isNotEmpty()) {
                return@withContext combined.map { CeliumTile.from(it) }
            }
            emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    suspend fun loadGame(url: String): CeliumGameTile? = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(url)
            val pageHtml = doc?.html().orEmpty()

            var jsonTitle: String? = null
            var jsonIframe: String? = null
            var jsonViews: Long? = null
            var jsonPublished: String? = null
            var jsonUpgraded: String? = null
            var jsonDesc: String? = null
            val jsonTags = mutableListOf<String>()
            val jsonImages = mutableListOf<String>()

            val initPattern = Regex("""window\.__INIT_DATA__\[.*?\]\s*=\s*(\{.*?\});\s*(?:window|\n|<)""", RegexOption.DOT_MATCHES_ALL)
            val matches = initPattern.findAll(pageHtml)
            for (match in matches) {
                try {
                    val jsonStr = match.groupValues[1]
                    val obj = JSONObject(jsonStr)
                    if (jsonTitle == null) jsonTitle = obj.optString("title").takeIf { it.isNotBlank() }
                    if (jsonIframe == null) jsonIframe = obj.optString("link_iframe").takeIf { it.isNotBlank() }
                    if (jsonViews == null) jsonViews = obj.optLong("view_count", -1L).takeIf { it > 0 }
                    if (jsonPublished == null) jsonPublished = obj.optString("published_at").takeIf { it.isNotBlank() }
                    if (jsonUpgraded == null) jsonUpgraded = obj.optString("upgraded_at").takeIf { it.isNotBlank() }
                    if (jsonDesc == null) {
                        jsonDesc = obj.optString("description").takeIf { it.isNotBlank() }
                            ?: obj.optString("content").takeIf { it.isNotBlank() }
                            ?: obj.optString("overview").takeIf { it.isNotBlank() }
                            ?: obj.optString("story").takeIf { it.isNotBlank() }
                    }

                    val tagsArr = obj.optJSONArray("tags") ?: obj.optJSONArray("categories") ?: obj.optJSONArray("genres")
                    if (tagsArr != null) {
                        for (i in 0 until tagsArr.length()) {
                            val tagItem = tagsArr.opt(i)
                            when (tagItem) {
                                is String -> if (tagItem.isNotBlank()) jsonTags.add(tagItem.trim())
                                is JSONObject -> {
                                    val name = tagItem.optString("name").ifBlank { tagItem.optString("title").ifBlank { tagItem.optString("slug") } }
                                    if (name.isNotBlank()) jsonTags.add(name.trim())
                                }
                            }
                        }
                    }

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

            // Resilient regex and DOM fallback for link_iframe
            if (jsonIframe.isNullOrBlank()) {
                val directIframeRegex = Regex(""""link_iframe"\s*:\s*"([^"]+)"""")
                val iframeMatch = directIframeRegex.find(pageHtml)
                if (iframeMatch != null) {
                    jsonIframe = iframeMatch.groupValues[1].replace("\\/", "/")
                }
            }
            if (jsonIframe.isNullOrBlank()) {
                val domIframe = doc?.selectFirst("iframe[src*='storage'], iframe#game-iframe, iframe.game-frame, iframe[src*='html']")?.attr("src")?.trim()
                if (!domIframe.isNullOrBlank()) {
                    jsonIframe = domIframe
                }
            }

            val title = jsonTitle
                ?: doc?.selectFirst("h1")?.text()?.trim()
                ?: doc?.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: doc?.title()?.substringBefore("|")?.trim()
                ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }

            val descBuilder = StringBuilder()
            if (!jsonDesc.isNullOrBlank()) {
                val cleanJsonDesc = Jsoup.parse(jsonDesc).wholeText().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
                if (cleanJsonDesc.isNotBlank()) {
                    descBuilder.append(cleanJsonDesc)
                }
            }

            if (descBuilder.isBlank()) {
                val storySelectors = listOf(
                    ".game-story", ".story-content", ".game-description", "#description",
                    ".entry-content", ".description", ".game-info-description", ".post-content",
                    ".game-about", ".about-game", "div[itemprop='description']", ".overview",
                    "#game-desc", ".game-details-content", ".synopsis"
                )
                for (sel in storySelectors) {
                    val elem = doc?.selectFirst(sel)
                    if (elem != null) {
                        val clone = elem.clone()
                        clone.select("script, style, iframe, noscript, .tags, .buttons, .share-buttons, .related-games, .comments").remove()
                        val text = clone.wholeText().lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n\n")
                        if (text.isNotBlank() && text.length > 20) {
                            descBuilder.append(text)
                            break
                        }
                    }
                }
            }

            if (descBuilder.isBlank()) {
                val descHeading = doc?.select("h1, h2, h3, h4")?.firstOrNull {
                    it.text().contains("Description", true) ||
                    it.text().contains("About", true) ||
                    it.text().contains("Story", true) ||
                    it.text().contains("Overview", true) ||
                    it.text().contains("Synopsis", true)
                }
                if (descHeading != null) {
                    var sibling = descHeading.nextElementSibling()
                    while (sibling != null && !sibling.tagName().startsWith("h", ignoreCase = true)) {
                        if (sibling.tagName().equals("p", ignoreCase = true) || sibling.tagName().equals("div", ignoreCase = true)) {
                            val pText = sibling.text().trim()
                            if (pText.isNotBlank()) {
                                if (descBuilder.isNotEmpty()) descBuilder.append("\n\n")
                                descBuilder.append(pText)
                            }
                        }
                        sibling = sibling.nextElementSibling()
                    }
                }
            }

            val desc = descBuilder.toString().ifBlank {
                doc?.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
                    .ifBlank { doc?.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty() }
            }

            val poster = jsonImages.firstOrNull()
                ?: doc?.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?: doc?.selectFirst(".game-poster img, .thumbnail img, img.cover")?.attr("src")?.trim()

            val screenshots = mutableListOf<String>()
            if (jsonImages.isNotEmpty()) {
                screenshots.addAll(jsonImages)
            } else {
                val galleryContainers = doc?.select(
                    ".game-gallery, #gallery, .gallery, .screenshots, .game-screenshots, .game-images"
                )
                val badKeywords = listOf(
                    "logo", "avatar", "icon", "banner", "advert", "button", "play-icon",
                    "related", "thumb", "policy", "verify", "rta", "badge", "rating", "ad-"
                )
                val candidateImages = if (!galleryContainers.isNullOrEmpty()) {
                    galleryContainers.select("img[data-src], img[src]")
                } else {
                    doc?.select("img[data-src*='/uploads/public/'], a[href*='/uploads/public/']") ?: emptyList()
                }

                for (elem in candidateImages) {
                    val inBadParent = elem.parents().any { parent ->
                        val cls = (parent.className() + " " + parent.id()).lowercase(java.util.Locale.ROOT)
                        val tag = parent.tagName().lowercase(java.util.Locale.ROOT)
                        tag == "footer" || tag == "header" || tag == "nav" || tag == "sidebar" ||
                        cls.contains("related") || cls.contains("recommend") || cls.contains("suggest") ||
                        cls.contains("archive") || cls.contains("single-gallery") || cls.contains("sidebar") ||
                        cls.contains("footer") || cls.contains("swiper") || cls.contains("carousel") ||
                        cls.contains("more-games") || cls.contains("similar")
                    }
                    if (inBadParent) continue

                    val rawSrc = elem.attr("data-src").ifBlank { elem.attr("src").ifBlank { elem.attr("href") } }.trim()
                    if (rawSrc.isNotBlank() && badKeywords.none { rawSrc.contains(it, ignoreCase = true) }) {
                        val clean = cleanUrl(rawSrc)
                        if (clean.endsWith(".jpg", true) || clean.endsWith(".jpeg", true) || clean.endsWith(".png", true) || clean.endsWith(".webp", true)) {
                            screenshots.add(clean)
                        }
                    }
                }
            }

            var author: String? = null
            val devHeading = doc?.selectFirst("h2:contains(Developer)")
            if (devHeading != null) {
                author = devHeading.nextElementSibling()?.selectFirst("h3, a, p")?.text()?.trim()
            }
            if (author.isNullOrBlank()) {
                author = doc?.selectFirst(".developer, .author, a[href*='/developer/']")?.text()?.trim()
            }

            val domTags = mutableListOf<String>()
            val tagNodes = doc?.select(
                ".tags a, .genres a, .game-tags a, .entry-tags a, .post-tags a, .tag-list a, .item-tags a, " +
                "a[href*='/tag/'], a[href*='/tags/'], a[href*='/category/'], a[href*='/genre/'], " +
                "div:contains(Tags) > a, div:contains(Categories) > a, div:contains(Genres) > a"
            ) ?: emptyList()
            for (node in tagNodes) {
                val tagText = node.text().trim()
                val href = node.attr("href")
                val lowerTag = tagText.lowercase(java.util.Locale.ROOT)
                val isBanned = BANNED_KEYWORDS.any { lowerTag.contains(it) } ||
                        lowerTag in listOf("home", "games", "play", "free", "all games")
                if (tagText.isNotBlank() && tagText.length in 2..35 && !isBanned &&
                    !href.contains("porngameshub.com/?", true) && !href.endsWith("/popular") && !href.endsWith("/best")
                ) {
                    domTags.add(tagText.replaceFirstChar { it.uppercase() })
                }
            }
            val tags = (jsonTags + domTags).distinct()

            val guideLink = doc?.selectFirst("a[href*='.pdf']")?.attr("href")?.trim()?.let { cleanUrl(it) }

            val viewsStr = jsonViews?.let {
                when {
                    it >= 1_000_000 -> String.format("%.1fM views", it / 1_000_000.0)
                    it >= 1_000 -> String.format("%.1fK views", it / 1_000.0)
                    else -> "$it views"
                }
            } ?: doc?.selectFirst("*:matchesOwn(Views:)")?.text()?.trim()

            val updatedStr = jsonUpgraded ?: doc?.selectFirst("*:matchesOwn(updated on)")?.text()?.trim()
            val ratingVal = doc?.selectFirst("*:matchesOwn(Rating:)")?.text()?.trim()?.substringAfter("Rating:")?.trim()

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
            // Even if an unexpected parsing exception occurs, return a valid playable tile
            CeliumGameTile(
                name = url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() },
                url = url,
                apiName = "PornGamesHub",
                type = TvType.NSFW,
                playUrl = url
            )
        }
    }
}
