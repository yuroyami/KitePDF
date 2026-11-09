package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteFileSource
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.parser.XrefEntry
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A PDF of more than 2 GiB opens from a file on disk, and its first page renders (#906). The
 * stream of zeros that passes 2 GiB comes before the page's content stream and the table, so
 * both lie past Int.MAX_VALUE, and the file is written in chunks, never held in memory.
 */
@OptIn(KiteRawApi::class)
class PdfLargeFileTest {

    @Test
    fun a_pdf_past_2_gib_opens_from_a_file_and_its_first_page_renders() {
        val dir = File(System.getProperty("java.io.tmpdir"))
        val pdf = LargePdf(LargePdf.PAST_2_GIB)
        assumeTrue("the temp directory has under 2.5 GB free", dir.usableSpace > pdf.size + (400L shl 20))
        val file = File.createTempFile("kite-large", ".pdf", dir)
        try {
            FileOutputStream(file).use { out ->
                out.write(pdf.head)
                val chunk = ByteArray(1 shl 20)
                var left = pdf.zeros
                while (left > 0) {
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    out.write(chunk, 0, n)
                    left -= n
                }
                out.write(pdf.tail)
            }
            assertEquals(pdf.size, file.length())
            assertTrue(file.length() > 1L shl 31)
            KiteFileSource(file).use { source ->
                val doc = PdfDocument.open(source)
                assertEquals(1, doc.pageCount)
                assertEquals(pdf.contentOffset, assertIs<XrefEntry.InUse>(doc.xref[6L]).offset)
                assertTrue(LargePdf.FIRST_TEXT in doc.pages[0].extractText())
                val canvas = RecordingCanvas()
                doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
                assertTrue(canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().any { LargePdf.FIRST_TEXT in it.text })
                println("PdfLargeFileTest: opened and rendered a file of ${file.length()} bytes")
            }
        } finally {
            file.delete()
        }
    }

    /** Repair scans the whole source, so this one runs on the JVM, whose scan of 2 GiB takes seconds. */
    @Test
    fun a_broken_table_past_2_gib_is_repaired_by_a_scan_of_the_source() {
        val pdf = LargePdf(LargePdf.PAST_2_GIB, brokenStartxref = true)
        val doc = PdfDocument.open(pdf.source())
        assertEquals(1, doc.pageCount)
        assertEquals(pdf.contentOffset, assertIs<XrefEntry.InUse>(doc.xref[6L]).offset)
        assertTrue(LargePdf.FIRST_TEXT in doc.pages[0].extractText())
    }
}
