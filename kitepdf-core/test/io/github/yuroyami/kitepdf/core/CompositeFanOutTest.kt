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

    /** Glyphs 1 to [levels] are composites of ten copies of the next glyph; the last is a square. */
    private fun fanOutFont(levels: Int, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val glyphs = ArrayList<ByteArray>()
        glyphs.add(ByteArray(0))
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
        val maxp = u32(0x00010000) + u16(numGlyphs) + ByteArray(26)
        val hhea = ByteArray(36).also { it[35] = 1 }
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
