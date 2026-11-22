package com.lagradost.cloudstream4.desktop.moviesmod

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

object DesktopMoviesmod {
    private val mutex = Mutex()
    private var provider: MoviesmodProvider? = null
    private val client by lazy {
        // Moviesmod's intermediary returned 429 over HTTP/2 but its source pages worked over HTTP/1.1.
        app.baseClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(DesktopResponseCheck).build()
    }

    suspend fun initialize(): MoviesmodProvider = mutex.withLock {
        provider?.let { return@withLock it }
        app.baseClient = client
        val domains = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json", timeout = 15).text
        val instance = MoviesmodProvider(validateDomain(JSONObject(domains).getString("moviesmod")))
        // Construction is complete before mutating registries; initialization is owned by the app, not a screen.
        object : BasePlugin() {}.apply {
            filename = "bundled:moviesmod-csx-13903aa"
            registerMainAPI(instance)
            registerExtractorAPI(Driveseed())
            registerExtractorAPI(Driveleech())
        }
        provider = instance
        instance
    }
}

internal fun validateDomain(value: String): String {
    val url = value.toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath == "/" && url.query == null && url.fragment == null) { "Moviesmod returned an invalid website address" }
    return url.toString().trimEnd('/')
}

internal fun checkSearchPage(document: Document) {
    if (document.select("div.post-cards > article").isNotEmpty()) return
    val text = document.text().lowercase()
    if (listOf("nothing found", "no results", "not found", "no posts").any(text::contains)) return
    throw IOException("Moviesmod returned an unfamiliar search page. Its website may have changed.")
}

internal object DesktopResponseCheck : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request().newBuilder().apply {
            BrowserLinkResolver.headers(chain.request().url.toString()).forEach { (name, value) -> header(name, value) }
        }.build()
        val response = chain.proceed(request)
        val html = response.header("Content-Type").orEmpty().contains("text/html", true)
        val preview = if (html) response.peekBody(32_768).string().lowercase() else ""
        val verification = response.header("cf-mitigated") == "challenge" ||
            preview.contains("<title>just a moment") || preview.contains("<title>verify you are human")
        if (verification || response.code >= 400) {
            val message = if (verification) "This source requires browser verification, which this desktop prototype cannot complete yet."
                else "${chain.request().url.host} returned HTTP ${response.code}"
            response.close()
            throw IOException(message)
        }
        return response
    }
}
