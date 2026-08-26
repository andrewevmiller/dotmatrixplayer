package com.dotgrid.mediawidget

/**
 * Which face each kind of text on this tile is set in.
 *
 * Three roles plus a coverage fallback, from `docs/BRAND_LANGUAGE.md`, and
 * every caller names a role rather than a font resource. Both sibling modules
 * have the same file for the same reason: the roles are stable and the faces
 * behind them are not.
 *
 * | role | face | what it covers here |
 * |---|---|---|
 * | [BODY] | Geist (variable, `wght` 400) | the track title and the artist |
 * | [ACCENT] | NType 82 | the status label, and the elapsed / total time codes |
 * | [WORDMARK] | NDot 57 Aligned | the setup screen headline, and nothing else |
 * | [COVERAGE_FALLBACK] | NDot 77 JP Extended | any string [BODY] cannot render |
 *
 * ### The fallback is about coverage, not style
 *
 * This is the only one of the three tiles that draws strings it did not author:
 * a track title arrives from whichever app is playing, in whatever script that
 * app used. [BODY] cannot be assumed to cover it, and a missing glyph is a row
 * of tofu boxes where the song title should be.
 *
 * So [TextRenderer.render] takes a fallback and swaps to it **per string**,
 * only for the strings that need it. That keeps the guideline's general-purpose
 * face on everything it can actually render, and reaches for the 14 MB
 * wide-coverage NDot cut only where the alternative is showing nothing legible
 * at all. It is not an aesthetic preference for NDot in those cases - it is the
 * least-bad option, chosen one string at a time.
 *
 * Moving [BODY] from NType 82 to Geist made that fallback fire less often.
 * Measured off the two fonts' own cmap tables: NType 82 carries about 217
 * codepoints and is Latin-only, where Geist carries about 728 and includes
 * Cyrillic. A Russian track title used to fall through to the JP cut and now
 * does not.
 */
object Typography {

    /*
     * Geist ships as a variable font and needs no pinning here.
     *
     * `geist.ttf` is Geist-VariableFont_wght - one `wght` axis over 100..900,
     * default instance 400, matching its OS/2 usWeightClass. That is Regular,
     * the only weight this module wants, so nothing sets a variation.
     *
     * Do not set bold on it. A Paint told to embolden a variable font
     * synthesises a fake bold rather than moving the axis, and the artist line
     * here is set at 11sp, where that smears. Hierarchy comes from the type
     * scale and the white/60%/36% ramp.
     */

    /** Body copy - here, the two lines naming what is playing. */
    val BODY: Int = R.font.geist

    /** Status indicators and time codes. */
    val ACCENT: Int = R.font.ntype82

    /** Product names and the logotype. Never a general-purpose face. */
    val WORDMARK: Int = R.font.ndot57_aligned

    /**
     * Used per string, and only when [BODY] has no glyph for something in it.
     *
     * Never pass this as a primary face. It is 14 MB and it is NDot, which the
     * guideline scopes to product names - reaching for it wholesale would break
     * the rule for every string in order to serve the few that need it.
     */
    val COVERAGE_FALLBACK: Int = R.font.ndot77_jp
}
