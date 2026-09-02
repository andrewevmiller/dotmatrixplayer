package com.dotgrid.healthwidget

import android.content.Context
import java.util.Locale

/**
 * What each metric is called, what glyph it wears, how its figure is set, and
 * what its rail fills against.
 *
 * One place, because the same five answers are needed by the dial, by the rows
 * and by the live preview on the settings screen, and a metric that reads
 * "8,432 STEPS" on the tile and "8432" in the preview is a bug nobody notices
 * until the screenshot.
 */
object Metrics {

    /**
     * A reading, or the reason there isn't one.
     *
     * @param value the raw figure in the metric's own unit - steps, minutes,
     *   bpm, percent, breaths per minute - or null when there is nothing to
     *   show. Null and "granted but no data today" are the same state on the
     *   tile: both draw a dash.
     * @param granted whether Health Connect has given us this particular read.
     *   Separate from [value] because the tile says something different for
     *   each: a missing grant is the user's to fix, a missing figure is not.
     */
    data class Reading(val value: Double?, val granted: Boolean)

    fun label(context: Context, metric: Int): String = context.getString(
        when (metric) {
            HealthSettings.METRIC_SLEEP -> R.string.healthwidget_metric_sleep
            HealthSettings.METRIC_HEART -> R.string.healthwidget_metric_heart
            HealthSettings.METRIC_OXYGEN -> R.string.healthwidget_metric_oxygen
            HealthSettings.METRIC_BREATH -> R.string.healthwidget_metric_breath
            else -> R.string.healthwidget_metric_steps
        }
    )

    fun icon(metric: Int): Int = when (metric) {
        HealthSettings.METRIC_SLEEP -> R.drawable.ic_sleep
        HealthSettings.METRIC_HEART -> R.drawable.ic_heart
        HealthSettings.METRIC_OXYGEN -> R.drawable.ic_oxygen
        HealthSettings.METRIC_BREATH -> R.drawable.ic_breath
        else -> R.drawable.ic_steps
    }

    /**
     * The figure and its unit, set apart so the dial can size them differently
     * on a shared baseline.
     *
     * Locale.US for the digits, not the device locale. These are set in
     * Geist (a data readout - see Typography), which carries Latin digits and
     * nothing else - a locale that renders numbers in Arabic-Indic or
     * Devanagari digits would push them out to a fallback face, and the type
     * is half of what this tile is.
     */
    fun format(context: Context, metric: Int, reading: Reading): Pair<String, String> {
        val value = reading.value
            ?: return context.getString(R.string.healthwidget_value_unknown) to ""

        return when (metric) {
            // "7H 12M", or "48M" for a nap - an hours figure reading 0 is a
            // worse answer than no hours figure at all.
            HealthSettings.METRIC_SLEEP -> {
                val minutes = value.toLong().coerceAtLeast(0L)
                val text = if (minutes >= 60) {
                    String.format(Locale.US, "%dH %02dM", minutes / 60, minutes % 60)
                } else {
                    String.format(Locale.US, "%dM", minutes)
                }
                text to ""
            }

            HealthSettings.METRIC_HEART ->
                String.format(Locale.US, "%d", value.toLong()) to
                    context.getString(R.string.healthwidget_unit_heart)

            // One decimal. SpO2 moves over about four points of useful range,
            // so a whole number throws away most of what there is to see.
            HealthSettings.METRIC_OXYGEN ->
                String.format(Locale.US, "%.1f", value) to
                    context.getString(R.string.healthwidget_unit_oxygen)

            HealthSettings.METRIC_BREATH ->
                String.format(Locale.US, "%.1f", value) to
                    context.getString(R.string.healthwidget_unit_breath)

            // Grouped: five digits of steps unbroken is a figure you have to
            // count rather than read.
            else ->
                String.format(Locale.US, "%,d", value.toLong()) to
                    context.getString(R.string.healthwidget_unit_steps)
        }
    }

    /**
     * Where the dial or the rail should fill to, or null for an inert rail.
     *
     * Steps and sleep fill against the user's own goal, and turning that goal
     * off makes them inert - a tile measuring you against a number you did not
     * choose is worse than one that just counts.
     *
     * The three vitals have no goal to set. They fill against the span of
     * ordinary adult resting values instead, so the rail reads as a position
     * on a scale rather than as progress towards something: 40-180 bpm,
     * 90-100% oxygen, 8-24 breaths a minute. These are display bands, chosen
     * so a normal reading sits mid-rail and an unusual one visibly does not.
     * Nothing here is a diagnosis and the tile does not colour them.
     */
    fun fraction(metric: Int, reading: Reading, prefs: HealthSettings.Prefs): Float? {
        val value = reading.value ?: return null
        return when (metric) {
            HealthSettings.METRIC_SLEEP ->
                if (prefs.sleepGoalMinutes <= 0) null
                else (value / prefs.sleepGoalMinutes).toFloat()

            HealthSettings.METRIC_HEART -> band(value, 40.0, 180.0)
            HealthSettings.METRIC_OXYGEN -> band(value, 90.0, 100.0)
            HealthSettings.METRIC_BREATH -> band(value, 8.0, 24.0)

            else ->
                if (prefs.stepsGoal <= 0) null
                else (value / prefs.stepsGoal).toFloat()
        }
    }

    /** True once the metric has reached the goal it fills against. */
    fun goalMet(metric: Int, reading: Reading, prefs: HealthSettings.Prefs): Boolean {
        val value = reading.value ?: return false
        return when (metric) {
            HealthSettings.METRIC_SLEEP ->
                prefs.sleepGoalMinutes > 0 && value >= prefs.sleepGoalMinutes

            HealthSettings.METRIC_STEPS ->
                prefs.stepsGoal > 0 && value >= prefs.stepsGoal

            // A vital has no goal to meet. Reaching the top of a display band
            // is not an achievement and the accent would read as one.
            else -> false
        }
    }

    /**
     * The line under the dial's figure: what it is filling towards, in the
     * same words the settings screen used to set it.
     */
    fun goalLine(context: Context, metric: Int, prefs: HealthSettings.Prefs): String? =
        when (metric) {
            HealthSettings.METRIC_SLEEP -> prefs.sleepGoalMinutes
                .takeIf { it > 0 }
                ?.let {
                    context.getString(
                        R.string.healthwidget_goal_format,
                        String.format(Locale.US, "%dH %02dM", it / 60, it % 60)
                    )
                }

            HealthSettings.METRIC_STEPS -> prefs.stepsGoal
                .takeIf { it > 0 }
                ?.let {
                    context.getString(R.string.healthwidget_goal_format, String.format(Locale.US, "%,d", it))
                }

            else -> null
        }

    private fun band(value: Double, low: Double, high: Double): Float =
        ((value - low) / (high - low)).toFloat().coerceIn(0f, 1f)
}
