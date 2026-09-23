package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A canvas wrapper that delegates with `by` and overrides only the first image overload
 * still sees each image that paints with the Normal blend mode. Kotlin sends an overload
 * that the wrapper does not override straight to the wrapped canvas (#290).
 */
class CanvasWrapperTest {

    private class ImageCountingCanvas(val inner: RecordingCanvas = RecordingCanvas()) : KiteCanvas by inner {
        var images = 0
        override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
            images++
            inner.drawImage(image, ctm, alpha)
        }
    }

    private val redPixel = TestPdf.stream(
        "FF0000>",
        "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /ASCIIHexDecode",
    )

    @Test
    fun a_wrapper_sees_an_image_xobject() {
        val pdf = TestPdf.onePage("100 0 0 100 50 50 cm /Im1 Do", resources = "/XObject << /Im1 5 0 R >>", extra = listOf(redPixel))
        val canvas = ImageCountingCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(1, canvas.images)
    }

    @Test
    fun a_wrapper_sees_an_inline_image() {
        val pdf = TestPdf.onePage("BI /W 1 /H 1 /BPC 8 /CS /RGB /F /AHx ID FF0000> EI")
        val canvas = ImageCountingCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(1, canvas.images)
    }
}
