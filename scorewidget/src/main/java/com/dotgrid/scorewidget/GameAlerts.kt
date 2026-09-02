package com.dotgrid.scorewidget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * The three notifications the settings menu can arm: start, close finish,
 * final.
 *
 * Everything here is driven by comparing the freshly fetched cards against what
 * was on the tile last time. There is no push channel and no server - the app
 * finds out a game has started the same way the tile does, by asking, so an
 * alert is only ever as timely as the refresh interval behind it. See
 * [RefreshScheduler]: the interval tightens to a minute while anything is live
 * and to five minutes in the hour before a start, which is what makes "start"
 * and "close finish" land near enough to be worth having.
 *
 * ### On the permission
 *
 * The manifest holds `POST_NOTIFICATIONS`, but [ConfigActivity] does not ask
 * for it until the first toggle is switched on - the only moment the request
 * has a reason the user can see - and [post] checks the grant again anyway,
 * because it can be revoked from system settings at any time afterwards.
 */
object GameAlerts {

    private const val TAG = "GameAlerts"

    private const val CHANNEL_ID = "score_alerts"

    private const val PREFS = "score_alert_state"

    /**
     * What was last seen for each game, so a change can be detected.
     *
     * Keyed by game id and holding the state name plus the last margin. Both
     * are needed: the state alone catches start and final, and the margin is
     * what makes a close finish fire once rather than on every repaint for the
     * last six minutes of the game.
     */
    private const val KEY_SEEN_PREFIX = "seen_"
    private const val KEY_CLOSE_FIRED_PREFIX = "close_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Compares [cards] against the last seen state and posts whatever the user
     * asked for.
     *
     * Called on [Background]'s worker straight after a fetch, before the tiles
     * repaint - so a game that has just gone final has its notification posted
     * and its state recorded in the same pass that draws the final score.
     */
    fun check(context: Context, cards: List<Game>) {
        val armed = ScoreSettings.alerts(context)
        if (armed == 0) {
            // Still record the states. Otherwise arming an alert later would
            // fire it retroactively for every game already in progress.
            cards.forEach { remember(context, it) }
            return
        }
        if (!granted(context)) return

        cards.forEach { game ->
            val previous = prefs(context).getString(KEY_SEEN_PREFIX + game.id, null)

            /*
             * A game we have never seen before gets recorded and nothing else.
             *
             * Otherwise every game that is already live when the widget is
             * first placed - or when the process is restarted after a
             * force-stop - would arrive looking exactly like a game that just
             * started, and the user would get a fistful of notifications for
             * games that kicked off hours ago.
             */
            if (previous == null) {
                remember(context, game)
                return@forEach
            }
            if (previous == game.state.name) {
                maybeCloseFinish(context, game, armed)
                return@forEach
            }

            when (game.state) {
                GameState.LIVE ->
                    if (ScoreSettings.hasAlert(armed, ScoreSettings.ALERT_START)) {
                        post(context, game, startLine(game), game.id.hashCode())
                    }
                GameState.FINAL ->
                    if (ScoreSettings.hasAlert(armed, ScoreSettings.ALERT_FINAL)) {
                        post(context, game, finalLine(game), game.id.hashCode() + 1)
                    }
                GameState.SCHEDULED -> Unit
            }
            remember(context, game)
        }

        prune(context, cards)
    }

    private fun maybeCloseFinish(context: Context, game: Game, armed: Int) {
        if (!ScoreSettings.hasAlert(armed, ScoreSettings.ALERT_CLOSE)) return
        if (!WinProbability.isCloseFinish(game)) return

        // Once per game. The condition stays true for the whole closing
        // stretch, and a notification per refresh through the last six minutes
        // is the fastest way to have the user turn this off for good.
        val key = KEY_CLOSE_FIRED_PREFIX + game.id
        if (prefs(context).getBoolean(key, false)) return
        prefs(context).edit().putBoolean(key, true).apply()

        post(context, game, closeLine(game), game.id.hashCode() + 2)
    }

    private fun remember(context: Context, game: Game) {
        prefs(context).edit().putString(KEY_SEEN_PREFIX + game.id, game.state.name).apply()
    }

    /**
     * Drops state for games no longer on any card.
     *
     * Without it this file grows by a handful of keys every day and never
     * shrinks - and the close-finish flags in particular would eventually
     * collide with a reused game id.
     */
    private fun prune(context: Context, cards: List<Game>) {
        val live = cards.map { it.id }.toSet()
        val store = prefs(context)
        val editor = store.edit()
        store.all.keys.forEach { key ->
            val id = when {
                key.startsWith(KEY_SEEN_PREFIX) -> key.removePrefix(KEY_SEEN_PREFIX)
                key.startsWith(KEY_CLOSE_FIRED_PREFIX) -> key.removePrefix(KEY_CLOSE_FIRED_PREFIX)
                else -> return@forEach
            }
            if (id !in live) editor.remove(key)
        }
        editor.apply()
    }

    private fun startLine(game: Game): String =
        game.away.abbrev + " at " + game.home.abbrev + " has started"

    private fun finalLine(game: Game): String {
        val awayWon = game.awayScore > game.homeScore
        val winner = if (awayWon) game.away else game.home
        val loser = if (awayWon) game.home else game.away
        val high = maxOf(game.awayScore, game.homeScore)
        val low = minOf(game.awayScore, game.homeScore)
        if (high == low) {
            return game.away.abbrev + " " + game.awayScore + ", " +
                game.home.abbrev + " " + game.homeScore
        }
        return winner.abbrev + " beat " + loser.abbrev + " " + high + "-" + low
    }

    private fun closeLine(game: Game): String =
        "Close finish: " + game.away.abbrev + " " + game.awayScore + ", " +
            game.home.abbrev + " " + game.homeScore

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun post(context: Context, game: Game, text: String, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager, context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.score_notification_icon)
            .setContentTitle(game.league.label)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "Could not post alert", it) }
    }

    private fun ensureChannel(manager: NotificationManager, context: Context) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.scorewidget_config_section_alerts),
                // DEFAULT, not HIGH: these are worth a sound, not a heads-up
                // card over whatever the user is doing. A score is never
                // urgent.
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
