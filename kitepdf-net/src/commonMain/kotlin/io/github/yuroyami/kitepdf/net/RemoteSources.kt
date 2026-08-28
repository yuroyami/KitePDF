package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * Default post-decoding response-body ceiling for remote documents (128 MiB).
 * KitePDF holds the download and parsed document in memory at once, so an
 * unbounded body can otherwise terminate a mobile or browser host process.
 */
public const val DEFAULT_MAX_REMOTE_DOCUMENT_BYTES: Int = 128 * 1024 * 1024

/**
 * Downloads [url] and opens it as whichever format it turns out to be.
 *
 * The whole document is held in memory, here and everywhere else in KitePDF:
 * the engine has no incremental reader, so a URL is a download followed by
 * [KiteDoc.open]. Nothing is cached; call it once and keep the document.
 *
 * ```kotlin
 * val client = HttpClient()               // your engine, your config
 * val doc = KiteDoc.openUrl("https://example.com/book.epub", client)
 * KiteDocView(doc, Modifier.fillMaxSize())
 * ```
 *
 * The [client] is yours: KitePDF neither creates nor closes it, so timeouts,
 * auth headers, retries and logging stay under your control. Add per-request
 * headers through [configure]:
 *
 * ```kotlin
 * KiteDoc.openUrl(url, client) { header("Authorization", "Bearer $token") }
 * ```
 *
 * @param password for an encrypted PDF; ignored for EPUB.
 * @param epubSettings page size, font size and margins for an EPUB; ignored
 *   for PDF.
 * @param configure applied to the GET before it is sent.
 * @throws KiteFormatException when the server answers with a non-2xx status,
 *   or the body is neither a PDF nor an EPUB.
 * @throws io.github.yuroyami.kitepdf.core.KiteWrongPasswordException when a
 *   downloaded PDF is encrypted and [password] does not authenticate.
 */
public suspend fun KiteDoc.openUrl(
    url: String,
    client: HttpClient,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
    configure: HttpRequestBuilder.() -> Unit = {},
): KiteDocument = open(fetch(url, client, configure), password, epubSettings)

/**
 * [openUrl] with an explicit [maxBytes] ceiling. Raise the default only when
 * the caller has an independent trust boundary and enough process memory.
 */
public suspend fun KiteDoc.openUrl(
    url: String,
    client: HttpClient,
    maxBytes: Int,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
    configure: HttpRequestBuilder.() -> Unit = {},
): KiteDocument = open(fetch(url, client, configure, maxBytes), password, epubSettings)

/** [openUrl], but null instead of an exception on any failure, network included. */
public suspend fun KiteDoc.openUrlOrNull(
    url: String,
    client: HttpClient,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
    configure: HttpRequestBuilder.() -> Unit = {},
): KiteDocument? = try {
    openUrl(url, client, password, epubSettings, configure)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** [openUrlOrNull] with an explicit response-body [maxBytes] ceiling. */
public suspend fun KiteDoc.openUrlOrNull(
    url: String,
    client: HttpClient,
    maxBytes: Int,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
    configure: HttpRequestBuilder.() -> Unit = {},
): KiteDocument? = try {
    openUrl(url, client, maxBytes, password, epubSettings, configure)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/**
 * Downloads [url] and returns the raw bytes, without opening anything.
 *
 * For the cases where you want the bytes themselves: writing a cache file,
 * hashing, handing them to [KiteDoc.formatOf] before deciding what to do.
 *
 * @throws KiteFormatException when the server answers with a non-2xx status.
 */
public suspend fun KiteDoc.downloadBytes(
    url: String,
    client: HttpClient,
    configure: HttpRequestBuilder.() -> Unit = {},
): ByteArray = fetch(url, client, configure)

/** [downloadBytes] with an explicit response-body [maxBytes] ceiling. */
public suspend fun KiteDoc.downloadBytes(
    url: String,
    client: HttpClient,
    maxBytes: Int,
    configure: HttpRequestBuilder.() -> Unit = {},
): ByteArray = fetch(url, client, configure, maxBytes)

private suspend fun fetch(
    url: String,
    client: HttpClient,
    configure: HttpRequestBuilder.() -> Unit,
    maxBytes: Int = DEFAULT_MAX_REMOTE_DOCUMENT_BYTES,
): ByteArray {
    require(maxBytes > 0) { "maxBytes must be > 0" }
    val displayUrl = urlForDiagnostics(url)
    return try {
        // prepareGet + execute streams: ktor's default SaveBody plugin would
        // otherwise buffer the complete response in memory before this code
        // runs, making maxBytes decorative. The block form also cancels any
        // unread remainder when it exits, on failure paths included.
        client.prepareGet(url) { configure() }.execute { response ->
            if (!response.status.isSuccess()) {
                throw KiteFormatException("GET $displayUrl answered ${response.status}")
            }
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > maxBytes.toLong()) {
                throw KiteFormatException(
                    "GET $displayUrl body exceeds $maxBytes bytes (Content-Length $declared)",
                )
            }

            val initialCapacity = declared
                ?.takeIf { it in 1..maxBytes.toLong() }
                ?.let { minOf(it, 64L * 1024L).toInt() }
                ?: minOf(8 * 1024, maxBytes)
            val out = ByteArrayBuilder(initialCapacity)
            val chunk = ByteArray(minOf(64 * 1024, maxBytes))
            val channel = response.bodyAsChannel()
            while (true) {
                val read = channel.readAvailable(chunk)
                if (read < 0) break
                if (read == 0) continue
                if (out.size() > maxBytes - read) {
                    throw KiteFormatException("GET $displayUrl body exceeds $maxBytes bytes")
                }
                out.append(chunk, 0, read)
            }
            if (out.size() == 0) throw KiteFormatException("GET $displayUrl answered an empty body")
            out.toByteArray()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: KiteFormatException) {
        throw failure
    } catch (failure: Exception) {
        // Deliberately don't retain the original exception as a cause: Ktor
        // transport errors can embed the complete credential-bearing URL in
        // both their message and nested stack trace.
        throw KiteFormatException(
            "GET $displayUrl failed (${failure::class.simpleName ?: "network error"})",
        )
    }
}

/** Drop URL user-info, query and fragment before a failure reaches logs/UI. */
private fun urlForDiagnostics(url: String): String {
    val query = url.indexOf('?').let { if (it < 0) url.length else it }
    val fragment = url.indexOf('#').let { if (it < 0) url.length else it }
    var safe = url.substring(0, minOf(query, fragment))
    val scheme = safe.indexOf("://")
    if (scheme >= 0) {
        val authorityStart = scheme + 3
        val authorityEnd = safe.indexOf('/', authorityStart).let { if (it < 0) safe.length else it }
        val at = safe.lastIndexOf('@', authorityEnd - 1)
        if (at >= authorityStart) safe = safe.replaceRange(authorityStart, at + 1, "***@")
    }
    return if (safe.length <= 512) safe else safe.take(511) + "…"
}
