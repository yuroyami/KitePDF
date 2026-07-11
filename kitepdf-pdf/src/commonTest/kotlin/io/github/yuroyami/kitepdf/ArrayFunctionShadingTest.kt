package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.render.KiteShading
import io.github.yuroyami.kitepdf.render.sampleStops
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for the "array-form /Function drops the whole shading" bug: an
 * axial shading whose /Function is an array of n single-output functions must
 * parse and sample, not return null.
 */
class ArrayFunctionShadingTest {

    private val nil = IndirectResolver { null }
    private fun reals(vararg v: Double) = PdfArray(v.map { PdfReal(it) })

    private fun type2(c0: Double, c1: Double): PdfObject = PdfDictionary(linkedMapOf(
        "FunctionType" to PdfInt(2),
        "Domain" to reals(0.0, 1.0),
        "C0" to reals(c0), "C1" to reals(c1), "N" to PdfInt(1),
    ))

    @Test fun axial_with_array_function_samples_black_to_white() {
        val shading = KiteShading.parse(
            PdfDictionary(linkedMapOf(
                "ShadingType" to PdfInt(2),
                "ColorSpace" to PdfName("DeviceRGB"),
                "Coords" to reals(0.0, 0.0, 1.0, 0.0),
                "Extend" to PdfArray(listOf(PdfBoolean(false), PdfBoolean(false))),
                // One single-output function per RGB channel.
                "Function" to PdfArray(listOf(type2(0.0, 1.0), type2(0.0, 1.0), type2(0.0, 1.0))),
            )),
            nil,
        )
        assertNotNull(shading)
        val stops = shading.sampleStops(3)
        assertNotNull(stops)
        val first = stops.colors.first()
        val last = stops.colors.last()
        assertTrue(first.r < 0.05 && first.g < 0.05 && first.b < 0.05, "t=0 should be black: $first")
        assertTrue(last.r > 0.95 && last.g > 0.95 && last.b > 0.95, "t=1 should be white: $last")
    }
}
