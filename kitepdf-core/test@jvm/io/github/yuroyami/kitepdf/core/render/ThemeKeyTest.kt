package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two reading themes whose colour functions share a hash code do not share their recoloured line
 * art (#844). On the JVM only: Kotlin/JS does not let a class implement a function type.
 */
class ThemeKeyTest {

    /** A plus of black ink on white paper, 32 by 32. */
    private fun ink(): KiteImageData {
        val rgb = ByteArray(32 * 32 * 3) { 0 }
        return KiteImageData(32, 32, 8, "DeviceRGB", KiteImageData.Kind.RAW, ByteArray(0), pixelBytes = rgb, resolvedColorSpace = KiteColorSpace.DeviceRGB)
    }

    /** A colour function of one colour whose hash code is that of every other one (#844). */
    private class SameHash(private val c: RgbColor) : (RgbColor) -> RgbColor {
        override fun invoke(x: RgbColor) = c
        override fun hashCode() = 7
        override fun equals(other: Any?) = other is SameHash && other.c == c
    }

    @Test
    fun two_themes_whose_functions_share_a_hash_code_do_not_share_a_bitmap() {
        val red = ReaderTheme(RgbColor.BLACK, SameHash(RgbColor(1.0, 0.0, 0.0))).withImages(ReaderImages.LineArt)
        val blue = ReaderTheme(RgbColor.BLACK, SameHash(RgbColor(0.0, 0.0, 1.0))).withImages(ReaderImages.LineArt)
        val sameBlue = ReaderTheme(RgbColor.BLACK, SameHash(RgbColor(0.0, 0.0, 1.0))).withImages(ReaderImages.LineArt)
        val ink = ink()
        val canvas = KiteRasterCanvas(32, 32)
        fun draw(theme: ReaderTheme): Int {
            canvas.clear()
            theme.wrap(canvas).drawImage(ink, KiteMatrix(32.0, 0.0, 0.0, 32.0, 0.0, 0.0), 1.0)
            return canvas.toRaster().pixels[16 * 32 + 16]
        }
        assertEquals(0xFFFF0000.toInt(), draw(red))
        assertEquals(0xFF0000FF.toInt(), draw(blue), "blue, on the canvas that drew red")
        assertEquals(0xFF0000FF.toInt(), draw(sameBlue), "an equal theme draws the same")
    }
}
