package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stroked and clipped text (render modes 1, 2 and 4 to 7) in a font without an embedded program (#85). */
class OutlinedTextTest {

    /** A canvas whose host face draws every character as a 600 by 700 box. */
    private class HostOutlineCanvas(val inner: RecordingCanvas = RecordingCanvas()) : KiteCanvas by inner {
        override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath =
            KitePath.Builder().apply { rectangle(0.0, 0.0, 600.0, 700.0) }.build()
    }

    private fun page(content: String) = TestPdf.onePage(
        content = content,
        resources = "/Font << /F1 5 0 R >>",
        mediaBox = "0 0 300 300",
        // A font that names no standard font has no program at all, so the host face stands in.
        // It keeps the Helvetica widths of space, H and I: 278, 722 and 278.
        extra = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /HostSans /FirstChar 32 /LastChar 73 /Widths [278 ${"0 ".repeat(39)}722 278] >>"),
    )

    private fun render(content: String, canvas: KiteCanvas) =
        PdfDocument.open(page(content)).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)

    /** [left, bottom, right, top] of [path] after [m]. */
    private fun bounds(path: KitePath, m: KiteMatrix): List<Double> {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> { xs += m.transformX(seg.x, seg.y); ys += m.transformY(seg.x, seg.y) }
            is KitePath.Segment.LineTo -> { xs += m.transformX(seg.x, seg.y); ys += m.transformY(seg.x, seg.y) }
            else -> {}
        }
        return listOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun assertBox(expected: List<Double>, actual: List<Double>) {
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-9, "box $actual")
    }

    @Test
    fun stroke_mode_strokes_the_host_outline_of_each_glyph() {
        // ISO 32000-1, 9.3.6: mode 1 strokes, whether or not the font has a program.
        val canvas = HostOutlineCanvas()
        render("BT /F1 100 Tf 1 Tr 2 w 1 0 0 RG 20 50 Td (HI) Tj ET", canvas)
        val strokes = canvas.inner.calls.filterIsInstance<RecordingCanvas.Call.Stroke>()
        assertEquals(2, strokes.size)
        assertTrue(canvas.inner.calls.none { it is RecordingCanvas.Call.Glyphs }, "stroke mode fills nothing")
        assertEquals(RgbColor(1.0, 0.0, 0.0), strokes[0].color)
        assertEquals(2.0, strokes[0].lineWidth, 1e-9)
        // The box is 60 by 70 at 100 points, and I starts one Helvetica H advance (722) later.
        assertBox(listOf(20.0, 50.0, 80.0, 120.0), bounds(strokes[0].path, strokes[0].ctm))
        assertBox(listOf(92.2, 50.0, 152.2, 120.0), bounds(strokes[1].path, strokes[1].ctm))
    }

    @Test
    fun fill_then_stroke_does_both() {
        val canvas = HostOutlineCanvas()
        render("BT /F1 100 Tf 2 Tr 0 0 1 rg 1 0 0 RG 20 50 Td (H) Tj ET", canvas)
        assertEquals(RgbColor(0.0, 0.0, 1.0), canvas.inner.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single().color)
        assertEquals(RgbColor(1.0, 0.0, 0.0), canvas.inner.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single().color)
    }

    @Test
    fun a_blank_glyph_strokes_nothing() {
        val canvas = HostOutlineCanvas()
        render("BT /F1 100 Tf 1 Tr 20 50 Td (H I) Tj ET", canvas)
        assertEquals(2, canvas.inner.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().size)
    }

    @Test
    fun without_host_outlines_stroked_text_fills_in_the_stroke_colour() {
        // A canvas with no host outlines still shows the run, rather than nothing.
        val canvas = RecordingCanvas()
        render("BT /F1 100 Tf 1 Tr 0 0 1 rg 1 0 0 RG 20 50 Td (H) Tj ET", canvas)
        val run = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
        assertEquals("H", run.text)
        assertEquals(RgbColor(1.0, 0.0, 0.0), run.color)
    }

    @Test
    fun a_text_clip_uses_the_host_outline() {
        // Without host outlines the clip would be the em box, from y 30 to 130.
        val canvas = HostOutlineCanvas()
        render("BT /F1 100 Tf 7 Tr 20 50 Td (H) Tj ET 0 0 300 300 re f", canvas)
        val clip = canvas.inner.calls.takeWhile { it !is RecordingCanvas.Call.Fill }
            .filterIsInstance<RecordingCanvas.Call.PushClip>().last()
        assertBox(listOf(20.0, 50.0, 80.0, 120.0), bounds(clip.path, clip.ctm))
    }
}
