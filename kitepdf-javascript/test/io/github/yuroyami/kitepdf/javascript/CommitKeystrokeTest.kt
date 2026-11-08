package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/** keystroke with commit true runs the keystroke that ends the edit, with event.willCommit true (#779). */
class CommitKeystrokeTest {

    /** A text field `a` whose keystroke script is [script]. */
    private fun pdf(script: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = ArrayList<Int>()
        fun obj(body: String) {
            offsets += buf.size()
            buf.append("${offsets.size} 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n".encodeToByteArray())
        obj("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DA (/Helv 0 Tf 0 g) >> >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R] >>")
        obj("<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /V () /Rect [20 20 120 40] /AA << /K << /S /JavaScript /JS ($script) >> >> >>")
        val xref = buf.size()
        buf.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (off in offsets) buf.append("${off.toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        buf.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    @Test
    fun aCommitKeystrokeSeesWillCommit(): TestResult = scriptTest {
        PdfScriptRunner(PdfDocument.open(pdf("if \\(event.willCommit\\) event.rc = false;"))).use { runner ->
            assertTrue(runner.keystroke("a", "x", 0, 0, commit = false).accepted, "a plain keystroke")
            assertFalse(runner.keystroke("a", "x", 0, 0, commit = true).accepted, "the commit keystroke refuses, as setFieldValue does")
            assertFalse(runner.setFieldValue("a", "x"))
        }
    }

    @Test
    fun aCommitKeystrokeSeesTheWholeValueAndNoChange(): TestResult = scriptTest {
        val script = "if \\(event.willCommit\\) event.value = event.value + '|' + event.change.length;"
        PdfScriptRunner(PdfDocument.open(pdf(script))).use { runner ->
            val result = runner.keystroke("a", "abc", 0, 0, commit = true)
            assertTrue(result.accepted)
            assertEquals("abc|0", result.value, "the whole value, and an empty change")
            assertEquals("", runner.formState.value("a"), "the commit keystroke stores nothing")
        }
    }
}
