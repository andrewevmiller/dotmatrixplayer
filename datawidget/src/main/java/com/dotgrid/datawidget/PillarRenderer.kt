package com.dotgrid.datawidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * The card itself is drawn here, not by a background drawable, so the pill's
 * proportions and its stadium ends are set in one place and survive whatever
 * box the launcher hands the widget - see [render] and the ratios below.
 *
 * No text is drawn onto this bitmap. The pillar is a glanceable companion to
 * the dial, not a replacement for its readout - see docs/data-widget-pillar-
 * layout.md.
 */
object PillarRenderer {

    private const val COLS = 3
    private const val ROWS = 14
    private const val TOTAL = COLS * ROWS

    /*
     * Every number below is measured off the reference widget - the wide
     * horizontal dot-grid pill sitting next to this one on the user's home
     * screen - from a device screenshot at density 3.0, and expressed as a
     * ratio so the pillar is that same object rotated 90 degrees rather than
     * a lookalike at a different scale.
     *
     *   card         x 538..952, y 226..409   ->  415 x 184 px
     *   dot grid     x 628..860, y 293..342   ->  233 x 50 px, centred
     *   pitch        233 / 14 = 16.64         (50 / 3 = 16.67 agrees to 0.15%)
     *   dot diameter 16.7, stable from a 32 to a 45 luminance edge threshold
     *
     * The grid ends up occupying 56.1% of the card's long axis and 27.2% of
     * its short one - the narrow inset strip with generous margin all round
     * that the reference reads as, not a grid stretched to the edges.
     */

    /** Long side over short side of the reference card: 415 / 184. */
    private const val ASPECT = 2.2554f

    /**
     * Card long side over the dot pitch: 415 / 16.64.
     *
     * The single source for the pitch, deliberately. An earlier round took
     * `min()` of this and a separate short-axis ratio, which sounds safer and
     * is not: measured independently the two rounded to 24.9 and 11.1, and
     * 11.1 disagrees with 24.9 / [ASPECT] = 11.06 by half a percent, so the
     * min always picked the short-axis one and the grid came out half a point
     * short of the reference's 56.1%. With the card drawn at a fixed [ASPECT]
     * the two ratios carry identical information, so only one is kept and the
     * short-axis margin follows from the aspect.
     */
    private const val LONG_OVER_PITCH = 24.94f

    /** Dot diameter as a fraction of the pitch. The reference's dots are
     *  exactly tangent - its measured diameter and pitch agree to within a
     *  fifth of a pixel, and the visible separation between dots is the
     *  diagonal gap left between touching circles, not a designed-in gap.
     *  An earlier round guessed 0.6, then 0.93; both drew a sparser grid
     *  than the thing being copied. */
    private const val DOT_DIAMETER_RATIO = 1.0f

    /** Same reasoning as MeterRenderer's cache: one size recurs per tile, and
     *  the fill only moves in forty-seconds of a repaint, so four entries
     *  covers what actually repeats. */
    private val cache = object : LruCache<String, Bitmap>(4) {}

    /**
     * @param widthPx / @param heightPx the whole box the launcher gave the
     *   widget. The card is drawn centred inside it at the reference's fixed
     *   [ASPECT], letterboxed with transparency on whichever axis is roomier,
     *   so a cell that is not exactly 1:2.26 yields a correctly proportioned
     *   pill rather than a stretched one.
     * @param fraction usage over the allowance, uncapped, or null for "no
     *   limit set", which renders as an inert grid of dim dots.
     * @param cardColor the pill's own fill - [R.color.pillar_surface]
     *   normally, the chosen alert colour once tripped.
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        fraction: Float?,
        cardColor: Int,
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

        val key = "$w|$h|$filled|$cardColor|$activeColor|$inactiveColor"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // The card at the reference aspect, as large as fits, centred.
        val cardWidth = min(w.toFloat(), h / ASPECT)
        val cardHeight = cardWidth * ASPECT
        val left = (w - cardWidth) / 2f
        val top = (h - cardHeight) / 2f
        val card = RectF(left, top, left + cardWidth, top + cardHeight)

        // Stadium: the radius is half the short side by construction, which is
        // what the reference's fully rounded ends measure to. Drawn rather
        // than clamped from an oversized drawable radius, so nothing depends
        // on the host not clipping the card's corners away.
        paint.color = cardColor
        canvas.drawRoundRect(card, cardWidth / 2f, cardWidth / 2f, paint)

        // One ratio, off the card's long axis - see LONG_OVER_PITCH. The
        // card is always drawn at ASPECT, so this fixes the grid's margins
        // on both axes at once and the dots stay true circles at any size.
        val pitch = cardHeight / LONG_OVER_PITCH
        val dotRadius = pitch * DOT_DIAMETER_RATIO / 2f

        val offsetX = card.left + (cardWidth - pitch * COLS) / 2f
        val offsetY = card.top + (cardHeight - pitch * ROWS) / 2f

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
