package com.lagradost.cloudstream3.cast

import java.net.URI

/** Rewrites every URI in an HLS playlist so the TV fetches it through the relay (pure). */
object HlsRewriter {
    private val uriAttribute = Regex("URI=\"([^\"]*)\"")

    /**
     * @param proxy maps an absolute upstream URL to a relay URL; the flag is true for nested playlists.
     */
    fun rewrite(playlist: String, playlistUrl: String, proxy: (url: String, isPlaylist: Boolean) -> String): String {
        var nextIsPlaylist = false
        return playlist.lineSequence().joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) nextIsPlaylist = true
                    val nestedPlaylist = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                    uriAttribute.replace(line) { match ->
                        "URI=\"${proxy(resolve(playlistUrl, match.groupValues[1]), nestedPlaylist)}\""
                    }
                }
                else -> {
                    val url = resolve(playlistUrl, line.trim())
                    val playlistLine = nextIsPlaylist || isPlaylistUrl(url)
                    nextIsPlaylist = false
                    proxy(url, playlistLine)
                }
            }
        }
    }

    /** Length of a finished (VOD) media playlist: the sum of its segment durations. Null for live or master playlists. */
    fun duration(playlist: String): Long? {
        if (!playlist.contains("#EXT-X-ENDLIST")) return null
        val segments = Regex("""#EXTINF:\s*([0-9.]+)""").findAll(playlist).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (segments.isEmpty()) return null
        return (segments.sum() * 1000).toLong()
    }

    /** First variant (media playlist) of a master playlist, resolved against [playlistUrl]; null if it isn't a master. */
    fun firstVariant(playlist: String, playlistUrl: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (index < 0) return null
        return lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(playlistUrl, it) }
    }

    /** First media segment of a media playlist, resolved against [playlistUrl]. */
    fun firstSegment(playlist: String, playlistUrl: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val index = lines.indexOfFirst { it.startsWith("#EXTINF") }
        if (index < 0) return null
        return lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(playlistUrl, it) }
    }

    /** True when segments are encrypted (an EXT-X-KEY other than METHOD=NONE). */
    fun isEncrypted(playlist: String): Boolean =
        playlist.lineSequence().any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }

    fun isPlaylistUrl(url: String): Boolean =
        runCatching { URI(url).path }.getOrNull().orEmpty().lowercase().endsWith(".m3u8")

    fun isPlaylistBody(body: String): Boolean = body.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")

    fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)
}
