# Headless rendering (page → image)

Convert PDF pages to raster images (PNG, JPEG) without a UI. Useful for thumbnails, server previews, CI screenshots, or embedding rendered previews in desktop apps.

## Which tool to use?

KitePDF offers three rendering paths, each optimized for different scenarios:

| Use case | Artifact | Best for | Platform |
|----------|----------|----------|----------|
| **JVM / Server** | `kitepdf-native-renderer` (AWT) | Minimal dependencies; built-in to JDK | JVM, CI pipelines |
| **Apple platforms** | `kitepdf-native-renderer` (CoreGraphics) | Native system framework; no binary deps | iOS, macOS, tvOS |
| **Android** | `kitepdf-native-renderer` | Native Bitmap API | Android |
| **Cross-platform** | `kitepdf-skia` | One common API across JVM/Apple/Android/Linux | All except JS/wasmJs |
| **Skia on web** | `kitepdf-skia` (Skiko over WASM) | Best fidelity on JS; includes images | JS, wasmJs |
| **Canvas2D on web** | `kitepdf-native-renderer` (Canvas2D) | Minimal JS bundle; native acceleration | JS (lightweight viewers) |
| **Compose viewers** | `kitepdf-compose` + `ImageBitmap.encodeToPng()` | Export rendered page from UI widget | All Compose platforms |

## JVM / Server: AWT + ImageIO

The AWT rasterizer is zero-dependency; it ships with the JDK and needs no native binaries.

**Install:**

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.1.0")
}
```

**Basic usage:**

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.PdfDocument
import java.io.File

// Render page 0 to a PNG file on disk
val pdf = PdfDocument.open(File("sample.pdf"))
val page = pdf.getPage(0)

// Write PNG bytes directly to disk
val pngBytes = AwtPdfRasterizer.encodeToPng(page, scale = 2.0)
File("preview.png").writeBytes(pngBytes)
```

**Parameters:**

- **`page: PdfPage`** : The page to render.
- **`scale: Double`** (default: `1.0`) : Multiplier on page dimensions. Use `2.0` for "2× density" / retina thumbnails; `0.5` to shrink.
- **`background: Color`** (default: `Color.WHITE`) : Fill color behind rendered content. Pass `Color(255, 255, 255, 0)` for transparency.

**API:**

- **`renderToImage(page, scale, background): BufferedImage`** : Returns an AWT `BufferedImage` (TYPE_INT_ARGB). Use this if you need to post-process, draw into another canvas, or store in a custom format.
- **`encodeToPng(page, scale, background): ByteArray`** : Returns PNG bytes ready to write to disk or send over HTTP.
- **`encodeToJpeg(page, scale, background): ByteArray`** : Returns JPEG bytes (TYPE_INT_RGB). JPEG doesn't support alpha; opaque background is used.

!!! tip "Server / CI usage"
    For CI jobs rendering many pages, parallelize on a thread pool to saturate CPU:
    ```kotlin
    val pngBytes = withContext(Dispatchers.Default) {
        AwtPdfRasterizer.encodeToPng(page, scale = 1.5)
    }
    ```

## Apple platforms: CoreGraphics

On iOS, macOS, and tvOS, use `ApplePdfRasterizer` to render via native CoreGraphics + ImageIO.

**Install:**

```kotlin
// In your ios/macOS sourceSet
dependencies {
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.1.0")
}
```

**Usage:**

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.ApplePdfRasterizer
import io.github.yuroyami.kitepdf.PdfDocument
import platform.Foundation.NSFileManager

val pdf = PdfDocument.open(fileUrl)
val page = pdf.getPage(0)

// Render to PNG NSData
val pngData = ApplePdfRasterizer.renderToPngData(
    page,
    scale = 2.0,
    backgroundR = 1.0,  // RGBA, 0.0–1.0
    backgroundG = 1.0,
    backgroundB = 1.0,
    backgroundA = 1.0,
) ?: return  // null if CoreGraphics / encoder fails (extremely rare)

// Write to Documents folder
val docUrl = NSFileManager.defaultManager.URLsForDirectory(4u, NSUserDomainMask).first()
val fileUrl = docUrl.URLByAppendingPathComponent("preview.png")
pngData.writeToURL(fileUrl, atomically = true)
```

**Parameters:**

- **`page: PdfPage`** : The page to render.
- **`scale: Double`** (default: `1.0`) : Multiplier on page dimensions (pt).
- **`backgroundR/G/B/A: Double`** (default: all `1.0`) : RGBA fill color, each in `[0.0, 1.0]`. Pass `A = 0.0` for a transparent background.

**Returns:**

- **`NSData?`** : PNG bytes, or `null` if CoreGraphics allocation or PNG encoding fails (extremely rare; usually indicates OS-level resource exhaustion).

## Android: Bitmap API

On Android, `AndroidPdfBitmapRenderer` returns an ARGB_8888 `Bitmap` for use with `Canvas`, `ImageView`, or disk caching.

**Install:**

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.1.0")
}
```

**Usage:**

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AndroidPdfBitmapRenderer
import io.github.yuroyami.kitepdf.PdfDocument
import android.graphics.Color
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Render off the main thread
LaunchedEffect(pdf) {
    val bitmap = withContext(Dispatchers.Default) {
        AndroidPdfBitmapRenderer.renderToBitmap(
            page,
            scale = 1.5,
            background = Color.WHITE
        )
    }
    
    // Now draw or cache the bitmap
    val imageBitmap = bitmap.asImageBitmap()
    Image(imageBitmap, contentDescription = "Page thumbnail")
}
```

**Parameters:**

- **`page: PdfPage`** : The page to render.
- **`scale: Double`** (default: `1.0`) : Multiplier on page dimensions.
- **`background: Int`** (default: `Color.WHITE`) : Android color int (0xAARRGGBB).

**Returns:**

- **`Bitmap`** : ARGB_8888 bitmap. You own the memory; the bitmap does not auto-recycle. Call `recycle()` when done with large batches.

!!! warning "Bitmap allocation"
    Large pages at high scale can exhaust memory. Keep an eye on bitmap dimensions: `(page.width * scale).toInt() x (page.height * scale).toInt()` pixels.

## Cross-platform: Skia (kitepdf-skia)

For a single API across JVM, Android, Apple, and Linux, use `PdfPageRasterizer` from `kitepdf-skia`.

**Install:**

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-skia:0.1.0")
}
```

**Usage:**

```kotlin
import io.github.yuroyami.kitepdf.skia.PdfPageRasterizer
import io.github.yuroyami.kitepdf.PdfDocument
import org.jetbrains.skia.Color
import java.io.File

val pdf = PdfDocument.open(File("sample.pdf"))
val page = pdf.getPage(0)

// Render to Skia Image
val image = PdfPageRasterizer.renderToImage(
    page,
    scale = 2.0,
    background = Color.WHITE
)

// Encode to PNG bytes and write to disk
val pngBytes = PdfPageRasterizer.encodeToPng(page, scale = 2.0)
File("preview.png").writeBytes(pngBytes)

// Clean up the image if you only needed bytes
image.close()
```

**Parameters:**

- **`page: PdfPage`** : The page to render.
- **`scale: Double`** (default: `1.0`) : Multiplier on page dimensions.
- **`background: Int`** (default: `Color.WHITE`) : Skia color int (0xAARRGGBB).

**API:**

- **`renderToImage(page, scale, background): Image`** : Returns a Skia `Image` (holds off-heap memory; call `close()` when done).
- **`encodeToPng(page, scale, background): ByteArray`** : Convenience: render and encode in one call. Handles cleanup internally.

!!! note "Off-heap memory"
    Skia images are backed by native memory. Always call `image.close()` when you're done, or use `encodeToPng()` which handles cleanup automatically.

## Web: Canvas2D (kitepdf-native-renderer, JS)

For minimal bundle size on the web, use Canvas2D rendering via `Canvas2dCanvas`.

**Install:**

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.1.0")
}
```

**Usage:**

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.Canvas2dCanvas
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import org.w3c.dom.CanvasRenderingContext2D

// In a <canvas> context
val canvas: CanvasRenderingContext2D = /* ... */
val pdfCanvas = Canvas2dCanvas(canvas)

val pdf = PdfDocument.open(/* ... */)
val page = pdf.getPage(0)
val deviceCtm = PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, page.height * scale)
page.renderTo(pdfCanvas, deviceCtm)

// To save as PNG: use the browser's canvas.toBlob() or toDataURL()
```

!!! warning "Image XObjects in Canvas2D"
    Embedded JPEG and JP2 images in the PDF are painted as grey placeholders (async browser decoding doesn't fit the synchronous renderer). Use Skia on JS for full image support.

## Web: Skia over WASM (kitepdf-skia, JS/wasmJs)

For better image fidelity on the web (including embedded image XObjects), use Skia compiled to WASM.

**Install:**

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-skia:0.1.0")
}
```

**Usage (same as JVM Skia):**

```kotlin
import io.github.yuroyami.kitepdf.skia.PdfPageRasterizer

val pngBytes = PdfPageRasterizer.encodeToPng(page, scale = 1.5)

// Save via browser API
val blob = Blob(arrayOf(pngBytes), object : BlobPropertyBag {
    override var type = "image/png"
})
// ... then download or upload
```

!!! tip "Bundle size trade-off"
    Skia over WASM (Skiko) adds ~5–10MB to your JS bundle. For lightweight viewers, stick with Canvas2D.

## Compose Multiplatform: Export from PdfView

If you're using the Compose viewer (`kitepdf-compose`), export the current rendered page as a PNG via `ImageBitmap.encodeToPng()`.

**Install:**

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-compose:0.1.0")
}
```

**Usage:**

```kotlin
import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kitepdf.compose.encodeToPng
import java.io.File

// Assuming you've rendered a page into an ImageBitmap (e.g., via PdfView's onPageRendered)
val imageBitmap: ImageBitmap = /* ... */
val pngBytes = imageBitmap.encodeToPng() ?: return

// Write to disk
File("export.png").writeBytes(pngBytes)
```

- **Returns:** `ByteArray?` : PNG bytes, or `null` if encoding fails (shouldn't happen for bitmaps produced by `PdfView`).

## Real-world example: Render all pages to PNG thumbnails

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.PdfDocument
import java.io.File

fun renderThumbnails(pdfPath: String, outputDir: String) {
    val pdf = PdfDocument.open(File(pdfPath))
    val outDir = File(outputDir).apply { mkdirs() }
    
    repeat(pdf.pageCount) { pageNum ->
        val page = pdf.getPage(pageNum)
        val pngBytes = AwtPdfRasterizer.encodeToPng(page, scale = 0.5)  // Half-size for quick previews
        File(outDir, "page_$pageNum.png").writeBytes(pngBytes)
        println("Rendered page $pageNum")
    }
    
    pdf.close()
}
```

On a 100-page PDF, parallelizing with coroutines is faster:

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.io.File

suspend fun renderThumbnailsAsync(pdfPath: String, outputDir: String) {
    val pdf = PdfDocument.open(File(pdfPath))
    val outDir = File(outputDir).apply { mkdirs() }
    
    coroutineScope {
        repeat(pdf.pageCount) { pageNum ->
            launch(Dispatchers.Default) {
                val page = pdf.getPage(pageNum)
                val pngBytes = AwtPdfRasterizer.encodeToPng(page, scale = 0.5)
                File(outDir, "page_$pageNum.png").writeBytes(pngBytes)
            }
        }
    }
    
    pdf.close()
}
```

## Performance tips

- **Scale parameter:** A page rendered at `scale = 0.5` is 4x faster and uses 4x less memory than `scale = 1.0` (area scales quadratically).
- **Batch rendering:** Render many pages in parallel on a thread pool or coroutine dispatcher to saturate CPU cores.
- **Platform choice:** AWT on JVM and CoreGraphics on Apple are extremely fast. Skia is also fast but has larger memory overhead.
- **Background color:** Transparent backgrounds (alpha = 0) may be slightly slower than opaque on some platforms.

## Next steps

- [Reading & extracting text](reading.md) from PDFs
- [Building PDFs](writing.md) from scratch
- [Viewing with Compose](compose-viewer.md)
