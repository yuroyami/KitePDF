package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * GPOS mark attachment: onto a base (type 4), onto the mark below (type 6,
 * which is what stacks diacritics), and onto a ligature component (type 5).
 */
class OpenTypeMarksTest {

    /** A byte sink that can patch a 16-bit offset once the target is known. */
    private class Buf {
        val bytes = ArrayList<Byte>()
        val size: Int get() = bytes.size
        fun u16(v: Int) { bytes.add(((v ushr 8) and 0xFF).toByte()); bytes.add((v and 0xFF).toByte()) }
        fun s16(v: Int) = u16(v and 0xFFFF)
        fun u32(v: Int) { u16((v ushr 16) and 0xFFFF); u16(v and 0xFFFF) }
        fun patch(at: Int, v: Int) {
            bytes[at] = ((v ushr 8) and 0xFF).toByte(); bytes[at + 1] = (v and 0xFF).toByte()
        }
        fun placeholder(): Int { val at = size; u16(0); return at }
        fun toByteArray() = bytes.toByteArray()
    }

    private fun coverage(b: Buf, gids: List<Int>) {
        b.u16(1); b.u16(gids.size); for (g in gids) b.u16(g)
    }

    private fun anchor(b: Buf, x: Int, y: Int) { b.u16(1); b.s16(x); b.s16(y) }

    /**
     * A GPOS table holding one lookup of [type] (4, 5 or 6) with one mark and
     * one target glyph. The mark anchor is (0,0); the target's is [tx],[ty],
     * so the offset a lookup reports is exactly the target anchor.
     */
    private fun gpos(type: Int, markGid: Int, targetGid: Int, tx: Int, ty: Int, components: Int = 1): ByteArray {
        val b = Buf()
        // header: 1.0, scriptList, featureList, lookupList
        b.u16(1); b.u16(0)
        b.u16(0); b.u16(0)
        val lookupListAt = b.placeholder()

        b.patch(lookupListAt, b.size)
        val lookupListBase = b.size
        b.u16(1)                      // lookupCount
        val lookupOffAt = b.placeholder()
        b.patch(lookupOffAt, b.size - lookupListBase)

        val lookupBase = b.size
        b.u16(type); b.u16(0); b.u16(1)
        val subOffAt = b.placeholder()
        b.patch(subOffAt, b.size - lookupBase)

        val subBase = b.size
        b.u16(1)                      // posFormat
        val markCovAt = b.placeholder()
        val targetCovAt = b.placeholder()
        b.u16(1)                      // markClassCount
        val markArrayAt = b.placeholder()
        val targetArrayAt = b.placeholder()

        b.patch(markCovAt, b.size - subBase); coverage(b, listOf(markGid))
        b.patch(targetCovAt, b.size - subBase); coverage(b, listOf(targetGid))

        b.patch(markArrayAt, b.size - subBase)
        val markArrayBase = b.size
        b.u16(1)                      // markCount
        b.u16(0)                      // class
        val markAnchorAt = b.placeholder()
        b.patch(markAnchorAt, b.size - markArrayBase); anchor(b, 0, 0)

        b.patch(targetArrayAt, b.size - subBase)
        val targetArrayBase = b.size
        b.u16(1)                      // baseCount / mark2Count / ligatureCount
        if (type == 5) {
            val attachAt = b.placeholder()
            b.patch(attachAt, b.size - targetArrayBase)
            val attachBase = b.size
            b.u16(components)
            val anchorAts = (0 until components).map { b.placeholder() }
            anchorAts.forEachIndexed { i, at ->
                b.patch(at, b.size - attachBase)
                // Each component's anchor is distinct, so the test can tell them apart.
                anchor(b, tx + i * 100, ty)
            }
        } else {
            val anchorAt = b.placeholder()
            b.patch(anchorAt, b.size - targetArrayBase); anchor(b, tx, ty)
        }
        return b.toByteArray()
    }

    @Test
    fun mark_to_base_still_reads() {
        val m = OpenTypeMarks.from(gpos(4, markGid = 7, targetGid = 3, tx = 250, ty = 600))
        assertNotNull(m)
        assertEquals(250.0 to 600.0, m.offset(3, 7))
        assertNull(m.offset(4, 7), "a glyph outside the coverage attaches to nothing")
    }

    @Test
    fun mark_to_mark_stacks() {
        val m = OpenTypeMarks.from(gpos(6, markGid = 8, targetGid = 7, tx = 10, ty = 900))
        assertNotNull(m)
        assertEquals(10.0 to 900.0, m.stackOffset(7, 8))
        assertNull(m.offset(7, 8), "a type 6 lookup is not a base attachment")
    }

    @Test
    fun mark_to_ligature_picks_its_component() {
        val m = OpenTypeMarks.from(gpos(5, markGid = 9, targetGid = 5, tx = 300, ty = 500, components = 3))
        assertNotNull(m)
        assertEquals(300.0 to 500.0, m.ligatureOffset(5, 9, 0))
        assertEquals(400.0 to 500.0, m.ligatureOffset(5, 9, 1))
        assertEquals(500.0 to 500.0, m.ligatureOffset(5, 9, 2))
        assertEquals(500.0 to 500.0, m.ligatureOffset(5, 9, 99), "an out-of-range component clamps")
    }

    @Test
    fun a_table_with_none_of_the_three_yields_nothing() {
        assertNull(OpenTypeMarks.from(gpos(2, markGid = 1, targetGid = 2, tx = 0, ty = 0)))
        assertNull(OpenTypeMarks.from(null))
    }
}
