package com.dotgrid.mediawidget

import android.content.Context
import android.content.Intent
import android.service.media.MediaBrowserService

/**
 * Which app's session should win the widget's idle fallback, for when more
 * than one app holds a titled-but-not-playing session at once.
 *
 * [MediaHub.activeController] otherwise falls through to the platform's own
 * priority order - index 0 of whatever [android.media.session.MediaSessionManager]
 * hands back - which is not necessarily the app the user means. Someone
 * running both a podcast app and a music app, say, can pin the one they
 * actually want the widget to remember when neither is playing.
 */
object IdlePreference {

    private const val PREFS = "idle_preference"
    private const val KEY_PACKAGE = "preferred_package"

    /** Null means no preference: fall back to the platform's own ordering. */
    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PACKAGE, null)

    fun set(context: Context, packageName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (packageName == null) remove(KEY_PACKAGE) else putString(KEY_PACKAGE, packageName)
        }.apply()
    }

    data class Candidate(val packageName: String, val label: String)

    /**
     * Every installed app able to hand out a media session, for the picker.
     * Same discovery mechanism [SessionResumer] uses to find a browser
     * service, just without pinning it to one package first.
     */
    fun candidates(context: Context): List<Candidate> {
        val pm = context.packageManager
        val intent = Intent(MediaBrowserService.SERVICE_INTERFACE)
        return runCatching {
            pm.queryIntentServices(intent, 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
                .mapNotNull { pkg ->
                    runCatching {
                        val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        Candidate(pkg, label)
                    }.getOrNull()
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
}
