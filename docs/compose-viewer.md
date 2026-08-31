# Compose viewer (KiteDocView)

Build a full-featured PDF viewer in Compose with a single composable. The `KiteDocView` family lets you display PDFs on screen with pinch zoom, paging, panning, and customizable rendering; all in pure Kotlin Multiplatform.

## Installation

Add the `kitepdf-compose-viewer` artifact to your Gradle dependencies:

=== "Kotlin (KMP)"

    ```kotlin
    // commonMain
    dependencies {
        implementation("io.github.yuroyami:kitepdf-compose-viewer:0.8.2")
    }
    ```

=== "Android / JVM"

    ```gradle
    dependencies {
        implementation("io.github.yuroyami:kitepdf-compose-viewer:0.8.2")
    }
    ```

## Quick start

The simplest viewer: a whole document in a continuous vertical scroll.

```kotlin
val document = remember { PdfDocument.open(bytes) }
KiteDocView(document, modifier = Modifier.fillMaxSize())
```

`KiteDocView` takes any `KiteDocument`, so an EPUB goes in the same call:

```kotlin
val book = remember { EpubDocument.open(bytes) }
KiteDocView(book, modifier = Modifier.fillMaxSize())
```

Or just one page, sized to fill the width:

```kotlin
KiteDocView(document, page = 2, modifier = Modifier.fillMaxWidth())
```

## The full KiteDocView composable

For complete control, pass a hoisted state and specify layout, zoom, render mode, and overlays:

```kotlin
val state = rememberKiteDocViewState(document)

KiteDocView(
    state = state,
    modifier = Modifier.fillMaxSize(),
    layout = KiteDocLayout.Paged(Orientation.Horizontal),
    zoomSpec = KiteZoomSpec(maxZoom = 6f),
    renderSpec = KiteRenderSpec.Rasterized(quality = 1.5f),
    colors = KiteDocViewColors(pageBackground = Color.White),
    pageSpacing = 8.dp,
    overlay = { state ->
        KiteNavigationControls(state, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    },
)
```

## KiteDocViewState: control and observation

`KiteDocViewState` is the single point of control for all viewer behavior. Hoist it outside the `KiteDocView` so navigation widgets, sliders, and external controls all drive the same state.

```kotlin
val state = rememberKiteDocViewState(document)
```

### Navigation

All navigation methods are suspending; call them from a coroutine scope:

```kotlin
scope.launch {
    // Jump to a page (immediately)
    state.scrollToPage(2)

    // Animate to a page (smooth scroll)
    state.animateScrollToPage(2)

    // One page at a time
    state.nextPage()
    state.previousPage()
}
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
scope.launch { state.animateZoomTo(3f, focal = tapPosition) }

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

### `KiteDocLayout.Continuous` (default)

All pages in one lazy-loaded strip, scrollable in a single axis. Zoom is magnifier-style: the whole strip scales around the viewport center while scrolling stays native along the scroll axis.

```kotlin
KiteDocView(
    state,
    layout = KiteDocLayout.Continuous(orientation = Orientation.Vertical),
    // vertical = scroll down through all pages; horizontal = scroll right
)
```

**Best for:** reading documents end-to-end (papers, reports), where the page count matters less than the scroll position.

### `KiteDocLayout.Paged` (snap paging)

One page per screen, snapped. Swipe or drive programmatically to flip pages. Each page fits letterbox-style within the viewport.

```kotlin
KiteDocView(
    state,
    layout = KiteDocLayout.Paged(
        orientation = Orientation.Horizontal,
        offscreenPages = 1, // pages pre-rasterized on each side
    ),
)
```

**Best for:** books, slide decks, comics; anything where users think in "pages" not "scroll position".

- **`offscreenPages`**: pages kept composed and rasterized on each side of the visible page (default 1). Raise to cover faster flinging; set 0 to minimise memory. While idle, the immediate neighbours are pre-rendered so a swipe never stalls.

### `KiteDocLayout.SinglePage`

Exactly one fixed page, letterboxed to fill the viewport:

```kotlin
KiteDocView(state, layout = KiteDocLayout.SinglePage(pageIndex = 3))
```

## Zoom & gesture configuration

Customise pinch, double-tap, pan, and zoom bounds:

```kotlin
val spec = KiteZoomSpec(
    pinchEnabled = true,
    doubleTapEnabled = true,
    panEnabled = true,
    minZoom = 1f,
    maxZoom = 8f,
    doubleTapZoom = 2.5f, // what double-tap toggles to
    resetZoomOnPageChange = true, // snap to minZoom when paging
)
KiteDocView(state, zoomSpec = spec)
```

These bounds are honoured by both gestures and programmatic calls (`setZoom`, `animateZoomTo`), so an app driving zoom from a slider is governed by the same range.

To disable zoom entirely:

```kotlin
KiteDocView(state, zoomSpec = KiteZoomSpec.Disabled)
```

## Rendering: rasterized vs. vectorized

The `renderSpec` parameter controls how pages become pixels. Choose the right trade-off for your use case.

### `KiteRenderSpec.Rasterized` (default)

Vector-render each page once into a bitmap per (size, zoom, quality) bucket, then draw that bitmap and GPU-transform it during gestures. Scrolling and panning are cheap; the PDF engine never re-executes.

```kotlin
val spec = KiteRenderSpec.Rasterized(
    quality = 1f, // supersampling multiplier over on-screen resolution
    maxBitmapLongSide = 4096, // memory cap
    rerasterizeOnZoom = true, // re-render at settled zoom for crispness
    preserveHairlines = true, // compensate sub-pixel strokes
)
KiteDocView(state, renderSpec = spec)
```

**When to use:**
- Dense pages with heavy content (graphs, photographs, detailed illustrations).
- Lots of pinch-zooming and panning (fast gestures, content-independent cost).
- Slow devices, where re-drawing the page every frame would stutter.

**Parameters:**

- **`quality`** (default 1.0): supersampling multiplier over on-screen pixels. `1.0` = rasterize exactly at display resolution (fastest and sharpest). `>1.0` (e.g. 1.5) oversamples for screenshots or print-like export. `<1.0` undersamples for cheap thumbnails.
- **`maxBitmapLongSide`** (default 4096): hard memory cap. Large pages and deep zoom won't exceed this on the longest side.
- **`rerasterizeOnZoom`** (default true): after a zoom settles, re-render the visible page at the zoomed resolution so deep zoom stays crisp. Costs one extra rasterization per zoom settle.
- **`preserveHairlines`** (default true): compensate the engine's 1-px hairline floor for any raster-vs-screen scale difference, so sub-pixel strokes (ECG traces, fine table rules) never vanish when the bitmap is downscaled.

### `KiteRenderSpec.Vectorized`

Re-execute each page's content stream into a live Canvas every composition, transformed by zoom/pan via a GPU layer. No bitmap; lower memory footprint, resolution-independent quality.

```kotlin
val spec = KiteRenderSpec.Vectorized(
    hairlineWidthPx = 1f, // minimum stroke width in device pixels
)
KiteDocView(state, renderSpec = spec)
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

## Colors

Control the paper and viewport background:

```kotlin
val colors = KiteDocViewColors(
    pageBackground = Color.White,      // behind page content
    viewportBackground = Color.Black,  // letterbox / gutter
)
KiteDocView(state, colors = colors)
```

Most PDFs assume white paper and paint nothing behind their content, so `pageBackground` typically stays white.

`KiteDocViewColors` also carries `searchHighlight` (the fill for `state.searchHighlights`) and `selectionHighlight` (the fill for the active text selection).

## Highlights

`KiteDocViewState` has two highlight channels, and both paint over the page in the same pass.

`searchHighlights` is the plain one. Every hit paints in `KiteDocViewColors.searchHighlight`, which is what search results want:

```kotlin
state.searchHighlights = document.search("invoice").toList()
```

`highlights` is the app-owned one. Each entry is a `KiteHighlight`, which wraps a hit with its own colour and its own optional margin marker:

```kotlin
state.highlights = notes.map { note ->
    KiteHighlight(
        hit = KiteSearchHit(note.pageIndex, note.quads, note.text),
        color = note.category.tint,       // null keeps KiteDocViewColors.searchHighlight
        edgeMarker = true,                // a pill in the page margin
        edgeMarkerColor = Color(0xFFEF6C00),
    )
}
```

The marker sits in the page's right margin, level with the highlighted text, so a reader can tell a note lives on the page without hunting for the words. It scales with the rendered page, so it keeps its proportions in a thumbnail and at deep zoom alike, and its inner edge is clamped past the highlighted quads so it never paints over the words.

Clear either channel by assigning an empty list.

## Text selection

A long press anchors a selection, dragging extends it, and the result lands in `state.selection` (with `state.onSelectionChange` for a callback). The viewer never touches the clipboard: read `selection.text` and copy it in your app.

While a selection is live, `state.isSelectionActive` is `true`, and the viewer suppresses one-finger panning and the list or pager's own scrolling so the page cannot move out from under the selection. Two-finger pinch zoom keeps working. The flag turns on the moment the long press fires and stays on until `state.clearSelection()`, which any tap on the page also calls.

```kotlin
val state = rememberKiteDocViewState(document)
state.onSelectionChange = { sel -> selectedText = sel?.text }

// Elsewhere, e.g. in a selection action bar:
if (state.isSelectionActive) {
    Button(onClick = { clipboard.setText(state.selection?.text.orEmpty()) }) { Text("Copy") }
}
```

`state.selectionInProgress` is `true` only while the finger is still down on the drag that is building the selection, and drops the moment it lifts. Gate a context menu on it: a popup shown mid-drag covers the words being chosen. `isSelectionActive` cannot tell those apart, because it deliberately stays on after the finger lifts to keep the page from drifting.

### Turning selection off

Some documents are pictures, not prose: a chart, a scan, an ECG trace, a generated report you only ever look at. There, a long press that paints a blue wash over a label is noise, and the gesture competes with panning. Pass `selectionEnabled = false` and the whole thing goes away:

```kotlin
KiteDocView(state, selectionEnabled = false)
```

No long press selects, no wash is painted, no thumbs appear, and a selection already on screen is dropped (which also hands back the pan and scroll locks it was holding). The gesture is not attached at all, so it cannot compete with panning. Everything else, zoom, pan, tap, links, page navigation, is untouched. The default stays `true`.

### Selection menu

`KiteSelectionMenu` is a ready-made context menu for the `overlay` slot. It appears when a selection exists and the drag has ended, lists your actions, and can offer a wrapping row of highlight-colour swatches:

```kotlin
KiteDocView(state, overlay = {
    KiteSelectionMenu(
        state = state,
        items = listOf(
            KiteSelectionMenuItem("Copy") { clipboard.setText(AnnotatedString(it.text)) },
            KiteSelectionMenuItem("Add note", clearsSelection = false) { openNoteEditor(it) },
        ),
        highlightColors = listOf(Color(0xFFFFF176), Color(0xFFA5D6A7), Color(0xFF90CAF9)),
        onHighlightColorPicked = { sel, color -> addHighlight(sel, color) },
    )
})
```

Every visual layer is replaceable: `container` swaps the card, `itemContent` swaps how one action renders, `colorSwatch` swaps how one colour renders, and `alignment` moves the whole menu. For a completely different menu, skip the composable and build your own against `state.selection` and `state.selectionInProgress`; the built-in one is a default, not a contract.

### Selection handles

The two boundary markers ("thumbs") are canvas vector drawing inside the page's draw pass, not composables, so they scale and pan in lockstep with the words they bound.

**They drag.** Press on a thumb and that end of the selection follows your finger while the other end stays put; haul one past the other and the two ends swap, the same as a platform text field. The rest of the gesture layer is untouched: a press that misses both thumbs is still an ordinary press, so long-press selection, tap and pan behave exactly as before. Nothing to enable, and the selection stays on one page as it always did.

Recolour the markers with `KiteDocViewColors.selectionHandle`, or replace the drawing entirely with `KiteDocViewColors.selectionHandlePainter`:

```kotlin
KiteDocView(state, colors = KiteDocViewColors(
    selectionHandlePainter = KiteSelectionHandlePainter { edge, x, top, bottom, color ->
        drawCircle(color, radius = (bottom - top) * 0.35f, center = Offset(x, bottom))
    },
))
```

The default is `KiteSelectionHandleDefaults.CaretAndDot`: a caret spanning the boundary line with a grab dot beneath it.

One catch with a custom painter: the grab area is the boundary line, not the shape you paint. It is a fixed 24.dp radius in screen pixels, so the touch target stays the same size at every zoom level while the marker scales with the text. Draw your marker near its boundary and the two agree; draw it far away and readers will be grabbing empty space.

## Opening at a saved position

A PDF is ready the moment it opens. A reflowable EPUB is not: it has to be laid
out before it has pages, and a whole book takes seconds. So `KiteDocView` reads
and lays out one chapter at a time, starting with the one the reader is on.

```kotlin
val state = rememberKiteDocViewState(book, savedBookmark)
KiteDocView(state, Modifier.fillMaxSize())

val savedBookmark = state.currentBookmark()   // save on pause
```

The sample app in `sample/` runs this loop against a generated 24-chapter book.

The rest of the book loads in the background, nearest chapter first. A chapter
that lands above the reader does not move their page: the strip is keyed by
reading position and each publication corrects the pager before the frame
draws, so the viewer holds the page, the zoom, and any active selection while
the book fills in. A saved Flow bookmark shows its chapter's placeholder from
the very first frame.

Chapters that have not been laid out yet hold one page-shaped slot each. A
reader can scroll onto one and wait there; when the chapter arrives they land
on its first page. Replace what that slot shows with `chapterPlaceholder`:

```kotlin
KiteDocView(
    state = state,
    chapterPlaceholder = { chapter -> CircularProgressIndicator() },
)
```

### Reading the position

| Member | Use it for |
|---|---|
| `state.currentLocation` | where the reader is, always exact |
| `state.currentBookmark()` | what to save and reopen with |
| `state.currentPage` | the slot on screen, for an indicator |
| `state.knownPageCount` | pages laid out so far |
| `state.isComplete` | true once the total is final |
| `state.scrollTo(location)` / `scrollTo(bookmark)` | move, laying out one chapter |

`KitePageIndicator` prefixes the total with `~` until `isComplete`.

`state.pageCount` is still there and still exact, but reading it lays out every
chapter. Prefer `knownPageCount` with `isComplete`.

## Link taps

A tap on a link inside the document is handled for you: internal jumps (PDF
destinations, EPUB hrefs into another chapter) scroll to the target page and
never reach your code.

Everything else goes to `onLinkTap` as a `KiteLinkAction`. Return `true` once
you have handled it; `false` lets the tap fall through to `onTap`.

```kotlin
KiteDocView(
    state = state,
    onLinkTap = { link ->
        link.uri?.let { openInBrowser(it); true } ?: false
    },
)
```

`link.uri` answers for both formats, so opening web links needs no `when`. When
you do need the format-native payload:

| Case | Comes from | Carries |
|---|---|---|
| `KiteLinkAction.Uri` | an EPUB href with a scheme | the URL |
| `KiteLinkAction.Pdf` | any PDF `/A` action the viewer does not perform itself | the parsed `PdfAction` (a URI, a remote GoTo, a Launch, JavaScript, a form submit) |

```kotlin
onLinkTap = { link ->
    when (link) {
        is KiteLinkAction.Uri -> { openInBrowser(link.uri); true }
        is KiteLinkAction.Pdf -> when (val action = link.action) {
            is PdfAction.Uri -> { openInBrowser(action.uri); true }
            is PdfAction.Launch -> { warnAboutLaunch(action.filename); true }
            else -> false
        }
    }
}
```

## Navigation widgets

Ready-made UI components for common patterns. They all take a `KiteDocViewState`, so they work from anywhere in your tree; inside the viewport (via `overlay`), in your top bar, in a side panel.

### Page indicator

Display "current / total" page count:

```kotlin
KitePageIndicator(
    state,
    modifier = Modifier.padding(8.dp),
    format = { current, total -> "Page ${current + 1} / $total" },
)
```

### Navigation controls

Previous / page number / next pill. Made for floating over the viewport:

```kotlin
overlay = { state ->
    KiteNavigationControls(
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
KiteThumbnailStrip(
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
KiteDocView(
    state,
    overlay = { state ->
        // Everything here floats over the pages
        KiteNavigationControls(state, Modifier.align(Alignment.BottomCenter))
        
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
KiteDocView(
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

## Custom viewer: KitePageRasterizer

If you need a viewer that doesn't fit the built-in layouts (e.g. a thumbnail grid, an image-gallery-style pager, or a PNG batch export), use `KitePageRasterizer` directly:

```kotlin
@Composable
fun MyCustomPdfViewer(document: PdfDocument) {
    val rasterizer = rememberKitePageRasterizer()
    
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

`rememberKitePageRasterizer()` wires the rasterizer to the composition's density, layout direction, and text measurement engine. For off-composition rasterization (e.g. a background job), construct `KitePageRasterizer` directly if you already have a `TextMeasurer`.

## Placeholder while rasterizing

Show a custom placeholder while a page bitmap is being rendered:

```kotlin
KiteDocView(
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

By default, pages show a solid `pageBackground` color until their raster lands.

## Crossfade on page transition

Freshly rasterized pages fade in smoothly rather than popping (160 ms by default). The previous frame remains visible during re-rasterization, so placeholder → page and crisp-zoom refreshes read as a gentle dissolve, never a flash.

## Performance notes

- **Lazy composition**: Continuous mode composes only visible pages and their immediate offscreen neighbours (paged mode pre-renders `offscreenPages` on each side). Millions of pages are supported; only visible ones cost anything.
- **Rasterization is off the main thread**: `KiteDocView` renders page bitmaps through `KitePageRasterizer.rasterizeOffMain()` on a background pool after composition settles, so scrolling and input stay responsive; results land through a page-bitmap LRU cache. The jitter on a page turn is avoided by pre-fetching neighbours while idle.
- **Synchronous escape hatch**: `KitePageRasterizer.rasterize()` still runs on the calling thread for callers that need a bitmap right now; text measurement inside it is serialized internally, so either entry point is safe to use.
- **Zoom settle debounce**: By default, `rerasterizeOnZoom=true` waits approximately 220 ms after zoom stops before re-rendering, so quick pinch-and-release doesn't thrash the rasterizer.

## See also

- [Reading and writing PDFs](reading.md)
- [Headless rendering](rendering.md)
