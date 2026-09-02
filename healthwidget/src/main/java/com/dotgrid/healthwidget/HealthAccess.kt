package com.dotgrid.healthwidget

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * Which Health Connect permission belongs to which metric, and whether we have
 * it.
 *
 * Health Connect grants one read at a time. Ticking OXYGEN on the settings
 * screen is a statement about what the tile should show, not a grant - the
 * grant is a separate trip through Health Connect's own sheet, and the user
 * can come back having given some of what was asked for and not the rest. So
 * "enabled" and "granted" are two different questions everywhere in this app,
 * and this is where the second one is answered.
 */
object HealthAccess {

    /**
     * Reading in the background is what makes a home-screen tile work: it is
     * repainted by a broadcast while nothing of ours is on screen, and without
     * this Health Connect answers those reads with a SecurityException.
     *
     * It is granted separately from the reads themselves and is the one
     * permission the user is most likely to skip, so nothing here treats it as
     * required - [HealthSnapshot] falls back to the last figures it managed to
     * read instead.
     */
    val BACKGROUND_PERMISSION: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun permissionFor(metric: Int): String = when (metric) {
        HealthSettings.METRIC_SLEEP -> HealthPermission.getReadPermission(SleepSessionRecord::class)
        HealthSettings.METRIC_HEART -> HealthPermission.getReadPermission(HeartRateRecord::class)
        HealthSettings.METRIC_OXYGEN ->
            HealthPermission.getReadPermission(OxygenSaturationRecord::class)
        HealthSettings.METRIC_BREATH ->
            HealthPermission.getReadPermission(RespiratoryRateRecord::class)
        else -> HealthPermission.getReadPermission(StepsRecord::class)
    }

    /**
     * What to ask for, given what the user has turned on.
     *
     * Only the enabled metrics. Asking for all five whatever the tile shows
     * would put three reads the user did not want in front of them on the
     * grant sheet, which is how an app teaches someone to deny the lot.
     */
    fun permissionsFor(prefs: HealthSettings.Prefs): Set<String> =
        prefs.enabledMetrics().map { permissionFor(it) }.toSet() + BACKGROUND_PERMISSION

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    fun sdkAvailable(context: Context): Boolean =
        sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /**
     * The permissions currently held, or an empty set if we cannot ask.
     *
     * Every failure mode here - no provider, a provider mid-update, a client
     * that will not construct - means the same thing to every caller: we hold
     * nothing. Distinguishing them would only push the same `catch` out into
     * three call sites.
     */
    suspend fun granted(context: Context): Set<String> {
        if (!sdkAvailable(context)) return emptySet()
        return runCatching {
            HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
        }.getOrDefault(emptySet())
    }
}
