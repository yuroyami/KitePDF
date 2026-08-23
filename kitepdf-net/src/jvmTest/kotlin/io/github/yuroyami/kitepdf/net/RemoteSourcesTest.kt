package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Remote loading, driven through Ktor's MockEngine so the suite stays offline.
 * The download path is common code; only the engine differs per platform.
 */
class RemoteSourcesTest {

    private fun clientServing(bytes: ByteArray) = HttpClient(
        MockEngine { respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf()) },
    )

    @Test
    fun downloads_and_opens_a_pdf() = runBlocking {
        val doc = KiteDoc.openUrl("https://example.org/a.pdf", clientServing(samplePdf()))
        assertTrue(doc is PdfDocument)
        assertEquals(2, doc.pageCount)
    }

    @Test
    fun downloads_and_opens_an_epub() = runBlocking {
        val doc = KiteDoc.openUrl("https://example.org/a.epub", clientServing(sampleEpub()))
        assertTrue(doc is EpubDocument)
        assertEquals("Remote Fixture", doc.metadata.title)
    }

    @Test
    fun a_failed_status_names_itself() = runBlocking {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.openUrl("https://example.org/missing.pdf", client)
        }
        assertTrue(e.message!!.contains("404"), "message: ${e.message}")
    }

    @Test
    fun an_empty_body_is_not_a_document() = runBlocking {
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.openUrl("https://example.org/empty.pdf", clientServing(ByteArray(0)))
        }
        assertTrue(e.message!!.contains("empty"), "message: ${e.message}")
    }

    @Test
    fun or_null_swallows_the_failure() = runBlocking {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        assertNull(KiteDoc.openUrlOrNull("https://example.org/boom.pdf", client))
    }

    @Test
    fun download_bytes_hands_back_the_body_untouched() = runBlocking {
        val pdf = samplePdf()
        assertContentEquals(pdf, KiteDoc.downloadBytes("https://example.org/a.pdf", clientServing(pdf)))
    }

    /** The configure block is where auth headers go, so it has to reach the request. */
    @Test
    fun the_configure_block_reaches_the_request() = runBlocking {
        var seen: String? = null
        val client = HttpClient(
            MockEngine { request ->
                seen = request.headers["Authorization"]
                respond(ByteReadChannel(samplePdf()), HttpStatusCode.OK, headersOf())
            },
        )
        KiteDoc.openUrl("https://example.org/a.pdf", client) { header("Authorization", "Bearer t0ken") }
        assertEquals("Bearer t0ken", seen)
    }

    /* ── fixtures ─────────────────────────────────────────────────────────── */

    private fun samplePdf(): ByteArray = PdfBuilder()
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page one") }
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page two") }
        .build()

    private fun sampleEpub(): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">urn:uuid:kite-net</dc:identifier>
                <dc:title>Remote Fixture</dc:title>
              </metadata>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        val ch1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>downloaded</p></body></html>"""
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/ch1.xhtml" to ch1.encodeToByteArray(),
            ),
        )
    }

    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            for ((name, data) in entries) {
                val crc = CRC32().apply { update(data) }
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        this.crc = crc.value
                    },
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
