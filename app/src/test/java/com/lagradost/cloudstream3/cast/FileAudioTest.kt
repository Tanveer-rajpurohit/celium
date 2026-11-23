package com.lagradost.cloudstream3.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FileAudioTest {
    // ---- Tiny EBML writer for building test MKVs ----

    private fun id(value: Long): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v shr 8 }
        return bytes.toByteArray()
    }

    private fun size(n: Int): ByteArray = when {
        n < 0x7F -> byteArrayOf((0x80 or n).toByte())
        else -> byteArrayOf((0x40 or (n shr 8)).toByte(), n.toByte())
    }

    private fun el(idValue: Long, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        return id(idValue) + size(body.size) + body
    }

    private fun uintEl(idValue: Long, v: Int) = el(idValue, byteArrayOf(v.toByte()))
    private fun textEl(idValue: Long, s: String) = el(idValue, s.toByteArray())

    private fun track(number: Int, type: Int, language: String?, flagDefault: Int?, name: String? = null, void: Int = 0): ByteArray {
        val parts = mutableListOf(uintEl(0xD7, number), uintEl(0x83, type))
        if (language != null) parts += textEl(0x22B59C, language)
        if (flagDefault != null) parts += uintEl(0x88, flagDefault)
        if (name != null) parts += textEl(0x536E, name)
        if (void > 0) parts += el(0xEC, ByteArray(void - 2))
        return el(0xAE, *parts.toTypedArray())
    }

    private fun mkv(vararg tracks: ByteArray): ByteArray {
        val header = el(0x1A45DFA3, uintEl(0x4286, 1), textEl(0x4282, "matroska"))
        val tracksEl = el(0x1654AE6B, *tracks)
        val cluster = el(0x1F43B675, ByteArray(64) { 7 })
        // Segment with unknown size, as streamed files often have.
        return header + id(0x18538067) + byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) + el(0x1549A966, uintEl(0x2AD7B1, 1)) + tracksEl + cluster
    }

    private fun patched(file: ByteArray, patches: List<BytePatch>): ByteArray =
        file.copyOf().also { CastRelay.applyPatches(it, it.size, 0, patches) }

    /** The real "Heart of the Beast" case: Hindi has no flag (so it's default), English is flagged not default. */
    private val dualAudio = mkv(
        track(1, 1, "und", null, "[Fibwatch.Com]"),
        track(2, 2, "hin", null, "[Fibwatch.Com]"),
        track(3, 2, "eng", 0, "[Fibwatch.Com]"),
        track(4, 17, "eng", null, "[Fibwatch.Com]"),
    )

    @Test
    fun mkvListsAudioTracksAndTheirDefault() {
        val layout = FileAudio.parseMkv(dualAudio)!!
        assertEquals(listOf("hi", "en"), layout.languages)
        assertEquals(0, layout.defaultIndex)
        assertTrue(layout.canSelect("en"))
        assertFalse(layout.canSelect("ta"))
    }

    @Test
    fun mkvEnglishBecomesTheOnlyDefaultWithoutChangingTheSize() {
        val layout = FileAudio.parseMkv(dualAudio)!!
        val edited = patched(dualAudio, layout.patches[1]!!)
        assertEquals(dualAudio.size, edited.size)
        val after = FileAudio.parseMkv(edited)!!
        assertEquals(listOf(false, true), after.tracks.map { it.isDefault })
        assertEquals(1, after.defaultIndex)
        // The video data after the header is untouched.
        assertArrayEquals(dualAudio.copyOfRange(dualAudio.size - 64, dualAudio.size), edited.copyOfRange(edited.size - 64, edited.size))
    }

    @Test
    fun mkvChoosingTheCurrentDefaultNeedsNoEdits() {
        assertEquals(emptyList<BytePatch>(), FileAudio.parseMkv(dualAudio)!!.patches[0])
    }

    @Test
    fun mkvUsesVoidSpaceWhenThereIsNoName() {
        val file = mkv(track(1, 2, "hin", null, void = 10), track(2, 2, "eng", 0))
        val after = FileAudio.parseMkv(patched(file, FileAudio.parseMkv(file)!!.patches[1]!!))!!
        assertEquals(listOf(false, true), after.tracks.map { it.isDefault })
    }

    @Test
    fun mkvWithoutRoomForTheFlagCantSwitch() {
        val file = mkv(track(1, 2, "hin", null), track(2, 2, "eng", 0))
        val layout = FileAudio.parseMkv(file)!!
        assertNull(layout.patches[1])
        assertFalse(layout.canSelect("en"))
        assertTrue(layout.canSelect("hi"))
    }

    @Test
    fun mkvMissingLanguageMeansEnglish() {
        val layout = FileAudio.parseMkv(mkv(track(1, 2, "hin", 1), track(2, 2, null, 0)))!!
        assertEquals(listOf("hi", "en"), layout.languages)
    }

    @Test
    fun notMkvIsIgnored() {
        assertNull(FileAudio.parseMkv(ByteArray(100)))
        assertNull(FileAudio.parseMkv(mkv(track(1, 1, "und", null)).copyOf(40)))
    }

    // ---- MP4 ----

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        val out = ByteArrayOutputStream()
        val total = 8 + body.size
        out.write(byteArrayOf((total shr 24).toByte(), (total shr 16).toByte(), (total shr 8).toByte(), total.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1)); out.write(body)
        return out.toByteArray()
    }

    private fun trak(handler: String, language: String, enabled: Boolean): ByteArray {
        val tkhd = box("tkhd", byteArrayOf(0, 0, 0, if (enabled) 3 else 2) + ByteArray(80))
        val code = language.fold(0) { acc, c -> (acc shl 5) or (c.code - 0x60) }
        val mdhd = box("mdhd", ByteArray(20) + byteArrayOf((code shr 8).toByte(), code.toByte(), 0, 0))
        val hdlr = box("hdlr", ByteArray(8) + handler.toByteArray(Charsets.ISO_8859_1) + ByteArray(12))
        return box("trak", tkhd, box("mdia", mdhd, hdlr))
    }

    private val mp4 = box("ftyp", "isom".toByteArray()) +
        box("moov", trak("vide", "und", true), trak("soun", "hin", true), trak("soun", "eng", true)) +
        box("mdat", ByteArray(32))

    @Test
    fun mp4FirstEnabledAudioIsTheDefaultAndCanBeSwitched() {
        val (start, end) = FileAudio.mp4MoovLocation(mp4)!!
        val layout = FileAudio.parseMp4Moov(mp4.copyOfRange(start.toInt(), end.toInt()), start)!!
        assertEquals(listOf("hi", "en"), layout.languages)
        assertEquals(0, layout.defaultIndex)
        val edited = patched(mp4, layout.patches[1]!!)
        val after = FileAudio.parseMp4Moov(edited.copyOfRange(start.toInt(), end.toInt()), start)!!
        assertEquals(1, after.defaultIndex)
        assertEquals(mp4.size, edited.size)
    }

    @Test
    fun mp4WithMoovAtTheEndPointsPastTheVideoData() {
        val file = box("ftyp", "isom".toByteArray()) + box("mdat", ByteArray(5000)) + box("moov", trak("soun", "eng", true))
        val (next, end) = FileAudio.mp4MoovLocation(file.copyOf(1000))!!
        assertEquals(-1L, end)
        assertEquals(12L + 8 + 5000, next) // ftyp (12 bytes) + mdat (8 + 5000)
    }

    // ---- HLS ----

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="hin",NAME="Hindi",DEFAULT=YES,AUTOSELECT=YES,URI="hi.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="eng",NAME="English",DEFAULT=NO,AUTOSELECT=YES,URI="en.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="eng",NAME="English",URI="subs.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aud",SUBTITLES="subs"
        video.m3u8
    """.trimIndent()

    @Test
    fun hlsListsAudioRenditions() {
        val layout = FileAudio.parseHls(master)!!
        assertTrue(layout.hls)
        assertEquals(listOf("hi", "en"), layout.languages)
        assertEquals(0, layout.defaultIndex)
        assertTrue(layout.canSelect("en"))
        assertNull(FileAudio.parseHls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8"))
    }

    @Test
    fun hlsKeepsOnlyTheChosenLanguageAsDefault() {
        val out = FileAudio.preferHlsAudio(master, "en")
        assertFalse(out.contains("hi.m3u8"))
        assertTrue(out.lines().any { it.contains("en.m3u8") && it.contains("DEFAULT=YES") })
        assertTrue(out.contains("subs.m3u8"))
        assertTrue(out.contains("video.m3u8"))
        // A language the playlist doesn't have leaves it unchanged.
        assertEquals(master, FileAudio.preferHlsAudio(master, "ta"))
    }

    // ---- Relay helpers ----

    @Test
    fun patchesApplyAcrossChunkBoundaries() {
        val patch = BytePatch(98, byteArrayOf(1, 2, 3, 4))
        val first = ByteArray(100)
        CastRelay.applyPatches(first, 100, 0, listOf(patch))
        assertArrayEquals(byteArrayOf(1, 2), first.copyOfRange(98, 100))
        val second = ByteArray(100)
        CastRelay.applyPatches(second, 100, 100, listOf(patch))
        assertArrayEquals(byteArrayOf(3, 4, 0), second.copyOfRange(0, 3))
        // A range starting after the header is left alone.
        val later = ByteArray(10)
        CastRelay.applyPatches(later, 10, 5000, listOf(patch))
        assertArrayEquals(ByteArray(10), later)
    }

    @Test
    fun contentRangeStartIsRead() {
        assertEquals(500L, CastRelay.contentRangeStart("bytes 500-999/1000"))
        assertNotNull(CastRelay.contentRangeStart("bytes 0-0/*"))
        assertNull(CastRelay.contentRangeStart(null))
    }
}
