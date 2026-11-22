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

    fun isPlaylistUrl(url: String): Boolean =
        runCatching { URI(url).path }.getOrNull().orEmpty().lowercase().endsWith(".m3u8")

    fun isPlaylistBody(body: String): Boolean = body.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")

    fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)
}
