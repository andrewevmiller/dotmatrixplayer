package com.dotgrid.mediawidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlin.math.max

/**
 * Album art handling for a surface that cannot clip.
 *
 * RemoteViews has no outline provider and no clipToOutline, so rounded corners
 * have to be baked into the bitmap before it crosses into the launcher. Doing
 * the downscale in the same pass keeps the parcelled bitmap small, which
 * matters: widget RemoteViews are size-capped and album art arrives at whatever
 * resolution the source app felt like.
 */
object ArtworkTools {

    /**
     * Centre-crops [source] to a square, scales it to [sizePx], and rounds the
     * corners by [radiusPx].
     */
    fun roundedSquare(source: Bitmap, sizePx: Int, radiusPx: Float): Bitmap {
        val size = max(1, sizePx)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        // Cover, not fit: scale by the larger ratio and centre the overflow.
        val scale = max(size.toFloat() / source.width, size.toFloat() / source.height)
        val dx = (size - source.width * scale) / 2f
        val dy = (size - source.height * scale) / 2f
        shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            isFilterBitmap = true
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            radiusPx,
            radiusPx,
            paint
        )
        return output
    }

    /**
     * Some apps hand back enormous cover art. Anything much beyond the size we
     * draw at is wasted decode and wasted parcel, so cap it early.
     */
    fun downscaleIfHuge(source: Bitmap, maxDimen: Int): Bitmap {
        val software = source.toSoftware()
        val largest = max(software.width, software.height)
        if (largest <= maxDimen) return software
        val scale = maxDimen.toFloat() / largest
        return Bitmap.createScaledBitmap(
            software,
            max(1, (software.width * scale).toInt()),
            max(1, (software.height * scale).toInt()),
            true
        )
    }

    // ---- pulling art out of whatever an app happened to publish -----------

    private const val TAG = "ArtworkTools"

    /** Cap on decoded art. Well above the 94dp we draw at, even on a 4x display. */
    private const val DECODE_MAX_PX = 512

    /** Embedded bitmaps, best first. */
    private val BITMAP_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON
    )

    /** The same three as references. Plenty of apps only ever set these. */
    private val URI_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
    )

    private val uriCache = object : LruCache<String, Bitmap>(4) {}

    /**
     * The album art for a session, from whichever field the app chose to use.
     *
     * Apps are wildly inconsistent here. Spotify embeds a bitmap; SoundCloud
     * publishes only a URI and leaves every bitmap key null. Reading just the
     * bitmap keys - which is the obvious implementation - therefore works for
     * some apps and silently shows the empty panel for others.
     *
     * Remote http(s) art is deliberately **not** fetched: the app holds no
     * INTERNET permission and nothing it does leaves the device, which is worth
     * more than cover art for the minority of apps that only offer a URL.
     */
    fun fromMetadata(context: Context, metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null

        for (key in BITMAP_KEYS) {
            metadata.getBitmap(key)?.let { return it.toSoftware() }
        }

        for (key in URI_KEYS) {
            val raw = metadata.getString(key)
            if (raw.isNullOrBlank()) continue
            decodeUri(context, raw)?.let { return it }
        }
        return null
    }

    private fun decodeUri(context: Context, raw: String): Bitmap? {
        uriCache.get(raw)?.let { if (!it.isRecycled) return it }

        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                Log.i(TAG, "Art for this track is remote ($raw); not fetched - no INTERNET permission")
                return null
            }
            null -> return null
        }

        val decoded = runCatching {
            // Two passes: measure, then decode subsampled, so a 3000px cover
            // does not land in memory at full size just to be shrunk.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val largest = max(bounds.outWidth, bounds.outHeight)
            if (largest <= 0) return@runCatching null

            var sample = 1
            while (largest / sample > DECODE_MAX_PX) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.onFailure {
            // Another app's provider is entitled to refuse us; that is a missing
            // cover, not an error worth shouting about.
            Log.i(TAG, "Could not read art at $raw: ${it.message}")
        }.getOrNull() ?: return null

        uriCache.put(raw, decoded)
        return decoded
    }

    /**
     * A HARDWARE-config bitmap has no pixels we can read, so it cannot be
     * scaled, shaded or re-encoded. Apps do hand these out. Copy to a software
     * config before anything tries to touch it.
     */
    private fun Bitmap.toSoftware(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false) ?: this
        } else {
            this
        }
}
