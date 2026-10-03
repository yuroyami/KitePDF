package io.github.yuroyami.kitepdf.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.compose.KitePageOverlayScope
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the scripted content of an EPUB page in the platform's own web engine, for the
 * `pageOverlay` of `KiteDocView` (#41):
 *
 * ```kotlin
 * KiteDocView(state, pageOverlay = { KiteScriptOverlay() })
 * ```
 *
 * Each island of the page, as [webIslands] finds them, gets a web view over its box: the
 * document of an inline frame or an HTML object, or a whole scripted fixed-layout page. The
 * rest of the page stays on the library's own rendering. Every file a web view asks for comes
 * from the book, under an origin of the book's own, and a request for anything else gets no
 * answer, so an island loads nothing from the network. A link that a web view opens goes the
 * way of a tapped link of the page: `onLinkTap` first, then the viewer.
 *
 * A web view lives while its page is composed. Scrolling the page away and back loads its
 * document again, so the values of its forms and the state of its scripts start over.
 *
 * Where the platform has no web engine, as on native macOS, or on the desktop JVM without
 * JavaFX, nothing is mounted and the page shows what the library renders: the fallback of an
 * object, an empty frame, and the markup of a fixed-layout page without its scripts.
 *
 * @param allowRemote also show an embedded document from an `https` URL, which loads from the
 *   network on its own terms.
 */
@Composable
public fun KitePageOverlayScope.KiteScriptOverlay(allowRemote: Boolean = false) {
    val epubPage = page as? EpubPage ?: return
    if (!isWebViewAvailable()) return
    // The islands read the page's layout, which can mean laying its chapter out: off the main thread.
    val islands by produceState(emptyList<EpubWebIsland>(), epubPage, allowRemote) {
        value = withContext(Dispatchers.Default) { runCatching { epubPage.webIslands(allowRemote) }.getOrDefault(emptyList()) }
    }
    islands.forEachIndexed { index, island ->
        key(epubPage, index) {
            EpubWebView(
                island, epubPage.document,
                Modifier.displayRect(island.rect),
                onLink = { href -> followLink(href, island.rect) },
            )
        }
    }
}

/**
 * Shows [island] of [document] in the platform's own web engine, scaled to fill [modifier]'s
 * box. Every file the web view asks for comes from the book, and nothing from the network unless
 * the island itself is an `https` document. [onLink] gets each link the web view opens instead
 * of following it: a zip path with its fragment for a file of the book, as `EpubLink.href` gives
 * one, or the URL of anything else. It is called on the main thread.
 *
 * Where the platform has no web engine, this shows nothing.
 */
@Composable
public fun EpubWebView(
    island: EpubWebIsland,
    document: EpubDocument,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit = {},
) {
    if (!isWebViewAvailable()) return
    PlatformWebView(island, document, onLink, modifier)
}

/**
 * Whether [EpubWebView] can show anything here: false where the platform has no web engine, and
 * on the desktop JVM when JavaFX is missing or cannot start, such as without a display.
 */
public fun isWebViewAvailable(): Boolean = platformWebViewAvailable()

/** The platform's web view over [island], with its files from [document] and its links to [onLink]. */
@Composable
internal expect fun PlatformWebView(
    island: EpubWebIsland,
    document: EpubDocument,
    onLink: (String) -> Unit,
    modifier: Modifier,
)

internal expect fun platformWebViewAvailable(): Boolean
