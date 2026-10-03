package io.github.yuroyami.kitepdf.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.util.Collections
import java.util.WeakHashMap

internal actual fun platformWebViewAvailable(): Boolean = true

/**
 * Android's web view over an island (#41). It answers every request from the book through
 * [BookWebViewClient], under an `https` origin of the book's own, and hands each link the page
 * opens, and each navigation of it, to [onLink]. The document lays out in the island's CSS size
 * and scales to the box.
 */
@Composable
internal actual fun PlatformWebView(
    island: EpubWebIsland,
    document: EpubDocument,
    onLink: (String) -> Unit,
    modifier: Modifier,
) {
    val remote = hasScheme(island.href)
    val urls = remember(document, remote) { if (remote) null else AndroidBooks.urlsOf(document) }
    val files = remember(document, remote) { if (remote) null else BookFiles(document) }
    val url = remember(urls, island.href) { urls?.urlOf(island.href) ?: island.href }
    val currentOnLink by rememberUpdatedState(onLink)
    AndroidView(
        modifier = modifier.semantics { contentDescription = island.href },
        factory = { context ->
            IslandWebView(context, url, island.contentWidth).apply {
                val client = BookWebViewClient(files, urls, url) { href -> currentOnLink(href) }
                webViewClient = client
                addJavascriptInterface(AndroidLinkBridge(this, client), "kitepdfHost")
            }
        },
        onRelease = { view -> view.destroy() },
    )
}

/** The origin of each book's web views on Android: `https`, under a name that cannot resolve. */
internal object AndroidBooks {
    private val bases = Collections.synchronizedMap(WeakHashMap<EpubDocument, BookUrls>())

    fun urlsOf(document: EpubDocument): BookUrls =
        bases.getOrPut(document) { BookUrls("https://b${randomToken(8)}.book.kitepdf.invalid/") }
}

/**
 * A web view that loads [url] once it has a size, laid out [contentWidth] CSS pixels wide. The
 * first width sets the web view's scale; a later one, as the reader zooms, scales the document
 * by CSS zoom instead, which keeps the page and its state.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class IslandWebView(context: Context, private val url: String, private val contentWidth: Double) : WebView(context) {
    private var firstWidth = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || contentWidth <= 0.0) return
        if (firstWidth == 0) {
            firstWidth = w
            // Percent of a device pixel to each CSS pixel: the content's width fills the box.
            setInitialScale((100.0 * w / contentWidth).toInt().coerceAtLeast(1))
            loadUrl(url)
        } else {
            val zoom = w.toDouble() / firstWidth
            evaluateJavascript("document.documentElement.style.zoom = '$zoom';", null)
        }
    }
}
