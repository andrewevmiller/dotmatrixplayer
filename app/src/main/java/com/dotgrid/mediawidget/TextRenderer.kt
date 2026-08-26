package com.dotgrid.mediawidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
     * Rendered labels are cached: at 1 Hz the only string that actually changes
     * is the elapsed time, so everything else is drawn once per track.
     */
    private val cache = object : LruCache<String, Bitmap>(48) {}
    private val faces = HashMap<Int, Typeface>()

    /** Hard ceiling on a label bitmap, so a pathological title cannot blow the parcel. */
    private const val MAX_WIDTH_PX = 1200

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
     * @param maxWidthPx clamps the result, ellipsising the text to fit. Pass 0
     *   to let the label take whatever width it needs.
     * @param fallbackFontRes used instead of [fontRes] when [text] contains a
     *   character [fontRes] has no glyph for. Nothing's guidelines put NType
     *   forward as the general-purpose face and reserve NDot for wordmarks -
     *   but NType82 covers only Latin, and track titles arrive in whatever
     *   script the source app used. Falling back to a wide-coverage NDot cut
     *   for just those strings beats either breaking the guideline for every
     *   string or showing tofu for the ones NType can't render.
     */
    fun render(
        context: Context,
        text: String,
        fontRes: Int,
        sizePx: Float,
        color: Int,
        maxWidthPx: Int = 0,
        letterSpacingEm: Float = 0f,
        fallbackFontRes: Int? = null
    ): Bitmap {
        val resolvedFontRes =
            if (fallbackFontRes != null && !face(context, fontRes).covers(text)) {
                fallbackFontRes
            } else {
                fontRes
            }

        val key = "$text|$resolvedFontRes|$sizePx|$color|$maxWidthPx|$letterSpacingEm"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face(context, resolvedFontRes)
            textSize = sizePx
            this.color = color
            letterSpacing = letterSpacingEm
        }

        val limit = if (maxWidthPx > 0) min(maxWidthPx, MAX_WIDTH_PX) else MAX_WIDTH_PX
        val shown = TextUtils.ellipsize(
            text.ifEmpty { " " },
            paint,
            limit.toFloat(),
            TextUtils.TruncateAt.END
        ).toString()

        val metrics = paint.fontMetricsInt
        val width = max(1, min(limit, ceil(paint.measureText(shown)).toInt()))
        val height = max(1, metrics.descent - metrics.ascent)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(shown, 0f, -metrics.ascent.toFloat(), paint)

        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * A time code, drawn with **tabular digits** whatever the face's own
     * figures do.
     *
     * The elapsed clock repaints once a second, and every face this project
     * ships except one has proportional figures. Measured off their own `hmtx`
     * tables, per 1000 units of em: Ndot 57 Aligned puts all ten digits at 600;
     * NType 82 runs 422 to 508; Geist runs 384 to 663, a 42% spread between `1`
     * and `0`. Set naively in either of the latter two, `1:01` is visibly
     * narrower than `0:00`, so the label reflows on the tick that a `1` enters
     * or leaves - and because [widthPx] sizes the scrub rail from these same
     * labels, the rail resizes with it.
     *
     * Ndot 57 Aligned is the tabular cut and used to carry this line for that
     * reason. It is the wordmark face, though, and the guideline is explicit
     * that it is not a general-purpose one, so the fix cannot be typographic.
     * It is structural instead: every digit is advanced by the width of the
     * widest digit and centred in that cell, which makes any face behave as a
     * tabular one here. Non-digits keep their natural advance, so the colon
     * stays tight.
     *
     * This is the same move the sibling score tile makes with its scoreline,
     * where two scores are measured into one shared column so the separator
     * cannot slide. Digit jitter in this family is solved by measuring, not by
     * picking a face.
     */
    fun renderTimeCode(
        context: Context,
        text: String,
        fontRes: Int,
        sizePx: Float,
        color: Int
    ): Bitmap {
        val key = "time|$text|$fontRes|$sizePx|$color"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face(context, fontRes)
            textSize = sizePx
            this.color = color
        }

        val cell = digitCellPx(paint)
        val metrics = paint.fontMetricsInt
        val width = max(1, ceil(tabularWidth(paint, text, cell)).toInt())
        val height = max(1, metrics.descent - metrics.ascent)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = -metrics.ascent.toFloat()

        var x = 0f
        text.forEach { ch ->
            val glyph = ch.toString()
            if (ch.isDigit()) {
                // Centred in its cell, so the column holds still even though
                // the glyph inside it is a different width each second.
                canvas.drawText(glyph, x + (cell - paint.measureText(glyph)) / 2f, baseline, paint)
                x += cell
            } else {
                canvas.drawText(glyph, x, baseline, paint)
                x += paint.measureText(glyph)
            }
        }

        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * Width [renderTimeCode] will produce, so the scrub rail can be sized
     * against the same geometry the labels are drawn with.
     *
     * Sizing the rail with the ordinary [widthPx] would put the tabular fix
     * only half in place: the labels would hold still and the rail between them
     * would still breathe.
     */
    fun timeCodeWidthPx(context: Context, text: String, fontRes: Int, sizePx: Float): Int {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face(context, fontRes)
            textSize = sizePx
        }
        return max(1, ceil(tabularWidth(paint, text, digitCellPx(paint))).toInt())
    }

    /** The widest of the ten digits, which every digit is then advanced by. */
    private fun digitCellPx(paint: TextPaint): Float {
        var widest = 0f
        for (d in '0'..'9') {
            widest = max(widest, paint.measureText(d.toString()))
        }
        return widest
    }

    private fun tabularWidth(paint: TextPaint, text: String, cell: Float): Float {
        var total = 0f
        text.forEach { ch ->
            total += if (ch.isDigit()) cell else paint.measureText(ch.toString())
        }
        return total
    }

    /** Height a label of this size will occupy, so layouts can reserve for it. */
    fun heightPx(context: Context, fontRes: Int, sizePx: Float): Int {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face(context, fontRes)
            textSize = sizePx
        }
        val m = paint.fontMetricsInt
        return max(1, m.descent - m.ascent)
    }

    /** Width this string needs, unclamped - used to size the scrub rail. */
    fun widthPx(
        context: Context,
        text: String,
        fontRes: Int,
        sizePx: Float,
        letterSpacingEm: Float = 0f
    ): Int {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face(context, fontRes)
            textSize = sizePx
            letterSpacing = letterSpacingEm
        }
        return ceil(paint.measureText(text)).toInt()
    }

    /** Whether every non-whitespace character in [text] has a glyph in this face. */
    private fun Typeface.covers(text: String): Boolean {
        if (text.isBlank()) return true
        val paint = Paint().apply { typeface = this@covers }
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            if (!Character.isWhitespace(codePoint) &&
                !paint.hasGlyph(text.substring(i, i + charCount))
            ) {
                return false
            }
            i += charCount
        }
        return true
    }
}
