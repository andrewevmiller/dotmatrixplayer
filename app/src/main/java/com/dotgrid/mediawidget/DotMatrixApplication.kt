package com.dotgrid.mediawidget

import android.app.Application
import android.content.res.Configuration

/**
 * Repaints every tile in the bundle when the system's light/dark state changes.
 *
 * A widget only redraws when something asks it to - a media-session update, a
 * five-minute alarm, a manual tap. Flipping the system theme is none of those,
 * so a tile already on a home screen keeps showing bitmaps and a background
 * baked under the old theme until the next unrelated trigger happens to fire.
 * That is invisible most of the time (the two palettes were designed to both
 * be legible) but becomes visible exactly when a phone's *scheduled*
 * day/night switch lands while a tile is idle - the failure this class exists
 * to close.
 *
 * [Application.onConfigurationChanged] is delivered to every live process on
 * any system configuration change, not only while an Activity is on screen -
 * this is why the fix lives here rather than in [SetupActivity]. It still
 * depends on the process being alive when the switch happens; in practice
 * this app's [NotificationHookService] is a bound notification listener that
 * the system keeps resident, so the process is alive far more often than a
 * plain widget-only app's would be. A device that kills the process anyway
 * self-corrects at the next periodic refresh already in place per widget.
 */
class DotMatrixApplication : Application() {

    private var nightMode = 0

    override fun onCreate() {
        super.onCreate()
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightMode == nightMode) return
        nightMode = newNightMode

        WidgetRenderer.refreshAll(this)
        com.dotgrid.datawidget.WidgetRenderer.refreshAll(this)
        com.dotgrid.scorewidget.WidgetRenderer.refreshAll(
            this,
            com.dotgrid.scorewidget.GameRepository.cards(this)
        )
        com.dotgrid.healthwidget.WidgetRenderer.refreshAll(this)
    }
}
