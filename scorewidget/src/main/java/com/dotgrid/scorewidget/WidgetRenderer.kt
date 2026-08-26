package com.dotgrid.scorewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a [Game] into RemoteViews, at whichever of the three sizes the tile
 * currently is.
 *
 * All text is drawn to bitmaps by [TextRenderer] rather than set on TextViews.
 * That is not a stylistic choice - a widget cannot resolve `android:fontFamily`
 * at all, because AppWidgetHostView inflates through a CONTEXT_RESTRICTED
 * context and TextView skips font resources when the context is restricted.
 *
 * Which face each of those bitmaps is set in is [Typography]'s decision, not
 * this file's - every call below names a **role** rather than a font. The rule
 * from `docs/BRAND_LANGUAGE.md` that shapes this layout most is the one
 * splitting a line in half: the score is a data readout and takes the body
 * face, while the clock beside it is a time code and takes the accent face.
 */
object WidgetRenderer {

    // -----------------------------------------------------------------------
    // Breakpoints.
    //
    // The launcher reports a tile's size in dp through its options bundle, and
    // these are the thresholds between the three layouts. They sit below each
    // target rather than at it: a "4x1" is 250dp on a standard grid but a
    // launcher with a denser grid, or a phone in landscape, hands over
    // something a little under that, and a tile that falls back to the strip
    // because it is 244dp rather than 250dp is a bug the user sees and cannot
    // explain.
    // -----------------------------------------------------------------------

    /** At or above this height, the tile has two rows and gets the card. */
    private const val CARD_MIN_HEIGHT_DP = 90

    /** At or above this width, a single row is wide enough for the banner. */
    private const val BANNER_MIN_WIDTH_DP = 200

    private const val STRIP_TILE_DP = 110f
    private const val BANNER_TILE_DP = 250f
    private const val CARD_TILE_DP = 250f

    private enum class Size(val layout: Int) {
        STRIP(R.layout.widget_score_strip),
        BANNER(R.layout.widget_score_banner),
        CARD(R.layout.widget_score_card)
    }

    // -----------------------------------------------------------------------
    // Type scale, in sp, per size.
    //
    // The score is the one figure on the tile that has to be readable at a
    // glance from a pocket-height distance, so it takes the largest step at
    // every size and everything else is set against it.
    // -----------------------------------------------------------------------

    private const val STRIP_SCORE_SP = 15f
    private const val BANNER_SCORE_SP = 17f
    private const val CARD_SCORE_SP = 24f

    private const val BANNER_ABBREV_SP = 10f
    private const val CARD_ABBREV_SP = 11f

    private const val CLOCK_SP = 9f
    private const val CONTEXT_SP = 8.5f
    private const val CARD_META_SP = 8.5f

    /**
     * NType takes tracking by size - the guideline ships a spacing chart for
     * it, unlike NDot which takes none at all. At the sizes these labels are
     * set, small caps need the air or they close up into a bar.
     */
    private const val LABEL_TRACKING = Typography.Tracking.LABEL


    fun build(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        cards: List<Game>
    ): RemoteViews {
        val options = runCatching {
            appWidgetManager.getAppWidgetOptions(appWidgetId)
        }.getOrNull()

        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            ?.takeIf { it > 0 } ?: STRIP_TILE_DP.toInt()
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            ?.takeIf { it > 0 } ?: 40

        val size = pickLayout(widthDp, heightDp)
        val index = ScoreSettings.carouselIndex(context, appWidgetId)
        val game = TeamFilter.pick(cards, index)

        return paint(context, size, widthDp.toFloat(), game, cards.size, index, appWidgetId)
    }

    /**
     * Which of the three layouts a tile of this size gets.
     *
     * Height decides first, because it is the dimension that actually gates
     * what can be drawn: the card's four bands need two rows and no amount of
     * width substitutes for them. Only once the tile is known to be a single
     * row does width choose between the strip and the banner.
     */
    private fun pickLayout(widthDp: Int, heightDp: Int): Size = when {
        heightDp >= CARD_MIN_HEIGHT_DP && widthDp >= BANNER_MIN_WIDTH_DP -> Size.CARD
        widthDp >= BANNER_MIN_WIDTH_DP -> Size.BANNER
        else -> Size.STRIP
    }

    /**
     * The same RemoteViews the launcher gets, at the 4x2 size, for the settings
     * screen to show live.
     *
     * Rendering the preview through this path rather than a mock-up means a bug
     * in the tile shows up on the settings screen too, instead of only on a
     * home screen - the sibling data tile makes the same call for the same
     * reason.
     */
    fun buildPreview(context: Context, game: Game?, cardCount: Int): RemoteViews =
        paint(context, Size.CARD, CARD_TILE_DP, game, cardCount, 0, PREVIEW_WIDGET_ID)

    /** Not a real widget id; keeps the preview's tap targets from addressing a tile. */
    private const val PREVIEW_WIDGET_ID = -1

    fun refreshAll(context: Context, cards: List<Game>) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context, ScoreWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        ids.forEach { id -> manager.updateAppWidget(id, build(context, manager, id, cards)) }
    }

    // -----------------------------------------------------------------------

    private fun paint(
        context: Context,
        size: Size,
        tileWidthDp: Float,
        game: Game?,
        cardCount: Int,
        index: Int,
        appWidgetId: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, size.layout)
        val density = context.resources.displayMetrics.density

        /*
         * Type grows with the tile, but slower than the tile does and never
         * below its base size. A widget dragged wider should read as the same
         * object seen larger, not as the same 9sp caps marooned in a field of
         * black. Capped at 1.35 rather than the data tile's 1.9 because this
         * one is already at three discrete sizes - the scaling here only has to
         * cover the stretch *within* a breakpoint.
         */
        val baseDp = when (size) {
            Size.STRIP -> STRIP_TILE_DP
            Size.BANNER -> BANNER_TILE_DP
            Size.CARD -> CARD_TILE_DP
        }
        val typeScale = (tileWidthDp / baseDp).coerceIn(1f, 1.35f)
        fun sp(value: Float) = value * density * typeScale

        val white = context.getColor(R.color.text_primary)
        val secondary = context.getColor(R.color.text_secondary)
        val tertiary = context.getColor(R.color.text_tertiary)
        val matrixActive = context.getColor(R.color.matrix_active)
        val accent = ScoreSettings.accentColor(context)

        // The whole card opens the settings menu. This is the tap the brief
        // asks for, and it is set on the root rather than on a control so that
        // anywhere on the tile that is not the pager does it.
        views.setOnClickPendingIntent(R.id.widget_root, configIntent(context))

        if (game == null) {
            paintEmpty(context, views, size, sp(CLOCK_SP), white, tertiary, matrixActive)
            return views
        }

        // ---- the two matrices -------------------------------------------
        val glyphDp = when (size) {
            Size.STRIP -> R.dimen.strip_glyph
            Size.BANNER -> R.dimen.banner_glyph
            Size.CARD -> R.dimen.card_glyph
        }
        val glyphPx = context.resources.getDimensionPixelSize(glyphDp)

        views.setImageViewBitmap(
            R.id.away_glyph,
            GlyphMatrix.render(
                TeamGlyphs.forTeam(context, game.league, game.away.abbrev),
                glyphPx,
                matrixActive
            )
        )
        views.setImageViewBitmap(
            R.id.home_glyph,
            GlyphMatrix.render(
                TeamGlyphs.forTeam(context, game.league, game.home.abbrev),
                glyphPx,
                matrixActive
            )
        )
        views.setContentDescription(R.id.away_glyph, game.away.name)
        views.setContentDescription(R.id.home_glyph, game.home.name)

        // ---- the scoreline ----------------------------------------------
        val scoreSp = when (size) {
            Size.STRIP -> STRIP_SCORE_SP
            Size.BANNER -> BANNER_SCORE_SP
            Size.CARD -> CARD_SCORE_SP
        }

        /*
         * Before a game starts there is no score to show, and 0-0 under a
         * kickoff time is a scoreboard's answer rather than a lie. The
         * countdown carries the actual information, so the figures step back to
         * secondary and let it lead.
         */
        val scoresAreLive = !game.isScheduled
        val awayColor = scoreColor(game, home = false, white, secondary, scoresAreLive)
        val homeColor = scoreColor(game, home = true, white, secondary, scoresAreLive)

        views.setImageViewBitmap(
            R.id.scoreline,
            TextRenderer.renderScoreline(
                context = context,
                away = game.awayScore.toString(),
                home = game.homeScore.toString(),
                // A score is a data readout, so it takes the body face - not
                // the tabular NDot cut a changing figure would otherwise invite.
                fontRes = Typography.BODY,
                sizePx = sp(scoreSp),
                awayColor = awayColor,
                homeColor = homeColor,
                separator = context.getString(R.string.score_separator),
                separatorSizePx = sp(scoreSp) * 0.55f,
                separatorColor = tertiary,
                gapPx = sp(scoreSp) * 0.18f
            )
        )
        views.setContentDescription(
            R.id.scoreline,
            game.away.abbrev + " " + game.awayScore + ", " + game.home.abbrev + " " + game.homeScore
        )

        // ---- the live marker --------------------------------------------
        views.setViewVisibility(R.id.live_dot, if (game.isLive) View.VISIBLE else View.GONE)
        if (game.isLive) {
            // The drawable is white; the accent arrives as a filter, so one
            // shape covers every colour the settings menu can choose.
            views.setInt(R.id.live_dot, "setColorFilter", accent)
        }

        if (size == Size.STRIP) {
            paintPager(context, views, cardCount, index, appWidgetId, accent, tertiary, false)
            return views
        }

        // ---- abbreviations and possession (banner and card) --------------
        val abbrevSp = if (size == Size.CARD) CARD_ABBREV_SP else BANNER_ABBREV_SP
        views.setImageViewBitmap(
            R.id.away_abbrev,
            TextRenderer.render(
                context, game.away.abbrev, Typography.BODY, sp(abbrevSp), secondary, LABEL_TRACKING
            )
        )
        views.setImageViewBitmap(
            R.id.home_abbrev,
            TextRenderer.render(
                context, game.home.abbrev, Typography.BODY, sp(abbrevSp), secondary, LABEL_TRACKING
            )
        )

        val possession = game.situation.possessionAbbrev
        val awayHasBall = possession != null && possession == game.away.abbrev
        val homeHasBall = possession != null && possession == game.home.abbrev
        views.setViewVisibility(
            R.id.away_possession, if (awayHasBall) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.home_possession, if (homeHasBall) View.VISIBLE else View.GONE
        )

        // ---- clock and context ------------------------------------------
        val clockText = clockText(context, game)
        views.setImageViewBitmap(
            R.id.clock_line,
            TextRenderer.render(
                context, clockText, Typography.ACCENT, sp(CLOCK_SP),
                if (game.isLive) white else secondary, LABEL_TRACKING
            )
        )
        views.setContentDescription(R.id.clock_line, clockText)

        val contextText = contextText(game)
        val contextWidthPx = contextBudgetPx(context, size, tileWidthDp, density)
        if (contextText != null) {
            val fitted = TextRenderer.shrinkToFit(sp(CONTEXT_SP)) { candidate ->
                TextRenderer.widthPx(
                    context, contextText, Typography.BODY, candidate, LABEL_TRACKING
                ) <= contextWidthPx
            }
            views.setImageViewBitmap(
                R.id.context_line,
                TextRenderer.render(
                    context, contextText, Typography.BODY, fitted,
                    if (game.situation.isRedZone) accent else tertiary,
                    LABEL_TRACKING
                )
            )
            views.setViewVisibility(R.id.context_line, View.VISIBLE)
            views.setContentDescription(R.id.context_line, contextText)
        } else {
            views.setViewVisibility(R.id.context_line, View.INVISIBLE)
        }

        if (size == Size.BANNER) {
            paintPager(context, views, cardCount, index, appWidgetId, accent, tertiary, false)
            return views
        }

        // ---- card-only bands --------------------------------------------
        paintCardExtras(
            context, views, game, sp(CARD_META_SP), density, tileWidthDp,
            white, secondary, tertiary, accent, matrixActive
        )
        paintPager(context, views, cardCount, index, appWidgetId, accent, tertiary, true)
        return views
    }

    /**
     * The score's colour.
     *
     * The side that is ahead stays white and the side behind steps down to
     * secondary, so the leader is readable without the tile having to draw an
     * arrow or bold anything - the type scale is fixed and weight is not
     * available in a face that has one cut. A tie leaves both white, which is
     * the right answer: nobody is ahead.
     */
    private fun scoreColor(
        game: Game,
        home: Boolean,
        white: Int,
        secondary: Int,
        scoresAreLive: Boolean
    ): Int {
        if (!scoresAreLive) return secondary
        val mine = if (home) game.homeScore else game.awayScore
        val theirs = if (home) game.awayScore else game.homeScore
        return if (mine >= theirs) white else secondary
    }

    /**
     * The line where the clock goes: a running clock, a countdown, or a final.
     *
     * The countdown is the [League]-agnostic half of the brief's "countdown to
     * kickoff / first pitch" - the sport does not change what a countdown is,
     * only what the thing being counted down to is called, and the tile has no
     * room to name it.
     */
    private fun clockText(context: Context, game: Game): String {
        if (game.isScheduled) {
            val start = game.startsAt ?: return ""
            val remaining = start - System.currentTimeMillis()
            if (remaining <= 0) return context.getString(R.string.countdown_soon)
            if (remaining > TeamFilter.UPCOMING_WINDOW_MS) {
                // Beyond a day out, a countdown in hours is noise - the game is
                // tomorrow and the feed's own short status says which day.
                return game.statusDetail?.uppercase() ?: ""
            }
            val totalMinutes = (remaining / 60_000L).toInt()
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) {
                context.getString(R.string.countdown_hours, hours, minutes)
            } else {
                context.getString(R.string.countdown_minutes, max(1, minutes))
            }
        }
        return game.clockLine()
    }

    /**
     * The sport's own live detail, as one line.
     *
     * Baseball returns null on the card, where the diamond draws it instead -
     * the count and the runners are a picture there. On the banner, which has
     * no room for a diamond, it comes back as text.
     */
    private fun contextText(game: Game): String? {
        if (!game.isLive) {
            return game.broadcast?.takeIf { game.isScheduled }
        }
        return when (game.league) {
            League.NFL, League.NCAAF -> game.situation.downAndDistance()
            League.MLB -> game.situation.countLine()
            League.NBA, League.NHL -> {
                val bonus = when {
                    game.situation.awayInBonus -> game.away.abbrev + " BONUS"
                    game.situation.homeInBonus -> game.home.abbrev + " BONUS"
                    else -> null
                }
                bonus ?: game.situation.powerPlayAbbrev?.let { "$it PP" }
            }
        }
    }

    /**
     * Width the context line may take.
     *
     * On the banner it shares a fixed-width column with the clock and gets what
     * is left of it. On the card it runs from the clock to the right edge,
     * which is most of the tile - so the budget is the content width less the
     * clock and the diamond, and it is computed rather than guessed because the
     * diamond is only sometimes there.
     */
    private fun contextBudgetPx(
        context: Context,
        size: Size,
        tileWidthDp: Float,
        density: Float
    ): Int {
        val paddingDp = if (size == Size.CARD) 12f else 12f
        return when (size) {
            Size.BANNER ->
                (context.resources.getDimensionPixelSize(R.dimen.banner_status_width) * 0.95f)
                    .roundToInt()
            else -> ((tileWidthDp - paddingDp * 2) * 0.55f * density).roundToInt()
        }
    }

    /**
     * The 4x2 card's bottom two bands: league, broadcast, field position, win
     * probability and the stat line.
     */
    private fun paintCardExtras(
        context: Context,
        views: RemoteViews,
        game: Game,
        metaPx: Float,
        density: Float,
        tileWidthDp: Float,
        white: Int,
        secondary: Int,
        tertiary: Int,
        accent: Int,
        matrixActive: Int
    ) {
        views.setImageViewBitmap(
            R.id.league_label,
            TextRenderer.render(
                context, game.league.label, Typography.ACCENT, metaPx, tertiary, LABEL_TRACKING
            )
        )

        val broadcast = game.broadcast
        views.setViewVisibility(
            R.id.broadcast_label, if (broadcast != null) View.VISIBLE else View.GONE
        )
        if (broadcast != null) {
            views.setImageViewBitmap(
                R.id.broadcast_label,
                TextRenderer.render(
                    context, broadcast, Typography.BODY, metaPx, secondary, LABEL_TRACKING
                )
            )
        }

        // ---- the diamond -------------------------------------------------
        val showBases = game.league == League.MLB && game.isLive
        views.setViewVisibility(R.id.bases, if (showBases) View.VISIBLE else View.GONE)
        if (showBases) {
            val basesPx = context.resources.getDimensionPixelSize(R.dimen.card_bases)
            views.setImageViewBitmap(
                R.id.bases,
                ContextRenderer.bases(
                    sizePx = basesPx,
                    onFirst = game.situation.onFirst,
                    onSecond = game.situation.onSecond,
                    onThird = game.situation.onThird,
                    outs = game.situation.outs ?: 0,
                    activeColor = matrixActive,
                    inactiveColor = context.getColor(R.color.matrix_rail)
                )
            )
        }

        // ---- field position ---------------------------------------------
        val fieldFraction = fieldFraction(game)
        views.setViewVisibility(
            R.id.field_rail, if (fieldFraction != null) View.VISIBLE else View.GONE
        )
        if (fieldFraction != null) {
            val railWidthPx = ((tileWidthDp - 24f) * density).roundToInt()
            val railHeightPx = context.resources.getDimensionPixelSize(R.dimen.card_rail_height)
            views.setImageViewBitmap(
                R.id.field_rail,
                ContextRenderer.fieldRail(
                    widthPx = railWidthPx,
                    heightPx = railHeightPx,
                    ownHalfFraction = fieldFraction,
                    railColor = context.getColor(R.color.matrix_rail),
                    markerColor = white,
                    redZone = game.situation.isRedZone,
                    redZoneColor = accent
                )
            )
        }

        /*
         * The win rail and the stat line share the last row and take turns.
         *
         * Probability while the game is running, performance once it is over -
         * because before the final whistle nobody has been the top performer
         * yet, and after it the probability is 100% and says nothing. A
         * scheduled game has neither, and the row collapses.
         */
        val probability = if (ScoreSettings.showWinProbability(context)) {
            WinProbability.forGame(game)
        } else {
            null
        }
        val liveProbability = probability?.takeIf { game.isLive }
        val performer = game.topPerformer?.takeIf { game.isFinal }

        views.setViewVisibility(
            R.id.win_rail, if (liveProbability != null) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.performer_line,
            if (liveProbability == null && performer != null) View.VISIBLE else View.GONE
        )

        if (liveProbability != null) {
            val railWidthPx = ((tileWidthDp - 24f) * density * 0.72f).roundToInt()
            val railHeightPx = context.resources.getDimensionPixelSize(R.dimen.card_rail_height)
            views.setImageViewBitmap(
                R.id.win_rail,
                ContextRenderer.winRail(
                    widthPx = railWidthPx,
                    heightPx = railHeightPx,
                    fraction = liveProbability,
                    leadColor = white,
                    trailColor = context.getColor(R.color.matrix_rail),
                    markerColor = if (WinProbability.isCloseFinish(game)) accent else white
                )
            )
            views.setContentDescription(
                R.id.win_rail,
                game.home.abbrev + " " + (liveProbability * 100).roundToInt() + "%"
            )
        } else if (performer != null) {
            val budget = ((tileWidthDp - 24f) * density * 0.72f).roundToInt()
            val fitted = TextRenderer.shrinkToFit(metaPx) { candidate ->
                TextRenderer.widthPx(
                    context, performer, Typography.BODY, candidate, LABEL_TRACKING
                ) <= budget
            }
            views.setImageViewBitmap(
                R.id.performer_line,
                TextRenderer.render(
                    context, performer, Typography.BODY, fitted, secondary, LABEL_TRACKING
                )
            )
            views.setContentDescription(R.id.performer_line, performer)
        }
    }

    /**
     * Where the ball is, as a fraction from the possessing team's own goal line.
     *
     * ESPN reports the yard line as a number 1..50 plus which side of the field
     * it is on, and the side arrives folded into the text rather than as a
     * flag - so this reads the conventional form, where a value above 50 is
     * already in the opponent's half. Null whenever the game is not football or
     * nobody has the ball, which is what hides the rail.
     */
    private fun fieldFraction(game: Game): Float? {
        if (game.league != League.NFL && game.league != League.NCAAF) return null
        if (!game.isLive) return null
        if (game.situation.possessionAbbrev == null) return null
        val yard = game.situation.yardLine?.trim()?.toIntOrNull() ?: return null
        // 0 is the possessing team's own goal line, 100 the opponent's.
        return (yard.coerceIn(0, 100) / 100f)
    }

    /**
     * The carousel control: a dot per card, the current one lit.
     *
     * Hidden when there is one card or none. A pager with a single dot is a
     * control that invites a tap and does nothing, and on the strip there is no
     * room for one at all.
     */
    private fun paintPager(
        context: Context,
        views: RemoteViews,
        cardCount: Int,
        index: Int,
        appWidgetId: Int,
        accent: Int,
        tertiary: Int,
        vertical: Boolean
    ) {
        if (cardCount <= 1 || appWidgetId == PREVIEW_WIDGET_ID) {
            views.setViewVisibility(R.id.pager, View.GONE)
            return
        }
        views.setViewVisibility(R.id.pager, View.VISIBLE)

        val density = context.resources.displayMetrics.density
        views.setImageViewBitmap(
            R.id.pager,
            PagerRenderer.render(
                count = cardCount,
                current = TeamFilter.wrapIndex(index, cardCount),
                dotPx = max(2, (3f * density).roundToInt()),
                pitchPx = max(4, (7f * density).roundToInt()),
                vertical = vertical,
                activeColor = accent,
                inactiveColor = tertiary
            )
        )
        views.setOnClickPendingIntent(R.id.pager, advanceIntent(context, appWidgetId))
    }

    /**
     * The empty states.
     *
     * Four of them, and they are different sentences rather than one shrug: no
     * teams picked at all, every favourite's league dark, nothing on today, and
     * no network. Each one implies a different next action, and a tile that
     * said "nothing to show" for all four would be hiding which.
     */
    private fun paintEmpty(
        context: Context,
        views: RemoteViews,
        size: Size,
        textPx: Float,
        white: Int,
        tertiary: Int,
        matrixActive: Int
    ) {
        val hasFavorites = ScoreSettings.favorites(context).isNotEmpty()
        val leagues = ScoreSettings.activeLeagues(context)
        val allDark = hasFavorites && leagues.isNotEmpty() && leagues.none { it.inSeason() }

        val headline = when {
            !hasFavorites -> context.getString(R.string.state_no_teams)
            allDark -> context.getString(R.string.state_offseason)
            else -> context.getString(R.string.state_no_games)
        }
        val hint = when {
            !hasFavorites -> context.getString(R.string.state_no_teams_hint)
            allDark -> context.getString(R.string.state_offseason_hint)
            else -> ""
        }

        // A league mark stands in for the two team matrices, so the empty tile
        // is still recognisably this widget rather than a blank card.
        val league = leagues.firstOrNull() ?: League.NFL
        val glyphRes = when (size) {
            Size.STRIP -> R.dimen.strip_glyph
            Size.BANNER -> R.dimen.banner_glyph
            Size.CARD -> R.dimen.card_glyph
        }
        val glyphPx = context.resources.getDimensionPixelSize(glyphRes)
        val mark = GlyphMatrix.render(TeamGlyphs.forLeague(league), glyphPx, matrixActive)

        views.setImageViewBitmap(R.id.away_glyph, mark)
        views.setViewVisibility(R.id.home_glyph, View.INVISIBLE)
        views.setViewVisibility(R.id.live_dot, View.GONE)
        views.setViewVisibility(R.id.pager, View.GONE)

        views.setImageViewBitmap(
            R.id.scoreline,
            TextRenderer.render(context, headline, Typography.ACCENT, textPx, white, LABEL_TRACKING)
        )
        views.setContentDescription(R.id.scoreline, headline)

        if (size != Size.STRIP) {
            views.setViewVisibility(R.id.away_abbrev, View.GONE)
            views.setViewVisibility(R.id.home_abbrev, View.GONE)
            views.setViewVisibility(R.id.away_possession, View.GONE)
            views.setViewVisibility(R.id.home_possession, View.GONE)
            views.setImageViewBitmap(
                R.id.clock_line,
                TextRenderer.render(
                    context, hint, Typography.ACCENT, textPx * 0.9f, tertiary, LABEL_TRACKING
                )
            )
            views.setViewVisibility(R.id.context_line, View.GONE)
        }
        if (size == Size.CARD) {
            views.setViewVisibility(R.id.bases, View.GONE)
            views.setViewVisibility(R.id.field_rail, View.GONE)
            views.setViewVisibility(R.id.win_rail, View.GONE)
            views.setViewVisibility(R.id.performer_line, View.GONE)
            views.setViewVisibility(R.id.broadcast_label, View.GONE)
            views.setImageViewBitmap(
                R.id.league_label,
                TextRenderer.render(
                    context, league.label, Typography.ACCENT, textPx * 0.85f, tertiary, LABEL_TRACKING
                )
            )
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

    /**
     * Steps this one tile's carousel forward.
     *
     * The widget id is both an extra and the request code. Without it in the
     * request code every tile on the home screen would share one PendingIntent
     * - `filterEquals` ignores extras - and tapping one pager would advance all
     * of them, which is the one thing a per-tile carousel must not do.
     */
    private fun advanceIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, ScoreWidgetProvider::class.java).apply {
            action = ScoreWidgetProvider.ACTION_ADVANCE
            component = ComponentName(context, ScoreWidgetProvider::class.java)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
