package com.dotgrid.scorewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Keeps the score moving, and stops asking when nothing is on.
 *
 * `updatePeriodMillis` alone would not do: its floor is 30 minutes, and a score
 * that can be half an hour stale is not a score, it is a rumour. But the
 * opposite mistake is worse here than on either sibling tile - this is the only
 * one of the three that costs the user data and battery to refresh, and a
 * fixed 60-second alarm running through a February afternoon when none of their
 * teams play until August would be indefensible.
 *
 * So the interval is a function of what is actually on the tile:
 *
 * | state                          | interval |
 * |--------------------------------|----------|
 * | a game is live                 | 1 min    |
 * | a game starts within the hour  | 5 min    |
 * | something today, not yet close | 30 min   |
 * | nothing at all                 | 3 hours  |
 *
 * The alarm is **inexact** at every one of them. An exact alarm would need
 * SCHEDULE_EXACT_ALARM, which Android 13 makes the user grant by hand, to buy
 * nothing - a score is not a deadline, and inexact alarms are batched with
 * whatever else the device is already waking for, so the tile costs no wakeup
 * of its own.
 */
object RefreshScheduler {

    private const val LIVE_MS = 60 * 1000L
    private const val IMMINENT_MS = 5 * 60 * 1000L
    private const val TODAY_MS = 30 * 60 * 1000L
    private const val IDLE_MS = 3 * 60 * 60 * 1000L

    /** How close to a start counts as imminent. */
    private const val IMMINENT_WINDOW_MS = 60 * 60 * 1000L

    /**
     * Arms the next refresh, at whatever interval [cards] justifies.
     *
     * A single alarm re-armed on every firing, not a repeating one: the
     * interval changes as the games do, and `setInexactRepeating` would hold
     * whichever interval was true when it was set - so a tile armed at three
     * hours during an off day would still be on three hours when the game
     * kicked off.
     */
    fun arm(context: Context, cards: List<Game>) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val interval = intervalFor(cards, System.currentTimeMillis())

        manager.set(
            // RTC, not RTC_WAKEUP: if the screen is off, nobody is reading the
            // tile, and it will be repainted before it is next looked at.
            AlarmManager.RTC,
            System.currentTimeMillis() + interval,
            pendingIntent(context)
        )
    }

    /** Visible for testing - the interval decision, without an AlarmManager. */
    fun intervalFor(cards: List<Game>, now: Long): Long {
        if (cards.isEmpty()) return IDLE_MS
        if (cards.any { it.isLive }) return LIVE_MS

        val nextStart = cards.filter { it.isScheduled }
            .mapNotNull { it.startsAt }
            .filter { it > now }
            .minOrNull()

        return when {
            nextStart == null -> IDLE_MS
            nextStart - now <= IMMINENT_WINDOW_MS -> IMMINENT_MS
            else -> TODAY_MS
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScoreWidgetProvider::class.java).apply {
            action = ScoreWidgetProvider.ACTION_REFRESH
            // Explicit component: a broadcast to our own provider, resolvable
            // without the manifest having to export a filter for it.
            component = ComponentName(context, ScoreWidgetProvider::class.java)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private const val REQUEST_CODE = 2001
}
