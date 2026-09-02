package com.dotgrid.healthwidget

import android.content.Context

/**
 * Everything the user can change, in one place.
 *
 * These are **global, not per-widget instance**, even though the widget
 * declares a configuration activity. There is one body being measured: two
 * tiles on the same home screen disagreeing about what counts as sleep would
 * be a bug the user had to debug, not a feature. The settings screen therefore
 * edits the single shared set, and placing a second tile shows the values
 * already in force rather than a blank form.
 */
object HealthSettings {

    private const val PREFS = "health_widget"

    // ---- metrics ---------------------------------------------------------

    const val METRIC_STEPS = 1 shl 0
    const val METRIC_SLEEP = 1 shl 1
    const val METRIC_HEART = 1 shl 2
    const val METRIC_OXYGEN = 1 shl 3
    const val METRIC_BREATH = 1 shl 4

    /**
     * Display order on the wide tile, and the order the settings chips appear
     * in. Steps and sleep lead because they are the two a day is usually
     * summarised by; the three vitals follow in the order a clinician would
     * read them off a monitor.
     */
    val ALL_METRICS = intArrayOf(
        METRIC_STEPS, METRIC_SLEEP, METRIC_HEART, METRIC_OXYGEN, METRIC_BREATH
    )

    /**
     * Steps and sleep only. The three vitals are opt-in because each one is a
     * separate Health Connect grant, and asking for a heart rate the user
     * never wanted shown is how an app teaches someone to deny the whole
     * sheet.
     */
    private const val DEFAULT_METRICS = METRIC_STEPS or METRIC_SLEEP

    // ---- sleep interpretation --------------------------------------------

    /** Light + deep + REM + plain sleeping. Awake time inside the session is out. */
    const val SLEEP_ASLEEP = 0

    /** The whole session, start to end, awake minutes included. */
    const val SLEEP_IN_BED = 1

    /** Deep + REM: the restorative part, and the one a sleep score is built on. */
    const val SLEEP_RESTFUL = 2

    /** Deep alone. */
    const val SLEEP_DEEP = 3

    val ALL_SLEEP_MODES = intArrayOf(SLEEP_ASLEEP, SLEEP_IN_BED, SLEEP_RESTFUL, SLEEP_DEEP)

    /** Noon yesterday to noon today. */
    const val WINDOW_NIGHT = 0

    /** A rolling 24 hours back from now - catches naps and shift patterns. */
    const val WINDOW_24H = 1

    /** Local midnight to now, the same window the step count uses. */
    const val WINDOW_TODAY = 2

    val ALL_WINDOWS = intArrayOf(WINDOW_NIGHT, WINDOW_24H, WINDOW_TODAY)

    // ---- goal indicator --------------------------------------------------

    /** A dot in the corner of the tile, in the accent colour. */
    const val STYLE_DOT = 1 shl 0

    /** The filled part of the dial switches to the accent colour. */
    const val STYLE_RING = 1 shl 1

    /** The big readout switches to the accent colour. */
    const val STYLE_VALUE = 1 shl 2

    /** The tile's border switches to the accent colour. */
    const val STYLE_CARD = 1 shl 3

    val ALL_STYLES = intArrayOf(STYLE_DOT, STYLE_RING, STYLE_VALUE, STYLE_CARD)

    private const val DEFAULT_STYLES = STYLE_DOT or STYLE_RING

    const val COLOR_RED = 0
    const val COLOR_AMBER = 1
    const val COLOR_WHITE = 2

    val ALL_COLORS = intArrayOf(COLOR_RED, COLOR_AMBER, COLOR_WHITE)

    /**
     * Nothing OS is monochrome with one signal colour, so red is the honest
     * default. Amber is there for anyone who wants a step below alarm, and
     * white for anyone who wants the tile to stay strictly monochrome and
     * signal by weight instead of hue.
     */
    private const val DEFAULT_COLOR = COLOR_RED

    // ---- where a tap goes ------------------------------------------------

    const val TAP_SETTINGS = 0
    const val TAP_HEALTH_CONNECT = 1
    const val TAP_FITBIT = 2

    val ALL_TAP_TARGETS = intArrayOf(TAP_SETTINGS, TAP_HEALTH_CONNECT, TAP_FITBIT)

    // ---- goals -----------------------------------------------------------

    const val STEPS_GOAL_OFF = 0
    const val STEPS_GOAL_STEP = 500
    const val MAX_STEPS_GOAL = 100_000
    private const val DEFAULT_STEPS_GOAL = 10_000

    const val SLEEP_GOAL_OFF = 0
    const val SLEEP_GOAL_STEP_MIN = 15
    const val MAX_SLEEP_GOAL_MIN = 16 * 60
    private const val DEFAULT_SLEEP_GOAL_MIN = 8 * 60

    // ---- storage ---------------------------------------------------------

    private const val KEY_METRICS = "metrics"
    private const val KEY_PRIMARY = "primary"
    private const val KEY_SLEEP_MODE = "sleep_mode"
    private const val KEY_SLEEP_WINDOW = "sleep_window"
    private const val KEY_COUNT_UNSTAGED = "count_unstaged"
    private const val KEY_STEPS_GOAL = "steps_goal"
    private const val KEY_SLEEP_GOAL = "sleep_goal_min"
    private const val KEY_STYLES = "styles"
    private const val KEY_COLOR = "color"
    private const val KEY_TAP = "tap_target"

    /**
     * The whole set, read in one go.
     *
     * The tile repaints from a background thread and the settings screen holds
     * a working copy it writes straight through, so both want every value at
     * once rather than ten separate lookups against the same file.
     */
    data class Prefs(
        val metrics: Int,
        val primary: Int,
        val sleepMode: Int,
        val sleepWindow: Int,
        val countUnstaged: Boolean,
        val stepsGoal: Int,
        val sleepGoalMinutes: Int,
        val styles: Int,
        val colorChoice: Int,
        val tapTarget: Int
    ) {
        fun shows(metric: Int): Boolean = metrics and metric != 0

        fun styled(style: Int): Boolean = styles and style != 0

        /** Enabled metrics, in display order. */
        fun enabledMetrics(): List<Int> = ALL_METRICS.filter { shows(it) }

        /**
         * The metric the dial shows. Falls back to the first enabled one when
         * the chosen metric has since been turned off - a dial with nothing in
         * it would otherwise be the only trace of that.
         */
        fun dialMetric(): Int =
            if (shows(primary)) primary else enabledMetrics().firstOrNull() ?: METRIC_STEPS
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Prefs {
        val p = prefs(context)
        return Prefs(
            // Zero metrics is a blank tile with no way back to this screen
            // except the launcher icon, so an empty set falls back to the
            // defaults rather than being honoured.
            metrics = p.getInt(KEY_METRICS, DEFAULT_METRICS).takeIf { it != 0 } ?: DEFAULT_METRICS,
            primary = p.getInt(KEY_PRIMARY, METRIC_STEPS),
            sleepMode = p.getInt(KEY_SLEEP_MODE, SLEEP_ASLEEP),
            sleepWindow = p.getInt(KEY_SLEEP_WINDOW, WINDOW_NIGHT),
            countUnstaged = p.getBoolean(KEY_COUNT_UNSTAGED, true),
            stepsGoal = p.getInt(KEY_STEPS_GOAL, DEFAULT_STEPS_GOAL)
                .coerceIn(STEPS_GOAL_OFF, MAX_STEPS_GOAL),
            sleepGoalMinutes = p.getInt(KEY_SLEEP_GOAL, DEFAULT_SLEEP_GOAL_MIN)
                .coerceIn(SLEEP_GOAL_OFF, MAX_SLEEP_GOAL_MIN),
            styles = p.getInt(KEY_STYLES, DEFAULT_STYLES),
            colorChoice = p.getInt(KEY_COLOR, DEFAULT_COLOR),
            tapTarget = p.getInt(KEY_TAP, TAP_SETTINGS)
        )
    }

    fun save(context: Context, value: Prefs) {
        prefs(context).edit()
            .putInt(KEY_METRICS, value.metrics)
            .putInt(KEY_PRIMARY, value.primary)
            .putInt(KEY_SLEEP_MODE, value.sleepMode)
            .putInt(KEY_SLEEP_WINDOW, value.sleepWindow)
            .putBoolean(KEY_COUNT_UNSTAGED, value.countUnstaged)
            .putInt(KEY_STEPS_GOAL, value.stepsGoal.coerceIn(STEPS_GOAL_OFF, MAX_STEPS_GOAL))
            .putInt(KEY_SLEEP_GOAL, value.sleepGoalMinutes.coerceIn(SLEEP_GOAL_OFF, MAX_SLEEP_GOAL_MIN))
            .putInt(KEY_STYLES, value.styles)
            .putInt(KEY_COLOR, value.colorChoice)
            .putInt(KEY_TAP, value.tapTarget)
            .apply()
    }

    /**
     * Resolves an accent choice to a colour, without going near the stored one.
     *
     * COLOR_WHITE resolves through `text_primary`, not a literal `nt_white`:
     * "white" here means "no colour, just the tile's own ink", and that ink is
     * white on a dark tile but black on a light one. A hardcoded white
     * goal-met dot/border painted over a light widget_surface would vanish -
     * resolving through the split resource keeps it visible in both themes
     * the same way every other text on the tile already is.
     */
    fun colorFor(context: Context, choice: Int): Int = context.getColor(
        when (choice) {
            COLOR_AMBER -> R.color.nt_amber
            COLOR_WHITE -> R.color.text_primary
            else -> R.color.nt_red
        }
    )
}
