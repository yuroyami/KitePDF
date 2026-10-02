package io.github.yuroyami.kitepdf.media

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Books with media for the tests: one chapter whose body is [body], and the media files beside it. */
internal object MediaBooks {

    /** A media file of the test resources: a two second 440 Hz tone, or a two second 64 by 48 clip with sound. */
    fun fixture(name: String): ByteArray =
        checkNotNull(MediaBooks::class.java.getResourceAsStream("/media/$name")) { "no fixture $name" }.readBytes()

    fun book(body: String, files: Map<String, ByteArray> = emptyMap(), manifest: String = ""): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">media-test</dc:identifier></metadata>""" +
            """<manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>$manifest</manifest>""" +
            """<spine><itemref idref="c1"/></spine></package>"""
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to container.encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
            "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
        )
        for ((name, bytes) in files) entries["OEBPS/$name"] = bytes
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                // Stored entries, as an EPUB's mimetype must be (EPUB OCF 3.3, 4.3).
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return EpubDocument.open(out.toByteArray())
    }

    fun firstPage(doc: EpubDocument): EpubPage = doc.pages.first()
}
