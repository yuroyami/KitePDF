package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KitePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A CFF program's `FontMatrix` maps its outlines into PDF glyph space, where an em is
 * 1000 units (ISO 32000-1, 9.2.4, #140). The CFF programs are built by [TestCff].
 */
class FontMatrixTest {

    private val box = listOf(0.0, 0.0, 500.0, 700.0)
    private val halfBox = listOf(0.0, 0.0, 250.0, 350.0)
    private val half = TestCff.matrix("0.0005", "0", "0", "0.0005", "0", "0")

    @Test
    fun without_a_font_matrix_the_outline_is_unchanged() {
        val cff = CffFont.parse(TestCff.simple())
        assertSame(cff.outline(1), cff.glyphSpaceOutline(1))
        assertEquals(box, bounds(cff.glyphSpaceOutline(1)))
    }

    @Test
    fun a_matrix_of_2000_units_per_em_halves_the_glyph_space_outline() {
        val cff = CffFont.parse(TestCff.simple(half))
        assertEquals(box, bounds(cff.outline(1)), "outline() stays in the program's own units")
        assertEquals(halfBox, bounds(cff.glyphSpaceOutline(1)))
    }

    @Test
    fun a_slanted_matrix_slants_the_outline() {
        val cff = CffFont.parse(TestCff.simple(TestCff.matrix("0.001", "0", "0.0002", "0.001", "0", "0")))
        // x' = x + 0.2y, so the top edge moves right by 140.
        assertEquals(listOf(0.0, 0.0, 640.0, 700.0), bounds(cff.glyphSpaceOutline(1)))
    }

    @Test
    fun an_unusable_matrix_falls_back_to_the_default() {
        val zero = CffFont.parse(TestCff.simple(TestCff.matrix("0", "0", "0", "0", "0", "0")))
        assertEquals(box, bounds(zero.glyphSpaceOutline(1)), "a matrix with no area")
        val short = CffFont.parse(TestCff.simple(TestCff.matrix("0.0005", "0", "0", "0.0005")))
        assertEquals(box, bounds(short.glyphSpaceOutline(1)), "four numbers instead of six")
    }

    @Test
    fun a_fontdict_matrix_applies_alone_when_the_top_dict_has_none() {
        val cff = CffFont.parse(TestCff.cid(ByteArray(0), ByteArray(0), half))
        assertEquals(box, bounds(cff.glyphSpaceOutline(1)), "FontDict 0 has no matrix")
        assertEquals(halfBox, bounds(cff.glyphSpaceOutline(2)), "FontDict 1 has 1/2000")
    }

    @Test
    fun a_fontdict_matrix_combines_with_the_top_dict_matrix() {
        val unit = TestCff.matrix("1", "0", "0", "1", "0", "0")
        val thousand = TestCff.matrix("0.001", "0", "0", "0.001", "0", "0")
        val cff = CffFont.parse(TestCff.cid(unit, thousand, half))
        assertEquals(box, bounds(cff.glyphSpaceOutline(1)))
        assertEquals(halfBox, bounds(cff.glyphSpaceOutline(2)))
    }

    @Test
    fun a_fontdict_without_a_matrix_takes_the_top_dict_matrix() {
        val cff = CffFont.parse(TestCff.cid(half, ByteArray(0)))
        assertEquals(halfBox, bounds(cff.glyphSpaceOutline(1)))
    }

    @Test
    fun a_pdf_font_draws_cff_outlines_in_glyph_space() {
        val simple = PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type1"), "BaseFont" to PdfName("Test"),
                "FirstChar" to PdfInt(32), "LastChar" to PdfInt(32), "Widths" to PdfArray(listOf(PdfInt(500))),
                "FontDescriptor" to descriptor(TestCff.simple(half), "Type1C"),
            ),
            refs,
        )
        assertEquals(1000, simple.unitsPerEm)
        // Code 32 is /space in StandardEncoding, and /space is glyph 1 in the predefined charset.
        assertEquals(halfBox, bounds(simple.layoutBytes(byteArrayOf(32)).single().outline))

        val cidFont = dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName("CIDFontType0"), "BaseFont" to PdfName("Test"),
            "FontDescriptor" to descriptor(TestCff.cid(ByteArray(0), ByteArray(0), half), "CIDFontType0C"),
        )
        val composite = PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type0"), "BaseFont" to PdfName("Test"),
                "Encoding" to PdfName("Identity-H"), "DescendantFonts" to PdfArray(listOf(cidFont)),
            ),
            refs,
        )
        assertEquals(1000, composite.unitsPerEm)
        val glyphs = composite.layoutBytes(byteArrayOf(0, 1, 0, 2))
        assertEquals(listOf(box, halfBox), glyphs.map { bounds(it.outline) })
    }

    @Test
    fun a_subset_records_units_per_em_that_are_not_1000() {
        val source = CffFont.parse(TestCff.simple())
        val plain = CffFont.parse(CffSubsetter.subset(source, setOf(1)).cff)
        assertSame(plain.outline(1), plain.glyphSpaceOutline(1), "1000 units per em writes no matrix")

        val scaled = CffFont.parse(CffSubsetter.subset(source, setOf(1), unitsPerEm = 2000).cff)
        assertEquals(box, bounds(scaled.outline(1)))
        assertEquals(halfBox, bounds(scaled.glyphSpaceOutline(1)))

        // 1/2048 has no short decimal form, so the real operand must keep every digit.
        val odd = CffFont.parse(CffSubsetter.subset(source, setOf(1), unitsPerEm = 2048).cff)
        val b = bounds(odd.glyphSpaceOutline(1))
        assertEquals(500.0 * 1000 / 2048, b[2], 1e-9)
        assertEquals(700.0 * 1000 / 2048, b[3], 1e-9)
    }

    private val refs = IndirectResolver { null }

    private fun dict(vararg entries: Pair<String, PdfObject>) = PdfDictionary(linkedMapOf(*entries))

    private fun descriptor(program: ByteArray, subtype: String) = dict(
        "Type" to PdfName("FontDescriptor"), "FontName" to PdfName("Test"),
        "FontFile3" to PdfStream(dict("Subtype" to PdfName(subtype)), program),
    )

    private fun bounds(path: KitePath?) = TestCff.bounds(path)
}

/** Builds small CFF programs (Adobe Technical Note 5176) whose glyphs are a 500 by 700 box. */
internal object TestCff {

    fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** The left, bottom, right and top of every point in [path]. */
    fun bounds(path: KitePath?): List<Double> {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (s in path!!.segments) when (s) {
            is KitePath.Segment.MoveTo -> { xs += s.x; ys += s.y }
            is KitePath.Segment.LineTo -> { xs += s.x; ys += s.y }
            is KitePath.Segment.CurveTo -> { xs += listOf(s.x1, s.x2, s.x3); ys += listOf(s.y1, s.y2, s.y3) }
            is KitePath.Segment.QuadTo -> { xs += listOf(s.x1, s.x2); ys += listOf(s.y1, s.y2) }
            KitePath.Segment.Close -> {}
        }
        return listOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Type 2 charstring: `0 0 rmoveto 500 0 0 700 -500 0 rlineto endchar`. */
    private val BOX = bytes(139, 139, 21, 248, 136, 139, 139, 249, 80, 252, 136, 139, 5, 14)

    /** One `defaultWidthX 0` entry, so each Private DICT has a size. */
    private val PRIVATE = bytes(139, 20)

    /** An INDEX with four-byte offsets. */
    fun index(items: List<ByteArray>): ByteArray {
        if (items.isEmpty()) return bytes(0, 0)
        val offsets = ArrayList<Int>()
        var off = 1
        offsets += off
        for (item in items) { off += item.size; offsets += off }
        return bytes(items.size ushr 8, items.size, 4) +
            offsets.fold(ByteArray(0)) { acc, o -> acc + bytes(o ushr 24, o ushr 16, o ushr 8, o) } +
            items.fold(ByteArray(0)) { acc, item -> acc + item }
    }

    /** A five-byte DICT integer, so a DICT keeps its size whatever offsets it holds. */
    private fun int5(v: Int) = bytes(29, v ushr 24, v ushr 16, v ushr 8, v)

    /** A DICT real number, written as nibbles. */
    private fun real(text: String): ByteArray {
        val n = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() -> n += c - '0'
                c == '.' -> n += 0xA
                c == '-' -> n += 0xE
                c == 'E' && text.getOrNull(i + 1) == '-' -> { n += 0xC; i++ }
                c == 'E' -> n += 0xB
            }
            i++
        }
        n += 0xF
        if (n.size % 2 == 1) n += 0xF
        return ByteArray(1 + n.size / 2) { k -> if (k == 0) 30 else ((n[2 * k - 2] shl 4) or n[2 * k - 1]).toByte() }
    }

    /** A `FontMatrix` DICT entry. */
    fun matrix(vararg numbers: String): ByteArray = numbers.fold(ByteArray(0)) { acc, s -> acc + real(s) } + bytes(12, 7)

    /** A non-CID program: glyph 0 is `.notdef` and glyph 1 is the box. [topMatrix] goes in the Top DICT. */
    fun simple(topMatrix: ByteArray = ByteArray(0)): ByteArray {
        val name = index(listOf("Test".encodeToByteArray()))
        val empty = index(emptyList())
        val charStrings = index(listOf(bytes(14), BOX))
        fun top(cs: Int, priv: Int) = topMatrix + int5(cs) + bytes(17) + int5(PRIVATE.size) + int5(priv) + bytes(18)
        val csOffset = 4 + name.size + index(listOf(top(0, 0))).size + 2 * empty.size
        val privOffset = csOffset + charStrings.size
        return bytes(1, 0, 4, 4) + name + index(listOf(top(csOffset, privOffset))) + empty + empty + charStrings + PRIVATE
    }

    /**
     * A CID-keyed program with one FontDict for each of [fdMatrices], where an empty array
     * means no matrix. Glyph 0 is `.notdef` in FontDict 0, and glyph 1 + i is a box in FontDict i.
     * [charset] gives the CID of each glyph after `.notdef`; without it, the predefined
     * charset applies.
     */
    fun cid(topMatrix: ByteArray, vararg fdMatrices: ByteArray, charset: IntArray? = null): ByteArray {
        val name = index(listOf("Test".encodeToByteArray()))
        val empty = index(emptyList())
        val charStrings = index(listOf(bytes(14)) + fdMatrices.map { BOX })
        val charsetBytes = charset?.let { cids -> bytes(0) + cids.fold(ByteArray(0)) { acc, c -> acc + bytes(c ushr 8, c) } }
        val fdSelect = bytes(0, 0, *IntArray(fdMatrices.size) { it })
        fun fontDict(i: Int, priv: Int) = fdMatrices[i] + int5(PRIVATE.size) + int5(priv) + bytes(18)
        fun fdArray(priv: Int) = index(fdMatrices.indices.map { fontDict(it, priv) })
        fun top(cs: Int, charsetAt: Int, fdArrayAt: Int, fdSelectAt: Int) = bytes(139, 139, 139, 12, 30) + topMatrix +
            (if (charsetBytes == null) ByteArray(0) else int5(charsetAt) + bytes(15)) +
            int5(cs) + bytes(17) + int5(fdArrayAt) + bytes(12, 36) + int5(fdSelectAt) + bytes(12, 37)
        val csOffset = 4 + name.size + index(listOf(top(0, 0, 0, 0))).size + 2 * empty.size
        val charsetOffset = csOffset + charStrings.size
        val fdSelectOffset = charsetOffset + (charsetBytes?.size ?: 0)
        val fdArrayOffset = fdSelectOffset + fdSelect.size
        // Every FontDict points at the one Private DICT at the end.
        val privOffset = fdArrayOffset + fdArray(0).size
        return bytes(1, 0, 4, 4) + name + index(listOf(top(csOffset, charsetOffset, fdArrayOffset, fdSelectOffset))) +
            empty + empty + charStrings + (charsetBytes ?: ByteArray(0)) + fdSelect + fdArray(privOffset) + PRIVATE
    }
}
