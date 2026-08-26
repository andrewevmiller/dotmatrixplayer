package com.dotgrid.mediawidget

import android.graphics.Bitmap

/**
 * Everything the widget needs to draw one frame, resolved at a single instant.
 *
 * The renderer never touches a MediaController directly. Sessions appear and
 * vanish between the moment a broadcast arrives and the moment RemoteViews are
 * built, and a half-updated widget looks broken in a way a stale one does not.
 */
data class PlaybackSnapshot(
    /** False until the user grants notification access; the widget then prompts for it. */
    val hasAccess: Boolean,
    /** True when some app currently owns a media session we can read. */
    val hasSession: Boolean,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val artwork: Bitmap?,
    /** Identity for the current art, so it is only re-processed when it actually changes. */
    val artworkKey: String,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val canSeek: Boolean,
    /** Owner of the session, used to route commands and to open the app on tap. */
    val packageName: String?,
    /**
     * True when this frame was rebuilt from [LastSession] rather than read from
     * a live session: the app has gone, but we know what it was playing and can
     * offer to start it again. Mutually exclusive with [hasSession].
     */
    val resumable: Boolean = false,
    /**
     * How many apps hold a session the widget could show, and which of them
     * this frame is. Anything below two means there is nowhere to page to and
     * the carousel affordance stays hidden entirely - a control that cannot
     * do anything is worse than no control.
     */
    val sourceCount: Int = 0,
    val sourceIndex: Int = 0
) {
    /** Null when the duration is unknown - live streams, or metadata that never arrived. */
    val fraction: Float?
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    companion object {
        fun noAccess() = PlaybackSnapshot(
            hasAccess = false,
            hasSession = false,
            title = "",
            artist = "",
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            artwork = null,
            artworkKey = "none",
            canSkipNext = false,
            canSkipPrevious = false,
            canSeek = false,
            packageName = null
        )

        fun idle() = noAccess().copy(hasAccess = true)

        /**
         * The last thing that played, with the app behind it now gone. Carries
         * real metadata so the tile stays recognisable, but no session - so
         * nothing that needs one (skip, scrub) is offered.
         */
        fun resuming(cached: LastSession.Cached) = PlaybackSnapshot(
            hasAccess = true,
            hasSession = false,
            title = cached.title,
            artist = cached.artist,
            isPlaying = false,
            positionMs = cached.positionMs,
            durationMs = cached.durationMs,
            artwork = cached.artwork,
            artworkKey = cached.identity,
            canSkipNext = false,
            canSkipPrevious = false,
            canSeek = false,
            packageName = cached.packageName,
            resumable = true
        )
    }
}
