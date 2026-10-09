package dev.openspotify.shell

import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import org.json.JSONObject

/** Narrow seam so MediaSessionCompat can later be swapped for Media3 (DESIGN §2.7). */
interface NativeMediaSession {
    val token: MediaSessionCompat.Token
    fun update(state: BridgeState, artwork: Bitmap?)
    fun handleMediaButton(intent: Intent)
    fun release()
}

/**
 * Mirrors [BridgeState] into a MediaSessionCompat and routes transport callbacks back to the page.
 * It deliberately never requests audio focus: WebView already does (DESIGN §2.6, review M2).
 */
class CompatMediaSession(context: android.content.Context) : NativeMediaSession {
    private val session = MediaSessionCompat(context, TAG)

    override val token: MediaSessionCompat.Token get() = session.sessionToken

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = MediaBridge.invoke("play")
            override fun onPause() = MediaBridge.invoke("pause")
            override fun onSkipToNext() = MediaBridge.invoke("nexttrack")
            override fun onSkipToPrevious() = MediaBridge.invoke("previoustrack")
            override fun onSeekTo(pos: Long) =
                MediaBridge.invoke("seekto", JSONObject().put("seekTime", pos / 1000.0))
        })
    }

    /** What the last `setMetadata` call was built from; position ticks at 1 Hz must not resend it. */
    private var lastMetadataKey: List<Any?>? = null

    override fun update(state: BridgeState, artwork: Bitmap?) {
        session.isActive = state.hasTrack
        val metadataKey = listOf(state.title, state.artist, state.album, state.durationMs, artwork)
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs.takeIf { it > 0 } ?: -1)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                    .build(),
            )
        }
        val playbackState = when {
            !state.hasTrack -> PlaybackStateCompat.STATE_NONE
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val speed = if (state.isPlaying) state.rate.toFloat() else 0f
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actionsFor(state))
                .setState(playbackState, state.positionMs, speed, state.positionUpdatedAt)
                .build(),
        )
    }

    /** Play and pause always work (element fallback in the bridge); the rest depend on page handlers. */
    private fun actionsFor(state: BridgeState): Long {
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        if ("nexttrack" in state.handlers) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if ("previoustrack" in state.handlers) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if ("seekto" in state.handlers) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        return actions
    }

    override fun handleMediaButton(intent: Intent) {
        MediaButtonReceiver.handleIntent(session, intent)
    }

    override fun release() {
        session.isActive = false
        session.release()
    }
}
