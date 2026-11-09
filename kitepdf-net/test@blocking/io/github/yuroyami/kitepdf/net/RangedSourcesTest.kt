package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [KiteDoc.openUrlRanged] opens a document by range requests and fetches only what the reader
 * needs, counted by the server; a server without ranges still serves the whole file (#906).
 */
class RangedSourcesTest {

    /** A server of [file] that answers ranges as RFC 9110, 14 does, or ignores them, and counts what it sends. */
    private class Server(private val file: ByteArray, private val ranges: Boolean = true) {
        var served = 0L
        var requests = 0

        val client = HttpClient(
            MockEngine { request ->
                requests++
                val range = request.headers[HttpHeaders.Range]
                if (ranges && range != null) {
                    val (a, b) = range.removePrefix("bytes=").split('-').map { it.toLong() }
                    val last = minOf(b, file.size - 1L)
                    val body = file.copyOfRange(a.toInt(), last.toInt() + 1)
                    served += body.size
                    respond(
                        ByteReadChannel(body), HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange, "bytes $a-$last/${file.size}"),
                    )
                } else {
                    served += file.size
                    respond(ByteReadChannel(file), HttpStatusCode.OK)
                }
            },
        )
    }

    @OptIn(KiteRawApi::class)
    private fun pdfWithUnusedStream(): ByteArray {
        val editor = PdfDocument.open(NetFixtures.samplePdf()).edit()
        editor.addObject(PdfStream(PdfDictionary(emptyMap()), ByteArray(8 * 1024 * 1024) { (it * 13).toByte() }))
        return editor.saveIncremental()
    }

    @Test
    fun a_pdf_shows_its_first_page_after_a_fraction_of_the_file() = runTest {
        val file = pdfWithUnusedStream()
        val server = Server(file)

        val doc = assertIs<PdfDocument>(KiteDoc.openUrlRanged("https://example.org/big.pdf", server.client))

        assertEquals(2, doc.pageCount)
        assertTrue("page one" in doc.pages[0].extractText(), doc.pages[0].extractText())
        assertTrue(server.served < 1024 * 1024, "served ${server.served} bytes of ${file.size}")
    }

    @Test
    fun an_epub_opens_by_ranges() = runTest {
        val server = Server(NetFixtures.sampleEpub())
        assertIs<EpubDocument>(KiteDoc.openUrlRanged("https://example.org/b.epub", server.client))
    }

    @Test
    fun a_server_that_ignores_ranges_serves_the_whole_file() = runTest {
        val file = NetFixtures.samplePdf()
        val server = Server(file, ranges = false)
        val doc = assertIs<PdfDocument>(KiteDoc.openUrlRanged("https://example.org/a.pdf", server.client))
        assertEquals(2, doc.pageCount)
        assertEquals(2, server.requests, "the probe and the whole download")
    }

    @Test
    fun another_status_is_a_format_exception() = runTest {
        val client = HttpClient(MockEngine { respond("gone", HttpStatusCode.NotFound) })
        assertFailsWith<KiteFormatException> { KiteDoc.openUrlRanged("https://user:secret@example.org/a.pdf", client) }
    }
}
