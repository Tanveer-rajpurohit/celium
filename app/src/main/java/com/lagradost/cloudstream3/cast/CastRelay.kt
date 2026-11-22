package com.lagradost.cloudstream3.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small HTTP server on the phone. The TV requests `/s/<token>/...` and the relay fetches the real
 * URL with the source's Referer/headers (which the TV cannot send) and streams it back.
 * HLS playlists are rewritten so their segments and keys also go through the relay.
 */
class CastRelay(baseClient: OkHttpClient) {
    private data class Entry(
        val url: String,
        val headers: Map<String, String>,
        val mime: String?,
        val playlist: Boolean,
        val subtitle: Boolean = false,
        /** Relay URL of the subtitle for this video, announced to TVs that ask for it. */
        val captionUrl: String? = null,
    )

    private val client = baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val tokens = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "CastRelay").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    @Volatile var host: String? = null
        private set

    val isRunning get() = server?.isClosed == false

    val port: Int? get() = server?.takeIf { !it.isClosed }?.localPort

    /** Starts the relay, on [preferredPort] when possible (so a TV can keep using URLs from before). */
    @Synchronized
    fun start(localHost: String, preferredPort: Int = 0) {
        host = localHost
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(preferredPort))
        } catch (e: IOException) {
            if (preferredPort == 0) throw e
            socket.bind(InetSocketAddress(0))
        }
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                pool.execute { handle(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        clear()
    }

    /** A registered stream, saved so the relay can serve the same URLs after Celium restarts. */
    data class Saved(
        val token: String, val url: String, val headers: Map<String, String>, val mime: String?,
        val playlist: Boolean, val subtitle: Boolean, val captionUrl: String?,
    )

    fun saved(): List<Saved> = entries.map { (token, e) -> Saved(token, e.url, e.headers, e.mime, e.playlist, e.subtitle, e.captionUrl) }

    fun restore(saved: List<Saved>) = saved.forEach {
        entries[it.token] = Entry(it.url, it.headers, it.mime, it.playlist, it.subtitle, it.captionUrl)
        tokens[it.url] = it.token
    }

    /** Forget all streams of the previous video. */
    fun clear() { entries.clear(); tokens.clear() }

    /** Returns the relay URL the TV should open for [url]. Same upstream URL gives the same token. */
    fun register(
        url: String, headers: Map<String, String>, mime: String?, playlist: Boolean, name: String = "media",
        subtitle: Boolean = false, captionUrl: String? = null,
    ): String {
        val token = tokens.getOrPut(url) {
            ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        }
        entries[token] = Entry(url, headers, mime, playlist, subtitle, captionUrl)
        val ext = mime?.let(CastLinks::extension) ?: url.substringAfterLast('/').substringBefore('?').substringAfterLast('.', "bin").take(5)
        val port = server?.localPort ?: throw IOException("Relay is not running")
        return "http://$host:$port/s/$token/$name.$ext"
    }

    private fun handle(socket: Socket) = socket.use {
        try {
            it.soTimeout = 30_000
            val request = readHead(it.getInputStream()) ?: return
            val out = BufferedOutputStream(it.getOutputStream(), 64 * 1024)
            val lines = request.split("\r\n")
            val (method, path) = lines.first().split(' ').let { p -> p.getOrNull(0).orEmpty() to p.getOrNull(1).orEmpty() }
            val clientHeaders = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':'); if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            val entry = path.removePrefix("/s/").substringBefore('/').let(entries::get)
            if (entry == null || (method != "GET" && method != "HEAD")) {
                respond(out, "404 Not Found", mapOf("Content-Length" to "0")); out.flush(); return
            }
            try {
                serve(entry, method == "HEAD", clientHeaders, out)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0"))
            } finally {
                out.flush()
            }
        } catch (e: IOException) {
            // Usually the TV closed the connection (seek/stop).
            onError(e)
        }
    }

    /** Hook for diagnostics; errors never escape the relay threads. */
    var onError: (Throwable) -> Unit = {}

    private fun upstream(url: String, headers: Map<String, String>): Request.Builder = LinkCheck.request(url, headers)

    /**
     * Quick check from the phone, before the TV is involved: returns a plain-language problem,
     * or null when the link responds with video (or a playlist).
     */
    fun probe(url: String, headers: Map<String, String>, playlist: Boolean): String? = LinkCheck.check(client, url, headers, playlist)

    private fun serve(entry: Entry, head: Boolean, clientHeaders: Map<String, String>, out: OutputStream) {
        val builder = upstream(entry.url, entry.headers)
        if (!entry.playlist && !entry.subtitle) clientHeaders["range"]?.let { builder.header("Range", it) }
        client.newCall(builder.build()).execute().use { response ->
            @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
            val body = response.body ?: run {
                respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0")); return
            }
            if (!response.isSuccessful) {
                respond(out, "${response.code} Upstream", mapOf("Content-Length" to "0")); return
            }
            val upstreamType = response.header("Content-Type").orEmpty()
            if (entry.subtitle) {
                val srt = CastLinks.vttToSrt(body.string()).toByteArray()
                respond(out, "200 OK", mapOf("Content-Type" to "text/srt; charset=utf-8", "Content-Length" to srt.size.toString()))
                if (!head) out.write(srt)
                out.flush(); return
            }
            if (entry.playlist || upstreamType.contains("mpegurl", true)) {
                val text = body.string()
                if (HlsRewriter.isPlaylistBody(text)) {
                    val rewritten = HlsRewriter.rewrite(text, response.request.url.toString()) { url, nested ->
                        register(url, entry.headers, if (nested) "application/vnd.apple.mpegurl" else null, nested, if (nested) "playlist" else "segment")
                    }.toByteArray()
                    respond(out, "200 OK", mapOf(
                        "Content-Type" to "application/vnd.apple.mpegurl",
                        "Content-Length" to rewritten.size.toString(),
                    ))
                    if (!head) out.write(rewritten)
                    out.flush(); return
                }
                // Not actually a playlist: send what we got.
                val bytes = text.toByteArray()
                respond(out, "200 OK", mapOf("Content-Type" to upstreamType.ifBlank { "application/octet-stream" }, "Content-Length" to bytes.size.toString()))
                if (!head) out.write(bytes)
                out.flush(); return
            }
            val type = when {
                entry.mime != null && !upstreamType.startsWith("video/") -> entry.mime
                upstreamType.isNotBlank() -> upstreamType
                else -> "application/octet-stream"
            }
            val headers = linkedMapOf("Content-Type" to type, "Accept-Ranges" to "bytes")
            response.header("Content-Length")?.let { headers["Content-Length"] = it }
            response.header("Content-Range")?.let { headers["Content-Range"] = it }
            if (entry.mime != null) {
                headers["transferMode.dlna.org"] = "Streaming"
                headers["contentFeatures.dlna.org"] = DlnaProtocol.dlnaFlags(entry.mime)
            }
            // Samsung-style TVs ask for the subtitle location on the video response.
            if (entry.captionUrl != null && clientHeaders.containsKey("getcaptioninfo.sec")) headers["CaptionInfo.sec"] = entry.captionUrl
            respond(out, if (response.code == 206) "206 Partial Content" else "200 OK", headers)
            if (!head) body.byteStream().copyTo(out, 64 * 1024)
            out.flush()
        }
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buffer.append(b.toChar())
            if (buffer.endsWith("\r\n\r\n")) return buffer.removeSuffix("\r\n\r\n").toString()
        }
        return null
    }

    companion object {
        /** The phone's address on the route to [remoteHost] (no packet is sent). */
        fun localAddressFor(remoteHost: String): String? = runCatching {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName(remoteHost), DlnaProtocol.SSDP_PORT)
                s.localAddress.hostAddress?.takeIf { it != "0.0.0.0" && !it.contains(':') }
            }
        }.getOrNull()
    }
}
