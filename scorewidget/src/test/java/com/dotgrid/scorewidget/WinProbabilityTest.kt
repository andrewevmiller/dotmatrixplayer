package com.dotgrid.scorewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The win-probability curve.
 *
 * What is tested here is the *shape*, not the numbers - the model is a
 * scoreline model and its absolute output is an estimate by construction. What
 * has to hold is that it behaves like a game: the same lead is worth more later
 * than earlier, a tie is a coin flip, and nothing is ever certain while the
 * clock is running.
 */
class WinProbabilityTest {

    private fun team(abbrev: String, league: League) = Team(abbrev, abbrev, league)

    private fun game(
        league: League = League.NFL,
        state: GameState = GameState.LIVE,
        awayScore: Int = 0,
        homeScore: Int = 0,
        period: Int = 1,
        clock: String? = "15:00",
        situation: LiveContext = LiveContext(),
        feedProbability: Float? = null
    ) = Game(
        id = "g",
        league = league,
        state = state,
        away = team("AWY", league),
        home = team("HME", league),
        awayScore = awayScore,
        homeScore = homeScore,
        startsAt = 0L,
        clock = clock,
        period = period,
        statusDetail = null,
        situation = situation,
        feedHomeWinProbability = feedProbability
    )

    // ---- deferring to the feed -------------------------------------------

    @Test
    fun `the feed's own number wins when it has one`() {
        val p = WinProbability.forGame(game(feedProbability = 0.73f))
        assertEquals(0.73f, p!!, 0.0001f)
    }

    // ---- states ----------------------------------------------------------

    @Test
    fun `a scheduled game has no probability`() {
        // Deliberately null rather than 0.5 - this model has never heard of the
        // two rosters, and printing 50% for every fixture looks like information.
        assertNull(WinProbability.forGame(game(state = GameState.SCHEDULED)))
    }

    @Test
    fun `a final game is certain`() {
        assertEquals(
            1f,
            WinProbability.forGame(game(state = GameState.FINAL, homeScore = 21, awayScore = 7))!!,
            0.0001f
        )
        assertEquals(
            0f,
            WinProbability.forGame(game(state = GameState.FINAL, homeScore = 7, awayScore = 21))!!,
            0.0001f
        )
    }

    // ---- the shape -------------------------------------------------------

    @Test
    fun `a tie at kickoff is close to even, with the home edge`() {
        val p = WinProbability.forGame(game(period = 1, clock = "15:00"))!!
        // Home advantage is worth about two points in the NFL, so slightly
        // above a coin flip - but nowhere near decisive.
        assertTrue("was $p", p in 0.50f..0.60f)
    }

    @Test
    fun `the same lead is worth more in the fourth than in the first`() {
        val early = WinProbability.forGame(
            game(homeScore = 10, awayScore = 0, period = 1, clock = "15:00")
        )!!
        val late = WinProbability.forGame(
            game(homeScore = 10, awayScore = 0, period = 4, clock = "2:00")
        )!!
        assertTrue("early $early should be below late $late", late > early)
    }

    @Test
    fun `a bigger lead is always worth more than a smaller one`() {
        val small = WinProbability.forGame(
            game(homeScore = 3, awayScore = 0, period = 3, clock = "5:00")
        )!!
        val big = WinProbability.forGame(
            game(homeScore = 21, awayScore = 0, period = 3, clock = "5:00")
        )!!
        assertTrue(big > small)
    }

    @Test
    fun `trailing is the mirror of leading`() {
        val ahead = WinProbability.forGame(
            game(homeScore = 14, awayScore = 0, period = 2, clock = "10:00")
        )!!
        val behind = WinProbability.forGame(
            game(homeScore = 0, awayScore = 14, period = 2, clock = "10:00")
        )!!
        // Not exactly 1 - x, because the home edge does not flip with the score.
        assertTrue(ahead > 0.5f && behind < 0.5f)
    }

    @Test
    fun `nothing is ever certain while the clock runs`() {
        // The floor under sigma is what stops a one-point lead at 0:00 reading
        // as a mathematical certainty, which it is not in any of these sports.
        val p = WinProbability.forGame(
            game(homeScore = 50, awayScore = 0, period = 4, clock = "0:01")
        )!!
        assertTrue("was $p", p <= 0.98f)
    }

    @Test
    fun `every league produces a probability in range`() {
        League.entries.forEach { league ->
            val p = WinProbability.forGame(
                game(league = league, homeScore = 2, awayScore = 1, period = 2, clock = "5:00")
            )
            assertNotNull(league.label, p)
            assertTrue(league.label + " was " + p, p!! in 0f..1f)
        }
    }

    // ---- baseball counts outs, not seconds -------------------------------

    @Test
    fun `baseball hardens as the innings run out`() {
        val early = WinProbability.forGame(
            game(
                league = League.MLB, homeScore = 2, awayScore = 0, period = 2, clock = "-",
                situation = LiveContext(outs = 1, topOfInning = true)
            )
        )!!
        val late = WinProbability.forGame(
            game(
                league = League.MLB, homeScore = 2, awayScore = 0, period = 9, clock = "-",
                situation = LiveContext(outs = 2, topOfInning = true)
            )
        )!!
        assertTrue("early $early should be below late $late", late > early)
    }

    @Test
    fun `extra innings do not produce a negative remainder`() {
        val p = WinProbability.forGame(
            game(
                league = League.MLB, homeScore = 3, awayScore = 3, period = 12, clock = "-",
                situation = LiveContext(outs = 1, topOfInning = false)
            )
        )!!
        assertTrue("was $p", p in 0f..1f)
    }

    // ---- clock parsing ---------------------------------------------------

    @Test
    fun `the clock parses minutes, seconds and tenths`() {
        assertEquals(724f, WinProbability.clockSeconds("12:04")!!, 0.01f)
        assertEquals(34.2f, WinProbability.clockSeconds("0:34.2")!!, 0.01f)
        // Hockey and basketball both drop to a bare seconds count inside the
        // last minute, which is exactly when the number matters most.
        assertEquals(9f, WinProbability.clockSeconds("9")!!, 0.01f)
        assertNull(WinProbability.clockSeconds(null))
        assertNull(WinProbability.clockSeconds(""))
        assertNull(WinProbability.clockSeconds("halftime"))
    }

    // ---- close finishes --------------------------------------------------

    @Test
    fun `a one-score game in the last minutes is a close finish`() {
        assertTrue(
            WinProbability.isCloseFinish(
                game(homeScore = 21, awayScore = 17, period = 4, clock = "2:00")
            )
        )
    }

    @Test
    fun `a blowout in the last minutes is not`() {
        assertTrue(
            !WinProbability.isCloseFinish(
                game(homeScore = 45, awayScore = 3, period = 4, clock = "2:00")
            )
        )
    }

    @Test
    fun `a one-score game in the first quarter is not`() {
        assertTrue(
            !WinProbability.isCloseFinish(
                game(homeScore = 7, awayScore = 3, period = 1, clock = "10:00")
            )
        )
    }

    @Test
    fun `a game that is not live is never a close finish`() {
        assertTrue(
            !WinProbability.isCloseFinish(
                game(state = GameState.FINAL, homeScore = 21, awayScore = 20, period = 4)
            )
        )
    }
}
