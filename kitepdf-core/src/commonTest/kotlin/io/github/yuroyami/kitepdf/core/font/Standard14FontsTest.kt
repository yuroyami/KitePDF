package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The bundled programs that draw a standard 14 font that a file does not embed (#298). */
class Standard14FontsTest {

    private val latin = listOf(
        "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
        "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
        "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
    )

    private fun font(baseFont: String, subtype: String = "Type1"): PdfFont = PdfFont.from(
        PdfDictionary(linkedMapOf<String, PdfObject>("Subtype" to PdfName(subtype), "BaseFont" to PdfName(baseFont))),
        IndirectResolver { null },
    )

    @Test
    fun every_standard_font_has_a_program_with_its_glyphs() {
        for (name in latin) {
            val cff = assertNotNull(Standard14Fonts.program(name), name)
            for (glyph in listOf("A", "g", "zero", "Euro", "eacute", "quotedblleft")) {
                assertTrue(cff.glyphIdForName(glyph) > 0, "$name has no $glyph")
            }
        }
        assertTrue(Standard14Fonts.program("Symbol")!!.glyphIdForName("alpha") > 0)
        assertTrue(Standard14Fonts.program("ZapfDingbats")!!.glyphIdForName("a1") > 0)
    }

    @Test
    fun a_program_is_parsed_once_and_shared() {
        assertSame(Standard14Fonts.program("Helvetica"), Standard14Fonts.program("ArialMT"))
    }

    @Test
    fun names_that_stand_for_a_standard_font_find_it() {
        assertEquals("Helvetica-Bold", Standard14Widths.canonicalName("Helvetica,Bold"))
        assertEquals("Helvetica-BoldOblique", Standard14Widths.canonicalName("Helvetica,BoldItalic"))
        assertEquals("Times-Italic", Standard14Widths.canonicalName("Times,Italic"))
        assertEquals("Courier-Bold", Standard14Widths.canonicalName("Courier,Bold"))
        assertEquals("Symbol", Standard14Widths.canonicalName("SymbolMT"))
        assertEquals("Symbol", Standard14Widths.canonicalName("Symbol,Bold"))
        assertEquals("Helvetica", Standard14Widths.canonicalName("ABCDEF+Helvetica"))
        assertEquals("HelveticaNeue", Standard14Widths.canonicalName("HelveticaNeue"))
        assertNull(Standard14Fonts.program("Calibri"))
    }

    @Test
    fun a_standard_font_that_is_not_embedded_draws_from_outlines() {
        val helvetica = font("Helvetica")
        assertTrue(helvetica.hasOutlines)
        assertFalse(helvetica.hasEmbeddedOutlines)
        assertEquals(1000, helvetica.unitsPerEm)
        val a = assertNotNull(helvetica.outlineForByte('A'.code))
        assertFalse(a.isEmpty())
        // The outline of a TrueType font that names Arial comes from the same program.
        assertTrue(font("Arial,Bold", subtype = "TrueType").hasOutlines)
    }

    @Test
    fun other_fonts_keep_the_host_typeface() {
        assertFalse(font("Calibri", subtype = "TrueType").hasOutlines)
        assertFalse(font("Helvetica", subtype = "Type3").hasOutlines)
    }
}
