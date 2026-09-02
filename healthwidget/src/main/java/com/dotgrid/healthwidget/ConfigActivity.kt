package com.dotgrid.healthwidget

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What the tile reads, and what it does about it.
 *
 * One screen serving five entrances - the launcher icon, a tap on the tile, the
 * widget's own reconfigure item, and Health Connect's two rationale actions -
 * because they all want the same thing. It renders live copies of both tile
 * layouts at the top, built through [WidgetRenderer.buildPreview], so every
 * setting can be seen taking effect on the size you are not holding, and a
 * rendering bug shows up here rather than only on a home screen.
 *
 * Settings are saved as they are touched rather than on the way out. A widget
 * configuration screen can be left by the back gesture, by the home key, or by
 * the system deciding it has waited long enough, and a Save button would lose
 * to all three.
 */
class ConfigActivity : ComponentActivity() {

    private lateinit var previewSquare: FrameLayout
    private lateinit var previewWide: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var statusDot: ImageView
    private lateinit var grantButton: TextView
    private lateinit var manageButton: TextView
    private lateinit var stepsGoalValue: TextView
    private lateinit var sleepGoalValue: TextView

    private lateinit var metricChips: Map<Int, TextView>
    private lateinit var sleepModeChips: Map<Int, TextView>
    private lateinit var windowChips: Map<Int, TextView>
    private lateinit var unstagedChips: Map<Boolean, TextView>
    private lateinit var primaryChips: Map<Int, TextView>
    private lateinit var styleChips: Map<Int, TextView>
    private lateinit var colorChips: Map<Int, TextView>
    private lateinit var tapChips: Map<Int, TextView>
    private lateinit var backgroundChip: TextView

    /** The working copy. Written straight through to [HealthSettings] on every edit. */
    private var prefs = HealthSettings.Prefs(
        metrics = HealthSettings.METRIC_STEPS or HealthSettings.METRIC_SLEEP,
        primary = HealthSettings.METRIC_STEPS,
        sleepMode = HealthSettings.SLEEP_ASLEEP,
        sleepWindow = HealthSettings.WINDOW_NIGHT,
        countUnstaged = true,
        stepsGoal = 10_000,
        sleepGoalMinutes = 480,
        styles = HealthSettings.STYLE_DOT or HealthSettings.STYLE_RING,
        colorChoice = HealthSettings.COLOR_RED,
        tapTarget = HealthSettings.TAP_SETTINGS
    )

    /**
     * The last snapshot read, held so that touching a chip repaints both
     * previews instantly instead of waiting on Health Connect.
     *
     * Most settings only change how the figures are drawn. The four that change
     * what is read - the metric set, the sleep reading, the window, and whether
     * unstaged sessions count - go back and ask again; the rest repaint from
     * this.
     */
    private var snapshot: HealthSnapshot? = null

    /**
     * Health Connect is another app behind a binder, and it is slow enough to
     * be felt: on the widget side the query runs on a broadcast thread already,
     * but here it would land on the thread drawing the previews.
     */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /**
     * Health Connect grants are requested through its own contract, not through
     * the platform permission dialog - which is why this screen exists as an
     * Activity at all. An AppWidgetProvider is a BroadcastReceiver and cannot
     * put a dialog in front of anyone.
     */
    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // Health Connect stops showing the sheet after two refusals and returns
        // immediately from then on, which looks identical to a button that does
        // nothing. Say so, and point at the screen that still works.
        if (granted.isEmpty()) {
            Toast.makeText(this, R.string.config_permissions_denied, Toast.LENGTH_LONG).show()
        }
        reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        /*
         * Answer OK immediately rather than on the way out. The defaults are
         * already a working tile, so there is no state in which the honest
         * answer is "cancel" - and a cancelled configuration makes the host
         * throw the widget away, which is a harsh reading of a back gesture.
         */
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
        }

        bind()
        prefs = HealthSettings.read(this)
        wire()
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been changed in Health Connect while we were
        // away - including revoked, which nothing sends us an event for.
        reload()
    }

    override fun onDestroy() {
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bind() {
        previewSquare = findViewById(R.id.preview_square)
        previewWide = findViewById(R.id.preview_wide)
        statusLabel = findViewById(R.id.status_label)
        statusDot = findViewById(R.id.status_dot)
        grantButton = findViewById(R.id.grant_button)
        manageButton = findViewById(R.id.manage_button)
        findViewById<ImageView>(R.id.menu_button).setOnClickListener { showWidgetMenu(it) }
        stepsGoalValue = findViewById(R.id.steps_goal_value)
        sleepGoalValue = findViewById(R.id.sleep_goal_value)
        backgroundChip = findViewById(R.id.chip_background)

        metricChips = mapOf(
            HealthSettings.METRIC_STEPS to findViewById(R.id.metric_steps),
            HealthSettings.METRIC_SLEEP to findViewById(R.id.metric_sleep),
            HealthSettings.METRIC_HEART to findViewById(R.id.metric_heart),
            HealthSettings.METRIC_OXYGEN to findViewById(R.id.metric_oxygen),
            HealthSettings.METRIC_BREATH to findViewById(R.id.metric_breath)
        )
        sleepModeChips = mapOf(
            HealthSettings.SLEEP_ASLEEP to findViewById(R.id.sleep_asleep),
            HealthSettings.SLEEP_IN_BED to findViewById(R.id.sleep_in_bed),
            HealthSettings.SLEEP_RESTFUL to findViewById(R.id.sleep_restful),
            HealthSettings.SLEEP_DEEP to findViewById(R.id.sleep_deep)
        )
        windowChips = mapOf(
            HealthSettings.WINDOW_NIGHT to findViewById(R.id.window_night),
            HealthSettings.WINDOW_24H to findViewById(R.id.window_24h),
            HealthSettings.WINDOW_TODAY to findViewById(R.id.window_today)
        )
        unstagedChips = mapOf(
            true to findViewById(R.id.unstaged_count),
            false to findViewById(R.id.unstaged_ignore)
        )
        primaryChips = mapOf(
            HealthSettings.METRIC_STEPS to findViewById(R.id.primary_steps),
            HealthSettings.METRIC_SLEEP to findViewById(R.id.primary_sleep),
            HealthSettings.METRIC_HEART to findViewById(R.id.primary_heart),
            HealthSettings.METRIC_OXYGEN to findViewById(R.id.primary_oxygen),
            HealthSettings.METRIC_BREATH to findViewById(R.id.primary_breath)
        )
        styleChips = mapOf(
            HealthSettings.STYLE_DOT to findViewById(R.id.style_dot),
            HealthSettings.STYLE_RING to findViewById(R.id.style_ring),
            HealthSettings.STYLE_VALUE to findViewById(R.id.style_value),
            HealthSettings.STYLE_CARD to findViewById(R.id.style_card)
        )
        colorChips = mapOf(
            HealthSettings.COLOR_RED to findViewById(R.id.color_red),
            HealthSettings.COLOR_AMBER to findViewById(R.id.color_amber),
            HealthSettings.COLOR_WHITE to findViewById(R.id.color_white)
        )
        tapChips = mapOf(
            HealthSettings.TAP_SETTINGS to findViewById(R.id.tap_settings),
            HealthSettings.TAP_HEALTH_CONNECT to findViewById(R.id.tap_health),
            HealthSettings.TAP_FITBIT to findViewById(R.id.tap_fitbit)
        )
    }

    private fun wire() {
        grantButton.setOnClickListener { askForPermissions() }
        manageButton.setOnClickListener { openHealthConnectSettings() }
        findViewById<TextView>(R.id.done_button).setOnClickListener { finish() }

        /*
         * Metrics are a set, not a choice, and turning one on is also a request
         * to read it - so the chip both saves and goes and asks. Turning the
         * last one off is refused rather than obeyed: an empty tile has no way
         * back to this screen except the launcher icon.
         */
        metricChips.forEach { (metric, chip) ->
            chip.setOnClickListener {
                val next = prefs.metrics xor metric
                if (next == 0) return@setOnClickListener
                prefs = prefs.copy(metrics = next)
                persist()
                if (prefs.shows(metric)) askForPermissions() else reload()
            }
        }

        backgroundChip.setOnClickListener { askForPermissions() }

        sleepModeChips.forEach { (mode, chip) ->
            chip.setOnClickListener {
                prefs = prefs.copy(sleepMode = mode)
                persist()
                // Changes what counts, not just how it is drawn.
                reload()
            }
        }

        windowChips.forEach { (window, chip) ->
            chip.setOnClickListener {
                prefs = prefs.copy(sleepWindow = window)
                persist()
                reload()
            }
        }

        unstagedChips.forEach { (count, chip) ->
            chip.setOnClickListener {
                prefs = prefs.copy(countUnstaged = count)
                persist()
                reload()
            }
        }

        primaryChips.forEach { (metric, chip) ->
            chip.setOnClickListener {
                prefs = prefs.copy(primary = metric)
                persist()
                render()
            }
        }

        styleChips.forEach { (style, chip) ->
            chip.setOnClickListener {
                // A bitmask, not a single choice: the four indicators are
                // independent, and someone who wants the dial and the border to
                // both take the accent should not have to pick.
                prefs = prefs.copy(styles = prefs.styles xor style)
                persist()
                render()
            }
        }

        colorChips.forEach { (choice, chip) ->
            chip.setOnClickListener {
                prefs = prefs.copy(colorChoice = choice)
                persist()
                render()
            }
        }

        tapChips.forEach { (target, chip) ->
            chip.setOnClickListener {
                if (target == HealthSettings.TAP_FITBIT && !isInstalled(
                        NothingHealthWidgetProvider.FITBIT_PACKAGE
                    )
                ) {
                    // Allowed anyway - the tile falls back to this screen - but
                    // said out loud, because a tap target that silently does
                    // something else is worse than one that warned you.
                    Toast.makeText(this, R.string.config_tap_missing, Toast.LENGTH_SHORT).show()
                }
                prefs = prefs.copy(tapTarget = target)
                persist()
                render()
            }
        }

        findViewById<TextView>(R.id.steps_goal_minus).setOnClickListener { stepStepsGoal(-1) }
        findViewById<TextView>(R.id.steps_goal_plus).setOnClickListener { stepStepsGoal(1) }
        findViewById<TextView>(R.id.sleep_goal_minus).setOnClickListener { stepSleepGoal(-1) }
        findViewById<TextView>(R.id.sleep_goal_plus).setOnClickListener { stepSleepGoal(1) }
    }

    private fun stepStepsGoal(direction: Int) {
        val next = prefs.stepsGoal + direction * HealthSettings.STEPS_GOAL_STEP
        prefs = prefs.copy(
            stepsGoal = next.coerceIn(HealthSettings.STEPS_GOAL_OFF, HealthSettings.MAX_STEPS_GOAL)
        )
        persist()
        render()
    }

    private fun stepSleepGoal(direction: Int) {
        val next = prefs.sleepGoalMinutes + direction * HealthSettings.SLEEP_GOAL_STEP_MIN
        prefs = prefs.copy(
            sleepGoalMinutes = next.coerceIn(
                HealthSettings.SLEEP_GOAL_OFF, HealthSettings.MAX_SLEEP_GOAL_MIN
            )
        )
        persist()
        render()
    }

    private fun persist() {
        HealthSettings.save(this, prefs)
        val context = applicationContext
        worker.execute { WidgetRenderer.refreshAll(context) }
    }

    /** Re-reads Health Connect, then repaints. */
    private fun reload() {
        render()

        val context = applicationContext
        worker.execute {
            val read = runCatching { HealthSnapshot.read(context) }.getOrNull()
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (read != null) snapshot = read
                render()
            }
        }
    }

    private fun render() {
        val current = snapshot
        val sdkAvailable = HealthAccess.sdkAvailable(this)
        val granted = current?.granted ?: emptySet()

        val wanted = prefs.enabledMetrics().map { HealthAccess.permissionFor(it) }
        val held = wanted.count { it in granted }
        val backgroundHeld = HealthAccess.BACKGROUND_PERMISSION in granted

        statusLabel.setText(
            when {
                !sdkAvailable -> R.string.config_status_missing
                held == 0 -> R.string.config_status_off
                held < wanted.size || !backgroundHeld -> R.string.config_status_partial
                else -> R.string.config_status_on
            }
        )
        // The dot is the one place on this screen that is not monochrome, so it
        // only ever means "something needs you": red until everything asked for
        // has been given, white once it has.
        statusDot.setImageResource(
            if (sdkAvailable && held == wanted.size && backgroundHeld) R.drawable.health_status_dot_on
            else R.drawable.health_status_dot_off
        )

        grantButton.setText(
            when {
                !sdkAvailable -> R.string.config_install
                held == wanted.size && backgroundHeld -> R.string.config_granted
                else -> R.string.health_config_grant
            }
        )
        val nothingLeftToAsk = sdkAvailable && held == wanted.size && backgroundHeld
        grantButton.isEnabled = !nothingLeftToAsk
        grantButton.alpha = if (nothingLeftToAsk) 0.45f else 1f
        manageButton.alpha = if (sdkAvailable) 1f else 0.45f
        manageButton.isEnabled = sdkAvailable

        /*
         * A metric chip carries two states at once: whether the tile wants it,
         * and whether Health Connect has given it. Selected is "wanted"; a
         * wanted metric still waiting on its grant is dimmed, so the screen
         * distinguishes "I turned that off" from "it would not let me".
         */
        metricChips.forEach { (metric, chip) ->
            val wantedHere = prefs.shows(metric)
            chip.isSelected = wantedHere
            chip.alpha = when {
                !wantedHere -> 1f
                HealthAccess.permissionFor(metric) in granted -> 1f
                else -> 0.45f
            }
        }
        backgroundChip.isSelected = backgroundHeld

        sleepModeChips.forEach { (mode, chip) -> chip.isSelected = prefs.sleepMode == mode }
        windowChips.forEach { (window, chip) -> chip.isSelected = prefs.sleepWindow == window }
        unstagedChips.forEach { (count, chip) -> chip.isSelected = prefs.countUnstaged == count }
        tapChips.forEach { (target, chip) -> chip.isSelected = prefs.tapTarget == target }
        styleChips.forEach { (style, chip) -> chip.isSelected = prefs.styled(style) }

        /*
         * The dial can only show a metric the tile is reading, so the chips for
         * the others are dimmed rather than hidden - a control that disappears
         * when you turn something else off is a control the user has to
         * rediscover. The selection follows dialMetric(), which falls back when
         * the chosen one has been turned off.
         */
        val dialMetric = prefs.dialMetric()
        primaryChips.forEach { (metric, chip) ->
            chip.isSelected = dialMetric == metric
            chip.isEnabled = prefs.shows(metric)
            chip.alpha = if (prefs.shows(metric)) 1f else 0.35f
        }

        colorChips.forEach { (choice, chip) -> paintColorChip(chip, choice) }

        stepsGoalValue.text = if (prefs.stepsGoal <= 0) {
            getString(R.string.value_unknown)
        } else {
            // Locale.US for the figures: they are set in Geist (StepperValue
            // is Body - it's the figure being edited, not a control), and a
            // locale that renders numbers in Arabic-Indic digits would push
            // them to a fallback face.
            String.format(Locale.US, "%,d", prefs.stepsGoal)
        }
        sleepGoalValue.text = if (prefs.sleepGoalMinutes <= 0) {
            getString(R.string.value_unknown)
        } else {
            String.format(
                Locale.US,
                getString(R.string.config_sleep_goal_format),
                prefs.sleepGoalMinutes / 60,
                prefs.sleepGoalMinutes % 60
            )
        }

        paintPreviews(current)
    }

    /**
     * Both tiles, drawn from the settings in hand.
     *
     * The snapshot may be null on the first pass - the read has not come back
     * yet - so the preview is built from a placeholder carrying the same
     * settings. Better an empty tile in the right shape than a hole where the
     * preview goes and then a jump when the data lands.
     */
    private fun paintPreviews(current: HealthSnapshot?) {
        val shown = current?.let {
            HealthSnapshot(
                sdkStatus = it.sdkStatus,
                granted = it.granted,
                readings = it.readings,
                readAt = it.readAt,
                stale = it.stale,
                // The live settings, not the ones the read was taken under, so
                // a chip repaints the preview without a round trip.
                prefs = prefs,
                accentColor = HealthSettings.colorFor(this, prefs.colorChoice)
            )
        } ?: HealthSnapshot(
            sdkStatus = HealthAccess.sdkStatus(this),
            granted = emptySet(),
            readings = emptyMap(),
            readAt = System.currentTimeMillis(),
            stale = false,
            prefs = prefs,
            accentColor = HealthSettings.colorFor(this, prefs.colorChoice)
        )

        previewSquare.removeAllViews()
        previewSquare.addView(
            WidgetRenderer.buildPreview(this, shown, wide = false)
                .apply(applicationContext, previewSquare)
        )

        previewWide.removeAllViews()
        previewWide.addView(
            WidgetRenderer.buildPreview(this, shown, wide = true)
                .apply(applicationContext, previewWide)
        )
    }

    /**
     * The colour chips wear the colour they name. Everything else on this
     * screen selects to a white pill, but a swatch that does not show its own
     * swatch is asking the user to take the word "AMBER" on trust.
     */
    private fun paintColorChip(chip: TextView, choice: Int) {
        val selected = prefs.colorChoice == choice
        chip.isSelected = selected
        if (selected) {
            chip.backgroundTintList =
                ColorStateList.valueOf(HealthSettings.colorFor(this, choice))
            chip.setTextColor(
                getColor(
                    // WHITE now resolves through text_primary (theme-aware), so
                    // the fill flips with the theme too: nt_black text reads on
                    // the dark-mode white fill, nt_white text on the light-mode
                    // black fill. RED and AMBER's own fills don't flip, so they
                    // keep the white label they've always had.
                    if (choice == HealthSettings.COLOR_WHITE) {
                        if (isNightMode()) R.color.nt_black else R.color.nt_white
                    } else {
                        R.color.nt_white
                    }
                )
            )
        } else {
            chip.backgroundTintList = null
            chip.setTextColor(getColorStateList(R.color.chip_text))
        }
    }

    /** Whether the system is currently in dark mode, per the active configuration. */
    private fun isNightMode(): Boolean {
        val flags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return flags == Configuration.UI_MODE_NIGHT_YES
    }

    private fun askForPermissions() {
        if (!HealthAccess.sdkAvailable(this)) {
            openPlayStoreListing()
            return
        }
        // Everything currently wanted, in one sheet. Asking only for what is
        // missing would be tidier, but Health Connect shows the whole set with
        // the held ones already ticked, and a sheet that omits them looks like
        // it is about to take them away.
        requestPermissions.launch(HealthAccess.permissionsFor(prefs))
    }

    private fun openHealthConnectSettings() {
        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(intent) }.isSuccess) return
        openPlayStoreListing()
    }

    /**
     * Health Connect is a separate app before Android 14 and a system module
     * after it, so "not available" can mean not installed or simply too old.
     * The listing handles both, and is where the platform's own dialog sends
     * people.
     */
    private fun openPlayStoreListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$HEALTH_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(market) }.isSuccess) return

        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(web) }.onFailure {
            if (it is ActivityNotFoundException) {
                Toast.makeText(this, R.string.config_store_missing, Toast.LENGTH_LONG).show()
            } else {
                throw it
            }
        }
    }

    private fun isInstalled(packageName: String): Boolean =
        packageManager.getLaunchIntentForPackage(packageName) != null

    /**
     * The way to the other three widgets' settings screens - the same menu
     * this module's siblings (:app's SetupActivity, :datawidget's and
     * :scorewidget's own ConfigActivity) each carry in their own header.
     *
     * :healthwidget cannot depend on :app or on the sibling widget modules
     * (see build.gradle.kts - :app already depends on all three, so the
     * reverse edge would be circular), so the other three activities are
     * targeted by string component name rather than a class literal, exactly
     * the way the manifest already merges all four into one package without
     * any module knowing about the others at compile time.
     */
    private fun showWidgetMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        val entries = listOf(
            Triple(getString(R.string.menu_media_player), "com.dotgrid.mediawidget.SetupActivity", 0),
            Triple(getString(R.string.menu_data_widget), "com.dotgrid.datawidget.ConfigActivity", 1),
            Triple(getString(R.string.menu_score_widget), "com.dotgrid.scorewidget.ConfigActivity", 2),
            Triple(getString(R.string.health_config_title), null, 3)
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
            Toast.makeText(this, R.string.menu_settings_missing, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val HEALTH_PACKAGE = "com.google.android.apps.healthdata"
    }
}
