package com.dotgrid.scorewidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The tile itself.
 *
 * It owns no state beyond each tile's carousel position. Every repaint reads
 * the cards from [GameRepository], which is either a fresh fetch or the cached
 * one - see that class for why the cache is on disk rather than in a field.
 */
class ScoreWidgetProvider : AppWidgetProvider() {

    /*
     * Everything below hands its work to [Background]. onUpdate and
     * onAppWidgetOptionsChanged are both dispatched from onReceive, on the main
     * thread, and the work most of them do reaches the network - which is not
     * merely slow there but throws.
     */

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Background.run(this) {
            /*
             * Paint from the cache first, then fetch.
             *
             * onUpdate is what runs when a tile is placed, when the host
             * restarts, and after a reboot - all moments where the alternative
             * is an empty card sitting there for however long the request
             * takes. The cached cards are seconds old in the common case and
             * the second repaint costs nothing but a binder call.
             */
            val cached = GameRepository.cards(context)
            if (cached.isNotEmpty()) {
                appWidgetIds.forEach { id ->
                    appWidgetManager.updateAppWidget(
                        id, WidgetRenderer.build(context, appWidgetManager, id, cached)
                    )
                }
            }

            val fresh = GameRepository.refresh(context)
            appWidgetIds.forEach { id ->
                appWidgetManager.updateAppWidget(
                    id, WidgetRenderer.build(context, appWidgetManager, id, fresh)
                )
            }

            GameAlerts.check(context, fresh)
            RefreshScheduler.arm(context, fresh)
        }
    }

    /**
     * Resize.
     *
     * This is where the three breakpoints actually take effect: the layout is
     * chosen from the size in the options bundle, so a tile dragged from 4x1 to
     * 4x2 gets a different layout resource entirely rather than a stretched
     * one. No fetch - the cards have not changed, only the shape they are drawn
     * into.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        Background.run(this) {
            appWidgetManager.updateAppWidget(
                appWidgetId,
                WidgetRenderer.build(
                    context, appWidgetManager, appWidgetId, GameRepository.cards(context)
                )
            )
        }
    }

    override fun onEnabled(context: Context) {
        // First tile placed. Nothing to arm the alarm against yet - onUpdate
        // follows immediately and will arm it with real cards.
        RefreshScheduler.arm(context, emptyList())
    }

    override fun onDisabled(context: Context) {
        // Last tile removed; nothing left to keep fresh, and nothing worth
        // holding bitmaps for.
        RefreshScheduler.cancel(context)
        GlyphMatrix.clear()
        TeamGlyphs.clear()
        TextRenderer.clear()
        ContextRenderer.clear()
        PagerRenderer.clear()
    }

    /** Each tile's carousel position is stored per id, so it has to be dropped per id. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { ScoreSettings.forgetWidget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> Background.run(this) {
                val cards = GameRepository.refresh(context)
                WidgetRenderer.refreshAll(context, cards)
                GameAlerts.check(context, cards)
                // Re-arm every time: this is a one-shot alarm precisely so the
                // interval can change with the games, so nothing repeats unless
                // it is asked to.
                RefreshScheduler.arm(context, cards)
            }

            ACTION_ADVANCE -> {
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

                Background.run(this) {
                    /*
                     * Steps the index and repaints from the cache. No fetch:
                     * the other cards were fetched in the same pass as the one
                     * on screen, and a tap that went to the network would make
                     * paging through five games five requests and five visible
                     * delays.
                     */
                    val cards = GameRepository.cards(context)
                    if (cards.isEmpty()) return@run

                    val next = TeamFilter.wrapIndex(
                        ScoreSettings.carouselIndex(context, id) + 1, cards.size
                    )
                    ScoreSettings.setCarouselIndex(context, id, next)

                    val manager = AppWidgetManager.getInstance(context) ?: return@run
                    manager.updateAppWidget(
                        id, WidgetRenderer.build(context, manager, id, cards)
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dotgrid.scorewidget.REFRESH"
        const val ACTION_ADVANCE = "com.dotgrid.scorewidget.ADVANCE"
    }
}
