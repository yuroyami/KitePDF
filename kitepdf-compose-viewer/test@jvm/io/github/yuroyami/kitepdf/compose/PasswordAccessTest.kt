package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The accessibility node of a password field says it is one and carries no value (#953). */
class PasswordAccessTest {

    private fun pdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) { offsets.add(sb.length); sb.append("${offsets.size} 0 obj\n$body\nendobj\n") }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (pw) /Ff 8192 /V () /Rect [20 80 180 120] >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun aPasswordFieldNodeHoldsNoValue() {
        val doc = PdfDocument.open(pdf())
        val state = PdfFormState(doc)
        state.setValue("pw", "hunter2")
        val node = accessNodes(doc.pages[0], state, KiteViewerStrings()).single { it.kind == AccessKind.TEXT_FIELD }
        assertTrue(node.password)
        assertNull(node.state)
        assertEquals(false, node.label.contains("hunter2"))
    }
}
