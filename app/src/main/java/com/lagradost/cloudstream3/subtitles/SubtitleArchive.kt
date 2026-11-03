package com.lagradost.cloudstream3.subtitles

import java.io.File
import java.util.zip.ZipInputStream

internal object SubtitleArchive {
    private val extensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "xml")

    fun extract(file: File): List<Pair<String, File>> {
        val files = mutableListOf<Pair<String, File>>()
        try {
            ZipInputStream(file.inputStream()).use { zip ->
                var count = 0
                var total = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 200) { "Subtitle archive contains too many files" }
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    val extension = name.substringAfterLast('.', "").lowercase()
                    val subtitle = !entry.isDirectory && extension in extensions
                    val output = if (subtitle) File.createTempFile("subtitle-", ".$extension") else null
                    if (output != null) files += name to output
                    output?.outputStream().use { sink ->
                        while (true) {
                            val size = zip.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= 64L * 1024 * 1024) { "Subtitle archive is too large" }
                            sink?.write(buffer, 0, size)
                        }
                    }
                    if (output?.length() == 0L) {
                        files.removeLast()
                        output.delete()
                    }
                }
            }
            require(files.isNotEmpty()) { "This result has no usable subtitles. Try another result." }
            return files
        } catch (error: Exception) {
            files.forEach { it.second.delete() }
            throw error
        }
    }
}
