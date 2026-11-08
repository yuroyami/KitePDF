package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.CidWidthTable
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.font.Standard14Widths
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for indirect width-array resolution. Word and many other
 * producers store a font's `/W` (Type0/CID) or `/Widths` (simple font) as an
 * INDIRECT reference. If the loader doesn't resolve it, every glyph falls back
 * to the default width, which spreads text out and breaks Arabic cursive
 * joining (diagnosed on the corpus: `benyoub`/`math`/`69_10`).
 */
class FontWidthResolutionTest {

    private fun dict(vararg e: Pair<String, PdfObject>) = PdfDictionary(linkedMapOf(*e))

    @Test fun widths_without_first_char_start_at_code_0() {
        // Table 111 requires /FirstChar; without it, as in Poppler, /Widths starts at 0 (#657).
        val font = PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("TrueType"), "BaseFont" to PdfName("Foo"),
                "LastChar" to PdfInt(90), "Widths" to PdfArray(List(91) { PdfInt(250) }), "Encoding" to PdfName("WinAnsiEncoding"),
            ),
            IndirectResolver { null },
        )
        assertEquals(250, font.widthOf('A'.code))
    }

    @Test fun missing_width_comes_from_the_font_descriptor() {
        // ISO 32000-1, 9.8.1, Table 122: /MissingWidth is a number of the descriptor (#655).
        val descriptor = dict(
            "Type" to PdfName("FontDescriptor"), "FontName" to PdfName("Custom"), "Flags" to PdfInt(32),
            "MissingWidth" to io.github.yuroyami.kitepdf.core.parser.PdfReal(400.0),
        )
        val font = PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type1"), "BaseFont" to PdfName("Custom"),
                "FirstChar" to PdfInt(65), "LastChar" to PdfInt(66), "Widths" to PdfArray(listOf(PdfInt(600), PdfInt(600))),
                "Encoding" to PdfName("WinAnsiEncoding"), "FontDescriptor" to descriptor,
            ),
            IndirectResolver { null },
        )
        assertEquals(600, font.widthOf('A'.code))
        assertEquals(400, font.widthOf('C'.code))
    }

    @Test fun composite_resolves_indirect_W() {
        // /W [ 10 [500 600] 20 25 700 ] delivered via an indirect reference.
        val wArray = PdfArray(
            listOf(
                PdfInt(10), PdfArray(listOf(PdfInt(500), PdfInt(600))),
                PdfInt(20), PdfInt(25), PdfInt(700),
            ),
        )
        val resolver = IndirectResolver { ref -> if (ref.objectNumber == 99L) wArray else null }
        val descendant = PdfDictionary(
            linkedMapOf<String, PdfObject>("DW" to PdfInt(1000), "W" to PdfReference(99, 0)),
        )
        val table = CidWidthTable.from(descendant, resolver)

        assertEquals(500.0, table.widthOf(10))
        assertEquals(600.0, table.widthOf(11))
        assertEquals(700.0, table.widthOf(22)) // within the 20..25 range
        assertEquals(1000.0, table.widthOf(99)) // unlisted → /DW
    }

    @Test fun composite_direct_W_still_resolves() {
        val descendant = PdfDictionary(
            linkedMapOf<String, PdfObject>(
                "DW" to PdfInt(1000),
                "W" to PdfArray(listOf(PdfInt(5), PdfArray(listOf(PdfInt(333))))),
            ),
        )
        val table = CidWidthTable.from(descendant, IndirectResolver { null })
        assertEquals(333.0, table.widthOf(5))
        assertEquals(1000.0, table.widthOf(6))
    }

    @Test fun simple_font_resolves_indirect_widths() {
        // 10 widths of 700 for codes 65..74, delivered indirectly.
        val widthsArr = PdfArray((0 until 10).map { PdfInt(700) })
        val resolver = IndirectResolver { ref -> if (ref.objectNumber == 50L) widthsArr else null }
        val fontDict = PdfDictionary(
            linkedMapOf<String, PdfObject>(
                "Type" to PdfName("Font"),
                "Subtype" to PdfName("Type1"),
                "BaseFont" to PdfName("ABCDEE+CustomNotStandard14"),
                "FirstChar" to PdfInt(65),
                "LastChar" to PdfInt(74),
                "Widths" to PdfReference(50, 0),
            ),
        )
        val font = PdfFont.from(fontDict, resolver)
        // Without resolving the indirect /Widths, this would be the 500 default.
        assertEquals(700, font.widthOf(65))
        assertEquals(700, font.widthOf(74))
    }

    @Test fun a_windows_core_font_with_no_widths_borrows_standard14_metrics() {
        // /ArialMT with no /Widths measures as Helvetica, as other readers do (#120).
        val font = PdfFont.from(
            PdfDictionary(linkedMapOf<String, PdfObject>(
                "Type" to PdfName("Font"), "Subtype" to PdfName("TrueType"),
                "BaseFont" to PdfName("ArialMT"), "Encoding" to PdfName("WinAnsiEncoding"),
            )),
            IndirectResolver { null },
        )
        assertEquals(222, font.widthOf('i'.code))
        assertEquals(944, font.widthOf('W'.code))
    }

    @Test fun the_core_font_aliases_keep_weight_and_slant() {
        assertEquals("Helvetica-Bold", Standard14Widths.canonicalName("Arial,Bold"))
        assertEquals("Times-BoldItalic", Standard14Widths.canonicalName("TimesNewRomanPS-BoldItalicMT"))
        assertEquals("Courier", Standard14Widths.canonicalName("CourierNewPSMT"))
        assertEquals("ArialNarrow", Standard14Widths.canonicalName("ArialNarrow"))
    }
}
