package com.dotgrid.healthwidget

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId

/**
 * One read of everything the tile shows.
 *
 * The widget owns no state: every repaint re-reads the settings and re-queries
 * Health Connect. A cached total would be wrong the moment the user changed
 * what counts as sleep, which is the whole point of the settings screen.
 *
 * The one thing that *is* cached is the last figure each metric successfully
 * returned - see [LastGood]. That is not an optimisation either. Without the
 * background-read permission, Health Connect refuses every query that arrives
 * while nothing of ours is on screen, which is every query a home-screen tile
 * makes; the alternative to showing yesterday's figure with the time it was
 * read is showing a dash forever.
 */
class HealthSnapshot(
    val sdkStatus: Int,
    val granted: Set<String>,
    val readings: Map<Int, Metrics.Reading>,
    /** When the figures on show were actually read - not when they were drawn. */
    val readAt: Long,
    /** True when at least one figure came out of the cache rather than off the wire. */
    val stale: Boolean,
    val prefs: HealthSettings.Prefs,
    val accentColor: Int
) {

    val sdkAvailable: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    val backgroundGranted: Boolean get() = HealthAccess.BACKGROUND_PERMISSION in granted

    /** True when not one of the enabled metrics has been granted. */
    val noAccess: Boolean
        get() = prefs.enabledMetrics().none { HealthAccess.permissionFor(it) in granted }

    fun reading(metric: Int): Metrics.Reading =
        readings[metric] ?: Metrics.Reading(null, granted = false)

    fun fraction(metric: Int): Float? = Metrics.fraction(metric, reading(metric), prefs)

    fun goalMet(metric: Int): Boolean = Metrics.goalMet(metric, reading(metric), prefs)

    fun styled(style: Int): Boolean = prefs.styled(style)

    companion object {

        private const val TAG = "HealthSnapshot"

        /**
         * Sleep sessions are read over a window wider than the one they are
         * counted in, then clipped by [SleepMath].
         *
         * A night that starts at 23:00 and ends at 07:00 sits half outside a
         * midnight-anchored window, and asking Health Connect for exactly the
         * window risks losing the record that carries it. Eighteen hours of
         * slack is more than any single session, and the clipping means the
         * extra reach cannot inflate the total.
         */
        private const val SLEEP_LOOKBACK_MS = 18L * 60 * 60 * 1000

        /** How far back to look for a vital before calling it absent. */
        private const val VITAL_LOOKBACK_MS = 24L * 60 * 60 * 1000

        /**
         * Blocking, because every caller is already on a worker thread.
         *
         * The widget arrives through [Background], which pairs a single thread
         * with `goAsync`; the settings screen has its own executor. Neither has
         * a coroutine scope worth the ceremony, and both want the answer before
         * they go on.
         */
        fun read(context: Context): HealthSnapshot = runBlocking { readSuspend(context) }

        suspend fun readSuspend(context: Context): HealthSnapshot {
            val prefs = HealthSettings.read(context)
            val accent = HealthSettings.colorFor(context, prefs.colorChoice)
            val status = HealthAccess.sdkStatus(context)
            val now = System.currentTimeMillis()

            if (status != HealthConnectClient.SDK_AVAILABLE) {
                return HealthSnapshot(
                    sdkStatus = status,
                    granted = emptySet(),
                    readings = emptyMap(),
                    readAt = now,
                    stale = false,
                    prefs = prefs,
                    accentColor = accent
                )
            }

            val granted = HealthAccess.granted(context)
            val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()

            val readings = HashMap<Int, Metrics.Reading>()
            var stale = false

            for (metric in prefs.enabledMetrics()) {
                val hasPermission = HealthAccess.permissionFor(metric) in granted
                if (client == null || !hasPermission) {
                    readings[metric] = Metrics.Reading(null, granted = hasPermission)
                    continue
                }

                val fresh = runCatching { readMetric(client, metric, prefs, now) }
                    .onFailure {
                        // A denied background read lands here, and so does a
                        // provider that has gone away mid-query. Both mean the
                        // same thing to the tile: no answer this time.
                        Log.i(TAG, "Read failed for metric $metric", it)
                    }
                    .getOrNull()

                if (fresh != null) {
                    LastGood.put(context, metric, fresh, now)
                    readings[metric] = Metrics.Reading(fresh, granted = true)
                } else {
                    val cached = LastGood.get(context, metric)
                    if (cached != null) stale = true
                    readings[metric] = Metrics.Reading(cached, granted = true)
                }
            }

            return HealthSnapshot(
                sdkStatus = status,
                granted = granted,
                readings = readings,
                readAt = if (stale) LastGood.readAt(context, now) else now,
                stale = stale,
                prefs = prefs,
                accentColor = accent
            )
        }

        private suspend fun readMetric(
            client: HealthConnectClient,
            metric: Int,
            prefs: HealthSettings.Prefs,
            now: Long
        ): Double? = when (metric) {
            HealthSettings.METRIC_SLEEP -> readSleep(client, prefs, now).toDouble()
            HealthSettings.METRIC_HEART -> readHeart(client, now)
            HealthSettings.METRIC_OXYGEN -> readOxygen(client, now)
            HealthSettings.METRIC_BREATH -> readBreath(client, now)
            else -> readSteps(client, now)
        }

        /**
         * Steps are counted over the calendar day so far - local midnight to
         * now - which is how every step counter on the phone defines a day, and
         * the only definition under which the tile and the health app agree.
         *
         * No records is zero, not unknown. A day with no steps in it yet is a
         * real answer, and a dash there would send the user looking for a
         * permission problem that is not there.
         */
        private suspend fun readSteps(client: HealthConnectClient, now: Long): Double {
            val zone = ZoneId.systemDefault()
            val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(midnight, Instant.ofEpochMilli(now))
                )
            )
            return (result[StepsRecord.COUNT_TOTAL] ?: 0L).toDouble()
        }

        /**
         * Sleep is read raw rather than aggregated.
         *
         * `SleepSessionRecord.SLEEP_DURATION_TOTAL` would give one number, but
         * it gives Health Connect's definition of the total, and the whole
         * point of the sleep settings is that the user picks a different one.
         * Stages only exist on the records themselves.
         */
        private suspend fun readSleep(
            client: HealthConnectClient,
            prefs: HealthSettings.Prefs,
            now: Long
        ): Long {
            val window = SleepMath.window(now, prefs.sleepWindow, ZoneId.systemDefault())

            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(window.start - SLEEP_LOOKBACK_MS),
                        Instant.ofEpochMilli(window.end)
                    ),
                    // A window is at most 24 hours wide and a night is one
                    // session, or a handful when a tracker fragments it. Two
                    // hundred is far past any real answer, so there is no
                    // second page to go back for.
                    pageSize = 200
                )
            ).records

            val sessions = records.map { record ->
                SleepMath.Session(
                    start = record.startTime.toEpochMilli(),
                    end = record.endTime.toEpochMilli(),
                    stages = record.stages.map { stage ->
                        SleepMath.Stage(
                            start = stage.startTime.toEpochMilli(),
                            end = stage.endTime.toEpochMilli(),
                            kind = stageKind(stage.stage)
                        )
                    }
                )
            }

            return SleepMath.totalMinutes(
                sessions = sessions,
                mode = prefs.sleepMode,
                countUnstaged = prefs.countUnstaged,
                window = window
            )
        }

        /**
         * Health Connect's stage numbering, mapped across one at a time.
         *
         * [SleepMath] numbers its own kinds identically, so this is the
         * identity function today. It is written out anyway: SleepMath is the
         * file that decides what counts as asleep, and it should not inherit a
         * renumbering from a library upgrade without someone noticing.
         */
        private fun stageKind(stage: Int): Int = when (stage) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> SleepMath.STAGE_AWAKE
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepMath.STAGE_SLEEPING
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepMath.STAGE_OUT_OF_BED
            SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepMath.STAGE_LIGHT
            SleepSessionRecord.STAGE_TYPE_DEEP -> SleepMath.STAGE_DEEP
            SleepSessionRecord.STAGE_TYPE_REM -> SleepMath.STAGE_REM
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepMath.STAGE_AWAKE_IN_BED
            else -> SleepMath.STAGE_UNKNOWN
        }

        /**
         * The most recent sample, not a day's average.
         *
         * A mean heart rate over twenty-four hours is a number nobody has a use
         * for - it mixes a run with a nap and lands somewhere neither of them
         * was. The latest reading is the one a glance at a tile is asking for.
         */
        private suspend fun readHeart(client: HealthConnectClient, now: Long): Double? =
            latest(client, HeartRateRecord::class, now)
                ?.samples
                ?.maxByOrNull { it.time }
                ?.beatsPerMinute
                ?.toDouble()

        private suspend fun readOxygen(client: HealthConnectClient, now: Long): Double? =
            latest(client, OxygenSaturationRecord::class, now)?.percentage?.value

        private suspend fun readBreath(client: HealthConnectClient, now: Long): Double? =
            latest(client, RespiratoryRateRecord::class, now)?.rate

        /** The newest record of its type in the last day, or null if there is none. */
        private suspend fun <T : androidx.health.connect.client.records.Record> latest(
            client: HealthConnectClient,
            type: kotlin.reflect.KClass<T>,
            now: Long
        ): T? = client.readRecords(
            ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(
                    Instant.ofEpochMilli(now - VITAL_LOOKBACK_MS),
                    Instant.ofEpochMilli(now)
                ),
                // Newest first, one row. Reading the day and sorting it here
                // would pull a continuous heart-rate trace - thousands of
                // samples - across the binder to keep the last one.
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()
    }
}
