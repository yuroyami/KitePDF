package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.PageRenderer
import io.github.yuroyami.kitepdf.render.RecordingCanvas
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

    @Test
    fun line_move_is_scaled_by_text_matrix() {
        // Font size baked into Tm (Tf 1, Tm scale 20): a `Td 0 -1` must move the line
        // 20 user-units down (scaled by Tm), not 1. Regression for the concat-order bug
        // that collapsed line spacing on size-in-Tm PDFs (e.g. Android-generated reports).
        val pdf = singlePagePdf("BT /F1 1 Tf 20 0 0 20 100 700 Tm (A) Tj 0 -1 Td (B) Tj ET")
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val texts = canvas.calls.filterIsInstance<RecordingCanvas.Call.Text>()
        assertEquals(2, texts.size)
        assertEquals(700.0, texts[0].textMatrix.f, 1e-6)
        assertEquals(680.0, texts[1].textMatrix.f, 1e-6) // 700 - (1 × 20), NOT 700 - 1
        assertEquals(100.0, texts[1].textMatrix.e, 1e-6)
    }

    @Test
    fun run_advance_is_scaled_by_text_matrix() {
        // Same size-in-Tm setup: the advance between two Tj runs on a line is in text
        // space, so it must be scaled by Tm (×10 here) → a gap of ~13 units, not ~1.3.
        val pdf = singlePagePdf("BT /F1 1 Tf 10 0 0 10 50 500 Tm (AB) Tj (CD) Tj ET")
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val texts = canvas.calls.filterIsInstance<RecordingCanvas.Call.Text>()
        assertEquals(2, texts.size)
        assertTrue(
            texts[1].textMatrix.e - texts[0].textMatrix.e > 10.0,
            "run advance not scaled by Tm: ${texts[0].textMatrix.e} -> ${texts[1].textMatrix.e}",
        )
    }

    @Test
    fun cs_selects_color_space_so_scn_reads_all_components() {
        // `CS` must select the stroke colour space so SCN reads all components. Without a cs/CS
        // handler the space stayed DeviceGray and `1 0 0 SCN` was read as gray(1)=white — the
        // iOS ECG-grid-rendered-white bug (CoreGraphics uses ICCBased-RGB + CS/SCN, not rg).
        val pdf = singlePagePdf("/DeviceRGB CS 1 0 0 SCN 0 0 m 100 100 l S")
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val strokes = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>()
        assertEquals(1, strokes.size)
        assertEquals(1.0, strokes[0].color.r, 1e-6)
        assertEquals(0.0, strokes[0].color.g, 1e-6)
        assertEquals(0.0, strokes[0].color.b, 1e-6) // would be (1,1,1) white without the CS handler
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
