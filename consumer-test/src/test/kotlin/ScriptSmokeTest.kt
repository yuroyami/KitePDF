import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.javascript.EpubScriptRunner
import io.github.yuroyami.kitepdf.javascript.PdfScriptRunner
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs a book's script and a form's through the published `kitepdf-javascript`, whose engine,
 * KiteJS, comes from Maven Central, the way an application that depends on it does (#556).
 */
class ScriptSmokeTest {

    /**
     * A one-page PDF whose document-level script defines `answer`, with a link whose script
     * calls it.
     */
    private fun pdf(): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /JavaScript << /Names [(lib) 5 0 R] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 100 100] >>",
            "<< /Type /Page /Parent 2 0 R /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Link /Rect [0 0 50 50] /A << /S /JavaScript /JS (answer\\(\\)) >> >>",
            "<< /S /JavaScript /JS (function answer\\(\\) { return 6 * 7; }) >>",
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A one-chapter EPUB 3 book whose script adds a paragraph, stored without compression. */
    private fun epub(): ByteArray {
        val entries = listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">consumer-scripts</dc:identifier><dc:title>Scripts</dc:title><dc:language>en</dc:language></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml" properties="scripted"/></manifest><spine><itemref idref="c1"/></spine></package>""",
            "OEBPS/c1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Markup.</p><script>var p = document.createElement('p'); p.textContent = 'Added by a script in ' + new Date(0).getUTCFullYear() + '.'; document.body.appendChild(p);</script></body></html>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                val bytes = text.encodeToByteArray()
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().also { it.update(bytes) }.value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun a_book_runs_its_scripts() {
        val book = KiteDoc.open(epub()) as EpubDocument
        EpubScriptRunner(book).use { scripts ->
            runBlocking { scripts.chapterOpened(0) }
            assertEquals(emptyList(), scripts.failures.map { it.message })
        }
        val text = book.pages.first().textContent().plainText
        assertTrue("Added by a script in 1970." in text, "the script's paragraph is on the page: $text")
    }

    @Test
    fun a_form_runs_its_scripts() {
        val doc = KiteDoc.open(pdf()) as PdfDocument
        PdfScriptRunner(doc).use { scripts ->
            runBlocking { scripts.documentOpened() }
            val link = doc.pages.single().annotations.single().action as PdfAction.JavaScript
            assertEquals("42", scripts.run(link), "the link calls the function the document's own script defined")
            assertEquals(emptyList(), scripts.failures.map { it.message })
        }
    }
}
