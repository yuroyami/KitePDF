package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A render stops between operators once its cancellation reads true, with the canvas balanced (#188). */
class RenderCancellationTest {

    /** 3,000 small fills inside a clip, then a transparency group form of 200 more. */
    private fun doc(): PdfDocument {
        val page = StringBuilder("q 0 0 500 500 re W n 1 0 0 rg\n")
        repeat(3000) { page.append("${it % 50 * 10} ${it / 50 * 5} 8 4 re f\n") }
        page.append("Q /Fm1 Do")
        val form = StringBuilder("0 0 1 rg\n")
        repeat(200) { form.append("${it % 20 * 10} ${it / 20 * 10} 8 8 re f\n") }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 500] /Resources << /XObject << /Fm1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${page.length} >>\nstream\n$page\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency >> /Resources << >> /Length ${form.length} >>\nstream\n$form\nendstream",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(out.toString().encodeToByteArray())
    }

    private fun RecordingCanvas.fills() = calls.count { it is RecordingCanvas.Call.Fill }

    private fun assertBalanced(canvas: RecordingCanvas) {
        val calls = canvas.calls
        assertEquals(calls.count { it is RecordingCanvas.Call.PushClip }, calls.count { it == RecordingCanvas.Call.PopClip }, "every clip pops")
        assertEquals(calls.count { it is RecordingCanvas.Call.PushGroup }, calls.count { it == RecordingCanvas.Call.PopGroup }, "every group ends")
        assertEquals(RecordingCanvas.Call.EndPage, calls.last(), "the page ends")
    }

    @Test
    fun a_render_that_is_cancelled_stops_between_operators() {
        val canvas = RecordingCanvas()
        doc().pages[0].renderTo(canvas, KiteMatrix.IDENTITY, KiteCancellation { canvas.fills() >= 100 })
        val fills = canvas.fills()
        // The signal is read every 32 operators, and each fill is 2 of them.
        assertTrue(fills in 100..120, "stopped soon after the signal: $fills fills of 3,200")
        assertBalanced(canvas)
    }

    @Test
    fun a_render_cancelled_inside_a_form_closes_the_form() {
        val canvas = RecordingCanvas()
        doc().pages[0].renderTo(canvas, KiteMatrix.IDENTITY, KiteCancellation { canvas.fills() >= 3050 })
        assertTrue(canvas.fills() in 3050..3070, "stopped inside the form: ${canvas.fills()}")
        assertTrue(canvas.calls.any { it is RecordingCanvas.Call.PushGroup }, "the form's group opened")
        assertBalanced(canvas)
    }

    @Test
    fun a_render_nobody_cancels_paints_everything() {
        val canvas = RecordingCanvas()
        doc().pages[0].renderTo(canvas, KiteMatrix.IDENTITY, KiteCancellation { false })
        assertEquals(3200, canvas.fills())
        assertBalanced(canvas)
    }
}
