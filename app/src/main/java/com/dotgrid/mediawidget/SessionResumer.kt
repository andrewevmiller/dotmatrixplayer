package com.dotgrid.mediawidget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import android.util.Log

/**
 * Restarts playback in an app whose media session has already gone away.
 *
 * A dead app has no session, so there is no controller to send `play()` to. The
 * way back in is the same one Android's own media-resumption tile uses: connect
 * to the app's [MediaBrowserService], which starts the service, and drive the
 * session token it hands back.
 *
 * Apps are free to refuse. Several big ones allowlist Android Auto and Wear in
 * `onGetRoot` and reject everyone else, so a refusal is expected rather than
 * exceptional - when it happens we just open the app, which is what the user
 * would have done anyway.
 */
object SessionResumer {

    private const val TAG = "SessionResumer"

    /** A refusal usually arrives fast; this is only a backstop against a hang. */
    private const val CONNECT_TIMEOUT_MS = 4_000L

    /**
     * @param onFinished always invoked exactly once, on the main thread, however
     *   the attempt ends - the caller uses it to release a broadcast's
     *   PendingResult.
     */
    fun resume(context: Context, packageName: String, onFinished: () -> Unit) {
        val app = context.applicationContext
        val service = browserService(app, packageName)
        if (service == null) {
            Log.i(TAG, "$packageName publishes no MediaBrowserService; opening it instead")
            launch(app, packageName)
            onFinished()
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var browser: MediaBrowser? = null
        var settled = false

        // One-shot guard: connection callbacks and the timeout race each other,
        // and disconnecting twice throws.
        fun settle(playedOk: Boolean) {
            if (settled) return
            settled = true
            handler.removeCallbacksAndMessages(null)
            runCatching { browser?.disconnect() }
            if (!playedOk) launch(app, packageName)
            WidgetRenderer.refreshAll(app)
            NotificationHookService.requestSync(app)
            onFinished()
        }

        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val played = runCatching {
                    val controller = MediaController(app, browser!!.sessionToken)
                    // prepare() first: an app that was cold-started may need to
                    // restore its queue before play() means anything.
                    controller.transportControls.prepare()
                    controller.transportControls.play()
                }.onFailure { Log.w(TAG, "Connected to $packageName but could not start it", it) }
                    .isSuccess
                settle(played)
            }

            override fun onConnectionFailed() {
                Log.i(TAG, "$packageName refused the browser connection; opening it instead")
                settle(false)
            }

            override fun onConnectionSuspended() = settle(false)
        }

        browser = MediaBrowser(app, service, callback, null)
        handler.postDelayed({
            Log.i(TAG, "$packageName did not answer in time; opening it instead")
            settle(false)
        }, CONNECT_TIMEOUT_MS)

        runCatching { browser.connect() }.onFailure { settle(false) }
    }

    /**
     * The app's media-browser entry point, if it publishes one.
     *
     * An app may publish several. Spotify, for instance, exposes both a
     * media-*library* service and a media-*browser* one, and only the latter is
     * the transport entry point - so prefer a service that names itself as the
     * browser before falling back to whatever the package manager lists first.
     */
    private fun browserService(context: Context, packageName: String): ComponentName? {
        val intent = Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName)
        return runCatching {
            val candidates = context.packageManager.queryIntentServices(intent, 0)
                .mapNotNull { it.serviceInfo }
                .filter { it.exported }
            val chosen = candidates.firstOrNull { it.name.endsWith("MediaBrowserService") }
                ?: candidates.firstOrNull { it.name.contains("MediaBrowser") }
                ?: candidates.firstOrNull()
            chosen?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull()
    }

    /** Fallback: put the user in front of the app so they can press play there. */
    fun launch(context: Context, packageName: String) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open $packageName", it) }
    }
}
