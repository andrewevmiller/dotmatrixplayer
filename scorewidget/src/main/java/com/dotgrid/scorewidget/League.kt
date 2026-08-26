package com.dotgrid.scorewidget

import java.util.Calendar

/**
 * The five leagues the widget knows about, and the shape of their year.
 *
 * [path] is the pair of segments ESPN's public scoreboard endpoint wants -
 * sport, then league - and it is the only thing in this file that is anyone
 * else's schema. Everything else here is ours.
 */
enum class League(
    val code: String,
    val label: String,
    val path: String,

    /**
     * The months the league is *in* season, 1-based and inclusive at both ends.
     *
     * Two numbers rather than a set, because every one of these seasons is a
     * contiguous run - and four of the five wrap around the new year, which is
     * the case a naive `first..last` range gets wrong. See [inSeason].
     *
     * These are the regular season plus the postseason, rounded outwards to the
     * month. Rounding out rather than in is the safer error: a fortnight of
     * showing a team that has finished its year is a mild annoyance, where
     * hiding a team during its first week back is the widget failing at the one
     * moment its owner is most likely to look at it.
     */
    private val seasonFrom: Int,
    private val seasonTo: Int
) {
    NFL("NFL", "NFL", "football/nfl", 9, 2),
    NBA("NBA", "NBA", "basketball/nba", 10, 6),
    MLB("MLB", "MLB", "baseball/mlb", 3, 11),
    NHL("NHL", "NHL", "hockey/nhl", 10, 6),
    NCAAF("NCAAF", "NCAA FB", "football/college-football", 8, 1);

    /**
     * Whether the league is playing in the month [calendar] falls in.
     *
     * The wrap is the whole reason this is not a range check. The NFL runs
     * September to February, so its months are 9,10,11,12,1,2 - and
     * `month in 9..2` is empty, which would filter the NFL out of the entire
     * year. When the season wraps, the test inverts: a month is in season when
     * it is *not* between the end and the start.
     */
    fun inSeason(calendar: Calendar = Calendar.getInstance()): Boolean {
        val month = calendar.get(Calendar.MONTH) + 1
        return if (seasonFrom <= seasonTo) {
            month in seasonFrom..seasonTo
        } else {
            month >= seasonFrom || month <= seasonTo
        }
    }

    /**
     * What a period is called here, given its number.
     *
     * Football and basketball count quarters, hockey counts periods, baseball
     * counts innings and is handled by its own branch because its half matters.
     * Past regulation they all become overtime, and the numbering restarts -
     * "5TH" for a basketball overtime is technically true and universally
     * unsaid.
     */
    fun periodLabel(period: Int): String {
        val regulation = when (this) {
            NHL -> 3
            MLB -> 9
            else -> 4
        }
        if (period > regulation) {
            val extra = period - regulation
            val tag = if (this == MLB) "EXTRA" else "OT"
            return if (extra <= 1) tag else "$tag$extra"
        }
        return when (period) {
            1 -> "1ST"
            2 -> "2ND"
            3 -> "3RD"
            4 -> "4TH"
            else -> period.toString()
        }
    }

    companion object {
        fun byCode(code: String?): League? = entries.firstOrNull { it.code == code }
    }
}
