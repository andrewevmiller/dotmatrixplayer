package com.dotgrid.scorewidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The parts of a live game that are a picture rather than a sentence.
 *
 * Three things: the bases, the win-probability rail, and the small marks that
 * hang off a scoreline. All of them are drawn rather than typeset, and all of
 * them are drawn in dots or in outlines with a dot's weight, because the tile
 * has exactly one visual vocabulary and a solid triangle on a base would be the
 * only filled shape on it.
 */
object ContextRenderer {

    private val cache = object : LruCache<String, Bitmap>(12) {}

    // -----------------------------------------------------------------------
    // The diamond.
    // -----------------------------------------------------------------------

    /**
     * Three bases and the outs.
     *
     * Drawn as **squares stood on a corner**, which is what a base looks like
     * from above and what every scoreboard graphic in the sport uses. An
     * occupied base is filled; an empty one is its outline. That distinction
     * has to survive at 22dp across, which is why the outline is a stroke of a
     * fixed minimum weight rather than a proportion - below about 1.2px the
     * empty and filled states start to look the same on a dark surface.
     *
     * Home plate is not drawn. It is where the batter is standing, it is never
     * "occupied" in the sense the other three are, and including it turns a
     * legible three-point diamond into four marks in a diamond-ish cluster.
     *
     * @param sizePx the square the diamond and its outs are drawn into.
     */
    fun bases(
        sizePx: Int,
        onFirst: Boolean,
        onSecond: Boolean,
        onThird: Boolean,
        outs: Int,
        activeColor: Int,
        inactiveColor: Int
    ): Bitmap {
        val size = max(12, sizePx)
        val key = "bases|$size|$onFirst|$onSecond|$onThird|$outs|$activeColor|$inactiveColor"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        /*
         * The diamond takes the top ~70% and the outs sit under it. Splitting
         * the box rather than drawing the outs beside the diamond keeps the
         * whole indicator square, which is what lets the layout give it one
         * dimension and have it behave the same on all three tile sizes.
         */
        val diamondSize = size * 0.70f
        val base = diamondSize * 0.30f
        val centreX = size / 2f
        val centreY = diamondSize / 2f
        // The three bases sit on a circle around the diamond's middle.
        val orbit = diamondSize * 0.30f

        drawBase(canvas, centreX + orbit, centreY, base, onFirst, activeColor, inactiveColor)
        drawBase(canvas, centreX, centreY - orbit, base, onSecond, activeColor, inactiveColor)
        drawBase(canvas, centreX - orbit, centreY, base, onThird, activeColor, inactiveColor)

        // Outs: three dots, lit for each one recorded. Three rather than two,
        // because the third out exists for the instant before the inning turns
        // over and a two-dot indicator cannot show it.
        val dotRadius = max(1f, size * 0.045f)
        val gap = dotRadius * 3f
        val outsY = diamondSize + (size - diamondSize) / 2f
        val startX = centreX - gap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (i in 0 until 3) {
            paint.color = if (i < outs) activeColor else inactiveColor
            canvas.drawCircle(startX + i * gap, outsY, dotRadius, paint)
        }

        cache.put(key, bitmap)
        return bitmap
    }

    private fun drawBase(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        occupied: Boolean,
        activeColor: Int,
        inactiveColor: Int
    ) {
        val half = size / 2f
        val path = Path().apply {
            moveTo(cx, cy - half)
            lineTo(cx + half, cy)
            lineTo(cx, cy + half)
            lineTo(cx - half, cy)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (occupied) {
                style = Paint.Style.FILL
                color = activeColor
            } else {
                style = Paint.Style.STROKE
                // Floored, not scaled: an outline thinner than about 1.2px
                // stops reading as an outline and the empty base looks filled.
                strokeWidth = max(1.2f, size * 0.16f)
                color = inactiveColor
            }
        }
        canvas.drawPath(path, paint)
    }

    // -----------------------------------------------------------------------
    // The win-probability rail.
    // -----------------------------------------------------------------------

    /**
     * Win probability as a rail of dots with a marker where the two sides meet.
     *
     * Oriented **left to right, away to home**, matching the scoreline directly
     * above it - so the marker sitting right of centre means the home side, on
     * the right of the scoreline, is favoured. Any other orientation would need
     * a label to explain it, and there is no room for one.
     *
     * Both halves stay lit, at different intensities, rather than one half
     * being lit and the other dark. A bar that empties reads as a quantity
     * draining away; this is not a quantity, it is a *split*, and the thing to
     * see is where the two meet.
     *
     * @param fraction home win probability, 0..1.
     */
    fun winRail(
        widthPx: Int,
        heightPx: Int,
        fraction: Float,
        leadColor: Int,
        trailColor: Int,
        markerColor: Int
    ): Bitmap {
        val width = max(8, widthPx)
        val height = max(4, heightPx)

        // Quantised to the dot the marker lands on, not to the raw fraction:
        // the probability creeps continuously and the rail only moves in
        // whole dots, so keying on the fraction would miss on every repaint.
        val dotCount = max(5, (width / (height * 0.9f)).roundToInt())
        val awayDots = ((1f - fraction).coerceIn(0f, 1f) * dotCount).roundToInt()

        val key = "rail|$width|$height|$dotCount|$awayDots|$leadColor|$trailColor|$markerColor"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val pitch = width.toFloat() / dotCount
        val radius = max(1f, height * 0.22f)
        val markerRadius = max(radius * 1.7f, height * 0.42f)
        val centreY = height / 2f

        // Whichever side is ahead gets the brighter dots, so the rail says who
        // is favoured before the marker's position has been read at all.
        val homeAhead = fraction >= 0.5f

        for (i in 0 until dotCount) {
            val isAway = i < awayDots
            paint.color = when {
                isAway && !homeAhead -> leadColor
                !isAway && homeAhead -> leadColor
                else -> trailColor
            }
            canvas.drawCircle((i + 0.5f) * pitch, centreY, radius, paint)
        }

        // The marker sits on the boundary between the two runs, clamped inside
        // the rail so a 2% probability still shows a marker rather than half of
        // one hanging off the end.
        paint.color = markerColor
        val markerX = (awayDots * pitch).coerceIn(markerRadius, width - markerRadius)
        canvas.drawCircle(markerX, centreY, markerRadius, paint)

        cache.put(key, bitmap)
        return bitmap
    }

    // -----------------------------------------------------------------------
    // Field position.
    // -----------------------------------------------------------------------

    /**
     * Where the ball is, as a hundred yards of rail with a marker on it.
     *
     * Only drawn on the 4x2 card, where there is width to make a hundred yards
     * mean something. On a banner it would be a dozen dots and a marker that
     * moves one dot per first down, which says less than the yard line already
     * written beside it.
     *
     * @param ownHalfFraction 0 at the possessing team's own goal line, 1 at the
     *   opponent's. The feed gives a yard line and a side; converting to a
     *   single axis is the caller's job because only the caller knows which way
     *   the possession is pointing.
     */
    fun fieldRail(
        widthPx: Int,
        heightPx: Int,
        ownHalfFraction: Float,
        railColor: Int,
        markerColor: Int,
        redZone: Boolean,
        redZoneColor: Int
    ): Bitmap {
        val width = max(12, widthPx)
        val height = max(4, heightPx)
        val dotCount = max(10, (width / (height * 0.85f)).roundToInt())
        val marker = (ownHalfFraction.coerceIn(0f, 1f) * (dotCount - 1)).roundToInt()

        val key = "field|$width|$height|$dotCount|$marker|$railColor|$markerColor|$redZone"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val pitch = width.toFloat() / dotCount
        val radius = max(1f, height * 0.20f)
        val centreY = height / 2f

        // The last fifth of the rail is the red zone, drawn in the accent
        // whether or not the ball is in it - so the destination is visible
        // before the marker arrives, which is what makes the arrival read.
        val redZoneFrom = (dotCount * 0.8f).roundToInt()

        for (i in 0 until dotCount) {
            paint.color = when {
                i == marker -> markerColor
                redZone && i >= redZoneFrom -> redZoneColor
                else -> railColor
            }
            val r = if (i == marker) radius * 1.8f else radius
            canvas.drawCircle((i + 0.5f) * pitch, centreY, r, paint)
        }

        cache.put(key, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()
}
