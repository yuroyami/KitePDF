package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.GenericFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Standard-14 advances replacing the old heuristic. Values are AFM widths. */
class FontMetricsTest {

    @Test
    fun times_roman_widths_match_afm() {
        assertEquals(250, FontMetrics.advance1000(' '.code), "Times-Roman space")
        assertEquals(722, FontMetrics.advance1000('A'.code), "Times-Roman A")
        assertEquals(444, FontMetrics.advance1000('a'.code), "Times-Roman a")
    }

    @Test
    fun bold_differs_from_roman() {
        assertTrue(
            FontMetrics.advance1000('a'.code, bold = true) != FontMetrics.advance1000('a'.code),
            "bold face has its own metrics",
        )
    }

    @Test
    fun courier_is_monospaced() {
        val w = FontMetrics.advance1000('a'.code, family = GenericFont.MONO)
        assertEquals(600, w)
        assertEquals(w, FontMetrics.advance1000('M'.code, family = GenericFont.MONO), "every Courier glyph is 600")
        assertEquals(w, FontMetrics.advance1000('.'.code, family = GenericFont.MONO))
    }

    @Test
    fun sans_uses_helvetica_metrics() {
        // Helvetica 'A' is 667, distinct from Times-Roman's 722.
        assertEquals(667, FontMetrics.advance1000('A'.code, family = GenericFont.SANS))
    }

    @Test
    fun unmapped_char_falls_back() {
        // A snowman (U+2603) has no Standard-14 glyph and isn't wide; width falls back.
        assertEquals(500, FontMetrics.advance1000(0x2603), "fallback width for unmapped char")
    }

    @Test
    fun cjk_is_full_width() {
        assertTrue(FontMetrics.isWide(0x4E2D), "中 is a wide CJK ideograph")
        assertTrue(!FontMetrics.isWide('a'.code), "Latin is not wide")
        assertEquals(1000, FontMetrics.advance1000(0x4E2D), "CJK ideographs advance one em")
    }

    @Test
    fun advance_scales_with_font_size() {
        val at12 = FontMetrics.advancePt('A', 12.0)
        val at24 = FontMetrics.advancePt('A', 24.0)
        assertEquals(at12 * 2, at24, 1e-9)
        assertEquals(722 * 12.0 / 1000.0, at12, 1e-9)
    }
}
