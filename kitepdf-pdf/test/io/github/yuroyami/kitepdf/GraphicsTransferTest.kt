package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** The transfer function of the graphics state, `/TR` and `/TR2`, maps each painted colour (ISO 32000-1, 10.4, Table 58; #909). */
class GraphicsTransferTest {

    private val invert = TestPdf.stream("{1 exch sub}", "/FunctionType 4 /Domain [0 1] /Range [0 1]")
    // Object 6: a function that gives 0.5 to everything.
    private val half = TestPdf.stream("{pop 0.5}", "/FunctionType 4 /Domain [0 1] /Range [0 1]")

    /** The grey level at the centre of the left and the right half, and the red, green and blue of the left. */
    private fun levels(states: String, content: String): Pair<Int, Int> {
        val pdf = TestPdf.onePage(content, resources = "/ExtGState << $states >>", extra = listOf(invert, half))
        val canvas = KiteRasterCanvas(200, 200)
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0))
        val px = canvas.toRaster().pixels
        return (px[100 * 200 + 50] and 0xFF) to (px[100 * 200 + 150] and 0xFF)
    }

    private val halves = "q /GS1 gs 0.25 g 0 0 100 200 re f Q 0.25 g 100 0 100 200 re f"

    @Test
    fun tr_maps_the_colours_it_paints_and_only_under_its_gs() {
        // The issue's file: Poppler gives 191 on the left and 64 on the right.
        assertEquals(191 to 64, levels("/GS1 << /TR 5 0 R >>", halves))
    }

    @Test
    fun tr2_maps_too_and_wins_over_tr() {
        assertEquals(191 to 64, levels("/GS1 << /TR2 5 0 R >>", halves))
        assertEquals(128 to 64, levels("/GS1 << /TR 5 0 R /TR2 6 0 R >>", halves))
    }

    @Test
    fun identity_and_default_reset_it() {
        assertEquals(64 to 64, levels("/GS1 << /TR 5 0 R >> /GS2 << /TR /Identity >>", "q /GS1 gs /GS2 gs 0.25 g 0 0 100 200 re f Q 0.25 g 100 0 100 200 re f"))
        assertEquals(64 to 64, levels("/GS1 << /TR 5 0 R >> /GS2 << /TR2 /Default >>", "q /GS1 gs /GS2 gs 0.25 g 0 0 100 200 re f Q 0.25 g 100 0 100 200 re f"))
    }

    @Test
    fun an_array_maps_each_colorant_with_its_own_function() {
        val pdf = TestPdf.onePage(
            "/GS1 gs 0.25 g 0 0 200 200 re f",
            resources = "/ExtGState << /GS1 << /TR [5 0 R 6 0 R /Identity 6 0 R] >> >>",
            extra = listOf(invert, half),
        )
        val canvas = KiteRasterCanvas(200, 200)
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val p = canvas.toRaster().pixels[100 * 200 + 100]
        assertEquals(listOf(191, 128, 64), listOf(p ushr 16 and 0xFF, p ushr 8 and 0xFF, p and 0xFF))
    }

    @Test
    fun an_image_maps_its_samples_too() {
        val pdf = TestPdf.onePage(
            "/GS1 gs q 200 0 0 200 0 0 cm BI /W 1 /H 1 /CS /G /BPC 8 ID @ EI Q",
            resources = "/ExtGState << /GS1 << /TR 5 0 R >> >>",
            extra = listOf(invert, half),
        )
        val canvas = KiteRasterCanvas(200, 200)
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        // The sample is 0x40, 64; inverted it is 191.
        assertEquals(191, canvas.toRaster().pixels[100 * 200 + 100] and 0xFF)
    }
}
