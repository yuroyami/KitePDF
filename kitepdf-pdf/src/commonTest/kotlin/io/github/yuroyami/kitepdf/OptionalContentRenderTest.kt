package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Optional-content (layer) visibility honoured at render time (ISO 32000-1 §8.11).
 * A `BDC /OC <ocg>` section whose OCG is OFF in the default configuration must
 * not paint; an ON OCG paints normally. OCMD membership policies are evaluated.
 */
class OptionalContentRenderTest {

    private fun fills(pdf: ByteArray): Int {
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size
    }

    /**
     * Builds a one-page PDF: a visible blue rectangle, then a second rectangle
     * wrapped in `BDC /OC /MC0 … EMC`. [ocPropsBody] is the catalog
     * /OCProperties value; [mc0Target] is the object the /MC0 property points to.
     * Object 6 is the OCG; object 7 (when [withOcmd]) is an OCMD over it.
     */
    private fun ocPdf(ocPropsBody: String, mc0Target: String, withOcmd: Boolean = false): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) { offsets[n] = buf.size(); buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray()) }
        buf.append("%PDF-1.5\n%Äå\n".encodeToByteArray())

        obj(1, "<< /Type /Catalog /Pages 2 0 R /OCProperties $ocPropsBody >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Properties << /MC0 $mc0Target >> >> /Contents 5 0 R >>")
        val content = ("0 0 1 rg 10 10 50 50 re f\n" +
            "/OC /MC0 BDC\n1 0 0 rg 100 100 50 50 re f\nEMC\n").encodeToByteArray()
        offsets[5] = buf.size()
        buf.append("5 0 obj\n<< /Length ${content.size} >>\nstream\n".encodeToByteArray())
        buf.append(content); buf.append("\nendstream\nendobj\n".encodeToByteArray())
        obj(6, "<< /Type /OCG /Name (Layer1) >>")
        if (withOcmd) obj(7, "<< /Type /OCMD /OCGs [6 0 R] /P /AllOn >>")

        val xrefPos = buf.size()
        val maxObj = offsets.keys.max()
        buf.append("xref\n0 ${maxObj + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..maxObj) {
            val off = offsets[n]
            if (off == null) buf.append("0000000000 65535 f \n".encodeToByteArray())
            else buf.append("${off.toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        }
        buf.append("trailer\n<< /Size ${maxObj + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    @Test fun hidden_layer_suppresses_its_content() {
        // OCG 6 is OFF → only the visible rect paints.
        val pdf = ocPdf("<< /OCGs [6 0 R] /D << /OFF [6 0 R] >> >>", mc0Target = "6 0 R")
        assertEquals(1, fills(pdf))
    }

    @Test fun visible_layer_paints_its_content() {
        // OCG 6 default-ON → both rects paint.
        val pdf = ocPdf("<< /OCGs [6 0 R] /D << >> >>", mc0Target = "6 0 R")
        assertEquals(2, fills(pdf))
    }

    @Test fun ocmd_all_on_hides_when_member_off() {
        // /MC0 → OCMD (obj 7) with /P /AllOn over OCG 6, which is OFF → hidden.
        val pdf = ocPdf("<< /OCGs [6 0 R] /D << /OFF [6 0 R] >> >>", mc0Target = "7 0 R", withOcmd = true)
        assertEquals(1, fills(pdf))
    }
}
