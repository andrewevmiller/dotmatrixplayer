package com.dotgrid.mediawidget

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Finds the media session the user most likely means, and reads it.
 *
 * Everything here is best-effort. Media apps are wildly inconsistent about
 * which metadata keys they populate and which transport actions they advertise,
 * so every lookup has a fallback and nothing throws on a missing field.
 */
object MediaHub {

    private const val TAG = "MediaHub"

    /**
     * MediaSessionManager will only hand out active sessions to a *enabled*
     * NotificationListenerService, which is why the app asks for that
     * permission and nothing else.
     */
    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val us = ComponentName(context, NotificationHookService::class.java)
        return enabled.split(':').any { entry ->
            val cn = ComponentName.unflattenFromString(entry)
            cn != null && cn.packageName == us.packageName && cn.className == us.className
        }
    }

    fun activeControllers(context: Context): List<MediaController> {
        if (!hasNotificationAccess(context)) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        val component = ComponentName(context, NotificationHookService::class.java)
        return try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            // Access can be revoked between the check above and this call.
            Log.w(TAG, "Notification access refused while listing sessions", e)
            emptyList()
        }
    }

    /**
     * Picks the session to show. A session that is actually playing always wins;
     * otherwise the platform already sorts by priority, so index 0 is the last
     * thing the user touched.
     *
     * [preferPackage] lets a command that was queued against a specific app
     * still reach that app if a second session started in the meantime.
     */
    fun activeController(context: Context, preferPackage: String? = null): MediaController? {
        val controllers = activeControllers(context)
        if (controllers.isEmpty()) return null

        if (preferPackage != null) {
            controllers.firstOrNull { it.packageName == preferPackage }?.let { return it }
        }

        // A source the user paged to outranks everything below, including a
        // session that is playing: they went out of their way to say which of
        // several concurrent sessions the tile should be showing, and the
        // widget quietly overriding that would make the control look broken.
        // The pin expires on its own - see SessionCarousel.
        SessionCarousel.selected(context, controllers.filter { it.hasTitle() })
            ?.let { pinned ->
                controllers.firstOrNull { it.packageName == pinned }?.let { return it }
            }

        // A session that is playing wins outright, titled or not - a live radio
        // stream often carries no metadata and is still the thing on screen.
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?.let { return it }

        // Nothing is playing. Before falling back to the platform's own
        // priority order, give the user's own pick a chance - a second app
        // (a podcast player, say) can keep a titled session open and would
        // otherwise bump the app the user actually wants the widget to
        // remember. Still requires a title: a pinned app with an empty
        // session is exactly the case this whole filter exists to skip.
        IdlePreference.get(context)?.let { preferred ->
            controllers.firstOrNull { it.packageName == preferred && it.hasTitle() }
                ?.let { return it }
        }

        // No preference, or the preferred app has nothing to show - fall back
        // to the platform's own priority order, but only over sessions that
        // actually describe something. System components hold empty sessions
        // open indefinitely (com.nothing.hearthstone is one), and letting one
        // of those win means the tile claims to be showing a track while
        // every metadata field is blank.
        val chosen = controllers.firstOrNull { it.hasTitle() }
        if (chosen == null && controllers.isNotEmpty()) {
            Log.i(TAG, "Ignoring ${controllers.size} untitled session(s): " +
                controllers.joinToString { it.packageName })
        }
        return chosen
    }

    /** Whether this session describes a track at all, rather than merely existing. */
    internal fun MediaController.hasTitle(): Boolean = metadata.string(
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE
    ) != null

    /**
     * Reads the current frame.
     *
     * Deliberately not side-effect free: this is the one place that observes a
     * live session, so it is also where [LastSession] is kept current. Doing it
     * in the callers instead would mean four of them to keep in step, and a
     * cache that silently goes stale the day one of them is forgotten.
     */
    fun snapshot(context: Context): PlaybackSnapshot {
        if (!hasNotificationAccess(context)) return PlaybackSnapshot.noAccess()
        val controller = activeController(context)
            ?: return LastSession.load(context)
                ?.let { PlaybackSnapshot.resuming(it) }
                ?: PlaybackSnapshot.idle()

        val metadata = controller.metadata
        val state = controller.playbackState

        // Only a *playing* session can reach here without a title, and the best
        // name for it is the app's own. Never the package id: that is an
        // implementation detail, and it is what leaks when the label lookup is
        // blocked by package visibility.
        val title = metadata.string(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE
        )
            ?: appLabel(context, controller.packageName)
            ?: context.getString(R.string.unknown_track)

        val artist = metadata.string(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_ALBUM
        ) ?: appLabel(context, controller.packageName).orEmpty()

        // Both the embedded-bitmap and the URI keys - see ArtworkTools. Apps
        // split roughly evenly between the two, and reading only the first set
        // leaves half of them with a blank panel.
        val artwork = ArtworkTools.fromMetadata(context, metadata)

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val actions = state?.actions ?: 0L

        // Where this session sits in the ring the user can page through. Read
        // from the same ordering SessionCarousel pages by, so the dots and the
        // tap agree about what "next" means.
        val ring = SessionCarousel.sessions(context)
        val ringIndex = ring.indexOfFirst { it.packageName == controller.packageName }

        val snapshot = PlaybackSnapshot(
            hasAccess = true,
            hasSession = true,
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            positionMs = extrapolatedPosition(state, duration),
            durationMs = duration.coerceAtLeast(0L),
            artwork = artwork,
            artworkKey = buildString {
                append(controller.packageName)
                append('|')
                append(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: title)
                append('|')
                append(artwork?.let { "${it.width}x${it.height}@${it.generationId}" } ?: "noart")
            },
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L && duration > 0L,
            packageName = controller.packageName,
            sourceCount = ring.size,
            // A playing-but-untitled session is shown yet is not in the ring;
            // report it as position zero rather than -1, which would draw the
            // indicator with nothing lit.
            sourceIndex = ringIndex.coerceAtLeast(0)
        )
        LastSession.save(context, snapshot)
        return snapshot
    }

    /**
     * PlaybackState.position is a reading taken at lastPositionUpdateTime, not
     * a live value. While playing, the real position is that reading plus the
     * time since, scaled by the playback speed - otherwise the bar would only
     * move when the app happened to publish a new state.
     */
    private fun extrapolatedPosition(state: PlaybackState?, durationMs: Long): Long {
        if (state == null) return 0L
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val since = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            if (since > 0) {
                val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
                position += (since * speed).toLong()
            }
        }
        return if (durationMs > 0L) position.coerceIn(0L, durationMs) else position.coerceAtLeast(0L)
    }

    /** First non-blank value among [keys], or null. */
    private fun MediaMetadata?.string(vararg keys: String): String? {
        val metadata = this ?: return null
        for (key in keys) {
            val value = metadata.getString(key)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /**
     * Null rather than the package id on failure. Returning the id looks like a
     * harmless fallback and is not: package-visibility filtering makes the
     * lookup fail routinely, so the "fallback" is what the user actually ends up
     * reading, and it reads as a bug.
     */
    private fun appLabel(context: Context, packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            .takeIf { it.isNotBlank() && it != packageName }
    } catch (e: Exception) {
        null
    }
}
