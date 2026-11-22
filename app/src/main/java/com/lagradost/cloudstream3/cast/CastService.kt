package com.lagradost.cloudstream3.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the cast relay alive while the phone is locked or Celium is in the background,
 * and shows a notification with pause/disconnect controls.
 */
class CastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(CastSession.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "celium:cast")
            ?.apply { setReferenceCounted(false); acquire() }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "celium:cast")
            ?.apply { setReferenceCounted(false); acquire() }
        scope.launch {
            CastSession.state
                .map { s -> Triple(s.device?.name, s.nowPlaying?.let { it.title to it.label }, s.nowPlaying?.paused) }
                .distinctUntilChanged()
                .collect {
                    val state = CastSession.state.value
                    if (!state.connected) { stopSelf(); return@collect }
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(state))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> CastSession.togglePause()
            ACTION_DISCONNECT -> CastSession.disconnect(this)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting to TV", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun pending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, CastService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(state: CastState): Notification {
        val playing = state.nowPlaying
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.cast)
            .setContentTitle(playing?.title ?: "Connected to ${state.device?.name ?: "TV"}")
            .setContentText(playing?.label ?: if (playing == null) "Tap Play in Celium to watch on the TV" else "Playing on ${state.device?.name}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (playing != null) addAction(
                    if (playing.paused) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24,
                    if (playing.paused) "Play" else "Pause", pending(ACTION_TOGGLE, 1)
                )
            }
            .addAction(R.drawable.ic_baseline_close_24, "Disconnect", pending(ACTION_DISCONNECT, 2))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "celium_cast"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_TOGGLE = "celium.cast.TOGGLE"
        private const val ACTION_DISCONNECT = "celium.cast.DISCONNECT"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, CastService::class.java))

        fun stop(context: Context) =
            context.applicationContext.stopService(Intent(context.applicationContext, CastService::class.java))
    }
}
