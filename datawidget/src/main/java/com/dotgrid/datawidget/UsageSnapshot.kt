package com.dotgrid.datawidget

import android.content.Context
import java.util.Locale

/**
 * One reading of the plan: what the tile paints, with no framework types left in
 * it. Built in the app process, consumed by the renderer, and easy to fake for
 * the configuration screen's preview.
 */
data class UsageSnapshot(
    val bytes: Long?,
    val limitMb: Int,
    val daysLeft: Int,
    val cycleDay: Int,
    val hasAccess: Boolean,
    val alertStyles: Int,
    val alertPercent: Int,
    val alertColor: Int
) {

    val hasLimit: Boolean get() = limitMb > DataSettings.LIMIT_OFF_MB

    /**
     * Usage as a fraction of the allowance, uncapped - 1.2 means twenty percent
     * over. Null when there is no limit to measure against, or nothing to
     * measure: the meter renders inert rather than inventing a scale.
     */
    val fraction: Float?
        get() {
            val used = bytes ?: return null
            if (!hasLimit) return null
            return used.toFloat() / (limitMb.toLong() * BYTES_PER_MB).toFloat()
        }

    /** True once usage crosses the user's chosen share of the limit. */
    val overLimit: Boolean
        get() {
            val used = fraction ?: return false
            return used >= alertPercent / 100f
        }

    fun styled(style: Int): Boolean =
        overLimit && DataSettings.hasStyle(alertStyles, style)

    companion object {

        /**
         * Decimal, not binary. Carriers sell and bill a "GB" of 1,000,000,000
         * bytes, and Android's own Settings has counted data this way since
         * Marshmallow. Matching the bill matters more here than matching RAM.
         */
        const val BYTES_PER_MB = 1_000_000L
        const val BYTES_PER_GB = 1_000_000_000L

        fun read(context: Context): UsageSnapshot {
            val cycleDay = DataSettings.cycleDay(context)
            val cycle = CycleMath.current(System.currentTimeMillis(), cycleDay)
            val hasAccess = MobileData.hasUsageAccess(context)

            return UsageSnapshot(
                bytes = if (hasAccess) {
                    MobileData.bytesInWindow(context, cycle.startMillis, cycle.endMillis)
                } else {
                    null
                },
                limitMb = DataSettings.limitMb(context),
                daysLeft = cycle.daysLeft,
                cycleDay = cycleDay,
                hasAccess = hasAccess,
                alertStyles = DataSettings.alertStyles(context),
                alertPercent = DataSettings.alertPercent(context),
                alertColor = DataSettings.alertColor(context)
            )
        }

        /**
         * The readout, split so the unit can be set in its own size beside the
         * number instead of riding along at 26sp.
         *
         * The number is held to four glyphs at most. A 2 x 2 tile is 110dp
         * across and the digits have to stay large enough to read at arm's
         * length, so "1.4" / "12.4" / "124" / "1024" all fit and nothing longer
         * is allowed to appear.
         */
        fun format(bytes: Long): Pair<String, String> {
            if (bytes < BYTES_PER_GB) {
                val mb = bytes.toDouble() / BYTES_PER_MB
                return String.format(Locale.US, "%.0f", mb) to "MB"
            }
            val gb = bytes.toDouble() / BYTES_PER_GB
            val text = if (gb >= 100) {
                String.format(Locale.US, "%.0f", gb)
            } else {
                String.format(Locale.US, "%.1f", gb)
            }
            return text to "GB"
        }

        /** The limit, written the way it was entered: whole GB lose the decimal. */
        fun formatLimit(limitMb: Int): String {
            val gb = limitMb.toDouble() / 1000.0
            return if (gb == Math.floor(gb)) {
                String.format(Locale.US, "%.0f", gb)
            } else {
                String.format(Locale.US, "%.1f", gb)
            }
        }
    }
}
