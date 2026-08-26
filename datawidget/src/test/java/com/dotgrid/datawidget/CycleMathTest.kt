package com.dotgrid.datawidget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The rollover date is the setting this widget is built around, and it is the
 * one place where being a day out is both easy and invisible: the tile would
 * still show a plausible number, just for the wrong window.
 *
 * These run on the JVM, so they set an explicit time zone rather than trusting
 * whichever one the build machine is in.
 */
class CycleMathTest {

    private var previousZone: TimeZone = TimeZone.getDefault()

    /**
     * CycleMath works in the default zone, because that is the zone the user's
     * bill is in. Pinning it here makes these cases mean the same thing on a
     * laptop in London and on CI in UTC - and Europe/London is deliberately a
     * zone with daylight saving, so the March cases cross a transition.
     */
    @Before
    fun pinTimeZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(ZONE)
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(previousZone)
    }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0
    ): Long = Calendar.getInstance(ZONE, Locale.US).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun assertDay(expected: Long, actual: Long, label: String) {
        val e = Calendar.getInstance(ZONE, Locale.US).apply { timeInMillis = expected }
        val a = Calendar.getInstance(ZONE, Locale.US).apply { timeInMillis = actual }
        assertEquals(
            "$label year", e.get(Calendar.YEAR), a.get(Calendar.YEAR)
        )
        assertEquals(
            "$label month", e.get(Calendar.MONTH), a.get(Calendar.MONTH)
        )
        assertEquals(
            "$label day", e.get(Calendar.DAY_OF_MONTH), a.get(Calendar.DAY_OF_MONTH)
        )
        assertEquals("$label hour", 0, a.get(Calendar.HOUR_OF_DAY))
        assertEquals("$label minute", 0, a.get(Calendar.MINUTE))
    }

    @Test
    fun `past the rollover day, the cycle opened this month`() {
        val cycle = CycleMath.current(at(2026, 3, 20), cycleDay = 14)
        assertDay(at(2026, 3, 14), cycle.startMillis, "start")
        assertDay(at(2026, 4, 14), cycle.endMillis, "end")
    }

    @Test
    fun `before the rollover day, the cycle opened last month`() {
        val cycle = CycleMath.current(at(2026, 3, 5), cycleDay = 14)
        assertDay(at(2026, 2, 14), cycle.startMillis, "start")
        assertDay(at(2026, 3, 14), cycle.endMillis, "end")
    }

    @Test
    fun `the rollover day itself belongs to the new cycle`() {
        // 00:01 on the 14th is the first minute of the new allowance, not the
        // last of the old one.
        val cycle = CycleMath.current(at(2026, 3, 14, hour = 0, minute = 1), cycleDay = 14)
        assertDay(at(2026, 3, 14), cycle.startMillis, "start")
        assertDay(at(2026, 4, 14), cycle.endMillis, "end")
    }

    @Test
    fun `a cycle opening in January rolls back across the year`() {
        val cycle = CycleMath.current(at(2026, 1, 3), cycleDay = 20)
        assertDay(at(2025, 12, 20), cycle.startMillis, "start")
        assertDay(at(2026, 1, 20), cycle.endMillis, "end")
    }

    @Test
    fun `a 31st rollover clamps to the last day of a short month`() {
        // February has no 31st. The cycle opens on the 28th rather than
        // spilling into 1 March, which would hand February a 29-day cycle.
        val cycle = CycleMath.current(at(2026, 2, 20), cycleDay = 31)
        assertDay(at(2026, 1, 31), cycle.startMillis, "start")
        assertDay(at(2026, 2, 28), cycle.endMillis, "end")
    }

    @Test
    fun `a clamped rollover returns to the 31st the following month`() {
        // The clamp is per month, not sticky: March has a 31st, so March gets
        // it back rather than staying on the 28th February was held to.
        val cycle = CycleMath.current(at(2026, 3, 1), cycleDay = 31)
        assertDay(at(2026, 2, 28), cycle.startMillis, "start")
        assertDay(at(2026, 3, 31), cycle.endMillis, "end")
    }

    @Test
    fun `a 30th rollover clamps in February and holds in April`() {
        assertDay(
            at(2026, 2, 28),
            CycleMath.current(at(2026, 3, 10), cycleDay = 30).startMillis,
            "february start"
        )
        assertDay(
            at(2026, 4, 30),
            CycleMath.current(at(2026, 5, 10), cycleDay = 30).startMillis,
            "april start"
        )
    }

    @Test
    fun `a leap February takes the 29th`() {
        val cycle = CycleMath.current(at(2028, 2, 20), cycleDay = 31)
        assertDay(at(2028, 1, 31), cycle.startMillis, "start")
        assertDay(at(2028, 2, 29), cycle.endMillis, "end")
    }

    @Test
    fun `days left counts whole days to the rollover`() {
        // Midday on the 20th, rolling over at midnight opening the 25th: four
        // and a half days, which the tile shows as five - it rounds up, so
        // "1D LEFT" never appears on a day that still has hours in it.
        assertEquals(5, CycleMath.current(at(2026, 3, 20), cycleDay = 25).daysLeft)
    }

    @Test
    fun `days left is one on the eve of the rollover`() {
        assertEquals(1, CycleMath.current(at(2026, 3, 24, hour = 23), cycleDay = 25).daysLeft)
    }

    @Test
    fun `an out of range rollover day is clamped rather than rejected`() {
        // Nothing in the UI can produce these, but a restored backup from a
        // future version could.
        assertDay(
            at(2026, 3, 1),
            CycleMath.current(at(2026, 3, 10), cycleDay = 0).startMillis,
            "day zero"
        )
        assertDay(
            at(2026, 3, 31),
            CycleMath.current(at(2026, 4, 10), cycleDay = 99).startMillis,
            "day ninety-nine"
        )
    }

    private companion object {
        val ZONE: TimeZone = TimeZone.getTimeZone("Europe/London")
    }
}
