package com.dotgrid.scorewidget

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
 *
 * This is the third copy of this class in the project, after `:app` and
 * `:datawidget`. They are deliberately not shared: a common module would put a
 * build edge between three apps that otherwise have none, to save a file whose
 * whole content is one platform quirk that is not going to change.
 *
 * ### Which face
 *
 * This class does not decide. Every method takes a font resource, and callers
 * pass a role from [Typography] rather than a face - see that file for the
 * mapping and for why Geist, NType82 and NDot each cover what they do.
 *
 * The one thing worth knowing here: **no call into this file should ever be
 * passed the NDot cut.** NDot is the product-name and logotype face, and
 * nothing this class draws is a product name. The single NDot use in the module
 * is [TeamGlyphs], which does its own rasterising precisely because what it
 * produces is a dot matrix rather than type.
 */
object TextRenderer {

    private const val TAG = "TextRenderer"

    /**
     * Larger than the data tile's 24.
     *
     * That tile draws one figure and three fixed labels. This one draws two
     * scores, two abbreviations, a clock, a down-and-distance, a broadcast
     * name and a stat line, for up to five games in a carousel - and the clock
     * is a distinct string every minute. 64 covers a full card's worth of
     * labels across a few carousel positions without the clock evicting the
     * things that do not change.
     */
    private val cache = object : LruCache<String, Bitmap>(64) {}

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
     * The two scores and the dash between them, as one bitmap.
     *
     * Three ImageViews could not do this. The scores are set large and the
     * separator small, on a shared baseline, and RemoteViews has no baseline
     * alignment to offer across children - bottom-aligning them would line up
     * the descents instead, which at 26sp against 13sp leaves the dash sunk
     * below the digits it separates.
     *
     * The two scores are also **column-aligned to the same width**, measured
     * from the wider of the pair. Without it the dash slides left when the away
     * side goes from 9 to 10, and on a tile that repaints every minute during a
     * game that movement is the thing the eye catches - not the score.
     *
     * That column alignment is also why the face here is [Typography.BODY]
     * rather than a tabular one. The obvious reach for a changing figure is
     * NDot 57 Aligned, whose digits are all one width - but NDot is the
     * product-name face and a score is a data readout, so the brand rule sends
     * it elsewhere. Measuring both scores into a shared column solves the
     * jitter structurally instead, and does it better: it holds for one digit
     * against two against three, which a tabular face does not.
     *
     * @param fontRes the face, as a [Typography] role. Passed in rather than
     *   chosen here so this file states no typographic opinion of its own.
     */
    fun renderScoreline(
        context: Context,
        away: String,
        home: String,
        fontRes: Int,
        sizePx: Float,
        awayColor: Int,
        homeColor: Int,
        separator: String,
        separatorSizePx: Float,
        separatorColor: Int,
        gapPx: Float
    ): Bitmap {
        val key = "score|$away|$home|$fontRes|$sizePx|$awayColor|$homeColor|" +
            "$separator|$separatorSizePx|$separatorColor|$gapPx"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val scorePaint = paint(context, fontRes, sizePx, Typography.Tracking.FIGURES)
        val sepPaint = paint(context, fontRes, separatorSizePx, Typography.Tracking.FIGURES)
            .apply { color = separatorColor }

        val column = max(scorePaint.measureText(away), scorePaint.measureText(home))
        val sepWidth = sepPaint.measureText(separator)
        val gap = max(0f, gapPx)

        val metrics = scorePaint.fontMetricsInt
        val width = max(1, ceil(column * 2 + sepWidth + gap * 2).toInt())
        val height = max(1, metrics.descent - metrics.ascent)
        val baseline = -metrics.ascent.toFloat()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Each score is centred in its own column, so a one-digit score and a
        // two-digit score both sit under the same midpoint.
        scorePaint.color = awayColor
        canvas.drawText(away, (column - scorePaint.measureText(away)) / 2f, baseline, scorePaint)

        canvas.drawText(separator, column + gap, baseline, sepPaint)

        scorePaint.color = homeColor
        val homeLeft = column + gap * 2 + sepWidth
        canvas.drawText(home, homeLeft + (column - scorePaint.measureText(home)) / 2f, baseline, scorePaint)

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

    /**
     * Steps a size down until [fits], in twentieths, to a floor of 60%.
     *
     * Shared by every caller that has a width budget, because they all have the
     * same shape of problem: a string the user did not choose - a long
     * broadcast name, a translated status, "3RD AND 12" - arriving in a slot
     * sized for the common case.
     */
    inline fun shrinkToFit(startPx: Float, fits: (Float) -> Boolean): Float {
        val floor = startPx * 0.6f
        var size = startPx
        while (size > floor) {
            if (fits(size)) return size
            size -= startPx * 0.05f
        }
        return floor
    }

    fun paint(context: Context, fontRes: Int, sizePx: Float, letterSpacingEm: Float): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face(context, fontRes)
            textSize = sizePx
            letterSpacing = letterSpacingEm
        }

    /** Dropped when the last tile is removed. */
    fun clear() = cache.evictAll()
}
