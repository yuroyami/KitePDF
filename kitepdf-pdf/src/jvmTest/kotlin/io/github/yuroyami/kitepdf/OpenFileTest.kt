package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** T-34: the JVM `openFile` sugar reads a PDF straight from a path. */
class OpenFileTest {

    @Test
    fun opens_a_pdf_written_to_disk() {
        val bytes = PdfBuilder()
            .page(width = 100.0, height = 100.0) {
                text(StandardFont.Helvetica, 10.0, 10.0, 50.0, "from disk")
            }
            .build()
        val f = File.createTempFile("kitepdf-openfile", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val doc = PdfDocument.openFile(f.absolutePath)
        assertEquals(1, doc.pageCount)
        assertEquals("from disk", doc.pages[0].extractText().trim())
    }

    @Test
    fun missing_file_throws_io() {
        assertFailsWith<java.io.IOException> {
            PdfDocument.openFile("/definitely/not/here.pdf")
        }
    }
}
