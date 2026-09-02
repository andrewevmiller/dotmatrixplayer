package com.dotgrid.datawidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The pillar: a rectangular dot-matrix grid instead of the radial dial, for
 * the 2 x 1 layout. Fills bottom to top, like a level gauge, rather than
 * clockwise like the dial - the two layouts read the same "how full" story
 * either way, but a grid has no natural clock-face start point, and bottom
 * to top is the one direction that does not need a reading direction learned
 * first.
 *
 * No text is drawn onto this bitmap. The pillar is a glanceable companion to
 * the dial, not a replacement for its readout - see docs/data-widget-pillar-
 * layout.md.
 */
object PillarRenderer {

    private const val COLS = 3
    private const val ROWS = 14
    private const val TOTAL = COLS * ROWS

    /** Dot diameter as a fraction of the grid's pitch (dot + gap). Matches
     *  the tightly packed look measured off the reference photo - the gap is
     *  smaller than the dot itself, not the other way round. */
    private const val DOT_DIAMETER_RATIO = 0.72f

    /** Same reasoning as MeterRenderer's cache: one size recurs per tile, and
     *  the fill only moves in forty-seconds of a repaint, so four entries
     *  covers what actually repeats. */
    private val cache = object : LruCache<String, Bitmap>(4) {}

    /**
     * @param widthPx / @param heightPx the box the grid is drawn into - the
     *   full pillar content area, not a square. The tighter of the two axes
     *   sets the dot pitch, so dots stay true circles and evenly spaced
     *   rather than stretching to fill whichever axis is roomier.
     * @param fraction usage over the allowance, uncapped, or null for "no
     *   limit set", which renders as an inert grid of dim dots.
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        fraction: Float?,
        activeColor: Int,
        inactiveColor: Int
    ): Bitmap {
        val w = max(8, widthPx)
        val h = max(8, heightPx)

        // Ceil, so any usage at all lights the first dot - same rule
        // MeterRenderer uses, for the same reason: an empty-looking gauge
        // while bytes are moving is worse than one dot of overstatement.
        val filled = fraction?.let {
            min(TOTAL, ceil((it.coerceAtLeast(0f) * TOTAL).toDouble()).toInt())
        } ?: 0

        val key = "$w|$h|$filled|$activeColor|$inactiveColor"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val pitch = min(w.toFloat() / COLS, h.toFloat() / ROWS)
        val dotRadius = pitch * DOT_DIAMETER_RATIO / 2f

        val gridWidth = pitch * COLS
        val gridHeight = pitch * ROWS
        val offsetX = (w - gridWidth) / 2f
        val offsetY = (h - gridHeight) / 2f

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                // Row 0 is the top of the canvas; the dot (ROWS - 1 - row)
                // rows up from the bottom is the one that fills first.
                val indexFromBottom = (ROWS - 1 - row) * COLS + col
                val cx = offsetX + pitch * (col + 0.5f)
                val cy = offsetY + pitch * (row + 0.5f)
                paint.color = if (indexFromBottom < filled) activeColor else inactiveColor
                canvas.drawCircle(cx, cy, dotRadius, paint)
            }
        }

        cache.put(key, bitmap)
        return bitmap
    }
}
