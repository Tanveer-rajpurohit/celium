package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.subtitles.SubtitleArchive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class SubtitleArchiveTest {
    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        return File.createTempFile("subs-test", ".zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    @Test fun `archive preserves subtitle extensions and excludes directories and other files`() {
        val zip = archive("folder/" to byteArrayOf(), "folder/English.vtt" to "WEBVTT\n".toByteArray(),
            "../../Other.ass" to "[Script Info]".toByteArray(), "readme.txt" to "notes".toByteArray())
        val files = try { SubtitleArchive.extract(zip) } finally { zip.delete() }
        try {
            assertEquals(listOf("English.vtt", "Other.ass"), files.map { it.first })
            assertEquals(listOf("vtt", "ass"), files.map { it.second.extension })
            assertEquals("WEBVTT\n", files.first().second.readText())
            assertTrue(files.all { it.second.parentFile == File(requireNotNull(System.getProperty("java.io.tmpdir"))) })
        } finally { files.forEach { it.second.delete() } }
    }

    @Test fun `empty or non ZIP downloads fail with useful error`() {
        val zip = archive("readme.txt" to "no subtitles".toByteArray())
        try {
            val error = assertThrows(IllegalArgumentException::class.java) { SubtitleArchive.extract(zip) }
            assertTrue(error.message!!.contains("no usable subtitles"))
            zip.writeText("<html>Service unavailable</html>")
            assertThrows(IllegalArgumentException::class.java) { SubtitleArchive.extract(zip) }
        } finally { zip.delete() }
    }

    @Test fun `oversized expansion is rejected`() {
        val zip = archive("large.srt" to ByteArray(65 * 1024 * 1024))
        try {
            val error = assertThrows(IllegalArgumentException::class.java) { SubtitleArchive.extract(zip) }
            assertTrue(error.message!!.contains("too large"))
        } finally { zip.delete() }
    }
}
