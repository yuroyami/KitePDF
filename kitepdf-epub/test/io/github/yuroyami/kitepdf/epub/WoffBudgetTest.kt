package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.compression.Zlib
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A WOFF table unpacks to its declared length, and a font to at most 64 MiB (#988). */
class WoffBudgetTest {

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** A WOFF 1.0 whose [count] directory entries all point at one [payload] with [origLen]. */
    private fun woff(payload: ByteArray, origLen: Long, count: Int = 1): ByteArray {
        val dataStart = 44 + 20 * count
        val header = "wOFF".encodeToByteArray() + u32(0x00010000) + u32((dataStart + payload.size).toLong()) +
            u16(count) + u16(0) + u32(origLen) + ByteArray(44 - 20)
        var dir = ByteArray(0)
        for (i in 0 until count) {
            dir += "t${'a' + i}bl".take(4).encodeToByteArray() + u32(dataStart.toLong()) + u32(payload.size.toLong()) + u32(origLen) + u32(0)
        }
        return header + dir + payload
    }

    @Test
    fun aTableUnpacksToItsDeclaredLength() {
        val table = ByteArray(1000) { (it % 7).toByte() }
        val sfnt = assertNotNull(Woff.toSfnt(woff(Zlib.encode(table), table.size.toLong())))
        assertEquals(12 + 16 + 1000, sfnt.size)
        // A stream that inflates past its declared length is damage, not a longer table.
        assertNull(Woff.toSfnt(woff(Zlib.encode(ByteArray(4000)), 1000)))
    }

    @Test
    fun aFontPastTheCeilingIsRefusedBeforeItInflates() {
        // Four entries over one stream of zeros, each declared at 100 MiB.
        val zeros = Zlib.encode(ByteArray(1 shl 20))
        assertNull(Woff.toSfnt(woff(zeros, 100L shl 20, count = 4)))
        assertNull(Woff.toSfnt(woff(zeros, 0xFFFFFFF0L)))
    }
}
