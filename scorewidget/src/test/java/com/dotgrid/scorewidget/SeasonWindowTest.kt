package com.dotgrid.scorewidget

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offseason windows.
 *
 * This is the one piece of arithmetic in the module that is both easy to get
 * wrong and impossible to notice: four of the five leagues have a season that
 * wraps the new year, and the naive `month in first..last` reads as an empty
 * range for every one of them. A widget that quietly filtered out the NFL for
 * twelve months of the year would look exactly like a widget with no NFL games
 * on today.
 */
class SeasonWindowTest {

    private fun at(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            // Calendar months are 0-based; every call site here is 1-based.
            set(year, month - 1, day)
        }

    // ---- the wrapping seasons -------------------------------------------

    @Test
    fun `NFL is in season either side of the new year`() {
        assertTrue(League.NFL.inSeason(at(2026, 9, 10)))
        assertTrue(League.NFL.inSeason(at(2026, 12, 25)))
        assertTrue(League.NFL.inSeason(at(2027, 1, 15)))
        assertTrue(League.NFL.inSeason(at(2027, 2, 8)))
    }

    @Test
    fun `NFL is out of season in the spring and summer`() {
        assertFalse(League.NFL.inSeason(at(2026, 3, 1)))
        assertFalse(League.NFL.inSeason(at(2026, 6, 15)))
        assertFalse(League.NFL.inSeason(at(2026, 8, 31)))
    }

    @Test
    fun `NBA and NHL wrap the same way`() {
        listOf(League.NBA, League.NHL).forEach { league ->
            assertTrue(league.label, league.inSeason(at(2026, 10, 20)))
            assertTrue(league.label, league.inSeason(at(2027, 1, 5)))
            assertTrue(league.label, league.inSeason(at(2027, 6, 10)))
            assertFalse(league.label, league.inSeason(at(2027, 7, 10)))
            assertFalse(league.label, league.inSeason(at(2027, 9, 1)))
        }
    }

    @Test
    fun `NCAAF runs August to January`() {
        assertTrue(League.NCAAF.inSeason(at(2026, 8, 25)))
        assertTrue(League.NCAAF.inSeason(at(2027, 1, 10)))
        assertFalse(League.NCAAF.inSeason(at(2027, 2, 1)))
        assertFalse(League.NCAAF.inSeason(at(2027, 7, 31)))
    }

    // ---- the one that does not wrap --------------------------------------

    @Test
    fun `MLB runs inside a single calendar year`() {
        assertTrue(League.MLB.inSeason(at(2026, 3, 28)))
        assertTrue(League.MLB.inSeason(at(2026, 7, 4)))
        assertTrue(League.MLB.inSeason(at(2026, 11, 1)))
        assertFalse(League.MLB.inSeason(at(2026, 1, 15)))
        assertFalse(League.MLB.inSeason(at(2026, 12, 20)))
    }

    /**
     * Every league is playing at some point, and no league is playing all year.
     *
     * A blanket check, because the failure mode this whole class exists for -
     * an inverted range - produces exactly one of those two states.
     */
    @Test
    fun `every league has both a season and an offseason`() {
        League.entries.forEach { league ->
            val months = (1..12).map { league.inSeason(at(2026, it, 15)) }
            assertTrue(league.label + " is never in season", months.any { it })
            assertTrue(league.label + " is never out of season", months.any { !it })
        }
    }
}
