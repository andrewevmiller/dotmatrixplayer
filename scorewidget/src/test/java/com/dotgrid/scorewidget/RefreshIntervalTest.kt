package com.dotgrid.scorewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often the tile asks.
 *
 * This is the one setting in the module with a cost attached to getting it
 * wrong in either direction: too slow and the score is a rumour, too fast and
 * the widget is spending someone's battery and data polling a scoreboard that
 * has nothing on it. The rule is the same shape as the ranking - it is entirely
 * about time - so it takes `now` rather than reading a clock.
 */
class RefreshIntervalTest {

    private val now = 1_772_000_000_000L
    private val minute = 60L * 1000
    private val hour = 60 * minute

    private fun game(
        id: String,
        state: GameState,
        startsAt: Long?
    ) = Game(
        id = id,
        league = League.NFL,
        state = state,
        away = Team("AWY", "Away", League.NFL),
        home = Team("HME", "Home", League.NFL),
        awayScore = 0,
        homeScore = 0,
        startsAt = startsAt,
        clock = null,
        period = 1,
        statusDetail = null
    )

    @Test
    fun `a live game refreshes every minute`() {
        val cards = listOf(game("a", GameState.LIVE, now))
        assertEquals(minute, RefreshScheduler.intervalFor(cards, now))
    }

    @Test
    fun `a live game wins even when other cards are idle`() {
        val cards = listOf(
            game("done", GameState.FINAL, now - 3 * hour),
            game("live", GameState.LIVE, now),
            game("later", GameState.SCHEDULED, now + 6 * hour)
        )
        assertEquals(minute, RefreshScheduler.intervalFor(cards, now))
    }

    @Test
    fun `a game starting within the hour refreshes every five minutes`() {
        val cards = listOf(game("soon", GameState.SCHEDULED, now + 30 * minute))
        assertEquals(5 * minute, RefreshScheduler.intervalFor(cards, now))
    }

    @Test
    fun `a game later today refreshes every half hour`() {
        val cards = listOf(game("later", GameState.SCHEDULED, now + 5 * hour))
        assertEquals(30 * minute, RefreshScheduler.intervalFor(cards, now))
    }

    @Test
    fun `an empty card list backs right off`() {
        assertEquals(3 * hour, RefreshScheduler.intervalFor(emptyList(), now))
    }

    /**
     * A card list holding only finished games is the state a tile sits in all
     * evening after the last game, and it must not keep polling at the pace it
     * was during one.
     */
    @Test
    fun `only-final cards back off too`() {
        val cards = listOf(game("done", GameState.FINAL, now - 2 * hour))
        assertEquals(3 * hour, RefreshScheduler.intervalFor(cards, now))
    }

    @Test
    fun `a start time already past does not pull the interval down`() {
        // A stale scheduled entry should not be read as "starting imminently"
        // and pin the tile to a five-minute poll indefinitely.
        val cards = listOf(game("stale", GameState.SCHEDULED, now - hour))
        assertEquals(3 * hour, RefreshScheduler.intervalFor(cards, now))
    }

    @Test
    fun `the nearest start decides`() {
        val cards = listOf(
            game("far", GameState.SCHEDULED, now + 8 * hour),
            game("near", GameState.SCHEDULED, now + 20 * minute)
        )
        assertEquals(5 * minute, RefreshScheduler.intervalFor(cards, now))
    }

    @Test
    fun `every interval is at least a minute`() {
        // Nothing here should ever produce a poll faster than the platform's
        // own broadcast overhead makes sensible.
        val states = listOf(
            emptyList(),
            listOf(game("a", GameState.LIVE, now)),
            listOf(game("b", GameState.SCHEDULED, now + minute)),
            listOf(game("c", GameState.FINAL, now - minute))
        )
        states.forEach { cards ->
            assertTrue(RefreshScheduler.intervalFor(cards, now) >= minute)
        }
    }
}
