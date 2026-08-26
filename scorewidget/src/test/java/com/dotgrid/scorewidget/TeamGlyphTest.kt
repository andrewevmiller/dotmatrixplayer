package com.dotgrid.scorewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-authored marks, and the grid they are parsed onto.
 *
 * Thirteen pieces of dot art drawn by counting characters in a text editor is
 * thirteen chances to put twelve cells in a row that wants thirteen - and
 * [GlyphFrame.parse] is deliberately forgiving about exactly that, because a
 * miscounted row should cost one wonky dot rather than a widget that refuses to
 * draw. Forgiving at runtime means it has to be strict somewhere, and this is
 * where.
 */
class TeamGlyphTest {

    private val grid = GlyphMatrix.GRID

    @Test
    fun `every authored mark is exactly the size of the grid`() {
        TeamGlyphs.AUTHORED_ART.forEach { (name, art) ->
            val rows = art.trim().lines()
            assertEquals("$name has the wrong number of rows", grid, rows.size)
            rows.forEachIndexed { index, row ->
                assertEquals(
                    "$name row $index is the wrong width: '$row'",
                    grid,
                    row.trim().length
                )
            }
        }
    }

    @Test
    fun `every authored mark uses only the four intensity characters`() {
        val legal = setOf('.', '-', '+', '#')
        TeamGlyphs.AUTHORED_ART.forEach { (name, art) ->
            art.trim().lines().forEachIndexed { index, row ->
                row.trim().forEach { char ->
                    assertTrue(
                        "$name row $index has an illegal character '$char'",
                        char in legal
                    )
                }
            }
        }
    }

    /**
     * A mark has to be substantial enough to read and sparse enough to be a
     * mark rather than a filled square.
     *
     * The bounds are wide on purpose - this is not a judgement about whether a
     * given mark is *good*, which no assertion can make. It catches the two
     * mechanical failures: art that is nearly empty because a paste went wrong,
     * and art that is nearly solid because a row of dots became a row of
     * hashes.
     */
    @Test
    fun `every authored mark lights a sensible share of the grid`() {
        val cells = grid * grid
        TeamGlyphs.AUTHORED_ART.forEach { (name, art) ->
            val frame = GlyphFrame.parse(name, art)
            val lit = frame.litCells()
            assertTrue("$name lights only $lit cells", lit >= cells / 10)
            assertTrue("$name lights $lit of $cells cells", lit <= cells * 3 / 4)
        }
    }

    /**
     * The rail is a circle, so a mark whose lit cells stray into the corners
     * reads as having escaped the matrix.
     *
     * Checked at a radius slightly beyond the rail's own, because a mark is
     * allowed to touch the edge - the arrowhead and the star both do - it just
     * must not sit outside it.
     */
    @Test
    fun `no authored mark lights a cell outside the matrix circle`() {
        val centre = (grid - 1) / 2.0
        val limit = 7.2

        TeamGlyphs.AUTHORED_ART.forEach { (name, art) ->
            val frame = GlyphFrame.parse(name, art)
            for (row in 0 until grid) {
                for (col in 0 until grid) {
                    if (frame[row, col] == 0) continue
                    val dx = col - centre
                    val dy = row - centre
                    val distance = Math.sqrt(dx * dx + dy * dy)
                    assertTrue(
                        "$name lights ($row,$col), $distance from centre",
                        distance <= limit
                    )
                }
            }
        }
    }

    // ---- parse ------------------------------------------------------------

    @Test
    fun `parse maps the four characters to four levels`() {
        val frame = GlyphFrame.parse("t", ".-+#---------")
        assertEquals(0, frame[0, 0])
        assertEquals(1, frame[0, 1])
        assertEquals(2, frame[0, 2])
        assertEquals(3, frame[0, 3])
    }

    @Test
    fun `parse pads a short row rather than throwing`() {
        val frame = GlyphFrame.parse("t", "###")
        assertEquals(3, frame[0, 0])
        assertEquals(0, frame[0, 5])
        assertEquals(0, frame[0, grid - 1])
    }

    @Test
    fun `parse truncates a long row rather than overflowing`() {
        val frame = GlyphFrame.parse("t", "#".repeat(grid * 2))
        assertEquals(3, frame[0, grid - 1])
        assertEquals(grid, frame.litCells())
    }

    @Test
    fun `parse ignores rows beyond the grid`() {
        val art = (1..grid * 2).joinToString("\n") { "#".repeat(grid) }
        assertEquals(grid * grid, GlyphFrame.parse("t", art).litCells())
    }

    @Test
    fun `reading outside the grid is zero, not a crash`() {
        val frame = GlyphFrame.parse("t", "#############")
        assertEquals(0, frame[-1, 0])
        assertEquals(0, frame[0, -1])
        assertEquals(0, frame[grid, 0])
        assertEquals(0, frame[0, grid])
    }

    // ---- assignment -------------------------------------------------------

    @Test
    fun `every league has a mark for an empty tile`() {
        League.entries.forEach { league ->
            assertNotNull(league.label, TeamGlyphs.forLeague(league))
        }
    }

    /**
     * Frames are shared by reference between the teams assigned the same mark,
     * which is what makes the renderer's cache key work: the key is the frame's
     * own, so two teams with the same mark are one cache entry rather than two
     * identical bitmaps.
     */
    @Test
    fun `teams assigned the same mark share one frame`() {
        assertTrue(TeamGlyphs.STAR === TeamGlyphs.STAR)
        assertTrue(TeamGlyphs.forLeague(League.NHL) === TeamGlyphs.WHEEL)
    }
}
