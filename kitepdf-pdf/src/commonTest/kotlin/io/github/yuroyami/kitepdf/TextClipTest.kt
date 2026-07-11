package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-41: text render modes 4..7 accumulate glyph shapes into a clip pushed at
 * ET; mode 7 paints nothing itself; mode 0 text pushes no clip (regression
 * guard). Structural assertions over the recorded canvas; the pixel-level
 * proof against mutool lives in the differential TextClipOracleTest.
 */
class TextClipTest {

    private fun pdf(textOps: String): ByteArray {
        val content = "$textOps\n1 0 0 rg 0 0 612 792 re f"
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        sb.append("%PDF-1.4\n")
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        add(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
        )
        add("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        add("5 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun render(textOps: String): RecordingCanvas {
        val canvas = RecordingCanvas()
        KitePDF.open(pdf(textOps)).pages[0].renderTo(canvas, Matrix.IDENTITY)
        return canvas
    }

    @Test
    fun mode7_pushes_a_clip_at_ET_and_paints_no_text() {
        val canvas = render("BT /F1 100 Tf 7 Tr 100 300 Td (AB) Tj ET")
        val calls = canvas.calls
        assertEquals(0, calls.count { it is RecordingCanvas.Call.Glyphs }, "mode 7 never paints glyphs")

        val clipIdx = calls.indexOfFirst { it is RecordingCanvas.Call.PushClip }
        val fillIdx = calls.indexOfLast { it is RecordingCanvas.Call.Fill }
        assertTrue(clipIdx >= 0, "ET pushed the accumulated text clip")
        assertTrue(fillIdx > clipIdx, "the red rect fills INSIDE the text clip")
        val clip = calls[clipIdx] as RecordingCanvas.Call.PushClip
        assertTrue(clip.path.segments.isNotEmpty(), "the clip path carries the glyph shapes")
    }

    @Test
    fun mode6_paints_and_clips() {
        val canvas = render("BT /F1 100 Tf 6 Tr 100 300 Td (AB) Tj ET")
        assertTrue(canvas.calls.any { it is RecordingCanvas.Call.Glyphs }, "mode 6 fills the text")
        assertTrue(canvas.calls.any { it is RecordingCanvas.Call.PushClip }, "and clips")
    }

    @Test
    fun mode0_pushes_no_clip() {
        val canvas = render("BT /F1 100 Tf 100 300 Td (AB) Tj ET")
        assertTrue(canvas.calls.any { it is RecordingCanvas.Call.Glyphs })
        assertEquals(0, canvas.calls.count { it is RecordingCanvas.Call.PushClip }, "plain text never clips")
    }

    @Test
    fun empty_mode7_run_pushes_nothing() {
        val canvas = render("BT /F1 100 Tf 7 Tr 100 300 Td () Tj ET")
        assertEquals(0, canvas.calls.count { it is RecordingCanvas.Call.PushClip })
    }
}
