package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A runner runs a document's own scripts once, however often a viewer reports the document open,
 * and closing it stops a script that is still running (#365).
 */
class RunnerLifecycleTest {

    /** A text field `log`, and a document open action that runs [openScript]. */
    private fun pdf(openScript: String): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> /OpenAction << /S /JavaScript /JS ($openScript) >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (log) /V () /Rect [20 120 180 160] >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun the_document_scripts_run_once_however_often_the_document_opens() {
        val doc = PdfDocument.open(pdf("var f = this.getField\\('log'\\); f.value = f.value + 'x';"))
        PdfScriptRunner(doc).use { runner ->
            runner.documentOpened()
            runner.documentOpened()
            assertEquals("x", runner.formState.value("log"))
        }
    }

    @Test
    fun closing_the_runner_stops_a_script_that_never_ends() {
        val doc = PdfDocument.open(pdf("while \\(true\\) {}"))
        val runner = PdfScriptRunner(doc, policy = PdfScriptPolicy.LONG_RUNNING)
        val opener = thread(isDaemon = true, name = "opener") { runCatching { runner.documentOpened() } }
        // Long enough for the script to be well inside its loop.
        Thread.sleep(500)
        val closer = thread(isDaemon = true, name = "closer") { runner.close() }
        closer.join(10_000)
        assertFalse(closer.isAlive, "close did not return")
        opener.join(10_000)
        assertFalse(opener.isAlive, "the script is still running")
    }
}
