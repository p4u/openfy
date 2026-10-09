package dev.openfy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

/** MediaStyle notification bound to the session token (DESIGN §2.7). */
class MediaNotifications(private val context: Context) {

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(state: BridgeState, artwork: Bitmap?, token: MediaSessionCompat.Token?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(state.title ?: context.getString(R.string.notification_idle_title))
            .setContentText(state.artist)
            .setSubText(state.album)
            .setLargeIcon(artwork)
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.isPlaying)

        builder.addAction(action(android.R.drawable.ic_media_previous, "Previous", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
        if (state.isPlaying) {
            builder.addAction(action(android.R.drawable.ic_media_pause, "Pause", PlaybackStateCompat.ACTION_PAUSE))
        } else {
            builder.addAction(action(android.R.drawable.ic_media_play, "Play", PlaybackStateCompat.ACTION_PLAY))
        }
        builder.addAction(action(android.R.drawable.ic_media_next, "Next", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_close),
                PendingIntent.getService(
                    context,
                    REQUEST_CLOSE,
                    Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_CLOSE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ),
        )

        val style = MediaStyle().setShowActionsInCompactView(0, 1, 2)
        if (token != null) style.setMediaSession(token)
        return builder.setStyle(style).build()
    }

    private fun action(icon: Int, title: String, mediaAction: Long) = NotificationCompat.Action(
        icon,
        title,
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, mediaAction),
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "playback"
        const val REQUEST_OPEN = 1
        const val REQUEST_CLOSE = 2
    }
}
