package com.dotgrid.healthwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The typography roles.
 *
 * The rule these protect is the one Nothing's guideline states outright:
 * **NDot is the product-name and logotype face and nothing else.** This
 * widget's step/sleep/heart-rate readouts used to default to NType82 - the
 * general-purpose face, but not the data-readout one the doc actually names -
 * which is the same kind of quiet drift the sibling widgets have each caught
 * once already.
 *
 * The failure is silent - point [Typography.BODY] at `ndot57_aligned`, or
 * point it at the wrong non-NDot face, and the tile still builds, still
 * draws, and simply looks off-brand to anyone who knows the document. Cheap
 * to assert, so worth asserting.
 */
class TypographyTest {

    @Test
    fun `the wordmark role is the NDot cut`() {
        assertEquals(R.font.ndot57_aligned, Typography.WORDMARK)
    }

    @Test
    fun `the readout is never set in NDot`() {
        // "Dot55 should not be used as a normal font." A changing figure is
        // exactly what makes a tabular face tempting; the answer is still no.
        assertNotEquals(R.font.ndot57_aligned, Typography.BODY)
    }

    @Test
    fun `accent text is never set in NDot`() {
        assertNotEquals(R.font.ndot57_aligned, Typography.ACCENT)
    }

    @Test
    fun `body is Geist and accent is NType82`() {
        assertEquals(R.font.geist, Typography.BODY)
        assertEquals(R.font.ntype82, Typography.ACCENT)
    }

    /**
     * Two roles mean two faces. If these ever collapse into one, the tile has
     * lost the distinction between reporting a reading and reporting that it
     * has none to give.
     */
    @Test
    fun `body and accent are different faces`() {
        assertNotEquals(Typography.ACCENT, Typography.BODY)
    }
}
