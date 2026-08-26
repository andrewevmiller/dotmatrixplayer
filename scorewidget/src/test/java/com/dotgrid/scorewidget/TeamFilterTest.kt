package com.dotgrid.scorewidget

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking rules.
 *
 * These decide which single game gets a tile that can only show one, and every
 * one of them is a rule about time - which is why [TeamFilter.rank] takes `now`
 * and a `Calendar` rather than reading either. A rule about "within 24 hours"
 * cannot be tested against a real clock without the test meaning something
 * different every day it runs.
 */
class TeamFilterTest {

    private val now = 1_772_000_000_000L // a fixed instant; nothing depends on which

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    /** October, when all five leagues are playing - so nothing is filtered. */
    private fun autumn(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 15)
        }

    /**
     * July, which is the only month that cleanly splits the five: baseball is
     * mid-season and the other four are dark. October will not do for the
     * offseason tests - MLB runs to November, so in autumn every league is on.
     */
    private fun summer(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JULY, 15)
        }

    private fun team(abbrev: String, league: League = League.NFL) =
        Team(abbrev, abbrev, league)

    private fun game(
        id: String,
        away: String,
        home: String,
        state: GameState,
        startsAt: Long? = now,
        league: League = League.NFL
    ) = Game(
        id = id,
        league = league,
        state = state,
        away = team(away, league),
        home = team(home, league),
        awayScore = 0,
        homeScore = 0,
        startsAt = startsAt,
        clock = null,
        period = 1,
        statusDetail = null
    )

    private fun rank(
        games: List<Game>,
        favorites: List<String>,
        rivalries: Boolean = true,
        filterOffseason: Boolean = true,
        calendar: Calendar = autumn()
    ) = TeamFilter.rank(games, favorites, rivalries, filterOffseason, now, calendar)

    // ---- tiers -----------------------------------------------------------

    @Test
    fun `a live favourite outranks one about to start`() {
        val live = game("live", "DAL", "NYG", GameState.LIVE)
        val soon = game("soon", "KC", "DEN", GameState.SCHEDULED, now + 2 * hour)

        val ranked = rank(listOf(soon, live), listOf("NFL/KC", "NFL/DAL"))

        // KC is the higher favourite, but its game has not started - live wins
        // the tile regardless of whose it is.
        assertEquals("live", ranked.first().id)
    }

    @Test
    fun `an imminent game outranks one that just finished`() {
        val finished = game("done", "DAL", "NYG", GameState.FINAL, now - 2 * hour)
        val soon = game("soon", "DAL", "PHI", GameState.SCHEDULED, now + 3 * hour)

        val ranked = rank(listOf(finished, soon), listOf("NFL/DAL"))
        assertEquals("soon", ranked.first().id)
    }

    @Test
    fun `favourite order breaks ties inside a tier`() {
        val first = game("a", "KC", "DEN", GameState.LIVE)
        val second = game("b", "DAL", "NYG", GameState.LIVE)

        assertEquals("b", rank(listOf(first, second), listOf("NFL/DAL", "NFL/KC")).first().id)
        assertEquals("a", rank(listOf(first, second), listOf("NFL/KC", "NFL/DAL")).first().id)
    }

    // ---- windows ---------------------------------------------------------

    @Test
    fun `a game beyond tomorrow still ranks, below everything current`() {
        val later = game("later", "DAL", "NYG", GameState.SCHEDULED, now + 3 * day)
        val soon = game("soon", "DAL", "PHI", GameState.SCHEDULED, now + 2 * hour)

        val ranked = rank(listOf(later, soon), listOf("NFL/DAL"))
        assertEquals(listOf("soon", "later"), ranked.map { it.id })
    }

    @Test
    fun `a game that has already started but is not live is dropped`() {
        // A scheduled game with a start time in the past is a stale feed entry,
        // not a fixture - showing it would put a countdown on a game that has
        // presumably already kicked off.
        val stale = game("stale", "DAL", "NYG", GameState.SCHEDULED, now - hour)
        assertTrue(rank(listOf(stale), listOf("NFL/DAL")).isEmpty())
    }

    @Test
    fun `a final from last week is dropped`() {
        val ancient = game("old", "DAL", "NYG", GameState.FINAL, now - 8 * day)
        assertTrue(rank(listOf(ancient), listOf("NFL/DAL")).isEmpty())
    }

    // ---- rivalries -------------------------------------------------------

    @Test
    fun `a rival fills the tile when no favourite is on`() {
        // PHI is a division rival of DAL. DAL are not playing.
        val rival = game("rival", "PHI", "WSH", GameState.LIVE)

        val ranked = rank(listOf(rival), listOf("NFL/DAL"))
        assertEquals("rival", ranked.single().id)
    }

    @Test
    fun `a rival is dropped entirely when a favourite is active`() {
        val mine = game("mine", "DAL", "NYG", GameState.LIVE)
        val rival = game("rival", "PHI", "WSH", GameState.LIVE)

        val ranked = rank(listOf(rival, mine), listOf("NFL/DAL"))

        // This is the rule the setting is named for: rivals fill an empty tile,
        // they do not share a carousel with the team you actually follow.
        assertEquals(listOf("mine"), ranked.map { it.id })
    }

    @Test
    fun `rivals can be turned off`() {
        val rival = game("rival", "PHI", "WSH", GameState.LIVE)
        assertTrue(rank(listOf(rival), listOf("NFL/DAL"), rivalries = false).isEmpty())
    }

    // ---- offseason -------------------------------------------------------

    @Test
    fun `an offseason favourite is filtered out in July`() {
        // The NFL is dark in July; MLB is mid-season. The football game is in
        // the feed either way - what the filter drops is the favourite, which
        // is what stops the widget asking for that scoreboard at all.
        val baseball = game("mlb", "NYY", "BOS", GameState.LIVE, now, League.MLB)
        val football = game("nfl", "DAL", "NYG", GameState.LIVE)

        val ranked = rank(
            listOf(baseball, football),
            listOf("NFL/DAL", "MLB/NYY"),
            calendar = summer()
        )
        assertEquals(listOf("mlb"), ranked.map { it.id })
    }

    @Test
    fun `turning the offseason filter off lets the game through`() {
        val football = game("nfl", "DAL", "NYG", GameState.LIVE)

        val ranked = rank(
            listOf(football),
            listOf("NFL/DAL"),
            filterOffseason = false,
            calendar = summer()
        )
        assertEquals(listOf("nfl"), ranked.map { it.id })
    }

    @Test
    fun `every favourite being out of season means an empty tile`() {
        val football = game("nfl", "DAL", "NYG", GameState.LIVE)
        assertTrue(rank(listOf(football), listOf("NFL/DAL"), calendar = summer()).isEmpty())
    }

    // ---- housekeeping ----------------------------------------------------

    @Test
    fun `a favourite playing a favourite is one card, not two`() {
        val derby = game("derby", "DAL", "PHI", GameState.LIVE)
        val ranked = rank(listOf(derby), listOf("NFL/DAL", "NFL/PHI"))
        assertEquals(1, ranked.size)
    }

    @Test
    fun `no favourites means no cards`() {
        val anything = game("x", "DAL", "NYG", GameState.LIVE)
        assertTrue(rank(listOf(anything), emptyList()).isEmpty())
    }

    @Test
    fun `the carousel is capped`() {
        val many = (1..12).map {
            game("g$it", "DAL", "T$it", GameState.LIVE)
        }
        assertEquals(ScoreSettings.MAX_CARDS, rank(many, listOf("NFL/DAL")).size)
    }

    // ---- wrapping --------------------------------------------------------

    @Test
    fun `pick wraps forwards and backwards`() {
        val cards = listOf(
            game("a", "DAL", "NYG", GameState.LIVE),
            game("b", "KC", "DEN", GameState.LIVE),
            game("c", "SF", "SEA", GameState.LIVE)
        )

        assertEquals("a", TeamFilter.pick(cards, 0)?.id)
        assertEquals("c", TeamFilter.pick(cards, 2)?.id)
        // Past the end, wraps round.
        assertEquals("a", TeamFilter.pick(cards, 3)?.id)
        assertEquals("b", TeamFilter.pick(cards, 4)?.id)

        // Kotlin's % keeps the sign of the dividend, so a negative index would
        // throw without the double modulo in wrapIndex.
        assertEquals("c", TeamFilter.pick(cards, -1)?.id)
        assertEquals("a", TeamFilter.pick(cards, -3)?.id)
    }

    @Test
    fun `pick on an empty list is null rather than a crash`() {
        assertNull(TeamFilter.pick(emptyList(), 3))
        assertEquals(0, TeamFilter.wrapIndex(3, 0))
    }

    /**
     * The list behind a stored index shrinks under it all the time - a game
     * ends, a rival drops off - and the tile has to keep drawing.
     */
    @Test
    fun `a stored index larger than the list still resolves`() {
        val cards = listOf(game("only", "DAL", "NYG", GameState.LIVE))
        assertEquals("only", TeamFilter.pick(cards, 7)?.id)
    }
}
