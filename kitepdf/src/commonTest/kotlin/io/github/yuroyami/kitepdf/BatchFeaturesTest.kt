package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch of interpreter + writer fixes: text render modes (Tr), Tz glyph scaling,
 * line cap/join, inline images, form /BBox clip + recursion guard, writer /ID.
 */
class BatchFeaturesTest {

    /** One-page PDF whose page has [content] as its stream, plus [resources] in the
     *  page dict and any [extraObjects] (obj 6+). */
    private fun page(
        content: ByteArray,
        resources: String = "<< /Font << /F1 4 0 R >> >>",
        extra: List<Pair<Int, ByteArray>> = emptyList(),
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun a(s: String) = buf.append(s.encodeToByteArray())
        a("%PDF-1.5\n%Äå\n")
        offsets[1] = buf.size(); a("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets[2] = buf.size(); a("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets[3] = buf.size(); a("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources $resources /Contents 5 0 R >>\nendobj\n")
        offsets[4] = buf.size(); a("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets[5] = buf.size(); a("5 0 obj\n<< /Length ${content.size} >>\nstream\n"); buf.append(content); a("\nendstream\nendobj\n")
        for ((n, bytes) in extra) { offsets[n] = buf.size(); buf.append(bytes) }
        val xref = buf.size()
        val maxN = offsets.keys.max()
        a("xref\n0 ${maxN + 1}\n0000000000 65535 f \n")
        for (n in 1..maxN) {
            val off = offsets[n]
            a(if (off == null) "0000000000 65535 f \n" else "${off.toString().padStart(10, '0')} 00000 n \n")
        }
        a("trailer\n<< /Size ${maxN + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun render(pdf: ByteArray): RecordingCanvas {
        val c = RecordingCanvas()
        KitePDF.open(pdf).pages[0].renderTo(c, Matrix.IDENTITY)
        return c
    }

    @Test fun invisible_text_mode_3_not_drawn() {
        val hidden = render(page("BT /F1 12 Tf 3 Tr 100 700 Td (secret) Tj ET".encodeToByteArray()))
        assertEquals(0, hidden.calls.filterIsInstance<RecordingCanvas.Call.Text>().size)
        val visible = render(page("BT /F1 12 Tf 0 Tr 100 700 Td (shown) Tj ET".encodeToByteArray()))
        assertEquals(1, visible.calls.filterIsInstance<RecordingCanvas.Call.Text>().size)
    }

    @Test fun tz_scaling_bakes_into_text_matrix() {
        val c = render(page("BT /F1 12 Tf 50 Tz 100 700 Td (x) Tj ET".encodeToByteArray()))
        val text = c.calls.filterIsInstance<RecordingCanvas.Call.Text>().single()
        assertEquals(0.5, text.textMatrix.a, 0.001) // 50% horizontal scale on the x axis
    }

    @Test fun line_cap_and_join_reach_the_stroke() {
        val c = render(page("2 J 1 j 100 700 m 200 700 l S".encodeToByteArray()))
        val stroke = c.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(2, stroke.lineCap)  // projecting square
        assertEquals(1, stroke.lineJoin) // round
    }

    @Test fun inline_image_is_drawn() {
        // 2×2 DeviceRGB @ 8bpc inline image (12 bytes, values < 128 for clean encoding).
        val data = ByteArray(12) { 0x10 }
        val content = ByteArrayBuilder()
        content.append("q 100 0 0 100 100 600 cm\n".encodeToByteArray())
        content.append("BI /W 2 /H 2 /CS /RGB /BPC 8 ID ".encodeToByteArray())
        content.append(data)
        content.append(" EI\nQ\n".encodeToByteArray())
        val c = render(page(content.toByteArray()))
        assertTrue(c.calls.filterIsInstance<RecordingCanvas.Call.Image>().isNotEmpty())
    }

    @Test fun form_recursion_guard_terminates() {
        // Form Fm0 draws itself — must not StackOverflow.
        val formContent = "/Fm0 Do".encodeToByteArray()
        val form = ByteArrayBuilder().apply {
            append("6 0 obj\n<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] /Resources << /XObject << /Fm0 6 0 R >> >> /Length ${formContent.size} >>\nstream\n".encodeToByteArray())
            append(formContent); append("\nendstream\nendobj\n".encodeToByteArray())
        }.toByteArray()
        val pdf = page(
            "/Fm0 Do".encodeToByteArray(),
            resources = "<< /XObject << /Fm0 6 0 R >> >>",
            extra = listOf(6 to form),
        )
        render(pdf) // completes without hanging or overflowing
    }

    @Test fun form_bbox_is_clipped() {
        val formContent = "1 0 0 rg 0 0 100 100 re f".encodeToByteArray()
        val form = ByteArrayBuilder().apply {
            append("6 0 obj\n<< /Type /XObject /Subtype /Form /BBox [10 10 50 50] /Length ${formContent.size} >>\nstream\n".encodeToByteArray())
            append(formContent); append("\nendstream\nendobj\n".encodeToByteArray())
        }.toByteArray()
        val pdf = page(
            "/Fm0 Do".encodeToByteArray(),
            resources = "<< /XObject << /Fm0 6 0 R >> >>",
            extra = listOf(6 to form),
        )
        val c = render(pdf)
        // The form pushes a /BBox clip around its content.
        assertTrue(c.calls.filterIsInstance<RecordingCanvas.Call.PushClip>().isNotEmpty())
    }

    @Test fun writer_emits_id() {
        val built = PdfBuilder().page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "x") }.build()
        assertTrue(KitePDF.open(built).trailer["ID"] != null, "from-scratch build should carry /ID")

        val edited = KitePDF.open(built).edit().apply { setInfo(title = "Z") }.saveIncremental()
        assertTrue(KitePDF.open(edited).trailer["ID"] != null, "incremental save should carry /ID")
    }
}
