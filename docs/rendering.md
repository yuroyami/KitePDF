# Headless rendering

Headless rendering turns a page into an image without a UI: for thumbnails, server previews or test screenshots.

## Choose a renderer

| Renderer | Artifact | Targets | Takes |
| --- | --- | --- | --- |
| `AwtPdfRasterizer` | `kitepdf-native-renderer` | JVM | `PdfPage` |
| `AndroidPdfBitmapRenderer` | `kitepdf-native-renderer` | Android | `PdfPage` |
| `ApplePdfRasterizer` | `kitepdf-native-renderer` | iOS, macOS, tvOS | `PdfPage` |
| `Canvas2dCanvas` | `kitepdf-native-renderer` | JS | `PdfPage` |
| `PdfPageRasterizer` | `kitepdf-skia-renderer` | JVM, Android, iOS, macOS, Linux, JS, Wasm | `PdfPage` |
| `EpubPageRasterizer` | `kitepdf-skia-renderer` | JVM, Android, iOS, macOS, Linux, JS, Wasm | `EpubPage` |
| `KitePageRasterizer` | `kitepdf-compose-viewer` | Every Compose target | Any `KitePage` |

`kitepdf-native-renderer` draws with the canvas of each platform, so it adds no graphics library to your app. `kitepdf-skia-renderer` draws with Skia, so the output is the same on every target. For an EPUB, an XPS document or any other page in a Compose app, use [`KitePageRasterizer`](compose-viewer.md#render-a-page-yourself).

The rasterizers share these rules:

- `scale` is pixels per point. `1.0` gives 72 dpi and `2.0` gives 144 dpi. For a given dpi, pass `dpi / 72.0`.
- The size of the image comes from the shown box of the page, with its crop box, its rotation and its user unit. `page.rasterGeometry(scale)` gives the size before you render.
- An image of more than 40 million pixels throws `IllegalArgumentException` before the rasterizer allocates memory. A `scale` that is not finite or not positive throws too. Pass `maxPixels` for another limit.

!!! warning
    Keep a pixel limit when you render documents that you do not trust. A page can declare any size, and a large raster can stop your process.

## JVM: AWT

`AwtPdfRasterizer` uses AWT and ImageIO, which the JDK has, so it needs no native library:

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-native-renderer:0.13.0")
}
```

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.openFile
import java.io.File

val pdf = PdfDocument.openFile("sample.pdf")
val pngBytes = AwtPdfRasterizer.encodeToPng(pdf.pages[0], scale = 2.0)
File("preview.png").writeBytes(pngBytes)
```

| Method | Returns |
| --- | --- |
| `renderToImage(page, scale, background)` | A `BufferedImage` of type `TYPE_INT_ARGB`, to change or to draw somewhere else |
| `encodeToPng(page, scale, background)` | PNG bytes |
| `encodeToJpeg(page, scale, background)` | JPEG bytes. JPEG has no alpha, so the image is opaque. |

`background` is a `java.awt.Color` and is white unless you pass one. `Color(255, 255, 255, 0)` gives a transparent PNG.

## Android: Bitmap

`AndroidPdfBitmapRenderer` returns an `ARGB_8888` `Bitmap`. Render off the main thread:

```kotlin
import android.graphics.Bitmap
import android.graphics.Color
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.nativerenderer.AndroidPdfBitmapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun thumbnail(page: PdfPage): Bitmap = withContext(Dispatchers.Default) {
    AndroidPdfBitmapRenderer.renderToBitmap(page, scale = 1.5, background = Color.WHITE)
}
```

- `background` is an Android color int (`0xAARRGGBB`).
- In Compose, `bitmap.asImageBitmap()` shows the result in an `Image`.
- The bitmap is yours. Call `recycle()` on bitmaps that you no longer need when you render many pages.

## Apple: CoreGraphics

`ApplePdfRasterizer` renders with CoreGraphics and encodes with ImageIO:

```kotlin
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.nativerenderer.ApplePdfRasterizer
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToURL

fun savePreview(page: PdfPage) {
    val pngData = ApplePdfRasterizer.renderToPngData(page, scale = 2.0) ?: return
    val documents = NSFileManager.defaultManager
        .URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        .first() as NSURL
    val fileUrl = documents.URLByAppendingPathComponent("preview.png") ?: return
    pngData.writeToURL(fileUrl, atomically = true)
}
```

- `renderToPngData` returns PNG bytes as `NSData`. It returns null when CoreGraphics cannot make the bitmap or the encoder.
- `backgroundR`, `backgroundG`, `backgroundB` and `backgroundA` set the background, each from 0.0 to 1.0. `backgroundA = 0.0` gives a transparent PNG.
- To draw into a view, make a `CoreGraphicsCanvas` over the current `CGContext` instead.

## Skia on every platform

`kitepdf-skia-renderer` draws with Skia through Skiko, the Kotlin binding of Skia:

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-skia-renderer:0.13.0")
}
```

!!! warning "Android needs an extra repository"
    On Android, this artifact needs `org.jetbrains.skiko:skiko-android`. JetBrains publishes it to the Compose dev repository, not to Maven Central. Without the repository, the build fails with `Could not find org.jetbrains.skiko:skiko-android`. Add it:

    ```kotlin
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    ```

    On Android, `kitepdf-native-renderer` needs no extra repository and adds no Skia library.

!!! warning "The JVM needs the native Skia library"
    On the JVM, Skiko loads a native library for each operating system and processor. A Compose Desktop app has it already. Without Compose, add the runtime for each system that runs your app, or `skiko-awt-runtime-all` for all of them. Use the Skiko version of KitePDF (0.153.0 for KitePDF 0.13.0):

    ```kotlin
    dependencies {
        implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.153.0")
    }
    ```

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.openFile
import io.github.yuroyami.kitepdf.skia.PdfPageRasterizer
import java.io.File

val pdf = PdfDocument.openFile("sample.pdf")
val page = pdf.pages[0]

// PNG bytes in one call
val pngBytes = PdfPageRasterizer.encodeToPng(page, scale = 2.0)
File("preview.png").writeBytes(pngBytes)

// A Skia Image, which holds memory outside the Java heap
val image = PdfPageRasterizer.renderToImage(page, scale = 2.0)
image.close()
```

- `renderToImage` returns a Skia `Image`. Call `close()` on it when you are done, because the garbage collector does not see its memory. `encodeToPng` closes its image itself.
- `background` is a Skia color int (`0xAARRGGBB`) and is white unless you pass one. `0` gives a transparent image.

`EpubPageRasterizer` has the same methods for an `EpubPage`, with one more parameter: `theme` draws the page in a [reader theme](compose-viewer.md#colors-and-themes), such as night mode.

```kotlin
val png = EpubPageRasterizer.encodeToPng(bookPage, scale = 2.0, theme = ReaderTheme.Dark)
```

### Skia on the web

On JS and Wasm, the API is the same. Skia adds its WebAssembly module, `skiko.wasm`, to your app: about 9 MB before compression. When a small download matters more than the same output on every platform, use [Canvas2D](#web-canvas2d).

## Web: Canvas2D

`Canvas2dCanvas` draws on the 2D context of an HTML canvas, with no extra download:

```kotlin
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.nativerenderer.Canvas2dCanvas
import io.github.yuroyami.kitepdf.rasterGeometry
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement

fun drawPage(page: PdfPage, element: HTMLCanvasElement) {
    val geometry = page.rasterGeometry(scale = 2.0)
    element.width = geometry.widthPx
    element.height = geometry.heightPx
    val context = element.getContext("2d") as CanvasRenderingContext2D
    page.renderTo(Canvas2dCanvas(context), geometry.deviceCtm)
}
```

To save the result as a PNG, use the browser's `toBlob()` or `toDataURL()` on the canvas.

An image that the decoders of KitePDF cannot read, such as a JPEG with damaged data, draws as a gray box, as on every canvas.

## Many pages

Each page can render on its own thread, so you can render the pages of one document in parallel:

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.openFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

suspend fun renderThumbnails(pdfPath: String, outputDir: String) {
    val pdf = PdfDocument.openFile(pdfPath)
    val outDir = File(outputDir).apply { mkdirs() }

    coroutineScope {
        pdf.pages.forEachIndexed { index, page ->
            launch(Dispatchers.Default) {
                // Half size, for quick previews
                val pngBytes = AwtPdfRasterizer.encodeToPng(page, scale = 0.5)
                File(outDir, "page_$index.png").writeBytes(pngBytes)
            }
        }
    }
}
```

The memory of an image grows with its area. At `scale = 0.5`, an image has a quarter of the pixels that it has at `scale = 1.0`.

## Cancel a render

Pass a `KiteCancellation` to `renderTo` to stop a long page. When the cancellation returns true, the page paints nothing more and returns with the canvas closed. What the page painted before stays on the canvas:

```kotlin
suspend fun renderOrStop(page: PdfPage, canvas: KiteCanvas, ctm: KiteMatrix) {
    val job = currentCoroutineContext().job
    page.renderTo(canvas, ctm, KiteCancellation { !job.isActive })
    // A cancelled render leaves a part of the page. Do not show it.
    ensureActive()
}
```

Every format checks the cancellation:

- A PDF page checks it every 32 operators.
- An EPUB page checks it before each box or line that it draws.
- An XPS or SVG page checks it before each element.
- A comic page checks it after it reads its image and before it decodes the image.

## Annotations and forms

`PdfPage.renderTo` takes a filter, so a page can render with only some of its annotations, or none:

```kotlin
// The page content only
page.renderTo(canvas, deviceCtm) { false }

// Everything except highlights
page.renderTo(canvas, deviceCtm) { it.subtype != PdfAnnotation.Subtype.Highlight }
```

Use the filter to print or export without markup, for a clean thumbnail, or in an editor that renders the page once and then redraws only its annotations.

To draw a form with the values that the reader filled in, pass a `PdfFormState` (see [Forms](editing.md#forms)):

```kotlin
page.renderTo(canvas, deviceCtm, formState)
```

## Memory

A `PdfDocument` keeps three caches, so that a page that draws again (at another zoom or after a scroll back) costs less:

| Cache | Budget property | Default | What it keeps |
| --- | --- | --- | --- |
| Decoded images | `imageCacheBudgetBytes` | 32 MiB | An image on many pages decodes once. |
| Parsed content | `operationCacheBudgetBytes` | 16 MiB | A page drawn again, or a form drawn many times, parses its content once. |
| Parsed fonts | `fontCacheBudgetBytes` | 32 MiB | A font parses once for all the pages, thumbnails and text extractions that use it. |

- Each cache drops the item that it used least recently first.
- An item larger than its whole budget is not kept, so it decodes or parses again each time.
- A JPEG or JPEG 2000 image without a mask keeps only its encoded data, so a scan counts as its file size. Each draw decodes it at the size that it draws, at a half, a quarter or an eighth of its pixels where it can. So a 600 dpi scan on a phone screen never exists at its full size.
- The content cache counts about 12 MB for a dense page of 100,000 operators. The font cache counts a font as about twice its embedded program, so a 10 MB CJK font counts as about 20 MB.

For a small heap, lower the budgets. When the app runs low on memory, call `dropDecodedImageCache()`, `dropOperationCache()` and `dropFontCache()`:

```kotlin
val doc = PdfDocument.open(bytes)
doc.imageCacheBudgetBytes = 16L * 1024 * 1024
doc.operationCacheBudgetBytes = 8L * 1024 * 1024
doc.fontCacheBudgetBytes = 16L * 1024 * 1024
```

## How the renderers draw

### Thin lines

Every renderer gives a thin stroke the same weight, so a page looks the same on each one:

- A line width of 0 is one device pixel wide, as ISO 32000-1, 8.4.3.2 asks.
- Any other line thinner than a fifth of a pixel widens to a fifth of a pixel. MuPDF does the same, so the thin grid of an ECG report stays light gray and does not turn black.

MuPDF draws a width of 0 at a fifth of a pixel too, so a zero-width line is darker here than in `mutool draw`. In the Compose viewer, `hairlineWidthPx` sets the width of a zero-width stroke (see [Compose viewer](compose-viewer.md)).

### Clip edges on AWT

Java2D does not smooth the edges of a clip. So the AWT renderer applies a clip as MuPDF does:

- A rectangular clip keeps every whole pixel that the rectangle touches.
- Any other clip sends the paints inside it to a layer. When the clip ends, the layer goes onto the page through the smoothed edge of the clip.

So a gradient, an image or a pattern inside a curved clip has smooth edges, as in `mutool draw`. Each curved clip costs one layer the size of the clip, so a page with hundreds of curved clips renders more slowly than a page with rectangular clips.

### Glyphs on AWT

The AWT renderer places and caches glyphs as MuPDF does:

- A glyph snaps to a grid of part pixels. Along the text, an em under 24 pixels lands on a quarter pixel, an em under 48 pixels lands on half a pixel, and a larger em lands on a whole pixel. Across the text, an em of 8 pixels or more lands on a whole pixel, so a line of text sits on one pixel row.
- The renderer fills each glyph once for each size, position on the grid and color. It copies those pixels for every other use on the page.

Turned or skewed text, a glyph with an em over 256 pixels, and text in a blend mode other than Normal are filled as paths. They still snap to the same grid.

### Raster steps

Some effects work on pixels, not on shapes. For example, an SVG filter blurs, moves or recolors the pixels of an element. A canvas gives such an effect a raster step (`KiteCanvas.rasterStep`): the canvas paints the content into a raster of its box, the effect changes the pixels in plain Kotlin, and the canvas draws the result back.

Every canvas of KitePDF has raster steps. A step can also read the backdrop (the pixels under its box), but only some canvases can give it:

| Canvas | Gives the backdrop |
| --- | --- |
| `AwtCanvas` | Always, also in a group or under a curved clip |
| `Canvas2dCanvas` | Always, because each of its layers is a canvas of its own |
| `SkiaCanvas` | Outside every group and soft mask layer, on a canvas that Skia can read back |
| `CoreGraphicsCanvas` | On a bitmap context, outside every group layer |
| `AndroidNativeCanvas` | In `AndroidPdfBitmapRenderer`, outside every group and soft mask layer |
| `ComposeCanvas` | In `KitePageRasterizer`, outside every group and soft mask layer |
| `KiteRasterCanvas` | Always, also in a group, because it draws into memory in plain Kotlin |

A PDF transparency group that is not isolated, and whose paints blend, needs the backdrop (ISO 32000-1, 11.4.8). The renderer paints such a group over a copy of the backdrop and alone, and works out the group from the two rasters. A knockout group of that kind (11.4.6) costs three renders of its box for each of its objects. A knockout group that would render more than 100 million pixels paints as a plain layer instead.

When an effect needs a backdrop that the canvas cannot give, the group paints as a plain layer. An SVG filter needs no backdrop: as in a browser, it reads `BackgroundImage` as transparent. A box larger than the pixel budget of the canvas works at a lower resolution and reads no backdrop. A canvas of your own runs no step unless you add one, and its caller then draws the content without the effect.

## See also

- [Reading PDFs](reading.md)
- [Creating PDFs](writing.md)
- [Compose viewer](compose-viewer.md)
