package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.utils.ExtractorLink
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.BorderLayout
import java.net.URI
import javax.swing.*

internal fun mediaOptions(link: ExtractorLink): Array<String> {
    require(URI(link.url).scheme in setOf("https", "http")) { "This source uses an unsupported playback protocol." }
    require(link.audioTracks.isEmpty()) { "Separate audio streams are not supported yet. Choose another source." }
    val headers = link.getAllHeaders()
    require(headers.keys.all { it.lowercase() in setOf("referer", "user-agent") }) {
        "This source needs request headers the current player cannot send. Choose another source."
    }
    require(headers.values.none { '\r' in it || '\n' in it }) { "Invalid playback headers." }
    return headers.map { (name, value) ->
        if (name.equals("referer", true)) ":http-referrer=$value" else ":http-user-agent=$value"
    }.toTypedArray()
}

internal class DesktopVideoPanel(
    link: ExtractorLink,
    onStarted: () -> Unit,
    onFailure: (String) -> Unit,
    onFullscreen: () -> Unit,
) : JPanel(BorderLayout()) {
    private val options = mediaOptions(link)
    private val video = EmbeddedMediaPlayerComponent()
    private val player = video.mediaPlayer()
    private val position = JSlider(0, 1000, 0)
    private var closed = false
    private var updatingPosition = false
    private var started = false
    private val timer = Timer(500) {
        if (!position.valueIsAdjusting) {
            updatingPosition = true
            position.value = (player.status().position() * 1000).toInt().coerceIn(0, 1000)
            updatingPosition = false
        }
    }
    private val startupDeadline = Timer(30_000) {
        if (!started) onFailure("Playback did not start. Return to sources and try another source.")
    }.apply { isRepeats = false }

    init {
        add(video, BorderLayout.CENTER)
        val controls = JPanel()
        controls.add(JButton("Play / pause").apply {
            addActionListener { player.submit { player.controls().pause() } }
        })
        position.accessibleContext.accessibleName = "Playback position"
        position.addChangeListener {
            if (!updatingPosition && !position.valueIsAdjusting) {
                val target = position.value / 1000f
                player.submit { if (player.status().isSeekable) player.controls().setPosition(target) }
            }
        }
        controls.add(position)
        controls.add(JLabel("Volume"))
        controls.add(JSlider(0, 100, 80).apply {
            accessibleContext.accessibleName = "Volume"
            addChangeListener { val volume = value; player.submit { player.audio().setVolume(volume) } }
        })
        controls.add(JButton("Fullscreen").apply { addActionListener { onFullscreen() } })
        add(controls, BorderLayout.SOUTH)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater { if (!closed) { started = true; startupDeadline.stop(); onStarted() } }
            }
            override fun error(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater {
                    if (!closed) { startupDeadline.stop(); onFailure("Playback failed on this source. Return to sources and try another.") }
                }
            }
        })
        timer.start()
        startupDeadline.start()
        // The Canvas needs a native peer before LibVLC is attached to it.
        SwingUtilities.invokeLater {
            if (!closed) player.submit {
                player.audio().setVolume(80)
                player.media().play(link.url, *options)
            }
        }
    }

    fun close() {
        closed = true
        timer.stop()
        startupDeadline.stop()
        video.release()
    }
}
