package com.lagradost.cloudstream3.cast

import kotlinx.coroutines.CancellationException

/**
 * Like runCatching, for calls that wait on the TV or the network: failures become a failed Result,
 * but a cancelled coroutine stays cancelled instead of carrying on (e.g. after the user switched source).
 */
internal inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

enum class CastKind { Dlna, Chromecast }

/** The device being cast to, as shown to the user. */
data class CastDevice(val id: String, val name: String, val kind: CastKind)

/**
 * Playback state in DLNA's terms, which the session logic uses for every device:
 * PLAYING, PAUSED_PLAYBACK, TRANSITIONING, STOPPED or NO_MEDIA_PRESENT.
 */
data class CastStatus(val state: String, val positionMs: Long?, val durationMs: Long?, val trackUri: String? = null)

/** What to play: relay URLs plus what the TV shows about it. */
data class CastLoad(
    val url: String,
    val mime: String,
    val title: String,
    val subtitleUrl: String? = null,
    val subtitleName: String? = null,
    val poster: String? = null,
    val startMs: Long = 0,
)

/** An audio or subtitle track inside the playing video (Chromecast only). */
data class CastTrack(val id: Long, val audio: Boolean, val name: String, val language: String?, val active: Boolean)

/** A TV or device Celium can cast to: DLNA renderers and Chromecasts. */
interface CastTarget {
    val device: CastDevice

    /** The device's IP address, used to pick the phone's address on the same network. */
    val host: String?

    /** MIME type the relay should serve subtitles as for this device. */
    val subtitleMime: String

    val isChromecast: Boolean get() = device.kind == CastKind.Chromecast

    /** Starts loading [media]; playback may need [play] afterwards. Throws when the device refuses it. */
    suspend fun load(media: CastLoad)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(ms: Long)
    suspend fun status(): CastStatus

    /** Whether the device offers volume control. */
    val hasVolume: Boolean

    /** Volume 0–100, or null when the device doesn't say. */
    suspend fun volume(): Int?
    suspend fun setVolume(volume: Int)
    suspend fun muted(): Boolean?
    suspend fun setMuted(muted: Boolean)

    /** Audio and subtitle tracks inside the playing video; empty when the device can't report them. */
    suspend fun tracks(): List<CastTrack> = emptyList()

    /** Activates these track ids (one audio, optionally one subtitle). */
    suspend fun selectTracks(ids: List<Long>) {}

    /** Ends the connection to the device (Chromecast: leaves the receiver app). */
    suspend fun release() {}
}
