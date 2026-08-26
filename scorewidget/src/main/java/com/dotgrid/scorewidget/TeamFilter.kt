package com.dotgrid.scorewidget

import java.util.Calendar

/**
 * Which games get the tile, and in what order.
 *
 * A widget shows one game at a time and the user may follow eight teams across
 * five leagues. On a Sunday in autumn that is a dozen games at once; in July it
 * is one, and half the favourites belong to leagues that are not playing. This
 * decides.
 *
 * Everything here is a pure function over its arguments - no `Context`, no
 * preferences, no clock of its own. That is not tidiness for its own sake: the
 * rules below are almost all *about* time, and the only way to test a rule
 * about time is to be able to lie about what time it is.
 */
object TeamFilter {

    /**
     * How far ahead a fixture is worth showing.
     *
     * 24 hours, matching the countdown: a game the tile can count down to is a
     * game worth holding the tile for, and one it cannot is not.
     */
    const val UPCOMING_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * How long a finished game keeps the tile.
     *
     * Six hours. Long enough that a game finishing at midnight is still there
     * at breakfast, short enough that yesterday's result is not still sitting
     * on the home screen when tonight's fixture is an hour away. The tie-break
     * against a scheduled game is handled by the tiers rather than by this
     * window, so the two can overlap without fighting.
     */
    const val RECAP_WINDOW_MS = 6L * 60 * 60 * 1000

    /**
     * The ranking bands. Sorted on this first, and on the fields below it only
     * within a band.
     *
     * The order is a claim about what someone wants to see, and it is: a game
     * happening now beats one about to happen, which beats one that just
     * finished, which beats one tomorrow. A rival's game beats none of those -
     * it is what fills the tile when a favourite has nothing on, which is the
     * whole point of tracking rivals rather than an argument for promoting
     * them.
     */
    private const val TIER_FAVORITE_LIVE = 0
    private const val TIER_FAVORITE_SOON = 1
    private const val TIER_FAVORITE_RECENT = 2
    private const val TIER_FAVORITE_LATER = 3
    private const val TIER_RIVAL_LIVE = 4
    private const val TIER_RIVAL_OTHER = 5

    /**
     * The cards the carousel will hold, best first.
     *
     * @param favorites team keys in the user's own priority order - position in
     *   this list is the tie-break inside every tier.
     * @param now injected rather than read, so the windows above can be tested.
     * @param calendar likewise, for the offseason check.
     */
    fun rank(
        games: List<Game>,
        favorites: List<String>,
        rivalriesEnabled: Boolean,
        filterOffseason: Boolean,
        now: Long,
        calendar: Calendar = Calendar.getInstance(),
        limit: Int = ScoreSettings.MAX_CARDS
    ): List<Game> {
        if (favorites.isEmpty()) return emptyList()

        /*
         * Offseason filtering happens on the *favourites*, not on the games.
         *
         * Filtering games would be a no-op that looked like a feature: a league
         * that is not playing has no games to filter, so the list would be
         * identical either way. The thing this setting actually saves is the
         * fetch - a favourite whose league is dark is a request that will come
         * back empty, and dropping it here is what stops the widget asking.
         */
        val eligible = if (filterOffseason) {
            favorites.filter { key ->
                League.byCode(key.substringBefore("/"))?.inSeason(calendar) ?: false
            }
        } else {
            favorites
        }
        if (eligible.isEmpty()) return emptyList()

        val ranked = games.mapNotNull { game ->
            val favoriteIndex = eligible.indexOfFirst { game.involves(it) }

            if (favoriteIndex >= 0) {
                val tier = when {
                    game.isLive -> TIER_FAVORITE_LIVE
                    game.isFinal -> {
                        val ended = game.startsAt ?: return@mapNotNull null
                        // A final from last week is history, not a recap.
                        if (now - ended > RECAP_WINDOW_MS + UPCOMING_WINDOW_MS) {
                            return@mapNotNull null
                        }
                        TIER_FAVORITE_RECENT
                    }
                    else -> {
                        val start = game.startsAt ?: return@mapNotNull null
                        if (start < now) return@mapNotNull null
                        if (start - now <= UPCOMING_WINDOW_MS) TIER_FAVORITE_SOON
                        else TIER_FAVORITE_LATER
                    }
                }
                Ranked(game, tier, favoriteIndex, game.startsAt ?: Long.MAX_VALUE)
            } else if (rivalriesEnabled) {
                /*
                 * A rivalry game is one where a rival of a favourite is playing
                 * - the rival's own game, not the favourite's, which by
                 * definition is not on. Its priority inherits from the
                 * favourite it hangs off, so someone whose first team is in the
                 * NFC East sees that division's games ahead of their fourth
                 * team's rivals.
                 */
                val owningFavorite = eligible.indexOfFirst { favorite ->
                    Rivalries.of(favorite).any { game.involves(it) }
                }
                if (owningFavorite < 0) return@mapNotNull null

                val tier = if (game.isLive) TIER_RIVAL_LIVE else TIER_RIVAL_OTHER
                if (!game.isLive) {
                    val start = game.startsAt ?: return@mapNotNull null
                    if (start < now || start - now > UPCOMING_WINDOW_MS) return@mapNotNull null
                }
                Ranked(game, tier, owningFavorite, game.startsAt ?: Long.MAX_VALUE)
            } else {
                null
            }
        }.sortedWith(
            compareBy<Ranked> { it.tier }
                .thenBy { it.favoriteIndex }
                .thenBy { it.startsAt }
        )

        /*
         * Rivals only fill an empty tile.
         *
         * This is the rule the setting is named for and the one most easily got
         * wrong: a rivalry tracker that pushes a rival's game in alongside a
         * favourite's turns a tile about your team into a tile about your
         * division. They are dropped entirely whenever a favourite has anything
         * live, imminent or just finished - the three tiers that mean "there is
         * something of yours to look at".
         */
        val favoriteIsActive = ranked.any { it.tier <= TIER_FAVORITE_RECENT }
        val kept = if (favoriteIsActive) {
            ranked.filter { it.tier <= TIER_FAVORITE_LATER }
        } else {
            ranked
        }

        // One card per game. A favourite playing a favourite is one game, and
        // it will have matched twice.
        return kept.distinctBy { it.game.id }.take(limit).map { it.game }
    }

    private class Ranked(
        val game: Game,
        val tier: Int,
        val favoriteIndex: Int,
        val startsAt: Long
    )

    /**
     * The card at [index], wrapping.
     *
     * The modulo lives here rather than in [ScoreSettings] because the count is
     * what it wraps against, and the count is a property of the games rather
     * than of the stored index. A card list that shrinks under a stored index -
     * a game ends, the list goes from four to three - wraps rather than
     * disappearing.
     *
     * `rem` then `plus` then `rem` again, because Kotlin's `%` keeps the sign
     * of the dividend: a stored index of -1, which is what stepping back from
     * zero produces, would otherwise index at -1 and throw.
     */
    fun pick(cards: List<Game>, index: Int): Game? {
        if (cards.isEmpty()) return null
        val wrapped = ((index % cards.size) + cards.size) % cards.size
        return cards[wrapped]
    }

    fun wrapIndex(index: Int, size: Int): Int =
        if (size <= 0) 0 else ((index % size) + size) % size
}

/**
 * Who counts as a rival.
 *
 * Divisions, mostly, because a division rival is the reliable answer - they
 * play twice a year, the games matter to the table, and the membership is a
 * fact rather than an opinion. The handful of entries that are not divisional
 * are the rivalries that would be conspicuous by their absence.
 *
 * Not exhaustive, and not meant to be. A team with no entry here simply has no
 * rivals to fall back on, and its owner sees an empty tile in the off week
 * instead of someone else's game - which is a defensible thing for a tile about
 * your team to do.
 */
object Rivalries {

    private val TABLE: Map<String, List<String>> by lazy {
        val groups = listOf(
            // NFL divisions
            listOf("NFL/DAL", "NFL/PHI", "NFL/NYG", "NFL/WSH"),
            listOf("NFL/KC", "NFL/LAC", "NFL/DEN", "NFL/LV"),
            listOf("NFL/BUF", "NFL/MIA", "NFL/NE", "NFL/NYJ"),
            listOf("NFL/BAL", "NFL/PIT", "NFL/CLE", "NFL/CIN"),
            listOf("NFL/GB", "NFL/CHI", "NFL/MIN", "NFL/DET"),
            listOf("NFL/SF", "NFL/SEA", "NFL/LAR", "NFL/ARI"),
            listOf("NFL/TB", "NFL/NO", "NFL/ATL", "NFL/CAR"),
            listOf("NFL/HOU", "NFL/IND", "NFL/TEN", "NFL/JAX"),

            // NBA
            listOf("NBA/BOS", "NBA/NY", "NBA/PHI", "NBA/BKN", "NBA/TOR"),
            listOf("NBA/LAL", "NBA/LAC", "NBA/GS", "NBA/PHX", "NBA/SAC"),
            listOf("NBA/MIL", "NBA/CHI", "NBA/CLE", "NBA/DET", "NBA/IND"),
            listOf("NBA/DAL", "NBA/HOU", "NBA/SA", "NBA/MEM", "NBA/NO"),
            listOf("NBA/MIA", "NBA/ORL", "NBA/ATL", "NBA/WSH", "NBA/CHA"),

            // MLB
            listOf("MLB/NYY", "MLB/BOS", "MLB/TB", "MLB/TOR", "MLB/BAL"),
            listOf("MLB/LAD", "MLB/SF", "MLB/SD", "MLB/ARI", "MLB/COL"),
            listOf("MLB/CHC", "MLB/STL", "MLB/MIL", "MLB/CIN", "MLB/PIT"),
            listOf("MLB/HOU", "MLB/TEX", "MLB/SEA", "MLB/LAA", "MLB/ATH"),
            listOf("MLB/NYM", "MLB/PHI", "MLB/ATL", "MLB/WSH", "MLB/MIA"),
            listOf("MLB/CLE", "MLB/DET", "MLB/MIN", "MLB/CWS", "MLB/KC"),

            // NHL
            listOf("NHL/TOR", "NHL/MTL", "NHL/OTT", "NHL/BOS", "NHL/BUF"),
            listOf("NHL/NYR", "NHL/NYI", "NHL/NJD", "NHL/PHI", "NHL/PIT"),
            listOf("NHL/CHI", "NHL/DET", "NHL/STL", "NHL/NSH", "NHL/MIN"),
            listOf("NHL/EDM", "NHL/CGY", "NHL/VAN", "NHL/SEA", "NHL/VGK"),
            listOf("NHL/COL", "NHL/DAL", "NHL/WPG", "NHL/UTA"),
            listOf("NHL/TBL", "NHL/FLA", "NHL/CAR", "NHL/WSH"),

            // NCAAF - conference-mates and the games that outlive conferences
            listOf("NCAAF/OSU", "NCAAF/MICH", "NCAAF/PSU", "NCAAF/MSU"),
            listOf("NCAAF/BAMA", "NCAAF/AUB", "NCAAF/UGA", "NCAAF/LSU"),
            listOf("NCAAF/TEX", "NCAAF/OU", "NCAAF/TA&M"),
            listOf("NCAAF/USC", "NCAAF/UCLA", "NCAAF/ORE", "NCAAF/WASH"),
            listOf("NCAAF/CLEM", "NCAAF/FSU", "NCAAF/MIA", "NCAAF/UNC")
        )

        val table = HashMap<String, MutableList<String>>()
        groups.forEach { group ->
            group.forEach { team ->
                table.getOrPut(team) { ArrayList() }
                    .addAll(group.filter { it != team })
            }
        }
        table.mapValues { it.value.distinct() }
    }

    fun of(teamKey: String): List<String> = TABLE[teamKey].orEmpty()
}
