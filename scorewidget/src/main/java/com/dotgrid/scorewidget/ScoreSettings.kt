package com.dotgrid.scorewidget

import android.content.Context

/**
 * Everything the settings menu can change.
 *
 * **Global, not per-tile**, for the same reason the sibling data widget keeps
 * its plan settings global: the alternative is two tiles on one home screen
 * disagreeing about who the user supports, which is a bug they would have to
 * debug rather than a feature. A 2x1 strip beside a 4x2 card should be the same
 * teams shown at two levels of detail.
 *
 * The one exception is the carousel position, which is per-tile by definition -
 * see [carouselIndex]. Two tiles showing different games is the point of having
 * two tiles.
 */
object ScoreSettings {

    private const val PREFS = "score_widget"

    private const val KEY_FAVORITES = "favorites"
    private const val KEY_FILTER_OFFSEASON = "filter_offseason"
    private const val KEY_RIVALRIES = "rivalries"
    private const val KEY_SHOW_WIN_PROBABILITY = "show_win_probability"
    private const val KEY_ACCENT = "accent"
    private const val KEY_ALERTS = "alerts"
    private const val KEY_CAROUSEL_PREFIX = "carousel_"

    /** Kickoff, first pitch, puck drop. */
    const val ALERT_START = 1 shl 0

    /** One score, last tenth of the game. See [WinProbability.isCloseFinish]. */
    const val ALERT_CLOSE = 1 shl 1

    /** The final whistle. */
    const val ALERT_FINAL = 1 shl 2

    val ALL_ALERTS = intArrayOf(ALERT_START, ALERT_CLOSE, ALERT_FINAL)

    const val ACCENT_RED = 0
    const val ACCENT_AMBER = 1
    const val ACCENT_WHITE = 2

    /**
     * Red, matching the two siblings. Nothing OS is monochrome with one signal
     * colour, and on this tile the signal is a live game - the one state where
     * the number on screen is changing while you look at it.
     */
    private const val DEFAULT_ACCENT = ACCENT_RED

    /**
     * How many games the carousel will hold.
     *
     * Five, because the carousel is advanced by tapping and the cost of
     * overshooting is a full lap. Five taps to get back where you started is
     * already at the edge of tolerable; a Saturday in autumn would otherwise
     * put forty college games behind one arrow.
     */
    const val MAX_CARDS = 5

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The favourites, **in priority order**. First is the team whose game gets
     * the tile when several are on at once.
     *
     * Stored as one newline-joined string rather than a `StringSet`, because
     * `StringSet` does not preserve order and the order is the entire feature -
     * it is the priority queue the settings menu lets the user drag into shape.
     */
    fun favorites(context: Context): List<String> =
        prefs(context).getString(KEY_FAVORITES, "")
            .orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }

    fun setFavorites(context: Context, keys: List<String>) {
        prefs(context).edit()
            .putString(KEY_FAVORITES, keys.distinct().joinToString("\n"))
            .apply()
    }

    /** The leagues any favourite belongs to - what the fetcher actually needs to ask for. */
    fun activeLeagues(context: Context): Set<League> =
        favorites(context).mapNotNull { League.byCode(it.substringBefore("/")) }.toSet()

    fun filterOffseason(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTER_OFFSEASON, true)

    fun setFilterOffseason(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FILTER_OFFSEASON, value).apply()
    }

    fun rivalries(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RIVALRIES, true)

    fun setRivalries(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RIVALRIES, value).apply()
    }

    fun showWinProbability(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_WIN_PROBABILITY, true)

    fun setShowWinProbability(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_WIN_PROBABILITY, value).apply()
    }

    fun accentChoice(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT, DEFAULT_ACCENT)

    fun setAccentChoice(context: Context, choice: Int) {
        prefs(context).edit().putInt(KEY_ACCENT, choice).apply()
    }

    /**
     * Resolves an accent choice to a colour without going near the stored one.
     *
     * ACCENT_WHITE resolves through `text_primary`, not a literal `nt_white`:
     * "white" here means "no colour, just the tile's own ink", and that ink is
     * white on a dark tile but black on a light one. A hardcoded white live dot
     * painted over a light widget_surface would vanish - resolving through the
     * split resource keeps it visible in both themes the same way every other
     * text on the tile already is.
     */
    fun colorFor(context: Context, choice: Int): Int = context.getColor(
        when (choice) {
            ACCENT_AMBER -> R.color.nt_amber
            ACCENT_WHITE -> R.color.text_primary
            else -> R.color.nt_red
        }
    )

    fun accentColor(context: Context): Int = colorFor(context, accentChoice(context))

    /**
     * Which alerts are armed, as a bitmask.
     *
     * Empty by default. The manifest does not hold POST_NOTIFICATIONS, and the
     * permission is requested at the moment the first of these is switched on -
     * so the default has to be a state in which nothing has been promised.
     */
    fun alerts(context: Context): Int = prefs(context).getInt(KEY_ALERTS, 0)

    fun setAlerts(context: Context, mask: Int) {
        prefs(context).edit().putInt(KEY_ALERTS, mask).apply()
    }

    fun hasAlert(mask: Int, alert: Int): Boolean = mask and alert != 0

    /**
     * Which card this particular tile is showing.
     *
     * Per-widget-id, and the one setting that is. It is also stored rather than
     * held in memory: the process this widget lives in is started by a
     * broadcast and killed again shortly after, so an in-memory index would
     * reset every time the user stopped tapping for a minute.
     *
     * Not clamped on read. The number of games behind the carousel changes
     * under it - a game ends, a team's rival comes on - and clamping here would
     * need the count, which the caller has and this does not. [TeamFilter.pick]
     * does the modulo.
     */
    fun carouselIndex(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(KEY_CAROUSEL_PREFIX + appWidgetId, 0)

    fun setCarouselIndex(context: Context, appWidgetId: Int, index: Int) {
        prefs(context).edit().putInt(KEY_CAROUSEL_PREFIX + appWidgetId, index).apply()
    }

    /** Tidies up after a tile that has been removed from the home screen. */
    fun forgetWidget(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(KEY_CAROUSEL_PREFIX + appWidgetId).apply()
    }
}
