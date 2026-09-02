package com.dotgrid.healthwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max

/**
 * The rail under each row on the wide tile: the same dial, unrolled.
 *
 * Drawn as a dot rail rather than a filled track so it shares the dot
 * vocabulary of the Ndot face the figures are set in - and so the wide tile
 * and the square one read as the same object at two sizes, rather than as two
 * widgets that happen to match on colour.
 *
 * Filled dots are solid, the rest drop to a dim white, and the head is a short
 * upright bar - a tick on a scale, not a bigger dot. On the arc there is room
 * for a dot to grow into; on an 7dp rail there is not.
 */
object RailRenderer {

    /**
     * @param widthPx  rail width in pixels - the on-screen width, so fitXY
     *   never actually scales it and the dots stay round.
     * @param heightPx rail height in pixels.
     * @param fraction progress over the goal, uncapped, or null for "no goal
     *   set", which renders as an inert rail of dim dots. Nothing is a fraction
     *   of a goal the user turned off.
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        fraction: Float?,
        activeColor: Int,
        inactiveColor: Int,
        headColor: Int
    ): Bitmap {
        val w = max(2, widthPx)
        val h = max(2, heightPx)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val dotRadius = max(0.8f, h * 0.13f)
        val pitch = dotRadius * 3.4f
        val count = max(2, floor((w - dotRadius * 2) / pitch).toInt() + 1)

        // Distribute the rounding slack across the rail instead of leaving a
        // ragged gap at the right edge.
        val step = if (count > 1) (w - dotRadius * 2) / (count - 1) else 0f
        val centerY = h / 2f

        // Capped for the drawing even though the caller may pass more than 1:
        // past the goal the rail is simply full, and a head drawn off the end
        // would be clamped to the last dot and read as if it had stopped short.
        val progress = fraction?.coerceIn(0f, 1f)
        val headX = progress?.let { dotRadius + it * (w - dotRadius * 2) }

        for (i in 0 until count) {
            val x = dotRadius + i * step
            paint.color = when {
                headX == null -> inactiveColor
                x <= headX -> activeColor
                else -> inactiveColor
            }
            canvas.drawCircle(x, centerY, dotRadius, paint)
        }

        // The head, drawn last so it sits over the rail. Skipped at zero:
        // a tick hard against the left edge reads as one step taken, and none
        // is a different statement from a few.
        if (headX != null && progress > 0f) {
            paint.color = headColor
            val halfW = max(1f, h * 0.10f)
            val halfH = h * 0.42f
            val left = (headX - halfW).coerceIn(0f, w - halfW * 2)
            canvas.drawRoundRect(
                RectF(left, centerY - halfH, left + halfW * 2, centerY + halfH),
                halfW,
                halfW,
                paint
            )
        }

        return bitmap
    }
}
