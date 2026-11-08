package io.github.yuroyami.kitepdf.core.parser

import io.github.yuroyami.kitepdf.core.render.ExtGState
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteShading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shading, function and graphics state entries may be indirect objects (ISO 32000-1, 7.3.10; #777). */
class DirectValuesTest {

    private val objects = mapOf(
        10L to PdfReal(0.5),
        11L to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(100), PdfInt(0))),
        12L to PdfArray(listOf(PdfInt(1), PdfInt(0), PdfInt(0))),
        13L to PdfInt(2),
    )
    private val refs = IndirectResolver { objects[it.objectNumber] }
    private fun ref(n: Long) = PdfReference(n, 0)

    @Test
    fun anIndirectAlphaApplies() {
        val gs = ExtGState.parse(PdfDictionary(mapOf("ca" to ref(10))), refs)
        assertEquals(0.5, gs.fillAlpha)
    }

    @Test
    fun anIndirectFunctionEntryApplies() {
        val f = KiteFunction.parse(
            PdfDictionary(mapOf("FunctionType" to ref(13), "Domain" to PdfArray(listOf(PdfInt(0), PdfInt(1))),
                "C0" to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(0))), "C1" to ref(12), "N" to PdfInt(1))),
            refs,
        )!!
        assertEquals(listOf(1.0, 0.0, 0.0), f.evaluate(doubleArrayOf(1.0)).toList())
    }

    @Test
    fun anIndirectCoordsArrayMakesAShading() {
        val fn = PdfDictionary(mapOf("FunctionType" to PdfInt(2), "Domain" to PdfArray(listOf(PdfInt(0), PdfInt(1))), "C0" to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(0))), "C1" to ref(12), "N" to PdfInt(1)))
        val shading = KiteShading.parse(
            PdfDictionary(mapOf("ShadingType" to ref(13), "ColorSpace" to PdfName("DeviceRGB"), "Coords" to ref(11), "Function" to fn)),
            refs,
        )
        assertTrue(shading is KiteShading.Axial, "$shading")
    }
}
