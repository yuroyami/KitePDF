package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-28: the warning sink. Salvage paths report through [KiteWarnings.sink],
 * the default null sink stays free, and installing a sink (even a hostile
 * one) never changes parse or render results.
 */
class KiteWarningsTest {

    @AfterTest
    fun tearDown() {
        KiteWarnings.sink = null
    }

    private fun corruptStartxrefDoc(): ByteArray {
        val bytes = PdfBuilder()
            .page { text(StandardFont.Helvetica, 18.0, 72.0, 720.0, "Warned but fine") }
            .build()
        // Max out the startxref digits so open() must fall to the repair scan.
        var sx = -1
        val needle = "startxref".encodeToByteArray()
        outer@ for (i in bytes.size - needle.size downTo 0) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            sx = i; break
        }
        assertTrue(sx >= 0)
        var p = sx + needle.size
        while (bytes[p] == '\r'.code.toByte() || bytes[p] == '\n'.code.toByte() || bytes[p] == ' '.code.toByte()) p++
        while (p < bytes.size && bytes[p] in '0'.code.toByte()..'9'.code.toByte()) {
            bytes[p] = '9'.code.toByte(); p++
        }
        return bytes
    }

    @Test
    fun repair_path_warns_and_results_are_identical_with_and_without_sink() {
        val bytes = corruptStartxrefDoc()

        // Baseline: null sink (default), render one page.
        KiteWarnings.sink = null
        val silent = KitePDF.open(bytes)
        val silentCanvas = RecordingCanvas()
        silent.pages[0].renderTo(silentCanvas)
        val silentText = silent.pages[0].extractText()

        // Collecting sink installed: same document, same results, plus warnings.
        val warnings = ArrayList<String>()
        KiteWarnings.sink = { warnings.add(it) }
        val watched = KitePDF.open(bytes)
        val watchedCanvas = RecordingCanvas()
        watched.pages[0].renderTo(watchedCanvas)

        assertTrue(warnings.isNotEmpty(), "the repair fallback must report through the sink")
        assertTrue(warnings.any { it.startsWith("open: ") }, "warnings carry the area prefix: $warnings")
        assertEquals(silentText, watched.pages[0].extractText(), "sink must not change parsing")
        assertEquals(silentCanvas.calls.size, watchedCanvas.calls.size, "sink must not change rendering")
    }

    @Test
    fun a_throwing_sink_never_breaks_the_caller() {
        KiteWarnings.sink = { throw IllegalStateException("hostile sink") }
        val doc = KitePDF.open(corruptStartxrefDoc())
        assertEquals(1, doc.pageCount)
        assertTrue(doc.pages[0].extractText().contains("Warned but fine"))
    }

    @Test
    fun missing_xobject_warns_from_the_renderer() {
        // A hand-built page whose content stream references /Missing Do with
        // no /XObject resource at all.
        val warnings = ArrayList<String>()
        KiteWarnings.sink = { warnings.add(it) }
        val doc = KitePDF.open(minimalPdfWithContent("/Missing Do"))
        doc.pages[0].renderTo(RecordingCanvas())
        assertTrue(
            warnings.any { it.startsWith("render: XObject Missing") },
            "the Do operator must report the missing XObject: $warnings",
        )
    }

    private fun minimalPdfWithContent(content: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(t: String) = buf.append(t.encodeToByteArray())
        write("%PDF-1.4\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val c = content.encodeToByteArray()
        write("4 0 obj\n<< /Length ${c.size} >>\nstream\n")
        buf.append(c)
        write("\nendstream\nendobj\n")
        val xref = buf.size()
        write("xref\n0 5\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
