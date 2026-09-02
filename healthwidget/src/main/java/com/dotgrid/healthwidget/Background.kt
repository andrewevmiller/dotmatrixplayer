package com.dotgrid.healthwidget

import android.content.BroadcastReceiver
import android.util.Log
import java.util.concurrent.Executors

/**
 * Runs a receiver's work off the main thread without letting the broadcast end
 * underneath it.
 *
 * Every entry point into this app is a broadcast - an appwidget update, a
 * resize, the refresh alarm, boot - and `onReceive` runs on the main thread.
 * The work behind all of them is a Health Connect query, which is a binder call
 * into another app that reads its own database. It is usually tens of
 * milliseconds and occasionally is not, and one of the times it is not is
 * straight after boot, when every health app on the device is asking that
 * provider something at once.
 *
 * `goAsync` is what keeps the process alive for the trip. Without it the
 * broadcast is finished the moment `onReceive` returns, and the app becomes a
 * candidate for death while the query is still outstanding.
 */
internal object Background {

    private const val TAG = "Background"

    /**
     * One thread, shared. These are all the same job on the same data, and two
     * of them racing would only mean two queries and two repaints for one
     * answer.
     */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "health-widget").apply { isDaemon = true }
    }

    fun run(receiver: BroadcastReceiver, block: () -> Unit) {
        // goAsync returns the pending result once and null after that. Nothing
        // here calls it twice, but a null would mean the broadcast is already
        // finished, and running the work anyway is better than crashing on it.
        val pending = runCatching { receiver.goAsync() }.getOrNull()
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Widget update failed", e)
            } finally {
                pending?.finish()
            }
        }
    }
}
