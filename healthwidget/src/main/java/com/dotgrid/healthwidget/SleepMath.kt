package com.dotgrid.healthwidget

import java.time.Instant
import java.time.ZoneId

/**
 * What "seven hours of sleep" means, worked out away from Health Connect.
 *
 * There is no single sleep total. A night can be reported as the whole session
 * end to end, as the minutes actually asleep inside it, or as the restorative
 * part alone, and the three differ by an hour or more on the same night from
 * the same tracker. Which one the tile shows is the user's to choose - see
 * [HealthSettings.SLEEP_ASLEEP] and friends - and this is where the choice is
 * turned into a number.
 *
 * Nothing here touches the Android framework or the Health Connect client:
 * [HealthSnapshot] flattens records into [Session] and [Stage] first. That is
 * deliberate. Sleep sessions overlap, stages overrun the sessions that carry
 * them, two apps write the same night twice, and none of that can be checked
 * by looking at a phone on any given morning - so it is checked in
 * `SleepMathTest` instead.
 */
object SleepMath {

    /*
     * Stage kinds, numbered as Health Connect numbers them. HealthSnapshot
     * still maps them across one by one rather than passing the raw int
     * through: this file is the one that decides what counts as asleep, and
     * it should not silently inherit a renumbering from a library upgrade.
     */
    const val STAGE_UNKNOWN = 0
    const val STAGE_AWAKE = 1
    const val STAGE_SLEEPING = 2
    const val STAGE_OUT_OF_BED = 3
    const val STAGE_LIGHT = 4
    const val STAGE_DEEP = 5
    const val STAGE_REM = 6
    const val STAGE_AWAKE_IN_BED = 7

    /** Half-open: `[start, end)`, in epoch milliseconds. */
    data class Span(val start: Long, val end: Long) {
        val length: Long get() = if (end > start) end - start else 0L
    }

    data class Stage(val start: Long, val end: Long, val kind: Int)

    data class Session(val start: Long, val end: Long, val stages: List<Stage>)

    /**
     * The stretch of time the tile counts sleep over.
     *
     * @param nowMillis the current instant.
     * @param mode one of [HealthSettings.WINDOW_NIGHT], `WINDOW_24H`, `WINDOW_TODAY`.
     */
    fun window(nowMillis: Long, mode: Int, zone: ZoneId): Span = when (mode) {
        HealthSettings.WINDOW_24H -> Span(nowMillis - DAY_MS, nowMillis)

        HealthSettings.WINDOW_TODAY -> {
            val midnight = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            Span(midnight, nowMillis)
        }

        /*
         * NIGHT is the twenty-four hours ending at today's noon, clipped so it
         * never runs past now.
         *
         * Noon rather than midnight because a night crosses midnight: anchored
         * there, sleep from 23:40 to 07:00 would land in two windows and be
         * counted in neither properly. Anchored at noon, the whole night sits
         * inside one window, a glance at 07:00 sees the night that has just
         * ended, and an afternoon nap at 14:00 falls outside it - which is what
         * makes it "night" rather than "the last day".
         */
        else -> {
            val noon = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                .atTime(12, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            Span(noon - DAY_MS, minOf(noon, nowMillis))
        }
    }

    /**
     * Total sleep in whole minutes, for the chosen reading.
     *
     * @param countUnstaged whether a session written with no stage breakdown at
     *   all counts as sleep for its full length. Only [HealthSettings.SLEEP_ASLEEP]
     *   consults it: an unstaged session says nothing about depth, so RESTFUL
     *   and DEEP ignore those sessions whatever this is set to, and IN BED
     *   never needed stages in the first place.
     */
    fun totalMinutes(
        sessions: List<Session>,
        mode: Int,
        countUnstaged: Boolean,
        window: Span
    ): Long = merge(spansFor(sessions, mode, countUnstaged, window))
        .sumOf { it.length } / 60_000L

    private fun spansFor(
        sessions: List<Session>,
        mode: Int,
        countUnstaged: Boolean,
        window: Span
    ): List<Span> = when (mode) {
        /*
         * IN BED is the session itself, not a sum of stages - a tracker that
         * reports no stages still knows when you lay down. Out-of-bed stages
         * come back off, because that is the one stage that explicitly says
         * this part of the session was not spent in bed.
         */
        HealthSettings.SLEEP_IN_BED -> subtract(
            merge(sessions.map { Span(it.start, it.end) }.clip(window)),
            merge(stagesOf(sessions, setOf(STAGE_OUT_OF_BED)).clip(window))
        )

        HealthSettings.SLEEP_RESTFUL -> stagesOf(sessions, RESTFUL_STAGES).clip(window)

        HealthSettings.SLEEP_DEEP -> stagesOf(sessions, setOf(STAGE_DEEP)).clip(window)

        else -> {
            val staged = stagesOf(sessions, ASLEEP_STAGES)
            val unstaged = if (countUnstaged) {
                sessions.filter { it.stages.isEmpty() }.map { Span(it.start, it.end) }
            } else {
                emptyList()
            }
            (staged + unstaged).clip(window)
        }
    }

    private fun stagesOf(sessions: List<Session>, kinds: Set<Int>): List<Span> =
        sessions.flatMap { session ->
            session.stages.filter { it.kind in kinds }.map { Span(it.start, it.end) }
        }

    /** Cuts every span down to the part inside [window], dropping what falls out. */
    private fun List<Span>.clip(window: Span): List<Span> = mapNotNull { span ->
        val start = maxOf(span.start, window.start)
        val end = minOf(span.end, window.end)
        if (end > start) Span(start, end) else null
    }

    /**
     * Collapses overlaps.
     *
     * Not defensive tidying - the overlaps are real. Stages inside one session
     * can be written overlapping by a second or two at the boundaries, and two
     * apps writing the same night (a watch and the phone it is paired to) put
     * two full sessions over the same hours. Summed raw, a seven-hour night
     * reads as fourteen.
     */
    private fun merge(spans: List<Span>): List<Span> {
        val sorted = spans.filter { it.length > 0 }.sortedBy { it.start }
        val out = ArrayList<Span>(sorted.size)
        for (span in sorted) {
            val last = out.lastOrNull()
            if (last != null && span.start <= last.end) {
                if (span.end > last.end) out[out.lastIndex] = Span(last.start, span.end)
            } else {
                out.add(span)
            }
        }
        return out
    }

    /** [base] with every part of [cut] removed. Both must already be merged. */
    private fun subtract(base: List<Span>, cut: List<Span>): List<Span> {
        if (cut.isEmpty()) return base
        val out = ArrayList<Span>(base.size)
        for (span in base) {
            var start = span.start
            for (hole in cut) {
                if (hole.end <= start) continue
                if (hole.start >= span.end) break
                if (hole.start > start) out.add(Span(start, hole.start))
                start = maxOf(start, hole.end)
                if (start >= span.end) break
            }
            if (start < span.end) out.add(Span(start, span.end))
        }
        return out
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val ASLEEP_STAGES = setOf(STAGE_LIGHT, STAGE_DEEP, STAGE_REM, STAGE_SLEEPING)
    private val RESTFUL_STAGES = setOf(STAGE_DEEP, STAGE_REM)
}
