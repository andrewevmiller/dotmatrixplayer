package com.dotgrid.mediawidget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * The app's one privileged component, and the reason it asks for notification
 * access at all: [MediaSessionManager.getActiveSessions] will only talk to an
 * enabled listener. No notification content is ever read.
 *
 * It also owns the widget's refresh policy. Two things drive a repaint:
 *
 *  - the session itself, via a [MediaController.Callback] - covers track
 *    changes, play/pause, and apps handing off to one another; and
 *  - a one-second ticker, purely so the scrub bar advances. That ticker runs
 *    only while audio is actually playing, the screen is on, and a widget is
 *    actually placed. Any one of those going false stops it.
 */
class NotificationHookService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var attached: MediaController? = null
    private var ticking = false
    private var screenOn = true

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { sync() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // The bar is stale by however long the screen was off.
                    sync()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopTicker()
                }
            }
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = sync()
        override fun onMetadataChanged(metadata: MediaMetadata?) = sync()
        override fun onSessionDestroyed() = sync()
    }

    private val tick = object : Runnable {
        override fun run() {
            WidgetRenderer.refreshAll(this@NotificationHookService)
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this

        sessionManager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, NotificationHookService::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChanged, component)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not observe sessions", e)
        }

        // Screen on/off are protected system broadcasts, so nothing outside the
        // system can reach this receiver. Android 13 introduced the explicit
        // export flag and 14 began enforcing it; below 13 the flag does not
        // exist, hence the split.
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }

        sync()
    }

    override fun onListenerDisconnected() {
        stopTicker()
        detach()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        runCatching { unregisterReceiver(screenReceiver) }
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopTicker()
        detach()
        instance = null
        super.onDestroy()
    }

    /**
     * Repaint, re-point the callback at whichever session is now current, and
     * decide whether the ticker should be running.
     */
    fun sync() {
        val controller = MediaHub.activeController(this)

        // Controllers are not reliably equal across lookups; compare identity of
        // the session token, which is stable for the life of the session.
        if (controller?.sessionToken != attached?.sessionToken) {
            detach()
            attached = controller
            attached?.registerCallback(controllerCallback, handler)
        }

        WidgetRenderer.refreshAll(this)

        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing && screenOn && WidgetRenderer.hasInstances(this)) startTicker() else stopTicker()
    }

    private fun detach() {
        attached?.let { runCatching { it.unregisterCallback(controllerCallback) } }
        attached = null
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopTicker() {
        if (!ticking) return
        ticking = false
        handler.removeCallbacks(tick)
    }

    companion object {
        private const val TAG = "NotificationHook"

        /** One second is the coarsest interval at which a scrub bar still looks live. */
        private const val TICK_MS = 1_000L

        /**
         * Set while the system has us bound. The service cannot be started
         * directly - only the platform binds a NotificationListenerService - so
         * callers reach it through here, and fall back to a plain repaint when
         * access has not been granted yet.
         */
        @Volatile
        private var instance: NotificationHookService? = null

        fun requestSync(context: Context) {
            val live = instance
            if (live != null) live.sync() else WidgetRenderer.refreshAll(context)
        }
    }
}
