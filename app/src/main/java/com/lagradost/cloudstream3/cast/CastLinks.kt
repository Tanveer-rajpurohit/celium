package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.ui.player.StreamLabels
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI

/** Pure decisions for casting: which links to try, their media type, and when an episode ended. */
object CastLinks {
    /** Links a TV renderer can play through the relay, best quality first. DASH/torrents are skipped. */
    fun rank(links: List<ExtractorLink>): List<ExtractorLink> = links
        .filter { (it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8) && it.audioTracks.isEmpty() }
        .distinctBy { it.url }
        .sortedWith(compareByDescending<ExtractorLink> { it.quality }.thenBy { if (it.type == ExtractorLinkType.VIDEO) 0 else 1 })

    fun mime(type: ExtractorLinkType, url: String): String {
        if (type == ExtractorLinkType.M3U8) return "application/vnd.apple.mpegurl"
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return when {
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".avi") -> "video/x-msvideo"
            path.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    fun extension(mime: String): String = when (mime) {
        "application/vnd.apple.mpegurl" -> "m3u8"
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/x-msvideo" -> "avi"
        "video/mp2t" -> "ts"
        "text/srt" -> "srt"
        else -> "mp4"
    }

    /**
     * True when playback ended on its own (so the next episode should start), based on the last
     * position seen while it was playing. Renderers often reset the position to 0 once stopped.
     */
    fun endedNaturally(lastPositionMs: Long, lastDurationMs: Long, userStopped: Boolean): Boolean {
        if (userStopped || lastDurationMs <= 0) return false
        return lastPositionMs >= lastDurationMs - 30_000 || lastPositionMs * 100 / lastDurationMs >= 95
    }

    fun label(link: ExtractorLink): String = "${StreamLabels.source(link.name)} · ${StreamLabels.quality(link.quality)}"

    /**
     * Plain-language reason a link cannot be streamed, from a quick check made by the phone;
     * null when the link looks playable.
     */
    fun problem(code: Int?, contentType: String?, bodyStart: String?, playlist: Boolean, timedOut: Boolean = false): String? {
        if (timedOut) return "The video host didn't respond"
        if (code == null) return "Couldn't reach the video host"
        when {
            code == 401 || code == 403 -> return "The video host blocked this link"
            code == 404 || code == 410 -> return "This link has expired or the video was removed"
            code == 429 -> return "The video host is limiting requests, try again later"
            code >= 500 -> return "The video host is having problems"
            code !in 200..299 -> return "The video host refused the request (error $code)"
        }
        val type = contentType.orEmpty().lowercase()
        val body = bodyStart.orEmpty().trimStart('﻿', ' ', '\r', '\n', '\t')
        if (playlist) {
            return if (body.startsWith("#EXTM3U")) null else "The video host sent a web page instead of the video"
        }
        if (type.startsWith("text/html") || body.startsWith("<!DOCTYPE", true) || body.startsWith("<html", true))
            return "The video host sent a web page instead of the video"
        return null
    }

    private val vttTime = Regex("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
    private val vttTag = Regex("<(?!/?[ibu]>)[^>]*>")
    private val blankLines = Regex("\\n{2,}")

    /** Converts WebVTT to SRT, which TVs read. Text that is not WebVTT is returned unchanged. */
    fun vttToSrt(text: String): String {
        val clean = text.trimStart('﻿').replace("\r\n", "\n").replace('\r', '\n')
        if (!clean.startsWith("WEBVTT")) return text
        val out = StringBuilder()
        var n = 1
        for (block in clean.split(blankLines)) {
            val lines = block.lines()
            val timing = lines.indexOfFirst { it.contains("-->") }
            if (timing < 0) continue // header, NOTE, STYLE, REGION
            val times = vttTime.findAll(lines[timing]).take(2).map { m ->
                val h = m.groupValues[1].ifEmpty { "0" }.toInt()
                "%02d:%s:%s,%s".format(h, m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }.toList()
            if (times.size < 2) continue
            val body = lines.drop(timing + 1).map { it.replace(vttTag, "") }.filter { it.isNotBlank() }
            if (body.isEmpty()) continue
            out.append(n++).append('\n').append(times[0]).append(" --> ").append(times[1]).append('\n')
            body.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }
}
