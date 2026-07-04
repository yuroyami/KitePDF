package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.render.ReaderTheme

/**
 * Remembers a [PdfViewState] for an EPUB [document]. Hoist it to drive an
 * [EpubView] (or the shared [PdfView] state overload) from navigation buttons,
 * a page indicator, a HUD — the state object is the single point of control.
 *
 * The state type is shared with PDF ([rememberPdfViewState]): both a
 * [io.github.yuroyami.kitepdf.PdfDocument] and an [EpubDocument] are
 * [io.github.yuroyami.kitepdf.KiteDocument]s, so one viewer path serves both.
 */
@Composable
fun rememberEpubViewState(document: EpubDocument, initialPage: Int = 0): PdfViewState =
    remember(document) { PdfViewState(document, initialPage) }

/**
 * Convenience viewer for a reflowed [EpubDocument]: opens the whole book as a
 * continuous vertical scroll, or a single [page]. Delegates to the shared
 * [PdfView] renderer — pages are rasterized once per (page, size) bucket and
 * drawn as images, exactly like PDF.
 *
 * ```kotlin
 * val doc = EpubDocument.open(bytes) ?: return
 * EpubView(doc, Modifier.fillMaxSize())
 * ```
 *
 * For full control (paged/horizontal layouts, zoom bounds, overlays, custom
 * colours) build the state yourself and use the [PdfView] state overload:
 *
 * ```kotlin
 * val state = rememberEpubViewState(doc)
 * PdfView(state, layout = PdfLayout.Paged(Orientation.Horizontal))
 * ```
 *
 * @param page index of the single page to show, or `null` (default) for the
 *   whole book as a continuous vertical scroll.
 * @param background colour painted behind page content. Ignored when [theme] is
 *   set (the theme owns the paper colour).
 * @param theme optional reading theme — [ReaderTheme.Dark] for night mode,
 *   [ReaderTheme.Sepia], or [ReaderTheme.Light]/null for the author's colours.
 *   Applied at render, so switching is instant (no re-layout).
 */
@Composable
fun EpubView(
    document: EpubDocument,
    modifier: Modifier = Modifier,
    page: Int? = null,
    background: Color = Color.White,
    theme: ReaderTheme? = null,
    pageSpacing: Dp = 8.dp,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
) {
    require(page == null || page in 0 until document.pageCount) {
        "page $page is out of bounds (document has ${document.pageCount} page(s))"
    }
    PdfView(
        state = rememberEpubViewState(document),
        modifier = modifier,
        layout = if (page != null) PdfLayout.SinglePage(page) else PdfLayout.Continuous(),
        colors = PdfViewColors(pageBackground = background, theme = theme),
        pageSpacing = pageSpacing,
        onPageRendered = onPageRendered,
    )
}
