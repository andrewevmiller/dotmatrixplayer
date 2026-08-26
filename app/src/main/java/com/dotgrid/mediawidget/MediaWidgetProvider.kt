package com.dotgrid.mediawidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log

/**
 * The widget itself: receives taps, forwards them to the live MediaSession, and
 * repaints.
 *
 * The provider deliberately owns no state. Every command re-resolves the
 * current session, because the session that was on screen when the PendingIntent
 * was built may be gone by the time the user's finger lands.
 */
class MediaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val snapshot = MediaHub.snapshot(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(
                id,
                WidgetRenderer.build(context, appWidgetManager, id, snapshot)
            )
        }
    }

    /** Resize: the bar bitmap is width-dependent, so it has to be redrawn. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(
            appWidgetId,
            WidgetRenderer.build(context, appWidgetManager, appWidgetId, MediaHub.snapshot(context))
        )
    }

    override fun onEnabled(context: Context) {
        // First instance placed - start the ticker if something is already playing.
        NotificationHookService.requestSync(context)
    }

    override fun onDisabled(context: Context) {
        // Last instance removed; nothing left to keep warm.
        NotificationHookService.requestSync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        when (intent.action) {
            ACTION_PLAY_PAUSE -> transport(context, targetPackage) { controller ->
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) controller.transportControls.pause()
                else controller.transportControls.play()
            }

            ACTION_NEXT -> transport(context, targetPackage) {
                it.transportControls.skipToNext()
            }

            ACTION_PREVIOUS -> transport(context, targetPackage) {
                it.transportControls.skipToPrevious()
            }

            ACTION_SEEK -> {
                val fraction = intent.getFloatExtra(EXTRA_SEEK_FRACTION, -1f)
                if (fraction < 0f) return
                transport(context, targetPackage) { controller ->
                    val duration = controller.metadata
                        ?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                        ?: 0L
                    if (duration > 0L) {
                        controller.transportControls.seekTo((duration * fraction).toLong())
                    }
                }
            }

            // The session is gone, so there is nothing to send a command to.
            // Wake the app that owned it instead, and let its own session come
            // back up; goAsync keeps this receiver alive across the connect.
            ACTION_RESUME -> {
                val pkg = targetPackage ?: LastSession.load(context)?.packageName
                if (pkg == null) {
                    WidgetRenderer.refreshAll(context)
                } else {
                    val pending = goAsync()
                    SessionResumer.resume(context, pkg) { pending.finish() }
                }
            }

            // Pages the carousel. No transport command goes out - nothing about
            // playback changes, only which of several live sessions the tile is
            // looking at.
            ACTION_NEXT_SOURCE -> {
                SessionCarousel.advance(context)
                // requestSync rather than a plain repaint: the state callback
                // and the scrub ticker are both pointed at the session that
                // was on screen a moment ago, and the new one needs them.
                NotificationHookService.requestSync(context)
            }

            ACTION_REFRESH -> WidgetRenderer.refreshAll(context)
        }
    }

    /**
     * Sends a command, then repaints immediately so the icon flips under the
     * user's finger. The session will publish its own state change a moment
     * later and [NotificationHookService] will repaint again with the truth -
     * this first paint is just to remove the lag.
     */
    private fun transport(
        context: Context,
        targetPackage: String?,
        block: (android.media.session.MediaController) -> Unit
    ) {
        val controller = MediaHub.activeController(context, targetPackage)
        if (controller == null) {
            WidgetRenderer.refreshAll(context)
            return
        }
        try {
            block(controller)
        } catch (e: Exception) {
            Log.w(TAG, "Transport command rejected by ${controller.packageName}", e)
        }
        WidgetRenderer.refreshAll(context)
        NotificationHookService.requestSync(context)
    }

    companion object {
        private const val TAG = "MediaWidgetProvider"

        const val ACTION_PLAY_PAUSE = "com.dotgrid.mediawidget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.dotgrid.mediawidget.NEXT"
        const val ACTION_PREVIOUS = "com.dotgrid.mediawidget.PREVIOUS"
        const val ACTION_SEEK = "com.dotgrid.mediawidget.SEEK"
        const val ACTION_RESUME = "com.dotgrid.mediawidget.RESUME"
        const val ACTION_NEXT_SOURCE = "com.dotgrid.mediawidget.NEXT_SOURCE"
        const val ACTION_REFRESH = "com.dotgrid.mediawidget.REFRESH"

        const val EXTRA_SEEK_FRACTION = "seek_fraction"
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}
