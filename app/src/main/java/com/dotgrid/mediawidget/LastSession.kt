package com.dotgrid.mediawidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlin.math.abs

/**
 * Remembers the last thing that was playing, so the widget has something to show
 * - and something to restart - once the app that owned the session has gone.
 *
 * A media session disappears the moment its app is swept from recents, taking
 * the artwork and metadata with it. Without this the tile falls back to "nothing
 * playing", which is true but useless: the thing the user most likely wants is
 * the thing they were just listening to.
 */
object LastSession {

    private const val TAG = "LastSession"
    private const val PREFS = "last_session"
    private const val ART_FILE = "last_art.webp"

    private const val KEY_PACKAGE = "package"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_POSITION = "position"
    private const val KEY_DURATION = "duration"
    private const val KEY_IDENTITY = "identity"
    private const val KEY_SAVED_AT = "saved_at"

    /** Artwork is re-encoded to at most this on its long edge before being stored. */
    private const val ART_MAX_PX = 512

    /** Position moves constantly; only write it back when it has moved this far. */
    private const val POSITION_WRITE_STEP_MS = 3_000L

    /**
     * Process-lifetime memo of what is already on disk, so a 1 Hz repaint does
     * not turn into a 1 Hz write. Losing it on process death costs one
     * redundant save.
     */
    private var writtenIdentity: String? = null
    private var writtenPosition = 0L

    data class Cached(
        val packageName: String,
        val title: String,
        val artist: String,
        val positionMs: Long,
        val durationMs: Long,
        val identity: String,
        val artwork: Bitmap?
    )

    /**
     * Records a live snapshot. Cheap to call on every repaint: the metadata is
     * only written when the track actually changes, and the artwork only when
     * its identity does.
     */
    fun save(context: Context, snapshot: PlaybackSnapshot) {
        if (!snapshot.hasSession) return
        val pkg = snapshot.packageName ?: return

        val identity = snapshot.artworkKey
        val trackChanged = identity != writtenIdentity
        val moved = abs(snapshot.positionMs - writtenPosition) >= POSITION_WRITE_STEP_MS
        if (!trackChanged && !moved) return

        if (trackChanged) writeArtwork(context, snapshot.artwork)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, pkg)
            .putString(KEY_TITLE, snapshot.title)
            .putString(KEY_ARTIST, snapshot.artist)
            .putLong(KEY_POSITION, snapshot.positionMs)
            .putLong(KEY_DURATION, snapshot.durationMs)
            .putString(KEY_IDENTITY, identity)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()

        writtenIdentity = identity
        writtenPosition = snapshot.positionMs
    }

    fun load(context: Context): Cached? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null) ?: return null
        val title = prefs.getString(KEY_TITLE, null) ?: return null

        // An app that has since been uninstalled is not resumable.
        if (!isInstalled(context, pkg)) {
            clear(context)
            return null
        }

        return Cached(
            packageName = pkg,
            title = title,
            artist = prefs.getString(KEY_ARTIST, "").orEmpty(),
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            durationMs = prefs.getLong(KEY_DURATION, 0L),
            identity = prefs.getString(KEY_IDENTITY, pkg).orEmpty(),
            artwork = readArtwork(context)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { File(context.filesDir, ART_FILE).delete() }
        writtenIdentity = null
        writtenPosition = 0L
    }

    private fun writeArtwork(context: Context, art: Bitmap?) {
        val file = File(context.filesDir, ART_FILE)
        if (art == null || art.isRecycled) {
            runCatching { file.delete() }
            return
        }
        runCatching {
            val scaled = ArtworkTools.downscaleIfHuge(art, ART_MAX_PX)
            file.outputStream().use { out ->
                @Suppress("DEPRECATION")
                val format = Bitmap.CompressFormat.WEBP
                scaled.compress(format, 90, out)
            }
        }.onFailure { Log.w(TAG, "Could not store artwork", it) }
    }

    private fun readArtwork(context: Context): Bitmap? {
        val file = File(context.filesDir, ART_FILE)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: Exception) {
        // Also reached when package visibility hides the app from us, in which
        // case we genuinely cannot drive it and should not offer to.
        false
    }
}
