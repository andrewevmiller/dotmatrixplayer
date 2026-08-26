package com.dotgrid.scorewidget

/**
 * Which face each kind of text on this tile is set in.
 *
 * Three roles, from `docs/BRAND_LANGUAGE.md`, and every caller names a role
 * rather than a font resource. That indirection is the point: the roles are
 * stable and the faces behind them are not, so a change to the brand doc is a
 * change to this file rather than a hunt through every `TextRenderer.render`
 * call in `WidgetRenderer`.
 *
 * | role | face | what it covers here |
 * |---|---|---|
 * | [BODY] | Geist (variable, `wght` 400) | scores, abbreviations, down-and-distance, broadcast, stat line |
 * | [ACCENT] | NType 82 | the clock, the league label, the empty-state lines |
 * | [WORDMARK] | NDot 57 Aligned | the settings headline, and nothing else |
 *
 * The split between the first two is the one worth stating, because both are
 * small caps on a dark card and they look similar until you know the rule.
 * **A score is a data readout and the clock is a time code.** The doc puts data
 * readouts on Geist and time codes on NType82, so the two halves of the same
 * line are set in different faces on purpose - the score is the number you
 * read, the clock is the thing telling you how much of the game is left to
 * change it.
 *
 * ### NDot is not in this table twice
 *
 * [TeamGlyphs] also sets text in NDot 57 Aligned, and deliberately does not go
 * through this file. What it produces is not type: the letters are rasterised
 * and resampled onto a 13 x 13 dot grid to become a team's Glyph Matrix mark,
 * and by the time the tile draws them they are a bitmap of dots. Routing that
 * through a typographic role would imply it is one.
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
     * Hierarchy on these tiles comes from the type scale and the
     * white/60%/36% ramp, and a `Paint` told to embolden a variable font
     * synthesises a fake bold rather than moving the axis - which smears the
     * letterforms at the 8-11sp most of this tile is set at.
     *
     * The italic cut next door is not shipped: nothing here is italic, and it
     * is another 170KB.
     * ---------------------------------------------------------------------
     */

    /** Body copy, data readouts and labels. */
    val BODY: Int = R.font.geist

    /** UI controls, status indicators, time codes and section labels. */
    val ACCENT: Int = R.font.ntype82

    /** Product names and the logotype. Never a general-purpose face. */
    val WORDMARK: Int = R.font.ndot57_aligned

    /**
     * Tracking, per role.
     *
     * NType82 and Geist both take letter-spacing that varies with size - the
     * guideline ships a spacing chart for it - where NDot takes none at all,
     * because it is a fixed matrix advance. That is why there is no tracking
     * value here for [WORDMARK]: the correct one is zero and a constant would
     * invite someone to change it.
     */
    object Tracking {

        /** Small caps at 8-11sp need the air or they close up into a bar. */
        const val LABEL = 0.12f

        /**
         * Figures take none.
         *
         * Tracking a number spreads its digits apart rather than making it
         * breathe, and on a score that repaints every minute the movement is
         * what the eye follows instead of the value.
         */
        const val FIGURES = 0f
    }
}
