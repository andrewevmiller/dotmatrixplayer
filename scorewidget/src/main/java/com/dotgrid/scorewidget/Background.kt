package com.dotgrid.scorewidget

import android.content.BroadcastReceiver
import android.util.Log
import java.util.concurrent.Executors

/**
 * Runs a receiver's work off the main thread without letting the broadcast end
 * underneath it.
 *
 * Every entry point into this app is a broadcast - an appwidget update, a
 * resize, a pager tap, the refresh alarm, boot - and `onReceive` runs on the
 * main thread. The work behind most of them is an HTTPS request, which is not
 * merely slow but illegal there: the framework throws
 * `NetworkOnMainThreadException` rather than letting it through.
 *
 * `goAsync` is what keeps the process alive for the trip. Without it the
 * broadcast is finished the moment `onReceive` returns, and the app becomes a
 * candidate for death while the fetch is still outstanding.
 */
internal object Background {

    private const val TAG = "Background"

    /**
     * One thread, shared. These are all the same job on the same data, and two
     * of them racing would only mean two fetches and two repaints for one
     * answer - on a metered connection, which the sibling modules never touch
     * at all.
     */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "score-widget").apply { isDaemon = true }
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
