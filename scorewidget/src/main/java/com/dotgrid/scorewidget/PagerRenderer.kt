package com.dotgrid.scorewidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import kotlin.math.max

/**
 * The carousel indicator: one dot per card, the current one lit and enlarged.
 *
 * Drawn rather than assembled from views because the count changes - a game
 * ends and the list goes from four cards to three - and a RemoteViews tree
 * cannot add or remove children after inflation. A bitmap can simply be a
 * different bitmap.
 *
 * The lit dot is both brighter *and* bigger. Brightness alone is not enough on
 * a tile where the whole palette is white at three alphas: at 3dp the
 * difference between full white and 36% white is a couple of pixels' worth of
 * grey, and the eye reads the row as evenly spaced dots with one slightly
 * dirty. Size is the difference that survives.
 */
object PagerRenderer {

    private val cache = object : LruCache<String, Bitmap>(6) {}

    private const val ACTIVE_SCALE = 1.5f

    fun render(
        count: Int,
        current: Int,
        dotPx: Int,
        pitchPx: Int,
        vertical: Boolean,
        activeColor: Int,
        inactiveColor: Int
    ): Bitmap {
        val safeCount = max(1, count)
        val key = "$safeCount|$current|$dotPx|$pitchPx|$vertical|$activeColor|$inactiveColor"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val radius = max(1f, dotPx / 2f)
        val activeRadius = radius * ACTIVE_SCALE
        // The lit dot is the widest thing in the row, so it sets the cross axis.
        val thickness = max(1, (activeRadius * 2).toInt())
        val length = max(1, pitchPx * safeCount)

        val width = if (vertical) thickness else length
        val height = if (vertical) length else thickness

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (i in 0 until safeCount) {
            val isCurrent = i == current
            paint.color = if (isCurrent) activeColor else inactiveColor
            val along = (i + 0.5f) * pitchPx
            val across = thickness / 2f
            val r = if (isCurrent) activeRadius else radius
            if (vertical) {
                canvas.drawCircle(across, along, r, paint)
            } else {
                canvas.drawCircle(along, across, r, paint)
            }
        }

        cache.put(key, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()
}
