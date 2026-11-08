package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains

/** Poppler's pdfdetach lists the attachments the editor writes (#921). */
class AttachmentPopplerTest {

    @Test
    fun pdfdetachListsTheAttachments() {
        assumeTrue("Poppler's pdfdetach is not installed", runCatching { ProcessBuilder("pdfdetach", "-v").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        val plain = PdfBuilder().page(200.0, 200.0) { text(StandardFont.Helvetica, 12.0, 20.0, 100.0, "Invoice") }.build()
        val doc = PdfDocument.open(plain)
        val out = doc.edit().apply {
            setAttachment("factur-x.xml", "<invoice/>".encodeToByteArray(), mimeType = "text/xml", relationship = "Data")
            setAttachment("notes.txt", "hello".encodeToByteArray(), mimeType = "text/plain")
        }.saveRewritten()
        val file = File.createTempFile("attached", ".pdf").apply { writeBytes(out); deleteOnExit() }
        val process = ProcessBuilder("pdfdetach", "-list", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor(30, TimeUnit.SECONDS)
        assertContains(output, "2 embedded files")
        assertContains(output, "factur-x.xml")
        assertContains(output, "notes.txt")
    }
}
