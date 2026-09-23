# Platform support

Render, extract, edit, or build PDFs across Android, iOS, JVM, web, and native desktop using shared Kotlin code. The engine declares the targets below; rendering binds to each platform's canvas.

## Target matrix

The seven document artifacts (`kitepdf`, `kitepdf-pdf`, `kitepdf-epub`,
`kitepdf-cbz`, `kitepdf-svg`, `kitepdf-xps` and `kitepdf-core`) share one target set. The
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

`kitepdf-javascript`, which runs the JavaScript inside PDFs, covers the targets
its engine, KiteJS, builds for: Android, JVM, iOS, macOS arm64, Linux x64 and
arm64, Windows, JS and wasmJs. It has no tvOS, watchOS, Android Native or
wasmWasi build.

Intel macOS, tvOS x64 and watchOS x64 are off everywhere. Kotlin 2.3 deprecated
those targets.

!!! note "What CI actually tests"
    Every push and pull request runs the default JVM test suites across the library modules,
    including the mutool differential oracle and the mutation fuzzer, on
    Linux, common tests for core/pdf/epub/cbz/svg/xps on the arm64 iOS simulator
    and macOS, and the core/pdf/epub/cbz/svg/xps/umbrella/net suites on JS/Node.
    Common code is also run through Android host-test variants, while the
    Android, iOS and browser rendering backends are compiled and the macOS
    CoreGraphics backend is tested. Android device rendering, Canvas2D, wasm
    and Linux/Windows native are not executed in CI.

## Verification coverage

The target matrix above describes the Gradle variants the project declares.
It does not mean every variant has been compiled or run. The checked-in CI
workflow currently provides the following coverage on pushes and pull requests:

| Target | Document and script modules | Rendering and viewer |
| --- | --- | --- |
| JVM on Linux | Unit tests, including umbrella and network modules | AWT, Skia and Compose tests; MuPDF differential tests |
| Android | Host tests for core, PDF, EPUB, CBZ, SVG, XPS, umbrella and JavaScript | Android, Skia and Compose compile checks; no emulator or device pixel tests |
| iOS simulator arm64 | Core, PDF, EPUB, CBZ, SVG, XPS and JavaScript common tests | Native, Skia and Compose compile checks; no rendering tests |
| macOS arm64 | Core, PDF, EPUB, CBZ, SVG, XPS, umbrella and JavaScript common tests | CoreGraphics tests; Skia and Compose native targets are not explicitly checked |
| JS on Node | Core, PDF, EPUB, CBZ, SVG, XPS, umbrella, network and JavaScript tests | Browser renderer and viewer compile checks; no browser pixel tests |
| wasmJs and wasmWasi | No CI compile or test job | No CI compile or test job |
| Linux native and Windows native | No CI compile or test job | No CI compile or test job |
| Android Native | No CI compile or test job | No renderer variants |
| Apple device targets, iOS x64, tvOS and watchOS | No explicit CI compile or test job | No explicit CI compile or test job |
| Sample applications | No CI compile or runtime job | No CI compile or runtime job |

The default JVM suites exclude `RenderBenchmarkTest` and
`IncrementalEpubSceneTest`, which require `-PslowTests`. CI does not pass that
flag, so those timing-sensitive suites are not part of its test coverage.

An Android host test executes shared code on the JVM; it does not execute
`android.graphics.Canvas`. A browser compilation does not exercise Canvas2D.
An iOS simulator build does not establish a device build or physical-device
behavior. Shared source compilation can cover code used by additional targets,
but it is not a substitute for compiling and running those target variants.

## What each binding does

### `kitepdf`: the core engine

No platform or native dependencies: only `kotlin-stdlib` and the pure-Kotlin KiteImage codec module. Parse, decrypt, extract text, edit, redact, fill forms, and build PDFs from scratch. No UI or platform binding; just the PDF spec in pure Kotlin. Use this when you need:

- Server-side PDF processing (CLI tools, batch jobs, REST APIs)
- Text extraction and metadata reading
- Form filling or redaction without rendering
- Programmatic PDF generation

Declares every document-artifact target in the table above; verification
coverage varies by target as listed above.

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
    **No Intel-Apple variants:** Compose Multiplatform publishes only arm64 variants for iOS (`iosArm64`, `iosSimulatorArm64`) and macOS (`macosArm64`). Deploy to Apple Silicon or use a different simulator. The document modules also declare `iosX64`; that variant is not checked by CI.

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
    hairlineWidthPx = 1f,  // width of a zero-width stroke in device pixels (the ISO default)
)
```

## Platform support notes

### Shared engine across declared targets

Parsing, editing, writing, and text extraction share Kotlin code across the declared targets. The engine's three `expect` declarations in `kitepdf-core` are `KiteLock`, `currentThreadId()` and `PlatformFlate`, the deflate/inflate hook. Document variants are declared for watchOS, WASI and Android Native without a UI framework dependency; CI does not compile or execute those variants.

### Compose ships Apple Silicon only

Compose Multiplatform publishes only `iosArm64()`, `iosSimulatorArm64()`, and `macosArm64()`. This reflects the Kotlin toolchain's deprecation of Intel x64 Apple variants. If you're on an Intel Mac and need to test on simulator:

- Upgrade to Apple Silicon, or
- Use the core engine + native renderer binding directly (skip `kitepdf-compose-viewer`)

### watchOS is engine-only

watchOS 32-bit `arm64_32` ABI makes `CGFloat` and `size_t` 32-bit, incompatible with this CoreGraphics backend. Neither the native renderer nor Skiko ship watchOS builds. The document modules declare watchOS variants for reading, extraction and editing; CI does not compile or execute them.

### Skiko coverage

Skiko does not publish builds for:

- **Windows (mingwX64):** no Windows-native Skiko runtime
- **watchOS:** no Skiko variant for the `arm64_32` ABI

The document modules declare Windows-native and watchOS variants. Neither
the native renderer nor the Skia renderer provides a variant for those targets.

### Android NDK and WASI

The document modules declare Android Native (`androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64`) and WASI variants for headless and embedded use. CI does not compile or execute them, and no rendering bindings are declared for these targets.

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
    implementation("io.github.yuroyami:kitepdf:0.10.0")

    // Optional: Compose viewer
    implementation("io.github.yuroyami:kitepdf-compose-viewer:0.10.0")

    // Optional: platform-native rasterizer (no Compose)
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.10.0")

    // Optional: Skia rasterizer (headless, one common API)
    implementation("io.github.yuroyami:kitepdf-skia-renderer:0.10.0")
    ```

=== "Android / JVM only"

    Add to your regular `dependencies { }` block:

    ```kotlin
    implementation("io.github.yuroyami:kitepdf:0.10.0")
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.10.0")
    // or
    implementation("io.github.yuroyami:kitepdf-skia-renderer:0.10.0")
    ```

## Related

- [Getting started](getting-started.md): render your first PDF in Compose
- [Reading](reading.md): extract text, metadata, and form fields
- [Editing](editing.md): fill forms, redact content, stamp pages
