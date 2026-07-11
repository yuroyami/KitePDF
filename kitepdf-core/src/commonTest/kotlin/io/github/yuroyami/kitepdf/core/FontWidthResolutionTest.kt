package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.CidWidthTable
import io.github.yuroyami.kitepdf.core.font.PdfFont
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
 * to the default width — which spreads text out and breaks Arabic cursive
 * joining (diagnosed on the corpus: `benyoub`/`math`/`69_10`).
 */
class FontWidthResolutionTest {

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
}
