package com.dotgrid.healthwidget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The sleep reading is the part of this app most likely to be quietly wrong.
 *
 * Every case below produces a plausible number when it is broken - an hour out,
 * a night double counted, a nap in the wrong day - so none of them can be
 * checked by looking at a phone in the morning. They are also the cases a real
 * device only offers occasionally: two writers on the same night needs a watch
 * and a phone both writing, an unstaged session needs an app that does not do
 * stages, and the window edges only come round once a day each.
 */
class SleepMathTest {

    private val utc = ZoneId.of("UTC")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** A window wide enough that nothing in these fixtures is clipped by it. */
    private val openWindow = SleepMath.Span(at("2026-08-23T00:00:00Z"), at("2026-08-24T23:59:00Z"))

    /**
     * A staged night: 23:00 to 07:00, with half an hour awake in the middle.
     * Seven and a half hours asleep inside eight hours in bed.
     */
    private fun stagedNight() = SleepMath.Session(
        start = at("2026-08-23T23:00:00Z"),
        end = at("2026-08-24T07:00:00Z"),
        stages = listOf(
            SleepMath.Stage(
                at("2026-08-23T23:00:00Z"), at("2026-08-24T01:00:00Z"), SleepMath.STAGE_LIGHT
            ),
            SleepMath.Stage(
                at("2026-08-24T01:00:00Z"), at("2026-08-24T03:00:00Z"), SleepMath.STAGE_DEEP
            ),
            SleepMath.Stage(
                at("2026-08-24T03:00:00Z"), at("2026-08-24T03:30:00Z"), SleepMath.STAGE_AWAKE
            ),
            SleepMath.Stage(
                at("2026-08-24T03:30:00Z"), at("2026-08-24T05:00:00Z"), SleepMath.STAGE_REM
            ),
            SleepMath.Stage(
                at("2026-08-24T05:00:00Z"), at("2026-08-24T07:00:00Z"), SleepMath.STAGE_LIGHT
            )
        )
    )

    private fun total(
        sessions: List<SleepMath.Session>,
        mode: Int,
        countUnstaged: Boolean = true,
        window: SleepMath.Span = openWindow
    ) = SleepMath.totalMinutes(sessions, mode, countUnstaged, window)

    // ---- the four readings ----------------------------------------------

    @Test
    fun `asleep adds the sleeping stages and leaves out the awake one`() {
        // 2h light + 2h deep + 1.5h REM + 2h light = 7h30, awake excluded.
        assertEquals(
            450L,
            total(listOf(stagedNight()), HealthSettings.SLEEP_ASLEEP)
        )
    }

    @Test
    fun `in bed is the whole session, awake minutes included`() {
        assertEquals(
            480L,
            total(listOf(stagedNight()), HealthSettings.SLEEP_IN_BED)
        )
    }

    @Test
    fun `restful is deep plus rem`() {
        // 2h deep + 1.5h REM.
        assertEquals(
            210L,
            total(listOf(stagedNight()), HealthSettings.SLEEP_RESTFUL)
        )
    }

    @Test
    fun `deep is deep alone`() {
        assertEquals(
            120L,
            total(listOf(stagedNight()), HealthSettings.SLEEP_DEEP)
        )
    }

    @Test
    fun `awake in bed counts as in bed but not as asleep`() {
        val session = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T01:00:00Z"),
            stages = listOf(
                SleepMath.Stage(
                    at("2026-08-23T23:00:00Z"), at("2026-08-24T00:00:00Z"),
                    SleepMath.STAGE_AWAKE_IN_BED
                ),
                SleepMath.Stage(
                    at("2026-08-24T00:00:00Z"), at("2026-08-24T01:00:00Z"),
                    SleepMath.STAGE_SLEEPING
                )
            )
        )
        assertEquals(60L, total(listOf(session), HealthSettings.SLEEP_ASLEEP))
        assertEquals(120L, total(listOf(session), HealthSettings.SLEEP_IN_BED))
    }

    @Test
    fun `in bed subtracts time spent out of bed`() {
        val session = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T03:00:00Z"),
            stages = listOf(
                SleepMath.Stage(
                    at("2026-08-24T01:00:00Z"), at("2026-08-24T01:30:00Z"),
                    SleepMath.STAGE_OUT_OF_BED
                )
            )
        )
        // Four hours of session, half an hour of it out of bed.
        assertEquals(210L, total(listOf(session), HealthSettings.SLEEP_IN_BED))
    }

    // ---- sessions with no stages ----------------------------------------

    @Test
    fun `an unstaged session counts as asleep when asked to`() {
        val bare = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T06:30:00Z"),
            stages = emptyList()
        )
        assertEquals(
            450L,
            total(listOf(bare), HealthSettings.SLEEP_ASLEEP, countUnstaged = true)
        )
    }

    @Test
    fun `an unstaged session is left out when not asked for`() {
        val bare = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T06:30:00Z"),
            stages = emptyList()
        )
        assertEquals(
            0L,
            total(listOf(bare), HealthSettings.SLEEP_ASLEEP, countUnstaged = false)
        )
    }

    @Test
    fun `an unstaged session is never restful or deep, whatever the setting`() {
        val bare = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T06:30:00Z"),
            stages = emptyList()
        )
        assertEquals(
            0L,
            total(listOf(bare), HealthSettings.SLEEP_RESTFUL, countUnstaged = true)
        )
        assertEquals(
            0L,
            total(listOf(bare), HealthSettings.SLEEP_DEEP, countUnstaged = true)
        )
    }

    @Test
    fun `in bed does not need stages`() {
        val bare = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T07:00:00Z"),
            stages = emptyList()
        )
        assertEquals(
            480L,
            total(listOf(bare), HealthSettings.SLEEP_IN_BED, countUnstaged = false)
        )
    }

    // ---- overlaps --------------------------------------------------------

    @Test
    fun `a watch and a phone writing the same night do not double it`() {
        // The same eight hours, twice, offset by twenty minutes the way two
        // devices noticing you fell asleep would be.
        val watch = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T07:00:00Z"),
            stages = emptyList()
        )
        val phone = SleepMath.Session(
            start = at("2026-08-23T23:20:00Z"),
            end = at("2026-08-24T06:50:00Z"),
            stages = emptyList()
        )
        assertEquals(480L, total(listOf(watch, phone), HealthSettings.SLEEP_IN_BED))
        assertEquals(
            480L,
            total(listOf(watch, phone), HealthSettings.SLEEP_ASLEEP, countUnstaged = true)
        )
    }

    @Test
    fun `stages that overlap at their boundaries are merged, not summed`() {
        val session = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = at("2026-08-24T01:00:00Z"),
            stages = listOf(
                SleepMath.Stage(
                    at("2026-08-23T23:00:00Z"), at("2026-08-24T00:05:00Z"), SleepMath.STAGE_LIGHT
                ),
                SleepMath.Stage(
                    at("2026-08-24T00:00:00Z"), at("2026-08-24T01:00:00Z"), SleepMath.STAGE_DEEP
                )
            )
        )
        // Two hours of wall clock, not two hours five minutes of stage.
        assertEquals(120L, total(listOf(session), HealthSettings.SLEEP_ASLEEP))
    }

    // ---- the window ------------------------------------------------------

    @Test
    fun `sleep running past the end of the window is clipped to it`() {
        val session = SleepMath.Session(
            start = at("2026-08-24T04:00:00Z"),
            end = at("2026-08-24T14:00:00Z"),
            stages = emptyList()
        )
        val window = SleepMath.Span(at("2026-08-23T12:00:00Z"), at("2026-08-24T12:00:00Z"))
        assertEquals(
            480L,
            total(listOf(session), HealthSettings.SLEEP_IN_BED, window = window)
        )
    }

    @Test
    fun `sleep starting before the window is clipped to it`() {
        val session = SleepMath.Session(
            start = at("2026-08-23T22:00:00Z"),
            end = at("2026-08-24T06:00:00Z"),
            stages = emptyList()
        )
        val window = SleepMath.Span(at("2026-08-24T00:00:00Z"), at("2026-08-24T12:00:00Z"))
        assertEquals(
            360L,
            total(listOf(session), HealthSettings.SLEEP_IN_BED, window = window)
        )
    }

    @Test
    fun `night in the morning reaches back to yesterday noon and stops at now`() {
        val now = at("2026-08-24T07:00:00Z")
        val window = SleepMath.window(now, HealthSettings.WINDOW_NIGHT, utc)
        assertEquals(at("2026-08-23T12:00:00Z"), window.start)
        // Not today's noon, which has not happened yet: a window that runs into
        // the future is one a launcher would happily draw an empty tile for.
        assertEquals(now, window.end)
    }

    @Test
    fun `night in the evening still means last night, not tonight`() {
        val window = SleepMath.window(at("2026-08-24T21:00:00Z"), HealthSettings.WINDOW_NIGHT, utc)
        assertEquals(at("2026-08-23T12:00:00Z"), window.start)
        assertEquals(at("2026-08-24T12:00:00Z"), window.end)
    }

    @Test
    fun `night just after midnight is still the night that has only just begun`() {
        // 00:30 on the 24th: the window is yesterday noon to now, so sleep from
        // 23:00 is inside it rather than waiting for the next day to claim it.
        val now = at("2026-08-24T00:30:00Z")
        val window = SleepMath.window(now, HealthSettings.WINDOW_NIGHT, utc)
        assertEquals(at("2026-08-23T12:00:00Z"), window.start)
        assertEquals(now, window.end)

        val session = SleepMath.Session(
            start = at("2026-08-23T23:00:00Z"),
            end = now,
            stages = emptyList()
        )
        assertEquals(
            90L,
            total(listOf(session), HealthSettings.SLEEP_IN_BED, window = window)
        )
    }

    @Test
    fun `today starts at local midnight`() {
        val now = at("2026-08-24T07:00:00Z")
        val window = SleepMath.window(now, HealthSettings.WINDOW_TODAY, utc)
        assertEquals(at("2026-08-24T00:00:00Z"), window.start)
        assertEquals(now, window.end)
    }

    @Test
    fun `the rolling window is exactly a day back from now`() {
        val now = at("2026-08-24T07:00:00Z")
        val window = SleepMath.window(now, HealthSettings.WINDOW_24H, utc)
        assertEquals(at("2026-08-23T07:00:00Z"), window.start)
        assertEquals(now, window.end)
    }

    @Test
    fun `a timezone west of UTC anchors on its own noon`() {
        // 07:00 UTC is 03:00 in New York, so the window reaches back to noon on
        // the 23rd local - 16:00 UTC - not to noon UTC.
        val window = SleepMath.window(
            at("2026-08-24T07:00:00Z"),
            HealthSettings.WINDOW_NIGHT,
            ZoneId.of("America/New_York")
        )
        assertEquals(at("2026-08-23T16:00:00Z"), window.start)
    }

    @Test
    fun `no sessions is zero, not a crash`() {
        assertEquals(0L, total(emptyList(), HealthSettings.SLEEP_ASLEEP))
        assertEquals(0L, total(emptyList(), HealthSettings.SLEEP_IN_BED))
    }
}
