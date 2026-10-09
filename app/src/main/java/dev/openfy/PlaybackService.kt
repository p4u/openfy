package dev.openfy

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type mediaPlayback) that keeps the process alive and exposes the page's
 * media session natively (DESIGN §2.7). It stays foreground while a track is loaded, playing or paused.
 */
class PlaybackService : Service(), MediaBridge.Listener {
    private lateinit var session: NativeMediaSession
    private lateinit var notifications: MediaNotifications
    private lateinit var notificationManager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var artwork: Bitmap? = null
    private var artworkFor: String? = null
    private var artworkJob: Job? = null
    private var lastNotificationKey: List<Any?>? = null

    private val pausedTimeout = Runnable {
        Log.i(TAG, "service: paused for ${PAUSED_TIMEOUT_MS / 60_000} min, stopping")
        shutdown(destroyWebView = false)
    }
    private var noTrackScheduled = false
    private val noTrackTimeout = Runnable {
        val s = MediaBridge.state
        Log.i(
            TAG,
            "service: nothing loaded for ${NO_TRACK_GRACE_MS / 1000}s, stopping " +
                "(title=${s.title} declared=${s.declaredState} element=${s.elementPlaying})",
        )
        shutdown(destroyWebView = false)
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        session = CompatMediaSession(this)
        notifications = MediaNotifications(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        enterForeground(MediaBridge.state)
        MediaBridge.addListener(this)
        onBridgeState(MediaBridge.state)
        Log.i(TAG, "service: created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() requires startForeground() promptly; onCreate did it.
        when (intent?.action) {
            ACTION_CLOSE -> {
                MediaBridge.invoke("pause")
                shutdown(destroyWebView = false)
            }
            Intent.ACTION_MEDIA_BUTTON -> session.handleMediaButton(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBridgeState(state: BridgeState) {
        // "Loaded" is metadata OR a playing element (DESIGN §2.7): metadata may be cleared for a while at
        // a track change while audio continues, and that must not stop the service.
        if (!state.isLoaded) {
            handler.removeCallbacks(pausedTimeout)
            if (!noTrackScheduled) {
                noTrackScheduled = true
                handler.postDelayed(noTrackTimeout, NO_TRACK_GRACE_MS)
            }
            // Show idle instead of freezing the old track in the session and the notification.
            loadArtworkIfChanged(null)
            session.update(state, null)
            updateNotification(state)
            return
        }
        handler.removeCallbacks(noTrackTimeout)
        noTrackScheduled = false

        handler.removeCallbacks(pausedTimeout)
        if (!state.isPlaying) handler.postDelayed(pausedTimeout, PAUSED_TIMEOUT_MS)

        loadArtworkIfChanged(state.artworkUrl)
        session.update(state, artwork)
        updateNotification(state)
    }

    private fun loadArtworkIfChanged(url: String?) {
        if (url == artworkFor) return
        artworkFor = url
        artwork = null
        artworkJob?.cancel()
        if (url == null) return
        artworkJob = scope.launch {
            artwork = ArtworkLoader.load(url)
            Log.i(TAG, "artwork: ${if (artwork != null) "loaded" else "unavailable"} $url")
            onBridgeState(MediaBridge.state)
        }
    }

    private fun updateNotification(state: BridgeState) {
        // Position ticks every second; the notification only needs to change when its content does.
        val key = listOf(state.title, state.artist, state.album, state.isPlaying, state.handlers, artwork)
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        notificationManager.notify(NOTIFICATION_ID, notifications.build(state, artwork, session.token))
    }

    private fun enterForeground(state: BridgeState) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notifications.build(state, artwork, session.token),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "service: startForeground refused", e)
            stopSelf()
        }
    }

    /** DESIGN §2.1: swiped from recents. Keep playing; otherwise tear everything down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (MediaBridge.state.isPlaying) {
            Log.i(TAG, "service: task removed while playing, staying alive")
        } else {
            Log.i(TAG, "service: task removed while paused, shutting down")
            shutdown(destroyWebView = true)
        }
    }

    private fun shutdown(destroyWebView: Boolean) {
        if (destroyWebView) WebViewHolder.destroy()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        MediaBridge.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session.release()
        Log.i(TAG, "service: destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CLOSE = "dev.openfy.action.CLOSE"
        private const val NOTIFICATION_ID = 1
        private const val PAUSED_TIMEOUT_MS = 30 * 60 * 1000L
        private const val NO_TRACK_GRACE_MS = 60_000L

        /** True while the service instance exists. */
        @Volatile
        var running = false
            private set
    }
}
