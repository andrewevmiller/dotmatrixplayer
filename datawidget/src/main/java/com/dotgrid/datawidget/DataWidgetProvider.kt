package com.dotgrid.datawidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The tile itself.
 *
 * It owns no state: every repaint re-reads the plan settings and re-queries the
 * stats service. A cached total would be wrong the moment the user changed the
 * rollover date on the configuration screen, and the query is cheap enough that
 * holding on to the answer buys nothing.
 */
open class DataWidgetProvider : AppWidgetProvider() {

    /*
     * Everything below hands its work to [Background]. onUpdate and
     * onAppWidgetOptionsChanged are both dispatched from onReceive, on the main
     * thread, and the work each of them does is a stats query - see Background
     * for why that does not belong there.
     */

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Background.run(this) {
            val snapshot = UsageSnapshot.read(context)
            appWidgetIds.forEach { id ->
                appWidgetManager.updateAppWidget(
                    id,
                    WidgetRenderer.build(context, appWidgetManager, id, snapshot)
                )
            }
        }
    }

    /** Resize: the dial bitmap is drawn at its on-screen size, so it has to be redrawn. */
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
                    context, appWidgetManager, appWidgetId, UsageSnapshot.read(context)
                )
            )
        }
    }

    override fun onEnabled(context: Context) {
        // First tile placed - start the refresh.
        RefreshScheduler.arm(context)
    }

    override fun onDisabled(context: Context) {
        // Fires once the last instance of *this* provider is gone - but
        // DataWidgetProvider and PillarWidgetProvider are two providers
        // sharing one refresh schedule, so check the sibling too before
        // deciding nothing is left to keep fresh.
        val manager = AppWidgetManager.getInstance(context) ?: return
        val remaining = manager.getAppWidgetIds(ComponentName(context, DataWidgetProvider::class.java)).size +
            manager.getAppWidgetIds(ComponentName(context, PillarWidgetProvider::class.java)).size
        if (remaining == 0) RefreshScheduler.cancel(context)
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
            Background.run(this) { WidgetRenderer.refreshAll(context) }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dotgrid.datawidget.REFRESH"
    }
}
