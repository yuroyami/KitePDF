# KitePDF

![status](https://img.shields.io/badge/status-experimental-orange)
![kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)
![platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20JVM%20%7C%20JS-7F52FF)
![license](https://img.shields.io/badge/license-Apache--2.0-blue)

**Read, view, edit and create PDFs from Kotlin Multiplatform — one API, every platform.**

Drop a PDF into your Android, iOS, desktop or web app, render it in Compose, extract its text, fill a form, redact something sensitive, or build one from scratch. All from common code.

```kotlin
val doc = PdfDocument.open(pdfBytes)

@Composable
fun MyScreen() {
    PdfPageView(page = doc.pages[0], modifier = Modifier.fillMaxWidth())
}
```

## Why

If you've tried to add PDF support to a Kotlin Multiplatform app, you've probably hit one of these:

- **Android only:** `PdfRenderer` is great… until you try to ship the same code to iOS.
- **iOS only:** `PDFKit` is great… until you try to ship the same code to Android.
- **Per-platform glue:** you end up writing four `expect/actual` wrappers around four totally different engines, with four totally different bugs.
- **Heavy native deps:** dragging MuPDF or PDFBox into a multiplatform app is painful, especially on iOS and JS.

KitePDF is written in pure Kotlin so you write your PDF code once and it just works on every target. No NDK, no CocoaPods, no `java.awt`, no PDF.js bridge.

## Install

```kotlin
dependencies {
    // Core: open, read, edit, save. Headless. kotlin-stdlib only.
    implementation("com.yuroyami.kitepdf:kitepdf:0.0.1")

    // Compose Multiplatform binding — adds @Composable PdfPageView.
    implementation("com.yuroyami.kitepdf:kitepdf-compose:0.0.1")
}
```

Pick the modules you actually need:

| Module | What you get | When to add it |
|---|---|---|
| `kitepdf` | The reader / editor / writer. Returns text, page geometry, byte arrays. | Always. |
| `kitepdf-compose` | `PdfPageView` + `PdfDocumentPages` composables. | You're using Compose Multiplatform. |
| `kitepdf-native-renderer` | Headless renderers using the host's native canvas (AWT / Android Canvas / CoreGraphics / Canvas2D). | You need a `Bitmap` / `BufferedImage` / PNG without Compose. |
| `kitepdf-skia` | Headless Skia rasterizer for JVM. | Server-side PNG generation. |

## What you can do

### View a PDF in Compose

```kotlin
val doc = remember(bytes) { PdfDocument.open(bytes) }

// One page:
PdfPageView(page = doc.pages[0], modifier = Modifier.fillMaxWidth())

// Or the whole document, scrollable:
PdfDocumentPages(document = doc, modifier = Modifier.fillMaxSize())
```

### Open a password-protected PDF

```kotlin
val doc = PdfDocument.open(bytes, password = "secret".encodeToByteArray())
if (!doc.isAuthenticated) error("Wrong password")
```

### Extract text

```kotlin
val firstPageText: String = doc.pages[0].extractText()
```

### Render a page to a PNG (no UI needed)

Great for thumbnails, server-side previews, CI screenshots:

```kotlin
val png: ByteArray = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
File("preview.png").writeBytes(png)
```

### Fill a form, stamp a watermark, save

```kotlin
val editor = doc.edit()

editor.setTextFieldValue(doc.formField("ApplicantName")!!, "Jane Doe")

editor.stampPage(doc.pages[0]) {
    setFillRgb(0.8, 0.1, 0.1)
    text(StandardFont.HelveticaBold, 48.0, x = 120.0, y = 400.0, "DRAFT")
}

val out: ByteArray = editor.saveIncremental()
```

### Redact something for real

Not "draw a black box on top" — the underlying text is removed from the file so it can't be copy-pasted out or recovered with a hex editor.

```kotlin
editor.redactRegion(doc.pages[0], Rectangle(72.0, 700.0, 320.0, 720.0))
val safe: ByteArray = editor.saveRewritten()
```

### Build a PDF from scratch

```kotlin
val bytes = PdfBuilder()
    .setInfo(title = "Hello", author = "KitePDF")
    .page {
        text(StandardFont.HelveticaBold, size = 24.0, x = 72.0, y = 720.0, "Hello, world!")
        setFillRgb(0.9, 0.95, 1.0); rectangle(72.0, 600.0, 200.0, 80.0); fill()
    }
    .build()
```

## Platform support

| Target | Read | Render | Edit | Write |
|---|:-:|:-:|:-:|:-:|
| Android | ✅ | ✅ | ✅ | ✅ |
| iOS (arm64 / sim) | ✅ | ✅ | ✅ | ✅ |
| JVM (Desktop / Server) | ✅ | ✅ | ✅ | ✅ |
| JS (Browser / Node) | ✅ | ✅ (Canvas2D / Compose) | ✅ | ✅ |

The core (parsing, editing, writing) is **the same Kotlin code on every target**. Rendering uses each platform's native 2D canvas under the hood (Compose / Android Canvas / CoreGraphics / Skia / Canvas2D), so it looks native and stays fast.

## Status

KitePDF is pre-1.0. Day-to-day PDF features work today — view, text extract, forms, annotations, encrypted documents, edit/save, redact, build from scratch.

Things still on the way: digital signatures, fancier image codecs (JBIG2, JPEG 2000), some less common form widgets, and advanced color management. If you hit a PDF that renders wrong, file an issue with the file attached — the project ships with a [pixel-diff harness against MuPDF](kitepdf-native-renderer/DIFFTEST.md), and every fix lands as a regression test.

## Sample

The `sample/` module is a runnable Compose Multiplatform app that opens a PDF and shows the API in action across Android / iOS / Desktop. Use it as a copy-paste starting point.

## License

Apache 2.0. A few encoding tables are derived from MuPDF and keep their AGPL-3.0 headers in source — see the comments in those specific files.

## Acknowledgements

Architectural reference: [MuPDF](https://mupdf.com/) by Artifex Software. Standard 14 font widths derive from URW++ AFM files. Thanks to everyone who shipped the spec docs that made this possible.
