# Recipes

Short, working solutions to common KitePDF tasks. Each snippet is copy-pasteable and uses only real APIs from KitePDF 0.1.0.

## Open a password-protected PDF

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument

val bytes = readFile("document.pdf")
val password = "secret".encodeToByteArray()

val doc = PdfDocument.open(bytes, password)
println("Authenticated: ${doc.isAuthenticated}")
println("${doc.pageCount} pages")
```

If `isAuthenticated` is false, the password was wrong or the document has no password.

## Extract text from a page

```kotlin
val doc = PdfDocument.open(bytes)
val text = doc.pages[0].extractText()
println(text)
```

## View a full PDF in Compose

```kotlin
import io.github.yuroyami.kitepdf.compose.PdfView
import io.github.yuroyami.kitepdf.compose.rememberPdfViewState

@Composable
fun PdfViewer(doc: PdfDocument) {
    PdfView(
        state = rememberPdfViewState(doc),
        modifier = Modifier.fillMaxSize(),
    )
}
```

Users scroll vertically by default; swiping and pinch-zoom work out of the box.

## View a single page in Compose

```kotlin
PdfView(
    document = doc,
    page = 0,
    modifier = Modifier.fillMaxWidth(),
)
```

## Horizontal pager layout with custom controls

```kotlin
@Composable
fun PdfPager(doc: PdfDocument) {
    val state = rememberPdfViewState(doc)
    val scope = rememberCoroutineScope()
    
    Column(Modifier.fillMaxSize()) {
        PdfView(
            state = state,
            modifier = Modifier.weight(1f),
            layout = PdfLayout.Paged(Orientation.Horizontal),
            pageSpacing = 12.dp,
        )
        
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { state.previousPage() } }) { Text("← Prev") }
            Text("${state.currentPage + 1} / ${state.pageCount}")
            Button(onClick = { scope.launch { state.nextPage() } }) { Text("Next →") }
        }
    }
}
```

`state` controls the viewer from outside; any widget can drive it.

## Add an overlay (HUD) to the viewer

```kotlin
PdfView(
    state = state,
    modifier = Modifier.fillMaxSize(),
    overlay = { state ->
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Text("Page ${state.currentPage + 1} of ${state.pageCount}")
            Button(onClick = { scope.launch { state.setZoom(2f) } }) {
                Text("2x zoom")
            }
        }
    },
)
```

The overlay `BoxScope` receives the viewer state; position widgets with `align()`.

## Choose rasterized vs. vectorized rendering

**Rasterized (default):** bitmaps, sharp at normal zoom, lighter on CPU, heavier memory:

```kotlin
PdfView(
    state = state,
    modifier = Modifier.fillMaxSize(),
    renderSpec = PdfRenderSpec.Rasterized(
        quality = 1f,              // 1 = display resolution
        maxBitmapLongSide = 4096,  // cap memory
        rerasterizeOnZoom = true,  // crisp at deep zoom
        preserveHairlines = true,  // keep thin lines visible
    ),
)
```

**Vectorized:** resolution-independent, lower memory, re-draws every frame:

```kotlin
PdfView(
    state = state,
    modifier = Modifier.fillMaxSize(),
    renderSpec = PdfRenderSpec.Vectorized(hairlineWidthPx = 1f),
)
```

Rasterized is best for fast panning and dense pages; Vectorized for simple layouts and extreme zoom.

## Build a new PDF from scratch

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont

val bytes = PdfBuilder()
    .setInfo(title = "Invoice", author = "MyApp")
    .page(width = 612.0, height = 792.0) {
        // US Letter: 612×792 points
        text(StandardFont.HelveticaBold, 24.0, 36.0, 750.0, "Invoice #2024-001")
        text(StandardFont.Helvetica, 12.0, 36.0, 720.0, "Customer: Jane Doe")
        text(StandardFont.Helvetica, 12.0, 36.0, 705.0, "Amount: $500.00")
    }
    .build()

writeFile("invoice.pdf", bytes)
```

Pages are 612×792 points by default (US Letter). Coordinates are in points, origin bottom-left.

## Add a watermark to every page and save

```kotlin
val editor = doc.edit()

for (page in doc.pages) {
    editor.stampPage(page) {
        setFillRgb(0.9, 0.1, 0.1)  // Red
        text(StandardFont.Helvetica, 48.0, 200.0, 400.0, "DRAFT")
    }
}

val bytes = editor.saveIncremental()
writeFile("watermarked.pdf", bytes)
```

`stampPage` appends drawing commands over existing content; the page content is preserved.

## Redact a rectangular region and save

```kotlin
import io.github.yuroyami.kitepdf.Rectangle

val editor = doc.edit()

val redactBox = Rectangle(
    left = 100.0,
    bottom = 500.0,
    right = 300.0,
    top = 550.0,
)

editor.redactRegions(doc.pages[0], listOf(redactBox))

// Must use saveRewritten() after redaction: incremental save would leave original content recoverable.
val bytes = editor.saveRewritten()
writeFile("redacted.pdf", bytes)
```

Redaction truly removes content bytes and paints black boxes. Use `saveRewritten()` to ensure removed content is purged from the file.

## Fill a text form field

```kotlin
val field = doc.formField("full_name")
    ?: throw IllegalArgumentException("Field not found")

val editor = doc.edit()
editor.setTextFieldValue(field, "Jane Doe")

val bytes = editor.saveIncremental()
writeFile("filled.pdf", bytes)
```

Only `/Tx` (text) fields are supported. The appearance is auto-generated from the field's `/DA` (default appearance).

## Render all pages to PNG files (CI / server)

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File

val doc = PdfDocument.open(bytes)

for ((index, page) in doc.pages.withIndex()) {
    val png = AwtPdfRasterizer.encodeToPng(page, scale = 1.0)
    File("page_${index + 1}.png").writeBytes(png)
}
```

No Compose, no Skia; pure JDK. Perfect for headless backends and CI pipelines.

## Generate a thumbnail

```kotlin
// Rasterize at 25% scale
val thumbnail = AwtPdfRasterizer.renderToImage(
    doc.pages[0],
    scale = 0.25,
    background = java.awt.Color.WHITE,
)

// Convert to PNG bytes for storage or transmission
val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 0.25)
```

## Drive external zoom and pan controls

```kotlin
@Composable
fun CustomZoomControls(state: PdfViewState) {
    var zoomValue by remember { mutableFloatStateOf(state.zoom) }
    val scope = rememberCoroutineScope()
    
    Column(Modifier.padding(8.dp)) {
        Slider(
            value = zoomValue,
            onValueChange = { zoomValue = it },
            valueRange = 1f..8f,
            onValueChangeFinished = {
                scope.launch { state.setZoom(zoomValue) }
            },
        )
        
        Button(onClick = { scope.launch { state.resetZoom() } }) { Text("Reset") }
        
        Button(onClick = { scope.launch { state.animateZoomTo(2f) } }) { Text("2x") }
    }
}
```

Wire any UI control (sliders, buttons, nudge keys) directly to `state.setZoom()` and `state.panBy()`.

## Update document metadata

```kotlin
val editor = doc.edit()

editor.setInfo(
    title = "New Title",
    author = "New Author",
    subject = "Updated Document",
    keywords = "example,metadata",
)

val bytes = editor.saveIncremental()
```

Only non-null fields are changed; existing `/Info` entries are preserved.

## Strip all text from a page

```kotlin
val editor = doc.edit()
editor.removeAllText(doc.pages[0])

val bytes = editor.saveIncremental()
```

This is a primitive text-removal filter (it removes all text show operators), not true selective redaction. For precise rectangular redaction, use `redactRegions()`.

## Disable zoom and panning

```kotlin
PdfView(
    state = state,
    modifier = Modifier.fillMaxSize(),
    zoomSpec = PdfZoomSpec.Disabled,
)
```

## Read document metadata and outlines

```kotlin
val doc = PdfDocument.open(bytes)

// Trailer /Info dict
println("Title: ${doc.info.title}")
println("Author: ${doc.info.author}")

// Bookmarks
for (outline in doc.outlines) {
    println("- ${outline.title}")
    for (child in outline.children) {
        println("  - ${child.title}")
    }
}
```

## Read form fields

```kotlin
for (field in doc.formFields) {
    println("${field.fullyQualifiedName}: ${field.value}")
}

// Or look up one by name
val field = doc.formField("email")
println(field?.value)
```

## Check document permissions

```kotlin
val perms = doc.permissions

if (!perms.canPrintFull) {
    println("User cannot print this document")
}
```

`PdfPermissions` reflects the `/P` bit-flags from the security handler. Unencrypted documents allow all operations.
