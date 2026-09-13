package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Soft masks on groups and under a later `cm`, shading boxes and backgrounds, and pattern alpha. */
class MaskPatternShadingTest {

    /** Records every soft mask a render applies, with the matrix it was given. */
    private class MaskRecordingCanvas(val inner: RecordingCanvas = RecordingCanvas()) : KiteCanvas by inner {
        val maskCtms = ArrayList<KiteMatrix>()
        override fun applySoftMask(
            kind: SoftMask.Kind,
            maskBBox: KiteRectangle,
            maskCtm: KiteMatrix,
            render: () -> Unit,
            renderMask: (KiteCanvas) -> Unit,
        ) {
            maskCtms += maskCtm
            render()
        }
    }

    private fun maskedRender(pdf: ByteArray): MaskRecordingCanvas =
        MaskRecordingCanvas().also { PdfDocument.open(pdf).pages[0].renderTo(it, KiteMatrix.IDENTITY) }

    /** Object 5 is a luminosity soft mask whose group, object 6, is a flat half grey. */
    private val softMask = listOf(
        "<< /Type /ExtGState /SMask << /Type /Mask /S /Luminosity /G 6 0 R >> >>",
        TestPdf.stream(
            "0.5 g 0 0 200 200 re f",
            "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceRGB >>",
        ),
    )

    @Test
    fun a_masked_transparency_group_takes_the_mask_once() {
        val canvas = maskedRender(
            TestPdf.onePage(
                content = "q /GS0 gs /Fx Do Q",
                resources = "/ExtGState << /GS0 5 0 R >> /XObject << /Fx 7 0 R >>",
                extra = softMask + TestPdf.stream(
                    "1 0 0 rg 20 20 100 100 re f 60 60 100 100 re f",
                    "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceRGB /I true >>",
                ),
            ),
        )
        assertEquals(1, canvas.maskCtms.size, "one mask on the group's result, not one per square")
        assertEquals(2, canvas.inner.calls.count { it is RecordingCanvas.Call.Fill })
    }

    @Test
    fun a_cm_after_the_mask_moves_the_content_and_not_the_mask() {
        val canvas = maskedRender(
            TestPdf.onePage(
                content = "q /GS0 gs 1 0 0 1 100 0 cm 1 0 0 rg 0 0 50 50 re f Q",
                resources = "/ExtGState << /GS0 5 0 R >>",
                extra = softMask,
            ),
        )
        val fill = canvas.inner.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(100.0, fill.ctm.e - canvas.maskCtms.single().e, 1e-9, "the mask keeps the matrix it was set under")
    }

    @Test
    fun sh_is_clipped_to_the_shading_box() {
        val calls = TestPdf.calls(
            TestPdf.onePage(
                "/Sh1 sh",
                resources = "/Shading << /Sh1 5 0 R >>",
                extra = listOf(
                    "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] " +
                        "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> /BBox [0 0 50 50] >>",
                ),
            ),
        )
        val fillAt = calls.indexOfFirst { it is RecordingCanvas.Call.Fill }
        val clip = calls[fillAt - 1]
        assertTrue(clip is RecordingCanvas.Call.PushClip, "the box clips the shading (got $calls)")
        assertEquals(50.0, clip.path.segments.mapNotNull { (it as? KitePath.Segment.LineTo)?.x }.max(), 1e-9)
    }

    @Test
    fun a_shading_pattern_paints_its_background_first_and_uses_its_own_alpha() {
        val calls = TestPdf.calls(
            TestPdf.onePage(
                content = "/Pattern cs /P1 scn 0 0 200 200 re f",
                resources = "/Pattern << /P1 5 0 R >>",
                extra = listOf(
                    "<< /PatternType 2 /Shading 6 0 R /ExtGState << /ca 0.5 >> >>",
                    "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] " +
                        "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> /Background [0 1 0] >>",
                ),
            ),
        )
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(2, fills.size, "the background, then the shading")
        assertEquals(RgbColor(0.0, 1.0, 0.0), fills[0].color, "the background comes first")
        assertTrue(fills.all { it.alpha == 0.5 }, "the pattern's own /ca applies (got ${fills.map { it.alpha }})")
    }

    @Test
    fun a_tiling_fill_under_an_alpha_composites_once() {
        val calls = TestPdf.calls(
            TestPdf.onePage(
                content = "q /GS1 gs /Pattern cs /P1 scn 0 0 200 200 re f Q",
                resources = "/Pattern << /P1 5 0 R >> /ExtGState << /GS1 6 0 R >>",
                extra = listOf(
                    TestPdf.stream(
                        "1 0 0 rg 0 0 40 40 re f",
                        "/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 40 40] /XStep 40 /YStep 40 /Resources << >>",
                    ),
                    "<< /Type /ExtGState /ca 0.5 >>",
                ),
            ),
        )
        val group = calls.filterIsInstance<RecordingCanvas.Call.PushGroup>().single()
        assertEquals(0.5, group.alpha, 1e-9)
        assertTrue(
            calls.filterIsInstance<RecordingCanvas.Call.Fill>().all { it.alpha == 1.0 },
            "the cells paint at full alpha inside the group",
        )
    }
}
