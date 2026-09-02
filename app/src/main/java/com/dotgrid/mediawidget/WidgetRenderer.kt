package com.dotgrid.mediawidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a [PlaybackSnapshot] into RemoteViews.
 *
 * Two layouts exist and both carry the same view ids, so everything below is
 * written once and applied to whichever is on screen.
 *
 * All text is drawn to bitmaps by [TextRenderer] rather than set on TextViews.
 * That is not a stylistic choice - a widget cannot resolve `android:fontFamily`
 * at all, because AppWidgetHostView inflates through a CONTEXT_RESTRICTED
 * context and TextView skips font resources when the context is restricted.
 */
object WidgetRenderer {

    /** Tap regions over the scrub bar. Mirrors res/layout/seek_strip.xml. */
    val SEEK_IDS = intArrayOf(
        R.id.seek_00, R.id.seek_01, R.id.seek_02, R.id.seek_03,
        R.id.seek_04, R.id.seek_05, R.id.seek_06, R.id.seek_07,
        R.id.seek_08, R.id.seek_09, R.id.seek_10, R.id.seek_11,
        R.id.seek_12, R.id.seek_13, R.id.seek_14, R.id.seek_15
    )

    // Mirrors of the layout metrics. Kept here rather than read back from
    // resources because the bar bitmap has to be rendered at its on-screen
    // width - a dot rail stretched by fitXY turns into an ellipse rail.
    private const val PADDING_DP = 12f
    private const val ART_DP = 94f
    private const val ART_GAP_DP = 12f
    private const val BAR_MARGIN_DP = 8f
    private const val BAR_HEIGHT_DP = 12f

    private const val COMPACT_PADDING_DP = 8f
    private const val COMPACT_ART_DP = 86f
    private const val COMPACT_ART_GAP_DP = 10f
    private const val COMPACT_BAR_HEIGHT_DP = 10f

    /** Source-app mark. Must match the ImageView sizes in the two layouts. */
    private const val GLYPH_DP = 20f
    private const val COMPACT_GLYPH_DP = 15f

    /**
     * source_switcher's own chrome - marginStart + paddingStart + paddingEnd
     * in the layout XML, not counting the glyph itself. Added to the glyph's
     * width in glyphSpacePx so a title/status label can never be given a
     * width budget wide enough to push the switcher past the card's edge.
     * Must match source_switcher's layout_marginStart/paddingStart/paddingEnd
     * in the corresponding XML - update this alongside any change there.
     */
    private const val SWITCHER_CHROME_DP = 20f          // widget_media.xml: 4 + 16 + 0
    private const val COMPACT_SWITCHER_CHROME_DP = 15f  // widget_media_compact.xml: 2 + 13 + 0

    /** source_dots' own marginEnd in the two layouts. */
    private const val DOTS_GAP_DP = 7f
    private const val COMPACT_DOTS_GAP_DP = 5f

    /** Carousel page indicator, one dot per source. */
    private const val SOURCE_DOT_DP = 4f
    private const val SOURCE_DOT_GAP_DP = 3f
    private const val COMPACT_SOURCE_DOT_DP = 3.5f
    private const val COMPACT_SOURCE_DOT_GAP_DP = 2.5f

    /** Indent of the artist line under the title in the compact layout. */
    private const val COMPACT_TEXT_INDENT_DP = 11f

    // Square, not rounded: album art is a square object and the tile lets it be
    // one. The crop still runs - only the corner radius is gone.
    private const val ART_CORNER_DP = 0f
    private const val COMPACT_ART_CORNER_DP = 0f

    // Type scale, in sp. Converted with the display density at render time.
    private const val STATUS_SP = 10f
    private const val TIME_SP = 10f
    private const val TITLE_SP = 16f
    private const val ARTIST_SP = 12f
    private const val COMPACT_TITLE_SP = 13f
    private const val COMPACT_ARTIST_SP = 11f

    /**
     * Small caps at 10sp need the air or they close up into a bar. NType 82
     * takes tracking that varies with size - it is NDot that takes none at all
     * - and this is the value both sibling tiles set on their own small-caps
     * labels.
     */
    private const val STATUS_TRACKING = 0.12f

    /** Below this height the tile is two cells tall and gets the sideways layout. */
    private const val COMPACT_BELOW_HEIGHT_DP = 150

    fun build(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: PlaybackSnapshot
    ): RemoteViews {
        val options: Bundle? = runCatching {
            appWidgetManager.getAppWidgetOptions(appWidgetId)
        }.getOrNull()

        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            ?.takeIf { it > 0 } ?: 320
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            ?.takeIf { it > 0 } ?: 180

        // One variant, not a RemoteViews(Map<SizeF, ...>) carrying both. Every
        // label is a bitmap now, and two full sets plus two pieces of artwork
        // run the update at the 1MB binder limit. onAppWidgetOptionsChanged
        // repaints on resize, which costs one round trip and cannot overflow.
        return variant(context, snapshot, widthDp, compact = heightDp < COMPACT_BELOW_HEIGHT_DP)
    }

    private fun variant(
        context: Context,
        snapshot: PlaybackSnapshot,
        widgetWidthDp: Int,
        compact: Boolean
    ): RemoteViews {
        val layout = if (compact) R.layout.widget_media_compact else R.layout.widget_media
        val views = RemoteViews(context.packageName, layout)
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = max(1, (dp * density).roundToInt())
        fun sp(value: Float) = value * density

        val white = context.getColor(R.color.text_primary)
        val secondary = context.getColor(R.color.text_secondary)
        val tertiary = context.getColor(R.color.text_tertiary)
        val active = context.getColor(R.color.track_active)
        val inactive = context.getColor(R.color.track_inactive)
        val red = context.getColor(R.color.nt_red)

        // ---- text -------------------------------------------------------
        val statusText = when {
            !snapshot.hasAccess -> context.getString(R.string.label_no_access)
            snapshot.resumable -> context.getString(R.string.label_resume)
            !snapshot.hasSession -> context.getString(R.string.label_idle)
            snapshot.isPlaying -> context.getString(R.string.label_now_playing)
            else -> context.getString(R.string.label_paused)
        }

        // A resumable frame carries real metadata from the cache, so it is shown
        // as-is; only a genuinely empty state falls back to placeholder copy.
        val hasContent = snapshot.hasSession || snapshot.resumable
        val title = when {
            !snapshot.hasAccess -> context.getString(R.string.no_access_title)
            hasContent -> snapshot.title
            else -> context.getString(R.string.idle_title)
        }
        val artist = when {
            !snapshot.hasAccess -> context.getString(R.string.no_access_artist)
            hasContent -> snapshot.artist
            else -> context.getString(R.string.idle_artist)
        }

        // Width left for the metadata column, so a long title is ellipsised to
        // the space it actually has rather than clipped by the launcher.
        val artDp = if (compact) COMPACT_ART_DP else ART_DP
        val padDp = if (compact) COMPACT_PADDING_DP else PADDING_DP
        val gapDp = if (compact) COMPACT_ART_GAP_DP else ART_GAP_DP
        val metaWidthPx = px((widgetWidthDp - padDp * 2 - artDp - gapDp).coerceAtLeast(72f))
        val indentPx = if (compact) px(COMPACT_TEXT_INDENT_DP) else 0

        // The source-app mark shares a row with the status label at full size and
        // with the title when compact, so whichever it sits beside has to give up
        // the space or the two will meet in the middle. The carousel dots sit in
        // the same cluster, so they come out of the same budget.
        val dotPx = px(if (compact) COMPACT_SOURCE_DOT_DP else SOURCE_DOT_DP)
        val dotGapPx = px(if (compact) COMPACT_SOURCE_DOT_GAP_DP else SOURCE_DOT_GAP_DP)
        val dotsWidthPx = SourceDots.widthPx(snapshot.sourceCount, dotPx, dotGapPx)
        val dotsSpacePx = if (dotsWidthPx > 0) {
            dotsWidthPx + px(if (compact) COMPACT_DOTS_GAP_DP else DOTS_GAP_DP)
        } else {
            0
        }

        val glyphSpacePx =
            px(if (compact) COMPACT_GLYPH_DP + COMPACT_SWITCHER_CHROME_DP
               else GLYPH_DP + SWITCHER_CHROME_DP) + dotsSpacePx
        val statusWidthPx = max(px(48f), metaWidthPx - indentPx - if (compact) 0 else glyphSpacePx)
        val nameWidthPx = max(px(48f), metaWidthPx - indentPx - if (compact) glyphSpacePx else 0)

        // Idle is the one state where the status label and the title would say
        // the same words, so the label stands down and the title says it once.
        val idle = snapshot.hasAccess && !hasContent
        if (idle) {
            views.setViewVisibility(R.id.status_label, View.GONE)
        } else {
            views.setViewVisibility(R.id.status_label, View.VISIBLE)
            views.setImageViewBitmap(
                R.id.status_label,
                TextRenderer.render(
                    context, statusText, Typography.ACCENT, sp(STATUS_SP),
                    if (snapshot.isPlaying) secondary else tertiary,
                    maxWidthPx = statusWidthPx, letterSpacingEm = STATUS_TRACKING
                )
            )
            views.setContentDescription(R.id.status_label, statusText)
        }

        // NType82 is the general-purpose face per Nothing's guidelines - NDot
        // is scoped to product names and the logotype, not arbitrary body
        // text, and a track title is exactly that. But NType82 covers only
        // Latin (~240 codepoints) and titles arrive from whichever app is
        // playing, in whatever script it used, so Ndot 77 JP Extended
        // (~21,000 codepoints) stands in as a coverage fallback for the rare
        // string NType82 cannot render, rather than the default face.
        views.setImageViewBitmap(
            R.id.track_title,
            TextRenderer.render(
                context, title, Typography.BODY,
                sp(if (compact) COMPACT_TITLE_SP else TITLE_SP), white,
                maxWidthPx = nameWidthPx, fallbackFontRes = Typography.COVERAGE_FALLBACK
            )
        )
        views.setContentDescription(R.id.track_title, title)

        // An app that reports no artist gets no artist line, rather than a blank
        // one holding space open under the title.
        if (artist.isBlank()) {
            views.setViewVisibility(R.id.track_artist, View.GONE)
        } else {
            views.setViewVisibility(R.id.track_artist, View.VISIBLE)
            views.setImageViewBitmap(
                R.id.track_artist,
                TextRenderer.render(
                    context, artist, Typography.BODY,
                    sp(if (compact) COMPACT_ARTIST_SP else ARTIST_SP), secondary,
                    maxWidthPx = nameWidthPx, fallbackFontRes = Typography.COVERAGE_FALLBACK
                )
            )
            views.setContentDescription(R.id.track_artist, artist)
        }

        // The live dot is the only red in the widget besides the playhead, and
        // it means exactly one thing: audio is moving right now.
        views.setViewVisibility(
            R.id.live_dot,
            if (snapshot.isPlaying) View.VISIBLE else View.INVISIBLE
        )

        // ---- source app --------------------------------------------------
        // Monochrome, so the one saturated thing on the card is never a
        // third-party logo. Hidden outright when there is nothing to name.
        val glyph = if (hasContent) {
            AppGlyph.render(
                context,
                snapshot.packageName,
                px(if (compact) COMPACT_GLYPH_DP else GLYPH_DP),
                context.getColor(R.color.app_glyph)
            )
        } else {
            null
        }
        if (glyph != null) {
            views.setImageViewBitmap(R.id.app_glyph, glyph)
            views.setViewVisibility(R.id.app_glyph, View.VISIBLE)
            AppGlyph.label(context, snapshot.packageName)?.let {
                views.setContentDescription(R.id.app_glyph, it)
            }
        } else {
            views.setViewVisibility(R.id.app_glyph, View.GONE)
        }

        // ---- carousel ----------------------------------------------------
        // Only drawn when there is somewhere to page to: with one source the
        // dots would be a control that does nothing, which reads worse than
        // no control at all.
        val dots = SourceDots.render(
            count = snapshot.sourceCount,
            index = snapshot.sourceIndex,
            dotPx = dotPx,
            gapPx = dotGapPx,
            activeColor = white,
            inactiveColor = inactive
        )
        if (dots != null) {
            views.setImageViewBitmap(R.id.source_dots, dots)
            views.setViewVisibility(R.id.source_dots, View.VISIBLE)
            views.setContentDescription(
                R.id.source_switcher,
                context.getString(
                    R.string.cd_next_source,
                    snapshot.sourceIndex + 1,
                    snapshot.sourceCount
                )
            )
        } else {
            views.setViewVisibility(R.id.source_dots, View.GONE)
        }

        // ---- artwork ----------------------------------------------------
        val cornerDp = if (compact) COMPACT_ART_CORNER_DP else ART_CORNER_DP
        val art = snapshot.artwork
        if (art != null && !art.isRecycled) {
            val prepared = ArtworkTools.roundedSquare(
                ArtworkTools.downscaleIfHuge(art, px(artDp)),
                px(artDp),
                cornerDp * density
            )
            views.setImageViewBitmap(R.id.album_art, prepared)
            views.setViewVisibility(R.id.album_art, View.VISIBLE)
            views.setViewVisibility(R.id.art_fallback, View.GONE)
        } else {
            views.setViewVisibility(R.id.album_art, View.GONE)
            views.setViewVisibility(R.id.art_fallback, View.VISIBLE)
        }

        // ---- transport --------------------------------------------------
        views.setImageViewResource(
            R.id.btn_play,
            if (snapshot.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        // Skip keys dim when the session says it cannot honour them - and always
        // in the resume state, where there is no session to skip within. Play
        // stays lit there, because it is the one key that still does something.
        views.setInt(R.id.btn_next, "setImageAlpha", if (snapshot.canSkipNext) 255 else 70)
        views.setInt(R.id.btn_prev, "setImageAlpha", if (snapshot.canSkipPrevious) 255 else 70)
        views.setInt(R.id.btn_play, "setImageAlpha", if (hasContent) 255 else 70)

        // ---- progress ---------------------------------------------------
        val elapsedText = ProgressRenderer.formatTime(snapshot.positionMs)
        val totalText = if (snapshot.durationMs > 0L) {
            ProgressRenderer.formatTime(snapshot.durationMs)
        } else {
            "--:--"
        }

        views.setImageViewBitmap(
            R.id.time_elapsed,
            TextRenderer.renderTimeCode(
                context, elapsedText, Typography.ACCENT, sp(TIME_SP), secondary
            )
        )
        views.setContentDescription(R.id.time_elapsed, elapsedText)
        views.setImageViewBitmap(
            R.id.time_total,
            TextRenderer.renderTimeCode(
                context, totalText, Typography.ACCENT, sp(TIME_SP), tertiary
            )
        )
        views.setContentDescription(R.id.time_total, totalText)

        val barWidthDp = if (compact) {
            // No time codes at this size, so the rail gets the whole column.
            widgetWidthDp - COMPACT_PADDING_DP * 2 - COMPACT_ART_DP - COMPACT_ART_GAP_DP
        } else {
            // Measure the labels with the same face and size they are drawn at,
            // otherwise the rail is built at the wrong width and fitXY stretches
            // its dots into ellipses.
            val elapsedDp = TextRenderer
                .timeCodeWidthPx(context, elapsedText, Typography.ACCENT, sp(TIME_SP)) / density
            val totalDp = TextRenderer
                .timeCodeWidthPx(context, totalText, Typography.ACCENT, sp(TIME_SP)) / density
            widgetWidthDp - PADDING_DP * 2 - BAR_MARGIN_DP * 2 - elapsedDp - totalDp
        }.coerceAtLeast(48f)

        val barHeightDp = if (compact) COMPACT_BAR_HEIGHT_DP else BAR_HEIGHT_DP

        views.setImageViewBitmap(
            R.id.progress_bar,
            ProgressRenderer.render(
                widthPx = px(barWidthDp),
                heightPx = px(barHeightDp),
                // A resumable frame shows where the track was left, dimmed: the
                // rail reads as a memory rather than as a live position.
                fraction = if (hasContent) snapshot.fraction else 0f,
                activeColor = if (snapshot.hasSession) active else inactive,
                inactiveColor = inactive,
                headColor = if (snapshot.isPlaying) red else white
            )
        )

        wireClicks(context, views, snapshot)
        return views
    }

    private fun wireClicks(context: Context, views: RemoteViews, snapshot: PlaybackSnapshot) {
        if (!snapshot.hasAccess) {
            // Nothing is controllable yet - every target becomes "fix this".
            val setup = activityIntent(context)
            views.setOnClickPendingIntent(R.id.widget_root, setup)
            views.setOnClickPendingIntent(R.id.btn_play, setup)
            views.setOnClickPendingIntent(R.id.btn_next, setup)
            views.setOnClickPendingIntent(R.id.btn_prev, setup)
            SEEK_IDS.forEach { views.setOnClickPendingIntent(it, setup) }
            return
        }

        val pkg = snapshot.packageName

        // With no live session, play means "start the last thing again" - a
        // different action, because there is no controller to send play() to.
        val playAction = if (snapshot.resumable) {
            MediaWidgetProvider.ACTION_RESUME
        } else {
            MediaWidgetProvider.ACTION_PLAY_PAUSE
        }
        views.setOnClickPendingIntent(R.id.btn_play, command(context, playAction, 1, pkg))
        views.setContentDescription(
            R.id.btn_play,
            context.getString(
                if (snapshot.resumable) R.string.cd_resume else R.string.cd_play_pause
            )
        )

        views.setOnClickPendingIntent(
            R.id.btn_next,
            command(context, MediaWidgetProvider.ACTION_NEXT, 2, pkg)
        )
        views.setOnClickPendingIntent(
            R.id.btn_prev,
            command(context, MediaWidgetProvider.ACTION_PREVIOUS, 3, pkg)
        )

        // Tapping the card opens whatever is playing - or was playing; with
        // nothing remembered it opens our own screen, so the widget is never a
        // dead end.
        val openTarget = pkg?.let { launchIntent(context, it) } ?: activityIntent(context)
        views.setOnClickPendingIntent(R.id.widget_root, openTarget)

        // The switcher sits inside widget_root, so it always needs its own
        // handler - without one the tap falls through to the card and opens
        // the app instead. With a single source it deliberately gets the same
        // target as the card, so the mark keeps behaving like part of it.
        views.setOnClickPendingIntent(
            R.id.source_switcher,
            if (snapshot.sourceCount > 1) {
                command(context, MediaWidgetProvider.ACTION_NEXT_SOURCE, 4, pkg)
            } else {
                openTarget
            }
        )

        SEEK_IDS.forEachIndexed { index, id ->
            if (!snapshot.canSeek) {
                views.setOnClickPendingIntent(id, openTarget)
                return@forEachIndexed
            }
            // Aim at the centre of the region, so a tap lands where it looks
            // like it should rather than at the region's leading edge.
            val fraction = (index + 0.5f) / SEEK_IDS.size
            views.setOnClickPendingIntent(
                id,
                command(context, MediaWidgetProvider.ACTION_SEEK, 100 + index, pkg) { intent ->
                    intent.putExtra(MediaWidgetProvider.EXTRA_SEEK_FRACTION, fraction)
                }
            )
        }
    }

    private fun command(
        context: Context,
        action: String,
        requestCode: Int,
        packageName: String?,
        extras: (Intent) -> Unit = {}
    ): PendingIntent {
        val intent = Intent(context, MediaWidgetProvider::class.java).apply {
            this.action = action
            // PendingIntent equality ignores extras, so the discriminator has to
            // live somewhere it does look: the data URI (and the request code).
            data = Uri.parse("dotgrid://action/$action/$requestCode")
            packageName?.let { putExtra(MediaWidgetProvider.EXTRA_TARGET_PACKAGE, it) }
            extras(this)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun activityIntent(context: Context): PendingIntent {
        val intent = Intent(context, SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 5, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun launchIntent(context: Context, packageName: String): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return activityIntent(context)
        return PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * The full-size variant, for inflating in-process on the setup screen.
     * Deliberately the same code path as the real widget - if the preview looks
     * right, the widget looks right.
     */
    fun buildPreview(
        context: Context,
        snapshot: PlaybackSnapshot,
        widthDp: Int = 320
    ): RemoteViews = variant(context, snapshot, widthDp, compact = false)

    /** Pushes a fresh frame to every instance of the widget. */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MediaWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val snapshot = MediaHub.snapshot(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, build(context, manager, id, snapshot))
        }
    }

    /** True when at least one instance is on a home screen. */
    fun hasInstances(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager
            .getAppWidgetIds(ComponentName(context, MediaWidgetProvider::class.java))
            .isNotEmpty()
    }
}
