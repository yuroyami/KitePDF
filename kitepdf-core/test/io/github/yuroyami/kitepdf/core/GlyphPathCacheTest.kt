package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A font keeps one copy of each glyph it has drawn: the path, not the boxed
 * points it was built from as well. Kept together they doubled what every
 * drawn glyph retained, about 4 KB against a 200 byte glyf record (#228).
 */
class GlyphPathCacheTest {

    @Test
    fun a_drawn_glyph_keeps_its_path_and_not_its_points() {
        val font = TrueTypeFont.parse(squareFont())
        val path = assertNotNull(font.outlinePath(1))
        assertTrue(path.segments.size >= 5, "a real square: move, lines, close")
        assertSame(path, font.outlinePath(1), "the path is built once")
        assertEquals(0, font.cachedOutlines, "the point form is not kept behind the path")
    }

    @Test
    fun asking_for_the_points_still_caches_them() {
        val font = TrueTypeFont.parse(squareFont())
        assertNotNull(font.outline(1))
        assertEquals(1, font.cachedOutlines)
        assertNotNull(font.outlinePath(1))
        assertEquals(1, font.cachedOutlines, "a path built from cached points adds nothing")
    }

    /** Glyph 1 is a 100-unit square: one contour, four on-curve points. */
    private fun squareFont(): ByteArray {
        val glyf = s16(1) + s16(0) + s16(0) + s16(100) + s16(100) + u16(3) + u16(0) +
            byteArrayOf(1, 1, 1, 1) +
            s16(0) + s16(100) + s16(0) + s16(-100) +
            s16(0) + s16(0) + s16(100) + s16(0)
        val head = ByteArray(54).also { put16(it, 18, 1000) }
        val maxp = u32(0x00010000) + u16(2) + ByteArray(26)
        val hhea = ByteArray(36).also { put16(it, 34, 1) }
        val hmtx = u16(500) + u16(0) + u16(0)
        val loca = u16(0) + u16(0) + u16(glyf.size / 2)
        val tables = listOf("glyf" to glyf, "head" to head, "hhea" to hhea, "hmtx" to hmtx, "loca" to loca, "maxp" to maxp)
        var offset = 12 + tables.size * 16
        val out = ArrayList<Byte>()
        val bodies = ArrayList<Byte>()
        out.addAll((u32(0x00010000) + u16(tables.size) + u16(0) + u16(0) + u16(0)).toList())
        for ((tag, body) in tables) {
            out.addAll(tag.encodeToByteArray().toList())
            out.addAll(u32(0).toList())
            out.addAll(u32(offset.toLong()).toList())
            out.addAll(u32(body.size.toLong()).toList())
            val padded = (body.size + 3) and 3.inv()
            bodies.addAll(body.toList())
            repeat(padded - body.size) { bodies.add(0) }
            offset += padded
        }
        out.addAll(bodies)
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun s16(v: Int) = u16(v and 0xFFFF)
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun put16(arr: ByteArray, at: Int, v: Int) { arr[at] = (v shr 8).toByte(); arr[at + 1] = v.toByte() }
}
