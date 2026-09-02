package com.dotgrid.healthwidget

import android.content.Context

/**
 * The last figure each metric actually returned, and when.
 *
 * This exists for one situation, and it is the common one: the user has
 * granted the reads but not [HealthAccess.BACKGROUND_PERMISSION]. Health
 * Connect then answers a query only while something of ours is on screen -
 * which a home-screen tile never is - so every repaint after the settings
 * screen closes comes back empty. Without a cache the tile would show real
 * figures while you looked at the settings and dashes for the rest of the day,
 * which reads as a broken widget rather than as a missing permission.
 *
 * The tile is honest about it: a stale figure carries the time it was read
 * rather than the time it was drawn, so an old number looks old.
 */
object LastGood {

    private const val PREFS = "health_last_good"

    /**
     * Past this, a cached figure is not shown at all.
     *
     * Long enough that a phone left alone overnight still has yesterday's step
     * count in the morning, short enough that the tile cannot sit on a figure
     * from a different week. Past the cap the tile draws a dash, which is the
     * true answer: we do not know.
     */
    private const val MAX_AGE_MS = 36L * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(context: Context, metric: Int, value: Double, at: Long) {
        prefs(context).edit()
            // SharedPreferences has no double. Raw bits rather than a float:
            // an oxygen saturation of 97.4 is the whole reading, and rounding
            // it in storage would make the cached figure differ from the live
            // one by a decimal the tile actually prints.
            .putLong(valueKey(metric), java.lang.Double.doubleToRawLongBits(value))
            .putLong(timeKey(metric), at)
            .apply()
    }

    fun get(context: Context, metric: Int): Double? {
        val p = prefs(context)
        val at = p.getLong(timeKey(metric), 0L)
        if (at <= 0L) return null
        val age = System.currentTimeMillis() - at
        // A negative age means the clock moved backwards under us - a manual
        // change, or a timezone-less device catching up with the network. Treat
        // it as unknown rather than as fresh.
        if (age < 0 || age > MAX_AGE_MS) return null
        if (!p.contains(valueKey(metric))) return null
        return java.lang.Double.longBitsToDouble(p.getLong(valueKey(metric), 0L))
    }

    /**
     * The newest timestamp held, for the tile's stamp.
     *
     * Newest rather than oldest: the stamp answers "when was this tile last
     * able to read anything", and the oldest entry would date the tile by
     * whichever metric has been quiet longest.
     */
    fun readAt(context: Context, fallback: Long): Long {
        val p = prefs(context)
        val newest = HealthSettings.ALL_METRICS
            .map { p.getLong(timeKey(it), 0L) }
            .filter { it > 0L }
            .maxOrNull()
        return newest ?: fallback
    }

    private fun valueKey(metric: Int) = "v_$metric"

    private fun timeKey(metric: Int) = "t_$metric"
}
