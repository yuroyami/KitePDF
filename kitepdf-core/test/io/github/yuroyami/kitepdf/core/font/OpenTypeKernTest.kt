package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** GPOS pair kerning, read directly and from behind an Extension lookup (#784). */
class OpenTypeKernTest {

    private class Bytes {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        fun u32(v: Long) { u16((v shr 16).toInt()); u16(v.toInt()) }
        val size get() = out.size
    }

    /** A pair adjustment, format 1: glyph 1 then glyph 2 kerns by [value] units. */
    private fun pairPos(value: Int): Bytes = Bytes().apply {
        u16(1)          // posFormat
        u16(12)         // coverage, after the 12-byte header with its one pair set offset
        u16(0x0004)     // valueFormat1: XAdvance
        u16(0)          // valueFormat2
        u16(1)          // pairSetCount
        u16(18)         // pairSet offset
        // Coverage format 1 at 12: one glyph, 1.
        u16(1); u16(1); u16(1)
        // PairSet at 18: one record, the second glyph 2, XAdvance [value].
        u16(1); u16(2); u16(value and 0xFFFF)
    }

    /** A GPOS whose only feature, kern, uses one lookup holding [subtable] as a lookup of [type]. */
    private fun gpos(type: Int, subtable: Bytes): ByteArray {
        val b = Bytes()
        b.u16(1); b.u16(0)
        b.u16(10)          // ScriptList
        b.u16(12)          // FeatureList
        b.u16(26)          // LookupList
        b.u16(0)           // ScriptList at 10: no scripts
        // FeatureList at 12: kern, at offset 8 from it.
        b.u16(1); b.u32(0x6B65726EL); b.u16(8)
        b.u16(0); b.u16(1); b.u16(0)      // Feature: no params, lookup 0
        // LookupList at 26: one lookup at offset 4.
        b.u16(1); b.u16(4)
        // Lookup at 30: [type], no flags, one subtable at offset 8.
        b.u16(type); b.u16(0); b.u16(1); b.u16(8)
        b.out += subtable.out
        return b.out.toByteArray()
    }

    @Test
    fun pairKerningReadsFromALookupOfType2() {
        val kern = assertNotNull(OpenTypeKern.from(null, gpos(2, pairPos(-80))))
        assertEquals(-80, kern.between(1, 2))
    }

    @Test
    fun pairKerningReadsFromBehindAnExtensionLookup() {
        // OpenType 1.9, GPOS Lookup Type 9: format 1, the real type 2, and a 32-bit offset from the extension subtable.
        val ext = Bytes().apply { u16(1); u16(2); u32(8) }
        ext.out += pairPos(-80).out
        val kern = assertNotNull(OpenTypeKern.from(null, gpos(9, ext)), "a type 9 lookup wrapping type 2")
        assertEquals(-80, kern.between(1, 2))
    }
}
