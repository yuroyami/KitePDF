package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess

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

/** [openUrl], but null instead of an exception on any failure, network included. */
public suspend fun KiteDoc.openUrlOrNull(
    url: String,
    client: HttpClient,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
    configure: HttpRequestBuilder.() -> Unit = {},
): KiteDocument? = try {
    openUrl(url, client, password, epubSettings, configure)
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

private suspend fun fetch(
    url: String,
    client: HttpClient,
    configure: HttpRequestBuilder.() -> Unit,
): ByteArray {
    val response = client.get(url) { configure() }
    if (!response.status.isSuccess()) {
        throw KiteFormatException("GET $url answered ${response.status}")
    }
    val bytes = response.bodyAsBytes()
    if (bytes.isEmpty()) throw KiteFormatException("GET $url answered an empty body")
    return bytes
}
