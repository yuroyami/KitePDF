package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Default colour spaces, ISO 32000-1, 8.6.5.6: a device colour space that content selects
 * is replaced by the DefaultGray, DefaultRGB or DefaultCMYK entry of the current resources.
 */
class DefaultColorSpaceTest {

    private val calGray = "[/CalGray << /WhitePoint [0.9505 1 1.089] /Gamma 1 >>]"
    private val defaultGray = "/ColorSpace << /DefaultGray $calGray >>"

    /** A grey level of 0.5 read in CalGray with gamma 1: linear light, so lighter than device grey. */
    private val calibratedHalf: RgbColor =
        KiteColorSpace.CalGray(doubleArrayOf(0.9505, 1.0, 1.089), 1.0).toRgb(doubleArrayOf(0.5))

    private fun fills(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>().map { it.color }

    private fun assertNear(expected: RgbColor, actual: RgbColor) =
        assertTrue(abs(expected.r - actual.r) < 1e-6 && abs(expected.g - actual.g) < 1e-6 && abs(expected.b - actual.b) < 1e-6, "expected $expected, got $actual")

    @Test
    fun default_gray_replaces_device_gray_for_g_and_cs() {
        assertTrue(calibratedHalf.r > 0.7, "the fixture must tell the two spaces apart: $calibratedHalf")
        val pdf = TestPdf.onePage("0.5 g 0 0 10 10 re f /DeviceGray cs 0.5 sc 20 0 10 10 re f", resources = defaultGray)
        val colors = fills(pdf)
        assertEquals(2, colors.size)
        colors.forEach { assertNear(calibratedHalf, it) }
    }

    @Test
    fun device_gray_is_unchanged_without_a_default() {
        assertEquals(listOf(RgbColor.gray(0.5)), fills(TestPdf.onePage("0.5 g 0 0 10 10 re f")))
    }

    @Test
    fun the_stroking_operator_takes_the_default_too() {
        val pdf = TestPdf.onePage("0.5 G 0 0 m 10 10 l S", resources = defaultGray)
        val stroke = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertNear(calibratedHalf, stroke.color)
    }

    @Test
    fun a_resource_entry_that_names_a_device_family_takes_the_default() {
        val pdf = TestPdf.onePage("/CS0 cs 0.5 sc 0 0 10 10 re f", resources = "/ColorSpace << /DefaultGray $calGray /CS0 /DeviceGray >>")
        assertNear(calibratedHalf, fills(pdf).single())
    }

    @Test
    fun a_calibrated_resource_space_keeps_its_own_colours() {
        val own = "[/CalGray << /WhitePoint [0.9505 1 1.089] /Gamma 2.2 >>]"
        val pdf = TestPdf.onePage("/CS1 cs 0.5 sc 0 0 10 10 re f", resources = "/ColorSpace << /DefaultGray $calGray /CS1 $own >>")
        val expected = KiteColorSpace.CalGray(doubleArrayOf(0.9505, 1.0, 1.089), 2.2).toRgb(doubleArrayOf(0.5))
        assertNear(expected, fills(pdf).single())
    }

    @Test
    fun a_default_of_the_wrong_size_or_a_lab_default_is_ignored() {
        val wrongSize = TestPdf.onePage("0.5 0.5 0.5 rg 0 0 10 10 re f", resources = "/ColorSpace << /DefaultRGB $calGray >>")
        assertEquals(listOf(RgbColor(0.5, 0.5, 0.5)), fills(wrongSize))
        val lab = TestPdf.onePage(
            "0.5 0.5 0.5 rg 0 0 10 10 re f",
            resources = "/ColorSpace << /DefaultRGB [/Lab << /WhitePoint [0.9505 1 1.089] >>] >>",
        )
        assertEquals(listOf(RgbColor(0.5, 0.5, 0.5)), fills(lab))
    }

    @Test
    fun a_device_gray_image_is_read_in_the_default() {
        val image = TestPdf.stream("80>", "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /ASCIIHexDecode")
        val pdf = TestPdf.onePage("10 0 0 10 0 0 cm /Im1 Do", resources = "$defaultGray /XObject << /Im1 5 0 R >>", extra = listOf(image))
        val drawn = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Image>().single().image
        assertIs<KiteColorSpace.CalGray>(drawn.resolvedColorSpace)
        val grey = drawn.toRgbaBytes()!![0].toInt() and 0xFF
        val expected = KiteColorSpace.CalGray(doubleArrayOf(0.9505, 1.0, 1.089), 1.0).toRgb(doubleArrayOf(0x80 / 255.0)).r * 255
        assertTrue(abs(grey - expected) <= 1.0, "grey $grey, expected about $expected")
    }

    @Test
    fun an_indexed_image_with_a_device_base_takes_the_default_base() {
        val image = TestPdf.stream(
            "00>",
            "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace [/Indexed /DeviceGray 1 <80FF>] /BitsPerComponent 8 /Filter /ASCIIHexDecode",
        )
        val pdf = TestPdf.onePage("10 0 0 10 0 0 cm /Im1 Do", resources = "$defaultGray /XObject << /Im1 5 0 R >>", extra = listOf(image))
        val drawn = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Image>().single().image
        val space = assertIs<KiteColorSpace.Indexed>(drawn.resolvedColorSpace)
        assertIs<KiteColorSpace.CalGray>(space.base)
    }

    @Test
    fun an_inline_image_named_by_its_abbreviation_takes_the_default() {
        val pdf = TestPdf.onePage("q 10 0 0 10 0 0 cm BI /W 1 /H 1 /BPC 8 /CS /G /F /AHx ID 80> EI Q", resources = defaultGray)
        val drawn = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Image>().single().image
        assertIs<KiteColorSpace.CalGray>(drawn.resolvedColorSpace)
    }

    @Test
    fun a_device_shading_is_parsed_in_the_default() {
        val shading = "<< /ShadingType 2 /ColorSpace /DeviceGray /Coords [0 0 100 0] " +
            "/Function << /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 1 >> >>"
        val pdf = TestPdf.onePage("/Sh1 sh", resources = "$defaultGray /Shading << /Sh1 5 0 R >>", extra = listOf(shading))
        val canvas = ShadingCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertIs<KiteColorSpace.CalGray>(canvas.shadings.single().colorSpace)
    }

    @Test
    fun a_form_with_its_own_resources_does_not_take_the_page_defaults() {
        // 8.6.5.6 reads the current resource dictionary, which is the form's own.
        val form = TestPdf.stream("0.5 g 0 0 10 10 re f", "/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Resources << >>")
        val pdf = TestPdf.onePage("/Fm1 Do", resources = "$defaultGray /XObject << /Fm1 5 0 R >>", extra = listOf(form))
        assertEquals(listOf(RgbColor.gray(0.5)), fills(pdf))
    }

    @Test
    fun a_form_without_resources_reads_the_page_defaults() {
        val form = TestPdf.stream("0.5 g 0 0 10 10 re f", "/Type /XObject /Subtype /Form /BBox [0 0 100 100]")
        val pdf = TestPdf.onePage("/Fm1 Do", resources = "$defaultGray /XObject << /Fm1 5 0 R >>", extra = listOf(form))
        assertNear(calibratedHalf, fills(pdf).single())
    }

    @Test
    fun the_default_space_stays_the_current_space_for_sc() {
        // g selects the default, so a later sc reads its operand in the default as well.
        val pdf = TestPdf.onePage("0 g 0.5 sc 0 0 10 10 re f", resources = defaultGray)
        assertNear(calibratedHalf, fills(pdf).single())
    }

    /** Keeps each shading the renderer hands to the canvas. */
    private class ShadingCanvas(private val inner: RecordingCanvas = RecordingCanvas()) : KiteCanvas by inner {
        val shadings = ArrayList<KiteShading>()
        override fun fillShading(shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?, alpha: Double, blendMode: KiteBlendMode) {
            shadings += shading
        }
    }
}
