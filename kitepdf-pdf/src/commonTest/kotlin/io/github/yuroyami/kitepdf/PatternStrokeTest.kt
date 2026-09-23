package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A stroke in a pattern colour paints the pattern inside the stroke: the path widened
 * by the line width, with its dashes, caps and joins (ISO 32000-1, 8.7.3.1, #284).
 */
class PatternStrokeTest {

    private val redToBlue = "<< /PatternType 2 /Shading << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] " +
        "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >> >>"

    /** A 10 by 10 cell with a red 5 by 5 square. */
    private val redSquares = TestPdf.Stream(
        "/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 10 /Resources << >>",
        "1 0 0 rg 0 0 5 5 re f".encodeToByteArray(),
    )

    private fun calls(content: String, pattern: Any = redToBlue) = TestPdf.calls(
        TestPdf.onePage(content = content, resources = "/Pattern << /P1 5 0 R >>", mediaBox = "0 0 200 200", extra = listOf(pattern)),
    )

    /** The clip that the pattern paints into, the first one pushed. Its path is in user space. */
    private fun region(calls: List<RecordingCanvas.Call>): KitePath =
        calls.filterIsInstance<RecordingCanvas.Call.PushClip>().first().path

    @Test
    fun a_closed_path_paints_a_band_and_not_its_inside() {
        val calls = calls("/Pattern CS /P1 SCN 10 w 50 50 100 100 re S")
        val band = region(calls)
        assertTrue(band.contains(50.0, 100.0))
        assertTrue(band.contains(46.0, 100.0))
        assertTrue(band.contains(54.0, 100.0))
        assertFalse(band.contains(56.0, 100.0))
        assertFalse(band.contains(100.0, 100.0), "the inside of the square stays empty")
        assertTrue(calls.none { it is RecordingCanvas.Call.Stroke }, "no stroke in the fallback colour")
    }

    @Test
    fun an_open_line_paints_its_width() {
        val band = region(calls("/Pattern CS /P1 SCN 10 w 20 100 m 180 100 l S"))
        assertTrue(band.contains(100.0, 104.0))
        assertFalse(band.contains(100.0, 106.0))
        assertFalse(band.contains(15.0, 100.0), "a butt cap ends at the end point")
    }

    @Test
    fun the_stroke_keeps_its_dashes_and_caps() {
        val band = region(calls("/Pattern CS /P1 SCN 10 w 1 J [20 20] 0 d 20 100 m 180 100 l S"))
        assertTrue(band.contains(30.0, 100.0))
        assertFalse(band.contains(50.0, 100.0))
        assertTrue(band.contains(16.0, 100.0), "a round cap reaches past the end point")
    }

    @Test
    fun a_tiling_pattern_paints_inside_the_stroke() {
        val calls = calls("/Pattern CS /P1 SCN 10 w 50 50 100 100 re S", redSquares)
        val band = region(calls)
        assertTrue(band.contains(50.0, 100.0))
        assertFalse(band.contains(100.0, 100.0))
        assertTrue(calls.any { it is RecordingCanvas.Call.Fill }, "the cells paint")
    }

    @Test
    fun stroked_text_paints_the_pattern_along_the_glyph_edges() {
        val widths = (listOf(250) + List(32) { 0 } + listOf(600)).joinToString(" ")
        val calls = TestPdf.calls(
            TestPdf.onePage(
                content = "/Pattern CS /P1 SCN 2 w BT 1 Tr /F1 100 Tf 20 120 Td (A) Tj ET",
                resources = "/Font << /F1 5 0 R >> /Pattern << /P1 8 0 R >>",
                mediaBox = "0 0 200 200",
                extra = listOf(
                    "<< /Type /Font /Subtype /TrueType /BaseFont /SquareTest /FirstChar 32 /LastChar 65 /Widths [$widths] " +
                        "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>",
                    "<< /Type /FontDescriptor /FontName /SquareTest /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                        "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>",
                    TestPdf.Stream("", TestFonts.squareAndSpaceTtf()),
                    redToBlue,
                ),
            ),
        )
        // The glyph is a 50-point square from (20, 120), stroked 2 points wide.
        val band = region(calls)
        assertTrue(band.contains(20.5, 145.0))
        assertFalse(band.contains(45.0, 145.0), "the inside of the glyph stays empty")
        assertEquals(0, calls.count { it is RecordingCanvas.Call.Stroke }, "no stroke in the fallback colour")
    }

    /** True when (x, y) is inside the path by the nonzero rule. Curves are split into short lines. */
    private fun KitePath.contains(x: Double, y: Double): Boolean {
        var winding = 0
        var cx = 0.0
        var cy = 0.0
        var sx = 0.0
        var sy = 0.0
        fun edge(x0: Double, y0: Double, x1: Double, y1: Double) {
            if (y0 <= y && y1 > y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) > 0) winding++
            if (y0 > y && y1 <= y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) < 0) winding--
        }
        for (s in segments) when (s) {
            is KitePath.Segment.MoveTo -> { edge(cx, cy, sx, sy); cx = s.x; cy = s.y; sx = s.x; sy = s.y }
            is KitePath.Segment.LineTo -> { edge(cx, cy, s.x, s.y); cx = s.x; cy = s.y }
            is KitePath.Segment.CurveTo -> {
                var px = cx
                var py = cy
                for (i in 1..64) {
                    val t = i / 64.0
                    val u = 1 - t
                    val nx = u * u * u * cx + 3 * u * u * t * s.x1 + 3 * u * t * t * s.x2 + t * t * t * s.x3
                    val ny = u * u * u * cy + 3 * u * u * t * s.y1 + 3 * u * t * t * s.y2 + t * t * t * s.y3
                    edge(px, py, nx, ny)
                    px = nx; py = ny
                }
                cx = s.x3; cy = s.y3
            }
            is KitePath.Segment.QuadTo -> { edge(cx, cy, s.x2, s.y2); cx = s.x2; cy = s.y2 }
            KitePath.Segment.Close -> { edge(cx, cy, sx, sy); cx = sx; cy = sy }
        }
        edge(cx, cy, sx, sy)
        return winding != 0
    }
}
