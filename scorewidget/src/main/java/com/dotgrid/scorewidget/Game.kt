package com.dotgrid.scorewidget

/**
 * One side of one game.
 *
 * [abbrev] is the identity everywhere in this app - it is what the settings
 * screen stores, what [TeamGlyphs] keys a mark on, and what the tile draws. The
 * display name is carried only so the settings screen can offer something
 * readable to pick from.
 */
data class Team(
    val abbrev: String,
    val name: String,
    val league: League
) {
    /** Unique across leagues, which bare abbreviations are not - see NFL/LAC vs MLB/LAA. */
    val key: String get() = league.code + "/" + abbrev
}

/** Where a game is in its life. Mirrors ESPN's three states, which are the right three. */
enum class GameState { SCHEDULED, LIVE, FINAL }

/**
 * The sport-specific detail that only means something while a game is running.
 *
 * One class for all five sports rather than a sealed hierarchy per sport. The
 * fields are disjoint in practice - a baseball game has no down and a football
 * game has no runner on second - but they are all nullable and all optional,
 * and the renderer already has to decide what to draw from the league. A sealed
 * type would move that decision into a `when` over subclasses that reads the
 * same way and costs a file each.
 */
data class LiveContext(
    // Football
    val down: Int? = null,
    val distance: Int? = null,
    val yardLine: String? = null,
    val possessionAbbrev: String? = null,
    val isRedZone: Boolean = false,

    /**
     * ESPN's own "3rd & 7", when the feed sends one.
     *
     * Preferred over rebuilding the line from [down] and [distance], because
     * the feed knows things this app does not. Goal-to-go is the case that
     * proves it: `yardLine` is an *absolute* 0-100 field position, so a team on
     * the opponent's five is at yard line 95 with a distance of 5, and any
     * local rule comparing the two gets goal-to-go wrong in both directions.
     */
    val downDistanceText: String? = null,

    // Baseball
    val balls: Int? = null,
    val strikes: Int? = null,
    val outs: Int? = null,
    val onFirst: Boolean = false,
    val onSecond: Boolean = false,
    val onThird: Boolean = false,
    /** True in the top half of the inning. Null when it is not a baseball game. */
    val topOfInning: Boolean? = null,

    // Basketball and hockey
    val awayInBonus: Boolean = false,
    val homeInBonus: Boolean = false,
    val powerPlayAbbrev: String? = null
) {
    /**
     * "3RD AND 12", or "3RD AND GOAL".
     *
     * The feed's own string wins whenever it sent one - it already knows about
     * goal-to-go, which cannot be worked out reliably from [yardLine] and
     * [distance] because the yard line is absolute rather than a distance to
     * the end zone. Saying "AND 5" when the ball is on the five is the kind of
     * wrongness a football fan sees instantly.
     *
     * The reconstruction below is the fallback for a payload that carries a
     * down and a distance but no text, and it deliberately does not guess at
     * goal-to-go: "4TH AND 1" on the one yard line is merely incomplete, where
     * a wrong guess in either direction is incorrect.
     */
    fun downAndDistance(): String? {
        downDistanceText?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.uppercase() }

        val d = down ?: return null
        val dist = distance ?: return null
        val ordinal = when (d) {
            1 -> "1ST"
            2 -> "2ND"
            3 -> "3RD"
            4 -> "4TH"
            else -> return null
        }
        return if (dist == 0) "$ordinal AND GOAL" else "$ordinal AND $dist"
    }

    /** "2-1, 2 OUT". Empty rather than null when nothing of it is known. */
    fun countLine(): String? {
        val b = balls ?: return null
        val s = strikes ?: return null
        val o = outs ?: return null
        return "$b-$s, $o OUT"
    }
}

/**
 * A game, as much of it as the tile needs.
 *
 * Deliberately flat and deliberately immutable: this is built on a background
 * thread by [GameRepository] and read on whichever thread is painting, and the
 * only safe version of that is one with no setters.
 */
data class Game(
    val id: String,
    val league: League,
    val state: GameState,

    val away: Team,
    val home: Team,
    val awayScore: Int,
    val homeScore: Int,

    /** Epoch millis of first pitch / kickoff. Null when the feed did not say. */
    val startsAt: Long?,

    /** "12:04" for a running clock; null between periods and before the start. */
    val clock: String?,
    /** 1-based. Quarter, period or inning depending on the league. */
    val period: Int,

    /** ESPN's own short status - "END OF 3RD", "HALFTIME", "FINAL/OT". */
    val statusDetail: String?,

    val situation: LiveContext = LiveContext(),

    /** "ESPN", "FOX", "NBCSN". Null when nothing is listed. */
    val broadcast: String? = null,

    /** "J. ALLEN 312 YDS, 3 TD". Null unless the feed carried one. */
    val topPerformer: String? = null,

    /**
     * Home win probability, 0..1, as the feed reported it - not as we modelled
     * it. Null when the feed carried none, which is most of the time outside
     * the NFL and NBA. [WinProbability] decides what to do about that.
     */
    val feedHomeWinProbability: Float? = null
) {
    val isLive: Boolean get() = state == GameState.LIVE
    val isFinal: Boolean get() = state == GameState.FINAL
    val isScheduled: Boolean get() = state == GameState.SCHEDULED

    /** Whether [team] is one of the two playing. */
    fun involves(team: Team): Boolean = away.key == team.key || home.key == team.key

    fun involves(teamKey: String): Boolean = away.key == teamKey || home.key == teamKey

    /**
     * The clock line: "12:04 2ND", "HALFTIME", "FINAL".
     *
     * ESPN's own detail string is preferred when the game is not simply
     * running, because the states between periods have names - "END OF 3RD",
     * "HALFTIME", "DELAYED" - and reconstructing those from a period number and
     * an absent clock would mean inventing a vocabulary the sport already has.
     */
    fun clockLine(): String {
        if (isFinal) return statusDetail?.uppercase() ?: "FINAL"
        if (isScheduled) return ""

        val running = clock?.takeIf { it.isNotBlank() && it != "0:00" }
        if (running == null) return statusDetail?.uppercase() ?: league.periodLabel(period)

        // Baseball has no clock, so its "clock" is the half of the inning.
        if (league == League.MLB) {
            val half = when (situation.topOfInning) {
                true -> "TOP"
                false -> "BOT"
                null -> ""
            }
            return (half + " " + league.periodLabel(period)).trim()
        }
        return running + " " + league.periodLabel(period)
    }
}
