package com.dotgrid.mediawidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the scrub bar as a dot rail rather than a filled track, so it shares
 * the dot vocabulary of the Ndot face used for the labels.
 *
 * Played dots are solid white, remaining dots drop to a dim white, and the
 * playhead is the single piece of red in the whole widget - the accent is worth
 * more when exactly one thing uses it.
 */
object ProgressRenderer {

    /**
     * @param widthPx  bar width in pixels
     * @param heightPx bar height in pixels
     * @param fraction 0..1 progress, or null for a track with no known duration
     *   (live streams, podcasts mid-buffer), which renders as an inert rail.
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        fraction: Float?,
        activeColor: Int,
        inactiveColor: Int,
        headColor: Int
    ): Bitmap {
        val w = max(1, widthPx)
        val h = max(1, heightPx)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val dotRadius = h * 0.13f
        val pitch = dotRadius * 3.4f
        val count = max(2, floor((w - dotRadius * 2) / pitch).toInt() + 1)

        // Distribute any rounding slack across the rail instead of leaving a
        // ragged gap at the right edge.
        val step = if (count > 1) (w - dotRadius * 2) / (count - 1) else 0f
        val centerY = h / 2f

        val progress = fraction?.coerceIn(0f, 1f)
        val headX = progress?.let { dotRadius + it * (w - dotRadius * 2) }

        for (i in 0 until count) {
            val x = dotRadius + i * step
            paint.color = when {
                progress == null -> inactiveColor
                headX != null && x <= headX -> activeColor
                else -> inactiveColor
            }
            canvas.drawCircle(x, centerY, dotRadius, paint)
        }

        // Playhead: a full-height bar, drawn last so it sits over the rail.
        if (headX != null) {
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

    /** Formats a duration the way a transport display does: m:ss, or h:mm:ss past an hour. */
    fun formatTime(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = (ms / 1000.0).roundToInt()
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
