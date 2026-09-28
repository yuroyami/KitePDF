package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.math.roundToInt

/**
 * What the `pageOverlay` of [KiteDocView] draws in: one page, in the page's own frame. The frame
 * moves and scales with the page, so an element placed on a rectangle of the page stays on that
 * rectangle at any zoom, and grows with the page (#30).
 *
 * An element with neither [displayRect] nor [pageRect] sits at the top-left corner of the page,
 * measured to fit inside the page.
 */
@Stable
public interface KitePageOverlayScope {
    /** The index of the page, as [KiteDocViewState.currentPage] and [KitePageHit.pageIndex] count it. */
    public val pageIndex: Int

    /** The page under the overlay. */
    public val page: KitePage

    /**
     * Sizes the element to [rect] and places it there. [rect] is in the display space of the
     * page: the space of [KiteDocViewState.hitTestDisplay], of search hits, and of the links and
     * media elements of an EPUB page.
     */
    public fun Modifier.displayRect(rect: KiteRectangle): Modifier

    /**
     * Sizes the element to [rect] and places it there. [rect] is in the page's own space: the
     * space of [KiteDocViewState.hitTest] and of the rectangle of a PDF annotation.
     */
    public fun Modifier.pageRect(rect: KiteRectangle): Modifier
}

/** The `pageOverlay` of the viewer that composes the page. */
internal val LocalKitePageOverlay = compositionLocalOf<(@Composable KitePageOverlayScope.() -> Unit)?> { null }

/**
 * [rect], in the page space of this page, as the display-space rectangle it covers, with the
 * smaller y in [KiteRectangle.bottom].
 */
internal fun KitePage.pageToDisplay(rect: KiteRectangle): KiteRectangle {
    val base = displayToDeviceBase()
    val corners = listOf(
        base.transformPoint(rect.left, rect.bottom),
        base.transformPoint(rect.right, rect.top),
        base.transformPoint(rect.left, rect.top),
        base.transformPoint(rect.right, rect.bottom),
    )
    return KiteRectangle(
        corners.minOf { it.first }, corners.minOf { it.second },
        corners.maxOf { it.first }, corners.maxOf { it.second },
    )
}

/** Lays [content] out over [page], each element on the rectangle it asks for. */
@Composable
internal fun PageOverlay(
    page: KitePage,
    pageIndex: Int,
    content: @Composable KitePageOverlayScope.() -> Unit,
    modifier: Modifier,
) {
    val scope = remember(page, pageIndex) { PageOverlayScope(pageIndex, page) }
    Layout(content = { scope.content() }, modifier = modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val sx = if (page.displayWidth > 0.0) width / page.displayWidth else 0.0
        val sy = if (page.displayHeight > 0.0) height / page.displayHeight else 0.0
        // An edge rounds on its own, so two rectangles that share an edge share it on screen. An
        // edge far off the page stops one page away, so a broken rectangle still fits a layout.
        fun edge(value: Double, scale: Double, side: Int): Int =
            (value * scale).takeIf { it.isFinite() }?.coerceIn(-side.toDouble(), 2.0 * side)?.roundToInt() ?: 0
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = measurables.map { measurable ->
            val rect = (measurable.parentData as? OverlayRectNode)?.rect
                ?: return@map measurable.measure(loose) to IntOffset.Zero
            val left = edge(rect.left, sx, width)
            val top = edge(rect.bottom, sy, height)
            val right = edge(rect.right, sx, width)
            val bottom = edge(rect.top, sy, height)
            measurable.measure(Constraints.fixed((right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))) to
                IntOffset(left, top)
        }
        layout(width, height) {
            // A page does not mirror in a right-to-left layout, so its overlay does not either.
            for ((placeable, at) in placed) placeable.place(at)
        }
    }
}

private class PageOverlayScope(override val pageIndex: Int, override val page: KitePage) : KitePageOverlayScope {
    override fun Modifier.displayRect(rect: KiteRectangle): Modifier = this then OverlayRectElement(rect.normalized())

    override fun Modifier.pageRect(rect: KiteRectangle): Modifier = this then OverlayRectElement(page.pageToDisplay(rect))
}

/** The display-space rectangle an overlay element asks for, with the smaller y in [KiteRectangle.bottom]. */
private class OverlayRectNode(var rect: KiteRectangle) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = this@OverlayRectNode
}

private class OverlayRectElement(val rect: KiteRectangle) : ModifierNodeElement<OverlayRectNode>() {
    override fun create(): OverlayRectNode = OverlayRectNode(rect)

    override fun update(node: OverlayRectNode) {
        node.rect = rect
    }

    override fun equals(other: Any?): Boolean = other is OverlayRectElement && other.rect == rect

    override fun hashCode(): Int = rect.hashCode()
}
