package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.ui.player.StreamChoice
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One audio track inside a video. [language] is a two-letter tag, null when the file doesn't say. */
data class FileAudioTrack(val language: String?, val name: String?, val isDefault: Boolean)

/** Same-length replacement of the file's bytes at [offset]: nothing else in the file moves. */
class BytePatch(val offset: Long, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is BytePatch && other.offset == offset && other.bytes.contentEquals(bytes)
    override fun hashCode() = offset.hashCode() * 31 + bytes.contentHashCode()
    override fun toString() = "BytePatch($offset, ${bytes.joinToString(" ") { "%02x".format(it) }})"
}

/**
 * The audio tracks of a video and how to make each one the track a TV plays.
 *
 * TVs and DLNA players have no command to pick an audio track: they play the file's default one.
 * The relay therefore changes which track is the default while the video passes through the phone:
 * for HLS by leaving the other languages out of the playlist, for MKV and MP4 by editing a few
 * header bytes in place (same length, so seeking and every offset in the file stay valid).
 */
data class AudioLayout(
    val tracks: List<FileAudioTrack>,
    /** For files: edits that make track i the only default; null entries can't be done. Empty for HLS. */
    val patches: List<List<BytePatch>?> = emptyList(),
    val hls: Boolean = false,
) {
    /** Languages of the tracks, in file order, without repeats. */
    val languages: List<String> get() = tracks.mapNotNull { it.language }.distinct()

    /** The track a player picks by itself: the first default one, else the first. */
    val defaultIndex: Int get() = tracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0

    /** Index of the first track in [language], or null. */
    fun indexOf(language: String?): Int? = language?.let { l -> tracks.indexOfFirst { it.language == l }.takeIf { it >= 0 } }

    /** Whether the relay can make [language] the one the TV plays. */
    fun canSelect(language: String): Boolean {
        val i = indexOf(language) ?: return false
        return hls || i == defaultIndex || patches.getOrNull(i) != null
    }
}

object FileAudio {
    // ---------------- Matroska (MKV / WebM) ----------------

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_FLAG_DEFAULT = 0x88L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_BCP47 = 0x22B59DL
    private const val ID_NAME = 0x536EL
    private const val ID_CODEC_NAME = 0x258688L
    private const val ID_VOID = 0xECL

    private class Element(val id: Long, val start: Int, val dataStart: Int, val size: Long, val sizeLength: Int) {
        val end get() = dataStart + size
        val idLength get() = dataStart - sizeLength - start
    }

    /** Reads an EBML variable-length number at [i]: (value, length). The ID form keeps its marker bit. */
    private fun vint(b: ByteArray, i: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (i >= b.size) return null
        val first = b[i].toInt() and 0xFF
        if (first == 0) return null
        var length = 1
        var mask = 0x80
        while (first and mask == 0) { mask = mask shr 1; length++ }
        if (length > 8 || i + length > b.size) return null
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        for (k in 1 until length) value = (value shl 8) or (b[i + k].toLong() and 0xFF)
        // All value bits set means "unknown size".
        if (!keepMarker && value == (1L shl (7 * length)) - 1) value = -1
        return value to length
    }

    private fun element(b: ByteArray, i: Int): Element? {
        val (id, idLength) = vint(b, i, true) ?: return null
        val (size, sizeLength) = vint(b, i + idLength, false) ?: return null
        return Element(id, i, i + idLength + sizeLength, size, sizeLength)
    }

    private fun children(b: ByteArray, from: Int, to: Long): List<Element> {
        val out = mutableListOf<Element>()
        var i = from
        while (i < to && i < b.size) {
            val e = element(b, i) ?: break
            if (e.size < 0 || e.end > to) break
            out += e
            i = e.end.toInt()
        }
        return out
    }

    private fun sizeBytes(value: Long, length: Int): ByteArray {
        val coded = (1L shl (7 * length)) or value
        return ByteArray(length) { k -> (coded shr (8 * (length - 1 - k))).toByte() }
    }

    /** Audio tracks of an MKV whose start is [head]; null when it isn't MKV or its track list isn't in [head]. */
    fun parseMkv(head: ByteArray): AudioLayout? {
        if (head.size < 4 || head[0] != 0x1A.toByte() || head[1] != 0x45.toByte() || head[2] != 0xDF.toByte() || head[3] != 0xA3.toByte()) return null
        val ebml = element(head, 0) ?: return null
        val segment = element(head, ebml.end.toInt())?.takeIf { it.id == ID_SEGMENT } ?: return null
        val segmentEnd = if (segment.size < 0) head.size.toLong() else minOf(segment.end, head.size.toLong())
        var i = segment.dataStart
        var tracks: Element? = null
        while (i < segmentEnd) {
            val e = element(head, i) ?: return null
            if (e.id == ID_CLUSTER) return null // video data started: the track list wasn't before it
            if (e.id == ID_TRACKS) { tracks = e; break }
            if (e.size < 0) return null
            i = e.end.toInt()
        }
        if (tracks == null || tracks.end > head.size) return null

        val audio = mutableListOf<FileAudioTrack>()
        val entries = mutableListOf<Map<Long, Element>>()
        for (entry in children(head, tracks.dataStart, tracks.end).filter { it.id == ID_TRACK_ENTRY }) {
            val fields = children(head, entry.dataStart, entry.end)
            val byId = fields.associateBy { it.id }
            if (byId[ID_TRACK_TYPE]?.let { uint(head, it) } != 2L) continue
            val language = (byId[ID_LANGUAGE_BCP47] ?: byId[ID_LANGUAGE])?.let { text(head, it) } ?: "eng" // Matroska's default
            audio += FileAudioTrack(
                language = StreamChoice.normalize(language),
                name = byId[ID_NAME]?.let { text(head, it) }?.takeIf { it.isNotBlank() },
                isDefault = byId[ID_FLAG_DEFAULT]?.let { uint(head, it) != 0L } ?: true,
            )
            // Void elements can sit anywhere in the entry; keep them all for carving room.
            entries += byId + fields.filter { it.id == ID_VOID }.mapIndexed { k, v -> (-1L - k) to v }
        }
        if (audio.isEmpty()) return null
        val patches = audio.indices.map { wanted ->
            val edits = mutableListOf<BytePatch>()
            for ((index, fields) in entries.withIndex()) {
                if (!setMkvDefault(head, fields, index == wanted, edits)) return@map null
            }
            edits
        }
        return AudioLayout(audio, patches)
    }

    /** Adds the edits that set one track's default flag; false when the track has no room for the flag. */
    private fun setMkvDefault(b: ByteArray, fields: Map<Long, Element>, wanted: Boolean, edits: MutableList<BytePatch>): Boolean {
        val flag = fields[ID_FLAG_DEFAULT]
        if (flag != null) {
            if ((uint(b, flag) != 0L) != wanted) {
                edits += BytePatch(flag.dataStart.toLong(), ByteArray(flag.size.toInt()) { k -> if (k == flag.size.toInt() - 1 && wanted) 1 else 0 })
            }
            return true
        }
        if (wanted) return true // a missing flag already means "default"
        // Missing flag on a track that must not be default: write "FlagDefault = 0" (88 81 00) into
        // 3 bytes taken from a Void element or from the end of the track's name.
        val newFlag = byteArrayOf(0x88.toByte(), 0x81.toByte(), 0x00)
        fields.filterKeys { it < 0 }.values.firstOrNull { it.end - it.start >= 3 && (it.end - it.start - 3).let { r -> r == 0L || r >= 2 } }?.let { void ->
            val rest = (void.end - void.start - 3).toInt()
            val bytes = if (rest == 0) newFlag else {
                val sizeLength = if (rest - 2 <= 126) 1 else 8
                if (rest - 1 - sizeLength < 0) return false
                newFlag + byteArrayOf(0xEC.toByte()) + sizeBytes((rest - 1 - sizeLength).toLong(), sizeLength) + ByteArray(rest - 1 - sizeLength)
            }
            edits += BytePatch(void.start.toLong(), bytes)
            return true
        }
        for (id in listOf(ID_NAME, ID_CODEC_NAME)) {
            val e = fields[id] ?: continue
            if (e.size < 3) continue
            val shorter = e.size - 3
            val bytes = newFlag +
                b.copyOfRange(e.start, e.start + e.idLength) +
                sizeBytes(shorter, e.sizeLength) +
                b.copyOfRange(e.dataStart, e.dataStart + shorter.toInt())
            edits += BytePatch(e.start.toLong(), bytes)
            return true
        }
        return false
    }

    private fun uint(b: ByteArray, e: Element): Long {
        var v = 0L
        for (k in 0 until e.size.toInt()) v = (v shl 8) or (b[e.dataStart + k].toLong() and 0xFF)
        return v
    }

    private fun text(b: ByteArray, e: Element) = String(b, e.dataStart, e.size.toInt(), Charsets.UTF_8).trimEnd('\u0000')

    // ---------------- MP4 / MOV ----------------

    private class Box(val type: String, val start: Long, val bodyStart: Long, val end: Long)

    /** Boxes in [b] (which holds the file from [base]) between absolute offsets [from] and [to]. */
    private fun boxes(b: ByteArray, base: Long, from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var i = from
        while (i + 8 <= to) {
            val p = (i - base).toInt()
            if (p < 0 || p + 8 > b.size) break
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (p + 16 > b.size) break
                size = (u32(b, p + 8) shl 32) or u32(b, p + 12); header = 16
            } else if (size == 0L) size = to - i
            if (size < header) break
            out += Box(type, i, i + header, i + size)
            i += size
        }
        return out
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    fun isMp4(head: ByteArray) = head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "free", "mdat", "wide", "skip")

    /**
     * Where the moov box (the track list) is: a (start, end) inside [head], or the offset to read
     * from when it comes after the video data. Null when [head] isn't MP4.
     */
    fun mp4MoovLocation(head: ByteArray): Pair<Long, Long>? {
        if (!isMp4(head)) return null
        for (box in boxes(head, 0, 0, Long.MAX_VALUE)) {
            if (box.type == "moov") return box.start to box.end
            if (box.end > head.size) return box.end to -1L // moov is after this (the video data)
        }
        return null
    }

    /** Audio tracks of an MP4 from its moov box, held in [b] starting at file offset [base]. */
    fun parseMp4Moov(b: ByteArray, base: Long): AudioLayout? {
        val moov = boxes(b, base, base, base + b.size).firstOrNull { it.type == "moov" } ?: return null
        if (moov.end > base + b.size) return null
        class Trak(val flagsAt: Long, val flags: Int, val language: String?)
        val traks = mutableListOf<Trak>()
        for (trak in boxes(b, base, moov.bodyStart, moov.end).filter { it.type == "trak" }) {
            val parts = boxes(b, base, trak.bodyStart, trak.end)
            val tkhd = parts.firstOrNull { it.type == "tkhd" } ?: continue
            val mdia = parts.firstOrNull { it.type == "mdia" } ?: continue
            val media = boxes(b, base, mdia.bodyStart, mdia.end)
            val handler = media.firstOrNull { it.type == "hdlr" }?.let { String(b, (it.bodyStart - base).toInt() + 8, 4, Charsets.ISO_8859_1) }
            if (handler != "soun") continue
            val language = media.firstOrNull { it.type == "mdhd" }?.let { mdhd ->
                val p = (mdhd.bodyStart - base).toInt()
                val at = p + if (b[p].toInt() == 1) 32 else 20
                val code = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
                String(CharArray(3) { k -> (((code shr (10 - 5 * k)) and 31) + 0x60).toChar() })
            }
            val flagsAt = tkhd.bodyStart + 3 // last byte of the 24-bit flags; bit 0 = track enabled
            traks += Trak(flagsAt, b[(flagsAt - base).toInt()].toInt() and 0xFF, language)
        }
        if (traks.isEmpty()) return null
        // Players treat the enabled audio track as the default; with several enabled, the first one.
        val firstEnabled = traks.indexOfFirst { it.flags and 1 == 1 }
        val tracks = traks.mapIndexed { i, t -> FileAudioTrack(StreamChoice.normalize(t.language), null, i == firstEnabled) }
        val patches = traks.indices.map { wanted ->
            traks.mapIndexedNotNull { i, t ->
                val flags = if (i == wanted) t.flags or 1 else t.flags and 1.inv()
                if (flags == t.flags) null else BytePatch(t.flagsAt, byteArrayOf(flags.toByte()))
            }
        }
        return AudioLayout(tracks, patches)
    }

    // ---------------- HLS ----------------

    private val attribute = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attributes(line: String) = attribute.findAll(line.substringAfter(':'))
        .associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /** Audio renditions of an HLS master playlist (EXT-X-MEDIA TYPE=AUDIO); null when there are none. */
    fun parseHls(playlist: String): AudioLayout? {
        val renditions = playlist.lineSequence().map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes)
            .filter { it["TYPE"] == "AUDIO" }.toList()
        if (renditions.isEmpty()) return null
        // Several variants usually repeat the same renditions per group: list each language once.
        val tracks = renditions.map { r ->
            FileAudioTrack(StreamChoice.normalize(r["LANGUAGE"]) ?: StreamChoice.languages(r["NAME"].orEmpty())?.singleOrNull(), r["NAME"], r["DEFAULT"] == "YES")
        }.distinctBy { it.language ?: it.name }
        return AudioLayout(tracks, hls = true)
    }

    /**
     * Leaves only [language]'s audio in each rendition group that has it, marked default, so any
     * player plays it. Groups without that language, and other lines, are left as they are. Pure.
     */
    fun preferHlsAudio(playlist: String, language: String): String {
        val lines = playlist.lines()
        val audio = lines.map { line ->
            if (line.trim().startsWith("#EXT-X-MEDIA:")) attributes(line.trim()).takeIf { it["TYPE"] == "AUDIO" } else null
        }
        fun lang(a: Map<String, String>) = StreamChoice.normalize(a["LANGUAGE"]) ?: StreamChoice.languages(a["NAME"].orEmpty())?.singleOrNull()
        val groupsWithLanguage = audio.filterNotNull().filter { lang(it) == language }.mapNotNull { it["GROUP-ID"] }.toSet()
        if (groupsWithLanguage.isEmpty()) return playlist
        val keptPerGroup = HashSet<String>()
        return lines.mapIndexedNotNull { i, line ->
            val a = audio[i] ?: return@mapIndexedNotNull line
            val group = a["GROUP-ID"] ?: return@mapIndexedNotNull line
            if (group !in groupsWithLanguage) return@mapIndexedNotNull line
            if (lang(a) != language || !keptPerGroup.add(group)) return@mapIndexedNotNull null
            line.replace(Regex("DEFAULT=(YES|NO)"), "DEFAULT=YES").replace(Regex("AUTOSELECT=(YES|NO)"), "AUTOSELECT=YES")
                .let { if ("DEFAULT=" in it) it else "$it,DEFAULT=YES" }
        }.joinToString("\n")
    }

    // ---------------- Reading a link ----------------

    private const val HEAD_BYTES = 1024 * 1024
    private const val MAX_MOOV_BYTES = 24 * 1024 * 1024

    /**
     * Reads the audio tracks of [url] (only its header, or the playlist). Null when there's nothing
     * to choose: one track, an unknown format, or the host didn't answer. Blocking.
     */
    fun read(client: OkHttpClient, url: String, headers: Map<String, String>, playlist: Boolean): AudioLayout? {
        val quick = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        /** Bytes [from]..[to] (the whole body when [from] is null), never more than asked for. */
        fun fetch(from: Long?, to: Long = 0): Pair<ByteArray, String?>? = runCatching {
            val request = LinkCheck.request(url, headers).apply { if (from != null) header("Range", "bytes=$from-$to") }.build()
            quick.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                // A host that ignores the range sends the file from the start: only useful when that's what was asked.
                if (from != null && from > 0 && response.code != 206) return@use null
                @Suppress("UNNECESSARY_SAFE_CALL")
                val input = response.body?.byteStream() ?: return@use null
                val limit = if (from == null) HEAD_BYTES else (to - from + 1).coerceAtMost(MAX_MOOV_BYTES.toLong()).toInt()
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() < limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray() to response.header("Content-Range")
            }
        }.getOrNull()

        val layout = if (playlist) {
            fetch(null)?.first?.toString(Charsets.UTF_8)?.let(::parseHls)
        } else {
            val (head, contentRange) = fetch(0, HEAD_BYTES - 1L) ?: return null
            parseMkv(head) ?: mp4MoovLocation(head)?.let { (start, end) ->
                if (end >= 0 && end <= head.size) return@let parseMp4Moov(head.copyOfRange(start.toInt(), end.toInt()), start)
                val total = contentRange?.substringAfterLast('/')?.toLongOrNull() ?: return@let null
                // The moov comes after the video data: step over the boxes there (reading only their
                // 16-byte headers) and read just the moov.
                var at = start
                repeat(4) {
                    if (at + 8 > total) return@let null
                    val (box, _) = fetch(at, at + 15) ?: return@let null
                    if (box.size < 8) return@let null
                    val size = u32(box, 0).let { if (it == 1L && box.size >= 16) (u32(box, 8) shl 32) or u32(box, 12) else it }
                    if (size < 8) return@let null
                    if (String(box, 4, 4, Charsets.ISO_8859_1) == "moov") {
                        if (size > MAX_MOOV_BYTES) return@let null
                        val (moov, _) = fetch(at, at + size - 1) ?: return@let null
                        return@let parseMp4Moov(moov, at)
                    }
                    at += size
                }
                null
            }
        }
        return layout?.takeIf { it.tracks.size > 1 }
    }
}
