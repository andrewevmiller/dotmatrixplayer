package com.dotgrid.scorewidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A team mark, restated as a field of lit dots.
 *
 * Nothing's Glyph Matrix is a circular array of individually dimmable LEDs
 * behind the back glass, and everything shown on it has been reduced to that
 * grid first. This is the same reduction, drawn onto the home screen instead:
 * a mark is not a logo scaled down, it is a logo *resampled* - and the two look
 * quite different, because resampling throws away every edge finer than a cell
 * and leaves only the silhouette.
 *
 * That is the point. A full-colour club crest dropped onto one of these tiles
 * would be the only saturated thing on the home screen and would read as a
 * sticker - the same failure `AppGlyph` exists to avoid next door in :app.
 * Reduced to thirteen rows of dots, every team in every league comes out in one
 * visual language, and it is the tile's own.
 *
 * ### Why the grid is 13 and not 25
 *
 * The hardware matrix is 25 x 25. At the size a mark actually occupies here -
 * 34dp on the 4x2 card, 20dp on the 2x1 strip - a 25-grid cell is under a
 * physical pixel on the strip and just over one on the card, so the dots stop
 * being dots and the mark degrades into a grey smudge with a shape in it.
 *
 * 13 is the largest odd grid whose cells still read as separate dots at 20dp.
 * Odd matters: a mark with a vertical axis - almost all of them - needs a
 * centre column to sit on, and on an even grid it has to choose a side.
 *
 * ### Two ways in, one way out
 *
 * A frame arrives either hand-authored ([GlyphFrame.parse]) or resampled from
 * something already drawn ([GlyphFrame.sample]) - and for most teams that
 * something is their abbreviation set in Ndot 57 Aligned, which is how a league
 * of thirty clubs gets a mark each without thirty pieces of hand-drawn dot art.
 * Both produce the same intensity grid, so this renderer never learns which it
 * got.
 */
object GlyphMatrix {

    /** Cells per side. Odd, so a mark with a vertical axis has a centre column. */
    const val GRID = 13

    /** Intensity levels a cell can hold, 0..3. Mirrors what the hardware dims to. */
    const val MAX_LEVEL = 3

    /**
     * Dot diameter as a fraction of the cell pitch.
     *
     * 0.78 rather than 1.0: the gap between dots is what makes this read as a
     * matrix rather than as a low-resolution bitmap. Below about 0.7 the mark
     * starts to disintegrate at strip size - the eye stops joining neighbouring
     * dots into a stroke and sees loose confetti instead.
     */
    private const val DOT_RATIO = 0.78f

    /**
     * The dark LEDs, drawn faintly so the matrix is visible as a field rather
     * than as a scatter of lit points with nothing behind them.
     *
     * On the real hardware an off LED is invisible - black on black. Here that
     * would be wrong twice over: the tile's surface is #1B1B1B rather than
     * black, so there is nothing for it to disappear into, and the mark loses
     * the circular frame that tells you it is a matrix at all. The sibling data
     * tile makes the same call with its dial, where the unfilled dots stay on
     * as a rail.
     */
    private const val RAIL_ALPHA = 0.10f

    /**
     * The rail's radius, in cells.
     *
     * 6.6 rather than 6.5 so the four cells at the ends of the centre row and
     * column - which sit at exactly 6.0 - are comfortably inside rather than on
     * the boundary, where a rounding difference would drop one of them and
     * leave the circle visibly flat-sided on one edge.
     */
    private const val RAIL_RADIUS = 6.6f

    /**
     * Small on purpose. A mark at card size is a 100px square, ~40KB as
     * ARGB_8888; the cache is sized for what actually recurs, which is the two
     * teams currently on screen at the one size the tile is currently drawn at,
     * across the repaints that happen while a game is live.
     */
    private val cache = object : LruCache<String, Bitmap>(8) {}

    /**
     * @param sizePx the square the matrix is drawn into.
     * @param color the lit colour. Intensity below [MAX_LEVEL] is applied as
     *   alpha against it, so one colour covers every level.
     * @param rail whether to draw the unlit cells. Off for a mark laid over
     *   artwork, where the rail would read as dirt on the image.
     */
    fun render(
        frame: GlyphFrame,
        sizePx: Int,
        color: Int,
        rail: Boolean = true
    ): Bitmap {
        val size = max(GRID, sizePx)

        val key = frame.key + "|" + size + "|" + color + "|" + rail
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val pitch = size.toFloat() / GRID
        val radius = pitch * DOT_RATIO / 2f
        val centre = (GRID - 1) / 2f

        for (row in 0 until GRID) {
            for (col in 0 until GRID) {
                val level = frame[row, col]

                val alpha = if (level > 0) {
                    level.toFloat() / MAX_LEVEL
                } else {
                    if (!rail) continue
                    // Unlit, and outside the circle: not part of the matrix.
                    val dx = col - centre
                    val dy = row - centre
                    if (sqrt(dx * dx + dy * dy) > RAIL_RADIUS) continue
                    RAIL_ALPHA
                }

                paint.color = Color.argb(
                    (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
                canvas.drawCircle(
                    (col + 0.5f) * pitch,
                    (row + 0.5f) * pitch,
                    radius,
                    paint
                )
            }
        }

        cache.put(key, bitmap)
        return bitmap
    }

    /** Dropped when the last tile is removed; nothing here is worth keeping warm. */
    fun clear() = cache.evictAll()
}

/**
 * One frame of the matrix: [GlyphMatrix.GRID] squared cells, each holding an
 * intensity of 0..[GlyphMatrix.MAX_LEVEL].
 *
 * Immutable, and carries its own cache [key], because a frame is the thing the
 * renderer is keyed on and recomputing a key from 169 cells on every repaint
 * would cost more than the drawing does.
 */
class GlyphFrame private constructor(
    private val cells: IntArray,
    val key: String
) {

    operator fun get(row: Int, col: Int): Int {
        if (row !in 0 until GlyphMatrix.GRID || col !in 0 until GlyphMatrix.GRID) return 0
        return cells[row * GlyphMatrix.GRID + col]
    }

    /** How many cells are lit at all. Used by the tests, and by nothing else. */
    fun litCells(): Int = cells.count { it > 0 }

    /**
     * The frame back as the same string art [parse] reads.
     *
     * This is how a resampled logo is stored. A 13 x 13 grid is 169 characters
     * - smaller than the URL of the image it came from, and far smaller than
     * the image - so a team's mark can be cached in preferences as text and
     * never fetched or resampled again. It also means a mark that came off a
     * real logo and one that was drawn by hand are the same kind of thing on
     * disk, which is what lets [TeamGlyphs] treat them as one.
     */
    fun toArt(): String = buildString {
        for (row in 0 until GlyphMatrix.GRID) {
            for (col in 0 until GlyphMatrix.GRID) {
                append(
                    when (get(row, col)) {
                        3 -> '#'
                        2 -> '+'
                        1 -> '-'
                        else -> '.'
                    }
                )
            }
            if (row < GlyphMatrix.GRID - 1) append('\n')
        }
    }

    companion object {

        /**
         * A hand-authored mark.
         *
         * One line per row, one character per cell, in ascending intensity:
         *
         *     .  off      -  dim      +  mid      #  lit
         *
         * Four levels rather than two because a silhouette reduced to on/off at
         * this size loses everything that is not a mass - a horn, a beak, the
         * open middle of a horseshoe. A dim cell is how the mark keeps an edge
         * that is thinner than a cell.
         *
         * Short rows are padded and long ones truncated rather than throwing:
         * this is called with literals from [TeamGlyphs] at class-load time, on
         * whatever thread first asks for a mark, and a miscounted row should
         * cost a wonky dot rather than a widget that does not draw.
         */
        fun parse(key: String, art: String): GlyphFrame {
            val cells = IntArray(GlyphMatrix.GRID * GlyphMatrix.GRID)
            val rows = art.trim().lines()

            for (row in 0 until min(GlyphMatrix.GRID, rows.size)) {
                val line = rows[row].trim()
                for (col in 0 until min(GlyphMatrix.GRID, line.length)) {
                    cells[row * GlyphMatrix.GRID + col] = when (line[col]) {
                        '#' -> 3
                        '+' -> 2
                        '-' -> 1
                        else -> 0
                    }
                }
            }
            return GlyphFrame(cells, key)
        }

        /**
         * A mark resampled from something already drawn - in practice a team's
         * abbreviation set in Ndot 57 Aligned by [TeamGlyphs].
         *
         * This is the operation the hardware performs on anything sent to it,
         * and doing it here rather than hand-drawing every club is what lets
         * five leagues' worth of teams share one visual language for the cost
         * of one function.
         *
         * The source is **box-averaged**, not point-sampled. A dot typeface is
         * mostly holes, so a single sample per cell lands in a gap about as
         * often as it lands on ink, and the abbreviation comes out moth-eaten -
         * two draws of the same letters at slightly different sizes would
         * disagree about which cells are lit. Averaging the whole cell asks how
         * much ink is in it, which is a question with a stable answer.
         *
         * Alpha is the channel read, not luminance: the source is drawn white
         * on transparent, so colour carries nothing and alpha carries all of
         * the coverage.
         */
        fun sample(key: String, source: Bitmap): GlyphFrame {
            val cells = IntArray(GlyphMatrix.GRID * GlyphMatrix.GRID)

            val cellW = source.width.toFloat() / GlyphMatrix.GRID
            val cellH = source.height.toFloat() / GlyphMatrix.GRID

            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

            for (row in 0 until GlyphMatrix.GRID) {
                val top = (row * cellH).toInt()
                val bottom = min(source.height, max(top + 1, ((row + 1) * cellH).toInt()))

                for (col in 0 until GlyphMatrix.GRID) {
                    val left = (col * cellW).toInt()
                    val right = min(source.width, max(left + 1, ((col + 1) * cellW).toInt()))

                    var total = 0L
                    var count = 0
                    for (y in top until bottom) {
                        val rowStart = y * source.width
                        for (x in left until right) {
                            total += Color.alpha(pixels[rowStart + x]).toLong()
                            count++
                        }
                    }
                    if (count == 0) continue

                    val coverage = (total.toFloat() / count) / 255f
                    cells[row * GlyphMatrix.GRID + col] = quantise(coverage)
                }
            }
            return GlyphFrame(cells, key)
        }

        /**
         * Coverage to one of four levels.
         *
         * The thresholds are not evenly spaced, and they are deliberately low.
         * A stroke crossing a cell at an angle covers well under half of it,
         * so even quarters would render a legible word as a field of dim dots
         * with nothing lit - which is exactly how this looked on a phone at the
         * old 0.55 cut for full intensity. 0.40 lights the strokes properly and
         * leaves the lower two levels to the edges, which is where they earn
         * their place.
         *
         * The floor at 0.10 is a noise gate: antialiasing puts a trace of alpha
         * in cells the glyph merely passes near, and without it every mark
         * arrives inside a faint rectangular halo.
         */
        private fun quantise(coverage: Float): Int = when {
            coverage >= 0.40f -> 3
            coverage >= 0.22f -> 2
            coverage >= 0.10f -> 1
            else -> 0
        }
    }
}
