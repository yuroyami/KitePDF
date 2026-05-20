package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.render.Matrix
import com.yuroyami.kitepdf.render.PageRenderer
import com.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderTest {

    @Test
    fun renderer_emits_text_call_for_simple_pdf() {
        val pdf = singlePagePdf("BT /F1 18 Tf 100 700 Td (Hello) Tj ET")
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val texts = canvas.calls.filterIsInstance<RecordingCanvas.Call.Text>()
        assertEquals(1, texts.size)
        assertEquals("Hello", texts[0].text)
        assertEquals(18.0, texts[0].fontSize)
        // Text origin lands at (100, 700) — translated by Td.
        assertEquals(100.0, texts[0].textMatrix.e)
        assertEquals(700.0, texts[0].textMatrix.f)
    }

    @Test
    fun renderer_paints_filled_rectangle() {
        val pdf = singlePagePdf("0.5 0.5 1 rg 10 20 100 200 re f")
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val fills = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(1, fills.size)
        assertEquals(0.5, fills[0].color.r)
        assertEquals(0.5, fills[0].color.g)
        assertEquals(1.0, fills[0].color.b)
        // The rectangle path should have 5 segments: M, L, L, L, Close.
        assertEquals(5, fills[0].path.segments.size)
    }

    @Test
    fun renderer_stroke_emits_separate_call() {
        val pdf = singlePagePdf("2 w 0 0 RG 0 0 m 100 100 l S")
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val strokes = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>()
        assertEquals(1, strokes.size)
        assertEquals(2.0, strokes[0].lineWidth)
    }

    @Test
    fun q_Q_isolates_state_changes() {
        // After Q, fill color should revert. Two filled rectangles should
        // emit two Fill calls with different colors.
        val pdf = singlePagePdf(
            """q 1 0 0 rg 10 10 50 50 re f Q
              |q 0 1 0 rg 70 10 50 50 re f Q""".trimMargin(),
        )
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val fills = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(2, fills.size)
        assertEquals(1.0, fills[0].color.r); assertEquals(0.0, fills[0].color.g)
        assertEquals(0.0, fills[1].color.r); assertEquals(1.0, fills[1].color.g)
    }

    @Test
    fun text_advance_accumulates_across_tj() {
        // Two consecutive Tj calls without repositioning. Both should be drawn
        // at advancing X coordinates.
        val pdf = singlePagePdf("BT /F1 12 Tf 50 700 Td (AB) Tj (CD) Tj ET")
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val texts = canvas.calls.filterIsInstance<RecordingCanvas.Call.Text>()
        assertEquals(2, texts.size)
        assertTrue(
            texts[1].textMatrix.e > texts[0].textMatrix.e,
            "Second show-text should advance past first: ${texts[0].textMatrix.e} → ${texts[1].textMatrix.e}",
        )
    }

    private fun singlePagePdf(contentStream: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        val payload = contentStream.encodeToByteArray()
        write("5 0 obj\n<< /Length ${payload.size} >>\nstream\n")
        buf.append(payload); write("\nendstream\nendobj\n")
        val xref = buf.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
