# KitePDF

**One pure-Kotlin PDF engine for Kotlin Multiplatform.** Read, create, edit and render PDFs from `commonMain`, with the exact same code on every target: Android, iOS, desktop, web and Kotlin/Native.

```kotlin
// commonMain. Nothing platform-specific. This runs everywhere Kotlin runs.
val doc = PdfDocument.open(bytes)

val text = doc.pages[0].extractText()       // read
doc.edit().apply {                          // edit
    redactRegion(doc.pages[0], Rectangle(72.0, 700.0, 320.0, 720.0))
}.saveRewritten()

val fresh = PdfBuilder()                     // create
    .page { text(StandardFont.Helvetica, 24.0, x = 72.0, y = 720.0, "Hello, world!") }
    .build()
```

<div class="grid cards" markdown>

- :material-rocket-launch: **New here?** [Get started in 5 minutes](getting-started.md)
- :material-book-open-variant: **Browse the guides** below, or jump to the [API reference](https://yuroyami.github.io/KitePDF/api/)

</div>

## Why KitePDF

Most "Kotlin PDF libraries" are thin `expect`/`actual` wrappers around the platform's own engine: `PdfRenderer` on Android, `PDFKit` on iOS, PDF.js in the browser, PDFBox on the JVM. You inherit four engines, four sets of bugs, and four feature sets that never line up.

KitePDF is a **single standalone engine, 100% common Kotlin**, with zero `expect`/`actual` in the core. Write your PDF code once in `commonMain` and it behaves identically everywhere, because it is the same code. Parser, renderer, writer, editor, encryption and fonts are all included. When something is wrong, it is one bug in one place.

Drawing to a screen is the only thing that needs a platform, and KitePDF keeps that cleanly separate so the engine stays pure and portable.

## Install

The engine is a single dependency. Add it to `commonMain` and you have everything except drawing to a screen:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitepdf:0.2.0")
        }
    }
}
```

Its only dependency is `kotlin-stdlib`, and it works on every Kotlin target. Rendering bindings are opt-in; see [Show it on screen](#show-it-on-screen) below.

!!! note "Not using Kotlin Multiplatform?"
    The same artifact works in a plain Android or JVM project. Add `io.github.yuroyami:kitepdf:0.2.0` to your normal `dependencies { }` block.

## What you can do

Everything in this section is pure common code from the `kitepdf` engine.

### Read

```kotlin
val doc = PdfDocument.open(bytes)

doc.pages[0].extractText()      // text, or structured layout with positions
doc.pageCount                   // metadata, outlines, annotations, form fields...

// Encrypted? Pass the password.
val locked = PdfDocument.open(bytes, password = "secret".encodeToByteArray())
require(locked.isAuthenticated)
```

Text extraction (plain and structured), document metadata and XMP, outlines and bookmarks, annotations, form fields, encryption and permissions, page labels, optional-content layers, attachments and more. See **[Reading PDFs](reading.md)**.

### Create and edit

```kotlin
// Fill a form field and save (append-only)
doc.edit().apply {
    setTextFieldValue(doc.formField("Name")!!, "Jane Doe")
}.saveIncremental()

// Truly redact a region (the underlying content is removed, not covered)
doc.edit().apply {
    redactRegion(doc.pages[0], Rectangle(72.0, 700.0, 320.0, 720.0))
}.saveRewritten()

// Build a new PDF from scratch
PdfBuilder()
    .page {
        text(StandardFont.HelveticaBold, 24.0, x = 72.0, y = 720.0, "Invoice")
        setFillRgb(0.9, 0.95, 1.0); rectangle(72.0, 600.0, 200.0, 80.0); fill()
    }
    .build()
```

Build from scratch with a content DSL, fill forms, stamp and watermark, and redact for real. See **[Creating PDFs](writing.md)** and **[Editing & redaction](editing.md)**.

## Show it on screen

The engine is headless. Rendering is the one job that needs a platform, so it lives in separate, optional artifacts.

### Compose Multiplatform: `kitepdf-compose-viewer`

A PDF page is just another composable, drawn straight into a Compose `DrawScope`.

```kotlin
val state = rememberPdfViewState(doc)

PdfView(
    state = state,
    layout = PdfLayout.Paged(Orientation.Horizontal),   // or Continuous / SinglePage
    zoomSpec = PdfZoomSpec(maxZoom = 6f),                // pinch, double-tap, pan
    renderSpec = PdfRenderSpec.Rasterized(),            // or Vectorized()
    overlay = { PdfNavigationControls(it, Modifier.align(Alignment.BottomCenter)) },
)
PdfPageIndicator(state)
PdfThumbnailStrip(state)
```

See **[the Compose viewer guide](compose-viewer.md)**.

### Headless: `kitepdf-native-renderer` and `kitepdf-skia-renderer`

For servers, CI and thumbnails, render a page straight to image bytes with no UI:

```kotlin
val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
```

See **[Headless rendering](rendering.md)**.

## Guides

| | |
|---|---|
| **[Getting started](getting-started.md)** | Open your first PDF and display it, step by step. |
| **[Compose viewer](compose-viewer.md)** | `PdfView`: layouts, zoom, render modes, navigation, export. |
| **[Reading PDFs](reading.md)** | Text, metadata, outlines, annotations, forms, encryption. |
| **[Creating PDFs](writing.md)** | Build from scratch with the content DSL. |
| **[Editing & redaction](editing.md)** | Fill forms, stamp pages, redact, save. |
| **[Headless rendering](rendering.md)** | Page to PNG / Bitmap without a UI. |
| **[Recipes](recipes.md)** | Copy-paste patterns for common tasks. |
| **[Platform support](platforms.md)** | What runs where, and why. |

## Status

KitePDF is pre-1.0 and actively developed. Reading, text extraction, metadata, outlines, annotations, forms, encrypted documents, the Compose viewer, headless rendering, editing, redaction and building from scratch all work today. Digital signatures, the JBIG2 and JPEG 2000 codecs, and advanced colour management are on the way.

If a PDF renders incorrectly, [open an issue](https://github.com/yuroyami/KitePDF/issues) with the file attached. Every fix lands as a regression test against a MuPDF pixel-diff harness.
