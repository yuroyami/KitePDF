# KitePDF

**One pure-Kotlin document engine for Kotlin Multiplatform.** Read, create, edit and render PDFs, and read EPUBs, from `commonMain`, with the exact same code on every target: Android, iOS, desktop, web and Kotlin/Native.

```kotlin
// commonMain. Nothing platform-specific. This runs everywhere Kotlin runs.
val doc = PdfDocument.open(bytes)

val text = doc.pages[0].extractText()       // read
doc.edit().apply {                          // edit
    redactRegion(doc.pages[0], KiteRectangle(72.0, 700.0, 320.0, 720.0))
}.saveRewritten()

val fresh = PdfBuilder()                     // create
    .page { text(StandardFont.Helvetica, 24.0, x = 72.0, y = 720.0, "Hello, world!") }
    .build()
```

<div class="grid cards" markdown>

- :material-rocket-launch: **New here?** [Get started in 5 minutes](getting-started.md)
- :material-book-open-variant: **Reading EPUBs too?** The same core paginates and renders [reflowable EPUB 2/3 books](epub.md)
- :material-code-braces: **Browse the guides** below, or jump to the [API reference](https://yuroyami.github.io/KitePDF/api/)

</div>

## Why KitePDF

Most "Kotlin PDF libraries" are thin `expect`/`actual` wrappers around the platform's own engine: `PdfRenderer` on Android, `PDFKit` on iOS, PDF.js in the browser, PDFBox on the JVM. You then depend on four engines. Each one has its own bugs and its own feature set.

KitePDF is a single standalone engine, written in Kotlin and almost entirely in common code. Parser, renderer, writer, editor, encryption and fonts are all included, and `kitepdf-core` carries just three `expect` declarations (a mutex, a thread id, and the deflate/inflate hook). Write your PDF code once in `commonMain` and the same implementation runs on every target.

Drawing to a screen is the only job that needs a platform. KitePDF keeps that job in separate artifacts, so the engine itself stays portable.

## Install

The engine is a single dependency. Add it to `commonMain` and you have everything except drawing to a screen:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitepdf:0.8.1")
        }
    }
}
```

Its runtime dependencies are `kotlin-stdlib` and the pure-Kotlin KiteImage codec engine. It runs on every target listed under [Platform support](platforms.md).

Drawing a page to the screen is the one job that needs a platform, so the rendering bindings are separate, opt-in artifacts. Add the one that matches how you draw:

| Artifact | Add it when you want |
|---|---|
| `io.github.yuroyami:kitepdf` | The engine: read, write and edit PDFs, **and** read EPUBs. Pure Kotlin (stdlib plus KiteImage codecs). |
| `io.github.yuroyami:kitepdf-compose-viewer` | A Compose `KiteDocView` for PDF and EPUB, drawn straight into a `DrawScope`. |
| `io.github.yuroyami:kitepdf-net` | Optional. Opens a document straight from a URL; the only artifact that pulls in Ktor. |
| `io.github.yuroyami:kitepdf-native-renderer` | Headless page → image through the platform canvas (AWT, CoreGraphics, `android.graphics`, Canvas2D). |
| `io.github.yuroyami:kitepdf-skia-renderer` | Headless page → image through Skia / Skiko: one API on JVM, Android, Apple, Linux and web. |

Every artifact is at `0.5.0`. For a single format, add `kitepdf-pdf` (the PDF handler alone) or `kitepdf-epub` (the EPUB reader alone). The `kitepdf` umbrella contains both. See [Show it on screen](#show-it-on-screen) for each binding in use.

!!! note "Not using Kotlin Multiplatform?"
    The same artifact works in a plain Android or JVM project. Add `io.github.yuroyami:kitepdf:0.8.1` to your normal `dependencies { }` block.

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
    redactRegion(doc.pages[0], KiteRectangle(72.0, 700.0, 320.0, 720.0))
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
implementation("io.github.yuroyami:kitepdf-compose-viewer:0.8.1")
```

```kotlin
val state = rememberKiteDocViewState(doc)

KiteDocView(
    state = state,
    layout = KiteDocLayout.Paged(Orientation.Horizontal),   // or Continuous / SinglePage
    zoomSpec = KiteZoomSpec(maxZoom = 6f),                // pinch, double-tap, pan
    renderSpec = KiteRenderSpec.Rasterized(),            // or Vectorized()
    overlay = { KiteNavigationControls(it, Modifier.align(Alignment.BottomCenter)) },
)
KitePageIndicator(state)
KiteThumbnailStrip(state)
```

See **[the Compose viewer guide](compose-viewer.md)**.

### Headless: `kitepdf-native-renderer` and `kitepdf-skia-renderer`

For servers, CI and thumbnails, render a page straight to image bytes with no UI:

```kotlin
implementation("io.github.yuroyami:kitepdf-native-renderer:0.8.1")  // or kitepdf-skia-renderer
```

```kotlin
val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
```

See **[Headless rendering](rendering.md)**.

## Guides

| | |
|---|---|
| **[Getting started](getting-started.md)** | Open your first PDF and display it, step by step. |
| **[Compose viewer](compose-viewer.md)** | `KiteDocView`: layouts, zoom, render modes, navigation, export. |
| **[Reading PDFs](reading.md)** | Text, metadata, outlines, annotations, forms, encryption. |
| **[Reading EPUBs](epub.md)** | Reflowable EPUB 2/3: pagination, reader settings, search, typography. |
| **[Creating PDFs](writing.md)** | Build from scratch with the content DSL. |
| **[Editing & redaction](editing.md)** | Fill forms, stamp pages, redact, save. |
| **[Headless rendering](rendering.md)** | Page to PNG / Bitmap without a UI. |
| **[Recipes](recipes.md)** | Copy-paste patterns for common tasks. |
| **[Platform support](platforms.md)** | What runs where, and why. |

## Status

KitePDF is pre-1.0 and actively developed. Reading, text extraction, metadata, outlines, annotations, forms, encrypted documents, the Compose viewer, headless rendering, editing, redaction, signing preparation, PDF creation, and the supported JBIG2/JPEG 2000 profiles all work today. Signature validation, less common form widgets, advanced color management, broader image-codec profiles, and more document handlers are on the way.

If a PDF renders incorrectly, [open an issue](https://github.com/yuroyami/KitePDF/issues) with the file attached. Every rendering fix ships with a regression test.
