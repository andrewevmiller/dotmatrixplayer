package com.dotgrid.scorewidget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The cards the tiles are drawing, and where they came from.
 *
 * Between the fetch and the tile sits a cache, and it is not an optimisation.
 * This app has no process of its own: it is started by a broadcast, paints, and
 * is gone again. Anything held in a field is lost before the next repaint, so
 * "the last score we knew" has to be on disk or it does not exist - and "the
 * last score we knew" is what a tile shows on a train, in a lift, and in the
 * two seconds before a fetch comes back.
 */
object GameRepository {

    private const val TAG = "GameRepository"

    private const val PREFS = "score_cache"
    private const val KEY_CARDS = "cards"
    private const val KEY_FETCHED_AT = "fetched_at"

    /**
     * How old the cache may be before a repaint refuses to trust it.
     *
     * Six hours, which is well beyond the refresh interval - this is not the
     * freshness policy, it is the floor under one. [RefreshScheduler] keeps the
     * data current; this only decides when a cache is so old that showing it
     * would be a lie rather than a delay, which in practice means the tile has
     * not had a network in half a day.
     */
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

    /**
     * How long [fetchInParallel] will wait for any one league before giving up
     * on it.
     *
     * [EspnClient] bounds a single request to roughly sixteen seconds of its
     * own - eight to connect, eight to read - and this adds a small margin on
     * top for thread scheduling rather than trusting that budget exactly.
     * Because every league's request runs on its own thread, this is the whole
     * wall-clock cost of a slow-but-not-dead network, no matter how many
     * leagues are active - not that cost multiplied by the league count.
     */
    private const val FETCH_TIMEOUT_MS = 18_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Fetches, ranks and caches. **Blocking** - callers are on [Background]'s
     * worker or on the settings screen's, never on the main thread.
     *
     * @return the ranked cards, or the cached ones if every league failed.
     */
    fun refresh(context: Context): List<Game> {
        val favorites = ScoreSettings.favorites(context)
        if (favorites.isEmpty()) return emptyList()

        val leagues = if (ScoreSettings.filterOffseason(context)) {
            ScoreSettings.activeLeagues(context).filter { it.inSeason() }
        } else {
            ScoreSettings.activeLeagues(context).toList()
        }

        /*
         * Rivalry games come from the same scoreboard call as the favourites'
         * own - a rival is in the same league by construction - so tracking
         * rivals costs no extra request. That is why the feature is on by
         * default: it is free.
         */
        val (fetched, anySucceeded) = if (leagues.isEmpty()) {
            emptyList<Game>() to false
        } else {
            fetchInParallel(leagues)
        }

        if (!anySucceeded && leagues.isNotEmpty()) {
            Log.w(TAG, "No league returned games; keeping the cached cards")
            return cards(context)
        }

        val ranked = TeamFilter.rank(
            games = fetched,
            favorites = favorites,
            rivalriesEnabled = ScoreSettings.rivalries(context),
            filterOffseason = ScoreSettings.filterOffseason(context),
            now = System.currentTimeMillis()
        )

        write(context, ranked)
        return ranked
    }

    /**
     * Fetches every active league's scoreboard at once rather than one after
     * another.
     *
     * The five leagues are independent requests to independent endpoints, and
     * fetching them in sequence turns "the network is slow" into "the network
     * is slow, five times over" - up to eighty seconds inside a single
     * [Background.run] pass with every league configured. That is long enough
     * for the system to consider the update wedged and abandon it before it
     * ever reaches the cache, which is the one situation this cache exists to
     * survive. Fanning the requests out over one thread each bounds the whole
     * pass to [FETCH_TIMEOUT_MS] regardless of how many leagues are active.
     *
     * The pool is sized to the call and does not outlive it - this is not a
     * standing thread pool like [Background]'s, which already serialises
     * refreshes against each other; this only fans a single one of them out.
     *
     * @return the games from every league that answered, and whether any
     *   league answered at all - a league that is fetched and genuinely has
     *   nothing on is indistinguishable from one that timed out until this
     *   flag says which.
     */
    private fun fetchInParallel(leagues: List<League>): Pair<List<Game>, Boolean> {
        val executor = Executors.newFixedThreadPool(leagues.size)
        try {
            val futures = leagues.map { league ->
                league to executor.submit(Callable { EspnClient.scoreboard(league) })
            }

            val fetched = ArrayList<Game>()
            var anySucceeded = false
            futures.forEach { (league, future) ->
                val games = runCatching { future.get(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                    .onFailure { Log.w(TAG, "Scoreboard fetch timed out for " + league.code, it) }
                    .getOrDefault(emptyList())
                // An empty list is ambiguous - an off day looks exactly like a
                // failed request. Treat any league returning anything as proof
                // the network is up, and fall back to the cache only if none did.
                if (games.isNotEmpty()) anySucceeded = true
                fetched.addAll(games)
            }
            return fetched to anySucceeded
        } finally {
            // Not graceful shutdown: a thread still blocked in a socket read
            // past this method's own timeout is not doing anything worth
            // waiting for, and the call is already on its way out regardless
            // of whether that thread ever notices the interrupt.
            executor.shutdownNow()
        }
    }

    /** The cached cards, or empty when there are none or they have gone stale. */
    fun cards(context: Context): List<Game> {
        val store = prefs(context)
        val fetchedAt = store.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAt <= 0L) return emptyList()
        if (System.currentTimeMillis() - fetchedAt > STALE_AFTER_MS) return emptyList()

        val raw = store.getString(KEY_CARDS, null) ?: return emptyList()
        return runCatching { read(JSONArray(raw)) }
            .onFailure { Log.w(TAG, "Cache unreadable; dropping it", it) }
            .getOrDefault(emptyList())
    }

    /** How long ago the cache was written, for the settings screen's status line. */
    fun fetchedAt(context: Context): Long = prefs(context).getLong(KEY_FETCHED_AT, 0L)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // -----------------------------------------------------------------------
    // Serialisation.
    //
    // By hand, into org.json, for the same reason there is no HTTP library: a
    // reflective mapper is a dependency, and this is one flat object with
    // twenty fields. Written defensively in both directions - a cache from a
    // previous version of the app is read by this one, and a field that has
    // changed shape should cost a card rather than the whole cache.
    // -----------------------------------------------------------------------

    private fun write(context: Context, games: List<Game>) {
        val array = JSONArray()
        games.forEach { array.put(toJson(it)) }
        prefs(context).edit()
            .putString(KEY_CARDS, array.toString())
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun read(array: JSONArray): List<Game> {
        val games = ArrayList<Game>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            fromJson(obj)?.let { games.add(it) }
        }
        return games
    }

    private fun toJson(game: Game): JSONObject = JSONObject().apply {
        put("id", game.id)
        put("lg", game.league.code)
        put("st", game.state.name)
        put("aa", game.away.abbrev)
        put("an", game.away.name)
        put("ha", game.home.abbrev)
        put("hn", game.home.name)
        put("as", game.awayScore)
        put("hs", game.homeScore)
        game.startsAt?.let { put("t", it) }
        game.clock?.let { put("c", it) }
        put("p", game.period)
        game.statusDetail?.let { put("sd", it) }
        game.broadcast?.let { put("tv", it) }
        game.topPerformer?.let { put("perf", it) }
        game.feedHomeWinProbability?.let { put("wp", it.toDouble()) }

        val s = game.situation
        put("sit", JSONObject().apply {
            s.down?.let { put("d", it) }
            s.distance?.let { put("dd", it) }
            s.yardLine?.let { put("yl", it) }
            s.possessionAbbrev?.let { put("pos", it) }
            put("rz", s.isRedZone)
            s.balls?.let { put("b", it) }
            s.strikes?.let { put("k", it) }
            s.outs?.let { put("o", it) }
            put("b1", s.onFirst)
            put("b2", s.onSecond)
            put("b3", s.onThird)
            s.topOfInning?.let { put("top", it) }
            put("ab", s.awayInBonus)
            put("hb", s.homeInBonus)
            s.powerPlayAbbrev?.let { put("pp", it) }
        })
    }

    private fun fromJson(obj: JSONObject): Game? {
        val league = League.byCode(obj.optString("lg")) ?: return null
        val state = runCatching { GameState.valueOf(obj.optString("st")) }
            .getOrDefault(GameState.SCHEDULED)

        val sit = obj.optJSONObject("sit") ?: JSONObject()

        return Game(
            id = obj.optString("id"),
            league = league,
            state = state,
            away = Team(obj.optString("aa"), obj.optString("an"), league),
            home = Team(obj.optString("ha"), obj.optString("hn"), league),
            awayScore = obj.optInt("as", 0),
            homeScore = obj.optInt("hs", 0),
            startsAt = if (obj.has("t")) obj.optLong("t") else null,
            clock = obj.optString("c").takeIf { it.isNotBlank() },
            period = obj.optInt("p", 1),
            statusDetail = obj.optString("sd").takeIf { it.isNotBlank() },
            broadcast = obj.optString("tv").takeIf { it.isNotBlank() },
            topPerformer = obj.optString("perf").takeIf { it.isNotBlank() },
            feedHomeWinProbability =
                if (obj.has("wp")) obj.optDouble("wp").toFloat() else null,
            situation = LiveContext(
                down = if (sit.has("d")) sit.optInt("d") else null,
                distance = if (sit.has("dd")) sit.optInt("dd") else null,
                yardLine = sit.optString("yl").takeIf { it.isNotBlank() },
                possessionAbbrev = sit.optString("pos").takeIf { it.isNotBlank() },
                isRedZone = sit.optBoolean("rz", false),
                balls = if (sit.has("b")) sit.optInt("b") else null,
                strikes = if (sit.has("k")) sit.optInt("k") else null,
                outs = if (sit.has("o")) sit.optInt("o") else null,
                onFirst = sit.optBoolean("b1", false),
                onSecond = sit.optBoolean("b2", false),
                onThird = sit.optBoolean("b3", false),
                topOfInning = if (sit.has("top")) sit.optBoolean("top") else null,
                awayInBonus = sit.optBoolean("ab", false),
                homeInBonus = sit.optBoolean("hb", false),
                powerPlayAbbrev = sit.optString("pp").takeIf { it.isNotBlank() }
            )
        )
    }
}
