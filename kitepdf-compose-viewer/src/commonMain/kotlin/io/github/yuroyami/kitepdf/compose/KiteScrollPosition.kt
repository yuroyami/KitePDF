package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Immutable
import io.github.yuroyami.kitepdf.core.KiteLocation

/**
 * A continuous viewport anchor that a host can persist between reading sessions.
 * [location] names the leading visible page, and [offsetPx] counts unzoomed
 * layout pixels scrolled beyond that page's leading edge along the scroll axis.
 * It works for vertical and horizontal continuous layouts, including right-to-left
 * layout direction. It does not save zoom or cross-axis pan.
 *
 * Restore with [KiteDocViewState.scrollTo] or [rememberKiteDocViewState] under
 * the same viewport, density and page layout for an exact visual match. When
 * an EPUB reflows, use [KiteDocViewState.currentBookmark] for a content anchor;
 * a page-and-pixel position cannot remain exact after repagination.
 */
@Immutable
public data class KiteScrollPosition(
    val location: KiteLocation,
    val offsetPx: Int = 0,
) {
    init {
        require(offsetPx >= 0) { "offsetPx must be non-negative" }
    }
}
