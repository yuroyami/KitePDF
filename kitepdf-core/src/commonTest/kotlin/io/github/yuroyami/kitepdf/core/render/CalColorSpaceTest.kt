package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CalGray and CalRGB (ISO 32000-1, 8.6.5.2 and 8.6.5.3): gamma and matrix
 * applied through the same XYZ-to-sRGB path Lab already uses, instead of
 * collapsing to the device space and ignoring both.
 */
class CalColorSpaceTest {

    private val d65 = doubleArrayOf(0.9505, 1.0, 1.089)

    private fun neutral(c: RgbColor): Boolean =
        abs(c.r - c.g) < 0.02 && abs(c.g - c.b) < 0.02

    @Test
    fun cal_gray_endpoints_are_black_and_white() {
        val cs = KiteColorSpace.CalGray(d65, gamma = 2.2)
        val black = cs.toRgb(doubleArrayOf(0.0))
        val white = cs.toRgb(doubleArrayOf(1.0))
        assertTrue(black.r < 0.01 && black.g < 0.01 && black.b < 0.01, "0 renders black: $black")
        assertTrue(white.r > 0.99 && white.g > 0.99 && white.b > 0.99, "1 renders white: $white")
    }

    @Test
    fun cal_gray_gamma_darkens_midtones_and_stays_neutral() {
        val flat = KiteColorSpace.CalGray(d65, gamma = 1.0).toRgb(doubleArrayOf(0.5))
        val curved = KiteColorSpace.CalGray(d65, gamma = 2.2).toRgb(doubleArrayOf(0.5))
        assertTrue(neutral(flat), "gray input stays neutral: $flat")
        assertTrue(neutral(curved), "gray input stays neutral: $curved")
        assertTrue(curved.g < flat.g - 0.1, "gamma 2.2 darkens a midtone: ${curved.g} vs ${flat.g}")
    }

    @Test
    fun cal_rgb_endpoints_and_gamma() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val flat = KiteColorSpace.CalRGB(d65, doubleArrayOf(1.0, 1.0, 1.0), null)
        val black = flat.toRgb(doubleArrayOf(0.0, 0.0, 0.0))
        val white = flat.toRgb(doubleArrayOf(1.0, 1.0, 1.0))
        assertTrue(black.r < 0.01 && black.g < 0.01 && black.b < 0.01, "black endpoint: $black")
        assertTrue(white.r > 0.99 && white.g > 0.99 && white.b > 0.99, "white endpoint: $white")

        // Compare within the matrix path: both treat the gamma'd value as
        // linear light, so only the gamma differs between them.
        val flatM = KiteColorSpace.CalRGB(d65, doubleArrayOf(1.0, 1.0, 1.0), identity)
        val curved = KiteColorSpace.CalRGB(d65, doubleArrayOf(2.2, 2.2, 2.2), identity)
        val mid = flatM.toRgb(doubleArrayOf(0.5, 0.5, 0.5))
        val midCurved = curved.toRgb(doubleArrayOf(0.5, 0.5, 0.5))
        assertTrue(midCurved.g < mid.g - 0.1, "per-channel gamma darkens: ${midCurved.g} vs ${mid.g}")
    }

    @Test
    fun cal_rgb_matrix_maps_a_primary_to_its_hue() {
        // The sRGB primaries as XYZ columns: pure A input must come out red-dominant.
        val srgbMatrix = doubleArrayOf(
            0.4124, 0.2126, 0.0193,   // column for A: X, Y, Z of the red primary
            0.3576, 0.7152, 0.1192,
            0.1805, 0.0722, 0.9505,
        )
        val cs = KiteColorSpace.CalRGB(d65, doubleArrayOf(1.0, 1.0, 1.0), srgbMatrix)
        val red = cs.toRgb(doubleArrayOf(1.0, 0.0, 0.0))
        assertTrue(red.r > 0.9 && red.g < 0.2 && red.b < 0.2, "red primary maps to red: $red")
    }

    @Test
    fun resolve_builds_the_cal_classes_not_device_fallbacks() {
        val none = IndirectResolver { null }
        fun wp() = PdfArray(listOf(PdfReal(0.9505), PdfReal(1.0), PdfReal(1.089)))

        val calGray = KiteColorSpace.resolve(
            PdfArray(listOf(
                PdfName("CalGray"),
                PdfDictionary(mapOf("WhitePoint" to wp(), "Gamma" to PdfReal(2.2))),
            )),
            none,
        )
        assertIs<KiteColorSpace.CalGray>(calGray, "CalGray array resolves to the real class")

        val calRgb = KiteColorSpace.resolve(
            PdfArray(listOf(
                PdfName("CalRGB"),
                PdfDictionary(mapOf("WhitePoint" to wp())),
            )),
            none,
        )
        assertIs<KiteColorSpace.CalRGB>(calRgb, "CalRGB array resolves to the real class")
    }
}
