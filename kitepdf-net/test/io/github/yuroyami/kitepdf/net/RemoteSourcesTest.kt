package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remote loading, driven through Ktor's MockEngine so the suite stays offline. The download path
 * is common code and MockEngine is multiplatform, so every target runs these (#768).
 */
class RemoteSourcesTest {

    @Test
    fun downloads_and_opens_a_pdf() = runTest {
        val doc = KiteDoc.openUrl("https://example.org/a.pdf", NetFixtures.clientServing(NetFixtures.samplePdf()))
        assertTrue(doc is PdfDocument)
        assertEquals(2, doc.pageCount)
    }

    @Test
    fun downloads_and_opens_an_epub() = runTest {
        val doc = KiteDoc.openUrl("https://example.org/a.epub", NetFixtures.clientServing(NetFixtures.sampleEpub()))
        assertTrue(doc is EpubDocument)
        assertEquals("Remote Fixture", doc.metadata.title)
    }

    @Test
    fun a_failed_status_names_itself() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.openUrl("https://example.org/missing.pdf", client)
        }
        assertTrue(e.message!!.contains("404"), "message: ${e.message}")
    }

    @Test
    fun failure_messages_do_not_expose_url_secrets() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.openUrl("https://alice:secret@example.org/a.pdf?token=top-secret#fragment", client)
        }
        assertTrue(e.message!!.contains("example.org/a.pdf"), "message keeps a useful location: ${e.message}")
        assertTrue("alice" !in e.message!! && "secret" !in e.message!! && "token" !in e.message!!)
    }

    @Test
    fun transport_exceptions_do_not_reintroduce_url_secrets_through_their_cause() = runTest {
        val secretUrl = "https://alice:secret@example.org/a.pdf?token=top-secret"
        val client = HttpClient(MockEngine { throw IllegalStateException("failed request to $secretUrl") })
        val e = assertFailsWith<KiteFormatException> { KiteDoc.downloadBytes(secretUrl, client) }
        assertNull(e.cause, "credential-bearing transport errors are not retained")
        assertTrue("alice" !in e.message!! && "secret" !in e.message!! && "token" !in e.message!!)
    }

    @Test
    fun an_empty_body_is_not_a_document() = runTest {
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.openUrl("https://example.org/empty.pdf", NetFixtures.clientServing(ByteArray(0)))
        }
        assertTrue(e.message!!.contains("empty"), "message: ${e.message}")
    }

    @Test
    fun or_null_swallows_the_failure() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        assertNull(KiteDoc.openUrlOrNull("https://example.org/boom.pdf", client))
    }

    @Test
    fun download_bytes_hands_back_the_body_untouched() = runTest {
        val pdf = NetFixtures.samplePdf()
        assertContentEquals(pdf, KiteDoc.downloadBytes("https://example.org/a.pdf", NetFixtures.clientServing(pdf)))
    }

    @Test
    fun streaming_body_is_stopped_at_the_configured_limit() = runTest {
        val e = assertFailsWith<KiteFormatException> {
            KiteDoc.downloadBytes("https://example.org/large.pdf", NetFixtures.clientServing(ByteArray(9)), maxBytes = 8)
        }
        assertTrue(e.message!!.contains("exceeds 8 bytes"), "message: ${e.message}")
    }

    @Test
    fun declared_oversize_body_is_rejected_before_buffering() = runTest {
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
    }

    @Test
    fun or_null_never_swallows_coroutine_cancellation() = runTest {
        assertFailsWith<CancellationException> {
            KiteDoc.openUrlOrNull("https://example.org/a.pdf", NetFixtures.clientServing(NetFixtures.samplePdf())) {
                throw CancellationException("cancelled by caller")
            }
        }
    }

    /** The configure block is where auth headers go, so it has to reach the request. */
    @Test
    fun the_configure_block_reaches_the_request() = runTest {
        var seen: String? = null
        val client = HttpClient(
            MockEngine { request ->
                seen = request.headers["Authorization"]
                respond(ByteReadChannel(NetFixtures.samplePdf()), HttpStatusCode.OK, headersOf())
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
    fun the_resource_fetcher_reads_an_https_resource() = runTest {
        val bytes = ByteArray(300) { it.toByte() }
        val server = Server(mapOf("https://example.org/font.ttf" to bytes))
        assertContentEquals(bytes, EpubResourceFetcher(server.client).fetch("https://example.org/font.ttf"))
        assertNull(EpubResourceFetcher(server.client).fetch("https://example.org/missing.ttf"), "a 404 is null")
    }

    @Test
    fun the_resource_fetcher_never_requests_plain_http() = runTest {
        val server = Server(mapOf("http://example.org/font.ttf" to ByteArray(10)))
        assertNull(EpubResourceFetcher(server.client).fetch("http://example.org/font.ttf"))
        assertNull(EpubResourceFetcher(server.client).fetch("ftp://example.org/font.ttf"))
        assertEquals(emptyList(), server.asked, "EPUB Reading Systems 3.3, 3.3: https only")
    }

    @Test
    fun the_resource_fetcher_gives_null_past_its_cap_and_on_a_transport_failure() = runTest {
        val server = Server(mapOf("https://example.org/big.png" to ByteArray(64)))
        assertNull(EpubResourceFetcher(server.client, maxBytes = 63).fetch("https://example.org/big.png"))
        val broken = HttpClient(MockEngine { throw IllegalStateException("connection reset") })
        assertNull(EpubResourceFetcher(broken).fetch("https://example.org/big.png"))
        assertFailsWith<IllegalArgumentException> { EpubResourceFetcher(server.client, maxBytes = 0) }
    }

    @Test
    fun the_resource_fetcher_passes_cancellation_through() = runTest {
        val server = Server(mapOf("https://example.org/a.png" to ByteArray(8)))
        assertFailsWith<CancellationException> {
            EpubResourceFetcher(server.client) { throw CancellationException("cancelled by caller") }.fetch("https://example.org/a.png")
        }
    }

    @Test
    fun a_downloaded_book_draws_an_image_it_names_by_url() = runTest {
        val picture = "https://images.example.org/pic.bmp"
        val book = NetFixtures.sampleEpub("""<p><img src="$picture" width="40" height="30" alt="A picture"/></p>""")
        val server = Server(mapOf("https://example.org/a.epub" to book, picture to NetFixtures.bmp2x1()))
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

}
