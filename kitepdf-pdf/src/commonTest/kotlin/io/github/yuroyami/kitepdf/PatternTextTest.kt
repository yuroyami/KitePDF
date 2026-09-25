package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Text shown in a pattern colour space paints the pattern inside its glyphs (ISO 32000-1, 9.3.6, #88). */
class PatternTextTest {

    private val widths = (listOf(250) + List(32) { 0 } + listOf(600)).joinToString(" ")

    private val redToBlue = "<< /PatternType 2 /Shading << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 300 0] " +
        "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >> >>"

    /** A 10 by 10 cell with a red 5 by 5 square. */
    private val redSquares = TestPdf.Stream(
        "/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 10 /Resources << >>",
        "1 0 0 rg 0 0 5 5 re f".encodeToByteArray(),
    )

    /** A page whose font `F1` draws `A` as a 500-unit square, and whose pattern `P1` is [pattern]. */
    private fun page(content: String, pattern: Any) = TestPdf.onePage(
        content = content,
        resources = "/Font << /F1 5 0 R >> /Pattern << /P1 8 0 R >>",
        mediaBox = "0 0 300 300",
        extra = listOf(
            "<< /Type /Font /Subtype /TrueType /BaseFont /SquareTest /FirstChar 32 /LastChar 65 /Widths [$widths] " +
                "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>",
            "<< /Type /FontDescriptor /FontName /SquareTest /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>",
            TestPdf.Stream("", TestFonts.squareAndSpaceTtf()),
            pattern,
        ),
    )

    /** [minX, minY, maxX, maxY] of [path] after [m], rounded to a millionth. */
    private fun deviceBounds(path: KitePath, m: KiteMatrix): List<Double> {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> { xs += m.transformX(seg.x, seg.y); ys += m.transformY(seg.x, seg.y) }
            is KitePath.Segment.LineTo -> { xs += m.transformX(seg.x, seg.y); ys += m.transformY(seg.x, seg.y) }
            else -> {}
        }
        return listOf(xs.min(), ys.min(), xs.max(), ys.max()).map { round(it * 1e6) / 1e6 }
    }

    /** Every fill painted while a clip with device bounds [bounds] is on the clip stack. */
    private fun fillsInside(calls: List<RecordingCanvas.Call>, bounds: List<Double>): List<RecordingCanvas.Call.Fill> {
        val stack = ArrayList<List<Double>>()
        val out = ArrayList<RecordingCanvas.Call.Fill>()
        for (call in calls) when (call) {
            is RecordingCanvas.Call.PushClip -> stack += deviceBounds(call.path, call.ctm)
            is RecordingCanvas.Call.PopClip -> stack.removeAt(stack.lastIndex)
            is RecordingCanvas.Call.Fill -> if (bounds in stack) out += call
            else -> {}
        }
        return out
    }

    @Test
    fun a_shading_pattern_fills_the_glyphs() {
        val calls = TestPdf.calls(page("/Pattern cs /P1 scn BT /F1 100 Tf 20 120 Td (AA) Tj ET", redToBlue))
        assertTrue(calls.none { it is RecordingCanvas.Call.Glyphs }, "no run in the fallback colour")
        // Two 50-point squares, the second one 60 points along, clip one shading fill.
        val fill = fillsInside(calls, listOf(20.0, 120.0, 130.0, 170.0)).single()
        // The recording canvas paints a shading as one colour over the whole clip, never in the black fallback.
        val covered = deviceBounds(fill.path, fill.ctm)
        assertTrue(covered[0] <= 0.0 && covered[2] >= 300.0, "a shading fill covers the clip, got $covered")
        assertTrue(fill.color.r + fill.color.g + fill.color.b > 0.5, "a gradient colour, got ${fill.color}")
        assertEquals(1, calls.count { it is RecordingCanvas.Call.Fill }, "nothing paints outside the glyphs")
    }

    @Test
    fun a_tiling_pattern_fills_the_glyphs() {
        val calls = TestPdf.calls(page("/Pattern cs /P1 scn BT /F1 100 Tf 20 120 Td (A) Tj ET", redSquares))
        assertTrue(calls.none { it is RecordingCanvas.Call.Glyphs }, "no run in the fallback colour")
        val inside = fillsInside(calls, listOf(20.0, 120.0, 70.0, 170.0))
        assertTrue(inside.isNotEmpty(), "the cells paint")
        assertTrue(inside.all { it.color.r > 0.9 && it.color.g < 0.1 }, "the cells are red")
        assertEquals(inside.size, calls.count { it is RecordingCanvas.Call.Fill }, "nothing paints outside the glyph")
    }

    @Test
    fun a_font_without_outlines_keeps_the_fallback_colour() {
        // The recording canvas has no host outlines, so a font without a program has no shapes to fill.
        val calls = TestPdf.calls(
            TestPdf.onePage(
                content = "/Pattern cs /P1 scn BT /F1 100 Tf 20 120 Td (A) Tj ET",
                resources = "/Font << /F1 5 0 R >> /Pattern << /P1 6 0 R >>",
                extra = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /HostSans /FirstChar 65 /LastChar 65 /Widths [667] >>", redToBlue),
            ),
        )
        assertEquals(1, calls.count { it is RecordingCanvas.Call.Glyphs })
    }
}
