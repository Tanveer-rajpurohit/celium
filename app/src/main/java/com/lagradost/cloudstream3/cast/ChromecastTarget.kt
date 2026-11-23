package com.lagradost.cloudstream3.cast

import android.content.Context
import androidx.core.net.toUri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.cast.CastDevice as GoogleCastDevice
import com.google.android.gms.cast.framework.CastSession as GoogleCastSession

/**
 * A Chromecast / Google TV, driven through Google's Cast SDK and its Default Media Receiver.
 * Videos and subtitles come from Celium's relay, like for DLNA TVs. Every SDK call runs on the main thread.
 */
class ChromecastTarget(private val session: GoogleCastSession) : CastTarget {
    private val castDevice: GoogleCastDevice? = session.castDevice

    override val device = CastDevice(
        castDevice?.deviceId ?: "chromecast",
        castDevice?.friendlyName ?: "Chromecast",
        CastKind.Chromecast,
    )
    override val host: String? = castDevice?.inetAddress?.hostAddress
    override val subtitleMime = "text/vtt"
    override val hasVolume = true

    /** The session this target drives, to recognise it when the SDK reports it ended. */
    fun owns(other: GoogleCastSession) = other === session

    private fun client() = session.remoteMediaClient ?: throw IOException("${device.name} isn't ready")

    override suspend fun load(media: CastLoad) = main {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            media.poster?.let { addImage(WebImage(it.toUri())) }
        }
        val tracks = listOfNotNull(media.subtitleUrl?.let { url ->
            MediaTrack.Builder(SUBTITLE_TRACK_ID, MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(url)
                .setContentType("text/vtt")
                .setName(media.subtitleName ?: "Subtitles")
                .build()
        })
        val info = MediaInfo.Builder(media.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.mime)
            .setMetadata(metadata)
            .setMediaTracks(tracks)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(media.startMs)
            .apply { if (tracks.isNotEmpty()) setActiveTrackIds(longArrayOf(SUBTITLE_TRACK_ID)) }
            .build()
        client().load(request).await("load the video")
    }

    override suspend fun play() = main { client().play().await("play") }
    override suspend fun pause() = main { client().pause().await("pause") }
    override suspend fun stop() = main { client().stop().await("stop") }

    override suspend fun seek(ms: Long) = main {
        client().seek(MediaSeekOptions.Builder().setPosition(ms).build()).await("seek")
    }

    override suspend fun status(): CastStatus = withContext(Dispatchers.Main) {
        if (!session.isConnected) throw IOException("${device.name} disconnected")
        val client = session.remoteMediaClient ?: return@withContext CastStatus("NO_MEDIA_PRESENT", null, null)
        val state = when (client.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
            MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED_PLAYBACK"
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> "TRANSITIONING"
            MediaStatus.PLAYER_STATE_IDLE ->
                if (client.idleReason == MediaStatus.IDLE_REASON_NONE && client.mediaInfo == null) "NO_MEDIA_PRESENT" else "STOPPED"
            else -> "NO_MEDIA_PRESENT"
        }
        CastStatus(
            state = state,
            positionMs = client.approximateStreamPosition.takeIf { it >= 0 },
            durationMs = client.streamDuration.takeIf { it > 0 },
            trackUri = client.mediaInfo?.contentId,
        )
    }

    override suspend fun volume(): Int? = withContext(Dispatchers.Main) {
        runCatching { (session.volume * 100).toInt() }.getOrNull()
    }

    override suspend fun setVolume(volume: Int) = main { session.volume = volume.coerceIn(0, 100) / 100.0 }

    override suspend fun muted(): Boolean? = withContext(Dispatchers.Main) { runCatching { session.isMute }.getOrNull() }

    override suspend fun setMuted(muted: Boolean) = main { session.isMute = muted }

    override suspend fun tracks(): List<CastTrack> = withContext(Dispatchers.Main) {
        val client = session.remoteMediaClient ?: return@withContext emptyList()
        val active = client.mediaStatus?.activeTrackIds?.toSet().orEmpty()
        client.mediaInfo?.mediaTracks.orEmpty()
            .filter { it.type == MediaTrack.TYPE_AUDIO || it.type == MediaTrack.TYPE_TEXT }
            .map { t ->
                CastTrack(
                    id = t.id,
                    audio = t.type == MediaTrack.TYPE_AUDIO,
                    name = t.name?.takeIf { it.isNotBlank() } ?: t.language ?: "Track ${t.id}",
                    language = t.language,
                    active = t.id in active,
                )
            }
    }

    override suspend fun selectTracks(ids: List<Long>) = main {
        client().setActiveMediaTracks(ids.toLongArray()).await("switch tracks")
    }

    /** Leaves the receiver app, so the TV returns to its home screen. */
    override suspend fun release() = main {
        CastContext.getSharedInstance()?.sessionManager?.endCurrentSession(true)
    }

    private suspend fun main(block: suspend () -> Unit) = withContext(Dispatchers.Main) { block() }

    companion object {
        const val SUBTITLE_TRACK_ID = 1L
    }
}

/** Waits for a Cast SDK request; throws with a plain message when the device refuses it. */
private suspend fun <R : Result> PendingResult<R>.await(what: String): R = suspendCancellableCoroutine { cont ->
    setResultCallback { result ->
        if (result.status.isSuccess) cont.resume(result)
        else cont.resumeWithException(IOException("The Chromecast couldn't $what (${result.status.statusCode})"))
    }
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Finding and connecting to Chromecasts with Google's Cast SDK. */
object Chromecast {
    /** Null when Google Play Services / Cast isn't available on this phone. */
    fun context(): CastContext? = runCatching { CastContext.getSharedInstance() }.getOrNull()

    fun selector(): MediaRouteSelector? = context()?.mergedSelector

    /** Chromecasts currently visible to the media router (call on the main thread). */
    fun routes(context: Context, selector: MediaRouteSelector): List<MediaRouter.RouteInfo> =
        MediaRouter.getInstance(context).routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }

    @Volatile private var watching = false

    /**
     * Connects to [route] and returns a target for it. Fails with a plain message when the
     * Chromecast doesn't answer or refuses the connection.
     */
    suspend fun connect(context: Context, route: MediaRouter.RouteInfo): ChromecastTarget = withContext(Dispatchers.Main) {
        val cast = context() ?: throw IOException("Chromecast isn't available on this phone")
        val manager = cast.sessionManager
        watchEnds()
        val session = withTimeout(25_000) {
            suspendCancellableCoroutine<GoogleCastSession> { cont ->
                val listener = object : SessionManagerListener<GoogleCastSession> {
                    fun done(session: GoogleCastSession?, error: Int?) {
                        manager.removeSessionManagerListener(this, GoogleCastSession::class.java)
                        if (!cont.isActive) return
                        if (session != null) cont.resume(session)
                        else cont.resumeWithException(IOException("Couldn't connect to ${route.name}${error?.let { " (error $it)" }.orEmpty()}"))
                    }
                    override fun onSessionStarted(session: GoogleCastSession, sessionId: String) = done(session, null)
                    override fun onSessionResumed(session: GoogleCastSession, wasSuspended: Boolean) = done(session, null)
                    override fun onSessionStartFailed(session: GoogleCastSession, error: Int) = done(null, error)
                    override fun onSessionResumeFailed(session: GoogleCastSession, error: Int) = done(null, error)
                    override fun onSessionStarting(session: GoogleCastSession) {}
                    override fun onSessionEnding(session: GoogleCastSession) {}
                    override fun onSessionEnded(session: GoogleCastSession, error: Int) {}
                    override fun onSessionResuming(session: GoogleCastSession, sessionId: String) {}
                    override fun onSessionSuspended(session: GoogleCastSession, reason: Int) {}
                }
                manager.addSessionManagerListener(listener, GoogleCastSession::class.java)
                cont.invokeOnCancellation { manager.removeSessionManagerListener(listener, GoogleCastSession::class.java) }
                // Already connected to this Chromecast (e.g. from another app screen): reuse it.
                val current = manager.currentCastSession
                if (current != null && current.isConnected && current.castDevice?.deviceId == GoogleCastDevice.getFromBundle(route.extras)?.deviceId) {
                    listener.done(current, null)
                } else {
                    MediaRouter.getInstance(context).selectRoute(route)
                }
            }
        }
        ChromecastTarget(session)
    }

    /** When a Chromecast session ends outside Celium (TV remote, another phone), disconnect cleanly. */
    private fun watchEnds() {
        if (watching) return
        val manager = context()?.sessionManager ?: return
        watching = true
        manager.addSessionManagerListener(object : SessionManagerListener<GoogleCastSession> {
            override fun onSessionEnded(session: GoogleCastSession, error: Int) = lost(session)
            override fun onSessionSuspended(session: GoogleCastSession, reason: Int) {}
            override fun onSessionStarted(session: GoogleCastSession, sessionId: String) {}
            override fun onSessionResumed(session: GoogleCastSession, wasSuspended: Boolean) {}
            override fun onSessionStartFailed(session: GoogleCastSession, error: Int) {}
            override fun onSessionResumeFailed(session: GoogleCastSession, error: Int) {}
            override fun onSessionStarting(session: GoogleCastSession) {}
            override fun onSessionEnding(session: GoogleCastSession) {}
            override fun onSessionResuming(session: GoogleCastSession, sessionId: String) {}
        }, GoogleCastSession::class.java)
    }

    private fun lost(session: GoogleCastSession) {
        val target = CastSession.target() as? ChromecastTarget ?: return
        if (target.owns(session)) CastSession.targetLost(target)
    }
}
