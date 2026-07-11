package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.Parser
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Malformed-file recovery: documents whose cross-reference table is corrupt,
 * missing, or whose stream lengths are wrong must still open and render, matching
 * MuPDF's repair behaviour (pdf-repair.c). The fixtures are valid PdfBuilder
 * output deliberately damaged after the fact.
 */
class RecoveryTest {

    private fun sampleDoc(): ByteArray = PdfBuilder()
        .setInfo(title = "Recoverable")
        .page { text(StandardFont.Helvetica, 18.0, 72.0, 700.0, "Recovery target alpha") }
        .page { text(StandardFont.TimesRoman, 18.0, 72.0, 700.0, "Recovery target beta") }
        .build(compress = false)

    private fun ByteArray.indexOfAscii(needle: String, from: Int = 0): Int {
        val n = needle.encodeToByteArray()
        var i = from.coerceAtLeast(0)
        outer@ while (i <= size - n.size) {
            for (k in n.indices) if (this[i + k] != n[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    private fun ByteArray.lastIndexOfAscii(needle: String, before: Int): Int {
        val n = needle.encodeToByteArray()
        var i = (before - n.size).coerceAtMost(size - n.size)
        outer@ while (i >= 0) {
            for (k in n.indices) if (this[i + k] != n[k]) { i--; continue@outer }
            return i
        }
        return -1
    }

    @Test fun valid_file_opens_normally() {
        val doc = KitePDF.open(sampleDoc())
        assertEquals(2, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Recovery target alpha")
    }

    @Test fun recovers_from_corrupt_startxref() {
        val bytes = sampleDoc()
        // Point startxref at a bogus (out-of-range) offset by maxing its digits.
        val sx = bytes.lastIndexOfAscii("startxref", bytes.size)
        var p = sx + "startxref".length
        while (p < bytes.size && (bytes[p] == '\r'.code.toByte() || bytes[p] == '\n'.code.toByte() ||
                bytes[p] == ' '.code.toByte())) p++
        var changed = false
        while (p < bytes.size && bytes[p] in '0'.code.toByte()..'9'.code.toByte()) {
            bytes[p] = '9'.code.toByte(); changed = true; p++
        }
        assertTrue(changed, "expected digits after startxref")

        val doc = KitePDF.open(bytes)
        assertEquals(2, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Recovery target alpha")
        assertContains(doc.pages[1].extractText(), "Recovery target beta")
    }

    @Test fun recovers_from_prepended_garbage() {
        val original = sampleDoc()
        // 200 junk bytes before %PDF- shift every xref offset; repair rebuilds them.
        val junk = ByteArray(200) { (it % 64 + 32).toByte() }
        val bytes = junk + original

        val doc = KitePDF.open(bytes)
        assertEquals(2, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Recovery target alpha")
    }

    @Test fun recovers_from_missing_xref_and_trailer() {
        val original = sampleDoc()
        // Cut the xref table, trailer, and startxref entirely; only object bodies
        // remain. Repair must rebuild the xref and recover /Root from the catalog.
        val trailerIdx = original.indexOfAscii("trailer")
        assertTrue(trailerIdx > 0, "expected a trailer keyword")
        val tableIdx = original.lastIndexOfAscii("xref", trailerIdx)
        assertTrue(tableIdx > 0, "expected an xref table keyword")
        val truncated = original.copyOfRange(0, tableIdx) + "%%EOF".encodeToByteArray()

        val doc = KitePDF.open(truncated)
        assertEquals(2, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Recovery target alpha")
        assertContains(doc.pages[1].extractText(), "Recovery target beta")
    }

    @Test fun parser_recovers_wrong_stream_length() {
        val body = "Hello stream body"
        val src = "1 0 obj\n<< /Length 9999 >>\nstream\n$body\nendstream\nendobj\n"
        val stream = Parser(src.encodeToByteArray()).readIndirectObject().value
        assertTrue(stream is PdfStream)
        assertEquals(body, stream.rawBytes.decodeToString())
    }

    @Test fun parser_recovers_missing_stream_length() {
        val body = "No length declared here"
        val src = "1 0 obj\n<< /Filter /FlateDecode >>\nstream\n$body\nendstream\nendobj\n"
        val stream = Parser(src.encodeToByteArray()).readIndirectObject().value
        assertTrue(stream is PdfStream)
        assertEquals(body, stream.rawBytes.decodeToString())
    }
}
