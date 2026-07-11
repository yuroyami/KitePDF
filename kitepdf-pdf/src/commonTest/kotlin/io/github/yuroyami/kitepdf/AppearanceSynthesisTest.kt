package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Widget `/AS` sub-state selection and appearance synthesis for annotations
 * lacking an `/AP` stream (ISO 32000-1 §12.5.5, §12.7.4.2).
 */
class AppearanceSynthesisTest {

    private class Pdf {
        private val buf = ByteArrayBuilder()
        private val offsets = LinkedHashMap<Int, Int>()
        init { buf.append("%PDF-1.6\n%Äå\n".encodeToByteArray()) }
        fun obj(n: Int, body: String) { offsets[n] = buf.size(); buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray()) }
        fun stream(n: Int, dict: String, content: String) {
            val c = content.encodeToByteArray()
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n<< $dict /Length ${c.size} >>\nstream\n".encodeToByteArray())
            buf.append(c); buf.append("\nendstream\nendobj\n".encodeToByteArray())
        }
        fun finish(rootRef: String = "1 0 R"): ByteArray {
            val xref = buf.size()
            val maxN = offsets.keys.max()
            buf.append("xref\n0 ${maxN + 1}\n0000000000 65535 f \n".encodeToByteArray())
            for (n in 1..maxN) {
                val off = offsets[n]
                buf.append((if (off == null) "0000000000 65535 f \n"
                    else "${off.toString().padStart(10, '0')} 00000 n \n").encodeToByteArray())
            }
            buf.append("trailer\n<< /Size ${maxN + 1} /Root $rootRef >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
            return buf.toByteArray()
        }
    }

    private fun render(pdf: ByteArray): RecordingCanvas {
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        return canvas
    }

    private fun fills(c: RecordingCanvas) = c.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
    private fun strokes(c: RecordingCanvas) = c.calls.filterIsInstance<RecordingCanvas.Call.Stroke>()

    /** Page with a single widget annotation [annotBody] plus appearance objects. */
    private fun widgetPdf(annotBody: String, vararg extras: Pair<Int, Pair<String, String>>): ByteArray {
        val p = Pdf()
        p.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        p.obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        p.obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [4 0 R] /Contents 5 0 R >>")
        p.obj(4, annotBody)
        p.stream(5, "", " ")
        for ((n, ds) in extras) p.stream(n, ds.first, ds.second)
        return p.finish()
    }

    @Test fun checkbox_widget_renders_AS_selected_appearance() {
        // /AP /N << /On 7 0 R /Off 8 0 R >>, /AS /On → the On appearance (blue) paints.
        val pdf = widgetPdf(
            "<< /Type /Annot /Subtype /Widget /Rect [100 100 120 120] " +
                "/AP << /N << /On 7 0 R /Off 8 0 R >> >> /AS /On >>",
            7 to ("/Type /XObject /Subtype /Form /BBox [0 0 20 20]" to "0 0 1 rg 0 0 20 20 re f"),
            8 to ("/Type /XObject /Subtype /Form /BBox [0 0 20 20]" to "1 0 0 rg 0 0 20 20 re f"),
        )
        val fills = fills(render(pdf))
        assertEquals(1, fills.size)
        assertEquals(1.0, fills[0].color.b)   // blue On appearance, not red Off
        assertEquals(0.0, fills[0].color.r)
    }

    @Test fun checkbox_off_state_selects_off_appearance() {
        val pdf = widgetPdf(
            "<< /Type /Annot /Subtype /Widget /Rect [100 100 120 120] " +
                "/AP << /N << /On 7 0 R /Off 8 0 R >> >> /AS /Off >>",
            7 to ("/Type /XObject /Subtype /Form /BBox [0 0 20 20]" to "0 0 1 rg 0 0 20 20 re f"),
            8 to ("/Type /XObject /Subtype /Form /BBox [0 0 20 20]" to "1 0 0 rg 0 0 20 20 re f"),
        )
        val fills = fills(render(pdf))
        assertEquals(1, fills.size)
        assertEquals(1.0, fills[0].color.r)   // red Off appearance
    }

    @Test fun highlight_without_ap_synthesizes_from_quadpoints() {
        val pdf = widgetPdf(
            "<< /Type /Annot /Subtype /Highlight /Rect [50 50 250 80] " +
                "/QuadPoints [50 80 150 80 50 50 150 50] /C [1 1 0] >>",
        )
        // No /AP → synthesize a yellow fill over the quad.
        val fills = fills(render(pdf))
        assertTrue(fills.isNotEmpty())
        assertEquals(1.0, fills[0].color.r)
        assertEquals(1.0, fills[0].color.g)
        assertEquals(0.0, fills[0].color.b)
    }

    @Test fun hidden_annotation_is_not_rendered() {
        // /F 2 = Hidden flag → nothing painted.
        val pdf = widgetPdf(
            "<< /Type /Annot /Subtype /Square /Rect [50 50 100 100] /F 2 /C [1 0 0] >>",
        )
        val c = render(pdf)
        assertTrue(fills(c).isEmpty())
        assertTrue(strokes(c).isEmpty())
    }

    @Test fun square_without_ap_synthesizes_border() {
        val pdf = widgetPdf(
            "<< /Type /Annot /Subtype /Square /Rect [50 50 100 100] /C [0 0 1] >>",
        )
        assertTrue(strokes(render(pdf)).isNotEmpty())
    }
}
