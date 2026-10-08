import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.nativerenderer.AwtCanvas
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opens and draws one document of each main format through the published artifacts, the way an
 * application that depends on them does (#212).
 */
class ConsumerSmokeTest {

    /** A one-page PDF with a line of Helvetica text. */
    private fun pdf(): ByteArray {
        val content = "BT /F1 24 Tf 20 60 Td (Hello) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 100] >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A one-chapter EPUB 3 book, stored without compression as the mimetype entry must be. */
    private fun epub(): ByteArray {
        val entries = listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">consumer</dc:identifier><dc:title>Consumer</dc:title><dc:language>en</dc:language></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
            "OEBPS/c1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Hello from a book</p></body></html>""",
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

    private fun inked(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) and 0xFF) < 128) count++
        return count
    }

    @Test
    fun a_pdf_opens_and_draws_its_text() {
        val doc = KiteDoc.open(pdf())
        assertEquals(1, doc.pageCount)
        val page = (doc as PdfDocument).pages.single()
        assertEquals("Hello", page.textContent().plainText.trim())
        assertTrue(inked(AwtPdfRasterizer.renderToImage(page)) > 20, "the page draws its text")
    }

    @Test
    fun an_epub_opens_and_draws_its_text() {
        val doc = KiteDoc.open(epub())
        val page = doc.pages.first()
        assertTrue(page.textContent()?.plainText?.contains("Hello from a book") == true)
        val image = BufferedImage(page.displayWidth.toInt(), page.displayHeight.toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.clip = java.awt.Rectangle(0, 0, image.width, image.height)
            page.renderTo(AwtCanvas(g), page.displayToDeviceBase())
        } finally {
            g.dispose()
        }
        assertTrue(inked(image) > 20, "the book draws its text")
    }

    @Test
    fun an_svg_opens_with_its_link() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><a href="https://example.com"><rect width="50" height="50"/></a></svg>"""
        val page = KiteDoc.open(svg.encodeToByteArray()).pages.single()
        assertEquals(listOf("https://example.com"), page.hyperlinks.map { it.uri })
    }

    @Test
    fun a_lossy_webp_resolves_the_published_image_decoder() {
        // A generated 16x16 card from ImageKodec's Apache-2.0 WebpDecoderTest at f25823b74d76.
        // The ARGB checksum comes from dwebp. This must work with only KitePDF coordinates (#513).
        val hex = "524946468200000057454250565038580a000000100000000f00000f0000414c504815000000010ff094ff888820102066ccd873ed20a2ff1530" +
            "5e005650382046000000d001009d012a1000100001402625b00274010eb589a80000fefe92532bfabaf61b2bfe6d7311f2d9de894ae0d53cb87e" +
            "d1c9dd7fbe5d7ffe5e99eabfffeb4fcf4b6fef830000"
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val image = assertNotNull(KiteImageData.fromEncodedImage(bytes))
        assertEquals(16 to 16, image.width to image.height)
        val rgb = assertNotNull(image.pixelBytes)
        val alpha = assertNotNull(image.softMaskAlpha)
        val argb = ByteArray(16 * 16 * 4) { i ->
            if (i % 4 == 0) alpha[i / 4] else rgb[i / 4 * 3 + i % 4 - 1]
        }
        assertEquals(0xE33C325CL, CRC32().also { it.update(argb) }.value)
    }
}
