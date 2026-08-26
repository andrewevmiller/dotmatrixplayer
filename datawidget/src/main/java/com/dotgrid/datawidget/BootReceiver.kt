package com.dotgrid.datawidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the refresh back after the two events that silently take it away.
 *
 * A reboot clears every alarm the device had set, and replacing the package
 * (an app update, or a reinstall over the top) stops the app, which does the
 * same. Neither delivers anything to the widget provider, so without this the
 * tile would fall back to its 30-minute `updatePeriodMillis` and nobody would
 * notice for a fortnight.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                RefreshScheduler.arm(context)
                // Paint once now as well: after a reboot the tile is showing
                // whatever the host had cached, which is last session's number.
                // Off the main thread - boot is exactly when the stats service
                // is busiest and slowest to answer.
                Background.run(this) { WidgetRenderer.refreshAll(context) }
            }
        }
    }
}
