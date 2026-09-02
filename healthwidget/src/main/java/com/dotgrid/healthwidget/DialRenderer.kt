package com.dotgrid.healthwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The dial on the square tile: an arc of dots rather than a swept ring, so the
 * meter speaks the same dot-matrix vocabulary as the Ndot face the figures are
 * set in.
 *
 * The arc opens at the bottom. That is not decoration - the readout stacks
 * three lines inside the ring, and a closed circle would either crowd them or
 * force the type down a size. The gap gives the last line somewhere to sit and
 * reads as a dial while doing it.
 */
object DialRenderer {

    /**
     * Android measures angles clockwise from 3 o'clock, so 135 deg is the lower
     * left and 270 deg of sweep closes at the lower right - a 90 deg opening
     * centred on 6 o'clock.
     */
    private const val START_DEG = 135f
    private const val SWEEP_DEG = 270f

    /**
     * Forty dots over 270 deg is one every 6.9 deg: dense enough that the arc
     * reads as a continuous scale at 110dp, sparse enough that each dot is
     * still a dot rather than a dashed line.
     */
    private const val DOT_COUNT = 40

    private const val DOT_RADIUS_RATIO = 0.026f

    /** The leading dot, enlarged - the same idea as a playhead on a scrub bar. */
    private const val HEAD_SCALE = 1.55f

    /**
     * Deliberately small. A dial for a 2 x 2 tile at 3x density is a 270px
     * square, which is about 290KB of ARGB_8888 - a dozen of those is 3.5MB
     * held for the life of the process, to save redrawing forty circles. Four
     * covers what actually recurs: one tile size, and the fill sitting on the
     * same dot across consecutive repaints.
     */
    private val cache = object : LruCache<String, Bitmap>(4) {}

    /**
     * @param sizePx the square the arc is drawn into.
     * @param fraction the metric over its goal, uncapped, or null for "no goal
     *   set" - which renders as an inert rail of dim dots instead of a fill,
     *   because there is nothing here to be a fraction of. A vital passes the
     *   position of the reading in its display band instead; see [Metrics].
     */
    fun render(
        sizePx: Int,
        fraction: Float?,
        activeColor: Int,
        inactiveColor: Int
    ): Bitmap {
        val size = max(8, sizePx)

        // Ceil, so any progress at all lights the first dot: a dial that reads
        // empty after a walk is worse than one dot of overstatement.
        val filled = fraction?.let {
            min(DOT_COUNT, ceil((it.coerceAtLeast(0f) * DOT_COUNT).toDouble()).toInt())
        } ?: 0

        // Keyed on the dot count, not the fraction behind it. Steps creep
        // continuously and the dial only moves in fortieths, so keying on the
        // raw fraction would miss on every repaint and cache nothing.
        val key = "$size|$filled|$activeColor|$inactiveColor"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val dotRadius = max(1f, size * DOT_RADIUS_RATIO)
        // The head dot is the widest thing on the arc, so it sets the inset.
        val radius = size / 2f - dotRadius * HEAD_SCALE
        val centre = size / 2f
        val step = SWEEP_DEG / (DOT_COUNT - 1)

        for (i in 0 until DOT_COUNT) {
            val radians = Math.toRadians((START_DEG + i * step).toDouble())
            val x = centre + radius * cos(radians).toFloat()
            val y = centre + radius * sin(radians).toFloat()

            val isHead = filled > 0 && i == filled - 1
            paint.color = if (i < filled) activeColor else inactiveColor
            canvas.drawCircle(x, y, if (isHead) dotRadius * HEAD_SCALE else dotRadius, paint)
        }

        cache.put(key, bitmap)
        return bitmap
    }
}
