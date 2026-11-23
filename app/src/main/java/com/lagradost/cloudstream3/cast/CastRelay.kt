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
        /** Header edits that make the chosen audio track the default (MKV/MP4). */
        val patches: List<BytePatch> = emptyList(),
        /** HLS: audio language to leave in the playlist (the others are dropped). */
        val audioLanguage: String? = null,
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
        /** Audio edits as "offset:hex" (plain strings survive the JSON round trip). */
        val patches: List<String> = emptyList(),
        val audioLanguage: String? = null,
    )

    fun saved(): List<Saved> = entries.map { (token, e) ->
        Saved(token, e.url, e.headers, e.mime, e.playlist, e.subtitle, e.captionUrl,
            e.patches.map { p -> "${p.offset}:" + p.bytes.joinToString("") { "%02x".format(it) } }, e.audioLanguage)
    }

    fun restore(saved: List<Saved>) = saved.forEach {
        val patches = it.patches.orEmpty().mapNotNull { s ->
            val offset = s.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val hex = s.substringAfter(':')
            BytePatch(offset, ByteArray(hex.length / 2) { k -> hex.substring(2 * k, 2 * k + 2).toInt(16).toByte() })
        }
        entries[it.token] = Entry(it.url, it.headers, it.mime, it.playlist, it.subtitle, it.captionUrl, patches, it.audioLanguage)
        tokens[key(it.url, patches, it.audioLanguage)] = it.token
    }

    /** Forget all streams of the previous video. */
    fun clear() { entries.clear(); tokens.clear() }

    /** Same video with another audio choice gets another URL, so a TV never reuses a cached header. */
    private fun key(url: String, patches: List<BytePatch>, audioLanguage: String?) =
        if (patches.isEmpty() && audioLanguage == null) url else "$url#audio=${audioLanguage}:${patches.hashCode()}"

    /**
     * Returns the relay URL the TV should open for [url]. The same upstream URL and audio choice give
     * the same token. [patches] and [audioLanguage] make the chosen audio track the one the TV plays.
     */
    fun register(
        url: String, headers: Map<String, String>, mime: String?, playlist: Boolean, name: String = "media",
        subtitle: Boolean = false, captionUrl: String? = null,
        patches: List<BytePatch> = emptyList(), audioLanguage: String? = null,
    ): String {
        val token = tokens.getOrPut(key(url, patches, audioLanguage)) {
            ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        }
        entries[token] = Entry(url, headers, mime, playlist, subtitle, captionUrl, patches, audioLanguage)
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
            // Chromecast's player is a web page: it checks permission (CORS) before fetching playlists and subtitles.
            if (method == "OPTIONS") {
                respond(out, "204 No Content", mapOf("Content-Length" to "0")); out.flush(); return
            }
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
                // DLNA TVs read SRT; Chromecast reads WebVTT.
                val vtt = entry.mime == "text/vtt"
                val text = body.string()
                val bytes = (if (vtt) CastLinks.srtToVtt(text) else CastLinks.vttToSrt(text)).toByteArray()
                val type = if (vtt) "text/vtt; charset=utf-8" else "text/srt; charset=utf-8"
                respond(out, "200 OK", mapOf("Content-Type" to type, "Content-Length" to bytes.size.toString()))
                if (!head) out.write(bytes)
                out.flush(); return
            }
            if (entry.playlist || upstreamType.contains("mpegurl", true)) {
                val text = body.string()
                if (HlsRewriter.isPlaylistBody(text)) {
                    val chosen = entry.audioLanguage?.let { FileAudio.preferHlsAudio(text, it) } ?: text
                    val rewritten = HlsRewriter.rewrite(chosen, response.request.url.toString()) { url, nested ->
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
            if (!head) {
                if (entry.patches.isEmpty()) body.byteStream().copyTo(out, 64 * 1024)
                else {
                    // Where this response starts in the file, so the audio edits land on the right bytes.
                    val start = if (response.code == 206) contentRangeStart(response.header("Content-Range")) ?: return else 0L
                    copyPatched(body.byteStream(), out, start, entry.patches)
                }
            }
            out.flush()
        }
    }

    private fun copyPatched(input: InputStream, out: OutputStream, start: Long, patches: List<BytePatch>) {
        val buffer = ByteArray(64 * 1024)
        val lastPatched = patches.maxOf { it.offset + it.bytes.size }
        var position = start
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (position < lastPatched) applyPatches(buffer, n, position, patches)
            out.write(buffer, 0, n)
            position += n
        }
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            // Lets web-based players (Chromecast) use the relay; DLNA TVs ignore these headers.
            CORS.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
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
        /** Writes the parts of [patches] that fall in [buffer] (the first [length] bytes, starting at file offset [position]). */
        fun applyPatches(buffer: ByteArray, length: Int, position: Long, patches: List<BytePatch>) {
            for (patch in patches) {
                for (k in patch.bytes.indices) {
                    val at = patch.offset + k - position
                    if (at in 0 until length) buffer[at.toInt()] = patch.bytes[k]
                }
            }
        }

        /** First byte of a "bytes 100-199/1000" Content-Range. */
        fun contentRangeStart(header: String?): Long? =
            header?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()

        private val CORS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "Range, Content-Type, Accept-Encoding",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
        )

        /** The phone's address on the route to [remoteHost], or its first Wi-Fi/LAN address when the device's address isn't known. */
        fun addressFor(remoteHost: String?): String? = remoteHost?.let(::localAddressFor) ?: lanAddress()

        private fun lanAddress(): String? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        /** The phone's address on the route to [remoteHost] (no packet is sent). */
        fun localAddressFor(remoteHost: String): String? = runCatching {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName(remoteHost), DlnaProtocol.SSDP_PORT)
                s.localAddress.hostAddress?.takeIf { it != "0.0.0.0" && !it.contains(':') }
            }
        }.getOrNull()
    }
}
