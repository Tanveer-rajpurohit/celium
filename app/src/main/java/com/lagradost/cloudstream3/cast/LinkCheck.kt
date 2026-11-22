package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Quick check that a link really serves video, made by the phone before anything switches to it
 * (phone player quality changes and casting). Blocking; call off the main thread.
 */
object LinkCheck {
    fun request(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (headers.keys.none { it.equals("user-agent", true) }) builder.header("User-Agent", USER_AGENT)
        builder.header("Accept-Encoding", "identity")
        return builder
    }

    /** A plain-language problem, or null when the link responds with video (or a playlist). */
    fun check(client: OkHttpClient, url: String, headers: Map<String, String>, playlist: Boolean): String? {
        val request = request(url, headers).apply { if (!playlist) header("Range", "bytes=0-1023") }.build()
        val call = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build().newCall(request)
        return try {
            call.execute().use { response ->
                @Suppress("UNNECESSARY_SAFE_CALL")
                val start = response.body?.byteStream()?.use { input ->
                    val bytes = ByteArray(1024)
                    val n = input.read(bytes)
                    if (n > 0) String(bytes, 0, n, Charsets.ISO_8859_1) else ""
                }
                CastLinks.problem(response.code, response.header("Content-Type"), start, playlist)
            }
        } catch (e: InterruptedIOException) {
            CastLinks.problem(null, null, null, playlist, timedOut = true)
        } catch (e: IOException) {
            CastLinks.problem(null, null, null, playlist)
        } catch (e: IllegalArgumentException) {
            "This link isn't a valid web address"
        }
    }
}
