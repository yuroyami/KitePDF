package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfScriptRunnerTest {

    /** A one-page PDF with document-level scripts, and a link that runs [linkScript]. */
    private fun pdf(scripts: List<Pair<String, String>>, linkScript: String = "0"): ByteArray {
        val names = scripts.indices.joinToString(" ") { "(${scripts[it].first}) ${5 + it} 0 R" }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /JavaScript << /Names [$names] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Link /Rect [0 0 50 50] /A << /S /JavaScript /JS ($linkScript) >> >>",
        ) + scripts.map { "<< /S /JavaScript /JS (${it.second}) >>" }
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) sb.append("${off.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun document_scripts_run_in_name_order_and_reach_the_host() {
        val printed = ArrayList<String>()
        val alerts = ArrayList<String>()
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    "a" to "var total = 40; console.println('first')",
                    "b" to "throw new Error('broken')",
                    "c" to "app.alert({ cMsg: 'total ' + (total + 2) })",
                ),
            ),
        )
        val failures = PdfScriptRunner(doc, onAlert = { alerts += it }, onConsole = { printed += it }).use { it.runDocumentScripts() }
        assertEquals(listOf("first"), printed)
        assertEquals(listOf("total 42"), alerts, "the third script still ran, and saw the first one's variable")
        assertEquals(1, failures.size, "the broken script is reported")
    }

    @Test
    fun a_link_javascript_action_runs() {
        val doc = PdfDocument.open(pdf(emptyList(), linkScript = "6 * 7"))
        val action = doc.pages[0].annotations.single().action as PdfAction.JavaScript
        assertEquals("42", PdfScriptRunner(doc).use { it.run(action) })
    }
}
