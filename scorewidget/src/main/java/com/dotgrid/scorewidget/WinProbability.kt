package com.dotgrid.scorewidget

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Who is winning, expressed as how likely they are to still be winning at the
 * end.
 *
 * The feed carries a probability of its own for some games, and where it does
 * that is what gets shown - ESPN's model has play-by-play, personnel and a
 * decade of drives behind it, and nothing computed from a scoreline is going to
 * beat it. This exists for the other games, which is most of them: the number
 * only appears for the NFL and NBA with any reliability, and never for a game
 * that has not started.
 *
 * ### The model
 *
 * One idea, applied five ways. The margin at the end of the game is the margin
 * now plus everything still to come, and everything still to come is a random
 * quantity centred on zero. So the home side wins if that quantity lands above
 * minus the current margin, and the probability of that is the normal
 * distribution's tail:
 *
 *     P(home) = phi( (margin + homeEdge) / sigma(t) )
 *
 * where `sigma(t)` is how much the margin can still move, which shrinks as the
 * clock does. A ten-point lead in the first quarter and a ten-point lead with a
 * minute left are the same margin and completely different games; `sigma` is
 * the entire difference between them.
 *
 * It is deliberately a *scoreline* model. It does not know about possession, or
 * that a two-goal lead with an empty net is not a two-goal lead. What it gets
 * right is the shape - a bar that barely moves in the first half and hardens
 * fast in the fourth - which is what a bar 60dp wide can actually communicate.
 */
object WinProbability {

    /**
     * Standard deviation of the margin swing across a whole game, in that
     * sport's own units.
     *
     * These are the numbers that make the curve mean anything, and they are the
     * spread of final margins each sport actually produces - roughly two
     * scores in the NFL, four runs in a baseball game, a couple of goals in
     * hockey. College football is wider than the NFL for the reason everyone
     * who watches it knows: the talent gap between two teams is far wider, so
     * blowouts are ordinary.
     */
    private fun fullGameSigma(league: League): Float = when (league) {
        League.NFL -> 14.0f
        League.NCAAF -> 17.0f
        League.NBA -> 13.0f
        League.NHL -> 2.2f
        League.MLB -> 4.0f
    }

    /**
     * Home advantage, in points/goals/runs of margin.
     *
     * Applied as a bonus to the home margin rather than as a shift on the
     * output, because that is what it is: playing at home is worth about two
     * points in the NFL and about a fifth of a goal in hockey, and expressing
     * it in the sport's own units keeps it comparable to the lead it is being
     * weighed against.
     */
    private fun homeEdge(league: League): Float = when (league) {
        League.NFL -> 2.0f
        League.NCAAF -> 3.0f
        League.NBA -> 2.5f
        League.NHL -> 0.2f
        League.MLB -> 0.15f
    }

    /** Regulation length in minutes, for the leagues that measure themselves in minutes. */
    private fun regulationMinutes(league: League): Float = when (league) {
        League.NFL, League.NCAAF, League.NHL -> 60f
        League.NBA -> 48f
        League.MLB -> 0f
    }

    /**
     * Home win probability for [game], 0..1, or null when the question does not
     * have a useful answer yet.
     *
     * Null before kickoff on purpose. A pre-game probability is a statement
     * about the two rosters, and this model has never heard of them - it would
     * print 50% for every fixture on the card, which is worse than printing
     * nothing because it looks like information.
     */
    fun forGame(game: Game): Float? {
        game.feedHomeWinProbability?.let { return it.coerceIn(0f, 1f) }

        if (game.isScheduled) return null
        if (game.isFinal) {
            return when {
                game.homeScore > game.awayScore -> 1f
                game.homeScore < game.awayScore -> 0f
                else -> 0.5f
            }
        }

        val remaining = fractionRemaining(game)
        val margin = (game.homeScore - game.awayScore).toFloat() + homeEdge(game.league)

        /*
         * The floor under sigma is what stops the last minute from being
         * nonsense. As `remaining` goes to zero so does the spread, and a
         * one-point lead divided by a spread of zero is infinite confidence -
         * which is wrong in every sport where possession still exists. 12% of
         * the full-game spread is about one scoring play, which is the honest
         * amount of game left when the clock says there is none.
         */
        val sigma = max(
            fullGameSigma(game.league) * 0.12f,
            fullGameSigma(game.league) * sqrt(remaining)
        )

        /*
         * The logistic standing in for the normal CDF. 1.702 is the constant
         * that makes the two agree to within half a percent everywhere, and it
         * costs one exp() where an error function costs a polynomial and a
         * branch. At the width this is drawn - a bar of sixty-odd dp - half a
         * percent is a fraction of a pixel.
         */
        val z = 1.702f * margin / sigma
        val p = 1f / (1f + exp(-z))

        // Never quite 0 or 1 while the game is running. A bar pinned to the end
        // of its rail says the game is over, and it is not.
        return p.coerceIn(0.02f, 0.98f)
    }

    /**
     * How much of the game is left, 0..1.
     *
     * Baseball counts outs rather than seconds, so it gets its own branch: a
     * half-inning is a nineteenth of a regulation game, and the outs inside it
     * split that further. Everything else divides a clock.
     */
    private fun fractionRemaining(game: Game): Float {
        if (game.league == League.MLB) {
            val inning = game.period.coerceAtLeast(1)
            // Extra innings: the game is by definition tied and could go on, so
            // treat it as one inning left rather than as a negative remainder.
            if (inning > 9) return 1f / 9f

            val half = if (game.situation.topOfInning == false) 0.5f else 0f
            val outs = (game.situation.outs ?: 0).coerceIn(0, 3) / 3f * 0.5f
            val elapsed = ((inning - 1) + half + outs) / 9f
            return (1f - elapsed).coerceIn(0f, 1f)
        }

        val total = regulationMinutes(game.league)
        if (total <= 0f) return 1f

        val periods = when (game.league) {
            League.NHL -> 3
            else -> 4
        }
        val perPeriod = total / periods

        // Overtime: whatever is on the clock is all there is.
        if (game.period > periods) {
            val left = clockSeconds(game.clock) ?: (perPeriod * 60f)
            return (left / 60f / total).coerceIn(0.01f, 1f)
        }

        val periodsDone = (game.period - 1).coerceAtLeast(0)
        val leftInPeriod = clockSeconds(game.clock)?.div(60f) ?: perPeriod
        val minutesLeft = (periods - periodsDone - 1) * perPeriod + leftInPeriod
        return (minutesLeft / total).coerceIn(0f, 1f)
    }

    /**
     * "12:04" to seconds, and "0:34.2" too - hockey and basketball both switch
     * to tenths inside the last minute, and a clock that stops parsing exactly
     * when the game gets interesting is the wrong one to ship.
     */
    fun clockSeconds(clock: String?): Float? {
        val text = clock?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(":")
        return runCatching {
            when (parts.size) {
                1 -> parts[0].toFloat()
                2 -> parts[0].toFloat() * 60f + parts[1].toFloat()
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Whether this is close enough, late enough, to be worth being told about.
     *
     * Used by the close-finish alert. One score in the sport's own units, in
     * the last tenth of the game - which is the last six minutes of an NFL
     * game, the last inning of a baseball game, and the last two minutes of a
     * hockey game, all from the same rule.
     */
    fun isCloseFinish(game: Game): Boolean {
        if (!game.isLive) return false
        if (fractionRemaining(game) > 0.10f) return false
        val margin = abs(game.homeScore - game.awayScore)
        val oneScore = when (game.league) {
            League.NFL, League.NCAAF -> 8
            League.NBA -> 5
            League.NHL -> 1
            League.MLB -> 2
        }
        return margin <= oneScore
    }
}
