# Getting Started

Learn how to open a PDF, display it in the UI, and extract its content with KitePDF: a pure-Kotlin multiplatform PDF engine.

## Step 1: Add the dependency

KitePDF is published to Maven Central as four artifacts. Start with the core headless engine; add the Compose viewer and renderers only when you need them.

=== "Kotlin (KMP)"

    ```gradle
    dependencies {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitepdf:0.2.0")
        }
    }
    ```

=== "Android / JVM"

    ```gradle
    dependencies {
        implementation("io.github.yuroyami:kitepdf:0.2.0")
    }
    ```

The core `kitepdf` artifact has one dependency: `kotlin-stdlib`. It works on every Kotlin target (JVM, Android, Native, JS, wasmJs).

## Step 2: Open your first PDF

Get PDF bytes from a file, network, or resources; the method depends on your platform.

**JVM / Desktop:**
```kotlin
import java.io.File
import io.github.yuroyami.kitepdf.KitePDF

val bytes = File("sample.pdf").readBytes()
val doc = KitePDF.open(bytes)

println("${doc.pageCount} pages")
println("PDF version ${doc.version}")
```

**Android:**
```kotlin
import io.github.yuroyami.kitepdf.KitePDF

// From resources or assets
val bytes = context.resources.openRawResource(R.raw.sample).readBytes()
val doc = KitePDF.open(bytes)

// Or from a file
val bytes = File(context.cacheDir, "sample.pdf").readBytes()
val doc = KitePDF.open(bytes)
```

**Password-protected PDFs:**

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument

val doc = PdfDocument.open(bytes, "secret".encodeToByteArray())
```

## Step 3: Show it on screen in Compose

The `kitepdf-compose-viewer` artifact provides `PdfView`: a Compose-Multiplatform viewer supporting pinch zoom, paging, continuous scroll, and more.

Add the dependency:

```gradle
dependencies {
    commonMain.dependencies {
        implementation("io.github.yuroyami:kitepdf-compose-viewer:0.2.0")
    }
}
```

**Display the whole document** (vertical continuous scroll):

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.compose.PdfView

@Composable
fun PdfViewer(doc: PdfDocument) {
    PdfView(document = doc, modifier = Modifier.fillMaxSize())
}
```

**Show a single page** with fit-to-viewport letterboxing:

```kotlin
@Composable
fun SinglePageView(doc: PdfDocument) {
    PdfView(
        document = doc,
        page = 0,  // zero-based index
        modifier = Modifier.fillMaxSize()
    )
}
```

**Full control** over layout, zoom, and rendering:

```kotlin
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.compose.*

@Composable
fun AdvancedViewer(doc: PdfDocument) {
    val state = rememberPdfViewState(doc)
    PdfView(
        state = state,
        modifier = Modifier.fillMaxSize(),
        layout = PdfLayout.Paged(Orientation.Horizontal),
        zoomSpec = PdfZoomSpec(minZoom = 0.5f, maxZoom = 6f),
        renderSpec = PdfRenderSpec.Rasterized(
            quality = 1f,
            maxBitmapLongSide = 4096,
            rerasterizeOnZoom = true,
            preserveHairlines = true
        ),
        colors = PdfViewColors(
            pageBackground = Color.White,
            viewportBackground = Color(0xFF1E1E1E)
        ),
        overlay = { state ->
            PdfNavigationControls(state, Modifier.align(Alignment.BottomCenter))
        }
    )
}
```

!!! tip

    Use `PdfRenderSpec.Rasterized()` (the default) for scrolling performance and memory efficiency. Switch to `PdfRenderSpec.Vectorized()` for resolution-independent, bitmap-free rendering: best for simple pages or when memory is tight.

## Step 4: Read text out of it

Extract text from any page without displaying it:

```kotlin
val page = doc.pages[0]
val text = page.extractText()
println(text)
```

For geometry-aware extraction (to build selection rectangles or search highlights), use structured text:

```kotlin
val structured = page.structuredText
// structured.blocks → lines → spans, each with bounds
for (block in structured.blocks) {
    println("Block: ${block.bounds}")
    for (line in block.lines) {
        println("  Line: ${line.text}")
        for (span in line.spans) {
            println("    Span: ${span.text} at ${span.bounds}")
        }
    }
}
```

## Step 5: Render a page to PNG

Export a page as a PNG (or JPEG) without showing the UI. The `kitepdf-native-renderer` artifact provides headless rasterization using the JDK's built-in AWT + ImageIO.

Add the dependency:

```gradle
dependencies {
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.2.0")
}
```

Render and encode:

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File

val page = doc.pages[0]

// Render at 2× scale (e.g. for print or high-DPI screenshots)
val pngBytes = AwtPdfRasterizer.encodeToPng(page, scale = 2.0)
File("page-0.png").writeBytes(pngBytes)

// Or JPEG
val jpegBytes = AwtPdfRasterizer.encodeToJpeg(page, scale = 1.0)
File("page-0.jpg").writeBytes(jpegBytes)
```

You can also grab the `BufferedImage` directly:

```kotlin
val img = AwtPdfRasterizer.renderToImage(page, scale = 1.5)
// Use img however you like...
```

!!! note

    The native-renderer artifact works on JVM, Android, and macOS. For other platforms (JS, wasm, iOS), use the Skia renderer (`kitepdf-skia-renderer`) or render through Compose (`kitepdf-compose-viewer`).

## Where to next?

- **[Compose Viewer](compose-viewer.md)**: zoom gestures, paging modes, navigation overlays, and thumbnails.
- **[Editing & Writing](writing.md)**: modify PDFs programmatically (form fields, metadata, incremental updates).
- **[Recipes](recipes.md)**: common patterns: search text, extract images, parse annotations, batch processing.
