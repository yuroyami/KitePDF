package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteOutlineItem
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Ready-made navigation widgets. Every widget takes a [KiteDocViewState], so they
 * work from anywhere in the tree: inside the viewport through [KiteDocView]'s
 * `overlay` slot (HUD style), in your app bar, or in a side panel.
 * They are foundation-only (no Material dependency) and deliberately
 * plain-looking; for an exact visual match with your design system, treat
 * them as references and build your own on top of [KiteDocViewState].
 */

/**
 * "current / total" page readout. Recomposes as the user scrolls or swipes.
 *
 * @param format full control over the text, e.g. `{ c, t -> "Page ${c + 1} of $t" }`.
 */
@Composable
public fun KitePageIndicator(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    format: (currentPage: Int, pageCount: Int) -> String = { c, t -> "${c + 1} / $t" },
) {
    // A reflowable book does not know its length until it is fully laid out, so
    // the total wears a "~" until then. The default format is untouched.
    val total = state.knownPageCount
    val text = if (state.isComplete) format(state.currentPage, total)
    else "~" + format(state.currentPage, total)
    BasicText(text = text, modifier = modifier, style = textStyle)
}

/**
 * Previous / "x of y" / next pill. The buttons turn off at the ends, and on a
 * fixed page ([KiteDocLayout.SinglePage]), which navigation does not turn.
 *
 * Float it over the pages via [KiteDocView]'s `overlay` slot:
 * ```kotlin
 * overlay = { s -> KiteNavigationControls(s, Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
 * ```
 * …or place it anywhere outside the viewport. It only needs the state.
 */
@Composable
public fun KiteNavigationControls(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    containerColor: Color = Color(0xB3222222),
    textStyle: TextStyle = TextStyle.Default,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChevronButton(
            pointsLeft = true,
            enabled = state.canNavigate && state.currentPage > 0,
            tint = contentColor,
            onClick = { scope.launch { state.previousPage() } },
        )
        KitePageIndicator(
            state = state,
            modifier = Modifier.padding(horizontal = 8.dp),
            textStyle = textStyle.merge(TextStyle(color = contentColor)),
        )
        ChevronButton(
            pointsLeft = false,
            enabled = state.canNavigate && state.currentPage < state.itemCount - 1,
            tint = contentColor,
            onClick = { scope.launch { state.nextPage() } },
        )
    }
}

@Composable
private fun ChevronButton(
    pointsLeft: Boolean,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (enabled) tint else tint.copy(alpha = tint.alpha * 0.35f)
        Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (pointsLeft) {
                    moveTo(w, 0f); lineTo(0f, h / 2f); lineTo(w, h)
                } else {
                    moveTo(0f, 0f); lineTo(w, h / 2f); lineTo(0f, h)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * Horizontal strip of tappable page thumbnails; the current page is outlined.
 * Tapping a thumbnail animates the [KiteDocView] sharing this [state] to that page.
 *
 * Thumbnails rasterize lazily at strip resolution (cheap), independently of
 * the main view's rasters.
 */
@Composable
public fun KiteThumbnailStrip(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    thumbnailHeight: Dp = 72.dp,
    spacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    selectedBorderColor: Color = Color(0xFF4A90D9),
    pageBackground: Color = Color.White,
) {
    val rasterizer = rememberKitePageRasterizer()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val heightPx = with(density) { thumbnailHeight.roundToPx() }.coerceAtLeast(1)
    // Thumbnails keep their bitmaps in a small cache of their own, so one that scrolls out and
    // back is a lookup, not a raster (#391). A new decorator draws differently: a new cache.
    val thumbnails = remember(state.viewerDecorator) { PageBitmapCache(THUMBNAIL_CACHE_BYTES) }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        contentPadding = contentPadding,
    ) {
        items(count = state.itemCount, key = { state.items[it].key }) { index ->
            val page = state.pageAt(index)
            val aspect = if (page != null) kitePageAspect(page) else state.placeholderAspect()
            val widthPx = (heightPx * aspect).roundToInt().coerceAtLeast(1)
            val shownFor = remember { arrayOfNulls<KitePage>(1) }
            // A thumbnail is page paint, so it takes the viewer's theme and decorator (#419).
            val theme = state.viewerTheme
            val decorator = state.viewerDecorator
            val paper = paperColor(pageBackground, theme)
            val bitmap by produceState<ImageBitmap?>(null, page, heightPx, paper, theme, decorator) {
                // Same mandatory guard as KitePageRaster: an exception escaping
                // produceState aborts the host app, so a failed thumbnail must
                // degrade to its placeholder instead. A chapter still laying out
                // has no page yet and simply shows its empty slot. A failed new
                // thumbnail of the same page keeps the old one (#430).
                val result = page?.let {
                    rasterizer.rasterizeCachedOrNull(
                        thumbnails, it, widthPx, heightPx, paper, 1f, theme, index, canvasDecorator = decorator,
                    )?.first
                }
                backOnComposeThread()
                value = when {
                    result != null -> result.also { shownFor[0] = page }
                    page != null && shownFor[0] === page -> value
                    else -> null
                }
            }
            val selected = index == state.currentPage
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .height(thumbnailHeight)
                    .aspectRatio(aspect)
                    .clip(shape)
                    .background(paper)
                    .border(
                        width = 2.dp,
                        color = if (selected) selectedBorderColor else Color.Transparent,
                        shape = shape,
                    )
                    .clickable { scope.launch { state.animateScrollToPage(index) } },
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.aspectRatio(aspect),
                    )
                }
            }
        }
    }
}

/**
 * A navigation panel over the document's outline (PDF bookmarks / EPUB table
 * of contents): an indented, clickable column of [KiteOutlineItem]s. Clicking
 * an entry scrolls to its target (and calls [onNavigate]); on a book still
 * paginating this lays out that one chapter rather than the whole book;
 * entries without one (unresolvable destinations, grouping labels) render
 * dimmed and unclickable. Plain foundation styling, like every widget here.
 *
 * @param outline defaults to the document's own [KiteDocument.outline];
 *   pass a subtree to scope the panel.
 * @param onNavigate observe navigation (e.g. to close a drawer). The scroll
 *   itself already happened.
 */
@Composable
public fun KiteOutlinePanel(
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
) {
    val scope = rememberCoroutineScope()
    val flat = remember(outline) {
        buildList {
            fun walk(items: List<KiteOutlineItem>, depth: Int) {
                for (item in items) {
                    add(item to depth)
                    walk(item.children, depth + 1)
                }
            }
            walk(outline, 0)
        }
    }
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        items(flat.size) { i ->
            val (item, depth) = flat[i]
            // A target resolves with one chapter's layout, so an entry is live
            // even while the rest of the book is still paginating.
            val target = item.target
            val page = item.pageIndex
            val isCurrent = page != null && page == state.currentPage
            BasicText(
                text = item.title,
                style = textStyle.copy(
                    color = when {
                        page == null && target == null -> disabledTextColor
                        isCurrent -> currentPageColor
                        else -> textColor
                    },
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            target != null -> Modifier.clickable {
                                scope.launch { state.scrollTo(target, animate = true) }
                                onNavigate?.invoke(item)
                            }
                            page != null -> Modifier.clickable {
                                scope.launch { state.animateScrollToPage(page) }
                                onNavigate?.invoke(item)
                            }
                            else -> Modifier
                        },
                    )
                    .padding(
                        start = indent * depth + 4.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                        end = 4.dp,
                    ),
            )
        }
    }
}

/** What the bitmaps of one thumbnail strip may hold: about sixty thumbnails of a phone. */
private const val THUMBNAIL_CACHE_BYTES = 8L * 1024 * 1024
