package com.dotgrid.scorewidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection

/**
 * One GET, one object walk.
 *
 * ESPN publishes a scoreboard endpoint per league that the espn.com site itself
 * reads. It needs no key and no account, which is the only reason this widget
 * can exist without an onboarding screen - and it is also **undocumented**, so
 * the shape below is observed rather than promised. Every field is read
 * defensively for that reason: a missing key here should cost a line on a tile,
 * never a crash in a broadcast receiver.
 *
 * ### Why no HTTP library and no JSON mapper
 *
 * The same rule the two sibling modules keep: framework API only at runtime. A
 * widget pays for a dependency twice, once in APK size and once in cold start
 * on every repaint, and a repaint here happens on an alarm with no UI in front
 * of it. `HttpsURLConnection` and `org.json` are both in the platform and both
 * sufficient for one request and one tree walk.
 *
 * ### What leaves the device
 *
 * A GET, with no body, no cookie, no identifier and no account. The response is
 * parsed, drawn, and dropped - the only thing kept is the resulting snapshot,
 * cached so a tile opened on a train shows the last score it knew rather than a
 * dash.
 */
object EspnClient {

    private const val TAG = "EspnClient"

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"

    /**
     * Deliberately short.
     *
     * This runs on a broadcast's `goAsync` window, which the system will not
     * hold open indefinitely - and the caller has a cached snapshot to fall
     * back on. Ten seconds of waiting to replace a five-minute-old score with a
     * four-minute-old one is a bad trade; failing fast and keeping the old one
     * is the right answer.
     */
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** ISO-8601 as ESPN writes it: "2026-08-24T17:00Z". */
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Today's card for one league.
     *
     * @return the games, or an empty list on any failure. An exception here is
     *   a network that is not there, which is an ordinary condition for a phone
     *   and not an error worth propagating - the caller's job is to keep
     *   showing what it already had.
     */
    fun scoreboard(league: League): List<Game> {
        val body = runCatching { get(BASE + league.path + "/scoreboard") }
            .onFailure { Log.w(TAG, "Scoreboard fetch failed for " + league.code, it) }
            .getOrNull() ?: return emptyList()

        return runCatching { parse(league, JSONObject(body)) }
            .onFailure { Log.w(TAG, "Scoreboard parse failed for " + league.code, it) }
            .getOrDefault(emptyList())
    }

    /**
     * Every team in a league.
     *
     * The static [TeamCatalog] cannot be the whole answer for college football:
     * ESPN lists over 700 programmes, the set changes with realignment, and a
     * hand-kept list is a list with someone's team missing from it. This is the
     * authoritative one.
     *
     * `limit=1000` because the endpoint pages at 50 by default, which would
     * silently return the first fiftieth of the alphabet - a failure that looks
     * exactly like a short league.
     *
     * @return the teams, or empty on any failure. The caller keeps whatever
     *   directory it already had; see [TeamDirectory].
     */
    fun teams(league: League): List<Team> {
        val body = runCatching { get(BASE + league.path + "/teams?limit=1000") }
            .onFailure { Log.w(TAG, "Team list fetch failed for " + league.code, it) }
            .getOrNull() ?: return emptyList()

        return runCatching { parseTeams(league, JSONObject(body)) }
            .onFailure { Log.w(TAG, "Team list parse failed for " + league.code, it) }
            .getOrDefault(emptyList())
    }

    /**
     * The team list is buried four levels down - `sports[0].leagues[0].teams[]`
     * - and each entry wraps the team in another object. Walked defensively,
     * like everything else here.
     */
    private fun parseTeams(league: League, root: JSONObject): List<Team> {
        val entries = root.optJSONArray("sports")
            ?.optJSONObject(0)
            ?.optJSONArray("leagues")
            ?.optJSONObject(0)
            ?.optJSONArray("teams")
            ?: return emptyList()

        val teams = ArrayList<Team>(entries.length())
        for (i in 0 until entries.length()) {
            val team = entries.optJSONObject(i)?.optJSONObject("team") ?: continue

            // Inactive entries are defunct or placeholder programmes. They can
            // never appear on a scoreboard, so offering them would be offering
            // a favourite that never has a game.
            if (team.has("isActive") && !team.optBoolean("isActive", true)) continue

            val abbrev = team.optString("abbreviation").takeIf { it.isNotBlank() } ?: continue
            val name = team.optString("displayName").takeIf { it.isNotBlank() } ?: abbrev
            teams.add(Team(abbrev.uppercase(), name, league))
        }
        return teams
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection) {
                // Nothing to configure - naming the type is how this file states
                // that plaintext is not a path it has. See the cleartext note in
                // the README.
            }
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            // Gzip is offered by default and transparently decoded, as long as
            // we do not set Accept-Encoding ourselves. A scoreboard is ~200KB
            // of JSON and about a tenth of that compressed.

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP " + code + " from " + url)
                return ""
            }
            return connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    // -----------------------------------------------------------------------
    // Parsing.
    //
    // Every accessor below is the optional form - opt* rather than get* - so a
    // field ESPN renames or drops leaves a null rather than throwing out of the
    // middle of a list. The one exception is the events array itself: no events
    // is a valid answer (an off day), an unreadable response is not.
    // -----------------------------------------------------------------------

    private fun parse(league: League, root: JSONObject): List<Game> {
        val events = root.optJSONArray("events") ?: return emptyList()
        val games = ArrayList<Game>(events.length())

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            runCatching { parseEvent(league, event) }
                .onFailure { Log.w(TAG, "Skipping unreadable event", it) }
                .getOrNull()
                ?.let { games.add(it) }
        }
        return games
    }

    private fun parseEvent(league: League, event: JSONObject): Game? {
        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = competition.optJSONArray("competitors") ?: return null

        var home: JSONObject? = null
        var away: JSONObject? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.optJSONObject(i) ?: continue
            when (c.optString("homeAway")) {
                "home" -> home = c
                "away" -> away = c
            }
        }
        if (home == null || away == null) return null

        val status = competition.optJSONObject("status") ?: event.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val state = when (type?.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINAL
            else -> GameState.SCHEDULED
        }

        val situation = competition.optJSONObject("situation")
        val shortDetail = type?.optString("shortDetail")?.takeIf { it.isNotBlank() }

        return Game(
            id = event.optString("id").ifEmpty { competition.optString("id") },
            league = league,
            state = state,
            away = team(away, league),
            home = team(home, league),
            awayScore = score(away),
            homeScore = score(home),
            startsAt = epoch(competition.optString("date").ifEmpty { event.optString("date") }),
            clock = status?.optString("displayClock")?.takeIf { it.isNotBlank() },
            period = status?.optInt("period", 1)?.coerceAtLeast(1) ?: 1,
            statusDetail = shortDetail,
            situation = situation(league, situation, shortDetail, home, away),
            broadcast = broadcast(competition),
            topPerformer = topPerformer(competition),
            feedHomeWinProbability = homeWinProbability(situation)
        )
    }

    private fun team(competitor: JSONObject, league: League): Team {
        val team = competitor.optJSONObject("team")
        val abbrev = team?.optString("abbreviation")?.takeIf { it.isNotBlank() }
            ?: team?.optString("shortDisplayName")?.take(4)?.uppercase()
            ?: "?"
        val name = team?.optString("displayName")?.takeIf { it.isNotBlank() } ?: abbrev
        return Team(abbrev.uppercase(), name, league)
    }

    /**
     * The score arrives as a string, and as an empty one before the game
     * starts. Zero is the right reading of "not yet" here: the tile shows
     * 0 - 0 under a kickoff time, which is what a scoreboard shows.
     */
    private fun score(competitor: JSONObject): Int =
        competitor.optString("score").trim().toIntOrNull() ?: 0

    private fun situation(
        league: League,
        situation: JSONObject?,
        shortDetail: String?,
        home: JSONObject,
        away: JSONObject
    ): LiveContext {
        if (situation == null && shortDetail == null) return LiveContext()

        /*
         * Possession arrives as a team id, not an abbreviation, so it has to be
         * matched back against the two competitors. Ids are stable and
         * abbreviations are not - a relocation changes the second and never the
         * first - which is why the feed sends the id, and why this lookup is
         * the right way round rather than a nuisance.
         */
        val possessionId = situation?.optString("possession")?.takeIf { it.isNotBlank() }
        val possessionAbbrev = possessionId?.let { id ->
            when (id) {
                home.optJSONObject("team")?.optString("id") -> team(home, league).abbrev
                away.optJSONObject("team")?.optString("id") -> team(away, league).abbrev
                else -> null
            }
        }

        /*
         * Which half of the inning, from the status line rather than from a
         * field - ESPN does not send a boolean for it, it sends "Top 5th" and
         * "Bot 5th" as display text. Matching on the words is reading a label
         * meant for a human, which is fragile; it is also the only signal there
         * is, so the null case is handled everywhere downstream.
         */
        val topOfInning = if (league != League.MLB) null else {
            val text = shortDetail?.lowercase().orEmpty()
            when {
                text.startsWith("top") -> true
                text.startsWith("bot") || text.startsWith("bottom") -> false
                else -> null
            }
        }

        return LiveContext(
            down = situation?.optInt("down", 0)?.takeIf { it in 1..4 },
            distance = situation?.optInt("distance", -1)?.takeIf { it >= 0 },
            yardLine = situation?.optString("yardLine")?.takeIf { it.isNotBlank() },
            possessionAbbrev = possessionAbbrev,
            isRedZone = situation?.optBoolean("isRedZone", false) == true,

            // The feed's own "3rd & 7", preferred over rebuilding it - see
            // LiveContext.downAndDistance. Short form first: the long one spells
            // out the yard line too, which does not fit the space this gets.
            downDistanceText = situation?.optString("shortDownDistanceText")
                ?.takeIf { it.isNotBlank() }
                ?: situation?.optString("downDistanceText")?.takeIf { it.isNotBlank() },

            balls = situation?.optInt("balls", -1)?.takeIf { it in 0..3 },
            strikes = situation?.optInt("strikes", -1)?.takeIf { it in 0..2 },
            outs = situation?.optInt("outs", -1)?.takeIf { it in 0..3 },
            onFirst = occupied(situation, "onFirst"),
            onSecond = occupied(situation, "onSecond"),
            onThird = occupied(situation, "onThird"),
            topOfInning = topOfInning,

            // Bonus and power play are not on the scoreboard payload for every
            // league; where they are absent these stay false and the indicator
            // simply does not appear, which is indistinguishable from "not in
            // the bonus" and therefore safe.
            awayInBonus = away.optBoolean("inBonus", false),
            homeInBonus = home.optBoolean("inBonus", false),
            powerPlayAbbrev = null
        )
    }

    /**
     * Whether a base has a runner on it, tolerating **either shape ESPN uses**.
     *
     * `onFirst` is a boolean on the site scoreboard and an *object* - the
     * runner's athlete record - on the core API that the scoreboard is
     * assembled from. Which one arrives on any given payload is not something
     * this app gets to decide, and the two fail in opposite directions:
     * `optBoolean` on an object quietly returns the fallback, so the diamond
     * would show empty bases all game with nothing in the log to say why.
     *
     * So the question asked here is "is there anything on this base", which
     * both shapes answer honestly: a present object means a runner, and a
     * present boolean means what it says.
     */
    private fun occupied(situation: JSONObject?, key: String): Boolean {
        if (situation == null) return false
        if (!situation.has(key) || situation.isNull(key)) return false
        // An object here is the runner themselves, so its presence is the answer.
        if (situation.optJSONObject(key) != null) return true
        return situation.optBoolean(key, false)
    }

    /**
     * The first listed network.
     *
     * A game can carry several - a national feed and two regional ones - and
     * the tile has room for one. First is right rather than arbitrary: ESPN
     * lists the national broadcast ahead of the regional ones, which is also
     * the one most likely to be the viewer's.
     */
    private fun broadcast(competition: JSONObject): String? {
        val broadcasts = competition.optJSONArray("broadcasts") ?: return null
        for (i in 0 until broadcasts.length()) {
            val names = broadcasts.optJSONObject(i)?.optJSONArray("names") ?: continue
            for (j in 0 until names.length()) {
                names.optString(j).takeIf { it.isNotBlank() }?.let { return it.uppercase() }
            }
        }
        return null
    }

    /**
     * One line about the game's best player so far.
     *
     * ESPN groups leaders by category - passing, rushing, receiving - in the
     * order the sport considers important, so the first entry of the first
     * group is the headline performance without having to rank anything here.
     */
    private fun topPerformer(competition: JSONObject): String? {
        val categories = competition.optJSONArray("leaders") ?: return null
        for (i in 0 until categories.length()) {
            val leaders: JSONArray = categories.optJSONObject(i)
                ?.optJSONArray("leaders") ?: continue
            val leader = leaders.optJSONObject(0) ?: continue

            val value = leader.optString("displayValue").takeIf { it.isNotBlank() } ?: continue
            val who = leader.optJSONObject("athlete")?.let { athlete ->
                athlete.optString("shortName").takeIf { it.isNotBlank() }
                    ?: athlete.optString("displayName").takeIf { it.isNotBlank() }
            }
            return (if (who != null) "$who $value" else value).uppercase()
        }
        return null
    }

    /**
     * ESPN's own win probability, when the feed carries one.
     *
     * It hangs off the last play rather than off the game, because that is what
     * it is a property of - the model runs per play. Absent for most leagues
     * and for every game that has not started.
     */
    private fun homeWinProbability(situation: JSONObject?): Float? {
        val probability = situation?.optJSONObject("lastPlay")?.optJSONObject("probability")
            ?: return null
        if (!probability.has("homeWinPercentage")) return null
        val value = probability.optDouble("homeWinPercentage", Double.NaN)
        if (value.isNaN()) return null
        return value.toFloat().coerceIn(0f, 1f)
    }

    private fun epoch(date: String?): Long? {
        val text = date?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { iso.parse(text)?.time }.getOrNull()
    }
}
