package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/FontFile3` with `/Subtype /OpenType` holds a whole OpenType font (ISO 32000-1,
 * Table 126, #282). Its CFF or TrueType outlines draw like a bare program's.
 */
class OpenTypeFontFileTest {

    private val box = listOf(0.0, 0.0, 500.0, 700.0)
    private val halfBox = listOf(0.0, 0.0, 250.0, 350.0)
    private val refs = IndirectResolver { null }

    private fun dict(vararg entries: Pair<String, PdfObject>) = PdfDictionary(linkedMapOf(*entries))

    private fun descriptor(program: ByteArray) = dict(
        "Type" to PdfName("FontDescriptor"), "FontName" to PdfName("Test"), "Flags" to PdfInt(4),
        "FontFile3" to PdfStream(dict("Subtype" to PdfName("OpenType")), program),
    )

    /** An Identity-H Type 0 font over a descendant of [subtype] whose program is [program]. */
    private fun type0(subtype: String, program: ByteArray): PdfFont {
        val cidFont = dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName(subtype), "BaseFont" to PdfName("Test"),
            "FontDescriptor" to descriptor(program),
        )
        return PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type0"), "BaseFont" to PdfName("Test"),
                "Encoding" to PdfName("Identity-H"), "DescendantFonts" to PdfArray(listOf(cidFont)),
            ),
            refs,
        )
    }

    /** A simple font of [subtype] whose code [code] is 500 wide and whose program is [program]. */
    private fun simple(subtype: String, code: Int, program: ByteArray) = PdfFont.from(
        dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName(subtype), "BaseFont" to PdfName("Test"),
            "FirstChar" to PdfInt(code.toLong()), "LastChar" to PdfInt(code.toLong()), "Widths" to PdfArray(listOf(PdfInt(500))),
            "FontDescriptor" to descriptor(program),
        ),
        refs,
    )

    private fun outlines(font: PdfFont, bytes: ByteArray) = font.layoutBytes(bytes).map { TestCff.bounds(it.outline) }

    @Test
    fun an_opentype_cff_font_draws_its_outlines() {
        val program = TestSfnt.openTypeCff(TestCff.simple(), unitsPerEm = 1000)
        val composite = type0("CIDFontType0", program)
        assertTrue(composite.hasEmbeddedOutlines)
        // A CFF program without CIDFont operators uses the CID as the glyph index.
        assertEquals(listOf(box), outlines(composite, TestCff.bytes(0, 1)))
        // Code 32 is /space in StandardEncoding, and /space is glyph 1 in the predefined charset.
        assertEquals(listOf(box), outlines(simple("Type1", 32, program), TestCff.bytes(32)))
    }

    @Test
    fun an_opentype_cff_font_without_a_font_matrix_takes_units_per_em_from_head() {
        val font = type0("CIDFontType0", TestSfnt.openTypeCff(TestCff.simple(), unitsPerEm = 2000))
        assertEquals(1000, font.unitsPerEm)
        assertEquals(listOf(halfBox), outlines(font, TestCff.bytes(0, 1)))
        // A FontMatrix of its own wins over head.
        val own = TestCff.simple(TestCff.matrix("0.001", "0", "0", "0.001", "0", "0"))
        assertEquals(listOf(box), outlines(type0("CIDFontType0", TestSfnt.openTypeCff(own, unitsPerEm = 2000)), TestCff.bytes(0, 1)))
    }

    @Test
    fun an_opentype_font_with_truetype_outlines_draws_them() {
        val program = TestSfnt.trueTypeBox()
        assertEquals(listOf(box), outlines(type0("CIDFontType2", program), TestCff.bytes(0, 1)))
        // A symbolic simple TrueType font without a cmap uses the code as the glyph index.
        val font = simple("TrueType", 1, program)
        assertEquals(1000, font.unitsPerEm)
        assertEquals(listOf(box), outlines(font, TestCff.bytes(1)))
    }

    @Test
    fun the_first_bytes_decide_not_the_subtype() {
        // Labelled OpenType, but a bare CFF program.
        assertEquals(listOf(box), outlines(type0("CIDFontType0", TestCff.simple()), TestCff.bytes(0, 1)))
    }
}

/** Builds small sfnt fonts with two glyphs, `.notdef` and glyph 1. */
internal object TestSfnt {

    private fun u16(v: Int) = TestCff.bytes(v ushr 8, v)
    private fun u32(v: Int) = TestCff.bytes(v ushr 24, v ushr 16, v ushr 8, v)

    /** An sfnt with [version], [unitsPerEm] and [tables], plus `head`, `hhea`, `hmtx` and `maxp`. */
    private fun font(version: Int, unitsPerEm: Int, tables: List<Pair<String, ByteArray>>): ByteArray {
        val head = ByteArray(54).also { it[18] = (unitsPerEm ushr 8).toByte(); it[19] = unitsPerEm.toByte() }
        val maxp = u32(0x00010000) + u16(2) + ByteArray(26)
        val hhea = ByteArray(36).also { it[35] = 1 }
        val hmtx = u16(500) + u16(0) + u16(0)
        val all = (tables + listOf("head" to head, "hhea" to hhea, "hmtx" to hmtx, "maxp" to maxp)).sortedBy { it.first }
        var offset = 12 + 16 * all.size
        var directory = u32(version) + u16(all.size) + u16(0) + u16(0) + u16(0)
        var bodies = ByteArray(0)
        for ((tag, body) in all) {
            directory += tag.encodeToByteArray() + u32(0) + u32(offset) + u32(body.size)
            val padded = (body.size + 3) and 3.inv()
            bodies += body + ByteArray(padded - body.size)
            offset += padded
        }
        return directory + bodies
    }

    /** An OpenType font whose outlines are the CFF program [cff]. */
    fun openTypeCff(cff: ByteArray, unitsPerEm: Int): ByteArray = font(0x4F54544F, unitsPerEm, listOf("CFF " to cff))

    /** An OpenType font with TrueType outlines, 1000 units per em, whose glyph 1 is a 500 by 700 box. */
    fun trueTypeBox(): ByteArray {
        // One contour of four on-curve points, each coordinate a 16-bit delta.
        val glyf = u16(1) + u16(0) + u16(0) + u16(500) + u16(700) + u16(3) + u16(0) +
            TestCff.bytes(1, 1, 1, 1) +
            u16(0) + u16(500) + u16(0) + u16(-500) +
            u16(0) + u16(0) + u16(700) + u16(0)
        val loca = u16(0) + u16(0) + u16(glyf.size / 2)
        return font(0x00010000, 1000, listOf("glyf" to glyf, "loca" to loca))
    }
}
