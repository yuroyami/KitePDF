package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Type 0 font over a CID-keyed CFF program selects each glyph through the program's
 * charset, and over any other CFF program it uses the CID as the glyph id
 * (ISO 32000-1, 9.7.4.2).
 */
class CffCidCharsetTest {

    private val box = listOf(0.0, 0.0, 500.0, 700.0)
    private val halfBox = listOf(0.0, 0.0, 250.0, 350.0)
    private val half = TestCff.matrix("0.0005", "0", "0", "0.0005", "0", "0")

    private fun dict(vararg entries: Pair<String, PdfObject>) = PdfDictionary(linkedMapOf(*entries))

    /** An Identity-H Type 0 font over [program], a CIDFontType0C program. */
    private fun type0(program: ByteArray): PdfFont {
        val descriptor = dict(
            "Type" to PdfName("FontDescriptor"), "FontName" to PdfName("Test"),
            "FontFile3" to PdfStream(dict("Subtype" to PdfName("CIDFontType0C")), program),
        )
        val cidFont = dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName("CIDFontType0"), "BaseFont" to PdfName("Test"),
            "FontDescriptor" to descriptor,
        )
        return PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type0"), "BaseFont" to PdfName("Test"),
                "Encoding" to PdfName("Identity-H"), "DescendantFonts" to PdfArray(listOf(cidFont)),
            ),
            IndirectResolver { null },
        )
    }

    @Test
    fun a_cid_keyed_program_finds_each_glyph_through_its_charset() {
        // Glyph 1 is a box with CID 700. Glyph 2 is a half-size box with CID 300.
        val program = TestCff.cid(ByteArray(0), ByteArray(0), half, charset = intArrayOf(700, 300))
        val cff = CffFont.parse(program)
        assertEquals(listOf(1, 2, 0, -1), listOf(700, 300, 0, 1).map { cff.glyphIdForCid(it) })

        // CIDs 700, 300 and 1, where the charset has no CID 1, so that glyph draws nothing.
        val glyphs = type0(program).layoutBytes(TestCff.bytes(0x02, 0xBC, 0x01, 0x2C, 0x00, 0x01))
        assertEquals(listOf(box, halfBox, null), glyphs.map { g -> g.outline?.let { TestCff.bounds(it) } })
        assertEquals(listOf(1, 2, 0), glyphs.map { it.gid })
    }

    @Test
    fun two_glyphs_with_one_cid_select_the_lower_glyph() {
        val cff = CffFont.parse(TestCff.cid(ByteArray(0), ByteArray(0), half, charset = intArrayOf(5, 5)))
        assertEquals(1, cff.glyphIdForCid(5))
    }

    @Test
    fun a_program_without_cid_operators_uses_the_cid_as_the_glyph_id() {
        val cff = CffFont.parse(TestCff.simple())
        assertEquals(1, cff.glyphIdForCid(1))
        assertEquals(7, cff.glyphIdForCid(7))
    }
}
