# Compose viewer (KiteDocView)

Build a full-featured PDF viewer in Compose with a single composable. The `KiteDocView` family lets you display PDFs on screen with pinch zoom, paging, panning, and customizable rendering; all in pure Kotlin Multiplatform.

## Run a document's scripts

A PDF can carry scripts: a form that totals a column, a button that hides a field, a page that
starts something when it opens. The viewer runs none of them unless you hand it a handler, and
`kitepdf-javascript` provides one:

```kotlin
val runner = remember(doc) { PdfScriptRunner(doc, onAlert = { alert -> showDialog(alert.message); 1 }) }
KiteDocView(state = state, scripts = runner)
```

With a handler the viewer fires the document and page triggers, sends a tap on a widget to the
scripts, draws the form from its live values, and pumps the timers a script set, one frame at a
time. The page itself is drawn once and kept: only the fields that changed are repainted, so a
script that writes a field twenty times a second costs twenty small redraws.

Without a handler nothing in the document runs, which is the default.

### Filling a form

With a handler, a tap on a widget does what a viewer does: a push button runs its press and
release scripts, a check box or a radio button changes the form's value, and a text field takes
the caret and opens the keyboard. Each character the reader types goes through the field's own
keystroke script first, so a form that only takes digits refuses a letter, and leaving the field
commits it: validate, then calculate, then format.

`state.focusedField` says which field has the caret, and it is null when none has.

Choice fields use the control their PDF flags specify (ISO 32000-1, 12.7.4.4):

- A noneditable combo opens an option picker. Its entries show display labels, and choosing
  one stores its export value without opening the text keyboard.
- An editable combo also takes typed text and filters suggestions by their display labels.
- A list shows its option rows inside the widget. A multiple-selection list toggles individual
  rows while retaining the other selected options. `CommitOnSelChange` commits each proposed
  selection immediately; otherwise leaving the list or pressing Enter commits its draft.

Arrow keys, Home and End move through options. Space toggles the current row; Enter accepts
and Escape discards a pending draft. Tab and Shift+Tab move between editable form widgets.
Choice options expose their selected state to accessibility services. Only an editable combo
uses text input. A refused script transaction restores the committed selection.

The field model keeps option identity separately from the text that is shown or exported:

```kotlin
val field = requireNotNull(doc.formField("interests"))
val options: List<PdfChoiceOption> = field.choiceOptions
// Each option has index, exportValue and label. index is its original /Opt position.
val proposed = PdfChoiceSelection(indices = options.take(2).map { it.index })

// Preview runs the selection-change keystroke without changing the live value.
val preview = runner.choiceKeystroke(field.fullyQualifiedName, proposed)
// Commit the accepted preview when the reader accepts it or leaves the field.
val accepted = preview != null && runner.commitChoice(field.fullyQualifiedName, preview)
val current: PdfChoiceSelection? = runner.formState.choiceSelection(field.fullyQualifiedName)
```

`choiceKeystroke` returns the accepted, possibly rewritten draft, or null when a script
refuses it. A deferred list calls it on each selection change and retains that draft outside
the live form state. `commitChoice` runs the commit keystroke, validation, store, calculation
and formatting without repeating the selection-change event. The viewer orders both calls
on its script lane; an immediate commit runs them consecutively. Typed text in an editable
combo uses ordinary text keystrokes before its final typed choice commit.

`choiceOptions` preserves distinct options even when their labels or exports are equal, and
retains original indices when damaged entries are skipped. The older `options: List<String>`
remains a display-label view. Use `choiceSelection`, including its complete `indices` list,
for selections. An editable combo can instead hold `freeText`. Empty indices with no text
mean no selection; this differs from an option whose export string is empty. Selection lists
are immutable snapshots. Unknown source values stay readable in `unresolvedValues` until a
valid edit replaces them, so opening a damaged form does not silently erase its value.

`PdfFormState.value(name)` and `Change.value` remain scalar compatibility views. For multiple
selections they return the **first selected export value in original option order**. They
never join the values into one string. `setValue(name, value)` replaces the selection with one
matching export, choosing the first matching option when exports are duplicated. Unmatched
text is accepted only by editable combos; an empty string clears a noneditable field when
there is no empty-export option. Use typed indices when a duplicate or empty option must be
identified exactly.

`formState.setChoiceSelection(name, selection)` updates live state directly and returns false
for an invalid selection. It publishes one revision and one `Change` containing the complete
`choiceSelection`; it does not execute scripts. Programmatic writes can update read-only
fields. Custom user transactions can capture `fieldRevision(name)` and pass that revision to
the three-argument setter, which atomically refuses stale, hidden or read-only targets.
That overload also restores an exact parsed source/default selection when cancelling an
edit, including its unresolved source values. New user commits still pass through
`field.validateChoiceSelection`; arbitrary unresolved values remain invalid.
`cancelChoiceTransaction(name, expectedRevision)` invalidates a still-pending choice edit
without changing its value or redraw revision. A validation result using the cancelled
revision can no longer store its value; cancellation after an intervening commit returns
false and preserves that committed value.
`reset(name)` restores the file's source value, `resetAll()` also drops visibility/read-only
overrides, and `resetForm()` applies the typed `/DV` defaults.

An existing custom `PdfScriptHandler` keeps its string methods. The default `choiceKeystroke`
adapter passes a single display label through the existing `keystroke` hook. Separately,
the default `commitChoice` adapter passes its export value to the existing `commit` hook;
it refuses multiple selections or a duplicate identity that the string API cannot preserve.
A handler supporting those cases overrides `commitChoice` and `supportsMultipleChoices`,
and overrides `choiceKeystroke` when its selection previews need custom script processing.
The supplied `PdfScriptRunner` supports typed choices and the JavaScript `value`,
`valueAsString`, `currentValueIndices` and `getItemAt` export/display distinctions.

These are live editing APIs. They do not save the document. The writer's existing scalar
`PdfEditor.setChoiceValue` remains separate; saving complete typed multiple selections,
their array `/V` and `/I` entries, and matching saved appearances is outside this change.
Do not save a multiple selection by passing the scalar `value(name)` view to that method.

### Where the scripts run

On a thread of their own. A form script finishes in milliseconds, but a document that carries a
program in a page's open action can work for tens of seconds before it shows anything, and the
reader must still be able to scroll and close it meanwhile. The viewer posts every script call to
that one thread, in order, and repaints when the form changes.

A character the reader types is shown at once and the field's keystroke script has the last word:
if it refuses, the field goes back to what it held.

## Installation

Add the `kitepdf-compose-viewer` artifact to your Gradle dependencies:

=== "Kotlin (KMP)"

    ```kotlin
    // commonMain
    dependencies {
        implementation("io.github.yuroyami:kitepdf-compose-viewer:0.12.0")
    }
    ```

=== "Android / JVM"

    ```gradle
    dependencies {
        implementation("io.github.yuroyami:kitepdf-compose-viewer:0.12.0")
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

### `KiteDocLayout.Spread` (two pages side by side)

Two pages per screen, like an open book. Swipe or drive it programmatically, as with `Paged`.

```kotlin
KiteDocView(
    state,
    layout = KiteDocLayout.Spread(
        reverseLayout = false, // true for a right-to-left book
        firstPageAlone = true, // show the cover alone, then pair the pages after it
    ),
)
```

Pages pair in order, the first two, the next two and so on, except where the document says otherwise:

- An EPUB chapter can ask for its first page on the left side, on the right side, or alone in the centre.
- An EPUB book or chapter can ask for no spreads, or for spreads in a landscape viewport only.
- A PDF whose `/PageLayout` is `TwoPageRight` or `TwoColumnRight` puts its first page on the right, so a left-to-right document shows it alone.

A page without a partner shows alone, centred. When the viewport turns between portrait and landscape, the reader stays on the same page.

**Best for:** fixed-layout books, magazines and comics made as two-page spreads.

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

A zoomed page pans with one finger. A quick release lets the pan go on and slow down, and a new touch stops it. In `Paged` and `Spread`, a drag that goes on past the edge of a zoomed page turns the page, the way the pager turns.

To disable zoom entirely:

```kotlin
KiteDocView(state, zoomSpec = KiteZoomSpec.Disabled)
```

### Mouse, trackpad and keyboard

On the desktop and the web, and on a tablet with a mouse or a keyboard, the viewer also takes these inputs:

| Input | What it does | Turned off by |
|---|---|---|
| Ctrl or Cmd with the wheel, or a trackpad pinch | Zooms about the pointer | `pinchEnabled = false` |
| Ctrl or Cmd with plus, minus or 0 | Zooms in, zooms out, goes back to fit | `pinchEnabled = false` |
| Page Down, Space, the down arrow | Next page | `userScrollEnabled = false` |
| Page Up, Shift with Space, the up arrow | Previous page | `userScrollEnabled = false` |
| The left and right arrows | Previous and next page; swapped where pages advance to the left | `userScrollEnabled = false` |
| Home and End | First and last page | `userScrollEnabled = false` |
| A mouse press and drag on text | Selects the text at once | `selectionEnabled = false` |

The keys work once the viewer has the focus, which a press on it gives. A form field with the caret keeps its keys.

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
- **`maxBitmapLongSide`** (default 4096): hard memory cap on a page's whole bitmap. A page that needs more pixels, at deep zoom or because it is very tall, keeps the capped bitmap, and the part on screen draws over it in tiles of 1,024 pixels at full resolution. On Android, keep it at or below the GPU's texture limit (4096 on many devices), or the page draws blank.
- **`rerasterizeOnZoom`** (default true): after a zoom settles, re-render the visible page at the zoomed resolution so deep zoom stays crisp. The zoom rounds up to a quarter of an octave (1, 1.19, 1.41, 1.68, 2 and so on), so pinches that settle close together share one raster. Costs one extra rasterization per zoom step.
- **`preserveHairlines`** (default true): scale the engine's stroke floors by the ratio of the raster to the screen. A zero-width stroke then stays one screen pixel wide, and other sub-pixel strokes (ECG traces, fine table rules) keep their weight when the bitmap is downscaled.

### `KiteRenderSpec.Vectorized`

Draw each page's content into a live Canvas, transformed by zoom/pan via a GPU layer. There is no whole-page raster; embedded images use a bounded cache of converted bitmaps shared by the pages of the viewer. The page draws again when its size, its settled zoom, the theme or the render spec changes. A change to the overlay, such as a search hit or a highlight, and each frame of a pinch replay the drawing that the page recorded.

```kotlin
val spec = KiteRenderSpec.Vectorized(
    hairlineWidthPx = 1f, // width of a zero-width stroke, in device pixels
    imageCacheBudgetBytes = 16L * 1024 * 1024, // shared by this viewer's pages
)
KiteDocView(state, renderSpec = spec)
```

**When to use:**
- Simple pages with minimal content (forms, text-only documents).
- Deep zoom crispness matters more than gesture smoothness.
- Whole-page raster memory would be too large; tune the embedded-image budget separately.
- Every composition must stay crisp (e.g. animation).

**Parameters:**

- **`hairlineWidthPx`** (default 1.0): the width in device pixels of a stroke whose line width is 0. `1.0` is the one device pixel of ISO 32000-1, 8.4.3.2. Other thin strokes (ECG traces, fine borders) widen to a fifth of this width, as MuPDF draws them, so they stay visible without turning into solid pixels.
- **`imageCacheBudgetBytes`** (default 16 MiB): the converted-image budget shared by the pages of one `KiteDocViewState`. Unchanged images reuse resident bitmaps at the same sampling size across redraws and page recycling. Zero disables reuse between draws; negative values are rejected. Least recently used entries are evicted first, and an image larger than the budget is drawn without retaining it. Leaving vector mode, disposing the viewer, or changing the budget releases the cache's references. Recorded drawing layers may still hold those bitmaps until the layers are released.

A PDF image XObject, a CBZ entry and an EPUB image resource each carry an identity for the document. The identity outlives the decoded image, so an image decoded again reuses its bitmap. The cache keys hold only these identities, never image buffers or documents. PDF stencils, images with a `/Mask`, images under a page's default colour space, inline images and SVG resources get a new identity on each decode, so they do not reuse a bitmap. A custom image producer can give its images an identity with `KiteImageIdentity().child(resourcePath)` and `KiteImageData.withIdentity(identity)`. Keep one root identity per open document. Use a new identity when the pixels, masks, size or colour interpretation change. Do not change an image's buffers after you draw it, also in a canvas decorator.

!!! warning "Rasterized vs. Vectorized trade-off"

    **Rasterized** wins on gesture smoothness: scroll and pan never re-execute the PDF engine. It trades memory (one bitmap) and rasterization latency for instant playback.
    
    **Vectorized** avoids a whole-page raster, but it draws on the UI thread. Each real redraw parses the page content. Cached image bitmaps are reused. A first conversion, an evicted image or a new sampling size still converts on the UI thread. A page of large scans, such as a comic, is better in Rasterized. On Android the vector display list replays under the live transform so zoom stays crisp mid-pinch; on Skia targets (iOS, desktop, web) the layer is texture-cached so deep in-gesture zoom softens until the draw re-runs.
    
    For most apps, **Rasterized with `rerasterizeOnZoom=true`** is the sweet spot: responsive gestures and crisp zoom, with a small memory footprint per page.

### Custom canvas decorators

Pass `canvasDecorator` to either render spec to filter ink, inspect glyphs or
insert drawing calls inside the page's own paint pass. This hook is also
available on `KitePageRasterizer.rasterize` and `rasterizeOffMain`.

```kotlin
val decorator: KiteCanvasDecorator = remember {
    { inner ->
        object : KiteCanvas by inner {
            override fun fillPath(
                path: KitePath, ctm: KiteMatrix, color: RgbColor,
                evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode,
            ) {
                inner.fillPath(path, ctm, color, evenOdd, alpha * 0.8, blendMode)
            }
        }
    }
}
KiteDocView(
    state = state,
    renderSpec = KiteRenderSpec.Rasterized(canvasDecorator = decorator),
)
```

The supplied canvas includes the reader theme, so custom colours pass through
its colour mapping. Paper, viewer overlays and interactive form controls are
outside this hook. The wrapper receives the same coordinates and matrices as
`KiteCanvas` and should delegate operations it does not customize.

Some `KiteCanvas` operations have two overloads. `drawImage` has one with a
blend mode, which the renderer calls for an image that does not paint with
the Normal blend mode. `applySoftMask` has one with a transfer function, which
the renderer calls for a soft mask that has one. To customize such an
operation, override both overloads.
With `by inner`, Kotlin sends an overload that the wrapper does not override
straight to `inner`.

Rasterization runs on a background thread. On Android a page with system-font
text renders on Main, and a page that does not say beforehand that it has such
text renders off Main first and then again on Main, so the function can run
twice for one bitmap. On the desktop JVM, iOS and macOS such a page renders once
off Main, and in a browser once on its one thread (#131). Create a fresh wrapper
in the function, keep it repeatable, and never retain the supplied canvas.
Cache hits do not invoke it.
Remember the function for cache reuse; replace it when captured rendering
settings change so the viewer redraws with a new cache key.

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
        hit = KiteSearchHit(note.location, note.quads, note.text),
        color = note.category.tint,       // null keeps KiteDocViewColors.searchHighlight
        edgeMarker = true,                // a pill in the page margin
        edgeMarkerColor = Color(0xFFEF6C00),
    )
}
```

The marker sits in the page's right margin, level with the highlighted text, so a reader can tell a note lives on the page without hunting for the words. It scales with the rendered page, so it keeps its proportions in a thumbnail and at deep zoom alike, and its inner edge is clamped past the highlighted quads so it never paints over the words.

A page location is `(chapter, page within chapter)` in the current layout. Save it with
selection quads instead of saving the selection's transient strip slot:

```kotlin
state.onSelectionChange = { selection ->
    if (selection != null) {
        val location = requireNotNull(selection.location)
        state.highlights = listOf(
            KiteHighlight(KiteSearchHit(location, selection.quads, selection.text), id = "note-1")
        )
    }
}
```

`KiteTextSelection.location`, `KitePageHit.location` and `KiteHighlight.location` identify
the real page even while earlier chapters are loading. Document searches also populate
`KiteSearchHit.location`. For standalone structured text, supply the coordinate with
`text.search("invoice", location = location)`. A location takes precedence over a hit's
legacy integer, including when the requested page is not published yet. An unavailable
located hit waits for its own page; it never paints at the legacy integer.

An old hit with no location treats `pageIndex` as a global page index, matching document
search. It paints only in the published prefix before the first pending chapter. PDF
behavior is unchanged. An old persisted EPUB note containing only a strip slot needs
its original chapter mapping to migrate; a slot alone cannot recover the intended page.

Locations and quads survive the publication of other chapters and reopening identical
content with identical layout settings. They must be recomputed after font, viewport,
margin or resource changes. Durable annotations across reflow need a content range,
which this page-coordinate API does not provide.

`state.locationOf(slot)` and `state.slotOf(location)` are exact, nonblocking lookups of
the published strip. They return null for pending or invalid pages and perform no layout.
Use `state.scrollTo(location)` to prepare and navigate to a location. `currentSlot`,
`scrollToSlot` and `animateScrollToSlot` explicitly name the strip coordinate. The older
`currentPage`, `scrollToPage`, selection/hit `pageIndex`, thumbnail callback indices,
`onPageRendered` and overlay indices still mean strip slots. Document-search indices
and `SinglePage.pageIndex` retain their global-page meaning.

The original constructors and JVM `copy`/`copy$default` signatures of `KitePageHit` and
`KiteTextSelection` are retained. A legacy copy preserves its source location; use the
new `copy(location = ...)` argument to change it explicitly. Locations now participate
in data-class equality, hashing and `toString`, and are the last destructured component.
The old `KiteSearchHit` constructor and `KiteStructuredText.search` overload also remain.

Clear either channel by assigning an empty list.

## Text selection

A long press anchors a selection, dragging extends it, and the result lands in `state.selection` (with `state.onSelectionChange` for a callback). A mouse press and drag on text selects at once, without the long press. The viewer never touches the clipboard: read `selection.text` and copy it in your app. On a desktop, bind Ctrl or Cmd with C to that copy:

```kotlin
val clipboard = LocalClipboardManager.current
KiteDocView(
    state = state,
    modifier = Modifier.onKeyEvent { event ->
        val copy = event.type == KeyEventType.KeyDown && event.key == Key.C &&
            (event.isCtrlPressed || event.isMetaPressed)
        val text = state.selection?.text
        if (copy && text != null) {
            clipboard.setText(AnnotatedString(text))
            true
        } else {
            false
        }
    },
)
```

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

The menu sits above the selection, or below it when there is no room above, centred on it and kept inside the viewport, so it does not cover the words.

Every visual layer is replaceable: `container` swaps the card, `itemContent` swaps how one action renders, `colorSwatch` swaps how one colour renders, and `alignment` pins the whole menu to one place in the viewport instead. For a completely different menu, skip the composable and build your own against `state.selection`, `state.selectionInProgress` and `state.selectionBounds`, the selection's box in viewport pixels; the built-in one is a default, not a contract.

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

A PDF of more than 200 pages shows the same slot for its first frames. It builds
its page list off the main thread, and then opens at the page the state asked for.

### Reading the position

| Member | Use it for |
|---|---|
| `state.currentLocation` | where the reader is, always exact |
| `state.currentBookmark()` | a content anchor that survives EPUB reflow |
| `state.currentScrollPosition` | the leading visible page and continuous offset in pixels |
| `state.currentPage` | the slot on screen, for an indicator |
| `state.knownPageCount` | pages laid out so far |
| `state.isComplete` | true once the total is final |
| `state.scrollTo(location)` / `scrollTo(bookmark)` | move, laying out one chapter |

For an exact continuous PDF scroll position, save `currentScrollPosition`:

```kotlin
val saved: KiteScrollPosition = state.currentScrollPosition
// Persist saved.location.chapter, saved.location.page and saved.offsetPx.
val reopened = rememberKiteDocViewState(document, saved)
// Or restore an existing viewer from a coroutine:
state.scrollTo(saved)
```

This records the leading visible page, which can differ from the page nearest
its viewport's centre (`currentLocation`), plus the unzoomed pixel offset into
that page. Both continuous axes and right-to-left layout direction are
supported. An exact visual match requires the same page layout, viewport and
density; zoom and cross-axis pan are separate. For reflowable EPUBs whose font
or page size changes, use `currentBookmark()` to retain the content anchor.
Paged and single-page layouts report an offset of zero and restore the page.

`KitePageIndicator` prefixes the total with `~` until `isComplete`.

`state.pageCount` is still there and still exact, but reading it lays out every
chapter. Prefer `knownPageCount` with `isComplete`.

## Link taps

Every link that the reader taps goes to `onLinkTap` first, in every format: PDF,
EPUB, XPS and SVG. The callback runs before the viewer does anything. Return
`true` to keep the viewer from acting, for example after you open a web address
or show a note in a popup. Return `false`, or pass no callback, and the viewer
does what the link asks where it can:

- A link inside the document moves the view to its target. A PDF link to a
  place on a page, such as `/XYZ` or `/FitH`, brings that place to the top of
  the viewport at the reader's zoom. A vertical `Continuous` strip scrolls there.
  A horizontal strip and a pager pan across the page, as far as the page lets
  them. A pager that resets its zoom on a page turn shows the whole page, so the
  place is on screen. An XPS or SVG link does the same with the element that it
  names.
- A PDF link that names a page turn (NextPage, PrevPage, FirstPage or LastPage)
  turns the page.
- A PDF script link runs in `scripts`, when you pass a handler.
- Anything else, such as a web address, does nothing, and the tap goes on to
  `onTap`.

`KiteDocLayout.SinglePage` cannot move, so there a declined link inside the
document goes on to `onTap` too. A screen reader that activates a link goes
through the same callback as a finger.

```kotlin
KiteDocView(
    state = state,
    onLinkTap = { link ->
        val uri = link.uri
        if (uri != null && (uri.startsWith("https://") || uri.startsWith("http://"))) {
            openInBrowser(uri)
            true
        } else {
            false // the viewer follows a link inside the document
        }
    },
)
```

A document can name any scheme, `file:`, `intent:` and `javascript:` included, so
open only the ones you trust.

Every link gives the same facts, whatever its format:

| Property | What it is |
|---|---|
| `uri` | the address outside the document, or null |
| `target` | the place inside the document, as a `KiteBookmark`, or null |
| `kind` | what the link is for: `LINK`, `NOTE_REFERENCE`, `GLOSSARY_REFERENCE` or `BIBLIOGRAPHY_REFERENCE` |
| `pageIndex` | the page that holds the link |
| `rect` | the link's area on that page, in display space |

`kind` comes from the document's markup. Only EPUB books mark their references
today, so a PDF, XPS or SVG link is `LINK`. To place a popup next to a link, turn
its `rect` into viewport coordinates with
`state.displayRectToViewport(link.pageIndex, link.rect)`.

When you need what only one format has, match the subclass:

| Case | Comes from | Carries |
|---|---|---|
| `KiteLinkAction.Pdf` | a PDF link, or a form widget's action that the viewer does not perform | the parsed `PdfAction`: a go-to, a URI, a remote go-to, a Launch, JavaScript, a form submit |
| `KiteLinkAction.Epub` | an EPUB link | the `EpubLink`, whose `href` `EpubDocument.linkTarget` reads |
| `KiteLinkAction.Plain` | an XPS or SVG link | the page's `KiteLink` |

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

### Notes in place

An EPUB link can mark itself as a reference to a note, a glossary entry or a
bibliography entry (see `EpubLink.kind` in [EPUB](epub.md)). Such a link arrives
with its `kind` set. Show the note and return `true` to keep the reader on the
page. Return `false` and the viewer scrolls to the note as usual.

```kotlin
KiteDocView(
    state = state,
    onLinkTap = { link ->
        val href = (link as? KiteLinkAction.Epub)?.link?.href
        val note = if (link.kind == KiteLinkKind.NOTE_REFERENCE && href != null) book.linkTarget(href) else null
        note?.let { showNote(it.text); true } ?: false
    },
)
```

Some books do not mark their notes. An endnote call can be a plain
`<a href="notes.xhtml#n19">[19]</a>`, which arrives as a `LINK`. Check where it
leads instead:

```kotlin
onLinkTap = { link ->
    val href = (link as? KiteLinkAction.Epub)?.link?.href
    // This book keeps its endnotes in one chapter and does not mark the links to them.
    val note = if (href != null && href.substringBefore('#') == notesChapter) book.linkTarget(href) else null
    note?.let { showNote(it.text); true } ?: false
}
```

### A back button

A link inside the document passes through `onLinkTap` before the view moves, so
the callback can save where the reader was. Return `false` to let the viewer
follow the link:

```kotlin
val history = remember { mutableStateListOf<KiteBookmark>() }
val scope = rememberCoroutineScope()
KiteDocView(
    state = state,
    onLinkTap = { link ->
        if (link.target != null) history += state.currentBookmark()
        false
    },
)
Button(onClick = { history.removeLastOrNull()?.let { scope.launch { state.scrollTo(it, animate = true) } } }) {
    Text("Back")
}
```

A bookmark survives a reflow, so the button works in a book whose font size
changed in between.

## Accessibility

A screen reader finds each page of `KiteDocView` by name ("Page 3 of 12", or "Page 3" while a book still lays out), and the buttons of `KiteNavigationControls`, the thumbnails of `KiteThumbnailStrip` and the colours of `KiteSelectionMenu` by name and role. The hidden input of a form field carries the field's tooltip, or its name.

On the page itself, a screen reader finds:

- The text, one node per item of `KitePage.readingOrder()`, in that order and at its place on the page. A heading is marked as one, and a picture reads its alternative text.
- Each link, as a button named by the words under it. Activating it follows the link, as a tap does.
- With a `scripts` handler, each form field: a text field or a list with its value, a check box or a radio button with its state, named by the field's tooltip, or its name. Activating it acts as a tap does.

A page builds these nodes once it shows and the view rests, off the main thread.

The names are English by default. Provide your own words through `LocalKiteViewerStrings`:

```kotlin
CompositionLocalProvider(
    LocalKiteViewerStrings provides KiteViewerStrings(
        page = { number, count -> if (count == null) "Seite $number" else "Seite $number von $count" },
        previousPage = "Vorherige Seite",
        nextPage = "Nächste Seite",
        link = "Verweis",
    ),
) {
    KiteDocView(state)
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

## Draw over a place on a page

The `pageOverlay` slot draws over each page, in the page's own frame. It moves and scales with the page, so an element placed on a rectangle of the page stays on that rectangle at any zoom. Use it for a note badge, a video surface or a mark of your own:

```kotlin
KiteDocView(
    state,
    pageOverlay = {
        // pageIndex and page name the page under this overlay.
        for (note in notesOn(pageIndex)) {
            NoteBadge(Modifier.pageRect(note.rect))
        }
    },
)
```

Two modifiers size an element to a rectangle of the page and place it there:

- `pageRect` takes the page's own space. This is the space of `hitTest` and of the `rect` of a PDF annotation.
- `displayRect` takes display space: points from the top-left corner of the page as it is shown, y down. This is the space of `hitTestDisplay`, of search hits, and of `EpubLink.rect` and `EpubMedia.rect`.

An element in `pageOverlay` grows with the page when the reader zooms in. For an element that keeps its size on screen, such as a pin, use the `overlay` slot and ask the state where the rectangle is:

```kotlin
KiteDocView(state, overlay = { state ->
    val bounds = state.pageRectToViewport(pageIndex = 0, rect = note.rect)
    if (bounds != null) Pin(Modifier.absoluteOffset { bounds.topLeft.round() })
})
```

`pageRectToViewport` and `displayRectToViewport` are `hitTest` and `hitTestDisplay` in reverse. They return null while the page has no place in the layout. A paged layout places only its current page. A composable that reads them follows the page as it scrolls and zooms.

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

If you need a viewer that doesn't fit the built-in layouts (e.g. a thumbnail grid, an image-gallery-style pager, or a PNG batch export), use `KitePageRasterizer` directly. Start a suspend export from a coroutine:

```kotlin
@Composable
fun MyCustomPdfViewer(document: PdfDocument, onBitmap: (ImageBitmap) -> Unit) {
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

`rememberKitePageRasterizer()` wires the rasterizer to the composition's density, layout direction, and text measurement engine. For off-composition rasterization (e.g. a background job), construct `KitePageRasterizer` directly if you already have a `TextMeasurer`.

Every synchronous `rasterize` overload requires the platform UI thread: Android's main
Looper, Apple's main thread, the browser thread, or the AWT event dispatch thread on
desktop JVM. Calls on other threads throw `IllegalStateException` before allocating or
drawing. On Android and in a browser, the same requirement applies when a directly
constructed `ComposeCanvas` draws system-font text or asks for host glyph outlines, since
Compose's shared text cache is not safe for simultaneous UI and worker access (#428). On
the desktop JVM, iOS and macOS such a canvas draws that text through Skia's own paragraph
engine, which keeps no such cache, so it draws on any thread (#131).

Use `rasterizeOffMain` from a worker coroutine or for a headless JVM export. Geometry and
embedded outlines stay on the raster pool. On the desktop JVM, iOS and macOS host text
stays there too, and the page renders in one pass that never touches the UI thread. On
Android host text goes to the UI thread. Desktop uses
asynchronous AWT dispatch even when no coroutine Main provider is installed, and works
with `java.awt.headless=true`. Do not block the UI thread with `runBlocking` or a future
wait while exporting. Custom desktop `ImageComposeScene` integrations must also create,
measure and draw their scenes on the AWT event dispatch thread. One part needs no such
thread: a Vectorized page and the form layer draw their host text inside the scene's own
draw pass, where Compose draws the scene's text, so a scene on a thread of its own shows
that text too (#464). A Rasterized page and an export draw their host text on the raster
pool (#131).

Pass `formState` to export a filled form. The suspend overload copies all accepted form
values, typed choice selections and field flags before waiting for a raster slot. Both
paint passes see that snapshot even if the reader edits the form meanwhile:

```kotlin
val bitmap = rasterizer.rasterizeOffMain(page, 1240, 1754, formState = scripts.formState)
val png = bitmap.encodeToPng()
```

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
- **Two pages render at once, the visible one first**: every viewer and thumbnail strip in the process shares two render slots. A page on screen gets the next free slot before a page drawn ahead, and both come before a thumbnail. A page that scrolls into view while it waits moves ahead. A cache hit needs no slot.
- **System-font text renders off Main, except on Android**: a page whose text has no font outlines of its own, such as the text of a book without embedded fonts, renders on the raster pool on the desktop JVM, iOS and macOS, where Skia's own paragraph engine draws that text on any thread (#131). On Android it renders on the main thread, because Compose's text stack is not safe to use from two threads. `KitePage.drawsHostFontText` lets a page say so up front, and an EPUB page does, so such a page renders once there. A page that does not say so renders off Main first, and the viewer remembers it for its next raster.
- **In a browser, everything runs on the UI thread**: JS and Wasm have one thread, so rasters and chapter layout run there. The viewer lays out the reader's chapter and its neighbours at once, and every other chapter only after the view has rested for 400 ms, so a scroll or a pinch does not stall while a book loads. A page renders in one pass there, because a probe off the main thread gains nothing. A long document script still blocks the page.
- **A page scrolled past stops rendering**: cancelling the coroutine of `rasterizeOffMain()` stops a PDF page between operators and throws a `CancellationException` instead of returning a partial bitmap.
- **Synchronous UI export**: `KitePageRasterizer.rasterize()` requires the platform UI thread and refuses other callers before drawing. Background jobs use `rasterizeOffMain()`, including exports with live form state.
- **Zoom settle debounce**: By default, `rerasterizeOnZoom=true` waits approximately 220 ms after zoom stops before re-rendering, so quick pinch-and-release doesn't thrash the rasterizer.

## See also

- [Reading and writing PDFs](reading.md)
- [Headless rendering](rendering.md)
