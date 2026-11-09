package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The document font cache stays within its budget by [PdfFont.retainedBytes], so the estimate
 * counts the tables a font keeps, not a fixed share for them (#711).
 */
class FontRetainedBytesTest {

    private fun dict(vararg entries: Pair<String, PdfObject>) = PdfDictionary(linkedMapOf(*entries))

    private fun type0(cidFont: PdfDictionary, vararg extra: Pair<String, PdfObject>): PdfFont = PdfFont.from(
        dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName("Type0"), "BaseFont" to PdfName("Test"),
            "Encoding" to PdfName("Identity-H"), "DescendantFonts" to PdfArray(listOf(cidFont)), *extra,
        ),
        IndirectResolver { null },
    )

    @Test
    fun a_composite_font_counts_its_cid_map_and_its_to_unicode_cmap() {
        val cids = 65_536
        val map = ByteArray(2 * cids) { i -> if (i % 2 == 0) (i / 512).toByte() else (i / 2).toByte() }
        val entries = 20_000
        val cmap = buildString {
            append("/CIDInit /ProcSet findresource begin 12 dict begin begincmap ")
            append("1 begincodespacerange <0000> <FFFF> endcodespacerange\n")
            for (start in 0 until entries step 100) {
                append("100 beginbfchar\n")
                for (code in start until start + 100) {
                    append('<').append(code.toString(16).padStart(4, '0')).append("> <")
                    append((0x4E00 + code).toString(16).padStart(4, '0')).append(">\n")
                }
                append("endbfchar\n")
            }
            append("endcmap CMapName currentdict /CMap defineresource pop end end")
        }
        val font = type0(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("CIDFontType2"), "BaseFont" to PdfName("Test"),
                "CIDToGIDMap" to PdfStream(dict(), map),
            ),
            "ToUnicode" to PdfStream(dict(), cmap.encodeToByteArray()),
        )
        // The map is an IntArray of 65,536 entries, and each bfchar entry a hash node, a boxed
        // code and a string; measured on the JVM, the font held about 2.3 MB.
        val floor = 4L * cids + 100L * entries
        assertTrue(font.retainedBytes >= floor, "the font estimates ${font.retainedBytes} bytes, below $floor")
    }

    @Test
    fun a_cff_font_counts_its_glyph_names_besides_its_program() {
        val half = TestCff.matrix("0.0005", "0", "0", "0.0005", "0", "0")
        val program = TestCff.cid(ByteArray(0), ByteArray(0), half, charset = intArrayOf(700, 300))
        val cff = CffFont.parse(program)
        val descriptor = dict(
            "Type" to PdfName("FontDescriptor"), "FontName" to PdfName("Test"),
            "FontFile3" to PdfStream(dict("Subtype" to PdfName("CIDFontType0C")), program),
        )
        val font = type0(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("CIDFontType0"), "BaseFont" to PdfName("Test"),
                "FontDescriptor" to descriptor,
            ),
        )
        val floor = 2L * cff.programBytes + cff.glyphTableBytes
        assertTrue(cff.glyphTableBytes >= 100L * cff.numGlyphs, "a glyph counts ${cff.glyphTableBytes / cff.numGlyphs} bytes")
        assertTrue(font.retainedBytes >= floor, "the font estimates ${font.retainedBytes} bytes, below $floor")
    }
}
