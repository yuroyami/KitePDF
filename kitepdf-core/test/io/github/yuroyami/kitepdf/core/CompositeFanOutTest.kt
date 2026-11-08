package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A composite glyph that names one component ten times, level after level, stays within the
 * font's own `maxp` limits and still parsed its leaf 10^levels times (#980).
 */
class CompositeFanOutTest {

    @Test
    fun aFanOutPastTheBudgetIsAnEmptyGlyphAndEndsAtOnce() {
        val start = TimeSource.Monotonic.markNow()
        val font = TrueTypeFont.parse(fanOutFont(levels = 8))
        assertNull(font.outline(1))
        assertNull(font.outlinePath(1))
        assertTrue(start.elapsedNow().inWholeSeconds < 10, "took ${start.elapsedNow()}")
    }

    @Test
    fun aFanOutWithinTheBudgetDrawsEveryComponent() {
        val font = TrueTypeFont.parse(fanOutFont(levels = 3))
        // Three levels of ten components over a square of four points.
        assertEquals(4000, font.outline(1)!!.contours.sumOf { it.points.size })
    }

    @Test
    fun aCmapThatClaimsMoreGroupsThanItHoldsReadsAsEmpty() {
        // A 16-byte format 12 header that claims 100 million groups sized three arrays of 400 MB (#983).
        val start = TimeSource.Monotonic.markNow()
        for (groups in listOf(100_000_000, 0x7FFFFFFF)) {
            val cmap = u16(0) + u16(1) + u16(3) + u16(10) + u32(12) + u16(12) + u16(0) + u32(16) + u32(0) + u32(groups)
            val font = TrueTypeFont.parse(fanOutFont(levels = 1, extra = listOf("cmap" to cmap)))
            assertEquals(0, font.glyphIdForCodePoint('A'.code))
        }
        assertTrue(start.elapsedNow().inWholeSeconds < 10, "took ${start.elapsedNow()}")
    }

    @Test
    fun aMacRomanCmapIsReadThroughMacRomanCodes() {
        // ISO 32000-1, 9.6.6.4: with only a (1,0) subtable, WinAnsi é goes name -> Mac code 0x8E (#677).
        val ids = ByteArray(256).also { it[0x65] = 1; it[0x8E] = 2; it[0xE9] = 3 }
        val cmap = u16(0) + u16(1) + u16(1) + u16(0) + u32(12) + u16(0) + u16(262) + u16(0) + ids
        val program = fanOutFont(levels = 2, extra = listOf("cmap" to cmap))
        val refs = io.github.yuroyami.kitepdf.core.parser.IndirectResolver { null }
        fun font(flags: Int, encoding: String?) = io.github.yuroyami.kitepdf.core.font.PdfFont.from(
            io.github.yuroyami.kitepdf.core.parser.PdfDictionary(
                linkedMapOf(
                    "Type" to io.github.yuroyami.kitepdf.core.parser.PdfName("Font"),
                    "Subtype" to io.github.yuroyami.kitepdf.core.parser.PdfName("TrueType"),
                    "BaseFont" to io.github.yuroyami.kitepdf.core.parser.PdfName("Mac"),
                    "FontDescriptor" to io.github.yuroyami.kitepdf.core.parser.PdfDictionary(
                        linkedMapOf(
                            "Flags" to io.github.yuroyami.kitepdf.core.parser.PdfInt(flags.toLong()),
                            "FontFile2" to io.github.yuroyami.kitepdf.core.parser.PdfStream(io.github.yuroyami.kitepdf.core.parser.PdfDictionary(linkedMapOf()), program),
                        ),
                    ),
                ) + (if (encoding != null) mapOf("Encoding" to io.github.yuroyami.kitepdf.core.parser.PdfName(encoding)) else emptyMap()),
            ),
            refs,
        )
        val nonsymbolic = font(32, "WinAnsiEncoding")
        assertEquals(1, nonsymbolic.glyphIdForByte(0x65))
        assertEquals(2, nonsymbolic.glyphIdForByte(0xE9), "eacute is Mac 0x8E, not the Egrave of slot 0xE9")
        // A symbolic font looks the code up as it is.
        assertEquals(3, font(4, null).glyphIdForByte(0xE9))
    }

    @Test
    fun countsThatDisagreeWithTheTablesKeepTheFont() {
        // OpenType hmtx: numberOfHMetrics past numGlyphs, or numGlyphs past loca, came from
        // subsetters and dropped the whole program (#792).
        val manyMetrics = TrueTypeFont.parse(fanOutFont(levels = 1, hMetrics = 9))
        assertEquals(4, manyMetrics.outline(2)!!.contours.sumOf { it.points.size })
        val manyGlyphs = TrueTypeFont.parse(fanOutFont(levels = 1, claimedGlyphs = 5))
        assertEquals(4, manyGlyphs.outline(2)!!.contours.sumOf { it.points.size })
        assertNull(manyGlyphs.outline(6))
    }

    @Test
    fun aScaledComponentOffsetGoesThroughTheScale() {
        // Glyph 1 is the square of glyph 2 at a scale of 0.5 and an offset of (1000, 0) (#816).
        fun minX(flags: Int): Int {
            val component = u16(flags) + u16(2) + u16(1000) + u16(0) + u16(0x2000)
            val composite = u16(0xFFFF) + u16(0) + u16(0) + u16(100) + u16(100) + component
            val font = TrueTypeFont.parse(fanOutFont(levels = 0, first = composite))
            return font.outline(1)!!.contours.flatMap { it.points }.minOf { it.x }
        }
        assertEquals(500, minX(0x080b))
        assertEquals(1000, minX(0x100b))
        assertEquals(1000, minX(0x000b))
    }

    /** Glyphs 1 to [levels] are composites of ten copies of the next glyph; the last is a square. */
    private fun fanOutFont(
        levels: Int,
        extra: List<Pair<String, ByteArray>> = emptyList(),
        first: ByteArray? = null,
        hMetrics: Int = 1,
        claimedGlyphs: Int = 0,
    ): ByteArray {
        val glyphs = ArrayList<ByteArray>()
        glyphs.add(ByteArray(0))
        if (first != null) glyphs.add(first)
        for (g in 1..levels) {
            var body = u16(0xFFFF) + u16(0) + u16(0) + u16(100) + u16(100) // numberOfContours -1, bbox
            for (c in 0 until 10) {
                val flags = 0x0002 or (if (c < 9) 0x0020 else 0) // ARGS_ARE_XY_VALUES, MORE_COMPONENTS
                body += u16(flags) + u16(g + 1) + byteArrayOf(0, 0)
            }
            glyphs.add(body)
        }
        glyphs.add(
            u16(1) + u16(0) + u16(0) + u16(100) + u16(100) + u16(3) + u16(0) +
                byteArrayOf(1, 1, 1, 1) + u16(0) + u16(100) + u16(0) + u16(-100 and 0xFFFF) +
                u16(0) + u16(0) + u16(100) + u16(0),
        )
        val numGlyphs = glyphs.size
        val glyf = glyphs.fold(ByteArray(0)) { a, b -> a + b }
        var at = 0
        var loca = ByteArray(0)
        for (g in glyphs) { loca += u16(at / 2); at += g.size }
        loca += u16(at / 2)
        val head = ByteArray(54).also { it[18] = (1000 shr 8).toByte(); it[19] = 1000.toByte() }
        val maxp = u32(0x00010000) + u16(numGlyphs + claimedGlyphs) + ByteArray(26)
        val hhea = ByteArray(36).also { it[34] = (hMetrics shr 8).toByte(); it[35] = hMetrics.toByte() }
        val hmtx = u16(500) + u16(0) + ByteArray(2 * (numGlyphs - 1))
        return sfnt((extra + listOf("glyf" to glyf, "head" to head, "hhea" to hhea, "hmtx" to hmtx, "loca" to loca, "maxp" to maxp)).sortedBy { it.first })
    }

    private fun sfnt(tables: List<Pair<String, ByteArray>>): ByteArray {
        var offset = 12 + tables.size * 16
        var dir = u32(0x00010000) + u16(tables.size) + u16(0) + u16(0) + u16(0)
        var bodies = ByteArray(0)
        for ((tag, body) in tables) {
            dir += tag.encodeToByteArray() + u32(0) + u32(offset) + u32(body.size)
            val padded = (body.size + 3) and 3.inv()
            bodies += body + ByteArray(padded - body.size)
            offset += padded
        }
        return dir + bodies
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
