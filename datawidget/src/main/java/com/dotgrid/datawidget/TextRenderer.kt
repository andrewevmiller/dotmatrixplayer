package com.dotgrid.datawidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws a string into a bitmap using one of the bundled Nothing faces.
 *
 * This exists because **a widget cannot set a font in XML.**
 * [android.appwidget.AppWidgetHostView] inflates the layout through a context
 * created with [Context.CONTEXT_RESTRICTED], and `TextView` only resolves an
 * `android:fontFamily` resource when `!context.isRestricted()`. In a widget that
 * test fails, so the attribute is skipped in silence - no exception, nothing in
 * the log, just the system face where the custom one should be. Setting a
 * `Typeface` from code does not help either: `TypefaceSpan` parcels a family
 * name, not a face, so it arrives in the launcher meaning nothing.
 *
 * Pixels are the only thing that survives the trip. We load the face in our own
 * process, where it resolves normally, draw the text, and send the result as a
 * bitmap.
 */
object TextRenderer {

    private const val TAG = "TextRenderer"

    /**
     * Every string this widget draws is one it authored itself, out of a small
     * vocabulary - digits, "GB", "MB", "LIMIT", a day count. Twenty-four entries
     * covers a whole cycle's worth of distinct labels several times over.
     */
    private val cache = object : LruCache<String, Bitmap>(24) {}

    /**
     * The unit rides beside a number four times its size, so it needs the extra
     * air to read as a label rather than as a smudge on the end of the figure.
     */
    private const val TRACKING_UNIT = 0.10f

    private val faces = HashMap<Int, Typeface>()

    /**
     * A face that fails to load falls back to the system one and the widget
     * quietly looks wrong, which is the exact failure this class exists to
     * avoid - so say so loudly rather than swallowing it.
     */
    fun face(context: Context, fontRes: Int): Typeface =
        faces.getOrPut(fontRes) {
            runCatching { context.resources.getFont(fontRes) }
                .onFailure { Log.e(TAG, "Font resource $fontRes did not load; using system face", it) }
                .getOrDefault(Typeface.DEFAULT)
                .also { face ->
                    if (face === Typeface.DEFAULT) {
                        Log.e(TAG, "Font resource $fontRes resolved to the system face")
                    } else {
                        Log.i(TAG, "Loaded font resource $fontRes")
                    }
                }
        }

    fun render(
        context: Context,
        text: String,
        fontRes: Int,
        sizePx: Float,
        color: Int,
        letterSpacingEm: Float = 0f
    ): Bitmap {
        val key = "$text|$fontRes|$sizePx|$color|$letterSpacingEm"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val paint = paint(context, fontRes, sizePx, letterSpacingEm).apply { this.color = color }
        val shown = text.ifEmpty { " " }

        val metrics = paint.fontMetricsInt
        val width = max(1, ceil(paint.measureText(shown)).toInt())
        val height = max(1, metrics.descent - metrics.ascent)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(shown, 0f, -metrics.ascent.toFloat(), paint)

        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * The readout: a big number with its unit set small beside it, drawn as one
     * bitmap so the two sit on a shared baseline.
     *
     * Two ImageViews could not do this. RemoteViews has no baseline alignment
     * to offer across children, and bottom-aligning them would line up the two
     * descents instead - which at 24sp against 10sp leaves the unit visibly
     * sunk, by the difference between the two descents.
     */
    fun renderReadout(
        context: Context,
        value: String,
        valueSizePx: Float,
        valueColor: Int,
        unit: String,
        unitSizePx: Float,
        unitColor: Int,
        gapPx: Float
    ): Bitmap {
        val key = "readout|" + value + "|" + valueSizePx + "|" + valueColor +
            "|" + unit + "|" + unitSizePx + "|" + unitColor + "|" + gapPx
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val valuePaint = paint(context, Typography.BODY, valueSizePx, 0f)
            .apply { color = valueColor }
        val unitPaint = paint(context, Typography.BODY, unitSizePx, TRACKING_UNIT)
            .apply { color = unitColor }

        val valueWidth = ceil(valuePaint.measureText(value)).toInt()
        val unitWidth = if (unit.isEmpty()) 0 else ceil(unitPaint.measureText(unit)).toInt()
        val gap = if (unit.isEmpty()) 0 else ceil(gapPx.toDouble()).toInt()

        // The unit is the smaller face, so its extremes sit inside the value's
        // and the value alone can size the bitmap.
        val metrics = valuePaint.fontMetricsInt
        val width = max(1, valueWidth + gap + unitWidth)
        val height = max(1, metrics.descent - metrics.ascent)
        val baseline = -metrics.ascent.toFloat()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawText(value, 0f, baseline, valuePaint)
        if (unit.isNotEmpty()) {
            canvas.drawText(unit, (valueWidth + gap).toFloat(), baseline, unitPaint)
        }

        cache.put(key, bitmap)
        return bitmap
    }

    /** Width this string needs, so the caller can decide whether it will fit. */
    fun widthPx(
        context: Context,
        text: String,
        fontRes: Int,
        sizePx: Float,
        letterSpacingEm: Float = 0f
    ): Int = ceil(paint(context, fontRes, sizePx, letterSpacingEm).measureText(text)).toInt()

    private fun paint(context: Context, fontRes: Int, sizePx: Float, letterSpacingEm: Float) =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face(context, fontRes)
            textSize = sizePx
            letterSpacing = letterSpacingEm
        }
}
