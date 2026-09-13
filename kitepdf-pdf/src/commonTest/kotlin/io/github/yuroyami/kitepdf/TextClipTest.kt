package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.render.glyphToUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Text clipping (render modes 4 to 7) and the clip a Type 3 `d1` box puts on its glyph. */
class TextClipTest {

    private val widths = (listOf(250) + List(32) { 0 } + listOf(600)).joinToString(" ")

    /** A page with an embedded TrueType font whose `A` is a square and whose space is empty. */
    private fun ttfPage(content: String) = TestPdf.onePage(
        content = content,
        resources = "/Font << /F1 5 0 R >>",
        mediaBox = "0 0 300 300",
        extra = listOf(
            "<< /Type /Font /Subtype /TrueType /BaseFont /SquareTest /FirstChar 32 /LastChar 65 /Widths [$widths] " +
                "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>",
            "<< /Type /FontDescriptor /FontName /SquareTest /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>",
            TestPdf.Stream("", TestFonts.squareAndSpaceTtf()),
        ),
    )

    /** The last clip pushed before the first fill: the text clip in these fixtures. */
    private fun clipBeforeFill(calls: List<RecordingCanvas.Call>): RecordingCanvas.Call.PushClip =
        calls.takeWhile { it !is RecordingCanvas.Call.Fill }.filterIsInstance<RecordingCanvas.Call.PushClip>().last()

    /** [minX, minY, maxX, maxY] of [path] after [m]. */
    private fun deviceBounds(path: KitePath, m: KiteMatrix): DoubleArray {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        fun add(x: Double, y: Double) { xs += m.a * x + m.c * y + m.e; ys += m.b * x + m.d * y + m.f }
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> add(seg.x, seg.y)
            is KitePath.Segment.LineTo -> add(seg.x, seg.y)
            is KitePath.Segment.CurveTo -> add(seg.x3, seg.y3)
            is KitePath.Segment.QuadTo -> add(seg.x2, seg.y2)
            KitePath.Segment.Close -> {}
        }
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    @Test
    fun a_space_in_clipped_text_adds_nothing_to_the_clip() {
        val clip = clipBeforeFill(TestPdf.calls(ttfPage("BT /F1 100 Tf 7 Tr 20 120 Td (A A) Tj ET 1 0 0 rg 0 0 300 300 re f")))
        assertEquals(2, clip.path.segments.count { it is KitePath.Segment.MoveTo }, "two squares, and no box for the space")
    }

    @Test
    fun a_matrix_change_before_et_does_not_move_the_text_clip() {
        fun bounds(content: String) = clipBeforeFill(TestPdf.calls(ttfPage(content))).let { deviceBounds(it.path, it.ctm) }
        val plain = bounds("BT /F1 100 Tf 7 Tr 20 120 Td (A) Tj ET 1 0 0 rg 0 0 300 300 re f")
        val moved = bounds("BT /F1 100 Tf 7 Tr 20 120 Td (A) Tj 2 0 0 2 0 0 cm ET 1 0 0 rg 0 0 300 300 re f")
        for (i in 0..3) assertEquals(plain[i], moved[i], 1e-6, "edge $i of the clip")
        assertEquals(50.0, plain[2] - plain[0], 1e-6, "the 500-unit square at 100pt")
    }

    @Test
    fun a_type3_glyph_is_clipped_to_its_d1_box() {
        val calls = TestPdf.calls(
            TestPdf.onePage(
                content = "BT /T3 100 Tf 10 10 Td (a) Tj ET",
                resources = "/Font << /T3 5 0 R >>",
                mediaBox = "0 0 300 300",
                extra = listOf(
                    "<< /Type /Font /Subtype /Type3 /FontMatrix [0.001 0 0 0.001 0 0] /FontBBox [0 0 1000 1000] " +
                        "/Encoding << /Differences [97 /Sq] >> /FirstChar 97 /LastChar 97 /Widths [1000] /CharProcs << /Sq 6 0 R >> >>",
                    TestPdf.stream("1000 0 100 100 400 400 d1 0 0 1000 1000 re f"),
                ),
            ),
        )
        val clip = clipBeforeFill(calls)
        val b = deviceBounds(clip.path, clip.ctm)
        assertEquals(30.0, b[2] - b[0], 1e-6, "the 300-unit glyph box at 100pt")
        val fillAt = calls.indexOfFirst { it is RecordingCanvas.Call.Fill }
        assertTrue(calls.drop(fillAt).any { it is RecordingCanvas.Call.PopClip }, "and the clip ends with the glyph")
    }

    @Test
    fun a_stray_d1_in_page_content_clips_nothing() {
        val calls = TestPdf.calls(TestPdf.onePage("0 0 0 0 10 10 d1 1 0 0 rg 0 0 200 200 re f"))
        val fillAt = calls.indexOfFirst { it is RecordingCanvas.Call.Fill }
        val clips = calls.subList(0, fillAt).filterIsInstance<RecordingCanvas.Call.PushClip>()
        assertTrue(clips.none { c -> deviceBounds(c.path, c.ctm).let { it[2] - it[0] < 20.0 } }, "no 10pt clip from the stray d1")
    }

    @Test
    fun a_glyph_offset_moves_its_stroke_and_clip_outline() {
        val glyph = TextGlyph(
            byteOffset = 0, byteCount = 1, gid = 1, text = "A", advanceWidth = 600.0,
            outline = null, isWordSpace = false, xOffset = 100.0, yOffset = 50.0,
        )
        val m = glyphToUser(KiteMatrix.IDENTITY, penX = 10.0, glyph = glyph, unitScale = 0.01)
        assertEquals(11.0, m.e, 1e-9, "the pen plus 100 font units at a hundredth")
        assertEquals(0.5, m.f, 1e-9)
        assertEquals(0.01, m.a, 1e-9)
    }
}
