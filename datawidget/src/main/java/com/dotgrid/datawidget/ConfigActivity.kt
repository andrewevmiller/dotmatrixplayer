package com.dotgrid.datawidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The plan, and how the tile should behave about it.
 *
 * One screen serving three entrances - the launcher icon, a tap on the tile,
 * and the widget's own reconfigure item - because they all want the same thing.
 * It renders a live copy of the tile at the top, built through
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
    private lateinit var grantButton: TextView
    private lateinit var cycleValue: TextView
    private lateinit var cycleNext: TextView
    private lateinit var limitField: EditText
    private lateinit var thresholdValue: TextView
    private lateinit var styleChips: Map<Int, TextView>
    private lateinit var colorChips: Map<Int, TextView>

    // The working copy. Written straight through to DataSettings on every edit.
    private var cycleDay = 1
    private var limitMb = 0
    private var alertStyles = 0
    private var alertPercent = 100
    private var colorChoice = DataSettings.COLOR_RED

    /**
     * The last byte count read, held so that editing a chip or the threshold
     * repaints the preview instantly instead of waiting on the stats service.
     * Only the rollover day changes the window the count comes from, so only
     * that edit has to go back and ask again.
     */
    private var bytes: Long? = null

    /**
     * NetworkStatsManager is a binder call into a service that reads its own
     * on-disk history, and it is slow enough to be felt: on the widget side it
     * runs on a broadcast thread already, but here it would land on the thread
     * drawing the preview.
     */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_config)

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

        previewHost = findViewById(R.id.preview_host)
        statusLabel = findViewById(R.id.status_label)
        statusDot = findViewById(R.id.status_dot)
        grantButton = findViewById(R.id.grant_button)
        cycleValue = findViewById(R.id.cycle_value)
        cycleNext = findViewById(R.id.cycle_next)
        limitField = findViewById(R.id.limit_value)
        thresholdValue = findViewById(R.id.threshold_value)
        findViewById<ImageView>(R.id.menu_button).setOnClickListener { showWidgetMenu(it) }

        styleChips = mapOf(
            DataSettings.STYLE_DOT to findViewById<TextView>(R.id.style_dot),
            DataSettings.STYLE_RING to findViewById<TextView>(R.id.style_ring),
            DataSettings.STYLE_VALUE to findViewById<TextView>(R.id.style_value),
            DataSettings.STYLE_CARD to findViewById<TextView>(R.id.style_card)
        )
        colorChips = mapOf(
            DataSettings.COLOR_RED to findViewById<TextView>(R.id.color_red),
            DataSettings.COLOR_AMBER to findViewById<TextView>(R.id.color_amber),
            DataSettings.COLOR_WHITE to findViewById<TextView>(R.id.color_white)
        )

        load()
        wire()
    }

    override fun onResume() {
        super.onResume()
        // Usage access may have been granted while we were in Settings.
        reload()
    }

    override fun onDestroy() {
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun load() {
        cycleDay = DataSettings.cycleDay(this)
        limitMb = DataSettings.limitMb(this)
        alertStyles = DataSettings.alertStyles(this)
        alertPercent = DataSettings.alertPercent(this)
        colorChoice = DataSettings.alertColorChoice(this)

        // Set once, here. render() deliberately leaves the field alone: writing
        // to it from the watcher it feeds would fight whatever is being typed.
        limitField.setText(UsageSnapshot.formatLimit(limitMb))
    }

    private fun wire() {
        grantButton.setOnClickListener { openUsageAccessSettings() }
        findViewById<TextView>(R.id.done_button).setOnClickListener { finish() }

        findViewById<TextView>(R.id.cycle_minus).setOnClickListener { stepCycle(-1) }
        findViewById<TextView>(R.id.cycle_plus).setOnClickListener { stepCycle(1) }

        findViewById<TextView>(R.id.threshold_minus).setOnClickListener { stepThreshold(-1) }
        findViewById<TextView>(R.id.threshold_plus).setOnClickListener { stepThreshold(1) }

        styleChips.forEach { (style, chip) ->
            chip.setOnClickListener {
                // A bitmask, not a single choice: the four indicators are
                // independent, and someone who wants the dial and the border
                // to both go red should not have to pick.
                alertStyles = alertStyles xor style
                persist()
                render()
            }
        }

        colorChips.forEach { (choice, chip) ->
            chip.setOnClickListener {
                colorChoice = choice
                persist()
                render()
            }
        }

        limitField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // A half-typed "1." is not a number yet; hold the last good
                // value rather than snapping the dial to zero mid-keystroke.
                val gb = s?.toString()?.trim()?.toDoubleOrNull() ?: return
                limitMb = (gb * 1000).toInt().coerceIn(
                    DataSettings.LIMIT_OFF_MB,
                    DataSettings.MAX_LIMIT_MB
                )
                persist()
                render()
            }
        })
    }

    private fun stepCycle(delta: Int) {
        cycleDay = (cycleDay + delta).coerceIn(1, 31)
        persist()
        // The rollover day moves the window the bytes were counted over, so
        // this is the one edit that has to go back to the stats service.
        reload()
    }

    private fun stepThreshold(direction: Int) {
        alertPercent = (alertPercent + direction * DataSettings.PERCENT_STEP)
            .coerceIn(DataSettings.MIN_PERCENT, DataSettings.MAX_PERCENT)
        persist()
        render()
    }

    private fun persist() {
        DataSettings.save(this, cycleDay, limitMb, alertStyles, alertPercent, colorChoice)
        val context = applicationContext
        worker.execute { WidgetRenderer.refreshAll(context) }
    }

    /** Re-reads the byte count, then repaints. */
    private fun reload() {
        render()

        val context = applicationContext
        val day = cycleDay
        worker.execute {
            val cycle = CycleMath.current(System.currentTimeMillis(), day)
            val read = if (MobileData.hasUsageAccess(context)) {
                MobileData.bytesInWindow(context, cycle.startMillis, cycle.endMillis)
            } else {
                null
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                bytes = read
                render()
            }
        }
    }

    private fun render() {
        val granted = MobileData.hasUsageAccess(this)

        statusLabel.setText(
            if (granted) R.string.datawidget_config_status_on else R.string.datawidget_config_status_off
        )
        statusDot.setImageResource(
            if (granted) R.drawable.data_status_dot_on else R.drawable.status_dot_off
        )
        grantButton.setText(
            if (granted) R.string.datawidget_config_granted else R.string.datawidget_config_grant
        )
        grantButton.isEnabled = !granted
        grantButton.alpha = if (granted) 0.45f else 1f

        val cycle = CycleMath.current(System.currentTimeMillis(), cycleDay)

        /*
         * Locale.US for the figures, not the device locale. These are set in
         * Ndot 57 Aligned, which carries Latin digits and nothing else - a
         * locale that renders numbers in Arabic-Indic or Devanagari digits
         * would push them out to a fallback face, and the one thing this screen
         * is demonstrating is what the widget's own type looks like.
         *
         * The month name is a word rather than a figure, so that one does
         * follow the device: it is read, not counted.
         */
        cycleValue.text = String.format(Locale.US, "%d", cycleDay)
        cycleNext.text = getString(
            R.string.datawidget_config_next_reset_format,
            monthDayFormat().format(Date(cycle.endMillis)).uppercase(Locale.getDefault())
        )
        thresholdValue.text = String.format(
            Locale.US,
            getString(R.string.datawidget_config_threshold_format),
            alertPercent
        )

        styleChips.forEach { (style, chip) ->
            chip.isSelected = DataSettings.hasStyle(alertStyles, style)
        }
        colorChips.forEach { (choice, chip) -> paintColorChip(chip, choice) }

        previewHost.removeAllViews()
        val snapshot = UsageSnapshot(
            bytes = if (granted) bytes else null,
            limitMb = limitMb,
            daysLeft = cycle.daysLeft,
            cycleDay = cycleDay,
            hasAccess = granted,
            alertStyles = alertStyles,
            alertPercent = alertPercent,
            alertColor = DataSettings.colorFor(this, colorChoice)
        )
        val views = WidgetRenderer.buildPreview(this, snapshot)
        previewHost.addView(views.apply(applicationContext, previewHost))
    }

    /**
     * The colour chips wear the colour they name. Everything else on this
     * screen selects to a white pill, but a swatch that does not show its own
     * swatch is asking the user to take the word "AMBER" on trust.
     */
    private fun paintColorChip(chip: TextView, choice: Int) {
        val selected = colorChoice == choice
        chip.isSelected = selected
        if (selected) {
            val color = DataSettings.colorFor(this, choice)
            chip.backgroundTintList = ColorStateList.valueOf(color)
            chip.setTextColor(
                getColor(
                    // WHITE now resolves through text_primary (theme-aware), so
                    // the fill flips with the theme too: nt_black text reads on
                    // the dark-mode white fill, nt_white text on the light-mode
                    // black fill. RED and AMBER's own fills don't flip, so they
                    // keep the white label they've always had.
                    if (choice == DataSettings.COLOR_WHITE) {
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

    private fun openUsageAccessSettings() {
        /*
         * The per-app screen is the one worth landing on, but not every OEM
         * honours the package URI on this action, and resolveActivity is
         * unreliable under package-visibility filtering. Try the specific one,
         * fall back to the list, then say so rather than doing nothing.
         */
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null))
        try {
            startActivity(direct)
            return
        } catch (e: ActivityNotFoundException) {
            // Fall through to the undirected screen.
        }
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.datawidget_config_settings_missing, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Day and short month: "7 SEP".
     *
     * Built per call rather than held as a constant. A SimpleDateFormat binds
     * the locale it was constructed with, and the user can change the device
     * locale while this process is still alive - after which a cached formatter
     * would go on printing month names in the old language.
     */
    private fun monthDayFormat() = SimpleDateFormat("d MMM", Locale.getDefault())

    /**
     * The way to the other three widgets' settings screens - the same menu
     * this module's siblings (:app's SetupActivity, :scorewidget's and
     * :healthwidget's own ConfigActivity) each carry in their own header.
     *
     * :datawidget cannot depend on :app or on the sibling widget modules
     * (see build.gradle.kts - :app already depends on all three, so the
     * reverse edge would be circular), so the other three activities are
     * targeted by string component name rather than a class literal, exactly
     * the way the manifest already merges all four into one package without
     * any module knowing about the others at compile time.
     */
    private fun showWidgetMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        val entries = listOf(
            Triple(getString(R.string.datawidget_menu_media_player), "com.dotgrid.mediawidget.SetupActivity", 0),
            Triple(getString(R.string.datawidget_data_config_title), null, 1),
            Triple(getString(R.string.datawidget_menu_score_widget), "com.dotgrid.scorewidget.ConfigActivity", 2),
            Triple(getString(R.string.datawidget_menu_health_widget), "com.dotgrid.healthwidget.ConfigActivity", 3)
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
        val intent = Intent().setComponent(android.content.ComponentName(packageName, className))
        return if (packageManager.resolveActivity(intent, 0) != null) intent else null
    }

    private fun launchConfig(className: String) {
        try {
            startActivity(Intent().setComponent(android.content.ComponentName(packageName, className)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.datawidget_menu_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}
