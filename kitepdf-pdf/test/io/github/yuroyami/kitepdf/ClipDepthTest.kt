package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A content stream that pushes clips without end stops at the renderer's cap, and the page still paints (#335). */
class ClipDepthTest {

    @Test
    fun clips_past_the_cap_are_dropped_and_the_page_still_paints() {
        val content = "0 0 200 200 re W n\n".repeat(20_000) + "1 0 0 rg 10 10 5 5 re f"
        val canvas = RecordingCanvas()
        PdfDocument.open(RawPdf.page(content = content.encodeToByteArray())).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val pushes = canvas.calls.count { it is RecordingCanvas.Call.PushClip }
        assertEquals(1024, pushes, "one push per clip up to the renderer's cap of 1024")
        assertTrue(canvas.calls.any { it is RecordingCanvas.Call.Fill }, "the fill after the clips still paints")
    }

    @Test
    fun a_page_below_the_cap_keeps_every_clip() {
        val content = "q 0 0 200 200 re W n\n".repeat(50) + "1 0 0 rg 10 10 5 5 re f" + "\nQ".repeat(50)
        val canvas = RecordingCanvas()
        PdfDocument.open(RawPdf.page(content = content.encodeToByteArray())).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(50, canvas.calls.count { it is RecordingCanvas.Call.PushClip })
        assertEquals(50, canvas.calls.count { it is RecordingCanvas.Call.PopClip }, "every Q pops its clip")
    }
}
