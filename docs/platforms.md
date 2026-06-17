# Platform support

Render, extract, edit, or build PDFs across Android, iOS, JVM, web, and native desktop; all from shared Kotlin code. The engine runs everywhere; rendering binds to the right canvas per platform.

## Target matrix

KitePDF's core engine (read, parse, edit, write, decrypt) is 100% common Kotlin and compiles for every target the Kotlin compiler supports. Rendering depends on which binding artifact ships for your platform:

| Platform | Engine (`kitepdf`) | Compose (`kitepdf-compose`) | Native Renderer (`kitepdf-native-renderer`) | Skia (`kitepdf-skia`) |
|---|:-:|:-:|:-:|:-:|
| **Android** (API 21+) | ✓ | ✓ | ✓ | ✓ |
| **iOS arm64** | ✓ | ✓ | ✓ | ✓ |
| **iOS Simulator** (arm64) | ✓ | ✓ | ✓ | ✓ |
| **JVM / Desktop** | ✓ | ✓ | ✓ | ✓ |
| **JavaScript** (Browser / Node) | ✓ | ✓ (browser) | ✓ (browser) | ✓ (browser) |
| **wasmJs** (Browser / Node) | ✓ | ✓ (browser) | – | ✓ (browser) |
| **macOS** (Apple Silicon) | ✓ | ✓ | ✓ | ✓ |
| **tvOS** | ✓ | – | ✓ | ✓ |
| **watchOS** | ✓ | – | – | – |
| **Linux** (x64, arm64) | ✓ | – | – | ✓ |
| **Windows** (mingwX64) | ✓ | – | – | – |
| **Android NDK** (arm32/64, x86/64) | ✓ | – | – | – |
| **WASI** | ✓ | – | – | – |

## What each binding does

### `kitepdf`: the core engine

No external dependencies beyond `kotlin-stdlib`. Parse, decrypt, extract text, edit, redact, fill forms, and build PDFs from scratch. No UI, no platform binding, no rendering; just the PDF spec in pure Kotlin. Use this when you need:

- Server-side PDF processing (CLI tools, batch jobs, REST APIs)
- Text extraction and metadata reading
- Form filling or redaction without rendering
- Programmatic PDF generation

Runs on every target in the table above.

```kotlin
val doc = PdfDocument.open(pdfBytes)
println(doc.pages[0].extractText())
```

### `kitepdf-compose`: Compose Multiplatform viewer

A full interactive `PdfView` composable: paginated or continuous scrolling, pinch/zoom, double-tap, panning, and hoisted state for external navigation. Draw PDFs as first-class UI elements alongside your app's own composables. Add it to your Compose projects on Android, iOS, macOS, Desktop (JVM), or the web:

```kotlin
val doc = remember(bytes) { PdfDocument.open(bytes) }
PdfView(document = doc, modifier = Modifier.fillMaxSize())
```

The composable supports rich configuration via parameters:

```kotlin
val state = rememberPdfViewState(doc)

PdfView(
    state = state,
    layout = PdfLayout.Paged(Orientation.Horizontal),
    zoomSpec = PdfZoomSpec(maxZoom = 6f),
    renderSpec = PdfRenderSpec.Rasterized(quality = 1f),
    colors = PdfViewColors(viewportBackground = Color.DarkGray),
    overlay = { s ->
        PdfNavigationControls(s, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    },
)

// The same state drives widgets outside the viewport:
PdfPageIndicator(state)
PdfThumbnailStrip(state)
```

!!! note
    **No Intel-Apple variants:** Compose Multiplatform publishes only arm64 variants for iOS (`iosArm64`, `iosSimulatorArm64`) and macOS (`macosArm64`). Deploy to Apple Silicon or use a different simulator. The core engine compiles for x64 targets too; only the Compose binding is limited.

### `kitepdf-native-renderer`: platform canvas bindings

Map PDF pages to each platform's native 2D drawing API with zero middleware:

- **JVM** → `java.awt.Graphics2D`
- **Android** → `android.graphics.Canvas`
- **Apple** (iOS, macOS, tvOS) → CoreGraphics (`CGContext`)
- **JavaScript** → `CanvasRenderingContext2D`

Perfect for server-side batch rendering, thumbnails, headless screenshots, or existing non-Compose apps (AWT, Swing, UIKit, web). Each call to `encodeToPng()` draws a page via the platform's own graphics stack:

```kotlin
// JVM / Desktop
val png: ByteArray = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
File("preview.png").writeBytes(png)
```

!!! warning
    **No watchOS:** The watchOS ABI (`arm64_32`) makes `CGFloat` and `size_t` 32-bit, which is incompatible with CoreGraphics. The core engine works on watchOS; rendering does not.

### `kitepdf-skia`: Skia (Skiko) rasterizer

Render PDFs through Skia with one common headless API everywhere Skiko ships; no Compose needed. Use for:

- Batch server jobs with consistent cross-platform output
- Web apps (CanvasKit backend)
- Desktop / mobile apps that don't use Compose

```kotlin
// Common code, every platform Skiko runs on:
val png: ByteArray = PdfPageRasterizer.encodeToPng(doc.pages[0])
```

## Render specifications

The Compose viewer's `renderSpec` parameter accepts a sealed interface with two variants:

**`PdfRenderSpec.Rasterized`** (default)
: Pages are vector-rendered once into a bitmap per size/zoom bucket. Scrolling, panning, and zoomed image display use the cached bitmap with GPU transforms; content streams never re-execute during gestures. Best for performance on slow devices.

```kotlin
renderSpec = PdfRenderSpec.Rasterized(
    quality = 1f,                    // 1 = native display resolution (default)
    maxBitmapLongSide = 4096,        // memory cap on largest page dimensions
    rerasterizeOnZoom = true,        // re-render at settled zoom level for crisp deep zoom
    preserveHairlines = true,        // compensate sub-pixel strokes for raster scale
)
```

**`PdfRenderSpec.Vectorized`**
: Pages are re-drawn live at the slot's layout resolution on every composition. No bitmap overhead; quality is resolution-independent. Best for simple pages, deep zoom crispness, and minimal memory.

```kotlin
renderSpec = PdfRenderSpec.Vectorized(
    hairlineWidthPx = 1f,  // minimum stroke width in device pixels (ISO hairline default)
)
```

## Platform support notes

### The engine runs everywhere

Parsing, editing, writing, and text extraction use the same Kotlin code on every target with no `expect`/`actual` branches. PDF operations work on watchOS, WASI, Android NDK, and minimal environments where no UI framework is available.

### Compose ships Apple Silicon only

Compose Multiplatform publishes only `iosArm64()`, `iosSimulatorArm64()`, and `macosArm64()`. This reflects the Kotlin toolchain's deprecation of Intel x64 Apple variants. If you're on an Intel Mac and need to test on simulator:

- Upgrade to Apple Silicon, or
- Use the core engine + native renderer binding directly (skip `kitepdf-compose`)

### watchOS is engine-only

watchOS 32-bit `arm64_32` ABI makes `CGFloat` and `size_t` 32-bit, incompatible with CoreGraphics. Neither the native renderer nor Skiko ship watchOS builds. The core KitePDF engine compiles fine; you can read, extract, and edit PDFs but cannot render them to screen or image.

### Skiko coverage

Skiko does not publish builds for:

- **Windows (mingwX64):** no Windows-native Skiko runtime
- **watchOS:** no Skiko variant for the `arm64_32` ABI

The core engine and native renderer (on iOS/macOS/tvOS) work fine in these environments; use them instead.

### Android NDK and WASI

The core engine compiles for Android NDK (`androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64`) and WASI for headless and embedded use. No rendering bindings are published for these targets; use the engine directly for PDF operations.

## Installation

=== "Kotlin (KMP)"

    Add to your `kotlin { sourceSets { commonMain.dependencies { } } }`:

    ```kotlin
    // The core engine (always add this)
    implementation("io.github.yuroyami:kitepdf:0.1.0")

    // Optional: Compose viewer
    implementation("io.github.yuroyami:kitepdf-compose:0.1.0")

    // Optional: platform-native rasterizer (no Compose)
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.1.0")

    // Optional: Skia rasterizer (headless, one common API)
    implementation("io.github.yuroyami:kitepdf-skia:0.1.0")
    ```

=== "Android / JVM only"

    Add to your regular `dependencies { }` block:

    ```kotlin
    implementation("io.github.yuroyami:kitepdf:0.1.0")
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.1.0")
    // or
    implementation("io.github.yuroyami:kitepdf-skia:0.1.0")
    ```

## Related

- [Getting started](getting-started.md): render your first PDF in Compose
- [Reading](reading.md): extract text, metadata, and form fields
- [Editing](editing.md): fill forms, redact content, stamp pages
