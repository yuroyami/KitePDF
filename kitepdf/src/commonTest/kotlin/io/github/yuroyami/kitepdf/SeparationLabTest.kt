package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.render.ColorSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Separation/DeviceN tint transforms and Lab → sRGB conversion. */
class SeparationLabTest {

    private val nil = IndirectResolver { null }
    private fun reals(vararg v: Double) = PdfArray(v.map { PdfReal(it) })

    private fun type2(c0: DoubleArray, c1: DoubleArray): PdfObject = PdfDictionary(linkedMapOf(
        "FunctionType" to PdfInt(2),
        "Domain" to reals(0.0, 1.0),
        "C0" to PdfArray(c0.map { PdfReal(it) }),
        "C1" to PdfArray(c1.map { PdfReal(it) }),
        "N" to PdfInt(1),
    ))

    @Test fun separation_runs_tint_transform_through_alternate() {
        // /Separation /Spot /DeviceCMYK { 0,0,0,0 → 0,1,1,0 } : full tint = red (M+Y).
        val cs = ColorSpace.resolve(
            PdfArray(listOf(
                PdfName("Separation"), PdfName("Spot"), PdfName("DeviceCMYK"),
                type2(doubleArrayOf(0.0, 0.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 1.0, 0.0)),
            )),
            nil,
        )
        assertEquals(1, cs.componentCount)
        val rgb = cs.toRgb(doubleArrayOf(1.0))
        assertTrue(rgb.r > 0.5, "expected reddish, got $rgb")
        assertTrue(rgb.r > rgb.g && rgb.r > rgb.b, "red channel should dominate: $rgb")
        // Zero tint = paper white-ish (CMYK 0,0,0,0).
        val none = cs.toRgb(doubleArrayOf(0.0))
        assertTrue(none.r > 0.9 && none.g > 0.9 && none.b > 0.9, "zero tint should be near white: $none")
    }

    @Test fun separation_none_paints_white() {
        val cs = ColorSpace.resolve(
            PdfArray(listOf(
                PdfName("Separation"), PdfName("None"), PdfName("DeviceCMYK"),
                type2(doubleArrayOf(0.0, 0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0, 1.0)),
            )),
            nil,
        )
        val rgb = cs.toRgb(doubleArrayOf(1.0))
        assertEquals(1.0, rgb.r); assertEquals(1.0, rgb.g); assertEquals(1.0, rgb.b)
    }

    @Test fun devicen_two_components() {
        val cs = ColorSpace.resolve(
            PdfArray(listOf(
                PdfName("DeviceN"),
                PdfArray(listOf(PdfName("Cyan"), PdfName("Magenta"))),
                PdfName("DeviceRGB"),
                // 2-in 3-out PostScript: map [c m] → [1-c 1-m 0]
                psFn("{ 1 exch sub exch 1 exch sub exch 0 }", inputs = 2, outputs = 3),
            )),
            nil,
        )
        assertEquals(2, cs.componentCount)
        val rgb = cs.toRgb(doubleArrayOf(0.0, 0.0))
        assertTrue(rgb.r > 0.9 && rgb.g > 0.9, "0,0 → white-ish: $rgb")
    }

    private fun psFn(program: String, inputs: Int, outputs: Int): PdfObject {
        val bytes = program.encodeToByteArray()
        val domain = DoubleArray(inputs * 2) { if (it % 2 == 0) 0.0 else 1.0 }
        val range = DoubleArray(outputs * 2) { if (it % 2 == 0) 0.0 else 1.0 }
        return io.github.yuroyami.kitepdf.parser.PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "FunctionType" to PdfInt(4),
                "Domain" to PdfArray(domain.map { PdfReal(it) }),
                "Range" to PdfArray(range.map { PdfReal(it) }),
                "Length" to PdfInt(bytes.size.toLong()),
            )),
            rawBytes = bytes,
        )
    }

    @Test fun lab_white_black_gray() {
        val cs = ColorSpace.resolve(
            PdfArray(listOf(PdfName("Lab"), PdfDictionary(linkedMapOf(
                "WhitePoint" to reals(0.9505, 1.0, 1.089),
                "Range" to reals(-128.0, 127.0, -128.0, 127.0),
            )))),
            nil,
        )
        assertEquals(3, cs.componentCount)
        val white = cs.toRgb(doubleArrayOf(100.0, 0.0, 0.0))
        assertTrue(white.r > 0.9 && white.g > 0.9 && white.b > 0.9, "L=100 → white: $white")
        val black = cs.toRgb(doubleArrayOf(0.0, 0.0, 0.0))
        assertTrue(black.r < 0.05 && black.g < 0.05 && black.b < 0.05, "L=0 → black: $black")
        val gray = cs.toRgb(doubleArrayOf(53.0, 0.0, 0.0)) // ~mid lightness
        assertTrue(gray.r in 0.4..0.65 && kotlin.math.abs(gray.r - gray.g) < 0.02, "neutral gray: $gray")
    }
}
