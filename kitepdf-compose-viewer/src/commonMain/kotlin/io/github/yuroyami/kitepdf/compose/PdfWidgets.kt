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
import io.github.yuroyami.kitepdf.KiteDocument
import io.github.yuroyami.kitepdf.KiteOutlineItem
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Ready-made navigation widgets. Every widget takes a [PdfViewState], so they
 * work from anywhere in the tree: inside the viewport through [PdfView]'s
 * `overlay` slot (HUD style), in your app bar, in a side panel — wherever.
 * They are foundation-only (no Material dependency) and deliberately
 * plain-looking; for an exact visual match with your design system, treat
 * them as references and build your own on top of [PdfViewState].
 */

/**
 * "current / total" page readout. Recomposes as the user scrolls or swipes.
 *
 * @param format full control over the text, e.g. `{ c, t -> "Page ${c + 1} of $t" }`.
 */
@Composable
public fun PdfPageIndicator(
    state: PdfViewState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    format: (currentPage: Int, pageCount: Int) -> String = { c, t -> "${c + 1} / $t" },
) {
    BasicText(
        text = format(state.currentPage, state.pageCount),
        modifier = modifier,
        style = textStyle,
    )
}

/**
 * Previous / "x of y" / next pill. Buttons auto-disable at the ends.
 *
 * Float it over the pages via [PdfView]'s `overlay` slot:
 * ```kotlin
 * overlay = { s -> PdfNavigationControls(s, Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
 * ```
 * …or place it anywhere outside the viewport — it only needs the state.
 */
@Composable
public fun PdfNavigationControls(
    state: PdfViewState,
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
            enabled = state.currentPage > 0,
            tint = contentColor,
            onClick = { scope.launch { state.previousPage() } },
        )
        PdfPageIndicator(
            state = state,
            modifier = Modifier.padding(horizontal = 8.dp),
            textStyle = textStyle.merge(TextStyle(color = contentColor)),
        )
        ChevronButton(
            pointsLeft = false,
            enabled = state.currentPage < state.pageCount - 1,
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
 * Tapping a thumbnail animates the [PdfView] sharing this [state] to that page.
 *
 * Thumbnails rasterize lazily at strip resolution (cheap), independently of
 * the main view's rasters.
 */
@Composable
public fun PdfThumbnailStrip(
    state: PdfViewState,
    modifier: Modifier = Modifier,
    thumbnailHeight: Dp = 72.dp,
    spacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    selectedBorderColor: Color = Color(0xFF4A90D9),
    pageBackground: Color = Color.White,
) {
    val rasterizer = rememberPdfRasterizer()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val heightPx = with(density) { thumbnailHeight.roundToPx() }.coerceAtLeast(1)

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        contentPadding = contentPadding,
    ) {
        items(count = state.pageCount, key = { it }) { index ->
            val page = state.document.pages[index]
            val aspect = pdfPageAspect(page)
            val widthPx = (heightPx * aspect).roundToInt().coerceAtLeast(1)
            val bitmap by produceState<ImageBitmap?>(null, page, heightPx, pageBackground) {
                value = rasterizer.rasterizeOffMain(page, widthPx, heightPx, pageBackground)
            }
            val selected = index == state.currentPage
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .height(thumbnailHeight)
                    .aspectRatio(aspect)
                    .clip(shape)
                    .background(pageBackground)
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
 * an entry with a resolved page scrolls there (and calls [onNavigate]);
 * entries without one (unresolvable destinations, grouping labels) render
 * dimmed and unclickable. Plain foundation styling, like every widget here.
 *
 * @param outline defaults to the document's own [KiteDocument.outline] (T-25);
 *   pass a subtree to scope the panel.
 * @param onNavigate observe navigation (e.g. to close a drawer). The scroll
 *   itself already happened.
 */
@Composable
public fun PdfOutlinePanel(
    state: PdfViewState,
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
            val page = item.pageIndex
            val isCurrent = page != null && page == state.currentPage
            BasicText(
                text = item.title,
                style = textStyle.copy(
                    color = when {
                        page == null -> disabledTextColor
                        isCurrent -> currentPageColor
                        else -> textColor
                    },
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (page != null) {
                            Modifier.clickable {
                                scope.launch { state.animateScrollToPage(page) }
                                onNavigate?.invoke(item)
                            }
                        } else Modifier,
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
