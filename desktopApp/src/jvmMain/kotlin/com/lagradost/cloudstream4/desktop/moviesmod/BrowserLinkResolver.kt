package com.lagradost.cloudstream4.desktop.moviesmod

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.CopyOnWriteArrayList

internal fun supportedBrowserTarget(url: String): Boolean {
    val target = url.toHttpUrlOrNull() ?: return false
    return target.isHttps && target.host.split('.').dropLast(1).lastOrNull() in setOf("driveleech", "driveseed")
}

internal object BrowserLinkResolver {
    private val gate = Semaphore(1)
    private val cookies = CopyOnWriteArrayList<Cookie>()
    @Volatile private var userAgent: String? = null

    internal fun remember(userAgent: String, values: List<Cookie>) {
        this.userAgent = userAgent
        for (value in values) {
            cookies.removeIf { it.name == value.name && it.domain == value.domain && it.path == value.path }
            cookies.add(value)
        }
    }

    fun headers(url: String): Map<String, String> {
        val target = url.toHttpUrl()
        val applicable = cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(target) }
        if (applicable.isEmpty()) return emptyMap()
        return buildMap {
            userAgent?.let { put("User-Agent", it) }
            put("Cookie", applicable.joinToString("; ") { "${it.name}=${it.value}" })
        }
    }

    suspend fun resolve(url: String): String = gate.withPermit {
        withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext()
            operation.ensureActive()
            Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"))).use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setChannel("msedge").setHeadless(false)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000.0))
                        val deadline = System.nanoTime() + 60_000_000_000L
                        while (System.nanoTime() < deadline) {
                            operation.ensureActive()
                            val destination = context.pages().firstOrNull { supportedBrowserTarget(it.url()) }
                            if (destination != null) {
                                val values = context.cookies().map { cookie ->
                                    val builder = Cookie.Builder().name(cookie.name).value(cookie.value).path(cookie.path)
                                    if (cookie.domain.startsWith('.')) builder.domain(cookie.domain.removePrefix(".")) else builder.hostOnlyDomain(cookie.domain)
                                    if (cookie.secure) builder.secure()
                                    if (cookie.expires > 0) builder.expiresAt((cookie.expires * 1000).toLong())
                                    builder.build()
                                }
                                remember(destination.evaluate("navigator.userAgent") as String, values)
                                return@withContext destination.url()
                            }
                            page.waitForTimeout(250.0)
                        }
                        error("The source browser stopped at ${page.url().toHttpUrl().host}. Complete visible verification or try another source.")
                    }
                }
            }
        }
    }
}
