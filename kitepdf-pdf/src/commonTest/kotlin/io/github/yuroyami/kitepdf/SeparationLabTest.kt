package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
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
        val cs = KiteColorSpace.resolve(
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

    @Test fun separation_none_paints_nothing() {
        // ISO 32000-1, 8.6.6.4: painting in a None separation has no effect on the
        // page. It used to paint opaque white over the artwork under it.
        val cs = KiteColorSpace.resolve(
            PdfArray(listOf(
                PdfName("Separation"), PdfName("None"), PdfName("DeviceCMYK"),
                type2(doubleArrayOf(0.0, 0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0, 1.0)),
            )),
            nil,
        )
        assertTrue(cs.paintsNothing)
        val pdf = TestPdf.onePage(
            content = "1 0 0 rg 0 0 200 200 re f /SN cs /SN CS 1 sc 1 SC 40 40 120 120 re f 40 40 120 120 re S " +
                "BT /F1 12 Tf 50 50 Td (x) Tj ET",
            resources = "/ColorSpace << /SN [/Separation /None /DeviceCMYK 5 0 R] >> /Font << /F1 6 0 R >>",
            extra = listOf(
                "<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 0 0 1] /N 1 /Range [0 1 0 1 0 1 0 1] >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            ),
        )
        val calls = TestPdf.calls(pdf)
        assertEquals(1, calls.count { it is RecordingCanvas.Call.Fill }, "only the red background paints")
        assertEquals(0, calls.count { it is RecordingCanvas.Call.Stroke || it is RecordingCanvas.Call.Glyphs })
    }

    @Test fun devicen_two_components() {
        val cs = KiteColorSpace.resolve(
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
        return io.github.yuroyami.kitepdf.core.parser.PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "FunctionType" to PdfInt(4),
                "Domain" to PdfArray(domain.map { PdfReal(it) }),
                "Range" to PdfArray(range.map { PdfReal(it) }),
                "Length" to PdfInt(bytes.size.toLong()),
            )),
            rawBytes = bytes,
        )
    }

    private fun labD50(): KiteColorSpace = KiteColorSpace.resolve(
        PdfArray(listOf(PdfName("Lab"), PdfDictionary(linkedMapOf(
            "WhitePoint" to reals(0.9642, 1.0, 0.8249),
            "Range" to reals(-100.0, 100.0, -100.0, 100.0),
        )))),
        nil,
    )

    @Test fun a_d50_lab_white_is_white() {
        // ISO 32000-1, 8.6.5.4: the white point is the diffuse white of the space.
        val white = labD50().toRgb(doubleArrayOf(100.0, 0.0, 0.0))
        assertTrue(white.r > 0.99 && white.g > 0.99 && white.b > 0.99, "D50 white must not render cream: $white")
    }

    @Test fun d50_calgray_and_calrgb_whites_are_white() {
        val gray = KiteColorSpace.resolve(
            PdfArray(listOf(PdfName("CalGray"), PdfDictionary(linkedMapOf(
                "WhitePoint" to reals(0.9642, 1.0, 0.8249),
                "Gamma" to PdfReal(2.2),
            )))),
            nil,
        ).toRgb(doubleArrayOf(1.0))
        assertTrue(gray.r > 0.99 && gray.g > 0.99 && gray.b > 0.99, "CalGray: $gray")
        // A matrix whose columns sum to the D50 white, as a well-formed CalRGB's do.
        val rgb = KiteColorSpace.resolve(
            PdfArray(listOf(PdfName("CalRGB"), PdfDictionary(linkedMapOf(
                "WhitePoint" to reals(0.9642, 1.0, 0.8249),
                "Matrix" to reals(0.4361, 0.2225, 0.0139, 0.3851, 0.7169, 0.0971, 0.1431, 0.0606, 0.7141),
            )))),
            nil,
        ).toRgb(doubleArrayOf(1.0, 1.0, 1.0))
        assertTrue(rgb.r > 0.99 && rgb.g > 0.99 && rgb.b > 0.99, "CalRGB: $rgb")
    }

    @Test fun an_unusable_icc_profile_uses_the_declared_alternate() {
        // ISO 32000-1, 8.6.5.5: the alternate is used when the profile cannot be.
        val pdf = TestPdf.onePage(
            content = "/IC cs 0.5 0.2 0.9 sc 0 0 200 200 re f",
            resources = "/ColorSpace << /IC [/ICCBased 5 0 R] >>",
            extra = listOf(TestPdf.stream("not an ICC profile", "/N 3 /Alternate [/Lab << /WhitePoint [0.9505 1 1.089] /Range [0 1 0 1] >>]")),
        )
        val c = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>().single().color
        assertTrue(c.r < 0.05 && c.g < 0.05 && c.b < 0.05, "Lab lightness 0.5 is near black, not DeviceRGB purple: $c")
    }

    @Test fun an_alternate_that_names_itself_still_resolves() {
        val pdf = TestPdf.onePage(
            content = "/IC cs 0.5 0.2 0.9 sc 0 0 200 200 re f",
            resources = "/ColorSpace << /IC [/ICCBased 5 0 R] >>",
            extra = listOf(TestPdf.stream("not an ICC profile", "/N 3 /Alternate [/ICCBased 5 0 R]")),
        )
        assertEquals(1, TestPdf.calls(pdf).count { it is RecordingCanvas.Call.Fill })
    }

    @Test fun lab_white_black_gray() {
        val cs = KiteColorSpace.resolve(
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
