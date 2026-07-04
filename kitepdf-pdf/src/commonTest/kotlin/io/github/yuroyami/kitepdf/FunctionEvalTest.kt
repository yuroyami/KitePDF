package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.render.PdfFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Type 0 (sampled) and Type 4 (PostScript calculator) function evaluation, plus
 *  the array-of-functions combination form. */
class FunctionEvalTest {

    private val nilRefs = io.github.yuroyami.kitepdf.parser.IndirectResolver { null }
    private fun ints(vararg v: Int) = PdfArray(v.map { PdfInt(it.toLong()) })
    private fun reals(vararg v: Double) = PdfArray(v.map { PdfReal(it) })

    @Test fun type0_sampled_linear_interpolation() {
        // 1-in 1-out ramp: samples [0x00, 0xFF] over Size [2] → identity ramp.
        val stream = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "FunctionType" to PdfInt(0),
                "Domain" to reals(0.0, 1.0),
                "Range" to reals(0.0, 1.0),
                "Size" to ints(2),
                "BitsPerSample" to PdfInt(8),
                "Length" to PdfInt(2),
            )),
            rawBytes = byteArrayOf(0x00, 0xFF.toByte()),
        )
        val f = PdfFunction.parse(stream, nilRefs)
        assertNotNull(f)
        assertEquals(0.0, f.evaluate(doubleArrayOf(0.0))[0], 0.001)
        assertEquals(1.0, f.evaluate(doubleArrayOf(1.0))[0], 0.001)
        assertEquals(0.5, f.evaluate(doubleArrayOf(0.5))[0], 0.01)
    }

    @Test fun type0_two_input_bilinear() {
        // 2-in 1-out, Size [2 2], samples (row-major, dim0 fastest):
        //   (0,0)=0, (1,0)=255, (0,1)=255, (1,1)=0
        val stream = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "FunctionType" to PdfInt(0),
                "Domain" to reals(0.0, 1.0, 0.0, 1.0),
                "Range" to reals(0.0, 1.0),
                "Size" to ints(2, 2),
                "BitsPerSample" to PdfInt(8),
                "Length" to PdfInt(4),
            )),
            rawBytes = byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00),
        )
        val f = PdfFunction.parse(stream, nilRefs)!!
        assertEquals(0.0, f.evaluate(doubleArrayOf(0.0, 0.0))[0], 0.01)
        assertEquals(1.0, f.evaluate(doubleArrayOf(1.0, 0.0))[0], 0.01)
        assertEquals(0.5, f.evaluate(doubleArrayOf(0.5, 0.5))[0], 0.02) // centre = mean of corners
    }

    private fun ps(program: String, range: PdfArray, domain: PdfArray = reals(0.0, 100.0)): PdfFunction {
        val bytes = program.encodeToByteArray()
        val stream = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "FunctionType" to PdfInt(4),
                "Domain" to domain,
                "Range" to range,
                "Length" to PdfInt(bytes.size.toLong()),
            )),
            rawBytes = bytes,
        )
        return PdfFunction.parse(stream, nilRefs)!!
    }

    @Test fun type4_arithmetic() {
        val f = ps("{ 2 mul }", reals(0.0, 100.0))
        assertEquals(6.0, f.evaluate(doubleArrayOf(3.0))[0], 0.001)
    }

    @Test fun type4_dup_mul_square() {
        val f = ps("{ dup mul }", reals(0.0, 100.0))
        assertEquals(16.0, f.evaluate(doubleArrayOf(4.0))[0], 0.001)
    }

    @Test fun type4_ifelse_branch() {
        val f = ps("{ 0.5 gt { 1 } { 0 } ifelse }", reals(0.0, 1.0))
        assertEquals(1.0, f.evaluate(doubleArrayOf(0.7))[0], 0.001)
        assertEquals(0.0, f.evaluate(doubleArrayOf(0.3))[0], 0.001)
    }

    @Test fun type4_multi_output() {
        // CMYK-ish: 1 input → 4 outputs (k channel only).
        val f = ps("{ 0 exch 0 exch 0 exch }", reals(0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0))
        val out = f.evaluate(doubleArrayOf(0.42))
        assertEquals(4, out.size)
        assertEquals(0.42, out[3], 0.001) // last pushed stays the k value
    }

    @Test fun array_of_functions_combines_outputs() {
        // [ {Type2 →0} {Type2 →1} ] → 2-output function.
        fun t2(c0: Double, c1: Double): PdfObject = PdfDictionary(linkedMapOf(
            "FunctionType" to PdfInt(2),
            "Domain" to reals(0.0, 1.0),
            "C0" to reals(c0), "C1" to reals(c1), "N" to PdfInt(1),
        ))
        val arr = PdfArray(listOf(t2(0.0, 1.0), t2(1.0, 0.0)))
        val f = PdfFunction.parse(arr, nilRefs)!!
        assertEquals(2, f.outputCount)
        val out = f.evaluate(doubleArrayOf(0.25))
        assertEquals(0.25, out[0], 0.001)
        assertEquals(0.75, out[1], 0.001)
    }
}
