package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.font.PdfFont
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the [PdfFont.layoutBytes] iterator across simple
 * and composite fonts.
 */
class FontPipelineTest {

    private val noopResolver = IndirectResolver { null }

    @Test
    fun simple_font_layouts_one_glyph_per_byte() {
        val dict = PdfDictionary(linkedMapOf(
            "Type" to PdfName("Font"),
            "Subtype" to PdfName("Type1"),
            "BaseFont" to PdfName("Helvetica"),
        ))
        val font = PdfFont.from(dict, noopResolver)
        val glyphs = font.layoutBytes("Abc".encodeToByteArray())
        assertEquals(3, glyphs.size)
        assertEquals("A", glyphs[0].text)
        assertEquals(1, glyphs[0].byteCount)
        assertEquals(0, glyphs[0].byteOffset)
        assertEquals(1, glyphs[1].byteCount)
        assertEquals(1, glyphs[1].byteOffset)
        // Standard 14 Helvetica width for 'A' is 667.
        assertEquals(667.0, glyphs[0].advanceWidth)
    }

    @Test
    fun simple_font_marks_word_space_correctly() {
        val dict = PdfDictionary(linkedMapOf(
            "Subtype" to PdfName("Type1"),
            "BaseFont" to PdfName("Helvetica"),
        ))
        val font = PdfFont.from(dict, noopResolver)
        val glyphs = font.layoutBytes("a b".encodeToByteArray())
        assertEquals(3, glyphs.size)
        assertEquals(false, glyphs[0].isWordSpace)
        assertEquals(true, glyphs[1].isWordSpace)
        assertEquals(false, glyphs[2].isWordSpace)
    }

    @Test
    fun composite_type0_with_identity_h_pairs_bytes() {
        val resolver = MapResolver()
        // Descendant CIDFontType2 with /CIDToGIDMap /Identity and /DW 500.
        val cidFont = PdfDictionary(linkedMapOf(
            "Type" to PdfName("Font"),
            "Subtype" to PdfName("CIDFontType2"),
            "BaseFont" to PdfName("ExampleCID"),
            "CIDSystemInfo" to PdfDictionary(linkedMapOf(
                "Registry" to PdfString("Adobe".encodeToByteArray()),
                "Ordering" to PdfString("Identity".encodeToByteArray()),
                "Supplement" to PdfInt(0),
            )),
            "CIDToGIDMap" to PdfName("Identity"),
            "DW" to PdfInt(500),
            "W" to PdfArray(listOf(
                PdfInt(0x4E2D),
                PdfArray(listOf(PdfInt(1234))),   // CID 0x4E2D has width 1234
            )),
        ))
        resolver.put(99, cidFont)

        val parent = PdfDictionary(linkedMapOf(
            "Type" to PdfName("Font"),
            "Subtype" to PdfName("Type0"),
            "BaseFont" to PdfName("ExampleCID"),
            "Encoding" to PdfName("Identity-H"),
            "DescendantFonts" to PdfArray(listOf(PdfReference(99, 0))),
        ))

        val font = PdfFont.from(parent, resolver)
        assertTrue(font.isComposite, "Should detect Type0 composite")

        // Bytes 00 41 00 42 4E 2D → 3 CIDs (0x41, 0x42, 0x4E2D)
        val glyphs = font.layoutBytes(byteArrayOf(0x00, 0x41, 0x00, 0x42, 0x4E, 0x2D))
        assertEquals(3, glyphs.size)
        assertEquals(2, glyphs[0].byteCount)
        assertEquals(0, glyphs[0].byteOffset)
        assertEquals(2, glyphs[1].byteCount)
        assertEquals(2, glyphs[1].byteOffset)
        assertEquals(2, glyphs[2].byteCount)
        assertEquals(4, glyphs[2].byteOffset)
        // CID 0x4E2D should pick up the /W width 1234.
        assertEquals(1234.0, glyphs[2].advanceWidth)
        // CIDs without /W entries use /DW.
        assertEquals(500.0, glyphs[0].advanceWidth)
    }

    @Test
    fun composite_widths_form2_cid_range() {
        val resolver = MapResolver()
        // /W [10 12 800] — CIDs 10, 11, 12 all have width 800.
        val cidFont = PdfDictionary(linkedMapOf(
            "Subtype" to PdfName("CIDFontType2"),
            "BaseFont" to PdfName("X"),
            "CIDToGIDMap" to PdfName("Identity"),
            "DW" to PdfInt(500),
            "W" to PdfArray(listOf(PdfInt(10), PdfInt(12), PdfInt(800))),
        ))
        resolver.put(50, cidFont)
        val parent = PdfDictionary(linkedMapOf(
            "Subtype" to PdfName("Type0"),
            "BaseFont" to PdfName("X"),
            "Encoding" to PdfName("Identity-H"),
            "DescendantFonts" to PdfArray(listOf(PdfReference(50, 0))),
        ))
        val font = PdfFont.from(parent, resolver)
        // CID 11 (= bytes 00 0B) should hit the 800 width.
        val glyphs = font.layoutBytes(byteArrayOf(0x00, 0x0B))
        assertEquals(1, glyphs.size)
        assertEquals(800.0, glyphs[0].advanceWidth)
    }

    /** Tiny IndirectResolver backed by a map — for unit-testing without a full document. */
    private class MapResolver : IndirectResolver {
        private val backing = HashMap<Long, PdfObject>()
        fun put(num: Long, obj: PdfObject) { backing[num] = obj }
        fun put(num: Int, obj: PdfObject) { backing[num.toLong()] = obj }
        override fun resolve(ref: PdfReference): PdfObject? = backing[ref.objectNumber]
    }
}
