package io.github.yuroyami.kitepdf.webview

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * What an Android web view over an island asks of the book (#41). Every request under [urls],
 * the book's own origin, gets its file from [files]; any other request gets an empty 404, so the
 * web view loads nothing from the network. A navigation of the page to another document goes
 * to [onLink] instead, with the zip path of a file of the book or the URL of anything else.
 *
 * A remote island, with no [urls], loads from the network on its own terms; its navigations
 * still go to [onLink].
 */
internal class BookWebViewClient(
    private val files: BookFiles?,
    private val urls: BookUrls?,
    private val islandUrl: String,
    private val onLink: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        return answer(url)
    }

    /** The answer to a request for [url], or null to let the web view load it itself. */
    fun answer(url: String): WebResourceResponse? {
        if (urls == null || files == null) return null
        val href = urls.hrefOf(url)
        val response = if (href == null) BookResponse(404, "text/plain", ByteArray(0)) else files.respond(href.substringBefore('#'))
        return WebResourceResponse(
            response.mimeType, response.charset ?: "utf-8", response.status, response.reason,
            response.headers, ByteArrayInputStream(response.body),
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        // A frame inside the island loads its own documents from the book.
        if (!request.isForMainFrame) return false
        return follows(url)
    }

    /** Hands a navigation to [url] of the island's page to [onLink], unless it stays in the island's document. */
    fun follows(url: String): Boolean {
        if (url.substringBefore('#') == islandUrl.substringBefore('#')) return false
        link(url)
        return true
    }

    /** Hands [url], a link the page opened, to [onLink]: as a zip path for a file of the book. */
    fun link(url: String) {
        onLink(urls?.hrefOf(url) ?: url)
    }
}

/**
 * What a page calls as `window.kitepdfHost`: the island script hands it each link the page
 * opens. The web view calls it on a thread of its own, so the link goes on to the main thread.
 */
internal class AndroidLinkBridge(private val view: WebView, private val client: BookWebViewClient) {
    @JavascriptInterface
    fun link(href: String) {
        view.post { client.link(href) }
    }
}
