package com.dotgrid.datawidget

/**
 * The same tile, registered a second time under `data_widget_pillar_info.xml`
 * so the launcher's widget picker offers a 1 x 2 size directly instead of the
 * pillar only being reachable by resizing the 2 x 2 [DataWidgetProvider] tile
 * by hand.
 *
 * No overrides: [WidgetRenderer.build] tells the two providers' instances
 * apart by which one owns the appWidgetId and always paints this one as the
 * pillar, so every lifecycle callback - refresh, resize, boot, enable/disable
 * - is identical to the gauge's and lives once, on the parent.
 */
class PillarWidgetProvider : DataWidgetProvider()
