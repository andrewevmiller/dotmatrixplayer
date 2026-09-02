package com.dotgrid.mediawidget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

/**
 * One screen, one job: get notification access granted, then get out of the way.
 *
 * It renders a live copy of the widget underneath the instructions - the same
 * RemoteViews the launcher gets - so the user can see the thing working before
 * they go looking for it in the widget picker.
 */
class SetupActivity : Activity() {

    private lateinit var statusLabel: TextView
    private lateinit var statusDot: ImageView
    private lateinit var grantButton: TextView
    private lateinit var previewHost: FrameLayout
    private lateinit var idlePrefRow: View
    private lateinit var idlePrefValue: TextView
    private lateinit var orderList: LinearLayout
    private lateinit var orderEmpty: TextView
    private lateinit var orderReset: TextView
    private lateinit var menuButton: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        statusLabel = findViewById(R.id.status_dots)
        statusDot = findViewById(R.id.status_dot)
        grantButton = findViewById(R.id.grant_button)
        previewHost = findViewById(R.id.preview_host)
        idlePrefRow = findViewById(R.id.idle_pref_row)
        idlePrefValue = findViewById(R.id.idle_pref_value)
        orderList = findViewById(R.id.source_order_list)
        orderEmpty = findViewById(R.id.source_order_empty)
        orderReset = findViewById(R.id.source_order_reset)
        menuButton = findViewById(R.id.menu_button)

        /*
         * SetupActivity doubles as the media widget's configure target
         * (see media_widget_info.xml). configuration_optional means the
         * tile already landed with defaults before this screen could ever
         * open, so there is no form to complete here - unlike the sibling
         * ConfigActivity screens, which gate setResult on a real choice
         * getting made, this hub has nothing left to gate on. Report
         * success up front so the reconfigure path from the widget's
         * long-press menu never reads as a cancel.
         */
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
        }

        grantButton.setOnClickListener { openListenerSettings() }
        idlePrefRow.setOnClickListener { showIdlePreferencePicker() }
        menuButton.setOnClickListener { showWidgetMenu(it) }
        orderReset.setOnClickListener {
            SourceOrder.clear(this)
            renderOrderList()
            WidgetRenderer.refreshAll(this)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val granted = MediaHub.hasNotificationAccess(this)

        statusLabel.text = getString(
            if (granted) R.string.setup_status_on else R.string.setup_status_off
        )

        statusDot.setImageResource(
            if (granted) R.drawable.status_dot_on else R.drawable.live_dot
        )

        grantButton.text = getString(
            if (granted) R.string.setup_granted else R.string.setup_grant
        )
        grantButton.isEnabled = !granted
        grantButton.alpha = if (granted) 0.45f else 1f

        idlePrefValue.text = IdlePreference.get(this)
            ?.let { pkg -> labelFor(pkg) ?: pkg }
            ?: getString(R.string.setup_idle_pref_none)

        renderOrderList()

        // Rebuild the preview from the same code path the widget uses, so a
        // rendering bug shows up here too rather than only on the home screen.
        previewHost.removeAllViews()
        val views = WidgetRenderer.buildPreview(this, MediaHub.snapshot(this))
        previewHost.addView(views.apply(applicationContext, previewHost))
    }

    private fun labelFor(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    /**
     * Rebuilds the carousel-order list from scratch.
     *
     * Cheap enough to redo wholesale on every move - this is a handful of rows
     * on a screen the user is looking at, and rebuilding sidesteps the class of
     * bug where a row's move handler still refers to the index it had before
     * the list shifted underneath it.
     */
    private fun renderOrderList() {
        // Observe live sessions first, so anything playing right now is in the
        // list on this pass rather than only after the next repaint.
        if (MediaHub.hasNotificationAccess(this)) SessionCarousel.sessions(this)

        val apps = SourceOrder.listForSettings(this)
        orderList.removeAllViews()

        orderEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        orderReset.visibility =
            if (SourceOrder.hasCustomOrder(this)) View.VISIBLE else View.GONE

        val glyphPx = (20 * resources.displayMetrics.density).toInt()
        val tint = getColor(R.color.app_glyph)

        apps.forEachIndexed { index, app ->
            val row = layoutInflater.inflate(R.layout.source_order_row, orderList, false)

            row.findViewById<TextView>(R.id.row_position).text = (index + 1).toString()
            val labelView = row.findViewById<TextView>(R.id.row_label)
            labelView.text = app.label
            // A package id standing in for a name is a fallback, not a
            // fact about the app - dim it, the way the transport keys dim
            // rather than disappear when they cannot do anything.
            labelView.alpha = if (app.named) 1f else 0.6f

            // Same monochrome treatment the widget gives the source mark, so
            // the row and the tile are recognisably describing the same thing.
            val glyphView = row.findViewById<ImageView>(R.id.row_glyph)
            val glyph = AppGlyph.render(this, app.packageName, glyphPx, tint)
            if (glyph != null) {
                glyphView.setImageBitmap(glyph)
                glyphView.visibility = View.VISIBLE
            } else {
                glyphView.visibility = View.INVISIBLE
            }

            // The ends of the list have nowhere to go; dim rather than hide, so
            // the rows stay the same width and the list does not twitch as
            // things move through it.
            val up = row.findViewById<ImageView>(R.id.row_up)
            val down = row.findViewById<ImageView>(R.id.row_down)
            bindMove(up, index, -1, enabled = index > 0, apps = apps)
            bindMove(down, index, +1, enabled = index < apps.size - 1, apps = apps)

            orderList.addView(row)
        }
    }

    private fun bindMove(
        button: ImageView,
        index: Int,
        delta: Int,
        enabled: Boolean,
        apps: List<SourceOrder.Entry>
    ) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.25f
        if (!enabled) {
            button.setOnClickListener(null)
            return
        }
        button.setOnClickListener {
            // Writing the whole visible list, not just the moved pair: the
            // stored order is partial by design, and persisting only the two
            // that swapped would leave the rest unranked and re-sorting
            // alphabetically around them.
            val reordered = apps.map { it.packageName }.toMutableList()
            java.util.Collections.swap(reordered, index, index + delta)
            SourceOrder.set(this, reordered)
            renderOrderList()
            WidgetRenderer.refreshAll(this)
        }
    }

    /**
     * "Most recent" plus every media app the device can see, so picking a
     * preference is a plain single-choice list rather than free text.
     */
    private fun showIdlePreferencePicker() {
        val candidates = IdlePreference.candidates(this)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.setup_idle_pref_empty, Toast.LENGTH_LONG).show()
            return
        }

        val entries = listOf(getString(R.string.setup_idle_pref_none)) + candidates.map { it.label }
        val current = IdlePreference.get(this)
        val checked = candidates.indexOfFirst { it.packageName == current } + 1 // 0 = "Most recent"

        AlertDialog.Builder(this)
            .setTitle(R.string.setup_idle_pref_dialog_title)
            .setSingleChoiceItems(entries.toTypedArray(), checked) { dialog, index ->
                IdlePreference.set(this, if (index == 0) null else candidates[index - 1].packageName)
                dialog.dismiss()
                render()
                // The new preference can change which session is idle-active
                // right now - reflect that on the home screen immediately
                // rather than waiting for the next natural refresh.
                WidgetRenderer.refreshAll(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The hamburger menu that replaces the old inline "other widgets" card.
     *
     * One installed app carries four tiles and only this screen is in the
     * launcher, so this is the way to the other three settings screens (and,
     * from each of those, back here and sideways to each other - see the
     * matching menu in each sibling module's own ConfigActivity). This
     * screen's own entry is listed too, so the set always reads as "all four
     * widgets"; tapping it just dismisses the menu.
     */
    private fun showWidgetMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val entries = listOf(
            Triple(getString(R.string.app_name), null, 0),
            Triple(
                getString(com.dotgrid.datawidget.R.string.datawidget_data_config_title),
                "com.dotgrid.datawidget.ConfigActivity",
                1
            ),
            Triple(
                getString(com.dotgrid.scorewidget.R.string.scorewidget_score_config_title),
                "com.dotgrid.scorewidget.ConfigActivity",
                2
            ),
            Triple(
                getString(com.dotgrid.healthwidget.R.string.healthwidget_health_config_title),
                "com.dotgrid.healthwidget.ConfigActivity",
                3
            )
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

    /**
     * Resolves a sibling widget's settings screen by class name rather than
     * class literal: all four modules call their settings screen
     * ConfigActivity or SetupActivity, so class literals would need import
     * aliases, and a module dropped from the bundle would break the build
     * here rather than simply not offering its menu entry.
     */
    private fun resolveConfigIntent(className: String): Intent? {
        val intent = Intent().setComponent(ComponentName(packageName, className))
        return if (packageManager.resolveActivity(intent, 0) != null) intent else null
    }

    private fun launchConfig(className: String) {
        try {
            startActivity(Intent().setComponent(ComponentName(packageName, className)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.setup_settings_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun openListenerSettings() {
        // resolveActivity is unreliable under package-visibility filtering, so
        // just try it and handle the miss.
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.setup_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}
