package com.dotgrid.scorewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the refresh after a reboot or an app update.
 *
 * Alarms do not survive either. `updatePeriodMillis` in the provider info does,
 * which is why it is left on underneath this as the floor - it covers the
 * window between the device coming back and this receiver running, and it is
 * the only thing that would still repaint the tile if this receiver never fired
 * at all.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Background.run(this) {
            /*
             * Paint from the cache and arm against it, rather than fetching.
             *
             * Boot is exactly when the network is least likely to be up - the
             * radio may not have attached yet - and exactly when the app is
             * most likely to be killed for taking its time. The alarm this
             * arms will fetch within the minute if anything is live, and
             * within three hours if nothing is.
             */
            val cards = GameRepository.cards(context)
            WidgetRenderer.refreshAll(context, cards)
            RefreshScheduler.arm(context, cards)
        }
    }
}
