package io.github.yuroyami.kitepdf.consumer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.media.KiteMediaLabels
import io.github.yuroyami.kitepdf.net.openUrl
import io.github.yuroyami.kitepdf.skia.EpubPageRasterizer
import io.github.yuroyami.kitepdf.skia.PdfPageRasterizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The common code and the optional artifacts on the JVM, as an app that resolves them by
 * coordinates uses them: the Skia rasterizer, remote loading through a Ktor engine, and the media
 * layer of the viewer (#678).
 */
class JvmConsumerTest {

    /** A one-chapter EPUB 3 book, stored without compression as the mimetype entry must be. */
    private fun epub(): ByteArray {
        val entries = listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">consumer-kmp</dc:identifier><dc:title>Consumer</dc:title><dc:language>en</dc:language></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
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

    /** The pixels of a PNG whose blue channel is below the middle: the ink on a white page. */
    private fun inked(png: ByteArray): Int {
        val image = ImageIO.read(ByteArrayInputStream(png))
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) and 0xFF) < 128) count++
        return count
    }

    @Test
    fun a_built_pdf_opens_and_skia_draws_its_text() {
        val bytes = helloPdf()
        assertEquals(1, pageCountOf(bytes))
        assertEquals("Hello", firstPageText(bytes)?.trim())
        val doc = KiteDoc.open(bytes) as PdfDocument
        doc.use {
            val png = PdfPageRasterizer.encodeToPng(it.pages.single())
            assertTrue(inked(png) > 20, "Skia draws the page's text")
        }
    }

    @Test
    fun an_epub_opens_and_skia_draws_its_text() {
        val doc = KiteDoc.open(epub()) as EpubDocument
        doc.use {
            val page = it.pages.first()
            assertTrue(page.textContent().plainText.contains("Hello from a book"))
            assertTrue(inked(EpubPageRasterizer.encodeToPng(page)) > 20, "Skia draws the book's text")
        }
    }

    @Test
    fun a_pdf_opens_from_a_url_through_a_ktor_engine() {
        val bytes = helloPdf("Remote")
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/hello.pdf") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            HttpClient(CIO).use { client ->
                val doc = runBlocking { KiteDoc.openUrl("http://127.0.0.1:${server.address.port}/hello.pdf", client) }
                doc.use {
                    assertEquals(1, it.pageCount)
                    assertEquals("Remote", it.pages.single().textContent()?.plainText?.trim())
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun the_media_artifact_loads() {
        // A class of kitepdf-media, whose JVM jar also brings KitePlayer.
        assertEquals("Play", KiteMediaLabels().play)
        Class.forName("io.github.yuroyami.kitepdf.media.KiteMediaOverlayKt")
    }
}
