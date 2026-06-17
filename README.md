# KitePDF

[![Maven Central](https://img.shields.io/badge/Maven%20Central-0.1.0-blue)](https://central.sonatype.com/namespace/io.github.yuroyami)
[![Docs](https://img.shields.io/badge/docs-yuroyami.github.io-1f6feb)](https://yuroyami.github.io/KitePDF/)
![status](https://img.shields.io/badge/status-pre--1.0-orange)
![kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![license](https://img.shields.io/badge/license-Apache--2.0-blue)

**One pure-Kotlin PDF engine for Kotlin Multiplatform. Read, create, edit and render PDFs from `commonMain`, with the exact same code on every target.**

> ## 📖 [Read the documentation →](https://yuroyami.github.io/KitePDF/)
> Getting started, guides, recipes, and the full API reference. **If you read one thing, read this.**

KitePDF is a complete PDF engine written from scratch in Kotlin: parser, renderer, writer, editor, encryption, fonts, the whole stack. You call it from common code and it runs unchanged on Android, iOS, desktop (JVM), the web (JS / Wasm) and Kotlin/Native. There is no platform PDF engine underneath, no `expect`/`actual`, no JNI, no native binary.

```kotlin
// commonMain. Nothing platform-specific. This runs everywhere Kotlin runs.
val doc = PdfDocument.open(bytes)

val pages = doc.pageCount                 // inspect
val text  = doc.pages[0].extractText()    // read

// edit in place, then save
val edited = doc.edit().apply {
    redactRegion(doc.pages[0], Rectangle(72.0, 700.0, 320.0, 720.0))
}.saveRewritten()

// or build a brand-new document
val fresh = PdfBuilder()
    .page { text(StandardFont.Helvetica, 24.0, x = 72.0, y = 720.0, "Hello, world!") }
    .build()
```

## Why KitePDF

Almost every other Kotlin / KMP "PDF library" is not really a PDF library. It is a thin `expect`/`actual` (or JNI) wrapper around the platform's own engine: `PdfRenderer` on Android, `PDFKit` on iOS, PDF.js in the browser, PDFBox on the JVM. You inherit four different engines, four sets of bugs, and four feature sets that never quite line up.

KitePDF is the opposite. It is a **single standalone engine, 100% common Kotlin**, with zero `expect`/`actual` in the core. Write your PDF code once in `commonMain` and it behaves identically on every target, because it is literally the same code. When something renders wrong, it is one bug in one place, and it is ours to fix.

Rendering to a screen is the only thing that needs a platform. KitePDF keeps that cleanly separate (see [Putting pixels on screen](#putting-pixels-on-screen)), so the engine stays pure and portable.

## Install

The engine is one dependency. Add it to `commonMain` and you have everything except drawing to a screen:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitepdf:0.1.0")
        }
    }
}
```

That is it for reading, writing, editing, redacting, encryption, text extraction and building PDFs, on every Kotlin target. Its only dependency is `kotlin-stdlib`.

Not using Kotlin Multiplatform? The same artifact works in a plain Android or JVM project. Add `io.github.yuroyami:kitepdf:0.1.0` to your normal `dependencies { }` block.

Rendering bindings are opt-in and covered under [Putting pixels on screen](#putting-pixels-on-screen).

## What it does

Everything below is pure common code from the `kitepdf` artifact. Each item links to its guide.

### Read

- **[Text extraction](https://yuroyami.github.io/KitePDF/reading/#text-extraction)**: plain text, or structured layout (blocks, lines, words with positions).
- **[Metadata](https://yuroyami.github.io/KitePDF/reading/#document-metadata)**: the Info dictionary and XMP.
- **[Outline / bookmarks](https://yuroyami.github.io/KitePDF/reading/#outline-bookmarks)**, **[annotations](https://yuroyami.github.io/KitePDF/reading/#annotations)**, and **[form fields](https://yuroyami.github.io/KitePDF/reading/#form-fields)**.
- **[Encrypted documents](https://yuroyami.github.io/KitePDF/reading/#encrypted-pdfs)**: open password-protected PDFs (RC4 and AES) and inspect permissions.
- Page labels, optional-content layers, attachments, article threads, viewer preferences, and tagged-PDF structure.

### Create and edit

- **[Build from scratch](https://yuroyami.github.io/KitePDF/writing/)**: a content DSL for text, vector graphics, images and clipping, with the standard 14 fonts.
- **[Fill forms](https://yuroyami.github.io/KitePDF/editing/#fill-form-fields)**, **[stamp and watermark](https://yuroyami.github.io/KitePDF/editing/#stamp-and-watermark-pages)** pages.
- **[True redaction](https://yuroyami.github.io/KitePDF/editing/#redaction)**: the underlying text and images are removed from the file, not hidden behind a black box.
- Save incrementally (append-only, signature-friendly) or rewrite the document fresh.

### Render

- **[Compose Multiplatform viewer](https://yuroyami.github.io/KitePDF/compose-viewer/)**: `PdfView`, drawn straight into a Compose `DrawScope`.
- **[Headless rendering](https://yuroyami.github.io/KitePDF/rendering/)**: page to PNG / Bitmap with no UI, for servers, CI and thumbnails.

A few quick tastes:

```kotlin
// Read text
val text = doc.pages[0].extractText()

// Open a password-protected PDF
val doc = PdfDocument.open(bytes, password = "secret".encodeToByteArray())
require(doc.isAuthenticated)

// Fill a form field and save (append-only)
val out = doc.edit()
    .apply { setTextFieldValue(doc.formField("ApplicantName")!!, "Jane Doe") }
    .saveIncremental()

// Watermark every page
val stamped = doc.edit().apply {
    doc.pages.forEach { page ->
        stampPage(page) {
            setFillRgb(0.8, 0.1, 0.1)
            text(StandardFont.HelveticaBold, 48.0, x = 120.0, y = 400.0, "DRAFT")
        }
    }
}.saveIncremental()
```

See the **[full documentation](https://yuroyami.github.io/KitePDF/)** for the complete API and worked examples.

## Putting pixels on screen

The engine is headless. Showing a PDF is the one job that needs a platform, so it lives in separate, optional artifacts. Pick the one that matches how you draw.

### Compose Multiplatform: `kitepdf-compose`

A PDF page is just another composable. `PdfView` draws into a Compose `DrawScope`, so it scrolls, zooms and composes with your UI like anything else on a `Canvas`. No `AndroidView`, no `UIKitView`, no embedded web view.

```kotlin
// commonMain of a Compose Multiplatform app
val state = rememberPdfViewState(doc)

PdfView(
    state = state,
    layout = PdfLayout.Paged(Orientation.Horizontal),     // or Continuous / SinglePage
    zoomSpec = PdfZoomSpec(maxZoom = 6f),                  // pinch, double-tap, pan
    renderSpec = PdfRenderSpec.Rasterized(),              // or Vectorized()
    overlay = { PdfNavigationControls(it, Modifier.align(Alignment.BottomCenter)) },
)

// The same state drives widgets anywhere in your UI
PdfPageIndicator(state)        // "3 / 12"
PdfThumbnailStrip(state)       // tappable thumbnails
```

See **[the viewer guide](https://yuroyami.github.io/KitePDF/compose-viewer/)** for layouts, zoom, render modes, navigation and export.

### Headless rasterizers: `kitepdf-native-renderer` and `kitepdf-skia`

For servers, CI, thumbnails, or non-Compose UIs, render a page straight to image bytes.

```kotlin
// kitepdf-native-renderer, JVM (AWT). Also CoreGraphics (Apple), android.graphics, Canvas2D (JS).
val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)

// kitepdf-skia, one common API via Skiko (JVM, Android, Apple, Linux, web).
val png = PdfPageRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
```

See **[the rendering guide](https://yuroyami.github.io/KitePDF/rendering/)** to choose between them.

## Platform support

The engine runs on **every target Kotlin supports**, with no per-platform code. Only the rendering bindings are limited by what their underlying toolkit ships.

| Target | Engine (`kitepdf`) | Compose (`-compose`) | Native renderer (`-native-renderer`) | Skia (`-skia`) |
|---|:---:|:---:|:---:|:---:|
| Android | ✓ | ✓ | ✓ | ✓ |
| iOS (arm64 / simulator) | ✓ | ✓ | ✓ | ✓ |
| JVM (Desktop / Server) | ✓ | ✓ | ✓ | ✓ |
| macOS (Apple Silicon) | ✓ | ✓ | ✓ | ✓ |
| JS (Browser / Node) | ✓ | ✓ | ✓ | ✓ |
| wasmJs | ✓ | ✓ | – | ✓ |
| tvOS | ✓ | – | ✓ | ✓ |
| watchOS | ✓ | – | – | – |
| Linux (x64 / arm64) | ✓ | – | – | ✓ |
| Windows (mingwX64) | ✓ | – | – | – |
| Android Native, WASI | ✓ | – | – | – |

Full details and the reasons behind each gap are in the **[platform support guide](https://yuroyami.github.io/KitePDF/platforms/)**.

## Status

KitePDF is pre-1.0 and actively developed.

**Working today:** reading and text extraction, metadata, outlines, annotations, form fields, encrypted documents, the Compose viewer (continuous / paged scroll, pinch zoom), headless rendering, editing and saving, true redaction, and building PDFs from scratch.

**On the way:** digital signatures, the JBIG2 and JPEG 2000 image codecs, less common form widgets, and advanced colour management.

If a PDF renders incorrectly, please open an issue with the file attached. The project ships a [pixel-diff harness against MuPDF](kitepdf-native-renderer/DIFFTEST.md), and every fix lands as a regression test.

## Sample app

The `sample/` module is a runnable Compose Multiplatform app that opens a PDF and exercises the API across Android, iOS and Desktop. Use it as a copy-paste starting point.

## License

Apache License 2.0. A few encoding tables and font data are derived from MuPDF and keep their AGPL-3.0 headers in those specific source files; see the comments there.

## Acknowledgements

Architectural reference: [MuPDF](https://mupdf.com/) by Artifex Software. Standard 14 font metrics derive from URW++ AFM files. Thanks to everyone who published the PDF specification and the tools that made this possible.
