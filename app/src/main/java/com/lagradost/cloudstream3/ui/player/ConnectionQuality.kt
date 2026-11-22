package com.lagradost.cloudstream3.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * Auto quality cap for the current connection. ExoPlayer's shared bandwidth meter starts from an
 * estimate for the network type and country, then learns from real downloads during playback.
 */
object ConnectionQuality {
    @OptIn(UnstableApi::class)
    fun bitrateEstimate(context: Context): Long? =
        runCatching { DefaultBandwidthMeter.getSingletonInstance(context.applicationContext).bitrateEstimate }
            .getOrNull()?.takeIf { it > 0 }

    fun autoCap(context: Context?): Int = StreamChoice.capFor(context?.let(::bitrateEstimate))
}
