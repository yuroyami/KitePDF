# Compose viewer

`kitepdf-compose-viewer` gives you `KiteDocView`, one composable that can show any document KitePDF opens (PDF, EPUB, CBZ, SVG, XPS and OpenXPS) with zoom, paging, text selection and links, on every Compose Multiplatform target.

## Installation

=== "Kotlin Multiplatform"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.yuroyami:kitepdf-compose-viewer:0.13.0")
            }
        }
    }
    ```

=== "Android or JVM"

    ```kotlin
    dependencies {
        implementation("io.github.yuroyami:kitepdf-compose-viewer:0.13.0")
    }
    ```

## Quick start

The simplest viewer shows a whole document in one vertical scroll:

```kotlin
@Composable
fun Viewer(bytes: ByteArray) {
    val document = remember(bytes) { PdfDocument.open(bytes) }
    KiteDocView(document, modifier = Modifier.fillMaxSize())
}
```

`KiteDocView` takes any `KiteDocument`, so an EPUB goes into the same call:

```kotlin
val book = remember(bytes) { EpubDocument.open(bytes) }
KiteDocView(book, modifier = Modifier.fillMaxSize())
```

Pass `page` to show one page (the index starts at zero) at the full width:

```kotlin
KiteDocView(document, page = 2, modifier = Modifier.fillMaxWidth())
```

This short form also takes a `background`, a `theme`, the `pageSpacing` and a few callbacks. For everything else on this page, use the form that takes a state.

## The state

`KiteDocViewState` controls the viewer and tells you what it shows. Create it outside `KiteDocView`, so that your buttons, sliders and navigation widgets drive the same state as the viewer:

```kotlin
@Composable
fun Reader(document: KiteDocument) {
    val state = rememberKiteDocViewState(document)

    KiteDocView(
        state = state,
        modifier = Modifier.fillMaxSize(),
        layout = KiteDocLayout.Paged(Orientation.Horizontal),
        zoomSpec = KiteZoomSpec(maxZoom = 6f),
        renderSpec = KiteRenderSpec.Rasterized(quality = 1.5f),
        colors = KiteDocViewColors(pageBackground = Color.White),
        pageSpacing = 8.dp,
        overlay = {
            KiteNavigationControls(
                state,
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        },
    )
}
```

??? note "Every parameter of `KiteDocView`"

    | Parameter | What it does |
    | --- | --- |
    | `state` | The state that this viewer shows and that your code drives |
    | `modifier` | The modifier of the viewport |
    | `layout` | How the pages sit and move, see [Layouts](#layouts) |
    | `zoomSpec` | Pinch, double tap, pan and the zoom limits, see [Zoom and gestures](#zoom-and-gestures) |
    | `renderSpec` | How a page becomes pixels, see [Rasterized or vectorized](#rasterized-or-vectorized) |
    | `colors` | The paper, the viewport, the reading theme, the highlights and the selection, see [Colors and themes](#colors-and-themes) |
    | `pageSpacing` | The gap between two pages. The pages of an EPUB roll have no gap. |
    | `userScrollEnabled` | Lets a swipe, the mouse wheel and the page keys move the view |
    | `selectionEnabled` | Lets the reader select text, see [Text selection](#text-selection) |
    | `onPageRendered` | Gets each new screen bitmap of a page, see [When a page renders](#when-a-page-renders) |
    | `pagePlaceholder` | Shows in a page's place until its bitmap is ready |
    | `chapterPlaceholder` | Shows in the place of a chapter that is not laid out yet |
    | `overlay` | Composables that float over the viewport, see [The overlay slot](#the-overlay-slot) |
    | `pageOverlay` | Composables that draw over each page and move with it, see [Draw over a place on a page](#draw-over-a-place-on-a-page) |
    | `onTap` | Gets a tap on the page that hits no link |
    | `onLinkTap` | Gets every link tap before the viewer acts, see [Link taps](#link-taps) |
    | `scripts` | Runs the scripts of a PDF, see [Scripts](#scripts) |
    | `onHighlightTap` | Gets a tap on a highlight before any link under it, see [Highlights](#highlights) |
    | `epubScripts` | Runs the scripts of an EPUB chapter, see [Scripts](#scripts) |
    | `externalLinks` | Sets which web and mail addresses the viewer opens, see [Addresses outside the document](#addresses-outside-the-document) |
    | `showMarkupErrors` | Shows a notice on the first page of an EPUB chapter whose markup is not well-formed XML |

### Navigation

Every navigation call suspends, so call it from a coroutine:

```kotlin
val scope = rememberCoroutineScope()

Button(onClick = { scope.launch { state.nextPage() } }) {
    Text("Next")
}
```

```kotlin
// Jump to a page
state.scrollToPage(2)

// Scroll to a page with an animation
state.animateScrollToPage(2)

// One page at a time
state.nextPage()
state.previousPage()
```

### Zoom and pan

```kotlin
// Set the zoom at once, inside the limits of the zoom spec
state.setZoom(2.5f)

// Zoom with an animation, about a point of the viewport
scope.launch { state.animateZoomTo(3f, focal = tapPosition) }

// Go back to the smallest zoom
state.resetZoom()

// Move the view, inside the bounds of the content
state.panBy(Offset(100f, 50f))
```

`state.zoom` is the current zoom (1 means that the page fits), `state.panOffset` is the current pan, and `state.isZoomed` is true above the smallest zoom.

## Layouts

The `layout` parameter sets how the pages sit and how the reader moves between them.

### Continuous

`KiteDocLayout.Continuous` is the default. All pages sit in one strip that scrolls along one axis, and the viewer composes only the pages on screen. A zoom scales the whole strip about the center of the viewport, while the scroll along the strip stays the platform's own scroll.

```kotlin
KiteDocView(
    state,
    layout = KiteDocLayout.Continuous(
        orientation = Orientation.Vertical,
        fit = KitePageFit.PAGE,
        prefetchPages = 2,
    ),
)
```

- `fit`: `KitePageFit.WIDTH` (the default) fills the width of a vertical strip, so a tall page scrolls through the viewport. `KitePageFit.PAGE` shrinks each page until the whole page shows.
- `prefetchPages`: when the strip stops, the viewer draws this many pages on each side of the screen into its cache, so a scroll to them shows them at once. The default is 1, and 0 draws nothing ahead.
- `contentPadding`: space at the ends of the strip for your own controls. The pages still scroll under them.

Use it for documents that people read from start to end, such as papers and reports.

### Paged

`KiteDocLayout.Paged` shows one page at a time. The reader swipes to turn the page, or your code turns it.

```kotlin
KiteDocView(
    state,
    layout = KiteDocLayout.Paged(
        orientation = Orientation.Horizontal,
        offscreenPages = 1,
    ),
)
```

- `offscreenPages`: the number of pages on each side that the viewer keeps ready, so a swipe never waits for a render. The default is 1. Use a higher number for fast swipes, or 0 to use less memory.
- `fit`: `KitePageFit.PAGE` (the default) shows the whole page. `KitePageFit.WIDTH` fills the width. A page taller than the viewport then starts at its top, and the reader drags or turns the wheel to move down the page. A sideways drag past the edge of the page turns the page.
- `reverseLayout`: puts the next page on the left, for a right-to-left book. `nextPage()` still goes to the next index.

Use it for books, slides and comics, where readers think in pages.

### Spread

`KiteDocLayout.Spread` shows two pages side by side, like an open book. It moves like `Paged`.

```kotlin
KiteDocView(
    state,
    layout = KiteDocLayout.Spread(
        // True for a right-to-left book
        reverseLayout = false,
        // Show the cover alone, then pair the pages after it
        firstPageAlone = true,
    ),
)
```

The viewer pairs the pages in order (the first two, then the next two), except where the document asks for something else:

- An EPUB chapter can put its first page on the left side, on the right side, or alone in the center.
- An EPUB book or chapter can turn spreads off, or allow them in a landscape viewport only.
- A PDF whose `/PageLayout` is `TwoPageRight` or `TwoColumnRight` puts its first page on the right. A left-to-right document then shows its first page alone.

A page without a partner shows alone in the center. When the viewport turns between portrait and landscape, the reader stays on the same page. A reflowable EPUB shows a chapter placeholder until all of its chapters are laid out, because the pairs depend on the whole book.

Use it for fixed-layout books, magazines and comics that have two-page spreads.

### SinglePage

`KiteDocLayout.SinglePage` shows one fixed page, which navigation does not change:

```kotlin
KiteDocView(state, layout = KiteDocLayout.SinglePage(pageIndex = 3))
```

### The layout a document asks for

`KiteDocLayout.forDocument(document)` gives the layout that a document asks for:

| Document | Layout |
| --- | --- |
| EPUB roll (`rendition:layout roll`) | `Continuous()`, with no gap between the pages |
| EPUB with `rendition:flow` `scrolled-continuous` | `Continuous()`, with all chapters in one strip |
| EPUB with `rendition:flow` `scrolled-doc` | `Paged(fit = KitePageFit.WIDTH)`, where each chapter scrolls down and a sideways swipe goes to the next chapter |
| Any other document | `KiteDocLayout.pagedFor(document)`, which is `Paged` with `reverseLayout` set for a right-to-left document |

```kotlin
KiteDocView(state, layout = KiteDocLayout.forDocument(book))
```

`EpubSettings.scrolled` changes the answer for an EPUB. With `false`, the book stays in pages. With `true`, a book that sets no flow gets `Continuous()`.

## Zoom and gestures

`KiteZoomSpec` sets the gestures and the zoom limits:

```kotlin
val spec = KiteZoomSpec(
    pinchEnabled = true,
    doubleTapEnabled = true,
    panEnabled = true,
    minZoom = 1f,
    maxZoom = 8f,
    // The zoom that a double tap goes to
    doubleTapZoom = 2.5f,
    // Go back to minZoom on a page turn
    resetZoomOnPageChange = true,
)
KiteDocView(state, zoomSpec = spec)
```

The limits apply to the gestures and to your own calls (`setZoom` and `animateZoomTo`), so a zoom slider in your app has the same range as a pinch.

The reader pans a zoomed page with one finger. A quick release lets the page move on and slow down, and a new touch stops it. In `Paged` and `Spread`, a drag past the edge of a zoomed page turns the page.

`KiteZoomSpec.Disabled` turns off every zoom gesture:

```kotlin
KiteDocView(state, zoomSpec = KiteZoomSpec.Disabled)
```

### Mouse, trackpad and keyboard

On the desktop and the web, and on a tablet with a mouse or a keyboard, the viewer also takes these inputs:

| Input | What it does | What turns it off |
| --- | --- | --- |
| Ctrl or Cmd with the wheel, or a trackpad pinch | Zooms about the pointer | `pinchEnabled = false` |
| Ctrl or Cmd with plus, minus or 0 | Zooms in, zooms out, or goes back to fit | `pinchEnabled = false` |
| The wheel in a horizontal `Paged` or `Spread` layout | Goes to the next or the previous page, one page for each wheel gesture | `userScrollEnabled = false` |
| The wheel on a page that is larger than the view (zoomed, or with `KitePageFit.WIDTH`) | Moves the page. At the edge of the page, the next wheel gesture turns the page. | `userScrollEnabled = false` |
| Page Down, Space, the down arrow | Goes to the next page | `userScrollEnabled = false` |
| Page Up, Shift with Space, the up arrow | Goes to the previous page | `userScrollEnabled = false` |
| The left and right arrows | Go to the previous and the next page. Where the pages advance to the left, the two arrows swap. | `userScrollEnabled = false` |
| Home and End | Go to the first and the last page | `userScrollEnabled = false` |
| A mouse press and drag on text | Selects the text at once | `selectionEnabled = false` |

The keys work after the viewer has the focus, which a press on the viewer gives it. A form field that has the caret keeps its keys.

## Rasterized or vectorized

The `renderSpec` parameter sets how a page becomes pixels:

| | `KiteRenderSpec.Rasterized` (default) | `KiteRenderSpec.Vectorized` |
| --- | --- | --- |
| Where the page draws | On a background thread, into a bitmap | On the UI thread, at each real redraw |
| Scroll, pan and pinch | Move the bitmap. The page does not draw again. | Play back the drawing that the page recorded |
| Memory | One bitmap for each page, and a cache of page bitmaps | No page bitmap. A cache of image bitmaps only. |
| Deep zoom during a pinch | Soft until the zoom stops and the page renders again | Sharp on Android. Soft on iOS, the desktop and the web until the page draws again. |
| A page of large scans, such as a comic | Fast | Slow, because the UI thread converts the images |
| `onPageRendered` | Called | Never called |

For most apps, the default `Rasterized` spec is the better choice. Its `rerasterizeOnZoom` renders the page again after each zoom, so text stays sharp at any zoom.

### Rasterized

The viewer renders each page into a bitmap for its size and its zoom, then draws that bitmap. A scroll, a pan or a pinch moves the bitmap, so the page itself does not draw again.

```kotlin
val spec = KiteRenderSpec.Rasterized(
    quality = 1f,
    maxBitmapLongSide = 4096,
    rerasterizeOnZoom = true,
    preserveHairlines = true,
    cacheBudgetBytes = 96L * 1024 * 1024,
)
KiteDocView(state, renderSpec = spec)
```

!!! warning
    On Android, a bitmap that is larger than the GPU texture limit draws blank. Keep `maxBitmapLongSide` at or below that limit, which is 4096 on many devices.

- `quality`: the number of bitmap pixels for each screen pixel. `1.0` renders at the screen resolution, which is the fastest and sharpest choice. A higher value (such as 1.5) gives more detail for a screenshot. A lower value gives cheap thumbnails.
- `maxBitmapLongSide`: the largest side of a page bitmap, in pixels. A page that needs more pixels (at deep zoom, or because it is very tall) keeps this bitmap, and the viewer draws the part on screen over it in tiles of 1024 pixels at full resolution.
- `rerasterizeOnZoom`: when a zoom stops, the viewer renders the page again at the new zoom. The zoom rounds up to the next step of 1, 1.19, 1.41, 1.68, 2 and so on (four steps for each doubling), so two pinches that stop near each other share one bitmap.
- `preserveHairlines`: scales the thinnest strokes with the bitmap, so a stroke of width 0 stays one screen pixel wide. Other thin strokes (such as ECG traces and fine table rules) keep their weight when the viewer scales the bitmap down.
- `cacheBudgetBytes`: the size of the cache of page bitmaps for one `KiteDocViewState`, so a page that comes back on screen needs no new render. 0 turns the cache off.
- `canvasDecorator`: see [Canvas decorators](#canvas-decorators).

### Vectorized

The viewer draws the page content on a live canvas and moves it with a GPU layer, so there is no page bitmap. The page draws again when its size, its zoom after a pinch, the theme or the render spec changes. A new search hit or highlight, and each frame of a pinch, play back the drawing that the page recorded.

```kotlin
val spec = KiteRenderSpec.Vectorized(
    hairlineWidthPx = 1f,
    imageCacheBudgetBytes = 16L * 1024 * 1024,
)
KiteDocView(state, renderSpec = spec)
```

- `hairlineWidthPx`: the width, in device pixels, of a stroke whose line width is 0. `1.0` is the one device pixel that ISO 32000-1, 8.4.3.2 asks for. Other thin strokes widen to a fifth of this width, as MuPDF draws them, so they stay visible but do not become solid lines.
- `imageCacheBudgetBytes`: the size of the cache of converted images that all pages of one `KiteDocViewState` share. An image that does not change keeps its bitmap across redraws, and when its page comes back on screen. 0 turns the cache off, and a negative value throws. The cache removes the least recently used image first. An image that is larger than the budget draws without a place in the cache.
- `canvasDecorator`: see [Canvas decorators](#canvas-decorators).

The cache lets go of its bitmaps when you leave the vectorized mode, when the viewer leaves the composition and when the budget changes. A drawing layer that Compose recorded can keep a bitmap until Compose releases that layer.

??? note "How the image cache knows an image"
    A PDF image XObject, a CBZ entry and an EPUB image resource each have an identity in their document. The identity lives longer than the decoded image, so an image that decodes again finds its bitmap. The keys of the cache hold only these identities, never image buffers or documents.

    These images get a new identity at each decode, so they never reuse a bitmap: PDF stencil masks, images with a `/Mask`, images in the default color space of a page, inline images and SVG resources.

    A custom image producer can give its images an identity with `KiteImageIdentity().child(resourcePath)` and `KiteImageData.withIdentity(identity)`:

    - Keep one root identity for each open document.
    - Use a new identity when the pixels, the masks, the size or the color meaning of an image change.
    - Do not change the buffers of an image after it draws. This also applies in a canvas decorator.

### Canvas decorators

A canvas decorator wraps the canvas of each page, so you can change the paint, read the glyphs or add your own drawing inside the page's paint pass. Both render specs take `canvasDecorator`, and so do `KitePageRasterizer.rasterize` and `rasterizeOffMain`.

```kotlin
@Composable
fun FadedInk(state: KiteDocViewState) {
    val decorator: KiteCanvasDecorator = remember {
        { inner ->
            object : KiteCanvas by inner {
                override fun fillPath(
                    path: KitePath,
                    ctm: KiteMatrix,
                    color: RgbColor,
                    evenOdd: Boolean,
                    alpha: Double,
                    blendMode: KiteBlendMode,
                ) {
                    // Paint every filled path at 80% of its opacity
                    inner.fillPath(path, ctm, color, evenOdd, alpha * 0.8, blendMode)
                }
            }
        }
    }
    KiteDocView(
        state = state,
        renderSpec = KiteRenderSpec.Rasterized(canvasDecorator = decorator),
    )
}
```

The canvas that the decorator gets already applies the reading theme, so your colors go through the theme too. The paper, the overlays of the viewer and the controls of a form do not go through the decorator. The wrapper gets the same coordinates and matrices as any `KiteCanvas`. It must pass on each call that it does not change.

Some `KiteCanvas` calls have two overloads:

- `drawImage` has one with a blend mode. The renderer calls it for an image that does not paint with the Normal blend mode.
- `applySoftMask` has one with a transfer function. The renderer calls it for a soft mask that has a transfer function.

To change one of these calls, override both overloads. With `by inner`, Kotlin sends each overload that the wrapper does not override directly to `inner`, which skips your code.

`rasterStep` needs no override. An SVG filter or a PDF transparency group paints its result back through the canvas that the document holds, which is the wrapper, so the wrapper sees those paints as usual.

Follow these rules for a decorator:

- The viewer calls the decorator on a background thread (in a browser, on the one UI thread).
- Create a new wrapper at each call. Do not keep the canvas that the viewer gives you.
- Give the same result for the same page at each call, because the viewer can paint a page more than once.
- Keep the decorator in `remember`. A cache hit does not call the decorator, and a new decorator starts a new cache.
- Make a new decorator when a setting that it captures changes. The viewer then draws the pages again.

## Colors and themes

`KiteDocViewColors` sets the colors that the viewer paints:

```kotlin
val colors = KiteDocViewColors(
    // Behind the page content
    pageBackground = Color.White,
    // Around and between the pages
    viewportBackground = Color.Black,
)
KiteDocView(state, colors = colors)
```

Most PDFs expect white paper and paint nothing behind their content, so `pageBackground` usually stays white. `KiteDocViewColors` also has these colors:

- `searchHighlight` fills the hits in `state.searchHighlights`.
- `selectionHighlight` fills the text selection.
- `selectionHandle` and `selectionHandlePainter` draw the two handles of a selection, see [Selection handles](#selection-handles).

A reading theme changes the colors of the page content at render time, so a change of theme does not lay out the book again. `ReaderTheme.Dark` gives dark paper with light text, `ReaderTheme.Sepia` gives cream paper with brown text, and `ReaderTheme.Light` changes nothing. Both `Dark` and `Sepia` keep the contrast of the text: a color with a contrast of 4.5 or more on white paper keeps a contrast of 4.5 or more on the theme's paper (WCAG 2.2, 1.4.3).

```kotlin
KiteDocView(state, colors = KiteDocViewColors(theme = ReaderTheme.Dark))
```

A theme does not change images, so a photo keeps its colors. A black symbol or equation that a book draws as an image then stays black on dark paper. `withImages(ReaderImages.LineArt)` tells the theme to change line art as it changes text:

```kotlin
val theme = ReaderTheme.Dark.withImages(ReaderImages.LineArt)
KiteDocView(state, colors = KiteDocViewColors(theme = theme))
```

Line art is an image whose visible pixels are all gray, in one or two narrow bands of lightness, such as a black or gray symbol on white or on transparent paper. A white box behind the symbol takes the color of the paper. A photo and colored artwork keep their colors.

## Highlights

`KiteDocViewState` has two lists of highlights. The viewer paints both over the page in the same pass.

`searchHighlights` is for search results. Every hit paints in `KiteDocViewColors.searchHighlight`:

```kotlin
state.searchHighlights = document.search("invoice").toList()
```

`highlights` is for your app's own marks, such as notes. Each `KiteHighlight` wraps a hit with its own color and an optional marker in the page margin:

```kotlin
state.highlights = notes.map { note ->
    KiteHighlight(
        hit = KiteSearchHit(note.location, note.quads, note.text),
        // Null uses KiteDocViewColors.searchHighlight
        color = note.category.tint,
        // A small mark in the page margin, level with the text
        edgeMarker = true,
        edgeMarkerColor = Color(0xFFEF6C00),
        id = note.id,
    )
}
```

The edge marker tells a reader that a note is on the page, without a search for the words. Its size follows the page, so it keeps its shape in a thumbnail and at deep zoom. The marker never paints over the highlighted words. On a page whose text fills that margin, the viewer draws no marker. `edgeMarkerSide` puts the marker in the start margin or the end margin (the end margin is the default).

To clear a list, set it to an empty list.

A tap on a highlight goes to `onHighlightTap` before any link under it. Return `true` to take the tap. `state.highlightAt(offset)` gives the highlight at a point of the viewport, with the `id` that you gave it.

### Where a highlight is

A `KiteLocation` names a page as a chapter and a page in that chapter, in the current layout. A selection gives you its location, so you can save a note from it:

```kotlin
state.onSelectionChange = { selection ->
    val location = selection?.location
    if (selection != null && location != null) {
        state.highlights = listOf(
            KiteHighlight(
                KiteSearchHit(location, selection.quads, selection.text),
                id = "note-1",
            ),
        )
    }
}
```

`KiteTextSelection.location`, `KitePageHit.location` and `KiteHighlight.location` name the real page, even while earlier chapters are still laying out. A document search fills in `KiteSearchHit.location` too. For a `KiteStructuredText` of your own, pass the location to its search: `text.search("invoice", location = location)`. A hit whose page is not laid out yet waits, and paints when its page appears.

A hit without a location reads its `pageIndex` as a page of the whole document, as a document search does. In an EPUB that is still laying out, such a hit paints only on the pages before the first chapter that is not laid out.

!!! warning
    A location and its quads stay correct while other chapters lay out, and when you open the same content with the same settings. After a change of font, viewport, margins or resources, they point at the wrong text. Calculate them again after such a change. A highlight holds page coordinates and no range of text, so it cannot follow its words through a reflow.

A slot is the place of a page in the viewer's strip of pages. While an EPUB lays out, a chapter that is not laid out yet fills one slot, so a slot and a page of the document can differ:

- `currentPage`, `scrollToPage`, the `pageIndex` of a selection or a hit, the indices of the thumbnail strip, `onPageRendered` and the overlay count slots. `currentSlot`, `scrollToSlot` and `animateScrollToSlot` are the same calls with names that say so.
- A document search and `SinglePage.pageIndex` count the pages of the whole document.
- `state.locationOf(slot)` and `state.slotOf(location)` convert between the two. Both return null for a page that is not laid out, and neither starts a layout.
- `state.scrollTo(location)` lays out the chapter that it needs, then goes there.

## Text selection

A long press starts a selection, and a drag extends it. A mouse press and drag on text selects at once, with no long press. `state.selection` holds the result, and `state.onSelectionChange` tells you when it changes.

The viewer never uses the clipboard. Read `selection.text` and copy it in your app. On a desktop, you can bind Ctrl or Cmd with C to that copy:

```kotlin
@Composable
fun CopyableViewer(state: KiteDocViewState, copy: (String) -> Unit) {
    KiteDocView(
        state = state,
        modifier = Modifier.onKeyEvent { event ->
            val text = state.selection?.text
            val isCopy = event.type == KeyEventType.KeyDown &&
                event.key == Key.C &&
                (event.isCtrlPressed || event.isMetaPressed)
            if (isCopy && text != null) {
                copy(text)
                true
            } else {
                false
            }
        },
    )
}
```

While a selection is on screen, `state.isSelectionActive` is `true`. The viewer then stops one-finger panning and the scroll of the strip or pager, so the page cannot move under the selection. A two-finger pinch still zooms. The flag turns on when the long press fires and stays on until `state.clearSelection()`, which a tap on the page also calls.

`state.selectionInProgress` is `true` only while the finger is down on the drag that builds the selection. Use it to show a menu: a menu that shows during the drag covers the words that the reader is choosing. `isSelectionActive` stays on after the finger lifts, so it cannot tell these two cases apart.

### Turning selection off

Some documents are pictures, not text: a chart, a scan, an ECG trace. There, a long press that paints a blue selection over a label gets in the way of panning. Pass `selectionEnabled = false` to remove the gesture:

```kotlin
KiteDocView(state, selectionEnabled = false)
```

A long press then selects nothing, and the viewer draws no selection and no handles. A selection that is already on screen goes away, and the viewer lets the reader pan and scroll again. Zoom, pan, taps, links and page navigation do not change.

### Selection menu

`KiteSelectionMenu` is a context menu for the `overlay` slot. It shows when a selection exists and the drag has ended. It lists your actions, and it can show a row of highlight colors:

```kotlin
KiteDocView(state, overlay = {
    KiteSelectionMenu(
        state = state,
        items = listOf(
            KiteSelectionMenuItem("Copy") { copy(it.text) },
            KiteSelectionMenuItem("Add note", clearsSelection = false) { openNoteEditor(it) },
        ),
        highlightColors = listOf(Color(0xFFFFF176), Color(0xFFA5D6A7), Color(0xFF90CAF9)),
        onHighlightColorPicked = { selection, color -> addHighlight(selection, color) },
    )
})
```

The menu sits above the selection, or below it when there is no room above. It stays inside the viewport and does not cover the selected words.

You can replace each part of the menu:

- `container` replaces the card.
- `itemContent` replaces how one action draws.
- `colorSwatch` replaces how one color draws.
- `alignment` puts the whole menu at one fixed place in the viewport.

For a menu of your own, read `state.selection`, `state.selectionInProgress` and `state.selectionBounds` (the box of the selection, in viewport pixels).

### Selection handles

The two handles at the ends of a selection are part of the page's drawing, not composables, so they scale and move with the words.

A reader can drag a handle. That end of the selection follows the finger, and the other end stays. When one handle passes the other, the two ends swap, as in a platform text field. A press that misses both handles does what it does without a selection.

`KiteDocViewColors.selectionHandle` sets the color of the handles. `KiteDocViewColors.selectionHandlePainter` replaces their drawing:

```kotlin
KiteDocView(
    state,
    colors = KiteDocViewColors(
        selectionHandlePainter = KiteSelectionHandlePainter { edge, x, top, bottom, color ->
            drawCircle(color, radius = (bottom - top) * 0.35f, center = Offset(x, bottom))
        },
    ),
)
```

The default is `KiteSelectionHandleDefaults.CaretAndDot`, a caret along the end of the selection with a dot below it.

!!! tip
    The touch area of a handle is a circle of 24 dp around the end of the selection, not the shape that you paint. Its size stays the same at every zoom, while your drawing scales with the text. Draw your handle near the end of the selection, or readers grab empty space.

## Opening at a saved position

A PDF has all its pages when it opens. A reflowable EPUB does not: it gets its pages from the layout, and a layout of a whole book takes seconds. So `KiteDocView` lays out one chapter at a time, starting with the reader's chapter.

```kotlin
// When the reader comes back, open at the saved bookmark
val state = rememberKiteDocViewState(book, savedBookmark)
KiteDocView(state, Modifier.fillMaxSize())

// When the reader leaves, save this one
val bookmark: KiteBookmark = state.currentBookmark()
```

The sample app in `sample/` runs this with a book of 24 chapters that it makes itself.

The rest of the book lays out in the background, nearest chapter first. A chapter that lands above the reader does not move the page on screen. The viewer keeps the page, the zoom and any selection while the book fills in. A saved bookmark shows its chapter's placeholder from the first frame.

A chapter that is not laid out yet holds one page-shaped slot. A reader can scroll onto that slot and wait there. When the chapter lands, the reader is on its first page. `chapterPlaceholder` sets what the slot shows:

```kotlin
KiteDocView(
    state = state,
    chapterPlaceholder = { chapter -> CircularProgressIndicator() },
)
```

A PDF of more than 200 pages also shows this slot for its first frames, while it builds its list of pages off the main thread. Then it opens at the page that the state asks for.

### Reading the position

| Member | Use it for |
| --- | --- |
| `state.currentLocation` | The exact page that the reader is on |
| `state.currentBookmark()` | A place in the content that stays correct after an EPUB reflow |
| `state.currentScrollPosition` | The first visible page, with the scroll offset into it in pixels |
| `state.currentPage` | The slot on screen, for a page indicator |
| `state.knownPageCount` | The number of pages laid out so far |
| `state.isComplete` | True when the page count is final |
| `state.scrollTo(location)` and `scrollTo(bookmark)` | Go to a place, after a layout of one chapter |

`KitePageIndicator` puts a `~` before the total until `isComplete` is true. `state.pageCount` is also exact, but it lays out every chapter. Use `knownPageCount` with `isComplete` instead.

To come back to the exact scroll position of a `Continuous` strip, save `currentScrollPosition`:

```kotlin
val saved: KiteScrollPosition = state.currentScrollPosition

// Save saved.location.chapter, saved.location.page and saved.offsetPx.
// Then open a new viewer at that position
val reopened = rememberKiteDocViewState(document, saved)

// Or move a viewer that is already open, from a coroutine
state.scrollTo(saved)
```

The position is the first visible page, which can differ from the page nearest the center of the viewport (`currentLocation`), and the offset into that page in pixels without zoom. It works for both orientations and for a right-to-left layout. The view matches exactly only with the same page layout, viewport and density. The zoom and the pan across the strip are not part of the position. For an EPUB whose font size or page size changes, use `currentBookmark()` instead. `Paged` and `SinglePage` give an offset of zero and come back to the page.

## Link taps

Every link that the reader taps goes to `onLinkTap` first, in every format. The callback runs before the viewer does anything. Return `true` to stop the viewer, for example after you open a web address or show a note in a popup. Return `false`, or pass no callback, and the viewer does what the link asks where it can:

- A link inside the document moves the view to its target. A PDF link to a place on a page (such as `/XYZ` or `/FitH`) brings that place to the top of the viewport at the reader's zoom. A vertical `Continuous` strip scrolls there, while a horizontal strip and a pager pan across the page as far as the page allows. A pager that resets its zoom on a page turn shows the whole page. An XPS or SVG link does the same with the element that it names.
- A PDF link that names a page turn (NextPage, PrevPage, FirstPage or LastPage) turns the page.
- A PDF script link runs in `scripts`, when you pass a handler.
- On a page of an EPUB chapter that has scripts, with `epubScripts`, the scripts see the tap first. The viewer does not follow a link that a script stopped.
- A web or mail address goes to `externalLinks`, which asks the reader and then opens the address.
- Anything else does nothing, and the tap goes on to `onTap`.

`KiteDocLayout.SinglePage` cannot move, so there a link inside the document goes on to `onTap` too. A screen reader that activates a link goes through the same callback as a finger.

!!! warning
    A document can name any scheme, such as `file:`, `intent:` or `javascript:`. Open only the schemes that you trust.

```kotlin
KiteDocView(
    state = state,
    onLinkTap = { link ->
        val uri = link.uri
        if (uri != null && (uri.startsWith("https://") || uri.startsWith("http://"))) {
            openInBrowser(uri)
            true
        } else {
            // The viewer follows a link inside the document
            false
        }
    },
)
```

Every link gives the same facts, whatever its format:

| Property | What it is |
| --- | --- |
| `uri` | The address outside the document, or null |
| `target` | The place inside the document, as a `KiteBookmark`, or null |
| `kind` | What the link is for: `LINK`, `NOTE_REFERENCE`, `GLOSSARY_REFERENCE` or `BIBLIOGRAPHY_REFERENCE` |
| `pageIndex` | The page that holds the link |
| `rect` | The area of the link on that page, in display space |

`kind` comes from the document's markup. Only EPUB books mark their references, so a PDF, XPS or SVG link is always `LINK`. To show a popup next to a link, convert its `rect` to viewport coordinates with `state.displayRectToViewport(link.pageIndex, link.rect)`.

For the facts that only one format has, match the subclass:

| Subclass | Comes from | Holds |
| --- | --- | --- |
| `KiteLinkAction.Pdf` | A PDF link, or a form widget action that the viewer does not do | The parsed `PdfAction`: a go-to, a URI, a remote go-to, a Launch, JavaScript or a form submit |
| `KiteLinkAction.Epub` | An EPUB link | The `EpubLink`, whose `href` `EpubDocument.linkTarget` reads |
| `KiteLinkAction.Plain` | An XPS or SVG link | The page's `KiteLink` |

```kotlin
onLinkTap = { link ->
    val action = (link as? KiteLinkAction.Pdf)?.action
    if (action is PdfAction.Launch) {
        warnAboutLaunch(action.filename)
        true
    } else {
        false
    }
}
```

### Addresses outside the document

When `onLinkTap` does not take a link to an `http:`, `https:` or `mailto:` address, the viewer asks the reader whether to open it. After the reader taps Open, the viewer opens the address through Compose's `LocalUriHandler`, which starts the browser or the mail app. EPUB Reading Systems 3.3 asks a reading system to do this. `KiteExternalLinks` sets which schemes the viewer opens and whether it asks first:

```kotlin
KiteDocView(
    state = state,
    externalLinks = KiteExternalLinks(schemes = setOf("https", "mailto"), askFirst = true),
)
```

With `externalLinks = null`, the viewer opens no address. The question takes its words from `LocalKiteViewerStrings` (`openLinkQuestion`, `openLink` and `cancel`). To ask in your own way, take the link in `onLinkTap` and return `true`.

### Notes in place

An EPUB link can say that it points to a note, a glossary entry or a bibliography entry (see `EpubLink.kind` in [Reading EPUBs](epub.md#notes-and-other-link-targets)). Such a link has its `kind` set. Show the note and return `true` to keep the reader on the page. Return `false`, and the viewer goes to the note.

```kotlin
KiteDocView(
    state = state,
    onLinkTap = { link ->
        val href = (link as? KiteLinkAction.Epub)?.link?.href
        val isNote = link.kind == KiteLinkKind.NOTE_REFERENCE
        val note = if (isNote && href != null) book.linkTarget(href) else null
        if (note != null) {
            showNote(note.text)
            true
        } else {
            false
        }
    },
)
```

Some books do not mark their notes. An endnote link can be a plain `<a href="notes.xhtml#n19">[19]</a>`, which comes as a `LINK`. Check where the link goes instead:

```kotlin
onLinkTap = { link ->
    val href = (link as? KiteLinkAction.Epub)?.link?.href
    // This book keeps its endnotes in one chapter and does not mark the links to them
    val isNote = href != null && href.substringBefore('#') == notesChapter
    val note = if (isNote) book.linkTarget(href) else null
    if (note != null) {
        showNote(note.text)
        true
    } else {
        false
    }
}
```

### A back button

A link inside the document goes through `onLinkTap` before the view moves, so the callback can save where the reader was. Return `false` to let the viewer follow the link:

```kotlin
@Composable
fun ReaderWithBack(state: KiteDocViewState) {
    val history = remember { mutableStateListOf<KiteBookmark>() }
    val scope = rememberCoroutineScope()

    KiteDocView(
        state = state,
        onLinkTap = { link ->
            if (link.target != null) history += state.currentBookmark()
            false
        },
    )

    Button(onClick = {
        val back = history.removeLastOrNull()
        if (back != null) scope.launch { state.scrollTo(back, animate = true) }
    }) {
        Text("Back")
    }
}
```

A bookmark stays correct after a reflow, so the button also works in a book whose font size changed in between.

## Scripts

The viewer runs no script of a document unless you give it a handler. `kitepdf-javascript` has a handler for each format: `PdfScriptRunner` for the `scripts` parameter and `EpubScriptRunner` for `epubScripts`. [Scripts in the Compose viewer](javascript.md#scripts-in-the-compose-viewer) shows how to set them up, how a form behaves while its scripts run, and what a script can do.

## Accessibility

A screen reader finds each page of `KiteDocView` by its name ("Page 3 of 12", or "Page 3" while a book still lays out). It finds the buttons of `KiteNavigationControls`, the thumbnails of `KiteThumbnailStrip` and the colors of `KiteSelectionMenu` by name and role. The hidden input of a form field has the field's tooltip, or else its name.

On the page itself, a screen reader finds:

- The text, as one node for each item of `KitePage.readingOrder()`, in that order and at its place on the page. A heading has the heading role, and a picture reads its alternative text.
- Each link, as a button with the words under it as its name. Activating it follows the link, as a tap does.
- With a `scripts` handler, each form field. A text field or a list has its value, and a check box or a radio button has its state. The name is the field's tooltip, or else its name. Activating a field does what a tap does.

A page builds these nodes off the main thread, after it shows and the view stops moving.

The names are in English by default. `LocalKiteViewerStrings` takes your own words:

```kotlin
CompositionLocalProvider(
    LocalKiteViewerStrings provides KiteViewerStrings(
        page = { number, count ->
            if (count == null) "Seite $number" else "Seite $number von $count"
        },
        previousPage = "Vorherige Seite",
        nextPage = "Nächste Seite",
        link = "Verweis",
    ),
) {
    KiteDocView(state)
}
```

## Navigation widgets

These widgets take a `KiteDocViewState`, so you can put them anywhere in your tree: in the `overlay` slot over the pages, in your top bar or in a side panel.

### Page indicator

`KitePageIndicator` shows the current page and the page count:

```kotlin
KitePageIndicator(
    state,
    modifier = Modifier.padding(8.dp),
    format = { current, total -> "Page ${current + 1} / $total" },
)
```

### Navigation controls

`KiteNavigationControls` is a pill with a previous button, the page number and a next button. It is made to float over the viewport:

```kotlin
KiteDocView(
    state,
    overlay = {
        KiteNavigationControls(
            state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            contentColor = Color.White,
            // Dark gray, partly transparent
            containerColor = Color(0xB3222222),
        )
    },
)
```

The previous button turns off on the first page, and the next button turns off on the last page. Both turn off on a `SinglePage` layout.

### Thumbnail strip

`KiteThumbnailStrip` is a horizontal row of page thumbnails. It outlines the current page, and a tap on a thumbnail scrolls the viewer to that page:

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

The strip renders its thumbnails at their own small size and keeps them in a cache of its own, so it does not slow down the main viewer. It draws the pages with the viewer's theme and canvas decorator.

### Outline panel

`KiteOutlinePanel` lists the outline of the document: the bookmarks of a PDF or the table of contents of an EPUB. It indents each entry by its depth:

```kotlin
var showOutline by remember { mutableStateOf(false) }

if (showOutline) {
    KiteOutlinePanel(
        state,
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        onNavigate = { showOutline = false },
    )
}
```

- A tap on an entry starts the scroll to its target, then calls `onNavigate`.
- An entry without a target, such as a group label, shows in `disabledTextColor` and does not take a tap.
- The entry of the current page shows in `currentPageColor`.
- In an EPUB that is still paginating, a tap lays out only the chapter of the entry.

To show only a part of the outline, pass a subtree as `outline`.

## The overlay slot

The `overlay` slot draws composables over the viewport. It runs in a `BoxScope`, so you can align each composable:

```kotlin
KiteDocView(
    state,
    overlay = {
        // Everything here floats over the pages
        KiteNavigationControls(state, Modifier.align(Alignment.BottomCenter))

        // Your own widgets
        Text(
            "${state.currentPage + 1}",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    },
)
```

## Draw over a place on a page

The `pageOverlay` slot draws over each page, in the page's own frame. It moves and scales with the page, so an element on a rectangle of the page stays on that rectangle at any zoom. Use it for a note badge, a video surface or a mark of your own:

```kotlin
KiteDocView(
    state,
    pageOverlay = {
        // pageIndex and page name the page under this overlay
        for (note in notesOn(pageIndex)) {
            NoteBadge(Modifier.pageRect(note.rect))
        }
    },
)
```

Two modifiers give an element the size and the place of a rectangle on the page:

- `pageRect` takes the page's own space. `hitTest` and the `rect` of a PDF annotation use this space.
- `displayRect` takes display space: points from the top-left corner of the page as it shows, with y going down. `hitTestDisplay`, search hits, `EpubLink.rect` and `EpubMedia.rect` use this space.

An element that opens links (such as a web view over an embedded document) can give each link to `followLink(href, rect)`. The link then goes the same way as a tapped link of the page: `onLinkTap` sees it first, and when the callback does not take it, a link inside the document moves the view. On an EPUB page, `href` is a path in the book's archive with a fragment, as in `EpubLink.href`, or an address with a scheme.

An element in `pageOverlay` grows with the page when the reader zooms in. For an element that keeps its size on screen (such as a pin), use the `overlay` slot and ask the state where the rectangle is:

```kotlin
KiteDocView(state, overlay = {
    val bounds = state.pageRectToViewport(pageIndex = 0, rect = note.rect)
    if (bounds != null) {
        Pin(Modifier.absoluteOffset { bounds.topLeft.round() })
    }
})
```

`pageRectToViewport` and `displayRectToViewport` do the reverse of `hitTest` and `hitTestDisplay`. They return null while the page has no place in the layout, and a paged layout places only its current page. A composable that reads them follows the page as it scrolls and zooms.

## When a page renders

`onPageRendered` gets each new screen bitmap of a page. The viewer calls it once for each page and bitmap size, so again after each zoom when `rerasterizeOnZoom` is on. A bitmap from the cache does not call it again.

```kotlin
KiteDocView(
    state,
    onPageRendered = { pageIndex, bitmap ->
        thumbnails[pageIndex] = bitmap
    },
)
```

The bitmap is what the screen shows, not an export. When a `scripts` handler draws a PDF form, the bitmap does not include the form widgets. In the vectorized mode, the viewer never calls `onPageRendered`, because there is no bitmap. To export a page, use [KitePageRasterizer](#render-a-page-yourself).

## Render a page yourself

`KitePageRasterizer` renders a page to an `ImageBitmap`. Use it for a viewer that the built-in layouts do not fit (such as a grid of thumbnails) or for an export to PNG:

```kotlin
@Composable
fun FirstPageImage(document: PdfDocument, onBitmap: (ImageBitmap) -> Unit) {
    val rasterizer = rememberKitePageRasterizer()

    LaunchedEffect(document, rasterizer) {
        val page = document.pages[0]
        val bitmap = rasterizer.rasterizeOffMain(
            page,
            widthPx = 1080,
            heightPx = (1080 * page.displayHeight / page.displayWidth).toInt(),
            background = Color.White,
            hairlineWidthPx = 1f,
        )
        onBitmap(bitmap)
    }
}
```

`bitmap.encodeToPng()` gives the PNG bytes. `rememberKitePageRasterizer()` connects the rasterizer to the density, the layout direction and the text engine of the composition. Outside a composition (in a background job, for example), create a `KitePageRasterizer` yourself with a `TextMeasurer`.

!!! warning
    On the desktop JVM, Apple targets and the web, Compose starts its text engine when the first window or `ImageComposeScene` opens. Before that, `createFontFamilyResolver()` throws "No Compose UI text implementation is registered". Create the `TextMeasurer` after a window or a scene exists. `KiteDocViewState` and `KitePageRasterizer` themselves need no window.

To export a filled form, pass `formState`. The call copies the form values, the choice selections and the field flags before it waits for a render slot, so the export shows that copy even when the reader edits the form during the render:

```kotlin
val bitmap = rasterizer.rasterizeOffMain(page, 1240, 1754, formState = runner.formState)
val png = bitmap.encodeToPng()
```

### Threads

| Call | Where it can run |
| --- | --- |
| `rasterizeOffMain` | Any coroutine. A page renders in one pass on the raster pool and never needs the UI thread. In a browser, the pool is the one UI thread. |
| `rasterize` | Only the platform UI thread: Android's main Looper, Apple's main thread, the browser thread or the AWT event dispatch thread. On any other thread, it throws `IllegalStateException` before it draws. |

`rasterizeOffMain` also works on a desktop that has no coroutine Main dispatcher, and with `java.awt.headless=true`. Cancel its coroutine to stop the render. A PDF page then stops between two operators and throws a `CancellationException`, so you never get part of a bitmap.

These rules also apply when you draw on a `ComposeCanvas` of your own:

- In a browser, draw system-font text or ask for host glyph outlines on the UI thread only, because Compose's shared text cache is not safe for use from two threads.
- On the desktop JVM, iOS, macOS and Android, a `ComposeCanvas` draws that text through the platform's own text engine (Skia's paragraph engine, or Android's text stack). It can draw on any thread.
- A custom desktop `ImageComposeScene` must create, measure and draw its scene on the AWT event dispatch thread. A vectorized page and the form layer draw their text inside the scene's own draw pass, so a scene on a thread of its own also shows that text.

## Placeholders and fades

`pagePlaceholder` sets what a page shows while its bitmap renders. By default, the page shows its `pageBackground` color.

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

A new bitmap fades in over 160 ms. During a render after a zoom, the old bitmap stays on screen, so the change from the placeholder to the page, and from a soft bitmap to a sharp one, never flashes.

## How the viewer stays fast

- The viewer composes only the pages on screen. A `Continuous` strip draws `prefetchPages` pages ahead on each side when it stops, and a pager keeps `offscreenPages` pages ready on each side. The page count does not change this cost.
- The viewer renders page bitmaps on a background pool with `rasterizeOffMain`, so a scroll or a tap never waits for a render. A cache of page bitmaps (`cacheBudgetBytes`) keeps the most recently used ones.
- All viewers and thumbnail strips of a process share two render slots (one in a browser). A page on screen gets the next free slot before a page that the viewer draws ahead, and both come before a thumbnail. A page that scrolls into view while it waits moves to the front. A cache hit needs no slot.
- A page renders in one pass, text included. Text without its own font outlines (such as the text of a book without embedded fonts) draws through the platform's own text engine, with the same pixels as Compose text: Skia's paragraph engine on the desktop JVM, iOS and macOS, and Android's text stack on Android.
- A browser has one thread, so renders and chapter layout run on it. The viewer lays out the reader's chapter and its neighbors first, and the other chapters only after the view stops for 400 ms. A chapter lays out in slices of 8 ms with a frame between two slices, so a long chapter does not freeze the page. A long document script still blocks the page.
- When a page scrolls away, the viewer cancels its render.
- After a zoom stops, the viewer waits 220 ms before it renders the page again, so a quick pinch does not start many renders.

## See also

- [Reading PDFs](reading.md)
- [Reading EPUBs](epub.md)
- [Headless rendering](rendering.md)
- [JavaScript](javascript.md)
