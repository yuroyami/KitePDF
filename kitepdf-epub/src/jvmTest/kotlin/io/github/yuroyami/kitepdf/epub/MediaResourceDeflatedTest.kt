package io.github.yuroyami.kitepdf.epub

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** [EpubDocument.resource] reads a deflated entry as it reads a stored one (#29). */
class MediaResourceDeflatedTest {

    @Test
    fun a_deflated_entry_reads_whole() {
        val payload = ByteArray(4_000) { (it % 7).toByte() }
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="a" href="a.mp3" media-type="audio/mpeg"/></manifest><spine><itemref idref="c1"/></spine></package>"""
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>x</p></body></html>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/c1.xhtml" to chapter.encodeToByteArray(),
                "OEBPS/a.mp3" to payload,
            )) {
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val doc = EpubDocument.open(out.toByteArray(), EpubSettings()) ?: error("the book did not open")
        assertContentEquals(payload, doc.resource("OEBPS/a.mp3"))
    }
}
