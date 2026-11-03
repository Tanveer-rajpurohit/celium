package com.lagradost.cloudstream3.subtitles

import androidx.core.net.toUri
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import okio.BufferedSource
import okio.buffer
import okio.sink
import java.io.File

/**
 * A builder for subtitle files.
 * @see addUrl
 * @see addFile
 */
class SubtitleResource {
    fun downloadFile(source: BufferedSource): File {
        val file = File.createTempFile("temp-subtitle", ".tmp").apply {
            deleteFileOnExit(this)
        }
        try {
            source.use { input ->
                file.sink().buffer().use { sink ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        require(total <= 16L * 1024 * 1024) { "Subtitle download is too large" }
                        sink.write(buffer, 0, size)
                    }
                }
            }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    data class SingleSubtitleResource(
        val name: String?,
        val url: String,
        val origin: SubtitleOrigin
    )

    private var resources: MutableList<SingleSubtitleResource> = mutableListOf()

    fun getSubtitles(): List<SingleSubtitleResource> {
        return resources.toList()
    }

    fun addUrl(url: String?, name: String? = null) {
        if (url == null) return
        this.resources.add(
            SingleSubtitleResource(name, url, SubtitleOrigin.URL)
        )
    }

    fun addFile(file: File, name: String? = null) {
        this.resources.add(
            SingleSubtitleResource(name, file.toUri().toString(), SubtitleOrigin.DOWNLOADED_FILE)
        )
        deleteFileOnExit(file)
    }

    suspend fun addZipUrl(
        url: String,
        nameGenerator: (String, File) -> String? = { _, _ -> null }
    ) {
        val zip = app.get(url).okhttpResponse.use { response ->
            require(response.isSuccessful) { "Subtitle download failed (HTTP ${response.code}). Try another result." }
            downloadFile(response.body.source())
        }
        val realFiles = try { SubtitleArchive.extract(zip) } finally { zip.delete() }
        realFiles.forEach { (name, subtitleFile) ->
            addFile(subtitleFile, nameGenerator(name, subtitleFile) ?: name)
        }
    }
}

