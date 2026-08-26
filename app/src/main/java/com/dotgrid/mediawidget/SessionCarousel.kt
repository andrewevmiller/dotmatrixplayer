package com.dotgrid.mediawidget

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState

/**
 * Which of several concurrent sessions the widget is currently showing.
 *
 * More than one app can hold a media session at once - a podcast paused
 * mid-episode while music plays, a video app that never let go of its
 * session - and the tile has room for exactly one. Left alone,
 * [MediaHub.activeController] resolves that on the user's behalf; this lets
 * them override it and page through the rest.
 *
 * A widget cannot be swiped. RemoteViews supports no gesture beyond a click,
 * so paging is a tap on the source mark rather than a horizontal drag, and
 * the dots beside it exist to say that there is anywhere to page *to*.
 */
object SessionCarousel {

    private const val PREFS = "session_carousel"
    private const val KEY_PACKAGE = "pinned_package"
    private const val KEY_PINNED_AT = "pinned_at"

    /**
     * How long a manual choice outranks the automatic one.
     *
     * Not indefinite: a pin that never expired would mean paging to a podcast
     * once quietly breaks the widget's main job for every song afterwards.
     * Not momentary either - a pin the user is actively listening to renews
     * itself (see [selected]), so this only counts down on a session they
     * paged to and then stopped caring about.
     */
    private const val PIN_TTL_MS = 2 * 60 * 1000L

    /**
     * The sessions available to page through, in a stable order.
     *
     * Never the platform's own order, which is by recency and reshuffles
     * underneath us as apps start and stop - that would make "next" land
     * somewhere different each time it is pressed, and a ring has to keep its
     * order to be a ring at all. [SourceOrder] supplies the user's arrangement,
     * falling back to alphabetical when they have not set one.
     */
    fun sessions(context: Context): List<MediaController> = with(MediaHub) {
        val titled = activeControllers(context).filter { it.hasTitle() }
        // This is the one place every session the ring can contain passes
        // through, so it is where the settings list learns what exists.
        SourceOrder.remember(context, titled.map { it.packageName })
        SourceOrder.sort(context, titled)
    }

    /**
     * The pinned package, or null when the pin has expired, was never set, or
     * points at a session that has since gone away.
     *
     * Renews the pin whenever the pinned session is the one actually playing:
     * the user chose it and is listening to it, so the clock should not be
     * running down on that choice.
     */
    fun selected(context: Context, available: List<MediaController>): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pinned = prefs.getString(KEY_PACKAGE, null) ?: return null

        val controller = available.firstOrNull { it.packageName == pinned }
        if (controller == null) {
            // The app we were pinned to is gone; stop holding a slot for it.
            clear(context)
            return null
        }

        if (controller.isPlaying()) {
            prefs.edit().putLong(KEY_PINNED_AT, System.currentTimeMillis()).apply()
            return pinned
        }

        val age = System.currentTimeMillis() - prefs.getLong(KEY_PINNED_AT, 0L)
        if (age > PIN_TTL_MS) {
            clear(context)
            return null
        }
        return pinned
    }

    /**
     * Moves to the next source in the ring and pins it.
     *
     * @return the package now shown, or null when there was nothing to page to.
     */
    fun advance(context: Context): String? {
        val available = sessions(context)
        if (available.size < 2) return null

        // Where the ring currently is: the pin if there is one, otherwise
        // whatever the automatic resolution settled on - so the first press
        // moves off what is on screen rather than jumping to the top.
        val current = selected(context, available)
            ?: MediaHub.activeController(context)?.packageName

        val index = available.indexOfFirst { it.packageName == current }
        val next = available[(index + 1).mod(available.size)]

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, next.packageName)
            .putLong(KEY_PINNED_AT, System.currentTimeMillis())
            .apply()

        return next.packageName
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun MediaController.isPlaying(): Boolean =
        playbackState?.state == PlaybackState.STATE_PLAYING
}
