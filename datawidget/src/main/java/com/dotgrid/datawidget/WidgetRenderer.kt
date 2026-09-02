package com.dotgrid.datawidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns an [UsageSnapshot] into RemoteViews.
 *
 * All text is drawn to bitmaps by [TextRenderer] rather than set on TextViews.
 * That is not a stylistic choice - a widget cannot resolve `android:fontFamily`
 * at all, because AppWidgetHostView inflates through a CONTEXT_RESTRICTED
 * context and TextView skips font resources when the context is restricted.
 */
object WidgetRenderer {

    /** Mirrors android:padding on widget_data.xml. The dial is drawn to what is left. */
    private const val PADDING_DP = 10f

    /** The 2 x 2 target: two 70dp cells less the 30dp the grid takes back. */
    private const val BASE_TILE_DP = 110f

    /**
     * Type scale at the 2 x 2 size, in sp. The tile is resizable, so these are
     * multiplied by a scale factor rather than used raw.
     */
    private const val VALUE_SP = 22f
    private const val UNIT_SP = 9f
    private const val LINE_SP = 8f
    private const val READOUT_GAP_SP = 3f

    /** Small caps need tracking to stay legible against a big tabular figure. */
    private const val LINE_TRACKING = 0.14f

    /** Matches the tracking renderReadout sets on the unit, so measuring agrees with drawing. */
    private const val UNIT_TRACKING = 0.10f

    /*
     * How much width each of the three lines actually has.
     *
     * The readout sits inside a circle, not a box, so the budget is not one
     * number - it narrows the further a line sits from the dial's middle. These
     * are the chord across the dial's clear inner radius at each line's own
     * band, taken from the rendered layout at the 2 x 2 size and divided by the
     * 90dp content square:
     *
     *   readout   -22dp .. +2dp from centre   64.5dp clear   -> 0.71
     *   limit     +4dp  .. +12dp              74.3dp clear   -> 0.80
     *   cycle     +14dp .. +22dp              64.5dp clear   -> 0.71
     *
     * Without them the type is measured against the tile and merely looks like
     * it fits: "LIMIT 1100 GB" and "TAP TO GRANT" both run into the arc.
     */
    private const val READOUT_WIDTH_RATIO = 0.71f
    private const val LIMIT_WIDTH_RATIO = 0.80f
    private const val CYCLE_WIDTH_RATIO = 0.71f

    fun build(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: UsageSnapshot
    ): RemoteViews {
        val options = runCatching {
            appWidgetManager.getAppWidgetOptions(appWidgetId)
        }.getOrNull()

        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            ?.takeIf { it > 0 } ?: BASE_TILE_DP.toInt()
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            ?.takeIf { it > 0 } ?: BASE_TILE_DP.toInt()

        // The dial is a circle, so the tile's short side is the whole budget.
        return paint(context, snapshot, min(widthDp, heightDp).toFloat())
    }

    /**
     * The same RemoteViews the launcher gets, at the 2 x 2 size, for the
     * configuration screen to show live. Rendering the preview through this
     * path rather than a mock-up means a bug in the tile shows up on the
     * settings screen too, instead of only on a home screen.
     */
    fun buildPreview(context: Context, snapshot: UsageSnapshot): RemoteViews =
        paint(context, snapshot, BASE_TILE_DP)

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context, DataWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return

        // One read for every tile on the home screen: the query is the
        // expensive part and they are all showing the same plan.
        val snapshot = UsageSnapshot.read(context)
        ids.forEach { id -> manager.updateAppWidget(id, build(context, manager, id, snapshot)) }
    }

    private fun paint(
        context: Context,
        snapshot: UsageSnapshot,
        tileDp: Float
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_data)
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = max(1, (dp * density).roundToInt())

        /*
         * Type grows with the tile, but slower than the tile does and never
         * below its 2 x 2 size. A widget dragged out to 4 x 4 should read as
         * the same object seen larger, not as the same 8sp caps marooned in a
         * field of black.
         */
        val typeScale = (tileDp / BASE_TILE_DP).coerceIn(1f, 1.9f)
        fun sp(value: Float) = value * density * typeScale

        val squarePx = px(tileDp - PADDING_DP * 2)

        val white = context.getColor(R.color.text_primary)
        val secondary = context.getColor(R.color.text_secondary)
        val tertiary = context.getColor(R.color.text_tertiary)
        val meterActive = context.getColor(R.color.meter_active)
        val meterInactive = context.getColor(R.color.meter_inactive)

        // ---- the dial ---------------------------------------------------
        views.setImageViewBitmap(
            R.id.meter,
            MeterRenderer.render(
                sizePx = squarePx,
                fraction = if (snapshot.bytes == null) null else snapshot.fraction,
                activeColor = if (snapshot.styled(DataSettings.STYLE_RING)) {
                    snapshot.alertColor
                } else {
                    meterActive
                },
                inactiveColor = meterInactive
            )
        )

        // ---- the readout ------------------------------------------------
        val bytes = snapshot.bytes
        val value: String
        val unit: String
        if (bytes == null) {
            value = context.getString(R.string.value_unknown)
            unit = ""
        } else {
            val formatted = UsageSnapshot.format(bytes)
            value = formatted.first
            unit = formatted.second
        }

        val valueColor = if (snapshot.styled(DataSettings.STYLE_VALUE)) {
            snapshot.alertColor
        } else {
            white
        }

        /*
         * Without usage access there is no reading to place-hold: a dash where
         * the figure goes is an instrument saying "not reading right now",
         * which is the wrong sentence. Drop the line entirely and let the two
         * small ones centre in the dial, so the state reads as deliberate
         * rather than as a tile that failed to draw.
         *
         * A failed query with access granted is the other case, and there the
         * dash is exactly right - the number exists, we just could not get it.
         */
        views.setViewVisibility(
            R.id.usage_readout,
            if (snapshot.hasAccess) View.VISIBLE else View.GONE
        )

        if (snapshot.hasAccess) {
            val valuePx = fitReadout(
                context, value, unit, sp(VALUE_SP), (squarePx * READOUT_WIDTH_RATIO).roundToInt()
            )

            views.setImageViewBitmap(
                R.id.usage_readout,
                TextRenderer.renderReadout(
                    context = context,
                    value = value,
                    valueSizePx = valuePx,
                    valueColor = valueColor,
                    unit = unit,
                    // The unit keeps its proportion to the value when the value
                    // has been shrunk to fit, so a long figure does not leave an
                    // oversized "GB" hanging off it.
                    unitSizePx = valuePx * (UNIT_SP / VALUE_SP),
                    unitColor = if (bytes == null) tertiary else secondary,
                    gapPx = valuePx * (READOUT_GAP_SP / VALUE_SP),
                    pillColor = pillColorFor(context, valueColor)
                )
            )
        }

        // ---- the two small lines ----------------------------------------
        val limitLine = when {
            !snapshot.hasAccess -> context.getString(R.string.no_access_line)
            !snapshot.hasLimit -> context.getString(R.string.no_limit)
            else -> context.getString(
                R.string.limit_format,
                UsageSnapshot.formatLimit(snapshot.limitMb)
            )
        }

        val cycleLine = when {
            !snapshot.hasAccess -> context.getString(R.string.no_access_hint)
            snapshot.daysLeft <= 0 -> context.getString(R.string.rolls_today)
            else -> context.getString(R.string.days_left_format, snapshot.daysLeft)
        }

        /*
         * Which face these two lines take depends on what they are saying.
         *
         * With access granted they are reporting the plan - "LIMIT 20 GB",
         * "12D LEFT" - which are data readouts and take the body face. Without
         * it the tile has no reading to give and is describing itself instead,
         * and "TAP TO GRANT" is a status indicator. See Typography.
         *
         * The colour on the limit line already switches for exactly this state,
         * so the face switching with it is a distinction the tile was drawing
         * anyway.
         */
        val lineFont = if (snapshot.hasAccess) Typography.BODY else Typography.ACCENT

        // Missing access is the one thing on this tile the user has to act
        // on, so it borrows the accent rather than sitting quietly in
        // secondary grey with the ordinary labels.
        val limitLineColor = if (snapshot.hasAccess) secondary else snapshot.alertColor
        views.setImageViewBitmap(
            R.id.limit_line,
            TextRenderer.render(
                context,
                limitLine,
                lineFont,
                fitLine(
                    context, limitLine, lineFont, sp(LINE_SP),
                    (squarePx * LIMIT_WIDTH_RATIO).roundToInt()
                ),
                limitLineColor,
                LINE_TRACKING,
                pillColorFor(context, limitLineColor)
            )
        )
        views.setImageViewBitmap(
            R.id.cycle_line,
            TextRenderer.render(
                context,
                cycleLine,
                lineFont,
                fitLine(
                    context, cycleLine, lineFont, sp(LINE_SP),
                    (squarePx * CYCLE_WIDTH_RATIO).roundToInt()
                ),
                tertiary,
                LINE_TRACKING
            )
        )

        // ---- the indicator ----------------------------------------------
        val showDot = snapshot.styled(DataSettings.STYLE_DOT)
        views.setViewVisibility(R.id.alert_dot, if (showDot) View.VISIBLE else View.GONE)
        if (showDot) {
            // The drawable is white; the accent arrives as a filter, so one
            // shape covers every colour the user can choose.
            views.setInt(R.id.alert_dot, "setColorFilter", snapshot.alertColor)
        }

        val showBorder = snapshot.styled(DataSettings.STYLE_CARD)
        views.setViewVisibility(R.id.alert_border, if (showBorder) View.VISIBLE else View.GONE)
        if (showBorder) {
            views.setInt(R.id.alert_border, "setColorFilter", snapshot.alertColor)
        }

        // ---- the tap ----------------------------------------------------
        views.setOnClickPendingIntent(R.id.widget_root, configIntent(context))

        return views
    }

    /**
     * Shrinks the figure until it clears the dial.
     *
     * "1024" beside "GB" is half again as wide as "8.4", and the dial cannot
     * grow to meet it - so the type gives way instead. Stepping down rather
     * than scaling in one go keeps the common case untouched: a figure that
     * already fits is returned at full size on the first pass.
     */
    private fun fitReadout(
        context: Context,
        value: String,
        unit: String,
        startPx: Float,
        maxWidthPx: Int
    ): Float = shrinkToFit(startPx) { size ->
        val unitSize = size * (UNIT_SP / VALUE_SP)
        TextRenderer.widthPx(context, value, Typography.BODY, size) +
            TextRenderer.widthPx(context, unit, Typography.BODY, unitSize, UNIT_TRACKING) +
            size * (READOUT_GAP_SP / VALUE_SP) <= maxWidthPx
    }

    /**
     * The same for one of the small caps lines.
     *
     * These are shorter than the readout but sit nearer the bottom of the dial,
     * where the circle has closed in - and the widest of them are the ones the
     * user never chose: "LIMIT 1100 GB" for a big plan, "TAP TO GRANT" when
     * there is nothing else to say. A translation can be wider still.
     */
    private fun fitLine(
        context: Context,
        text: String,
        fontRes: Int,
        startPx: Float,
        maxWidthPx: Int
    ): Float = shrinkToFit(startPx) { size ->
        TextRenderer.widthPx(context, text, fontRes, size, LINE_TRACKING) <= maxWidthPx
    }

    /** Steps down in twentieths, to a floor of 60%, and stops as soon as [fits]. */
    private inline fun shrinkToFit(startPx: Float, fits: (Float) -> Boolean): Float {
        val floor = startPx * 0.6f
        var size = startPx
        while (size > floor) {
            if (fits(size)) return size
            size -= startPx * 0.05f
        }
        return floor
    }

    /**
     * Whether the light-mode tile is on screen right now.
     *
     * `WidgetRenderer` has no Activity to ask, only the `Context` the
     * provider hands it - but `Configuration.uiMode` is a plain resource
     * lookup, not an Activity API, so the same check ConfigActivity uses for
     * its own light/dark branch works here unchanged.
     */
    private fun isNightMode(context: Context): Boolean {
        val flags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return flags == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * The pill behind [textColor] when it is an alert colour painted as
     * *text* rather than a dot or a border.
     *
     * N-Red and N-Yellow are the brand's own hexes and are not up for
     * changing (see DataSettings.colorFor) - but as plain text on the
     * light-mode N-Grey surface both fail WCAG 4.5:1, and they fail in
     * opposite directions. Amber is nearly white and needs a dark backing;
     * red is already dark and reads *better* lifted onto a light one - no
     * single flat pill clears 4.5:1 for both at once (the best any one
     * colour manages is red on black, ~3.6:1), so which pill comes back
     * depends on which hex actually arrived. COLOR_WHITE resolves through
     * `text_primary`, already legible, and falls through to null here.
     * Null in dark mode too, where both hexes already clear the bar against
     * the dark tile untouched.
     */
    private fun pillColorFor(context: Context, textColor: Int): Int? {
        if (isNightMode(context)) return null
        return when (textColor) {
            context.getColor(R.color.nt_red) -> context.getColor(R.color.nt_white)
            context.getColor(R.color.nt_amber) -> context.getColor(R.color.nt_black)
            else -> null
        }
    }

    private fun configIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConfigActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
