package com.dotgrid.healthwidget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Re-arms the refresh after a reboot or an update.
 *
 * Alarms do not survive either one. Without this the tile would fall back to
 * `updatePeriodMillis` - half-hourly at best - until something else happened to
 * call [RefreshScheduler.arm], which on a phone that is never reconfigured is
 * never.
 *
 * MY_PACKAGE_REPLACED as well as BOOT_COMPLETED: installing over the app stops
 * it, which drops the alarm exactly as a reboot does, and that is the case a
 * developer hits every build and a user hits on every update.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        // Nothing to keep fresh if no tile is placed. onEnabled arms it when
        // the first one arrives.
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context, NothingHealthWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return

        RefreshScheduler.arm(context)
        Background.run(this) { WidgetRenderer.refreshAll(context) }
    }
}
