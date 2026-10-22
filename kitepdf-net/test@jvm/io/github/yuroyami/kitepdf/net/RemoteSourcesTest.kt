package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
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
import kotlinx.coroutines.CancellationException

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
    fun failure_messages_do_not_expose_url_secrets() = runBlocking {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.openUrl("https://alice:secret@example.org/a.pdf?token=top-secret#fragment", client)
        }
        assertTrue(e.message!!.contains("example.org/a.pdf"), "message keeps a useful location: ${e.message}")
        assertTrue("alice" !in e.message!! && "secret" !in e.message!! && "token" !in e.message!!)
    }

    @Test
    fun transport_exceptions_do_not_reintroduce_url_secrets_through_their_cause() = runBlocking {
        val secretUrl = "https://alice:secret@example.org/a.pdf?token=top-secret"
        val client = HttpClient(MockEngine { throw IllegalStateException("failed request to $secretUrl") })
        val e = assertFailsWith<KiteFormatException> { KiteDoc.downloadBytes(secretUrl, client) }
        assertNull(e.cause, "credential-bearing transport errors are not retained")
        assertTrue("alice" !in e.message!! && "secret" !in e.message!! && "token" !in e.message!!)
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

    @Test
    fun streaming_body_is_stopped_at_the_configured_limit() = runBlocking {
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.downloadBytes("https://example.org/large.pdf", clientServing(ByteArray(9)), maxBytes = 8)
        }
        assertTrue(e.message!!.contains("exceeds 8 bytes"), "message: ${e.message}")
    }

    @Test
    fun declared_oversize_body_is_rejected_before_buffering() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(byteArrayOf(1)),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength, "1000"),
                )
            },
        )
        assertFailsWith<KiteFormatException> {
            KiteDoc.downloadBytes("https://example.org/large.pdf", client, maxBytes = 8)
        }
        Unit
    }

    @Test
    fun or_null_never_swallows_coroutine_cancellation() = runBlocking {
        assertFailsWith<CancellationException> {
            KiteDoc.openUrlOrNull("https://example.org/a.pdf", clientServing(samplePdf())) {
                throw CancellationException("cancelled by caller")
            }
        }
        Unit
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

    /** A client that serves [files] by URL, answers 404 to the rest, and records each URL asked for. */
    private class Server(private val files: Map<String, ByteArray>) {
        val asked = ArrayList<String>()
        val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                asked += url
                val bytes = files[url] ?: return@MockEngine respondError(HttpStatusCode.NotFound)
                respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
            },
        )
    }

    @Test
    fun the_resource_fetcher_reads_an_https_resource() = runBlocking {
        val bytes = ByteArray(300) { it.toByte() }
        val server = Server(mapOf("https://example.org/font.ttf" to bytes))
        assertContentEquals(bytes, EpubResourceFetcher(server.client).fetch("https://example.org/font.ttf"))
        assertNull(EpubResourceFetcher(server.client).fetch("https://example.org/missing.ttf"), "a 404 is null")
    }

    @Test
    fun the_resource_fetcher_never_requests_plain_http() = runBlocking {
        val server = Server(mapOf("http://example.org/font.ttf" to ByteArray(10)))
        assertNull(EpubResourceFetcher(server.client).fetch("http://example.org/font.ttf"))
        assertNull(EpubResourceFetcher(server.client).fetch("ftp://example.org/font.ttf"))
        assertEquals(emptyList(), server.asked, "EPUB Reading Systems 3.3, 3.3: https only")
    }

    @Test
    fun the_resource_fetcher_gives_null_past_its_cap_and_on_a_transport_failure() = runBlocking {
        val server = Server(mapOf("https://example.org/big.png" to ByteArray(64)))
        assertNull(EpubResourceFetcher(server.client, maxBytes = 63).fetch("https://example.org/big.png"))
        val broken = HttpClient(MockEngine { throw IllegalStateException("connection reset") })
        assertNull(EpubResourceFetcher(broken).fetch("https://example.org/big.png"))
        assertFailsWith<IllegalArgumentException> { EpubResourceFetcher(server.client, maxBytes = 0) }
        Unit
    }

    @Test
    fun the_resource_fetcher_passes_cancellation_through() = runBlocking {
        val server = Server(mapOf("https://example.org/a.png" to ByteArray(8)))
        assertFailsWith<CancellationException> {
            EpubResourceFetcher(server.client) { throw CancellationException("cancelled by caller") }.fetch("https://example.org/a.png")
        }
        Unit
    }

    @Test
    fun a_downloaded_book_draws_an_image_it_names_by_url() = runBlocking {
        val picture = "https://images.example.org/pic.bmp"
        val book = sampleEpub("""<p><img src="$picture" width="40" height="30" alt="A picture"/></p>""")
        val server = Server(mapOf("https://example.org/a.epub" to book, picture to bmp2x1()))
        val doc = KiteDoc.openUrl(
            "https://example.org/a.epub", server.client,
            epubSettings = EpubSettings(resourceFetcher = EpubResourceFetcher(server.client)),
        ) as EpubDocument
        assertTrue(doc.hasRemoteResources(0))
        doc.fetchRemoteResources(0)
        val canvas = RecordingCanvas().also { doc.page(KiteLocation(0, 0)).renderTo(it) }
        assertEquals(1, canvas.calls.count { it is RecordingCanvas.Call.Image })
        assertEquals(listOf("https://example.org/a.epub", picture), server.asked)
    }

    /* ── fixtures ─────────────────────────────────────────────────────────── */

    /** A 2x1 24-bit BMP: a red pixel and a blue one. */
    private fun bmp2x1(): ByteArray {
        val h = ByteArray(54)
        h[0] = 'B'.code.toByte(); h[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { h[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        return h + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    }

    private fun samplePdf(): ByteArray = PdfBuilder()
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page one") }
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page two") }
        .build()

    private fun sampleEpub(body: String = "<p>downloaded</p>"): ByteArray {
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
        val ch1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
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
