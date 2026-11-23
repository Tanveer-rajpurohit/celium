package com.lagradost.cloudstream3.utils

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.lagradost.cloudstream3.ui.ControllerActivity
import java.util.*

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // No SDK notification: Celium's cast notification and cast screen (cast/CastService, cast/CastUi) are used instead.
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .setExpandedControllerActivityClassName(ControllerActivity::class.qualifiedName!!)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(p0: Context): MutableList<SessionProvider> {
        return Collections.emptyList()
    }
}
