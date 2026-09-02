package com.dotgrid.healthwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import androidx.health.connect.client.HealthConnectClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a [HealthSnapshot] into RemoteViews.
 *
 * Two layouts, and they are two different objects rather than one stretched.
 * Below four cells wide the tile is a dial with a figure in it; at four and
 * above it is a list of rows with a rail under each. A dial pulled wide is an
 * ellipse, and a list squeezed to two cells has nowhere to put its figures, so
 * neither one covers the whole range on its own. `onAppWidgetOptionsChanged`
 * is what swaps them when the user finishes dragging.
 *
 * All text is drawn to bitmaps by [TextRenderer] rather than set on TextViews.
 * That is not a stylistic choice - a widget cannot resolve `android:fontFamily`
 * at all, because AppWidgetHostView inflates through a CONTEXT_RESTRICTED
 * context and TextView skips font resources when the context is restricted.
 */
object WidgetRenderer {

    /** The 2 x 2 baseline: two 70dp cells less the 30dp the grid takes back. */
    private const val BASE_TILE_DP = 110f

    /**
     * Where the dial gives way to the list.
     *
     * Three launcher cells is about 180dp and two is about 110dp, so 150
     * separates them with room either side for a launcher that measures its
     * grid slightly differently. The list needs roughly 95dp of width for a
     * glyph, a label and a five-digit figure on one line, which two cells does
     * not have once the padding is off it.
     */
    private const val WIDE_FROM_DP = 150

    // ---- the square tile -------------------------------------------------

    /** Mirrors android:padding on widget_health_dial.xml. */
    private const val PADDING_DP = 10f

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
     * number - it narrows the further a line sits from the dial's middle.
     * These are the chord across the dial's clear inner radius at each line's
     * own band, measured on the rendered layout at the 2 x 2 size and divided
     * by the 90dp content square:
     *
     *   readout   -22dp .. +2dp from centre   64.5dp clear   -> 0.71
     *   line A    +4dp  .. +12dp              74.3dp clear   -> 0.80
     *   line B    +14dp .. +22dp              64.5dp clear   -> 0.71
     *
     * Without them the type is measured against the tile and merely looks like
     * it fits: "GOAL 10,000" and "SLEEP 7H 12M" both run into the arc.
     */
    private const val READOUT_WIDTH_RATIO = 0.71f
    private const val LINE_A_WIDTH_RATIO = 0.80f
    private const val LINE_B_WIDTH_RATIO = 0.71f

    // ---- the wide tile ---------------------------------------------------

    /** Mirrors android:padding on widget_health_rows.xml. */
    private const val PADDING_WIDE_DP = 12f

    /** The header line, plus the gap under it. Mirrors the layout's margins. */
    private const val HEADER_DP = 14f
    private const val HEADER_GAP_DP = 4f

    /**
     * The least height a row can be given and still hold a figure over a rail.
     * Below this the renderer drops a row rather than letting two of them
     * overlap - a clipped descender is a bug the user cannot do anything about.
     */
    private const val ROW_MIN_DP = 32f

    /** Fixed pieces of a row: the rail, and the gap above it. Mirrors the layout. */
    private const val ROW_FIXED_DP = 11f

    /** What one line of the value type costs in height, per sp. */
    private const val LINE_HEIGHT_RATIO = 1.35f

    private const val ROW_LABEL_SP = 9f
    private const val ROW_VALUE_SP = 14f
    private const val ROW_UNIT_SP = 8f
    private const val ROW_GAP_SP = 2.5f
    private const val HEADER_SP = 9f
    private const val STAMP_SP = 8f

    private const val RAIL_HEIGHT_DP = 7f

    /** Five slots in widget_health_rows.xml, filled in order from the enabled metrics. */
    private val ROW_IDS = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4)
    private val ROW_ICON_IDS = intArrayOf(
        R.id.row_0_icon, R.id.row_1_icon, R.id.row_2_icon, R.id.row_3_icon, R.id.row_4_icon
    )
    private val ROW_LABEL_IDS = intArrayOf(
        R.id.row_0_label, R.id.row_1_label, R.id.row_2_label, R.id.row_3_label, R.id.row_4_label
    )
    private val ROW_VALUE_IDS = intArrayOf(
        R.id.row_0_value, R.id.row_1_value, R.id.row_2_value, R.id.row_3_value, R.id.row_4_value
    )
    private val ROW_RAIL_IDS = intArrayOf(
        R.id.row_0_rail, R.id.row_1_rail, R.id.row_2_rail, R.id.row_3_rail, R.id.row_4_rail
    )

    fun build(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: HealthSnapshot
    ): RemoteViews {
        val options = runCatching {
            appWidgetManager.getAppWidgetOptions(appWidgetId)
        }.getOrNull()

        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            ?.takeIf { it > 0 } ?: BASE_TILE_DP.toInt()
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            ?.takeIf { it > 0 } ?: BASE_TILE_DP.toInt()

        return if (widthDp >= WIDE_FROM_DP) {
            paintRows(context, snapshot, widthDp, heightDp)
        } else {
            // The dial is a circle, so the tile's short side is the whole budget.
            paintDial(context, snapshot, min(widthDp, heightDp).toFloat())
        }
    }

    /**
     * The same RemoteViews the launcher gets, at a fixed size, for the settings
     * screen to show live.
     *
     * Rendering the preview through this path rather than a mock-up means a bug
     * in the tile shows up on the settings screen too, instead of only on a
     * home screen - and it lets the preview show both layouts, which is the
     * only way to see what a setting does to the one you are not holding.
     */
    fun buildPreview(context: Context, snapshot: HealthSnapshot, wide: Boolean): RemoteViews =
        if (wide) paintRows(context, snapshot, PREVIEW_WIDE_DP, PREVIEW_WIDE_HEIGHT_DP)
        else paintDial(context, snapshot, BASE_TILE_DP)

    /** 4 x 2, the smallest size the list layout is used at. */
    const val PREVIEW_WIDE_DP = 250
    const val PREVIEW_WIDE_HEIGHT_DP = 110

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context, NothingHealthWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return

        // One read for every tile on the home screen: the Health Connect query
        // is the expensive part and they are all showing the same body.
        val snapshot = HealthSnapshot.read(context)
        ids.forEach { id -> manager.updateAppWidget(id, build(context, manager, id, snapshot)) }
    }

    // =====================================================================
    // The square tile
    // =====================================================================

    private fun paintDial(
        context: Context,
        snapshot: HealthSnapshot,
        tileDp: Float
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_health_dial)
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
        val glyph = context.getColor(R.color.glyph)
        val dialActive = context.getColor(R.color.meter_active)
        val dialInactive = context.getColor(R.color.meter_inactive)

        val metric = snapshot.prefs.dialMetric()
        val reading = snapshot.reading(metric)
        val goalMet = snapshot.goalMet(metric)

        // ---- the dial ---------------------------------------------------
        views.setImageViewBitmap(
            R.id.dial,
            DialRenderer.render(
                sizePx = squarePx,
                fraction = snapshot.fraction(metric),
                activeColor = if (goalMet && snapshot.styled(HealthSettings.STYLE_RING)) {
                    snapshot.accentColor
                } else {
                    dialActive
                },
                inactiveColor = dialInactive
            )
        )

        /*
         * With nothing readable there is no figure to place-hold: a dash where
         * the number goes is an instrument saying "not reading right now",
         * which is the wrong sentence when the answer is "you have not let me
         * look". Drop the line entirely and let the two small ones centre in
         * the dial, so the state reads as deliberate rather than as a tile that
         * failed to draw.
         *
         * A granted read that came back empty is the other case, and there the
         * dash is exactly right - the figure exists, we just have not got it.
         */
        val blocked = !snapshot.sdkAvailable || !reading.granted
        views.setViewVisibility(R.id.readout, if (blocked) View.GONE else View.VISIBLE)

        if (!blocked) {
            val (value, unit) = Metrics.format(context, metric, reading)
            val valuePx = fitReadout(
                context, value, unit, sp(VALUE_SP), (squarePx * READOUT_WIDTH_RATIO).roundToInt()
            )

            val readoutValueColor = if (goalMet && snapshot.styled(HealthSettings.STYLE_VALUE)) {
                snapshot.accentColor
            } else {
                white
            }
            views.setImageViewBitmap(
                R.id.readout,
                TextRenderer.renderReadout(
                    context = context,
                    value = value,
                    valueSizePx = valuePx,
                    valueColor = readoutValueColor,
                    unit = unit,
                    // The unit keeps its proportion to the value when the value
                    // has been shrunk to fit, so a long figure does not leave
                    // an oversized "STEPS" hanging off it.
                    unitSizePx = valuePx * (UNIT_SP / VALUE_SP),
                    unitColor = if (reading.value == null) tertiary else secondary,
                    gapPx = valuePx * (READOUT_GAP_SP / VALUE_SP),
                    pillColor = pillColorFor(context, readoutValueColor)
                )
            )
        }

        // ---- the two small lines ----------------------------------------
        val lineA = when {
            !snapshot.sdkAvailable -> context.getString(R.string.healthwidget_no_sdk_line)
            !reading.granted -> context.getString(R.string.healthwidget_no_access_line)
            else -> Metrics.goalLine(context, metric, snapshot.prefs)
                ?: Metrics.label(context, metric)
        }

        val lineB = when {
            !snapshot.sdkAvailable -> context.getString(R.string.healthwidget_no_sdk_hint)
            !reading.granted -> context.getString(R.string.healthwidget_no_access_hint)
            else -> secondLine(context, snapshot, metric)
        }

        /*
         * Which face these two lines take depends on what they are saying.
         *
         * Granted and reading, they report the goal or the metric name - a
         * data readout - and take the body face. Blocked, the tile has no
         * reading to give and is describing itself instead ("NO ACCESS"),
         * which is a status indicator. See Typography, and the data widget's
         * own lineFont switch for the same distinction.
         */
        val lineFont = if (blocked) Typography.ACCENT else Typography.BODY

        // A missing grant is the one thing on this tile the user has to act
        // on, so it borrows the accent rather than sitting quietly in
        // secondary grey with the ordinary labels.
        val lineAColor = if (blocked) snapshot.accentColor else secondary
        val lineBColor = if (blocked) snapshot.accentColor else tertiary
        views.setImageViewBitmap(
            R.id.line_a,
            TextRenderer.render(
                context, lineA, lineFont,
                fitLine(context, lineA, lineFont, sp(LINE_SP), (squarePx * LINE_A_WIDTH_RATIO).roundToInt()),
                lineAColor,
                LINE_TRACKING,
                pillColorFor(context, lineAColor)
            )
        )
        views.setImageViewBitmap(
            R.id.line_b,
            TextRenderer.render(
                context, lineB, lineFont,
                fitLine(context, lineB, lineFont, sp(LINE_SP), (squarePx * LINE_B_WIDTH_RATIO).roundToInt()),
                lineBColor,
                LINE_TRACKING,
                pillColorFor(context, lineBColor)
            )
        )

        paintAccents(views, snapshot, goalMet)
        views.setInt(R.id.refresh_button, "setColorFilter", glyph)
        views.setOnClickPendingIntent(R.id.refresh_button, refreshIntent(context))
        views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context, snapshot))
        return views
    }

    /**
     * The line under the goal: whatever the tile can say next.
     *
     * The second enabled metric, if there is one - a 2 x 2 showing steps and
     * last night's sleep is most of what the wide tile says, in a quarter of
     * the space. Failing that, the time the figures were read, which is the
     * only other fact the tile holds.
     */
    private fun secondLine(context: Context, snapshot: HealthSnapshot, dialMetric: Int): String {
        val other = snapshot.prefs.enabledMetrics().firstOrNull { it != dialMetric }
        if (other != null) {
            val reading = snapshot.reading(other)
            val label = Metrics.label(context, other)
            if (!reading.granted) {
                return context.getString(R.string.healthwidget_pair_format, label,
                    context.getString(R.string.healthwidget_no_access_line))
            }
            val (value, _) = Metrics.format(context, other, reading)
            return context.getString(R.string.healthwidget_pair_format, label, value)
        }
        return stamp(snapshot.readAt)
    }

    // =====================================================================
    // The wide tile
    // =====================================================================

    private fun paintRows(
        context: Context,
        snapshot: HealthSnapshot,
        widthDp: Int,
        heightDp: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_health_rows)
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = max(1, (dp * density).roundToInt())

        val metrics = snapshot.prefs.enabledMetrics()

        /*
         * How many rows the height can actually hold, and how tall each one
         * ends up. The rows are weighted in XML, so hiding the ones that do not
         * fit is all it takes - the survivors spread over the whole height
         * rather than stacking at the top and leaving a hole underneath.
         */
        val availableDp = heightDp - PADDING_WIDE_DP * 2 - HEADER_DP - HEADER_GAP_DP
        val rowCount = min(
            metrics.size,
            max(1, floor(availableDp / ROW_MIN_DP).toInt())
        ).coerceAtMost(ROW_IDS.size)
        val rowHeightDp = availableDp / rowCount

        /*
         * Type grows with the tile on both axes, and takes the smaller answer.
         * Width alone would let a short wide tile set 20sp figures into a 32dp
         * row and clip their descenders; height alone would leave a 5 x 2 tile
         * setting 2 x 2 type across 320dp.
         */
        val widthScale = widthDp / PREVIEW_WIDE_DP.toFloat()
        val heightScale = (rowHeightDp - ROW_FIXED_DP) / (ROW_VALUE_SP * LINE_HEIGHT_RATIO)
        val typeScale = min(widthScale, heightScale).coerceIn(1f, 1.6f)
        fun sp(value: Float) = value * density * typeScale

        val white = context.getColor(R.color.text_primary)
        val secondary = context.getColor(R.color.text_secondary)
        val tertiary = context.getColor(R.color.text_tertiary)
        val glyph = context.getColor(R.color.glyph)
        val railActive = context.getColor(R.color.meter_active)
        val railInactive = context.getColor(R.color.meter_inactive)

        val railWidthPx = px(widthDp - PADDING_WIDE_DP * 2)
        val railHeightPx = px(RAIL_HEIGHT_DP)

        // ---- header ------------------------------------------------------
        val attention = !snapshot.sdkAvailable || snapshot.noAccess
        views.setInt(
            R.id.status_dot, "setColorFilter",
            when {
                attention -> snapshot.accentColor
                snapshot.stale -> tertiary
                else -> white
            }
        )
        // The header title is chrome, not a reading - the tile's own name,
        // the way the score widget's league label is. Accent.
        views.setImageViewBitmap(
            R.id.header_title,
            TextRenderer.render(
                context, context.getString(R.string.healthwidget_widget_title), Typography.ACCENT,
                sp(HEADER_SP), white, LINE_TRACKING
            )
        )

        val stampText = when {
            !snapshot.sdkAvailable -> context.getString(R.string.healthwidget_no_sdk_hint)
            snapshot.noAccess -> context.getString(R.string.healthwidget_no_access_hint)
            else -> stamp(snapshot.readAt)
        }
        // A sync time is a clock, and the fallback text is a status line - both
        // Accent either way, same as the game clock and the league label.
        val stampColor = if (attention) snapshot.accentColor else tertiary
        views.setImageViewBitmap(
            R.id.header_stamp,
            TextRenderer.render(
                context, stampText, Typography.ACCENT, sp(STAMP_SP),
                stampColor,
                LINE_TRACKING,
                pillColorFor(context, stampColor)
            )
        )
        // Same steady-state colour as a row's own icon, not the header's
        // status dot - this is a control, not a reading, and has nothing to
        // say about whether the tile is stale or blocked.
        views.setInt(R.id.refresh_button, "setColorFilter", glyph)
        views.setOnClickPendingIntent(R.id.refresh_button, refreshIntent(context))

        // ---- rows --------------------------------------------------------
        for (slot in ROW_IDS.indices) {
            if (slot >= rowCount) {
                views.setViewVisibility(ROW_IDS[slot], View.GONE)
                continue
            }
            views.setViewVisibility(ROW_IDS[slot], View.VISIBLE)

            val metric = metrics[slot]
            val reading = snapshot.reading(metric)
            val goalMet = snapshot.goalMet(metric)
            val readable = snapshot.sdkAvailable && reading.granted

            views.setImageViewResource(ROW_ICON_IDS[slot], Metrics.icon(metric))
            views.setInt(
                ROW_ICON_IDS[slot], "setColorFilter",
                if (goalMet && snapshot.styled(HealthSettings.STYLE_VALUE)) {
                    snapshot.accentColor
                } else {
                    glyph
                }
            )

            // The row's own name beside its figure - a stat label, the same
            // as the score widget's stat line. Body.
            val label = Metrics.label(context, metric)
            views.setImageViewBitmap(
                ROW_LABEL_IDS[slot],
                TextRenderer.render(context, label, Typography.BODY, sp(ROW_LABEL_SP), secondary, LINE_TRACKING)
            )

            if (readable) {
                val (value, rawUnit) = Metrics.format(context, metric, reading)
                /*
                 * The row already carries its name on the left, so a unit that
                 * only repeats it is noise: "STEPS  260 STEPS". BPM, % and /MIN
                 * survive, because HEART, OXYGEN and BREATH do not say what
                 * they are measured in. The dial keeps its unit either way -
                 * there is no label beside the figure there to repeat.
                 */
                val unit = if (rawUnit.equals(label, ignoreCase = true)) "" else rawUnit
                val valuePx = sp(ROW_VALUE_SP)
                val rowValueColor = if (goalMet && snapshot.styled(HealthSettings.STYLE_VALUE)) {
                    snapshot.accentColor
                } else {
                    white
                }
                views.setImageViewBitmap(
                    ROW_VALUE_IDS[slot],
                    TextRenderer.renderReadout(
                        context = context,
                        value = value,
                        valueSizePx = valuePx,
                        valueColor = rowValueColor,
                        unit = unit,
                        unitSizePx = sp(ROW_UNIT_SP),
                        unitColor = if (reading.value == null) tertiary else secondary,
                        gapPx = sp(ROW_GAP_SP),
                        pillColor = pillColorFor(context, rowValueColor)
                    )
                )
            } else {
                // Per row, not per tile: Health Connect grants each read
                // separately, so steps can be live while oxygen is still
                // waiting, and one banner across the tile would misreport both.
                // The row has no reading to give and is describing itself
                // instead - a status indicator taking the value slot. Accent.
                views.setImageViewBitmap(
                    ROW_VALUE_IDS[slot],
                    TextRenderer.render(
                        context,
                        context.getString(
                            if (snapshot.sdkAvailable) R.string.healthwidget_no_access_line
                            else R.string.healthwidget_no_sdk_line
                        ),
                        Typography.ACCENT,
                        sp(ROW_LABEL_SP), snapshot.accentColor, LINE_TRACKING,
                        pillColorFor(context, snapshot.accentColor)
                    )
                )
            }

            views.setImageViewBitmap(
                ROW_RAIL_IDS[slot],
                RailRenderer.render(
                    widthPx = railWidthPx,
                    heightPx = railHeightPx,
                    fraction = if (readable) snapshot.fraction(metric) else null,
                    activeColor = if (goalMet && snapshot.styled(HealthSettings.STYLE_RING)) {
                        snapshot.accentColor
                    } else {
                        railActive
                    },
                    inactiveColor = railInactive,
                    headColor = if (goalMet && snapshot.styled(HealthSettings.STYLE_RING)) {
                        snapshot.accentColor
                    } else {
                        white
                    }
                )
            )
        }

        // The border is the only indicator the wide tile can carry whole: it
        // has no dial to recolour and no single figure to point at, so the
        // corner dot has nowhere to sit that is not already a row.
        val borderOn = snapshot.styled(HealthSettings.STYLE_CARD) &&
            metrics.any { snapshot.goalMet(it) }
        views.setViewVisibility(R.id.accent_border, if (borderOn) View.VISIBLE else View.GONE)
        if (borderOn) {
            views.setInt(R.id.accent_border, "setColorFilter", snapshot.accentColor)
        }

        views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context, snapshot))
        return views
    }

    // =====================================================================
    // Shared
    // =====================================================================

    private fun paintAccents(
        views: RemoteViews,
        snapshot: HealthSnapshot,
        goalMet: Boolean
    ) {
        val showDot = goalMet && snapshot.styled(HealthSettings.STYLE_DOT)
        views.setViewVisibility(R.id.accent_dot, if (showDot) View.VISIBLE else View.GONE)
        if (showDot) {
            // The drawable is white; the accent arrives as a filter, so one
            // shape covers every colour the user can choose.
            views.setInt(R.id.accent_dot, "setColorFilter", snapshot.accentColor)
        }

        val showBorder = goalMet && snapshot.styled(HealthSettings.STYLE_CARD)
        views.setViewVisibility(R.id.accent_border, if (showBorder) View.VISIBLE else View.GONE)
        if (showBorder) {
            views.setInt(R.id.accent_border, "setColorFilter", snapshot.accentColor)
        }
    }

    /**
     * Where a tap goes.
     *
     * A tile that cannot read anything always goes to the settings screen,
     * whatever the user chose - it is saying TAP TO GRANT, and sending that tap
     * to Fitbit would be a lie.
     *
     * The target is resolved **here, at paint time**, and the widget is handed
     * a PendingIntent aimed straight at it. The obvious alternative - broadcast
     * to our own provider and work it out on the tap - has to call
     * startActivity from a receiver, which is the launch background activity
     * launch restrictions exist to stop. It survives today on the sender being
     * visible; it is not a thing to depend on. Resolving now costs one
     * PackageManager lookup per repaint and means an app that has since been
     * uninstalled lands on the settings screen instead of nowhere.
     */
    private fun tapIntent(context: Context, snapshot: HealthSnapshot): PendingIntent {
        val blocked = !snapshot.sdkAvailable || snapshot.noAccess
        val target = if (blocked) null else resolveTarget(context, snapshot.prefs.tapTarget)

        val intent = (target ?: Intent(context, ConfigActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return PendingIntent.getActivity(
            context,
            // A distinct request code per target, so a tile that has changed
            // its mind cannot be handed the launcher's cached intent for the
            // old one.
            if (target == null) 0 else 1000 + snapshot.prefs.tapTarget,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * The manual refresh button's PendingIntent.
     *
     * Same shape as [RefreshScheduler]'s own - an explicit broadcast to this
     * provider carrying [NothingHealthWidgetProvider.ACTION_REFRESH], which
     * `onReceive` already re-arms the alarm and repaints on, so a tap needs
     * no handling of its own beyond building the intent and firing it.
     *
     * The request code has to be its own, distinct from
     * [RefreshScheduler]'s 1001: both PendingIntents use
     * FLAG_UPDATE_CURRENT, and a shared request code would make the second
     * one built silently replace the extras/target of the first rather than
     * coexist with it - the alarm and the button would end up firing
     * whichever intent was constructed most recently instead of each firing
     * its own.
     */
    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, NothingHealthWidgetProvider::class.java).apply {
            action = NothingHealthWidgetProvider.ACTION_REFRESH
            component = ComponentName(context, NothingHealthWidgetProvider::class.java)
        }
        return PendingIntent.getBroadcast(
            context,
            MANUAL_REFRESH_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
     * The pill behind [textColor] when it is an accent colour painted as
     * *text* rather than a dot, a border, or a rail.
     *
     * N-Red and N-Yellow are the brand's own hexes and are not up for
     * changing (see HealthSettings.colorFor) - but as plain text on the
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

    /** Distinct from RefreshScheduler.REQUEST_CODE (1001) - see [refreshIntent]. */
    private const val MANUAL_REFRESH_REQUEST_CODE = 1002

    /** The chosen app's own entry point, or null if it is not on this device. */
    private fun resolveTarget(context: Context, tapTarget: Int): Intent? = when (tapTarget) {
        HealthSettings.TAP_HEALTH_CONNECT ->
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).takeIf {
                // Resolvable because the manifest names this action in
                // <queries>; without that entry it would look absent even
                // where Health Connect is installed.
                it.resolveActivity(context.packageManager) != null
            }

        HealthSettings.TAP_FITBIT ->
            context.packageManager.getLaunchIntentForPackage(
                NothingHealthWidgetProvider.FITBIT_PACKAGE
            )

        else -> null
    }

    /**
     * Hours and minutes, in the device's own 12- or 24-hour convention.
     *
     * Built per call rather than held as a constant: a SimpleDateFormat binds
     * the locale it was constructed with, and the user can change the device
     * locale while this process is still alive.
     */
    private fun stamp(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    /**
     * Shrinks the figure until it clears the dial.
     *
     * "10,482" beside "STEPS" is half again as wide as "8.4", and the dial
     * cannot grow to meet it - so the type gives way instead. Stepping down
     * rather than scaling in one go keeps the common case untouched: a figure
     * that already fits is returned at full size on the first pass.
     */
    private fun fitReadout(
        context: Context,
        value: String,
        unit: String,
        startPx: Float,
        maxWidthPx: Int
    ): Float = shrinkToFit(startPx) { size ->
        val unitSize = size * (UNIT_SP / VALUE_SP)
        // A readout is a data readout: Body/Geist, always - see Typography.
        TextRenderer.widthPx(context, value, Typography.BODY, size) +
            TextRenderer.widthPx(context, unit, Typography.BODY, unitSize, UNIT_TRACKING) +
            size * (READOUT_GAP_SP / VALUE_SP) <= maxWidthPx
    }

    /**
     * The same for one of the small caps lines.
     *
     * These are shorter than the readout but sit nearer the bottom of the dial,
     * where the circle has closed in - and the widest of them are the ones the
     * user never chose: "SLEEP 12H 45M" after a long night, "TAP TO GRANT" when
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
}
