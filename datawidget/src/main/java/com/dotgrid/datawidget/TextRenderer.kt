package com.dotgrid.datawidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

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

    /**
     * How far the pill's edge sits from the glyphs it backs, in dp. Small
     * enough to read as a tight label rather than a button - this is a
     * legibility fix, not a new piece of chrome.
     */
    private const val PILL_PAD_H_DP = 4f
    private const val PILL_PAD_V_DP = 2f
    private const val PILL_RADIUS_DP = 3f

    fun render(
        context: Context,
        text: String,
        fontRes: Int,
        sizePx: Float,
        color: Int,
        letterSpacingEm: Float = 0f,
        pillColor: Int? = null
    ): Bitmap {
        val key = "$text|$fontRes|$sizePx|$color|$letterSpacingEm|$pillColor"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val paint = paint(context, fontRes, sizePx, letterSpacingEm).apply { this.color = color }
        val shown = text.ifEmpty { " " }

        val metrics = paint.fontMetricsInt
        val textWidth = max(1, ceil(paint.measureText(shown)).toInt())
        val textHeight = max(1, metrics.descent - metrics.ascent)

        /*
         * The pill is baked into the same bitmap as the glyphs rather than a
         * sibling drawable behind the ImageView: every ImageView these land
         * in is wrap_content with no slack around the text, so a background
         * drawn to the view's own bounds would exactly hug the glyphs anyway
         * - the padding has to come from inside this bitmap or not at all.
         */
        val density = context.resources.displayMetrics.density
        val padH = if (pillColor != null) (PILL_PAD_H_DP * density) else 0f
        val padV = if (pillColor != null) (PILL_PAD_V_DP * density) else 0f

        val width = max(1, textWidth + (padH * 2).roundToInt())
        val height = max(1, textHeight + (padV * 2).roundToInt())

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (pillColor != null) {
            val radius = PILL_RADIUS_DP * density
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = pillColor }
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, fill)
        }
        canvas.drawText(shown, padH, padV - metrics.ascent.toFloat(), paint)

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
        gapPx: Float,
        pillColor: Int? = null
    ): Bitmap {
        val key = "readout|" + value + "|" + valueSizePx + "|" + valueColor +
            "|" + unit + "|" + unitSizePx + "|" + unitColor + "|" + gapPx + "|" + pillColor
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
        val textWidth = max(1, valueWidth + gap + unitWidth)
        val textHeight = max(1, metrics.descent - metrics.ascent)

        // Same reasoning as render(): the pill is baked into the bitmap
        // because the ImageView it lands in is wrap_content with no slack.
        val density = context.resources.displayMetrics.density
        val padH = if (pillColor != null) (PILL_PAD_H_DP * density) else 0f
        val padV = if (pillColor != null) (PILL_PAD_V_DP * density) else 0f

        val width = max(1, textWidth + (padH * 2).roundToInt())
        val height = max(1, textHeight + (padV * 2).roundToInt())
        val baseline = padV - metrics.ascent.toFloat()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (pillColor != null) {
            val radius = PILL_RADIUS_DP * density
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillColor }
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, fill)
        }
        canvas.drawText(value, padH, baseline, valuePaint)
        if (unit.isNotEmpty()) {
            canvas.drawText(unit, padH + (valueWidth + gap).toFloat(), baseline, unitPaint)
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
