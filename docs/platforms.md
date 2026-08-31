# Platform support

Render, extract, edit, or build PDFs across Android, iOS, JVM, web, and native desktop; all from shared Kotlin code. The engine runs everywhere; rendering binds to the right canvas per platform.

## Target matrix

The six document artifacts (`kitepdf`, `kitepdf-pdf`, `kitepdf-epub`,
`kitepdf-cbz`, `kitepdf-svg` and `kitepdf-core`) share one target set. The
three renderers do not. That difference is the usual cause of a first build
that will not resolve.

| Target | document artifacts | `-compose-viewer` | `-native-renderer` | `-skia-renderer` |
| --- | :---: | :---: | :---: | :---: |
| Android | yes (minSdk 21) | yes (minSdk 24) | yes (minSdk 29) | yes (minSdk 21) |
| JVM | yes | yes | yes | yes |
| iOS arm64, simulator arm64 | yes | yes | yes | yes |
| iOS x64 | yes | no | yes | yes |
| macOS arm64 | yes | yes | yes | yes |
| tvOS arm64, simulator arm64 | yes | no | yes | yes |
| watchOS arm32, arm64, device arm64, simulator arm64 | yes | no | no | no |
| Linux x64, arm64 | yes | no | no | yes |
| Windows (mingwX64) | yes | no | no | no |
| Android Native arm32, arm64, x86, x64 | yes | no | no | no |
| JS | yes (browser, Node) | yes (browser) | yes (browser) | yes (browser) |
| wasmJs | yes (browser, Node) | yes (browser) | no | yes (browser) |
| wasmWasi | yes (Node) | no | no | no |

Intel macOS, tvOS x64 and watchOS x64 are off everywhere. Kotlin 2.3 deprecated
those targets.

!!! note "What CI actually tests"
    Every push and pull request runs the full JVM test suite (all modules,
    including the mutool differential oracle and the mutation fuzzer) on
    Linux, common tests for core/pdf/epub/cbz/svg on the arm64 iOS simulator
    and macOS, and the core/pdf/epub/cbz/svg/umbrella/net suites on JS/Node.
    Common code is also run through Android host-test variants, while the
    Android, iOS and browser rendering backends are compiled and the macOS
    CoreGraphics backend is tested. Android device rendering, Canvas2D, wasm
    and Linux/Windows native are not executed in CI.

## What each binding does

### `kitepdf`: the core engine

No platform or native dependencies: only `kotlin-stdlib` and the pure-Kotlin KiteImage codec module. Parse, decrypt, extract text, edit, redact, fill forms, and build PDFs from scratch. No UI or platform binding; just the PDF spec in pure Kotlin. Use this when you need:

- Server-side PDF processing (CLI tools, batch jobs, REST APIs)
- Text extraction and metadata reading
- Form filling or redaction without rendering
- Programmatic PDF generation

Runs on every target in the table above.

```kotlin
val doc = PdfDocument.open(pdfBytes)
println(doc.pages[0].extractText())
```

### `kitepdf-compose-viewer`: Compose Multiplatform viewer

A full interactive `KiteDocView` composable: paginated or continuous scrolling, pinch/zoom, double-tap, panning, and hoisted state for external navigation. It is an ordinary composable, so it lays out and recomposes alongside the rest of your UI. Add it to your Compose projects on Android, iOS, macOS, Desktop (JVM), or the web:

```kotlin
val doc = remember(bytes) { PdfDocument.open(bytes) }
KiteDocView(document = doc, modifier = Modifier.fillMaxSize())
```

Configure it through parameters:

```kotlin
val state = rememberKiteDocViewState(doc)

KiteDocView(
    state = state,
    layout = KiteDocLayout.Paged(Orientation.Horizontal),
    zoomSpec = KiteZoomSpec(maxZoom = 6f),
    renderSpec = KiteRenderSpec.Rasterized(quality = 1f),
    colors = KiteDocViewColors(viewportBackground = Color.DarkGray),
    overlay = { s ->
        KiteNavigationControls(s, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    },
)

// The same state drives widgets outside the viewport:
KitePageIndicator(state)
KiteThumbnailStrip(state)
```

!!! note
    **No Intel-Apple variants:** Compose Multiplatform publishes only arm64 variants for iOS (`iosArm64`, `iosSimulatorArm64`) and macOS (`macosArm64`). Deploy to Apple Silicon or use a different simulator. The core engine compiles for x64 targets too; only the Compose binding is limited.

### `kitepdf-native-renderer`: platform canvas bindings

Draws PDF pages through each platform's own 2D drawing API, with nothing in between:

- **JVM** → `java.awt.Graphics2D`
- **Android** → `android.graphics.Canvas`
- **Apple** (iOS, macOS, tvOS) → CoreGraphics (`CGContext`)
- **JavaScript** → `CanvasRenderingContext2D`

Good for server-side batch rendering, thumbnails, headless screenshots, and existing non-Compose apps (AWT, Swing, UIKit, web). Each call to `encodeToPng()` draws a page via the platform's own graphics stack:

```kotlin
// JVM / Desktop
val png: ByteArray = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
File("preview.png").writeBytes(png)
```

!!! warning
    **No watchOS:** The watchOS ABI (`arm64_32`) makes `CGFloat` and `size_t` 32-bit, which is incompatible with CoreGraphics. The core engine works on watchOS; rendering does not.

### `kitepdf-skia-renderer`: Skia (Skiko) rasterizer

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

**`KiteRenderSpec.Rasterized`** (default)
: Pages are vector-rendered once into a bitmap per size/zoom bucket. Scrolling, panning, and zoomed image display use the cached bitmap with GPU transforms; content streams never re-execute during gestures. Best for performance on slow devices.

```kotlin
renderSpec = KiteRenderSpec.Rasterized(
    quality = 1f,                    // 1 = native display resolution (default)
    maxBitmapLongSide = 4096,        // memory cap on largest page dimensions
    rerasterizeOnZoom = true,        // re-render at settled zoom level for crisp deep zoom
    preserveHairlines = true,        // compensate sub-pixel strokes for raster scale
)
```

**`KiteRenderSpec.Vectorized`**
: Pages are re-drawn live at the slot's layout resolution on every composition. No bitmap overhead; quality is resolution-independent. Best for simple pages, deep zoom crispness, and minimal memory.

```kotlin
renderSpec = KiteRenderSpec.Vectorized(
    hairlineWidthPx = 1f,  // minimum stroke width in device pixels (ISO hairline default)
)
```

## Platform support notes

### The engine runs everywhere

Parsing, editing, writing, and text extraction run the same Kotlin code on every target. The only per-platform branches in the engine are the three `expect` declarations in `kitepdf-core`: `KiteLock`, `currentThreadId()` and `PlatformFlate`, the deflate/inflate hook. PDF operations work on watchOS, WASI, Android NDK, and minimal environments where no UI framework is available.

### Compose ships Apple Silicon only

Compose Multiplatform publishes only `iosArm64()`, `iosSimulatorArm64()`, and `macosArm64()`. This reflects the Kotlin toolchain's deprecation of Intel x64 Apple variants. If you're on an Intel Mac and need to test on simulator:

- Upgrade to Apple Silicon, or
- Use the core engine + native renderer binding directly (skip `kitepdf-compose-viewer`)

### watchOS is engine-only

watchOS 32-bit `arm64_32` ABI makes `CGFloat` and `size_t` 32-bit, incompatible with CoreGraphics. Neither the native renderer nor Skiko ship watchOS builds. The core KitePDF engine compiles fine; you can read, extract, and edit PDFs but cannot render them to screen or image.

### Skiko coverage

Skiko does not publish builds for:

- **Windows (mingwX64):** no Windows-native Skiko runtime
- **watchOS:** no Skiko variant for the `arm64_32` ABI

The core engine and native renderer (on iOS/macOS/tvOS) work fine in these environments; use them instead.

### Android NDK and WASI

The core engine compiles for Android NDK (`androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64`) and WASI for headless and embedded use. No rendering bindings are published for these targets; use the engine directly for PDF operations.

### The three Android minimum API levels

Each artifact declares its own `minSdk`. Your app must satisfy the highest one you add.

| Artifact | `minSdk` | Why |
| --- | :---: | --- |
| `kitepdf`, `-pdf`, `-epub`, `-core` | 21 | The engine uses no newer platform API. |
| `kitepdf-skia-renderer` | 21 | Skiko carries its own rendering stack. On Android it also needs the Compose dev repository, see [Headless rendering](rendering.md#cross-platform-skia-kitepdf-skia-renderer). |
| `kitepdf-compose-viewer` | 24 | The Compose Multiplatform floor. |
| `kitepdf-native-renderer` | 29 | `Paint.setBlendMode`. Below API 29, blend modes would fall back to `SRC_OVER`. |

### Gaps that are not built yet

Two renderer gaps come from KitePDF, not from the toolkit underneath:

- The native renderer's Canvas2D backend would work on `wasmJs`, but that target is not declared.
- The Compose viewer omits tvOS and Linux.

## Installation

=== "Kotlin (KMP)"

    Add to your `kotlin { sourceSets { commonMain.dependencies { } } }`:

    ```kotlin
    // The core engine (always add this)
    implementation("io.github.yuroyami:kitepdf:0.8.2")

    // Optional: Compose viewer
    implementation("io.github.yuroyami:kitepdf-compose-viewer:0.8.2")

    // Optional: platform-native rasterizer (no Compose)
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.8.2")

    // Optional: Skia rasterizer (headless, one common API)
    implementation("io.github.yuroyami:kitepdf-skia-renderer:0.8.2")
    ```

=== "Android / JVM only"

    Add to your regular `dependencies { }` block:

    ```kotlin
    implementation("io.github.yuroyami:kitepdf:0.8.2")
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.8.2")
    // or
    implementation("io.github.yuroyami:kitepdf-skia-renderer:0.8.2")
    ```

## Related

- [Getting started](getting-started.md): render your first PDF in Compose
- [Reading](reading.md): extract text, metadata, and form fields
- [Editing](editing.md): fill forms, redact content, stamp pages
