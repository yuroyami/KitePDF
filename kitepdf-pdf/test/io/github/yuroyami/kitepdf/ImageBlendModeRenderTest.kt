package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** The blend mode of the graphics state reaches the canvas with every image (ISO 32000-1, 11.3.5, #113). */
class ImageBlendModeRenderTest {

    private fun blendModes(pdf: ByteArray): List<KiteBlendMode> =
        TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Image>().map { it.blendMode }

    private val multiply = "/ExtGState << /GS1 << /BM /Multiply >> >>"

    @Test
    fun an_image_xobject_paints_with_the_blend_mode() {
        val pdf = TestPdf.onePage(
            "q /GS1 gs 100 0 0 100 50 50 cm /Im1 Do Q 100 0 0 100 50 50 cm /Im1 Do",
            resources = "$multiply /XObject << /Im1 5 0 R >>",
            extra = listOf(TestPdf.stream("FF0000>", "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /ASCIIHexDecode")),
        )
        assertEquals(listOf(KiteBlendMode.Multiply, KiteBlendMode.Normal), blendModes(pdf))
    }

    @Test
    fun an_inline_image_paints_with_the_blend_mode() {
        val pdf = TestPdf.onePage("/GS1 gs BI /W 1 /H 1 /BPC 8 /CS /RGB /F /AHx ID FF0000> EI", resources = multiply)
        assertEquals(listOf(KiteBlendMode.Multiply), blendModes(pdf))
    }
}
