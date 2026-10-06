package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRole
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.zip.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An image that the spine lists shows as itself, at its own size, and its fallback document gives
 * its alternative text (EPUB 3.3, manifest fallbacks; W3C tests lay-roll-images-in-spine and
 * lay-roll-images-mixed, #614).
 */
class SpineImageTest {

    /** A [w] by [h] PNG of one red, stored without compression. */
    private fun png(w: Int, h: Int): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.encodeToByteArray() + data
            return be32(data.size) + body + be32(Crc32.of(body).toInt())
        }
        val scan = ByteArray(h * (1 + 3 * w))
        for (y in 0 until h) for (x in 0 until w) scan[y * (1 + 3 * w) + 1 + 3 * x] = 0xFF.toByte()
        var a = 1; var b = 0
        for (v in scan) { a = (a + (v.toInt() and 255)) % 65521; b = (b + a) % 65521 }
        val nlen = scan.size.inv()
        val zlib = byteArrayOf(0x78, 1, 1, scan.size.toByte(), (scan.size ushr 8).toByte(), nlen.toByte(), (nlen ushr 8).toByte()) + scan + be32((b shl 16) or a)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10) +
            chunk("IHDR", be32(w) + be32(h) + byteArrayOf(8, 2, 0, 0, 0)) + chunk("IDAT", zlib) + chunk("IEND", byteArrayOf())
    }

    private val fallback = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Alternate text</title></head>
        <body><p>Children dance around   an apple pie.</p></body></html>"""

    private val nav = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
        <nav epub:type="toc"><ol><li><a href="images/plate.png">The plate</a></li><li><a href="c2.xhtml">Text</a></li></ol></nav></body></html>"""

    /** A book whose spine lists the image `plate.png`, of [type], with the fallback document `plate.xhtml`, then a chapter of text. */
    private fun book(metadata: String = "", type: String = "image/png"): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier>$metadata</metadata>
            <manifest>
              <item id="plate" href="images/plate.png" media-type="$type" fallback="plate-text"/>
              <item id="plate-text" href="plate.xhtml" media-type="application/xhtml+xml"/>
              <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
              <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            </manifest>
            <spine><itemref idref="plate"/><itemref idref="c2"/></spine></package>"""
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/images/plate.png" to png(40, 30),
                    "OEBPS/plate.xhtml" to fallback.encodeToByteArray(),
                    "OEBPS/nav.xhtml" to nav.encodeToByteArray(),
                    "OEBPS/c2.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>Text.</p></body></html>""".encodeToByteArray(),
                ),
            ),
            EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0),
        )
    }

    private fun images(doc: EpubDocument, chapter: Int) =
        RecordingCanvas().also { doc.page(KiteLocation(chapter, 0)).renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>()

    @Test
    fun a_roll_shows_a_spine_image_as_a_page_of_its_own_size() {
        val doc = book("""<meta property="rendition:layout">roll</meta>""")
        assertEquals("OEBPS/images/plate.png", doc.chapterPath(0))
        assertEquals(1, doc.pageCountIn(0))
        val page = doc.page(KiteLocation(0, 0))
        // CSS pixels become points at three quarters.
        assertEquals(40 * 0.75, page.displayWidth, 0.01)
        assertEquals(30 * 0.75, page.displayHeight, 0.01)
        val drawn = images(doc, 0).single()
        assertEquals(40 * 0.75, drawn.ctm.a, 0.01)
        assertEquals(30 * 0.75, drawn.ctm.d, 0.01)
        assertTrue("apple pie" !in page.textContent().plainText, "the fallback text shows as text")
    }

    @Test
    fun the_fallback_document_gives_the_alternative_text() {
        val item = book().page(KiteLocation(0, 0)).readingOrder().single()
        assertEquals(KiteRole.IMAGE, item.role)
        assertEquals("Children dance around an apple pie.", item.text)
    }

    @Test
    fun a_contents_link_to_the_image_leads_to_its_page() {
        assertEquals(listOf(0, 1), book().tableOfContents.entries.map { it.spineIndex })
    }

    @Test
    fun a_reflowable_book_shows_a_spine_image_too() {
        val doc = book()
        assertEquals(1, doc.pageCountIn(0))
        assertEquals(1, images(doc, 0).size)
        assertEquals("Text.", doc.page(KiteLocation(1, 0)).textContent().plainText.trim())
    }

    @Test
    fun an_image_type_that_not_every_target_decodes_shows_its_fallback() {
        val doc = book(type = "image/webp")
        assertEquals("OEBPS/plate.xhtml", doc.chapterPath(0))
        assertTrue("apple pie" in doc.page(KiteLocation(0, 0)).textContent().plainText)
    }

    @Test
    fun the_size_comes_from_the_header_of_each_format() {
        assertEquals(40 to 30, ImagePage.size(png(40, 30)))
        val gif = "GIF89a".encodeToByteArray() + byteArrayOf(0x90.toByte(), 0x01, 0x2C, 0x01, 0, 0, 0)
        assertEquals(400 to 300, ImagePage.size(gif))
        // A JPEG: start of image, an APP0 segment of 16 bytes, then a baseline frame of 600 by 200.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(14) +
            byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, 0, 200.toByte(), 0x02, 0x58, 3) + ByteArray(9)
        assertEquals(600 to 200, ImagePage.size(jpeg))
        assertNull(ImagePage.size("not an image".encodeToByteArray()))
    }
}
