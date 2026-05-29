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

Almost every other Kotlin / KMP "PDF library" is not actually a PDF library — it's a thin `expect/actual` (or JNI) wrapper around the **platform's** PDF engine: `PdfRenderer` on Android, `PDFKit` on iOS, PDF.js in the browser, PDFBox on the JVM.

KitePDF is the opposite. It's a **standalone PDF engine written entirely in Kotlin** — parser, renderer, editor, writer, crypto, fonts, the whole stack. No platform engine, no JNI, no native binary, nothing to fall back on.

And the Compose binding draws **directly into a Compose `DrawScope`**. No `AndroidView`, no `UIKitView`, no embedded web view. A PDF page is just another composable: it scrolls, animates, zooms and composes with your own UI like anything else you'd put on a `Canvas`.

One codebase. Every target. Bugs are ours to fix.

## Install

KitePDF is published as four Kotlin Multiplatform artifacts on Maven Central. Add the core to `commonMain` and pick whichever extras you need:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // The PDF engine itself. Always.
            implementation("io.github.yuroyami:kitepdf:0.0.1")

            // Optional — Compose Multiplatform binding (@Composable PdfPageView).
            implementation("io.github.yuroyami:kitepdf-compose:0.0.1")

            // Optional — headless platform-native rasterizers (no Compose).
            // Pulls in the right backend for each target: AWT on JVM,
            // android.graphics.Canvas on Android, CoreGraphics on iOS,
            // Canvas2D on JS. You use it from common code; Gradle picks
            // the right one per target.
            implementation("io.github.yuroyami:kitepdf-native-renderer:0.0.1")
        }

        // Optional — JVM-only Skia rasterizer (server-side PNG generation).
        jvmMain.dependencies {
            implementation("io.github.yuroyami:kitepdf-skia:0.0.1")
        }
    }
}
```

If you're on a plain Android / JVM project (not KMP), just add `kitepdf` (+ whichever extras you want) to your normal `dependencies { }` block — the artifacts ship Android / JVM variants alongside the multiplatform ones.

### What each artifact gives you

| Artifact | What's in it | Targets |
|---|---|---|
| `kitepdf` | Open, read, decrypt, extract text, edit, save, build from scratch. Headless. Only depends on `kotlin-stdlib`. | Android · iOS · JVM · JS |
| `kitepdf-compose` | `PdfPageView` + `PdfDocumentPages` — draw a page **directly into Compose**, no native-view wrapping. | Android · iOS · JVM (Desktop) · JS |
| `kitepdf-native-renderer` | `PdfPage → Bitmap / BufferedImage / PNG bytes` using each platform's own 2D canvas. For non-Compose apps and server use. | Android · iOS · JVM · JS |
| `kitepdf-skia` | Same idea as above but via Skia directly (Skiko). Headless. | JVM |

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
| JS (Browser / Node) | ✅ | ✅ | ✅ | ✅ |

Parsing, editing and writing run **the same Kotlin code on every target** — no per-platform branches. Rendering has two paths: the Compose binding draws straight into a Compose `DrawScope` (one composable, everywhere Compose runs); the optional `kitepdf-native-renderer` module gives you a no-Compose path that uses each platform's own 2D canvas (AWT, `android.graphics.Canvas`, CoreGraphics, Canvas2D).

## Status

KitePDF is pre-1.0. Day-to-day PDF features work today — view, text extract, forms, annotations, encrypted documents, edit/save, redact, build from scratch.

Things still on the way: digital signatures, fancier image codecs (JBIG2, JPEG 2000), some less common form widgets, and advanced color management. If you hit a PDF that renders wrong, file an issue with the file attached — the project ships with a [pixel-diff harness against MuPDF](kitepdf-native-renderer/DIFFTEST.md), and every fix lands as a regression test.

## Sample

The `sample/` module is a runnable Compose Multiplatform app that opens a PDF and shows the API in action across Android / iOS / Desktop. Use it as a copy-paste starting point.

## License

Apache 2.0. A few encoding tables are derived from MuPDF and keep their AGPL-3.0 headers in source — see the comments in those specific files.

## Acknowledgements

Architectural reference: [MuPDF](https://mupdf.com/) by Artifex Software. Standard 14 font widths derive from URW++ AFM files. Thanks to everyone who shipped the spec docs that made this possible.
