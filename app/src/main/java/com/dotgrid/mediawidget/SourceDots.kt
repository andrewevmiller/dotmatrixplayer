package com.dotgrid.mediawidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max

/**
 * The carousel's page indicator: one dot per app holding a session, the
 * current one lit.
 *
 * Dots rather than "2/3" for the same reason the scrub bar is a dot rail -
 * the tile counts in dots, and a fraction here would be the only numeral on
 * the card that is not a timecode.
 */
object SourceDots {

    /** Beyond this the dots stop being countable and start being a smear. */
    private const val MAX_DOTS = 5

    /**
     * @param index  which dot is lit, clamped into range
     * @param count  total sources; below two this returns null, because a
     *   one-page carousel is not a carousel and should draw nothing at all
     */
    fun render(
        count: Int,
        index: Int,
        dotPx: Int,
        gapPx: Int,
        activeColor: Int,
        inactiveColor: Int
    ): Bitmap? {
        if (count < 2) return null

        val shown = count.coerceAtMost(MAX_DOTS)
        val lit = index.coerceIn(0, count - 1).coerceAtMost(shown - 1)

        val d = max(1, dotPx)
        val gap = max(0, gapPx)
        val width = shown * d + (shown - 1) * gap

        val bitmap = Bitmap.createBitmap(max(1, width), d, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val r = d / 2f
        for (i in 0 until shown) {
            paint.color = if (i == lit) activeColor else inactiveColor
            canvas.drawCircle(i * (d + gap) + r, r, r, paint)
        }
        return bitmap
    }

    /** Width the indicator will occupy, so the metadata column can reserve it. */
    fun widthPx(count: Int, dotPx: Int, gapPx: Int): Int {
        if (count < 2) return 0
        val shown = count.coerceAtMost(MAX_DOTS)
        return shown * max(1, dotPx) + (shown - 1) * max(0, gapPx)
    }
}
