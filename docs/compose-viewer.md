# Compose viewer (PdfView)

Build a full-featured PDF viewer in Compose with a single composable. The `PdfView` family lets you display PDFs on screen with pinch zoom, paging, panning, and customizable rendering; all in pure Kotlin Multiplatform.

## Installation

Add the `kitepdf-compose` artifact to your Gradle dependencies:

=== "Kotlin (KMP)"

    ```kotlin
    // commonMain
    dependencies {
        implementation("io.github.yuroyami:kitepdf-compose:0.1.0")
    }
    ```

=== "Android / JVM"

    ```gradle
    dependencies {
        implementation("io.github.yuroyami:kitepdf-compose:0.1.0")
    }
    ```

## Quick start

The simplest viewer: a whole document in a continuous vertical scroll.

```kotlin
val document = rememberPdfDocument("path/to/file.pdf")
PdfView(document, modifier = Modifier.fillMaxSize())
```

Or just one page, sized to fill the width:

```kotlin
PdfView(document, page = 2, modifier = Modifier.fillMaxWidth())
```

## The full PdfView composable

For complete control, pass a hoisted state and specify layout, zoom, render mode, and overlays:

```kotlin
val state = rememberPdfViewState(document)

PdfView(
    state = state,
    modifier = Modifier.fillMaxSize(),
    layout = PdfLayout.Paged(Orientation.Horizontal),
    zoomSpec = PdfZoomSpec(maxZoom = 6f),
    renderSpec = PdfRenderSpec.Rasterized(quality = 1.5f),
    colors = PdfViewColors(pageBackground = Color.White),
    pageSpacing = 8.dp,
    overlay = { state ->
        PdfNavigationControls(state, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    },
)
```

## PdfViewState: control and observation

`PdfViewState` is the single point of control for all viewer behaviour. Hoist it outside the `PdfView` so navigation widgets, sliders, and external controls all drive the same state.

```kotlin
val state = rememberPdfViewState(document)
```

### Navigation

All navigation methods are suspending; call them from a coroutine scope:

```kotlin
// Jump to a page (immediately)
state.scrollToPage(2)

// Animate to a page (smooth scroll)
state.animateScrollToPage(2)

// One page at a time
state.nextPage()
state.previousPage()
```

Call these in a `LaunchedEffect` or from a coroutine scope (e.g. a button's `onClick` via `rememberCoroutineScope()`):

```kotlin
val scope = rememberCoroutineScope()
Button(onClick = { scope.launch { state.nextPage() } }) {
    Text("Next")
}
```

### Zoom and pan

```kotlin
// Set zoom immediately (clamped to spec.minZoom..maxZoom)
state.setZoom(2.5f)

// Animate to a zoom level (e.g. double-tap at a position)
state.animateZoomTo(3f, focal = tapPosition)

// Reset to minimum zoom and center
state.resetZoom()

// Pan by a delta (clamped to content bounds)
state.panBy(Offset(100f, 50f))

// Query current state
println("Zoom: ${state.zoom}") // 1.0 = fit
println("Pan: ${state.panOffset}")
println("Current page: ${state.currentPage}")
println("Is zoomed in? ${state.isZoomed}")
```

## Layout modes

Control how pages are arranged and navigated:

### `PdfLayout.Continuous` (default)

All pages in one lazy-loaded strip, scrollable in a single axis. Zoom is magnifier-style: the whole strip scales around the viewport centre while scrolling stays native along the scroll axis.

```kotlin
PdfView(
    state,
    layout = PdfLayout.Continuous(orientation = Orientation.Vertical),
    // vertical = scroll down through all pages; horizontal = scroll right
)
```

**Best for:** reading documents end-to-end (papers, reports), where the page count matters less than the scroll position.

### `PdfLayout.Paged` (snap paging)

One page per screen, snapped. Swipe or drive programmatically to flip pages. Each page fits letterbox-style within the viewport.

```kotlin
PdfView(
    state,
    layout = PdfLayout.Paged(
        orientation = Orientation.Horizontal,
        offscreenPages = 1, // pages pre-rasterized on each side
    ),
)
```

**Best for:** books, slide decks, comics; anything where users think in "pages" not "scroll position".

- **`offscreenPages`**: pages kept composed and rasterized on each side of the visible page (default 1). Raise to cover faster flinging; set 0 to minimise memory. While idle, the immediate neighbours are pre-rendered so a swipe never stalls.

### `PdfLayout.SinglePage`

Exactly one fixed page, letterboxed to fill the viewport:

```kotlin
PdfView(state, layout = PdfLayout.SinglePage(pageIndex = 3))
```

## Zoom & gesture configuration

Customise pinch, double-tap, pan, and zoom bounds:

```kotlin
val spec = PdfZoomSpec(
    pinchEnabled = true,
    doubleTapEnabled = true,
    panEnabled = true,
    minZoom = 1f,
    maxZoom = 8f,
    doubleTapZoom = 2.5f, // what double-tap toggles to
    resetZoomOnPageChange = true, // snap to minZoom when paging
)
PdfView(state, zoomSpec = spec)
```

These bounds are honoured by both gestures and programmatic calls (`setZoom`, `animateZoomTo`), so an app driving zoom from a slider is governed by the same range.

To disable zoom entirely:

```kotlin
PdfView(state, zoomSpec = PdfZoomSpec.Disabled)
```

## Rendering: rasterized vs. vectorized

The `renderSpec` parameter controls how pages become pixels. Choose the right trade-off for your use case.

### `PdfRenderSpec.Rasterized` (default)

Vector-render each page once into a bitmap per (size, zoom, quality) bucket, then draw that bitmap and GPU-transform it during gestures. Scrolling and panning are cheap; the PDF engine never re-executes.

```kotlin
val spec = PdfRenderSpec.Rasterized(
    quality = 1f, // supersampling multiplier over on-screen resolution
    maxBitmapLongSide = 4096, // memory cap
    rerasterizeOnZoom = true, // re-render at settled zoom for crispness
    preserveHairlines = true, // compensate sub-pixel strokes
)
PdfView(state, renderSpec = spec)
```

**When to use:**
- Dense pages with complex content (graphs, photographs, intricate illustrations).
- Lots of pinch-zooming and panning (fast gestures, content-independent cost).
- Devices with limited memory (one bitmap per page at a time).

**Parameters:**

- **`quality`** (default 1.0): supersampling multiplier over on-screen pixels. `1.0` = rasterize exactly at display resolution (fastest and sharpest). `>1.0` (e.g. 1.5) oversamples for screenshots or print-like export. `<1.0` undersamples for cheap thumbnails.
- **`maxBitmapLongSide`** (default 4096): hard memory cap. Large pages and deep zoom won't exceed this on the longest side.
- **`rerasterizeOnZoom`** (default true): after a zoom settles, re-render the visible page at the zoomed resolution so deep zoom stays crisp. Costs one extra rasterization per zoom settle.
- **`preserveHairlines`** (default true): compensate the engine's 1-px hairline floor for any raster-vs-screen scale difference, so sub-pixel strokes (ECG traces, fine table rules) never vanish when the bitmap is downscaled.

### `PdfRenderSpec.Vectorized`

Re-execute each page's content stream into a live Canvas every composition, transformed by zoom/pan via a GPU layer. No bitmap; lower memory footprint, resolution-independent quality.

```kotlin
val spec = PdfRenderSpec.Vectorized(
    hairlineWidthPx = 1f, // minimum stroke width in device pixels
)
PdfView(state, renderSpec = spec)
```

**When to use:**
- Simple pages with minimal content (forms, text-only documents).
- Deep zoom crispness matters more than gesture smoothness.
- Memory is scarce (no bitmap overhead).
- Every composition must stay crisp (e.g. animation).

**Parameters:**

- **`hairlineWidthPx`** (default 1.0): minimum stroke width in device pixels. The engine floors thin strokes here so sub-pixel rules (ECG traces, fine borders) stay visible. `1.0` is the ISO hairline.

!!! warning "Rasterized vs. Vectorized trade-off"

    **Rasterized** wins on gesture smoothness: scroll and pan never re-execute the PDF engine. It trades memory (one bitmap) and rasterization latency for instant playback.
    
    **Vectorized** wins on memory and true resolution independence but re-draws on every composition. On Android the vector display list replays under the live transform so zoom stays crisp mid-pinch; on Skia targets (iOS, desktop, web) the layer is texture-cached so deep in-gesture zoom softens until the draw re-runs.
    
    For most apps, **Rasterized with `rerasterizeOnZoom=true`** is the sweet spot: responsive gestures and crisp zoom, with a small memory footprint per page.

## Colours

Control the paper and viewport background:

```kotlin
val colors = PdfViewColors(
    pageBackground = Color.White,      // behind page content
    viewportBackground = Color.Black,  // letterbox / gutter
)
PdfView(state, colors = colors)
```

Most PDFs assume white paper and paint nothing behind their content, so `pageBackground` typically stays white.

## Navigation widgets

Ready-made UI components for common patterns. They all take a `PdfViewState`, so they work from anywhere in your tree; inside the viewport (via `overlay`), in your top bar, in a side panel.

### Page indicator

Display "current / total" page count:

```kotlin
PdfPageIndicator(
    state,
    modifier = Modifier.padding(8.dp),
    format = { current, total -> "Page ${current + 1} / $total" },
)
```

### Navigation controls

Previous / page number / next pill. Perfect for floating over the viewport:

```kotlin
overlay = { state ->
    PdfNavigationControls(
        state,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp),
        contentColor = Color.White,
        containerColor = Color(0xB3222222), // semi-transparent dark
    )
}
```

Buttons auto-disable at the ends (no previous on page 0, no next on the last page).

### Thumbnail strip

Horizontal carousel of tappable page thumbnails. Current page is outlined; tap any thumbnail to animate there:

```kotlin
PdfThumbnailStrip(
    state,
    modifier = Modifier.fillMaxWidth(),
    thumbnailHeight = 72.dp,
    spacing = 8.dp,
    selectedBorderColor = Color.Blue,
    pageBackground = Color.White,
)
```

Thumbnails rasterize independently at strip resolution (cheap), so they don't block the main viewer.

## The overlay slot

Float HUD components over the viewport. The `overlay` lambda receives the `state` and a `BoxScope` for alignment:

```kotlin
PdfView(
    state,
    overlay = { state ->
        // Everything here floats over the pages
        PdfNavigationControls(state, Modifier.align(Alignment.BottomCenter))
        
        // Add your own widgets
        Text(
            "${state.currentPage + 1}",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    },
)
```

## Export rendered pages

Capture a page bitmap and save it as PNG:

```kotlin
PdfView(
    state,
    onPageRendered = { pageIndex, bitmap ->
        // bitmap is an ImageBitmap ready for export
        val pngBytes = bitmap.encodeToPng()
        if (pngBytes != null) {
            // Write to file, share, or upload
            File("page_$pageIndex.png").writeBytes(pngBytes)
        }
    },
)
```

This callback fires every time a page finishes rasterizing (i.e. the bitmap is ready). In rasterized mode it fires once per bucket; in vectorized mode it never fires (no bitmap to hand back).

## Custom viewer: PdfRasterizer

If you need a viewer that doesn't fit the built-in layouts (e.g. a thumbnail grid, an image-gallery-style pager, or a PNG batch export), use `PdfRasterizer` directly:

```kotlin
@Composable
fun MyCustomPdfViewer(document: PdfDocument) {
    val rasterizer = rememberPdfRasterizer()
    
    for (pageIndex in 0 until document.pageCount) {
        val page = document.pages[pageIndex]
        val bitmap = rasterizer.rasterize(
            page,
            widthPx = 1080,
            heightPx = 1440,
            background = Color.White,
            hairlineWidthPx = 1f,
        )
        // Use bitmap for your own layout
    }
}
```

`rememberPdfRasterizer()` wires the rasterizer to the composition's density, layout direction, and text measurement engine. For off-composition rasterization (e.g. a background job), construct `PdfRasterizer` directly if you already have a `TextMeasurer`.

## Placeholder while rasterizing

Show a custom placeholder while a page bitmap is being rendered:

```kotlin
PdfView(
    state,
    pagePlaceholder = { pageIndex ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.LightGray),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    },
)
```

By default, pages show a solid `pageBackground` colour until their raster lands.

## Crossfade on page transition

Freshly rasterized pages fade in smoothly rather than popping (160 ms by default). The previous frame remains visible during re-rasterization, so placeholder → page and crisp-zoom refreshes read as a gentle dissolve, never a flash.

## Performance notes

- **Lazy composition**: Continuous mode composes only visible pages and their immediate offscreen neighbours (paged mode pre-renders `offscreenPages` on each side). Millions of pages are supported; only visible ones cost anything.
- **Rasterization is post-frame**: In rasterized mode, the bitmap is rendered on the main thread after composition settles, so it doesn't block layout or paint. The jitter on a page turn is avoided by pre-fetching neighbours while idle.
- **Text measurement is not thread-safe**: `PdfRasterizer.rasterize()` runs synchronously on the calling thread. In `PdfView`, this is post-frame on the main thread. For off-composition rasterization, call from the main thread.
- **Zoom settle debounce**: By default, `rerasterizeOnZoom=true` waits approximately 220 ms after zoom stops before re-rendering, so quick pinch-and-release doesn't thrash the rasterizer.

## See also

- [Reading and writing PDFs](reading.md)
- [Headless rendering](rendering.md)
