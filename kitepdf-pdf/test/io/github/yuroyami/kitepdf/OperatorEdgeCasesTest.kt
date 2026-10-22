package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Operators, fonts and paints at the edges of what content streams send. */
class OperatorEdgeCasesTest {

    private fun fills(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>()
    private fun strokes(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Stroke>()
    private fun glyphs(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Glyphs>()

    @Test
    fun a_cap_join_or_miter_operator_with_no_operand_changes_nothing() {
        val stroke = strokes(TestPdf.onePage("1 J 2 j 7 M J j M 0 0 m 10 0 l S")).single()
        assertEquals(1, stroke.lineCap)
        assertEquals(2, stroke.lineJoin)
        assertEquals(7.0, stroke.miterLimit)
    }

    @Test
    fun a_curve_after_a_rectangle_starts_at_the_rectangles_start() {
        // v takes the current point as its first control point; after re that is the start.
        val curve = strokes(TestPdf.onePage("0 0 10 10 re 20 20 30 30 v S")).single()
            .path.segments.filterIsInstance<KitePath.Segment.CurveTo>().single()
        assertEquals(0.0, curve.x1)
        assertEquals(0.0, curve.y1)
    }

    @Test
    fun a_pattern_space_with_no_pattern_paints_nothing() {
        val calls = TestPdf.calls(TestPdf.onePage("/Pattern cs 0 0 10 10 re f /Pattern CS 0 0 m 10 0 l S"))
        assertEquals(0, calls.count { it is RecordingCanvas.Call.Fill || it is RecordingCanvas.Call.Stroke })
    }

    @Test
    fun text_in_a_missing_font_paints_in_the_substitute() {
        val runs = glyphs(TestPdf.onePage("BT /F9 24 Tf 10 10 Td (AB) Tj (C) Tj ET"))
        assertEquals(listOf("AB", "C"), runs.map { it.text })
        // Helvetica A and B are 667 units each, so C starts 24 x 1.334 further on.
        assertEquals(10.0 + 24.0 * 1.334, runs[1].textToDevice.e, 1e-6)
    }

    private fun type3(mode: Int) = TestPdf.onePage(
        content = "BT /T3 200 Tf $mode Tr 50 50 Td (a) Tj ET",
        resources = "/Font << /T3 5 0 R >>",
        mediaBox = "0 0 300 300",
        extra = listOf(
            "<< /Type /Font /Subtype /Type3 /FontMatrix [0.001 0 0 0.001 0 0] /FontBBox [0 0 700 700] " +
                "/Encoding << /Differences [97 /Sq] >> /FirstChar 97 /LastChar 97 /Widths [1000] /CharProcs << /Sq 6 0 R >> >>",
            TestPdf.stream("700 0 0 0 700 700 d1 0 0 700 700 re f"),
        ),
    )

    @Test
    fun invisible_type3_text_paints_nothing() {
        assertEquals(0, fills(type3(3)).size)
        assertEquals(0, fills(type3(7)).size)
        assertEquals(1, fills(type3(0)).size)
    }

    @Test
    fun a_type3_font_with_a_named_encoding_draws() {
        val pdf = TestPdf.onePage(
            content = "BT /T3 100 Tf 10 10 Td (a) Tj ET",
            resources = "/Font << /T3 5 0 R >>",
            extra = listOf(
                "<< /Type /Font /Subtype /Type3 /FontMatrix [0.001 0 0 0.001 0 0] /FontBBox [0 0 500 500] " +
                    "/Encoding /WinAnsiEncoding /FirstChar 97 /LastChar 97 /Widths [500] /CharProcs << /a 6 0 R >> >>",
                TestPdf.stream("500 0 d0 0 0 500 500 re f"),
            ),
        )
        assertEquals(1, fills(pdf).size)
    }

    @Test
    fun a_degenerate_subpath_under_square_caps_paints_nothing() {
        assertEquals(0, strokes(TestPdf.onePage("12 w 2 J 50 50 m 50 50 l S")).size)
    }

    @Test
    fun a_degenerate_subpath_under_round_caps_paints_one_dot() {
        val segments = strokes(TestPdf.onePage("12 w 1 J 50 50 m h S")).single().path.segments
        assertEquals(listOf(KitePath.Segment.MoveTo(50.0, 50.0), KitePath.Segment.LineTo(50.0, 50.0)), segments)
    }

    @Test
    fun a_tiling_cell_is_clipped_to_its_box() {
        val calls = TestPdf.calls(TestPdf.onePage(
            content = "q /Pattern cs /P1 scn 0 0 200 200 re f Q",
            resources = "/Pattern << /P1 5 0 R >>",
            extra = listOf(TestPdf.stream(
                "1 0 0 rg 0 0 40 40 re f",
                "/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 20 20] /XStep 40 /YStep 40 /Resources << >>",
            )),
        ))
        val cellFills = calls.count { it is RecordingCanvas.Call.Fill }
        val boxClips = calls.count { c ->
            c is RecordingCanvas.Call.PushClip &&
                c.path.segments.mapNotNull { (it as? KitePath.Segment.LineTo)?.x }.maxOrNull() == 20.0
        }
        assertTrue(cellFills > 0)
        assertEquals(cellFills, boxClips, "every cell paints under a clip to its 20 x 20 box")
    }

    @Test
    fun a_dash_set_through_a_graphics_state_dictionary_applies() {
        val stroke = strokes(TestPdf.onePage(
            content = "0 0 0 RG 4 w /GD gs 20 150 m 180 150 l S",
            resources = "/ExtGState << /GD 5 0 R >>",
            extra = listOf("<< /Type /ExtGState /D [[16 10] 0] >>"),
        )).single()
        assertEquals(listOf(16.0, 10.0), stroke.dashArray)
    }

    @Test
    fun an_inline_image_with_the_indexed_abbreviation_uses_its_palette() {
        val image = TestPdf.calls(TestPdf.onePage(
            "q 10 0 0 10 0 0 cm BI /W 1 /H 1 /BPC 8 /CS [/I /RGB 2 <FF000000FF000000FF>] /F /AHx ID 01> EI Q",
        )).filterIsInstance<RecordingCanvas.Call.Image>().single()
        val rgba = image.image.toRgbaBytes()!!
        assertEquals(listOf(0, 255, 0), (0..2).map { rgba[it].toInt() and 0xFF })
    }

    /** Counts the soft masks a render applies. */
    private class MaskCountingCanvas(private val inner: RecordingCanvas = RecordingCanvas()) : KiteCanvas by inner {
        var masked = 0
        override fun applySoftMask(
            kind: SoftMask.Kind,
            maskBBox: KiteRectangle,
            maskCtm: KiteMatrix,
            render: () -> Unit,
            renderMask: (KiteCanvas) -> Unit,
        ) {
            masked++
            render()
        }
    }

    @Test
    fun the_sh_operator_paints_under_the_soft_mask() {
        val pdf = TestPdf.onePage(
            content = "q /GS0 gs /Sh0 sh Q",
            resources = "/ExtGState << /GS0 << /Type /ExtGState /SMask << /Type /Mask /S /Luminosity /G 5 0 R >> >> >> " +
                "/Shading << /Sh0 << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] /Extend [true true] " +
                "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >> >>",
            extra = listOf(TestPdf.stream(
                "1 g 40 40 60 60 re f",
                "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceGray >>",
            )),
        )
        val canvas = MaskCountingCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(1, canvas.masked)
    }
}
