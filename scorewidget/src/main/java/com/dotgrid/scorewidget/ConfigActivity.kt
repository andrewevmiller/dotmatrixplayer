package com.dotgrid.scorewidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The widget's settings menu.
 *
 * One screen serving three entrances - the launcher icon, a tap on the tile,
 * and the widget's own reconfigure item - because they all want the same thing.
 * It renders a live copy of the 4x2 card at the top through
 * [WidgetRenderer.buildPreview], so every setting can be seen taking effect and
 * a rendering bug shows up here rather than only on a home screen.
 *
 * Settings are saved as they are touched rather than on the way out. A widget
 * configuration screen can be left by the back gesture, by the home key, or by
 * the system deciding it has waited long enough, and a Save button would lose
 * to all three.
 */
class ConfigActivity : Activity() {

    private lateinit var previewHost: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var statusDot: ImageView
    private lateinit var favoritesHost: LinearLayout
    private lateinit var favoritesEmpty: TextView
    private lateinit var pickerHost: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var alertDenied: TextView

    private lateinit var leagueChips: Map<League, TextView>
    private lateinit var alertChips: Map<Int, TextView>
    private lateinit var accentChips: Map<Int, TextView>
    private lateinit var toggleOffseason: TextView
    private lateinit var toggleRivalries: TextView
    private lateinit var toggleWinProbability: TextView

    /** Which league the picker is showing. Not persisted - it is a view state. */
    private var pickerLeague = League.NFL

    /**
     * The cards behind the preview.
     *
     * Held so that flipping a toggle repaints the preview instantly instead of
     * going back to the network. Only the favourites actually change what is
     * fetched, and that path refetches explicitly.
     */
    private var cards: List<Game> = emptyList()

    /**
     * A fetch is an HTTPS request and cannot happen on the thread drawing the
     * preview - the framework throws rather than merely being slow about it.
     */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_score_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        /*
         * Answer OK immediately rather than on the way out. The defaults are
         * already a working tile - it says "pick your teams" and opens this
         * screen when tapped - so there is no state in which the honest answer
         * is "cancel", and a cancelled configuration makes the host throw the
         * widget away, which is a harsh reading of a back gesture.
         */
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
        }

        previewHost = findViewById(R.id.preview_host)
        statusLabel = findViewById(R.id.status_label)
        statusDot = findViewById(R.id.status_dot)
        favoritesHost = findViewById(R.id.favorites_host)
        favoritesEmpty = findViewById(R.id.favorites_empty)
        pickerHost = findViewById(R.id.team_picker_host)
        searchField = findViewById(R.id.team_search)
        alertDenied = findViewById(R.id.alert_denied)
        findViewById<ImageView>(R.id.menu_button).setOnClickListener { showWidgetMenu(it) }
        toggleOffseason = findViewById(R.id.toggle_offseason)
        toggleRivalries = findViewById(R.id.toggle_rivalries)
        toggleWinProbability = findViewById(R.id.toggle_win_probability)

        leagueChips = mapOf(
            League.NFL to findViewById(R.id.league_nfl),
            League.NBA to findViewById(R.id.league_nba),
            League.MLB to findViewById(R.id.league_mlb),
            League.NHL to findViewById(R.id.league_nhl),
            League.NCAAF to findViewById(R.id.league_ncaaf)
        )
        alertChips = mapOf(
            ScoreSettings.ALERT_START to findViewById(R.id.alert_start),
            ScoreSettings.ALERT_CLOSE to findViewById(R.id.alert_close),
            ScoreSettings.ALERT_FINAL to findViewById(R.id.alert_final)
        )
        accentChips = mapOf(
            ScoreSettings.ACCENT_RED to findViewById(R.id.accent_red),
            ScoreSettings.ACCENT_AMBER to findViewById(R.id.accent_amber),
            ScoreSettings.ACCENT_WHITE to findViewById(R.id.accent_white)
        )

        wire()
        renderAll()
        loadCards(refetch = true)

        /*
         * The league the picker opens on, plus every league the user already
         * follows so their saved teams show their real names rather than the
         * stub TeamDirectory falls back to.
         *
         * Not all five: each is a request of about a megabyte, and four of them
         * would be populating lists this session may never open.
         */
        refreshDirectory(pickerLeague, *ScoreSettings.activeLeagues(this).toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        // Notification permission may have been changed while we were away.
        renderAlerts()
    }

    override fun onDestroy() {
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------

    private fun wire() {
        leagueChips.forEach { (league, chip) ->
            chip.setOnClickListener {
                pickerLeague = league
                renderLeagueChips()
                // Draw from whatever is cached first, then fill in behind it.
                renderPicker()
                refreshDirectory(league)
            }
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderPicker()
        })

        toggleOffseason.setOnClickListener {
            ScoreSettings.setFilterOffseason(this, !ScoreSettings.filterOffseason(this))
            renderToggles()
            // Changes which leagues are fetched at all, so it needs a real fetch.
            loadCards(refetch = true)
        }
        toggleRivalries.setOnClickListener {
            ScoreSettings.setRivalries(this, !ScoreSettings.rivalries(this))
            renderToggles()
            loadCards(refetch = true)
        }
        toggleWinProbability.setOnClickListener {
            ScoreSettings.setShowWinProbability(this, !ScoreSettings.showWinProbability(this))
            renderToggles()
            // Purely a drawing decision - the cards are unchanged.
            renderPreview()
            pushToWidgets()
        }

        alertChips.forEach { (alert, chip) ->
            chip.setOnClickListener {
                val current = ScoreSettings.alerts(this)
                val turningOn = !ScoreSettings.hasAlert(current, alert)
                ScoreSettings.setAlerts(this, current xor alert)
                renderAlerts()

                /*
                 * Ask for the permission at the moment the first alert is armed
                 * - not at install, and not when the screen opens. This is the
                 * only point where the request has a reason the user can see,
                 * which is also the only point they are likely to grant it.
                 */
                if (turningOn && !GameAlerts.granted(this)) {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS
                    )
                }
            }
        }

        accentChips.forEach { (choice, chip) ->
            chip.setOnClickListener {
                ScoreSettings.setAccentChoice(this, choice)
                renderAccent()
                renderPreview()
                pushToWidgets()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) renderAlerts()
    }

    // -----------------------------------------------------------------------

    private fun renderAll() {
        renderFavorites()
        renderLeagueChips()
        renderPicker()
        renderToggles()
        renderAlerts()
        renderAccent()
        renderStatus()
    }

    private fun renderFavorites() {
        favoritesHost.removeAllViews()
        val favorites = ScoreSettings.favorites(this)
        favoritesEmpty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE

        favorites.forEachIndexed { index, key ->
            val team = TeamDirectory.byKey(this, key) ?: return@forEachIndexed
            val row = LayoutInflater.from(this)
                .inflate(R.layout.row_favorite, favoritesHost, false)

            row.findViewById<ImageView>(R.id.row_glyph).setImageBitmap(
                GlyphMatrix.render(
                    TeamGlyphs.forTeam(this, team.league, team.abbrev),
                    resources.getDimensionPixelSize(R.dimen.card_glyph),
                    getColor(R.color.matrix_active)
                )
            )
            row.findViewById<TextView>(R.id.row_name).text = team.name
            row.findViewById<TextView>(R.id.row_league).text = team.league.label

            // The ends of the queue have nowhere to go, and a button that does
            // nothing should look like one.
            val up = row.findViewById<ImageView>(R.id.row_up)
            val down = row.findViewById<ImageView>(R.id.row_down)
            up.alpha = if (index == 0) 0.25f else 1f
            down.alpha = if (index == favorites.lastIndex) 0.25f else 1f

            up.setOnClickListener { if (index > 0) swap(index, index - 1) }
            down.setOnClickListener { if (index < favorites.lastIndex) swap(index, index + 1) }
            row.findViewById<ImageView>(R.id.row_remove).setOnClickListener { remove(index) }

            favoritesHost.addView(row)
        }
    }

    private fun renderLeagueChips() {
        leagueChips.forEach { (league, chip) -> chip.isSelected = league == pickerLeague }
    }

    private fun renderPicker() {
        pickerHost.removeAllViews()
        val chosen = ScoreSettings.favorites(this).toSet()
        val query = searchField.text?.toString().orEmpty()

        val results = TeamDirectory.search(this, pickerLeague, query)
            .filter { it.key !in chosen }
            // The picker sits inside the page's own ScrollView with no height
            // of its own, so an unbounded list would make the page enormous on
            // an empty search. Ten is enough to browse and few enough to scan.
            .take(if (query.isBlank()) PICKER_LIMIT else PICKER_LIMIT * 2)

        results.forEach { team ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.row_team_choice, pickerHost, false)

            row.findViewById<ImageView>(R.id.choice_glyph).setImageBitmap(
                GlyphMatrix.render(
                    TeamGlyphs.forTeam(this, team.league, team.abbrev),
                    resources.getDimensionPixelSize(R.dimen.card_glyph),
                    getColor(R.color.matrix_active)
                )
            )
            row.findViewById<TextView>(R.id.choice_name).text = team.name
            row.setOnClickListener { add(team.key) }

            pickerHost.addView(row)
        }
    }

    private fun renderToggles() {
        bindToggle(toggleOffseason, ScoreSettings.filterOffseason(this))
        bindToggle(toggleRivalries, ScoreSettings.rivalries(this))
        bindToggle(toggleWinProbability, ScoreSettings.showWinProbability(this))
    }

    /** On is the chip that went solid - the same idiom as the sibling data tile. */
    private fun bindToggle(chip: TextView, on: Boolean) {
        chip.isSelected = on
        chip.text = if (on) "ON" else "OFF"
    }

    private fun renderAlerts() {
        val mask = ScoreSettings.alerts(this)
        alertChips.forEach { (alert, chip) ->
            chip.isSelected = ScoreSettings.hasAlert(mask, alert)
        }
        // Only worth saying once something has actually been armed - before
        // that, "notifications are off" is not a problem to report.
        alertDenied.visibility =
            if (mask != 0 && !GameAlerts.granted(this)) View.VISIBLE else View.GONE
    }

    private fun renderAccent() {
        val choice = ScoreSettings.accentChoice(this)
        accentChips.forEach { (value, chip) -> chip.isSelected = value == choice }
    }

    private fun renderStatus() {
        val fetchedAt = GameRepository.fetchedAt(this)
        if (fetchedAt <= 0L) {
            statusLabel.text = getString(R.string.scorewidget_config_status_never)
            statusDot.setImageResource(R.drawable.score_status_dot_off)
            return
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fetchedAt))
        statusLabel.text = getString(R.string.scorewidget_config_status_updated, time)
        statusDot.setImageResource(R.drawable.score_status_dot_on)
    }

    private fun renderPreview() {
        val game = TeamFilter.pick(cards, 0)
        val views = WidgetRenderer.buildPreview(this, game, cards.size)
        previewHost.removeAllViews()
        // apply() rather than the RemoteViews being handed to a host: this is
        // our own unrestricted context, so the tile inflates here exactly as it
        // would in the launcher, bitmaps and all.
        previewHost.addView(views.apply(this, previewHost as ViewGroup))
    }

    // -----------------------------------------------------------------------

    private fun swap(from: Int, to: Int) {
        val updated = ScoreSettings.favorites(this).toMutableList()
        if (from !in updated.indices || to !in updated.indices) return
        val moved = updated.removeAt(from)
        updated.add(to, moved)
        ScoreSettings.setFavorites(this, updated)
        renderFavorites()
        // Reordering changes which game wins the tile, but not which games
        // exist - so the cards can simply be re-ranked from what is cached.
        loadCards(refetch = false)
    }

    private fun remove(index: Int) {
        val updated = ScoreSettings.favorites(this).toMutableList()
        if (index !in updated.indices) return
        updated.removeAt(index)
        ScoreSettings.setFavorites(this, updated)
        renderFavorites()
        renderPicker()
        loadCards(refetch = true)
    }

    private fun add(key: String) {
        val updated = ScoreSettings.favorites(this).toMutableList()
        if (key in updated) return
        updated.add(key)
        ScoreSettings.setFavorites(this, updated)
        searchField.setText("")
        renderFavorites()
        renderPicker()
        loadCards(refetch = true)
    }

    /**
     * Refreshes the preview's cards.
     *
     * @param refetch true when the change affects *which* games exist - a
     *   favourite added or removed, a league filtered out. False when it only
     *   affects their order or how they are drawn, in which case the cached
     *   response is re-ranked without another request. The distinction matters
     *   because this screen is where someone adds five teams in a row, and
     *   every one of those would otherwise be a fetch.
     */
    private fun loadCards(refetch: Boolean) {
        worker.execute {
            val loaded = if (refetch) {
                GameRepository.refresh(this)
            } else {
                TeamFilter.rank(
                    games = GameRepository.cards(this),
                    favorites = ScoreSettings.favorites(this),
                    rivalriesEnabled = ScoreSettings.rivalries(this),
                    filterOffseason = ScoreSettings.filterOffseason(this),
                    now = System.currentTimeMillis()
                )
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                cards = loaded
                renderPreview()
                renderStatus()
                pushToWidgets()
            }
        }
    }

    /**
     * Fills in the full team list for [leagues], in the background.
     *
     * The screen is already usable before this returns - [TeamDirectory] serves
     * the static seed until a fetch lands - so this never blocks anything and
     * quietly does nothing when the cached directory is still fresh.
     */
    private fun refreshDirectory(vararg leagues: League) {
        val wanted = leagues.toList()
        if (wanted.isEmpty()) return
        worker.execute {
            TeamDirectory.refresh(this, wanted)
            main.post {
                if (isFinishing || isDestroyed) return@post
                // Names on saved favourites can change too, not just the picker.
                renderFavorites()
                renderPicker()
            }
        }
    }

    /** Pushes the current settings out to every tile already on a home screen. */
    private fun pushToWidgets() {
        val snapshot = cards
        worker.execute {
            WidgetRenderer.refreshAll(this, snapshot)
            RefreshScheduler.arm(this, snapshot)
        }
    }

    /**
     * The way to the other three widgets' settings screens - the same menu
     * this module's siblings (:app's SetupActivity, :datawidget's and
     * :healthwidget's own ConfigActivity) each carry in their own header.
     *
     * :scorewidget cannot depend on :app or on the sibling widget modules
     * (see build.gradle.kts - :app already depends on all three, so the
     * reverse edge would be circular), so the other three activities are
     * targeted by string component name rather than a class literal, exactly
     * the way the manifest already merges all four into one package without
     * any module knowing about the others at compile time.
     */
    private fun showWidgetMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val entries = listOf(
            Triple(getString(R.string.scorewidget_menu_media_player), "com.dotgrid.mediawidget.SetupActivity", 0),
            Triple(getString(R.string.scorewidget_menu_data_widget), "com.dotgrid.datawidget.ConfigActivity", 1),
            Triple(getString(R.string.scorewidget_score_config_title), null, 2),
            Triple(getString(R.string.scorewidget_menu_health_widget), "com.dotgrid.healthwidget.ConfigActivity", 3)
        )
        entries.forEach { (label, className, id) ->
            if (className == null || resolveConfigIntent(className) != null) {
                popup.menu.add(0, id, id, label)
            }
        }
        popup.setOnMenuItemClickListener { item ->
            val target = entries.firstOrNull { it.third == item.itemId }?.second
            if (target != null) launchConfig(target)
            true
        }
        popup.show()
    }

    private fun resolveConfigIntent(className: String): Intent? {
        val intent = Intent().setComponent(ComponentName(packageName, className))
        return if (packageManager.resolveActivity(intent, 0) != null) intent else null
    }

    private fun launchConfig(className: String) {
        try {
            startActivity(Intent().setComponent(ComponentName(packageName, className)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.scorewidget_menu_settings_missing, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val PICKER_LIMIT = 10
    }
}
