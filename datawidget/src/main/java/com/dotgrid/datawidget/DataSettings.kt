package com.dotgrid.datawidget

import android.content.Context

/**
 * Everything the user can change, in one place.
 *
 * These are **global, not per-widget instance**, even though the widget declares
 * a configuration activity. A phone has one data plan: two tiles on the same
 * home screen disagreeing about when the month rolls over would be a bug the
 * user had to debug, not a feature. The configuration activity therefore edits
 * the single shared set, and placing a second tile shows the values already in
 * force rather than a blank form.
 */
object DataSettings {

    private const val PREFS = "data_widget"

    private const val KEY_CYCLE_DAY = "cycle_day"
    private const val KEY_LIMIT_MB = "limit_mb"
    private const val KEY_ALERT_STYLES = "alert_styles"
    private const val KEY_ALERT_PERCENT = "alert_percent"
    private const val KEY_ALERT_COLOR = "alert_color"
    private const val KEY_LAYOUT_STYLE = "layout_style"

    /** The radial arc of dots this tile has always drawn. */
    const val LAYOUT_GAUGE = 0

    /** The tall dot-matrix pillar: no readout text, fills bottom to top, and
     *  turns its whole card the alert colour on trip rather than tinting a
     *  ring or a border. */
    const val LAYOUT_PILLAR = 1

    private const val DEFAULT_LAYOUT = LAYOUT_GAUGE

    /** A dot in the corner of the tile, in the alert colour. */
    const val STYLE_DOT = 1 shl 0

    /** The filled part of the meter switches to the alert colour. */
    const val STYLE_RING = 1 shl 1

    /** The big readout switches to the alert colour. */
    const val STYLE_VALUE = 1 shl 2

    /** The tile's border switches to the alert colour. */
    const val STYLE_CARD = 1 shl 3

    val ALL_STYLES = intArrayOf(STYLE_DOT, STYLE_RING, STYLE_VALUE, STYLE_CARD)

    const val COLOR_RED = 0
    const val COLOR_AMBER = 1
    const val COLOR_WHITE = 2

    /**
     * Nothing OS is monochrome with one signal colour, so red is the honest
     * default. Amber is there for anyone who wants a step below alarm, and
     * white for anyone who wants the tile to stay strictly monochrome and
     * signal by weight instead of hue.
     */
    private const val DEFAULT_COLOR = COLOR_RED

    /** Most plans roll over on the 1st; the ones that do not are usually the billing date. */
    private const val DEFAULT_CYCLE_DAY = 1

    private const val DEFAULT_LIMIT_MB = 20_000

    /** Trip on the limit itself, not before it - an early warning is opt-in. */
    private const val DEFAULT_PERCENT = 100

    private const val DEFAULT_STYLES = STYLE_DOT or STYLE_RING

    const val MIN_PERCENT = 25
    const val MAX_PERCENT = 200
    const val PERCENT_STEP = 5

    /** 0 means no limit: the meter goes inert rather than measuring against a guess. */
    const val LIMIT_OFF_MB = 0
    const val MAX_LIMIT_MB = 2_000_000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Day of the month the allowance resets, 1..31. */
    fun cycleDay(context: Context): Int =
        prefs(context).getInt(KEY_CYCLE_DAY, DEFAULT_CYCLE_DAY).coerceIn(1, 31)

    fun limitMb(context: Context): Int =
        prefs(context).getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB).coerceIn(LIMIT_OFF_MB, MAX_LIMIT_MB)

    fun alertStyles(context: Context): Int =
        prefs(context).getInt(KEY_ALERT_STYLES, DEFAULT_STYLES)

    fun alertPercent(context: Context): Int =
        prefs(context).getInt(KEY_ALERT_PERCENT, DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun alertColorChoice(context: Context): Int =
        prefs(context).getInt(KEY_ALERT_COLOR, DEFAULT_COLOR)

    fun layoutStyle(context: Context): Int =
        prefs(context).getInt(KEY_LAYOUT_STYLE, DEFAULT_LAYOUT)

    fun save(
        context: Context,
        cycleDay: Int,
        limitMb: Int,
        alertStyles: Int,
        alertPercent: Int,
        alertColor: Int,
        layoutStyle: Int
    ) {
        prefs(context).edit()
            .putInt(KEY_CYCLE_DAY, cycleDay.coerceIn(1, 31))
            .putInt(KEY_LIMIT_MB, limitMb.coerceIn(LIMIT_OFF_MB, MAX_LIMIT_MB))
            .putInt(KEY_ALERT_STYLES, alertStyles)
            .putInt(KEY_ALERT_PERCENT, alertPercent.coerceIn(MIN_PERCENT, MAX_PERCENT))
            .putInt(KEY_ALERT_COLOR, alertColor)
            .putInt(KEY_LAYOUT_STYLE, layoutStyle)
            .apply()
    }

    /**
     * Resolves an accent choice to a colour, without going near the stored one.
     *
     * COLOR_WHITE resolves through `text_primary`, not a literal `nt_white`:
     * "white" here means "no colour, just the tile's own ink", and that ink is
     * white on a dark tile but black on a light one. A hardcoded white alert
     * dot/border painted over a light widget_surface would vanish - resolving
     * through the split resource keeps it visible in both themes the same way
     * every other text on the tile already is.
     */
    fun colorFor(context: Context, choice: Int): Int = context.getColor(
        when (choice) {
            COLOR_AMBER -> R.color.nt_amber
            COLOR_WHITE -> R.color.text_primary
            else -> R.color.nt_red
        }
    )

    /** Resolves the chosen accent to an actual colour. */
    fun alertColor(context: Context): Int = colorFor(context, alertColorChoice(context))

    fun hasStyle(styles: Int, style: Int): Boolean = styles and style != 0
}
