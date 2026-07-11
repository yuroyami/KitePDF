package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.compression.Inflate
import io.github.yuroyami.kitepdf.core.compression.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InflateTest {

    /** Adler-32 of "Hello" computed by hand: a=501, b=1420 → (1420<<16)|501 = 0x058C01F5. */
    @Test
    fun adler32_hello() {
        val data = "Hello".encodeToByteArray()
        val expected = 0x058C01F5
        assertEquals(expected, Zlib.adler32(data))
    }

    /** A stored (uncompressed) DEFLATE block — easiest to hand-craft. */
    @Test
    fun inflate_storedBlock_hello() {
        val bytes = byteArrayOf(
            0x01,                                                // BFINAL=1, BTYPE=0 (stored)
            0x05, 0x00,                                          // LEN  = 5  (little-endian)
            0xFA.toByte(), 0xFF.toByte(),                        // NLEN = ~LEN
            'H'.code.toByte(), 'e'.code.toByte(),
            'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
        )
        val decoded = Inflate.decode(bytes)
        assertContentEquals("Hello".encodeToByteArray(), decoded)
    }

    /** Full zlib envelope (header + stored payload + Adler trailer). */
    @Test
    fun zlib_roundTrip_hello() {
        val bytes = byteArrayOf(
            0x78, 0x01,                                          // CMF + FLG (0x7801 % 31 == 0)
            0x01,                                                // stored block header
            0x05, 0x00, 0xFA.toByte(), 0xFF.toByte(),            // LEN/NLEN
            'H'.code.toByte(), 'e'.code.toByte(),
            'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
            0x05, 0x8C.toByte(), 0x01, 0xF5.toByte(),            // Adler-32 big-endian
        )
        val decoded = Zlib.decode(bytes)
        assertContentEquals("Hello".encodeToByteArray(), decoded)
    }

    @Test
    fun zlib_corruptAdler_throws() {
        val bytes = byteArrayOf(
            0x78, 0x01,
            0x01, 0x05, 0x00, 0xFA.toByte(), 0xFF.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(),
            'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,                              // wrong checksum
        )
        assertFailsWith<io.github.yuroyami.kitepdf.core.compression.InflateException> {
            Zlib.decode(bytes)
        }
    }

    /** Fixed-Huffman block from a real zlib (level 9) of "AAA". */
    @Test
    fun zlib_realDeflate_aaa() {
        val bytes = byteArrayOf(
            0x78, 0xDA.toByte(),
            0x73, 0x74, 0x74, 0x04, 0x00,
            0x01, 0x89.toByte(), 0x00, 0xC4.toByte(),
        )
        val decoded = Zlib.decode(bytes)
        assertContentEquals("AAA".encodeToByteArray(), decoded)
    }

    /** A real-world DEFLATE stream that exercises fixed Huffman literals. */
    @Test
    fun zlib_realDeflate_helloKitePdf() {
        val bytes = byteArrayOf(
            0x78, 0xDA.toByte(),
            0xF3.toByte(), 0x48, 0xCD.toByte(), 0xC9.toByte(), 0xC9.toByte(), 0xD7.toByte(),
            0x51, 0xF0.toByte(), 0xCE.toByte(), 0x2C, 0x49, 0x0D, 0x70, 0x71, 0x53, 0x04, 0x00,
            0x28, 0x97.toByte(), 0x04, 0xC9.toByte(),
        )
        assertContentEquals("Hello, KitePDF!".encodeToByteArray(), Zlib.decode(bytes))
    }

    /** Longer text — exercises LZ77 back-references (the/o repeated). */
    @Test
    fun zlib_realDeflate_lazyDog() {
        val bytes = byteArrayOf(
            0x78, 0xDA.toByte(),
            0x0B, 0xC9.toByte(), 0x48, 0x55, 0x28, 0x2C, 0xCD.toByte(), 0x4C, 0xCE.toByte(),
            0x56, 0x48, 0x2A, 0xCA.toByte(), 0x2F, 0xCF.toByte(), 0x53, 0x48, 0xCB.toByte(),
            0xAF.toByte(), 0x50, 0xC8.toByte(), 0x2A, 0xCD.toByte(), 0x2D, 0x28, 0x56,
            0xC8.toByte(), 0x2F, 0x4B, 0x2D, 0x52, 0x28, 0x01, 0x4A, 0xE7.toByte(), 0x24,
            0x56, 0x55, 0x2A, 0xA4.toByte(), 0xE4.toByte(), 0xA7.toByte(), 0x03, 0x00,
            0x5B, 0xDC.toByte(), 0x0F, 0xDA.toByte(),
        )
        val expected = "The quick brown fox jumps over the lazy dog"
        assertContentEquals(expected.encodeToByteArray(), Zlib.decode(bytes))
    }
}
