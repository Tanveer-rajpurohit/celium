package com.lagradost.cloudstream4.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativePlayerLiveTest {
    @Test fun installedVlcDecodesVideoAndAdvancesTime() {
        assumeTrue(System.getenv("CELIUM_LIVE_TESTS") == "true")
        val factory = MediaPlayerFactory("--quiet", "--vout=dummy", "--aout=dummy")
        val player = factory.mediaPlayers().newMediaPlayer()
        val video = AtomicBoolean(false)
        val advanced = CountDownLatch(1)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun videoOutput(mediaPlayer: MediaPlayer, count: Int) { video.set(count > 0) }
            override fun timeChanged(mediaPlayer: MediaPlayer, time: Long) { if (video.get() && time >= 1500) advanced.countDown() }
        })
        try {
            assertTrue(player.media().play("https://media.w3.org/2010/05/sintel/trailer.mp4"))
            assertTrue("VLC did not decode video and advance time", advanced.await(40, TimeUnit.SECONDS))
        } finally { player.release(); factory.release() }
    }
}
