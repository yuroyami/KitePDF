@file:Suppress("unused", "DEPRECATION")

package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteOutlineItem
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.epub.EpubDocument

/*
 * Migration aliases, this release cycle only. The viewer's format-neutral
 * surface lost its `Pdf` prefix: every type below drives an EPUB exactly as
 * it drives a PDF, so `Pdf` in the name was a lie. `Pdf`/`Epub` now mark only
 * the entry points that really are one format.
 */

@Deprecated("Renamed to KiteDocViewState", ReplaceWith("KiteDocViewState"))
public typealias PdfViewState = KiteDocViewState

@Deprecated("Renamed to KiteDocViewColors", ReplaceWith("KiteDocViewColors"))
public typealias PdfViewColors = KiteDocViewColors

@Deprecated("Renamed to KiteDocLayout", ReplaceWith("KiteDocLayout"))
public typealias PdfLayout = KiteDocLayout

@Deprecated("Renamed to KiteZoomSpec", ReplaceWith("KiteZoomSpec"))
public typealias PdfZoomSpec = KiteZoomSpec

@Deprecated("Renamed to KiteRenderSpec", ReplaceWith("KiteRenderSpec"))
public typealias PdfRenderSpec = KiteRenderSpec

@Deprecated("Renamed to KiteHighlight", ReplaceWith("KiteHighlight"))
public typealias PdfHighlight = KiteHighlight

@Deprecated("Renamed to KiteMarkerSide", ReplaceWith("KiteMarkerSide"))
public typealias PdfMarkerSide = KiteMarkerSide

@Deprecated("Renamed to KiteSelectionHandleEdge", ReplaceWith("KiteSelectionHandleEdge"))
public typealias PdfSelectionHandleEdge = KiteSelectionHandleEdge

@Deprecated("Renamed to KiteSelectionHandlePainter", ReplaceWith("KiteSelectionHandlePainter"))
public typealias PdfSelectionHandlePainter = KiteSelectionHandlePainter

@Deprecated("Renamed to KiteSelectionHandleDefaults", ReplaceWith("KiteSelectionHandleDefaults"))
public typealias PdfSelectionHandleDefaults = KiteSelectionHandleDefaults

@Deprecated("Renamed to KiteSelectionMenuItem", ReplaceWith("KiteSelectionMenuItem"))
public typealias PdfSelectionMenuItem = KiteSelectionMenuItem

@Deprecated("Renamed to KiteSelectionMenuDefaults", ReplaceWith("KiteSelectionMenuDefaults"))
public typealias PdfSelectionMenuDefaults = KiteSelectionMenuDefaults

@Deprecated("Renamed to KitePageRasterizer", ReplaceWith("KitePageRasterizer"))
public typealias PdfRasterizer = KitePageRasterizer

@Deprecated("Renamed to KiteTextSelection", ReplaceWith("KiteTextSelection"))
public typealias TextSelection = KiteTextSelection

@Deprecated("Renamed to KitePageHit", ReplaceWith("KitePageHit"))
public typealias PageHit = KitePageHit

/** Old callbacks took a [PdfAction]; EPUB links were faked into one. */
private fun KiteLinkAction.toLegacyAction(): PdfAction = when (this) {
    is KiteLinkAction.Pdf -> action
    is KiteLinkAction.Uri -> PdfAction.Uri(uri, isMap = false, raw = PdfDictionary(emptyMap()))
}

@Deprecated(
    "Renamed to rememberKiteDocViewState, which takes any KiteDocument",
    ReplaceWith("rememberKiteDocViewState(document, initialPage)"),
)
@Composable
public fun rememberPdfViewState(document: PdfDocument, initialPage: Int = 0): KiteDocViewState =
    rememberKiteDocViewState(document, initialPage)

@Deprecated(
    "Renamed to rememberKiteDocViewState, which takes any KiteDocument",
    ReplaceWith("rememberKiteDocViewState(document, initialPage)"),
)
@Composable
public fun rememberEpubViewState(document: EpubDocument, initialPage: Int = 0): KiteDocViewState =
    rememberKiteDocViewState(document, initialPage)

@Deprecated("Renamed to rememberKitePageRasterizer", ReplaceWith("rememberKitePageRasterizer()"))
@Composable
public fun rememberPdfRasterizer(): KitePageRasterizer = rememberKitePageRasterizer()

@Deprecated("Renamed to KiteDocView", ReplaceWith("KiteDocView(state, modifier, layout, zoomSpec, renderSpec, colors, pageSpacing, userScrollEnabled, selectionEnabled, onPageRendered, pagePlaceholder, overlay, onTap)"))
@Composable
public fun PdfView(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    layout: KiteDocLayout = KiteDocLayout.Default,
    zoomSpec: KiteZoomSpec = KiteZoomSpec(),
    renderSpec: KiteRenderSpec = KiteRenderSpec.Default,
    colors: KiteDocViewColors = KiteDocViewColors(),
    pageSpacing: Dp = 8.dp,
    userScrollEnabled: Boolean = true,
    selectionEnabled: Boolean = true,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
    pagePlaceholder: (@Composable (pageIndex: Int) -> Unit)? = null,
    overlay: (@Composable BoxScope.(KiteDocViewState) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onLinkTap: ((PdfAction) -> Boolean)? = null,
) {
    KiteDocView(
        state = state,
        modifier = modifier,
        layout = layout,
        zoomSpec = zoomSpec,
        renderSpec = renderSpec,
        colors = colors,
        pageSpacing = pageSpacing,
        userScrollEnabled = userScrollEnabled,
        selectionEnabled = selectionEnabled,
        onPageRendered = onPageRendered,
        pagePlaceholder = pagePlaceholder,
        overlay = overlay,
        onTap = onTap,
        onLinkTap = onLinkTap?.let { cb -> { link: KiteLinkAction -> cb(link.toLegacyAction()) } },
    )
}

@Deprecated("Renamed to KiteDocView, which takes any KiteDocument", ReplaceWith("KiteDocView(document, modifier, page, background, null, pageSpacing, selectionEnabled, onPageRendered, onTap)"))
@Composable
public fun PdfView(
    document: PdfDocument,
    modifier: Modifier = Modifier,
    page: Int? = null,
    background: Color = Color.White,
    pageSpacing: Dp = 8.dp,
    selectionEnabled: Boolean = true,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onLinkTap: ((PdfAction) -> Boolean)? = null,
) {
    KiteDocView(
        document = document,
        modifier = modifier,
        page = page,
        background = background,
        pageSpacing = pageSpacing,
        selectionEnabled = selectionEnabled,
        onPageRendered = onPageRendered,
        onTap = onTap,
        onLinkTap = onLinkTap?.let { cb -> { link: KiteLinkAction -> cb(link.toLegacyAction()) } },
    )
}

@Deprecated("Renamed to KiteDocView, which takes any KiteDocument", ReplaceWith("KiteDocView(document, modifier, page, background, theme, pageSpacing, true, onPageRendered, onTap)"))
@Composable
public fun EpubView(
    document: EpubDocument,
    modifier: Modifier = Modifier,
    page: Int? = null,
    background: Color = Color.White,
    theme: ReaderTheme? = null,
    pageSpacing: Dp = 8.dp,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onLinkTap: ((PdfAction) -> Boolean)? = null,
) {
    KiteDocView(
        document = document,
        modifier = modifier,
        page = page,
        background = background,
        theme = theme,
        pageSpacing = pageSpacing,
        onPageRendered = onPageRendered,
        onTap = onTap,
        onLinkTap = onLinkTap?.let { cb -> { link: KiteLinkAction -> cb(link.toLegacyAction()) } },
    )
}

@Deprecated("Renamed to KiteSelectionMenu", ReplaceWith("KiteSelectionMenu(state, items, modifier, highlightColors, onHighlightColorPicked, clearSelectionOnColorPick, alignment, showWhileSelecting, containerColor, contentColor, container, itemContent, colorSwatch)"))
@Composable
public fun BoxScope.PdfSelectionMenu(
    state: KiteDocViewState,
    items: List<KiteSelectionMenuItem>,
    modifier: Modifier = Modifier,
    highlightColors: List<Color> = emptyList(),
    onHighlightColorPicked: ((KiteTextSelection, Color) -> Unit)? = null,
    clearSelectionOnColorPick: Boolean = true,
    alignment: Alignment = Alignment.TopCenter,
    showWhileSelecting: Boolean = false,
    containerColor: Color = KiteSelectionMenuDefaults.ContainerColor,
    contentColor: Color = KiteSelectionMenuDefaults.ContentColor,
    container: (@Composable (selection: KiteTextSelection, content: @Composable () -> Unit) -> Unit)? = null,
    itemContent: (@Composable (item: KiteSelectionMenuItem, selection: KiteTextSelection) -> Unit)? = null,
    colorSwatch: (@Composable (color: Color, onPick: () -> Unit) -> Unit)? = null,
): Unit = KiteSelectionMenu(
    state, items, modifier, highlightColors, onHighlightColorPicked,
    clearSelectionOnColorPick, alignment, showWhileSelecting, containerColor,
    contentColor, container, itemContent, colorSwatch,
)

@Deprecated("Renamed to KitePageIndicator", ReplaceWith("KitePageIndicator(state, modifier, textStyle, format)"))
@Composable
public fun PdfPageIndicator(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    format: (currentPage: Int, pageCount: Int) -> String = { c, t -> "${c + 1} / $t" },
): Unit = KitePageIndicator(state, modifier, textStyle, format)

@Deprecated("Renamed to KiteNavigationControls", ReplaceWith("KiteNavigationControls(state, modifier, contentColor, containerColor, textStyle)"))
@Composable
public fun PdfNavigationControls(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    containerColor: Color = Color(0xB3222222),
    textStyle: TextStyle = TextStyle.Default,
): Unit = KiteNavigationControls(state, modifier, contentColor, containerColor, textStyle)

@Deprecated("Renamed to KiteThumbnailStrip", ReplaceWith("KiteThumbnailStrip(state, modifier, thumbnailHeight, spacing, contentPadding, selectedBorderColor, pageBackground)"))
@Composable
public fun PdfThumbnailStrip(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    thumbnailHeight: Dp = 72.dp,
    spacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    selectedBorderColor: Color = Color(0xFF4A90D9),
    pageBackground: Color = Color.White,
): Unit = KiteThumbnailStrip(
    state, modifier, thumbnailHeight, spacing, contentPadding,
    selectedBorderColor, pageBackground,
)

@Deprecated("Renamed to KiteOutlinePanel", ReplaceWith("KiteOutlinePanel(state, modifier, outline, contentPadding, textStyle, textColor, disabledTextColor, currentPageColor, indent, onNavigate)"))
@Composable
public fun PdfOutlinePanel(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    outline: List<KiteOutlineItem> = state.document.outline,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    textStyle: TextStyle = TextStyle(fontSize = 14.sp),
    textColor: Color = Color(0xFF202124),
    disabledTextColor: Color = Color(0xFF9AA0A6),
    currentPageColor: Color = Color(0xFF4A90D9),
    indent: Dp = 16.dp,
    onNavigate: ((KiteOutlineItem) -> Unit)? = null,
): Unit = KiteOutlinePanel(
    state, modifier, outline, contentPadding, textStyle, textColor,
    disabledTextColor, currentPageColor, indent, onNavigate,
)
