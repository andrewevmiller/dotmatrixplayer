package com.dotgrid.mediawidget

import android.content.Context
import android.media.session.MediaController

/**
 * The order [SessionCarousel] pages through sources in, as the user arranged
 * it on the setup screen.
 *
 * The carousel needs *an* order badly enough that it invented one - sorting by
 * package name - purely so that "next" lands somewhere predictable. That is
 * stable but arbitrary: it puts com.nothing.hearthstone before the music app
 * someone actually uses, because 'n' sorts before 's'. This lets them say
 * otherwise.
 *
 * Partial by design. Only the apps the user has actually moved are stored;
 * everything else keeps the alphabetical fallback behind them, so ordering two
 * favourites does not mean ranking every media app on the device.
 */
object SourceOrder {

    private const val PREFS = "source_order"
    private const val KEY_ORDER = "order"

    /** One key per app, holding when it was last seen holding a session. */
    private const val SEEN_PREFIX = "seen_"

    /**
     * How long an app stays in the settings list after it last played
     * something. Long enough to cover an app used weekly, short enough that
     * the list does not slowly accumulate everything ever played on the
     * device and become the twenty-odd-row list this replaced.
     */
    private const val RETENTION_MS = 15L * 24 * 60 * 60 * 1000

    /**
     * Timestamps are only refreshed this often. Without it the 1 Hz repaint
     * would be a 1 Hz write, and fifteen days does not need second accuracy.
     */
    private const val TOUCH_THROTTLE_MS = 60L * 60 * 1000

    /**
     * A character that cannot occur in a package name, so no escaping is
     * needed and a stray one cannot split an entry in half.
     */
    private const val SEPARATOR = '\n'

    /** The stored order, minus anything since uninstalled. */
    fun get(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null)
            ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun set(context: Context, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORDER, packages.joinToString(SEPARATOR.toString()))
            .apply()
    }

    /** Back to alphabetical. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun hasCustomOrder(context: Context): Boolean = get(context).isNotEmpty()

    /**
     * Sorts sessions into the user's order, with unranked apps following in
     * the alphabetical order the carousel used before this existed.
     *
     * Ranked apps come first rather than last: someone who drags one app to
     * the top means "start here", and burying their choice behind a dozen
     * unranked ones would read as the setting having done nothing.
     */
    fun sort(context: Context, sessions: List<MediaController>): List<MediaController> {
        val ranked = get(context)
        if (ranked.isEmpty()) return sessions.sortedBy { it.packageName }

        return sessions.sortedWith(
            compareBy(
                { ranked.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { it.packageName }
            )
        )
    }

    /**
     * Records apps observed holding a media session.
     *
     * This is what the settings list is built from, and the reason it is not
     * simply every app that publishes a MediaBrowserService: on a real device
     * that is twenty-odd apps, most of which - a newspaper, a cloud drive, a
     * university app - will never hold a session anyone wants to page to.
     * Ordering a list like that is mostly scrolling past things that cannot
     * appear in the ring anyway.
     *
     * Written only when the set actually grows, so the 1 Hz repaint does not
     * become a 1 Hz write.
     */
    fun remember(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val stale = packages.filter { pkg ->
            now - prefs.getLong(SEEN_PREFIX + pkg, 0L) > TOUCH_THROTTLE_MS
        }
        if (stale.isEmpty()) return

        prefs.edit().apply {
            stale.forEach { putLong(SEEN_PREFIX + it, now) }
        }.apply()
    }

    /**
     * Packages seen within [RETENTION_MS], newest first, pruning anything past
     * the window as it goes.
     *
     * Pruning here rather than on a timer: this runs whenever the settings
     * list is built, which is the only moment the expiry is observable.
     */
    private fun seenRecently(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val entries = prefs.all
            .filterKeys { it.startsWith(SEEN_PREFIX) }
            .mapNotNull { (key, value) ->
                val at = value as? Long ?: return@mapNotNull null
                key.removePrefix(SEEN_PREFIX) to at
            }

        val (live, expired) = entries.partition { now - it.second <= RETENTION_MS }

        if (expired.isNotEmpty()) {
            prefs.edit().apply {
                expired.forEach { remove(SEEN_PREFIX + it.first) }
            }.apply()

            // An app that has aged out should not keep a slot in the order it
            // is no longer listed in, or it would silently reappear ranked the
            // day it is played again.
            val survivors = get(context).filter { pkg -> live.any { it.first == pkg } }
            if (survivors.size != get(context).size) set(context, survivors)
        }

        return live.sortedByDescending { it.second }.map { it.first }
    }

    /** A row in the settings list. [named] is false when only the package id is known. */
    data class Entry(val packageName: String, val label: String, val named: Boolean)

    /**
     * The list to show in settings: apps seen holding a session inside the
     * retention window, in the user's order, then the rest by name.
     *
     * An app whose label cannot be read is listed by package id rather than
     * dropped. That runs against the rule [MediaHub] follows for the widget's
     * own text - never show a package id to the user, because there it reads
     * as a bug - and the difference is the context. Package-visibility
     * filtering hides some apps (com.nothing.hearthstone is one) that still
     * publish a session the carousel can reach, so dropping them here made a
     * source the user could page *to* but could not reorder. An unlovely row
     * they can move is worth more than a tidy list that cannot control the
     * ring.
     */
    fun listForSettings(context: Context): List<Entry> {
        val ranked = get(context)
        val recent = seenRecently(context)
        val pm = context.packageManager

        val resolved = (ranked + recent).distinct().map { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }

            Entry(pkg, label ?: pkg, named = label != null)
        }

        // Ranked entries keep the user's order; the rest fall in by name so the
        // tail does not appear to shuffle between visits.
        val rankIndex = ranked.withIndex().associate { (i, pkg) -> pkg to i }
        return resolved.sortedWith(
            compareBy(
                { rankIndex[it.packageName] ?: Int.MAX_VALUE },
                { it.label.lowercase() }
            )
        )
    }
}
