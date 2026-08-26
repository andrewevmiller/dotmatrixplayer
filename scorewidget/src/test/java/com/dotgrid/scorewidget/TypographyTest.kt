package com.dotgrid.scorewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typography roles.
 *
 * The rule these protect is the one Nothing's guideline states outright and
 * that this project has already got wrong once: **NDot is the product-name and
 * logotype face and nothing else.** The failure is silent - point [BODY] at
 * `ndot57_aligned` and the tile still builds, still draws, and simply looks
 * subtly off-brand to anyone who knows the guideline.
 *
 * Cheap to assert, so worth asserting.
 */
class TypographyTest {

    @Test
    fun `the wordmark role is the NDot cut`() {
        assertEquals(R.font.ndot57_aligned, Typography.WORDMARK)
    }

    @Test
    fun `body text is never set in NDot`() {
        // "Dot55 should not be used as a normal font."
        assertNotEquals(R.font.ndot57_aligned, Typography.BODY)
    }

    @Test
    fun `accent text is never set in NDot`() {
        assertNotEquals(R.font.ndot57_aligned, Typography.ACCENT)
    }

    /**
     * NDot is a fixed matrix advance - the guideline sets its VA to `>0<` - so
     * no tracking constant here may be usable on it. The figures constant is
     * the one that would be tempting to reach for, since a score is the closest
     * thing on this tile to a wordmark in feel.
     */
    @Test
    fun `figures take no tracking`() {
        assertEquals(0f, Typography.Tracking.FIGURES, 0f)
    }

    @Test
    fun `labels take some tracking, but not a lot`() {
        // NType and Geist both carry tracking by size; past about 0.2em small
        // caps stop reading as a word and start reading as separate letters.
        assertTrue(Typography.Tracking.LABEL > 0f)
        assertTrue(Typography.Tracking.LABEL < 0.2f)
    }

    /**
     * Body and accent are two different faces, which is the whole point of
     * having two roles.
     *
     * This assertion was the other way round while Geist was missing and both
     * roles fell back to NType82 - a marker meant to fail once the font landed.
     * It has landed, so the marker has become the rule it was standing in for:
     * if these ever collapse to one face again, the tile has lost the
     * distinction between a data readout and a status line.
     */
    @Test
    fun `body and accent are different faces`() {
        assertNotEquals(Typography.ACCENT, Typography.BODY)
    }

    @Test
    fun `body is Geist`() {
        assertEquals(R.font.geist, Typography.BODY)
    }

    @Test
    fun `accent is NType82`() {
        assertEquals(R.font.ntype82, Typography.ACCENT)
    }
}
