package com.dotgrid.mediawidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlin.math.max

/**
 * The source app's icon, restated in the tile's own language.
 *
 * A full-colour launcher icon dropped into this widget would be the only
 * saturated thing on it, and would read as a sticker rather than as part of the
 * card. Nothing's own icon treatment is a flat monochrome silhouette, so that is
 * what this produces.
 *
 * Two routes, in order of fidelity:
 *
 * 1. **The monochrome layer.** Adaptive icons can carry one - it is what the
 *    system uses for themed icons on Android 13+ - and it is already exactly the
 *    silhouette we want, so it only needs tinting.
 * 2. **Desaturate the icon.** No monochrome layer, so drop the saturation to
 *    zero, lift the result towards white, and clip it to a circle. Keeps the
 *    shape recognisable (a Spotify or YouTube Music mark still reads) without
 *    bringing any colour onto the card.
 */
object AppGlyph {

    private const val TAG = "AppGlyph"

    private val cache = object : LruCache<String, Bitmap>(8) {}

    /** Nothing's marks sit a little under full white, so they recede behind the title. */
    private const val LIFT = 0.18f

    /**
     * @return null when the package is unknown to us - package-visibility
     *   filtering hides most apps by default, and an icon we cannot read is not
     *   worth guessing at.
     */
    fun render(context: Context, packageName: String?, sizePx: Int, tint: Int): Bitmap? {
        if (packageName.isNullOrEmpty() || sizePx <= 0) return null

        val key = "$packageName|$sizePx|$tint"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val icon = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.onFailure {
            // Almost always package-visibility filtering rather than a missing
            // app, and it shows up as a quietly absent mark - so name it.
            Log.w(TAG, "No icon for $packageName; is it covered by <queries>?", it)
        }.getOrNull() ?: return null

        val mono = monochromeLayer(icon)
        Log.i(TAG, "Glyph for $packageName via ${if (mono != null) "monochrome layer" else "desaturation"}")

        val bitmap = mono?.let { silhouette(it, sizePx, tint) } ?: desaturated(icon, sizePx)

        cache.put(key, bitmap)
        return bitmap
    }

    /** The themed-icon layer, when the app ships one. */
    private fun monochromeLayer(icon: Drawable): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (icon !is AdaptiveIconDrawable) return null
        return icon.monochrome
    }

    /**
     * The monochrome layer is drawn into the adaptive-icon safe zone, which is
     * the middle two thirds of the canvas. Drawn at our bounds it would come out
     * noticeably smaller than everything around it, so it is oversized and
     * centred to bring the mark itself up to [sizePx].
     */
    private fun silhouette(layer: Drawable, sizePx: Int, tint: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val inset = (sizePx * 0.25f).toInt()
        layer.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
        layer.setTint(tint)
        layer.draw(canvas)
        return bitmap
    }

    /**
     * No monochrome layer: strip the colour instead. A saturation of zero leaves
     * the icon's own luminance, which is then lifted so a dark mark still shows
     * against a dark card, and clipped to a circle so square icons do not read
     * as a heavy block.
     */
    private fun desaturated(icon: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val matrix = ColorMatrix().apply { setSaturation(0f) }
        // Lift towards white without clipping the highlights to a flat blob.
        val lift = 255f * LIFT
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f - LIFT, 0f, 0f, 0f, lift,
                    0f, 1f - LIFT, 0f, 0f, lift,
                    0f, 0f, 1f - LIFT, 0f, lift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        icon.setBounds(0, 0, sizePx, sizePx)
        icon.colorFilter = ColorMatrixColorFilter(matrix)
        icon.draw(canvas)
        icon.clearColorFilter()

        // Circular mask, punched rather than clipped: Canvas.clipPath does not
        // antialias, and at this size a hard-edged circle looks chewed.
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            color = Color.BLACK
        }
        val cut = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(cut).drawCircle(
            sizePx / 2f,
            sizePx / 2f,
            sizePx / 2f - max(1f, sizePx * 0.02f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        )
        canvas.drawBitmap(cut, 0f, 0f, mask)
        cut.recycle()

        return bitmap
    }

    /** Human-readable name, for the content description. */
    fun label(context: Context, packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    /** Dropped when the widget is removed; nothing here is worth keeping warm. */
    fun clear() = cache.evictAll()
}
