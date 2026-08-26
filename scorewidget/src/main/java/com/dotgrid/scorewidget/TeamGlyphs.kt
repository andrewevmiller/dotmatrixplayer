package com.dotgrid.scorewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.TextPaint
import android.util.LruCache
import kotlin.math.max

/**
 * Which mark a team gets.
 *
 * Two sources, and the split between them is the whole design:
 *
 * 1. **A hand-authored mark**, for the clubs whose identity survives being
 *    reduced to a silhouette - a star, a bolt, a horseshoe, a pair of horns.
 *    There are thirteen of them and they are shared: every club whose mark is
 *    fundamentally a star gets [STAR], because at 13 x 13 the differences
 *    between one star and another are smaller than a cell.
 *
 * 2. **The abbreviation, typeset and resampled onto the grid**, for everyone
 *    else. Five leagues is over a hundred and fifty clubs, and hand-drawing
 *    them would be a hundred and fifty chances to draw one badly, plus a
 *    maintenance bill every time a team relocates.
 *
 * The hand-authored marks exist for the cases where a silhouette says the team
 * faster than two letters do.
 *
 * ### Why the abbreviation is not set in NDot
 *
 * It was, and it did not work. NDot is itself a dot-matrix face, and
 * resampling a dot-matrix face onto a dot matrix is not the same idea applied
 * twice - it is two grids of different pitch beating against each other. Every
 * cell ends up partially covered by whichever of the face's own dots happen to
 * overlap it, nothing reaches full intensity, and the mark arrives as a field
 * of scattered mid-greys. On a phone the three-letter abbreviations were simply
 * unreadable.
 *
 * The source has to be **solid**, so it is set in the body face. Solid strokes
 * box-average into whole lit cells and partial edges, which is what
 * [GlyphFrame.quantise] is built to read. It also leaves this module with no
 * NDot outside the settings headline, which is where the brand guideline wanted
 * it all along - the elegant-sounding argument for using the dot font here was
 * wrong on the merits as well as on the rules.
 *
 * ### On the marks themselves
 *
 * These are **original dot compositions**, not reproductions. A club crest is a
 * trademark and tracing one onto a grid does not stop it being one. What is
 * here is the generic object the crest is *about* - a star, a crown, a wheel -
 * drawn from scratch at matrix resolution, which is the same liberty the Glyph
 * Matrix takes with everything it displays. The licence note in the README
 * covers the typefaces on the same terms.
 */
object TeamGlyphs {

    // ---------------------------------------------------------------------
    // The hand-authored marks.
    //
    // 13 rows of 13 cells. '#' lit, '+' mid, '-' dim, '.' off. The mid and dim
    // levels are not shading for its own sake - they are how a mark keeps an
    // edge that is thinner than a cell, which at this size is most of them.
    // ---------------------------------------------------------------------

    /** Five points, drawn from the centre out so the arms taper honestly. */
    private const val ART_STAR = """
......#......
......#......
.....+#+.....
.....###.....
####+###+####
-###########-
..#########..
..#########..
..###+.+###..
.####...####.
.###.....###.
.##.......##.
.............
"""

    /** Two strokes and a kink. The wide row is the kink, not a bar. */
    private const val ART_BOLT = """
.............
.......####..
......####...
.....####....
....####.....
...#########.
...+####+....
......###....
.....###.....
....###......
...###.......
..##.........
.............
"""

    /** A point, stepped rather than diagonal - a diagonal edge on a 13-grid is
        a staircase either way, so it may as well be an intentional one. */
    private const val ART_ARROWHEAD = """
.............
..#########..
..#########..
..#########..
...#######...
...#######...
....#####....
....#####....
.....###.....
.....###.....
......#......
......#......
.............
"""

    /** Open at the bottom. The opening is the mark; a closed one is a letter O. */
    private const val ART_HORSESHOE = """
.............
....#####....
...##+++##...
..##.....##..
..##.....##..
.##.......##.
.##.......##.
.##.......##.
.##.......##.
.##.......##.
.##.......##.
.##.......##.
.............
"""

    /** Swept right, and asymmetric on purpose - a symmetric wing reads as a leaf. */
    private const val ART_WING = """
.............
.............
.#...........
.###.........
.#####.......
.########....
.###########.
.#########+..
.######+.....
.####........
.##..........
.............
.............
"""

    /**
     * Horns above, muzzle below. The gap between the horns is load-bearing.
     *
     * The tips stop one cell short of the edge rather than reaching it. A horn
     * that ran to column zero would sit outside the rail's circle entirely, and
     * a lit dot beyond the matrix reads as a mark that has come apart rather
     * than as a wide one.
     */
    private const val ART_HORNS = """
.............
.##.......##.
.###.....###.
..###...###..
..####.####..
...#######...
...#######...
...##...##...
...##...##...
....#####....
.....###.....
.............
.............
"""

    /** A ball with its two stitch arcs. Used as the MLB mark, not a club's. */
    private const val ART_BASEBALL = """
....#####....
..##+++++##..
.#++.....++#.
.#+.......+#.
#+.........+#
#+.........+#
#+.........+#
#+.........+#
.#+.......+#.
.#++.....++#.
..##+++++##..
....#####....
.............
"""

    /** A spoked wheel: rim, hub, and four spokes on the axes. */
    private const val ART_WHEEL = """
.............
....#####....
..##..#..##..
.#...###...#.
.#..#####..#.
##...###...##
##.#######.##
##...###...##
.#..#####..#.
.#...###...#.
..##..#..##..
....#####....
.............
"""

    /** Three points and a band. */
    private const val ART_CROWN = """
.............
.#.........#.
.#...###...#.
.##..###..##.
.##.#####.##.
.###########.
.###########.
.###########.
.##+#####+##.
.###########.
.............
.............
.............
"""

    /** A flame needs the notch at the bottom, or it is a leaf. */
    private const val ART_FLAME = """
.............
......#......
.....###.....
....#####....
...###+###...
..###+.+###..
..###...###..
.####...####.
.####+.+####.
..#########..
...#######...
....+###+....
.............
"""

    /** Ring, stock, crossbar, flukes. */
    private const val ART_ANCHOR = """
.............
.....###.....
.....#.#.....
.....###.....
..#########..
......#......
......#......
.#....#....#.
.#....#....#.
.##...#...##.
..#########..
....#####....
.............
"""

    /**
     * Three toes and a pad.
     *
     * Three rather than four: four toes on a 13-cell row leaves each of them
     * two cells wide with a one-cell gap, and at strip size that is a dashed
     * line rather than a paw. Three toes at three cells each is the widest
     * arrangement that still reads as toes.
     */
    private const val ART_PAW = """
.............
.###.###.###.
.###.###.###.
.###.###.###.
.............
...#######...
..#########..
.###########.
.###########.
..#########..
...#######...
.............
.............
"""

    /** Rim and net. Used as the NBA mark. */
    private const val ART_HOOP = """
.............
..#########..
.###########.
..#########..
...#.#.#.#...
...#.#.#.#...
...##.#.##...
....#.#.#....
....##.##....
.....#.#.....
.....###.....
.............
.............
"""

    /**
     * The raw art, by name.
     *
     * Exists for `TeamGlyphTest`, which checks that none of these was
     * miscounted while being drawn by hand. [GlyphFrame.parse] deliberately
     * pads short rows and truncates long ones rather than throwing - a wonky
     * dot is a better failure than a widget that will not draw - which means a
     * typo in the art below is silent at runtime and has to be caught here.
     */
    internal val AUTHORED_ART: Map<String, String> by lazy {
        mapOf(
            "star" to ART_STAR,
            "bolt" to ART_BOLT,
            "arrow" to ART_ARROWHEAD,
            "shoe" to ART_HORSESHOE,
            "wing" to ART_WING,
            "horns" to ART_HORNS,
            "ball" to ART_BASEBALL,
            "wheel" to ART_WHEEL,
            "crown" to ART_CROWN,
            "flame" to ART_FLAME,
            "anchor" to ART_ANCHOR,
            "paw" to ART_PAW,
            "hoop" to ART_HOOP
        )
    }

    val STAR: GlyphFrame by lazy { GlyphFrame.parse("star", ART_STAR) }
    val BOLT: GlyphFrame by lazy { GlyphFrame.parse("bolt", ART_BOLT) }
    val ARROWHEAD: GlyphFrame by lazy { GlyphFrame.parse("arrow", ART_ARROWHEAD) }
    val HORSESHOE: GlyphFrame by lazy { GlyphFrame.parse("shoe", ART_HORSESHOE) }
    val WING: GlyphFrame by lazy { GlyphFrame.parse("wing", ART_WING) }
    val HORNS: GlyphFrame by lazy { GlyphFrame.parse("horns", ART_HORNS) }
    val BASEBALL: GlyphFrame by lazy { GlyphFrame.parse("ball", ART_BASEBALL) }
    val WHEEL: GlyphFrame by lazy { GlyphFrame.parse("wheel", ART_WHEEL) }
    val CROWN: GlyphFrame by lazy { GlyphFrame.parse("crown", ART_CROWN) }
    val FLAME: GlyphFrame by lazy { GlyphFrame.parse("flame", ART_FLAME) }
    val ANCHOR: GlyphFrame by lazy { GlyphFrame.parse("anchor", ART_ANCHOR) }
    val PAW: GlyphFrame by lazy { GlyphFrame.parse("paw", ART_PAW) }
    val HOOP: GlyphFrame by lazy { GlyphFrame.parse("hoop", ART_HOOP) }

    /**
     * Club to mark, keyed `LEAGUE/ABBREV` so two leagues can both have a team
     * abbreviated `LA` without colliding.
     *
     * Everything absent from this table falls through to the abbreviation,
     * which is the intended path for most of the league rather than a gap to be
     * filled in later. A mark earns a row here only when the silhouette reads
     * faster than the letters would - which is why there is no entry for, say,
     * the Jets or the Nets: a plane and a net at 13 cells are both a blob.
     */
    private val ASSIGNED: Map<String, GlyphFrame> by lazy {
        mapOf(
            // NFL
            "NFL/DAL" to STAR,
            "NFL/LAC" to BOLT,
            "NFL/KC" to ARROWHEAD,
            "NFL/IND" to HORSESHOE,
            "NFL/PHI" to WING,
            "NFL/SEA" to WING,
            "NFL/BAL" to WING,
            "NFL/ATL" to WING,
            "NFL/HOU" to HORNS,
            "NFL/LAR" to HORNS,
            "NFL/CIN" to PAW,
            "NFL/CAR" to PAW,
            "NFL/DET" to PAW,
            "NFL/JAX" to PAW,
            "NFL/TB" to ANCHOR,
            "NFL/NE" to STAR,
            "NFL/DEN" to FLAME,

            // NBA
            "NBA/DAL" to STAR,
            "NBA/CHI" to HORNS,
            "NBA/MIA" to FLAME,
            "NBA/SAC" to CROWN,
            "NBA/MIL" to HORNS,
            "NBA/DET" to WHEEL,
            "NBA/HOU" to STAR,

            // MLB
            "MLB/HOU" to STAR,
            "MLB/KC" to CROWN,
            "MLB/SEA" to ANCHOR,
            "MLB/DET" to PAW,
            "MLB/BAL" to WING,
            "MLB/STL" to WING,
            "MLB/TOR" to WING,
            "MLB/LAA" to CROWN,

            // NHL
            "NHL/TBL" to BOLT,
            "NHL/DET" to WHEEL,
            "NHL/LAK" to CROWN,
            "NHL/CGY" to FLAME,
            "NHL/PHI" to WING,
            "NHL/ANA" to ANCHOR,
            "NHL/DAL" to STAR,
            "NHL/VGK" to CROWN,

            // NCAAF
            "NCAAF/TEX" to HORNS,
            "NCAAF/CLEM" to PAW,
            "NCAAF/LSU" to PAW,
            "NCAAF/MIZ" to STAR,
            "NCAAF/AUB" to WING
        )
    }

    /** The mark a whole league gets, for an empty state with no club to show. */
    fun forLeague(league: League): GlyphFrame = when (league) {
        League.MLB -> BASEBALL
        League.NBA -> HOOP
        League.NHL -> WHEEL
        League.NFL, League.NCAAF -> ARROWHEAD
    }

    /**
     * Sampled abbreviations, held because sampling walks every pixel of a
     * 104px square and the same two teams are redrawn on every repaint of a
     * live game - roughly once a minute for as long as the game lasts.
     *
     * Sized for a 4x2 card showing three cards in the carousel, both teams
     * each, plus the two currently off-screen either side.
     */
    private val sampled = object : LruCache<String, GlyphFrame>(12) {}

    /**
     * The mark for a team: the assigned one if it has it, otherwise its
     * abbreviation resampled onto the grid.
     *
     * @param context needed only for the typeface, and only on the fallback
     *   path - an assigned mark never touches it.
     */
    fun forTeam(context: Context, league: League, abbrev: String): GlyphFrame {
        val clean = abbrev.trim().uppercase()
        ASSIGNED[league.code + "/" + clean]?.let { return it }
        if (clean.isEmpty()) return forLeague(league)

        val key = "abbrev/" + clean
        sampled.get(key)?.let { return it }

        val frame = GlyphFrame.sample(key, typeset(context, clean))
        sampled.put(key, frame)
        return frame
    }

    /**
     * The supersampling factor between the typeset bitmap and the grid.
     *
     * 8 gives 64 source pixels per cell, which is enough for the box average in
     * [GlyphFrame.sample] to be stable: at 4 the counts are coarse enough that
     * a half-pixel shift in where the text lands moves cells across a
     * quantisation threshold, and the same abbreviation drawn twice comes out
     * as two different marks.
     */
    private const val SUPERSAMPLE = 8

    /**
     * How much of the grid the letters may occupy, as a fraction.
     *
     * 0.86 across and 0.80 down. The width is the tighter of the two because
     * the rail is a circle: letters set to the full width would put their outer
     * stems in cells outside it, and the mark would read as letters that had
     * escaped the matrix rather than letters inside it.
     *
     * The height used to be 0.62 and that was the single biggest thing wrong
     * with this. Cap height is the whole of a letter, and at 0.62 of thirteen
     * cells a capital is eight cells tall before the width fitting below shrank
     * it further - which for a three-letter abbreviation took it to four or
     * five. No letterform resolves in four rows of dots. Ten is enough for the
     * crossbar of an E to sit clear of both terminals.
     */
    private const val FILL_X = 0.86f
    private const val FILL_Y = 0.80f

    /**
     * How far the letters may be condensed horizontally before the fitting
     * gives up.
     *
     * Three letters across thirteen cells is four cells each, and the only way
     * to keep them ten cells tall at that width is to narrow the letterforms
     * rather than scale them down. `textScaleX` does exactly that, and it is
     * what a dot-matrix display does when asked to fit a long word: the glyphs
     * get narrow, not small.
     *
     * 0.55 is the floor. Below it the counters close up and an O becomes a
     * filled block, at which point a shorter abbreviation would say more.
     */
    private const val MIN_CONDENSE = 0.55f

    /**
     * The abbreviation, drawn white on transparent at supersampled size.
     *
     * White because [GlyphFrame.sample] reads the alpha channel - the colour
     * here is thrown away, and the tile's own colour is applied when the matrix
     * is rendered. Drawing it in the final colour would bake a decision into a
     * cache entry that is shared across every state the mark can be shown in.
     */
    private fun typeset(context: Context, text: String): Bitmap {
        val size = GlyphMatrix.GRID * SUPERSAMPLE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            // NDot 57 Aligned, named directly rather than through a typographic
            // role: what comes out of here is not type. The letters are
            // resampled onto a 13 x 13 grid to become the mark, and NDot is the
            // industrial dot-matrix face that grid is quantising toward.
            // See docs/BRAND_LANGUAGE.md.
            typeface = TextRenderer.face(context, R.font.ndot57_aligned)
            color = Color.WHITE
            // The source is about to be reduced to thirteen cells, so tracking
            // at this stage is sub-cell noise. Zero keeps the same abbreviation
            // quantising the same way on every draw.
            letterSpacing = 0f
        }

        /*
         * Size for height, then condense for width.
         *
         * The obvious fitting loop - shrink the point size until the string
         * fits across - is wrong here, and looked it on a phone: it trades away
         * the height, which is the dimension a letterform needs to stay
         * legible, in order to buy width, which at thirteen cells it can get
         * more cheaply by narrowing. So the size is set once from the height
         * budget and never reduced; `textScaleX` does the fitting.
         */
        paint.textSize = size * FILL_Y
        val maxWidth = size * FILL_X
        val natural = paint.measureText(text)
        if (natural > maxWidth) {
            paint.textScaleX = max(MIN_CONDENSE, maxWidth / natural)
        }

        val metrics = paint.fontMetrics
        // Centre on the cap box rather than the line box: the line box carries
        // descender room that no capital uses, and centring on it leaves every
        // all-caps mark sitting visibly high in the matrix.
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f

        canvas.drawText(text, (size - paint.measureText(text)) / 2f, baseline, paint)
        return bitmap
    }

    /** Dropped when the last tile is removed. */
    fun clear() = sampled.evictAll()
}
