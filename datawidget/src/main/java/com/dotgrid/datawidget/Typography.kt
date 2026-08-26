package com.dotgrid.datawidget

/**
 * Which face each kind of text on this tile is set in.
 *
 * Three roles, from `docs/BRAND_LANGUAGE.md`, and every caller names a role
 * rather than a font resource. The sibling `:scorewidget` has the same file for
 * the same reason: the roles are stable and the faces behind them are not, so a
 * change to the brand doc is a change to one file per module rather than a hunt
 * through every `TextRenderer` call.
 *
 * | role | face | what it covers here |
 * |---|---|---|
 * | [BODY] | Geist (variable, `wght` 400) | the readout and its unit, the limit line, the days-left line |
 * | [ACCENT] | NType 82 | the lines shown when there is no reading to give, and every control on the settings screen |
 * | [WORDMARK] | NDot 57 Aligned | the settings headline, and nothing else |
 *
 * ### Where the line between the first two falls
 *
 * This tile answers one question - how much data have I used - and the figures
 * that answer it are data readouts, so they take [BODY]. It takes [ACCENT] only
 * when it has no answer: without usage access the tile is no longer reporting a
 * number, it is reporting *itself*, and "TAP TO GRANT" is a status indicator
 * rather than a readout.
 *
 * That is the same rule the score tile follows for its empty states, and it is
 * why the two tiles behave alike when they have nothing to show. The colour on
 * that line already switches for this exact state - see `WidgetRenderer`, where
 * a missing grant borrows the alert colour - so the face switching with it is
 * consistent with a distinction the tile was already drawing.
 */
object Typography {

    /*
     * Geist ships as a variable font and needs no pinning here.
     *
     * `geist.ttf` is Geist-VariableFont_wght - one `wght` axis over 100..900.
     * A variable font renders at its default instance unless an axis is set,
     * and this one's default is 400, matching its OS/2 usWeightClass. That is
     * Regular, the only weight this module wants, so nothing sets a variation.
     *
     * Do not set bold on it: a Paint told to embolden a variable font
     * synthesises a fake bold rather than moving the axis, and this tile sets
     * type as small as 8sp, where that smears badly. Hierarchy here comes from
     * the type scale and the white/60%/36% ramp, as it always has.
     */

    /** Data readouts, labels and body copy. */
    val BODY: Int = R.font.geist

    /** Controls, status indicators and section labels. */
    val ACCENT: Int = R.font.ntype82

    /** Product names and the logotype. Never a general-purpose face. */
    val WORDMARK: Int = R.font.ndot57_aligned
}
