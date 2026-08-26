package com.dotgrid.datawidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The typography roles.
 *
 * The rule these protect is the one Nothing's guideline states outright and
 * that this module has already got wrong once: **NDot is the product-name and
 * logotype face and nothing else.** This tile used to run its entire dial and
 * settings screen in NDot with manual tracking on top, which is the misuse the
 * guideline names directly.
 *
 * The failure is silent - point [Typography.BODY] at `ndot57_aligned` and the
 * tile still builds, still draws, and simply looks off-brand to anyone who
 * knows the document. Cheap to assert, so worth asserting.
 */
class TypographyTest {

    @Test
    fun `the wordmark role is the NDot cut`() {
        assertEquals(R.font.ndot57_aligned, Typography.WORDMARK)
    }

    @Test
    fun `the readout is never set in NDot`() {
        // "Dot55 should not be used as a normal font." The tabular digits are
        // tempting for a figure that changes as data is used; the answer is
        // still no.
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
     * lost the distinction between reporting a number and reporting that it has
     * no number to report.
     */
    @Test
    fun `body and accent are different faces`() {
        assertNotEquals(Typography.ACCENT, Typography.BODY)
    }
}
