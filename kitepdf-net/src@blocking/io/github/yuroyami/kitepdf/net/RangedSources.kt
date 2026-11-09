package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Opens the document at [url] by HTTP range requests (RFC 9110, 14), fetching only the parts that
 * the format reader asks for, so page 1 of a large PDF, or the first chapter of a large EPUB,
 * shows before the rest of the file arrives, and a file larger than the heap opens (#906).
 *
 * It asks for the first 64 KiB with a `Range` header. When the server answers 206 Partial Content
 * with the file's size, the document opens from a [KiteByteSource] whose reads become range
 * requests on [client]: [KiteDoc.open] of a source, on a background dispatcher, as [openUrl] does.
 * A server that ignores the header, and answers 200 with the whole file, gets the same request
 * again through [openUrl], which downloads it whole up to [DEFAULT_MAX_REMOTE_DOCUMENT_BYTES].
 *
 * Each later page, image or chapter makes the requests its bytes need, on the thread that reads
 * it, so read pages off the main thread, as the viewer does. A document of a format with no
 * reader on a source (SVG, FB2, MOBI, HTML) is read whole by range requests, up to
 * [DEFAULT_MAX_REMOTE_DOCUMENT_BYTES]. [configure] applies to every request. The file must not
 * change while the document lives; a range that comes back of another size fails its read, as a
 * damaged file would.
 *
 * Not on JS and Wasm, where a read cannot wait for a request.
 *
 * @throws KiteFormatException when the server answers with another status, or the file is no
 *   readable document.
 * @throws io.github.yuroyami.kitepdf.core.KiteWrongPasswordException when a PDF is encrypted and
 *   [password] does not authenticate.
 */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, kotlin.coroutines.cancellation.CancellationException::class)
public suspend fun KiteDoc.openUrlRanged(
    url: String,
    client: HttpClient,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
    configure: HttpRequestBuilder.() -> Unit = {},
): KiteDocument {
    val probe = try {
        rangeGet(url, client, configure, 0L, HttpRangeSource.CHUNK - 1L)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: KiteFormatException) {
        throw failure
    } catch (failure: Exception) {
        // As in fetch: no cause, which can carry the credential-bearing URL.
        throw KiteFormatException("GET ${urlForDiagnostics(url)} failed (${failure::class.simpleName ?: "network error"})")
    }
    if (probe == null) return openUrl(url, client, password, epubSettings, configure)
    val source = HttpRangeSource(url, client, configure, probe.total, probe.bytes)
    return withContext(Dispatchers.Default) {
        open(source, password, epubSettings, maxWholeBytes = DEFAULT_MAX_REMOTE_DOCUMENT_BYTES)
    }
}

/** A range of the file and the file's whole size, from a 206 answer. */
private class Range(val total: Long, val bytes: ByteArray)

/**
 * GET [first]..[last] of [url]: the bytes and the size from Content-Range on a 206, null on a 200,
 * which ignores the range, and a [KiteFormatException] on any other status.
 */
private suspend fun rangeGet(url: String, client: HttpClient, configure: HttpRequestBuilder.() -> Unit, first: Long, last: Long): Range? =
    client.prepareGet(url) {
        configure()
        header(HttpHeaders.Range, "bytes=$first-$last")
    }.execute { response ->
        when (response.status) {
            HttpStatusCode.PartialContent -> {
                val total = contentRangeTotal(response, first)
                    ?: throw KiteFormatException("GET ${urlForDiagnostics(url)} answered 206 without the file's size in Content-Range")
                val expected = (minOf(last, total - 1) - first + 1).toInt()
                Range(total, readExactly(response, expected, url))
            }
            HttpStatusCode.OK -> null
            else -> throw KiteFormatException("GET ${urlForDiagnostics(url)} answered ${response.status}")
        }
    }

/** The size after the slash of `Content-Range: bytes first-last/size`, when the range starts at [first]. */
private fun contentRangeTotal(response: HttpResponse, first: Long): Long? {
    val value = response.headers[HttpHeaders.ContentRange]?.trim() ?: return null
    if (!value.startsWith("bytes ")) return null
    val (span, total) = value.removePrefix("bytes ").split('/').takeIf { it.size == 2 } ?: return null
    if (span.substringBefore('-').trim().toLongOrNull() != first) return null
    return total.trim().toLongOrNull()?.takeIf { it > 0 }
}

/** The body, which must hold exactly [length] bytes. */
private suspend fun readExactly(response: HttpResponse, length: Int, url: String): ByteArray {
    val out = ByteArray(length)
    var got = 0
    val channel = response.bodyAsChannel()
    while (got < length) {
        val n = channel.readAvailable(out, got, length - got)
        if (n < 0) break
        got += n
    }
    if (got != length || channel.readAvailable(ByteArray(1)) > 0) {
        throw KiteFormatException("GET ${urlForDiagnostics(url)} answered a range of another size than asked")
    }
    return out
}

/**
 * The file at [url] as a [KiteByteSource]: each read a range request of [CHUNK] bytes or more on
 * [client], blocking the reading thread, and the last few chunks kept, as the parsers read near
 * where they read last. [first] is the probe's chunk at offset 0.
 */
private class HttpRangeSource(
    private val url: String,
    private val client: HttpClient,
    private val configure: HttpRequestBuilder.() -> Unit,
    override val size: Long,
    first: ByteArray,
) : KiteByteSource {

    private val chunks = LinkedHashMap<Long, ByteArray>().apply { put(0L, first) }

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val start = position - position % CHUNK
        val chunk = chunks.remove(start) ?: fetch(start)
        chunks[start] = chunk
        if (chunks.size > KEPT) chunks.remove(chunks.keys.first())
        val at = (position - start).toInt()
        val n = minOf(length, chunk.size - at)
        chunk.copyInto(into, offset, at, at + n)
        return n
    }

    private fun fetch(start: Long): ByteArray = runBlocking {
        rangeGet(url, client, configure, start, minOf(start + CHUNK, size) - 1)?.bytes
            ?: throw KiteFormatException("GET ${urlForDiagnostics(url)} stopped answering ranges")
    }

    companion object {
        const val CHUNK = 64 * 1024

        /** 512 KiB of chunks. */
        const val KEPT = 8
    }
}
