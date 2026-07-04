package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.render.PdfFunction
import io.github.yuroyami.kitepdf.render.PdfShading
import io.github.yuroyami.kitepdf.render.sampleStops
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunctionShadingTest {

    private val noResolver = IndirectResolver { _: PdfReference -> null }

    @Test
    fun type2_function_interpolates_linearly() {
        val fn = PdfFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0),
            range = null,
            c0 = doubleArrayOf(0.0, 0.0, 0.0),
            c1 = doubleArrayOf(1.0, 1.0, 1.0),
            n = 1.0,
        )
        // Midpoint → midpoint of colour line
        val mid = fn.evaluate(doubleArrayOf(0.5))
        assertEquals(0.5, mid[0], 1e-9)
        assertEquals(0.5, mid[1], 1e-9)
        assertEquals(0.5, mid[2], 1e-9)

        val zero = fn.evaluate(doubleArrayOf(0.0))
        assertEquals(0.0, zero[0])
        val one = fn.evaluate(doubleArrayOf(1.0))
        assertEquals(1.0, one[0])
    }

    @Test
    fun type2_function_with_n_squared_biases_to_c0() {
        val fn = PdfFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0),
            range = null,
            c0 = doubleArrayOf(0.0),
            c1 = doubleArrayOf(1.0),
            n = 2.0,
        )
        // n=2 → output = x^2 — at x=0.5 the result is 0.25, not 0.5.
        assertEquals(0.25, fn.evaluate(doubleArrayOf(0.5))[0], 1e-9)
    }

    @Test
    fun type3_stitching_dispatches_to_correct_subfunction() {
        val left = PdfFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0), range = null,
            c0 = doubleArrayOf(0.0), c1 = doubleArrayOf(1.0), n = 1.0,
        )
        val right = PdfFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0), range = null,
            c0 = doubleArrayOf(1.0), c1 = doubleArrayOf(0.0), n = 1.0,
        )
        // Domain 0..1 split at 0.5; left subfunction in [0, 0.5], right in [0.5, 1].
        // Encode maps the whole domain into each subfunction's [0, 1].
        val stitched = PdfFunction.Type3(
            domain = doubleArrayOf(0.0, 1.0), range = null,
            functions = listOf(left, right),
            bounds = doubleArrayOf(0.5),
            encode = doubleArrayOf(0.0, 1.0, 0.0, 1.0),
        )
        // x=0.25 routes to left → left(0.5) = 0.5
        assertEquals(0.5, stitched.evaluate(doubleArrayOf(0.25))[0], 1e-9)
        // x=0.75 routes to right → right(0.5) = 0.5
        assertEquals(0.5, stitched.evaluate(doubleArrayOf(0.75))[0], 1e-9)
        // x=0.1 routes to left → left(0.2) = 0.2
        assertEquals(0.2, stitched.evaluate(doubleArrayOf(0.1))[0], 1e-9)
    }

    @Test
    fun function_input_is_clamped_to_domain() {
        val fn = PdfFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0), range = null,
            c0 = doubleArrayOf(0.0), c1 = doubleArrayOf(1.0), n = 1.0,
        )
        assertEquals(0.0, fn.evaluate(doubleArrayOf(-0.5))[0])  // clamped to 0
        assertEquals(1.0, fn.evaluate(doubleArrayOf(2.0))[0])   // clamped to 1
    }

    @Test
    fun function_output_is_clamped_to_range() {
        val fn = PdfFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0),
            range = doubleArrayOf(0.2, 0.8),
            c0 = doubleArrayOf(0.0), c1 = doubleArrayOf(1.0), n = 1.0,
        )
        // f(0) = 0, but range floor is 0.2.
        assertEquals(0.2, fn.evaluate(doubleArrayOf(0.0))[0])
        // f(1) = 1, but range ceiling is 0.8.
        assertEquals(0.8, fn.evaluate(doubleArrayOf(1.0))[0])
    }

    @Test
    fun parses_type2_function_dict() {
        val dict = PdfDictionary(
            mapOf(
                "FunctionType" to PdfInt(2),
                "Domain" to PdfArray(listOf(PdfReal(0.0), PdfReal(1.0))),
                "C0" to PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(0.5))),
                "C1" to PdfArray(listOf(PdfReal(1.0), PdfReal(0.5), PdfReal(0.0))),
                "N" to PdfReal(1.0),
            ),
        )
        val fn = PdfFunction.parse(dict, noResolver)
        assertTrue(fn is PdfFunction.Type2)
        assertEquals(3, fn.outputCount)
        assertEquals(0.5, fn.evaluate(doubleArrayOf(1.0))[1], 1e-9)
    }

    @Test
    fun axial_shading_samples_endpoint_colors() {
        val axial = PdfShading.parse(
            shadingDict(
                shadingType = 2,
                coords = listOf(0.0, 0.0, 100.0, 0.0),
                c0 = listOf(1.0, 0.0, 0.0),
                c1 = listOf(0.0, 0.0, 1.0),
            ),
            noResolver,
        )
        assertTrue(axial is PdfShading.Axial)
        val stops = axial.sampleStops(2)
        assertNotNull(stops)
        // First stop = red, last stop = blue.
        assertEquals(1.0, stops.colors.first().r, 1e-9)
        assertEquals(0.0, stops.colors.first().b, 1e-9)
        assertEquals(0.0, stops.colors.last().r, 1e-9)
        assertEquals(1.0, stops.colors.last().b, 1e-9)
    }

    @Test
    fun radial_shading_samples_endpoint_colors() {
        val radial = PdfShading.parse(
            shadingDict(
                shadingType = 3,
                coords = listOf(50.0, 50.0, 0.0, 50.0, 50.0, 50.0),
                c0 = listOf(1.0, 1.0, 1.0),
                c1 = listOf(0.0, 0.0, 0.0),
            ),
            noResolver,
        )
        assertTrue(radial is PdfShading.Radial)
        val stops = radial.sampleStops()
        assertNotNull(stops)
        // White at centre (t=0), black at edge (t=1).
        assertEquals(1.0, stops.colors.first().r, 1e-9)
        assertEquals(0.0, stops.colors.last().r, 1e-9)
        assertEquals(32, stops.colors.size)
    }

    @Test
    fun unsupported_shading_type_returns_unsupported() {
        val sh = PdfShading.parse(
            shadingDict(shadingType = 4, coords = listOf()),
            noResolver,
        )
        assertTrue(sh is PdfShading.Unsupported)
        assertEquals(4, sh.type)
    }

    @Test
    fun number_array_returned_for_known_colorspace() {
        // Ensure DeviceRGB colourspace gets picked up from the dict.
        val sh = PdfShading.parse(
            shadingDict(
                shadingType = 2,
                coords = listOf(0.0, 0.0, 1.0, 0.0),
                c0 = listOf(0.0, 0.0, 0.0),
                c1 = listOf(1.0, 1.0, 1.0),
                colorSpace = PdfName("DeviceRGB"),
            ),
            noResolver,
        )
        assertTrue(sh is PdfShading.Axial)
        assertEquals(3, sh.colorSpace.componentCount)
    }

    /* ─── Helper ──────────────────────────────────────────────────────────── */

    private fun shadingDict(
        shadingType: Int,
        coords: List<Double>,
        c0: List<Double> = listOf(0.0, 0.0, 0.0),
        c1: List<Double> = listOf(1.0, 1.0, 1.0),
        colorSpace: PdfObject = PdfName("DeviceRGB"),
    ): PdfDictionary {
        val func = PdfDictionary(
            mapOf(
                "FunctionType" to PdfInt(2),
                "Domain" to PdfArray(listOf(PdfReal(0.0), PdfReal(1.0))),
                "C0" to PdfArray(c0.map { PdfReal(it) }),
                "C1" to PdfArray(c1.map { PdfReal(it) }),
                "N" to PdfReal(1.0),
            ),
        )
        return PdfDictionary(
            mapOf(
                "ShadingType" to PdfInt(shadingType.toLong()),
                "ColorSpace" to colorSpace,
                "Coords" to PdfArray(coords.map { PdfReal(it) }),
                "Function" to func,
            ),
        )
    }
}
