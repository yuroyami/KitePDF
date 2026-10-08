package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.zip.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The shared EPUB/SVG image entry point must return the reference pixels and opacity (#513). */
class ModernImageDecodeTest {
    @Test
    fun avif_matches_libavif_with_and_without_alpha() {
        assertEquals(0xad01fdbc63e1b638uL, fnv(argb(ModernImageFixtures.AVIF_PLAIN)))
        assertEquals(0xa55791907da1b9f1uL, fnv(argb(ModernImageFixtures.AVIF_ALPHA)))
    }

    @Test
    fun jpeg_xl_codestream_and_container_match_djxl() {
        assertEquals(0x89cab1b9bf89e9a5uL, fnv(argb(ModernImageFixtures.JXL_LOSSLESS)))
        assertEquals(0x17c398b1d481c291uL, fnv(argb(ModernImageFixtures.JXL_ALPHA)))
    }

    @Test
    fun lossy_webp_matches_dwebp_with_and_without_alpha() {
        assertEquals(0x0D1F34F3L, Crc32.of(argb(ModernImageFixtures.WEBP_LOSSY)))
        assertEquals(0xE33C325CL, Crc32.of(argb(ModernImageFixtures.WEBP_LOSSY_ALPHA, 16, 16)))
    }

    @Test
    fun damaged_modern_images_are_skipped() {
        for (file in listOf(
            ModernImageFixtures.AVIF_PLAIN, ModernImageFixtures.JXL_LOSSLESS,
            ModernImageFixtures.JXL_ALPHA, ModernImageFixtures.WEBP_LOSSY,
        )) {
            assertNull(KiteImageData.fromEncodedImage(hex(file).copyOf(32)))
        }
    }

    /** Reference hashes are over A, R, G, B samples, before compositing onto a page. */
    private fun argb(file: String, width: Int = 24, height: Int = 16): ByteArray {
        val image = assertNotNull(KiteImageData.fromEncodedImage(hex(file)))
        assertEquals(width to height, image.width to image.height)
        assertEquals(KiteImageData.Kind.RAW, image.kind)
        val rgb = assertNotNull(image.pixelBytes)
        return ByteArray(width * height * 4) { i ->
            val pixel = i / 4
            if (i % 4 == 0) image.softMaskAlpha?.get(pixel) ?: 0xFF.toByte()
            else rgb[pixel * 3 + i % 4 - 1]
        }
    }

    private fun fnv(bytes: ByteArray): ULong = bytes.fold(0xcbf29ce484222325uL) { hash, byte ->
        (hash xor (byte.toInt() and 0xFF).toULong()) * 0x100000001b3uL
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
