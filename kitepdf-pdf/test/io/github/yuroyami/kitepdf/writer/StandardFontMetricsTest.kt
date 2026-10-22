package io.github.yuroyami.kitepdf.writer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandardFontMetricsTest {

    @Test
    fun helvetica_widths_match_afm_sums() {
        // Helvetica AFM: space=278, A=667. "A A" = 667+278+667 = 1612 → ×size/1000.
        val w = StandardFont.Helvetica.stringWidth("A A", fontSize = 10.0)
        assertEquals(1612 / 1000.0 * 10.0, w, 1e-9)
    }

    @Test
    fun width_scales_linearly_with_font_size() {
        val a = StandardFont.Helvetica.stringWidth("Patient Name", 10.0)
        val b = StandardFont.Helvetica.stringWidth("Patient Name", 20.0)
        assertEquals(a * 2.0, b, 1e-9)
    }

    @Test
    fun bold_is_wider_than_regular_for_same_text() {
        val regular = StandardFont.Helvetica.stringWidth("Institution", 10.0)
        val bold = StandardFont.HelveticaBold.stringWidth("Institution", 10.0)
        assertTrue(bold > regular, "bold ($bold) should be wider than regular ($regular)")
    }

    @Test
    fun empty_string_is_zero() {
        assertEquals(0.0, StandardFont.Helvetica.stringWidth("", 12.0))
    }

    @Test
    fun unknown_glyph_uses_fallback() {
        // A codepoint with no WinAnsi glyph falls back to fallbackWidth.
        val w = StandardFont.Helvetica.stringWidth("😀", fontSize = 1000.0, fallbackWidth = 500)
        // Emoji is a surrogate pair → 2 chars, each falls back to 500.
        assertEquals(2 * 500 / 1000.0 * 1000.0, w, 1e-9)
    }
}
