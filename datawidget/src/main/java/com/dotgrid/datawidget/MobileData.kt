package com.dotgrid.datawidget

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log

/**
 * Reads the device's mobile data total for a window.
 *
 * There is exactly one public route to this number: [NetworkStatsManager]. The
 * `TrafficStats` counters are cheaper but reset on reboot and cannot be
 * windowed, so they cannot answer "since the 14th" - which is the whole
 * question this widget exists to answer.
 */
object MobileData {

    private const val TAG = "MobileData"

    /**
     * `PACKAGE_USAGE_STATS` is signature-level: the manifest entry only makes
     * the app appear in Settings > Special app access > Usage access, and the
     * real grant is an appop the user flips there. So the manifest is not the
     * thing to check - the op is.
     */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Total mobile bytes, up and down, over `[startMillis, endMillis]`.
     *
     * @return null when the number could not be read, which the widget shows as
     *   its own state rather than as a zero. A plan that looks untouched and a
     *   plan we cannot see are opposite messages, and printing "0.0 GB" for the
     *   second one is the kind of quiet lie that gets someone a bill.
     */
    fun bytesInWindow(context: Context, startMillis: Long, endMillis: Long): Long? {
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        return try {
            /*
             * A null subscriberId means "every subscriber on this device", which
             * is what a dual-SIM phone's owner means by "my data" and is the
             * only value a non-carrier app is allowed to pass from Q onward
             * anyway - getSubscriberId() is gated behind carrier privileges.
             *
             * TYPE_MOBILE is deprecated as a *ConnectivityManager* concept, but
             * NetworkStatsManager still takes it as its template selector and
             * has no public replacement: the NetworkTemplate overloads are
             * @SystemApi.
             */
            @Suppress("DEPRECATION")
            val bucket = manager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startMillis,
                endMillis
            )
            bucket?.let { it.rxBytes + it.txBytes }
        } catch (e: SecurityException) {
            // Usage access was revoked between the check and the call.
            Log.w(TAG, "Usage access denied for the stats query", e)
            null
        } catch (e: RuntimeException) {
            // The stats service throws IllegalStateException / RemoteException
            // wrappers when its on-disk history is being rotated. Transient.
            Log.w(TAG, "Network stats unavailable", e)
            null
        }
    }
}
