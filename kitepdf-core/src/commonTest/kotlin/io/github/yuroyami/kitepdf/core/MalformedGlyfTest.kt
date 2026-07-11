package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * T-51 regression: a glyf entry with NON-MONOTONIC endPtsOfContours (an
 * interior entry larger than the last one, which sizes the point arrays)
 * crashed parseSimpleGlyph with ArrayIndexOutOfBoundsException. Found by the
 * mutation fuzzer (master seed 42, mutant seed 7214284492122955126 of a
 * corpus file); the font here reproduces the same malformation from scratch.
 */
class MalformedGlyfTest {

    @Test
    fun non_monotonic_contour_end_points_do_not_crash() {
        val font = TrueTypeFont.parse(buildFontWithBadEndPts())
        // Glyph 1 declares endPts [2, 1]: numPoints = last+1 = 2, but the
        // first contour claims points 0..2. Must clamp, not throw.
        val outline = assertNotNull(font.outline(1))
        // One clamped contour survives (points 0..1); the second (1 < start 3) is dropped.
        assertEquals(1, outline.contours.size)
        assertEquals(2, outline.contours[0].points.size)
    }

    private fun buildFontWithBadEndPts(): ByteArray {
        // glyf for glyph 1: 2 contours, endPts [2, 1] (bad), 2 flags, no coords.
        val glyf = u16(2) + // numberOfContours
            s16(0) + s16(0) + s16(100) + s16(100) + // bbox
            u16(2) + u16(1) + // endPtsOfContours: NON-MONOTONIC
            u16(0) + // instructionLength
            byteArrayOf(0x31, 0x31) // 2 flags: on-curve, x-same, y-same (no coord bytes)

        val head = ByteArray(54).also {
            put16(it, 18, 1000) // unitsPerEm
            // indexToLocFormat stays 0 => short loca
        }
        val maxp = u32(0x00010000) + u16(2) + ByteArray(26) // numGlyphs = 2
        val hhea = ByteArray(36).also { put16(it, 34, 1) }  // numberOfHMetrics = 1
        val hmtx = u16(500) + u16(0) + u16(0)               // 1 metric + 1 lsb
        val loca = u16(0) + u16(0) + u16(glyf.size / 2)     // glyph0 empty, glyph1 = glyf

        val tables = listOf(
            "glyf" to glyf, "head" to head, "hhea" to hhea,
            "hmtx" to hmtx, "loca" to loca, "maxp" to maxp,
        )
        var offset = 12 + tables.size * 16
        val dir = StringBuilder()
        val out = ArrayList<Byte>()
        out.addAll((u32(0x00010000) + u16(tables.size) + u16(0) + u16(0) + u16(0)).toList())
        val bodies = ArrayList<Byte>()
        for ((tag, body) in tables) {
            out.addAll(tag.encodeToByteArray().toList())
            out.addAll(u32(0).toList())            // checksum (unchecked)
            out.addAll(u32(offset.toLong()).toList())
            out.addAll(u32(body.size.toLong()).toList())
            bodies.addAll(body.toList())
            val padded = (body.size + 3) and 3.inv()
            repeat(padded - body.size) { bodies.add(0) }
            offset += padded
            dir.append(tag)
        }
        out.addAll(bodies)
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun s16(v: Int) = u16(v and 0xFFFF)
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = u32(v.toLong())
    private fun put16(arr: ByteArray, at: Int, v: Int) {
        arr[at] = (v shr 8).toByte(); arr[at + 1] = v.toByte()
    }
}
