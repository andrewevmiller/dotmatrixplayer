package com.dotgrid.healthwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Keeps the figures moving.
 *
 * `updatePeriodMillis` alone would do this, but its floor is 30 minutes, and a
 * step count that can be half an hour stale is one you stop trusting - the
 * moment you actually look at it is the moment you have just walked somewhere.
 * Five minutes is the useful interval - close enough behind a walk that the
 * dial has usually caught up by the time it is looked at again, without
 * waking Health Connect so often that the query becomes the thing draining
 * the battery it is meant to be reporting on.
 *
 * The alarm is deliberately **inexact**. An exact alarm would need
 * SCHEDULE_EXACT_ALARM, which Android 13 makes the user grant by hand, to buy
 * nothing: these figures move in steps and minutes, not in seconds, and nobody
 * is timing the repaint. Inexact alarms are also batched with everything else
 * the device wakes for, so the tile costs no wakeup of its own.
 *
 * setInexactRepeating has not snapped an arbitrary interval to one of a fixed
 * set of buckets since KitKat - that behaviour belonged to the pre-19 API and
 * does not apply here. What still applies on current Android is Doze and App
 * Standby: with the screen off and the device idle, the system can defer an
 * RTC alarm well past its requested interval regardless of the number chosen,
 * and no interval - five minutes or fifteen - is exempt from that without
 * SCHEDULE_EXACT_ALARM. So five minutes is honoured while the device is
 * awake or being carried, and is a request rather than a guarantee once it is
 * idle in a pocket - which is the same trade this file already made at
 * fifteen.
 */
object RefreshScheduler {

    private const val INTERVAL_MS = 5 * 60 * 1000L

    fun arm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        /*
         * Same PendingIntent every time, so this replaces any alarm already set
         * rather than stacking a second one - which makes arm() safe to call
         * from onEnabled, from boot, from the settings screen and from the
         * alarm's own firing, without any of them having to know about the
         * others.
         */
        manager.setInexactRepeating(
            // RTC, not RTC_WAKEUP: if the screen is off, nobody is reading the
            // tile, and it will be repainted before it is next looked at. A
            // health widget waking the phone every quarter of an hour through
            // the night would be measuring sleep by interrupting it.
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
        val intent = Intent(context, NothingHealthWidgetProvider::class.java).apply {
            action = NothingHealthWidgetProvider.ACTION_REFRESH
            // Explicit component: a broadcast to our own provider, resolvable
            // without the manifest having to export a filter for it.
            component = ComponentName(context, NothingHealthWidgetProvider::class.java)
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
