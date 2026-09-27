package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/** A calculation order that points at a missing object falls back to the field order (#441). */
class FormSalvageScriptTest {

    @Test
    fun a_missing_calculation_order_falls_back_to_the_field_order() {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R 6 0 R] /CO 99 0 R >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 300] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R 6 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /V (0) /Rect [20 250 120 270] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (b) /V (0) /Rect [20 220 120 240] >>")
        add(
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (total) /V () /Rect [20 190 120 210] " +
                "/AA << /C << /S /JavaScript /JS (AFSimple_Calculate\\('SUM', new Array\\('a', 'b'\\)\\)) >> >> >>",
        )
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        val doc = PdfDocument.open(sb.toString().encodeToByteArray())
        PdfScriptRunner(doc).use { runner ->
            runner.setFieldValue("a", "2")
            runner.setFieldValue("b", "3")
            assertEquals("5", runner.formState.value("total"))
        }
    }
}
