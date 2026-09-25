package io.github.yuroyami.kitepdf.core.render

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/** DeviceCMYK converts to the colours PDFium draws (#299). */
class AdobeCmykTest {

    /** Swatches as PDFium 153.0.7999.0 renders them, through pypdfium2 5.13.0. MuPDF lands within 2 levels. */
    private val pdfium = listOf(
        listOf(0.0, 0.0, 0.0, 0.0) to listOf(255, 255, 255),
        listOf(1.0, 0.0, 0.0, 0.0) to listOf(0, 174, 239),
        listOf(0.0, 1.0, 0.0, 0.0) to listOf(237, 2, 140),
        listOf(0.0, 0.0, 1.0, 0.0) to listOf(255, 241, 1),
        listOf(0.0, 0.0, 0.0, 1.0) to listOf(35, 31, 32),
        listOf(1.0, 1.0, 1.0, 1.0) to listOf(0, 0, 0),
        listOf(0.5, 0.25, 0.75, 0.1) to listOf(128, 148, 92),
        listOf(0.2, 0.9, 0.1, 0.35) to listOf(142, 34, 99),
        listOf(0.03, 0.97, 0.41, 0.62) to listOf(115, 5, 42),
    )

    @Test
    fun every_swatch_has_the_colour_that_pdfium_draws() {
        for ((cmyk, rgb) in pdfium) {
            val c = KiteColorSpace.DeviceCMYK.toRgb(cmyk.toDoubleArray())
            assertEquals(rgb, listOf(c.r, c.g, c.b).map { (it * 255).roundToInt() }, "CMYK $cmyk")
        }
    }

    @Test
    fun the_initial_colour_is_black_ink() {
        assertEquals(KiteColorSpace.DeviceCMYK.toRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0)), KiteColorSpace.DeviceCMYK.defaultColor())
    }
}
