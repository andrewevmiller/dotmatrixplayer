package com.dotgrid.datawidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Keeps the number moving.
 *
 * `updatePeriodMillis` alone would do this, but its floor is 30 minutes, and a
 * data tile that can be half an hour stale is a tile you stop trusting - the
 * moment you actually look at it is the moment you have just streamed
 * something. Fifteen minutes is the useful interval.
 *
 * The alarm is deliberately **inexact**. An exact alarm would need
 * SCHEDULE_EXACT_ALARM, which Android 13 makes the user grant by hand, to buy
 * nothing: the number this widget shows moves in megabytes, not in seconds, and
 * nobody is timing the repaint. Inexact alarms are also batched with everything
 * else the device wakes for, so the tile costs no wakeup of its own.
 */
object RefreshScheduler {

    private const val INTERVAL_MS = 15 * 60 * 1000L

    fun arm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        /*
         * Same PendingIntent every time, so this replaces any alarm already
         * set rather than stacking a second one - which makes arm() safe to
         * call from onEnabled, from boot, from the config screen and from the
         * alarm's own firing, without any of them having to know about the
         * others.
         */
        manager.setInexactRepeating(
            // RTC, not RTC_WAKEUP: if the screen is off, nobody is reading the
            // tile, and it will be repainted before it is next looked at.
            AlarmManager.RTC,
            System.currentTimeMillis() + INTERVAL_MS,
            INTERVAL_MS,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DataWidgetProvider::class.java).apply {
            action = DataWidgetProvider.ACTION_REFRESH
            // Explicit component: a broadcast to our own provider, resolvable
            // without the manifest having to export a filter for it.
            component = ComponentName(context, DataWidgetProvider::class.java)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private const val REQUEST_CODE = 1001
}
