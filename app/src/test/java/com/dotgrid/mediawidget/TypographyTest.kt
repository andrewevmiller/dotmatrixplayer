package com.dotgrid.mediawidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The typography roles.
 *
 * The rule these protect is the one Nothing's guideline states outright:
 * **NDot is the product-name and logotype face and nothing else.** The failure
 * is silent - point [Typography.BODY] at an NDot cut and the tile still builds,
 * still draws, and simply looks off-brand to anyone who knows the document.
 *
 * This module is the one place NDot is legitimately reachable for ordinary
 * text, via [Typography.COVERAGE_FALLBACK] - and that is exactly why it needs
 * asserting that the fallback has not quietly become the primary.
 */
class TypographyTest {

    @Test
    fun `the wordmark role is the NDot cut`() {
        assertEquals(R.font.ndot57_aligned, Typography.WORDMARK)
    }

    @Test
    fun `body is Geist and accent is NType82`() {
        assertEquals(R.font.geist, Typography.BODY)
        assertEquals(R.font.ntype82, Typography.ACCENT)
    }

    @Test
    fun `neither primary role is an NDot cut`() {
        listOf(Typography.BODY, Typography.ACCENT).forEach { role ->
            assertNotEquals(R.font.ndot57_aligned, role)
            // The JP cut is 14 MB and is still NDot. It is reachable only as a
            // per-string coverage fallback, never as a face anything is set in
            // by default.
            assertNotEquals(R.font.ndot77_jp, role)
        }
    }

    @Test
    fun `the coverage fallback is the wide NDot cut`() {
        assertEquals(R.font.ndot77_jp, Typography.COVERAGE_FALLBACK)
    }

    @Test
    fun `body and accent are different faces`() {
        assertNotEquals(Typography.ACCENT, Typography.BODY)
    }
}
