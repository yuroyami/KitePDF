package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kitepdf.render.ReaderTheme

/**
 * How [PdfView] lays its pages out and how the user moves between them.
 */
@Immutable
sealed interface PdfLayout {

    /**
     * All pages in one continuous scrollable strip (lazy — offscreen pages are
     * neither composed nor rasterized). Pages fill the cross axis at their
     * natural aspect ratio.
     *
     * Zoom in this mode is magnifier-style: the strip is scaled around the
     * viewport centre, horizontal pan is a clamped transform, and the scroll
     * axis keeps scrolling the (scaled) strip natively.
     */
    @Immutable
    data class Continuous(
        val orientation: Orientation = Orientation.Vertical,
    ) : PdfLayout

    /**
     * One page at a time with snap paging (swipe, or drive programmatically
     * via [PdfViewState]). Each page is letterboxed to fit the viewport.
     *
     * @param offscreenPages pages kept composed (and rastered) on each side of
     *   the visible one — trade memory for instant page turns. Defaults to 1 so
     *   the immediate neighbours are pre-rasterized while idle and a swipe never
     *   waits on a render; raise it to cover faster flinging, set 0 to minimise
     *   memory at the cost of a first-swipe render.
     */
    @Immutable
    data class Paged(
        val orientation: Orientation = Orientation.Horizontal,
        val offscreenPages: Int = 1,
    ) : PdfLayout

    /** Exactly one fixed page, letterboxed to fit the viewport. */
    @Immutable
    data class SinglePage(val pageIndex: Int) : PdfLayout

    companion object {
        val Default: PdfLayout = Continuous()
    }
}

/**
 * Zoom & pan behaviour for [PdfView].
 *
 * [minZoom]/[maxZoom] bound *all* zoom changes, including programmatic ones
 * through [PdfViewState.setZoom] — so an app driving zoom from its own slider
 * (gestures disabled) still declares its range here.
 *
 * @param pinchEnabled two-finger pinch zoom.
 * @param doubleTapEnabled double-tap toggles between [minZoom] and [doubleTapZoom].
 * @param panEnabled one-finger pan while zoomed in.
 * @param resetZoomOnPageChange in [PdfLayout.Paged] mode, snap zoom back to
 *   [minZoom] when the user lands on another page. Disable when zoom is driven
 *   externally and should persist across pages.
 */
@Immutable
data class PdfZoomSpec(
    val pinchEnabled: Boolean = true,
    val doubleTapEnabled: Boolean = true,
    val panEnabled: Boolean = true,
    val minZoom: Float = 1f,
    val maxZoom: Float = 8f,
    val doubleTapZoom: Float = 2.5f,
    val resetZoomOnPageChange: Boolean = true,
) {
    init {
        require(minZoom > 0f) { "minZoom must be > 0 (was $minZoom)" }
        require(maxZoom >= minZoom) { "maxZoom ($maxZoom) must be >= minZoom ($minZoom)" }
    }

    companion object {
        /** No zoom at all: gestures off, range pinned to 1. */
        val Disabled = PdfZoomSpec(
            pinchEnabled = false,
            doubleTapEnabled = false,
            panEnabled = false,
            minZoom = 1f,
            maxZoom = 1f,
        )
    }
}

/**
 * How [PdfView] turns pages into pixels. Pick a variant; each carries only the
 * knobs that actually apply to it, so there are no settings that silently do
 * nothing. Defaults to [Rasterized] via [PdfRenderSpec.Default].
 */
@Immutable
sealed interface PdfRenderSpec {

    /**
     * Vector-render each page once into a bitmap per size/zoom bucket, then draw
     * that bitmap and GPU-transform it during gestures — so scrolling and zoom
     * never re-execute the content stream. Heavy gesturing is cheap and
     * content-independent; the costs are one rasterization hitch per bucket and
     * softness when zoomed past the raster resolution until the zoom settles and
     * it re-rasterizes. Best for slow devices and dense pages.
     *
     * @param quality supersampling multiplier over the on-screen pixel size.
     *   1 = rasterize exactly at display resolution (sharpest *and* cheapest —
     *   the default). >1 = oversample, e.g. for screenshots or print-ish export.
     *   <1 = undersample for cheap previews/thumbnails.
     * @param maxBitmapLongSide hard cap on the longest bitmap side, protecting
     *   memory on huge pages and deep zooms.
     * @param rerasterizeOnZoom after a zoom settles, re-render the visible page
     *   at the zoomed resolution so deep zoom stays crisp instead of upscaling
     *   the base raster. Costs one extra rasterization per zoom settle.
     * @param preserveHairlines compensate the engine's 1-px hairline floor for
     *   any raster-vs-screen scale difference, so sub-pixel strokes (0.1-width
     *   ECG traces, fine table rules) never vanish when the bitmap is downscaled.
     */
    @Immutable
    data class Rasterized(
        val quality: Float = 1f,
        val maxBitmapLongSide: Int = 4096,
        val rerasterizeOnZoom: Boolean = true,
        val preserveHairlines: Boolean = true,
    ) : PdfRenderSpec {
        init {
            require(quality > 0f) { "quality must be > 0 (was $quality)" }
            require(maxBitmapLongSide > 0) { "maxBitmapLongSide must be > 0" }
        }
    }

    /**
     * Re-execute each page's content stream into a live `Canvas` every
     * composition, transformed by zoom/pan via the same GPU layer — no bitmap
     * (lower memory), resolution-independent quality at rest on every platform.
     * On Android the vector display list replays under the live transform, so it
     * stays crisp even mid-pinch; on Skia targets (iOS/desktop/web) the layer is
     * texture-cached, so deep in-gesture zoom softens until the draw re-runs.
     * Per-page draw cost scales with content complexity. Best for simple pages,
     * deep-zoom crispness, and low memory.
     *
     * @param hairlineWidthPx minimum stroke width in device pixels. The engine
     *   floors thin strokes here so sub-pixel rules (ECG traces, fine borders)
     *   stay visible; 1 = the ISO hairline. There is no supersampling knob —
     *   vector output is already resolution-independent.
     */
    @Immutable
    data class Vectorized(
        val hairlineWidthPx: Float = 1f,
    ) : PdfRenderSpec {
        init {
            require(hairlineWidthPx > 0f) { "hairlineWidthPx must be > 0 (was $hairlineWidthPx)" }
        }
    }

    companion object {
        /** Default: rasterized at display resolution — the historical behaviour. */
        val Default: PdfRenderSpec = Rasterized()
    }
}

/**
 * Colours used by [PdfView].
 *
 * @param pageBackground painted behind page content (most PDFs assume white
 *   paper and paint nothing themselves). Ignored when [theme] is set — the
 *   theme owns the paper colour then.
 * @param viewportBackground the letterbox/gutter colour around pages.
 * @param theme optional reading theme ([ReaderTheme.Dark]/[ReaderTheme.Sepia]/
 *   [ReaderTheme.Light]). When set, page content colours are remapped (text,
 *   borders, backgrounds — not images) and the paper uses the theme background.
 *   Reflowable EPUB especially benefits: night mode without re-laying-out.
 */
@Immutable
data class PdfViewColors(
    val pageBackground: Color = Color.White,
    val viewportBackground: Color = Color.Transparent,
    val theme: ReaderTheme? = null,
    /** Fill for [PdfViewState.searchHighlights] quads, drawn over the page. */
    val searchHighlight: Color = Color(0x66FFEB3B),
    /** Fill for the active [PdfViewState.selection] quads. */
    val selectionHighlight: Color = Color(0x664285F4),
)
