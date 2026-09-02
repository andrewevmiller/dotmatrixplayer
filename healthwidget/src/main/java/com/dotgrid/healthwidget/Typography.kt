package com.dotgrid.healthwidget

/**
 * Which face each kind of text on this tile is set in.
 *
 * Three roles, from `docs/BRAND_LANGUAGE.md`, and every caller names a role
 * rather than a font resource. That indirection is the point: the roles are
 * stable and the faces behind them are not, so a change to the brand doc is a
 * change to this file rather than a hunt through every `TextRenderer` call in
 * `WidgetRenderer`.
 *
 * | role | face | what it covers here |
 * |---|---|---|
 * | [BODY] | Geist (variable, `wght` 400) | the metric readouts and their units, the row labels (STEPS, SLEEP, HEART...), and the settings screen's explanatory copy |
 * | [ACCENT] | NType 82 | the header title, the sync stamp, "NO ACCESS"/"NO SDK" status text, section labels, chips, steppers, and every settings-screen button |
 * | [WORDMARK] | NDot 57 Aligned | the settings headline, "Nothing Health", and nothing else |
 *
 * The split between the first two is the one worth stating, because both are
 * small caps on a dark card and they look similar until you know the rule.
 * The doc puts data readouts and labels on Geist and puts time codes and
 * status indicators on NType82 - a metric name beside its figure is reporting
 * a reading, the same way the score widget's stat line is, so it takes the
 * body face; the sync stamp is a clock, and "NO ACCESS" is the tile
 * describing itself rather than reporting anything, so both take the accent
 * face instead. See the data widget's own `limitLine`/`cycleLine` switch for
 * the same distinction drawn the same way.
 */
object Typography {

    /*
     * ---------------------------------------------------------------------
     * Geist ships as a variable font, and that is fine here without pinning.
     *
     * `geist.ttf` is Geist-VariableFont_wght - one `wght` axis running 100..900
     * with nine named instances. A variable font renders at its **default
     * instance** unless an axis is set, so the question that matters is what
     * that default is. Read out of the file's own fvar table: 400, matching its
     * OS/2 usWeightClass. That is Regular, which is the only weight this module
     * wants, so nothing below asks for a variation and no `wght` is set on any
     * paint.
     *
     * Do not reach for bold on it either, tempting as a weight axis makes that.
     * A `Paint` told to embolden a variable font synthesises a fake bold
     * rather than moving the axis, which smears the letterforms at the sizes
     * this tile draws at.
     *
     * The italic cut next door is not shipped: nothing here is italic.
     * ---------------------------------------------------------------------
     */

    /** Body copy, data readouts and labels. */
    val BODY: Int = R.font.geist

    /** UI controls, status indicators, time codes and section labels. */
    val ACCENT: Int = R.font.ntype82

    /** Product names and the logotype. Never a general-purpose face. */
    val WORDMARK: Int = R.font.ndot57_aligned
}
