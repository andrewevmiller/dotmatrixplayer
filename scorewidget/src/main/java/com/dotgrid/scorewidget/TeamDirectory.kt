package com.dotgrid.scorewidget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every team the settings menu can offer, per league.
 *
 * [TeamCatalog] is the offline seed and this is the real answer. The seed is
 * enough to pick a team on a plane; it is not enough to be *complete*, and for
 * college football it is not even close - ESPN lists over 700 programmes and
 * the set moves with realignment. A hand-kept list is a list with somebody's
 * team missing from it, which is exactly how this was found: no Vanderbilt.
 *
 * So the directory is fetched once and cached, and the seed is what shows until
 * it arrives. Merged rather than replaced, so a team in the seed never
 * disappears because a fetch came back short.
 *
 * ### Why this is worth a network call when the catalog was not
 *
 * The argument against fetching was that a settings screen which cannot list
 * teams offline is a bad settings screen. That still holds - which is why the
 * seed stays. What it does not justify is being *wrong*: an abbreviation is the
 * identity a favourite is stored under and matched against the scoreboard, so
 * a hand-typed one that disagrees with the feed produces a favourite whose
 * games never appear, with nothing on screen to explain why. The feed's own
 * abbreviations cannot disagree with the feed.
 */
object TeamDirectory {

    private const val TAG = "TeamDirectory"

    private const val PREFS = "team_directory"
    private const val KEY_TEAMS_PREFIX = "teams_"
    private const val KEY_FETCHED_PREFIX = "fetched_"

    /**
     * How long a cached directory is trusted.
     *
     * Thirty days. Rosters change constantly and none of that matters here -
     * this holds names and abbreviations, which move when a programme joins a
     * conference or rebrands, on the order of once a year. Re-fetching more
     * often would spend someone's data to learn nothing.
     */
    private const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The teams for [league] - fetched if cached, seeded otherwise.
     *
     * Never empty for a league [TeamCatalog] knows, and never blocks: this
     * reads the cache only. [refresh] is what goes to the network.
     */
    fun teams(context: Context, league: League): List<Team> {
        val cached = read(context, league)
        if (cached.isEmpty()) return TeamCatalog.teams(league)

        // Merged, keyed by abbreviation, with the fetched entry winning on a
        // collision - it is the one the scoreboard will agree with.
        val merged = LinkedHashMap<String, Team>()
        TeamCatalog.teams(league).forEach { merged[it.key] = it }
        val cachedKeys = cached.map { it.key }.toSet()
        cached.forEach { merged[it.key] = it }

        // A second pass, by name rather than by key - the seed and the feed
        // can each name a team correctly while disagreeing on its
        // abbreviation (a rename or relocation the seed has not caught up
        // with yet), and that disagreement is exactly what the key-based
        // merge above cannot catch: two different keys, the same team,
        // both surviving into the list. Collapsing by name too closes that
        // gap - a fetched entry always wins the collision, since its
        // abbreviation is the one the scoreboard will actually send.
        val byName = LinkedHashMap<String, Team>()
        merged.values.forEach { team ->
            val nameKey = team.name.trim().lowercase()
            val existing = byName[nameKey]
            if (existing == null || (team.key in cachedKeys && existing.key !in cachedKeys)) {
                byName[nameKey] = team
            }
        }
        return byName.values.sortedBy { it.name }
    }

    /** Case-insensitive match on abbreviation or name, for the settings search. */
    fun search(context: Context, league: League, query: String): List<Team> {
        val all = teams(context, league)
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter {
            it.abbrev.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true)
        }
    }

    /** A team by stored key, falling back to the static catalog then to a stub. */
    fun byKey(context: Context, key: String): Team? {
        val league = League.byCode(key.substringBefore("/")) ?: return null
        teams(context, league).firstOrNull { it.key == key }?.let { return it }
        return TeamCatalog.byKey(key)
    }

    /**
     * Fetches any league whose directory is missing or stale. **Blocking** -
     * callers are on a worker.
     *
     * Only the leagues the user actually follows, plus whichever one the picker
     * is showing. Fetching all five on first open would be five requests of a
     * megabyte each to populate lists that may never be opened.
     */
    fun refresh(context: Context, leagues: Collection<League>) {
        leagues.distinct().forEach { league ->
            if (!isStale(context, league)) return@forEach

            val fetched = EspnClient.teams(league)
            if (fetched.isEmpty()) {
                // Leave the previous directory in place. An empty result is a
                // network that is not there, not a league that lost its teams.
                Log.w(TAG, "Empty team list for " + league.code + "; keeping what we had")
                return@forEach
            }
            write(context, league, fetched)
        }
    }

    private fun isStale(context: Context, league: League): Boolean {
        val at = prefs(context).getLong(KEY_FETCHED_PREFIX + league.code, 0L)
        return at <= 0L || System.currentTimeMillis() - at > STALE_AFTER_MS
    }

    // -----------------------------------------------------------------------

    private fun write(context: Context, league: League, teams: List<Team>) {
        val array = JSONArray()
        teams.forEach { team ->
            array.put(JSONObject().apply {
                put("a", team.abbrev)
                put("n", team.name)
            })
        }
        prefs(context).edit()
            .putString(KEY_TEAMS_PREFIX + league.code, array.toString())
            .putLong(KEY_FETCHED_PREFIX + league.code, System.currentTimeMillis())
            .apply()
    }

    private fun read(context: Context, league: League): List<Team> {
        val raw = prefs(context).getString(KEY_TEAMS_PREFIX + league.code, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val teams = ArrayList<Team>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val abbrev = obj.optString("a").takeIf { it.isNotBlank() } ?: continue
                teams.add(Team(abbrev, obj.optString("n").ifEmpty { abbrev }, league))
            }
            teams
        }.onFailure {
            Log.w(TAG, "Directory cache unreadable for " + league.code, it)
        }.getOrDefault(emptyList())
    }
}
