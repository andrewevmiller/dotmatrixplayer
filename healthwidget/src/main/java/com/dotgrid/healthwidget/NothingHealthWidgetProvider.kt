package com.dotgrid.healthwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The tile itself.
 *
 * It owns no state: every repaint re-reads the settings and re-queries Health
 * Connect. A cached total would be wrong the moment the user changed what
 * counts as sleep on the settings screen, and the query is cheap enough that
 * holding on to the answer buys nothing. The one thing that is remembered -
 * the last figure each metric returned - lives in [LastGood], and is there for
 * a different reason entirely.
 *
 * Everything below hands its work to [Background]. `onUpdate`,
 * `onAppWidgetOptionsChanged` and `onReceive` are all dispatched on the main
 * thread, and the work each of them does is a binder call into another app.
 */
class NothingHealthWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Background.run(this) {
            val snapshot = HealthSnapshot.read(context)
            appWidgetIds.forEach { id ->
                appWidgetManager.updateAppWidget(
                    id,
                    WidgetRenderer.build(context, appWidgetManager, id, snapshot)
                )
            }
        }
    }

    /**
     * Resize.
     *
     * This is what makes the tile resizable in any sense that matters. Every
     * label on it is a bitmap drawn at a chosen size, and the dial and rails
     * are drawn at their on-screen pixel width - so a tile that is not
     * repainted after a drag keeps the bitmaps it was given at its old size and
     * either scales them into mush or leaves them marooned. The layout swap
     * from dial to list happens here too.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Background.run(this) {
            appWidgetManager.updateAppWidget(
                appWidgetId,
                WidgetRenderer.build(
                    context, appWidgetManager, appWidgetId, HealthSnapshot.read(context)
                )
            )
        }
    }

    override fun onEnabled(context: Context) {
        // First tile placed - start the refresh.
        RefreshScheduler.arm(context)
    }

    override fun onDisabled(context: Context) {
        // Last tile removed; nothing left to keep fresh.
        RefreshScheduler.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_REFRESH) {
            /*
             * Re-arm on every firing. setInexactRepeating does repeat on its
             * own, and arming again simply replaces the pending alarm rather
             * than adding a second - but an alarm set before a reboot or a
             * force-stop is gone, and this costs nothing to make certain of.
             */
            RefreshScheduler.arm(context)

            /*
             * The alarm fires every five minutes - nowhere near this window -
             * so the only thing this guards against is the manual button
             * being mashed, which would otherwise queue one Health Connect
             * read behind another for no reading anyone could see change.
             */
            val now = System.currentTimeMillis()
            if (now - lastRefreshAt < REFRESH_DEBOUNCE_MS) return
            lastRefreshAt = now

            Background.run(this) { WidgetRenderer.refreshAll(context) }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dotgrid.healthwidget.REFRESH"

        private const val REFRESH_DEBOUNCE_MS = 2_000L

        @Volatile
        private var lastRefreshAt = 0L

        /**
         * One of the three places a tap on the tile can be pointed. Named here
         * rather than in [HealthSettings] because the manifest has to declare
         * it in `<queries>` for [WidgetRenderer] to see it at all.
         */
        const val FITBIT_PACKAGE = "com.fitbit.FitbitMobile"
    }
}
