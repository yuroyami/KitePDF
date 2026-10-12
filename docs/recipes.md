# Recipes

Short solutions to common tasks. Each one links to the page that explains it in full.

## Reading

### Open a PDF with a password

```kotlin
val doc = PdfDocument.open(bytes, password = "secret")
println("${doc.pageCount} pages")
```

A wrong password throws `KiteWrongPasswordException`. See [Encrypted PDFs](reading.md#encrypted-pdfs).

### Get the text of a page

```kotlin
val text = doc.pages[0].extractText()
```

See [Text](reading.md#text).

### Read the metadata and the outline

```kotlin
println("Title: ${doc.info.title}")
println("Author: ${doc.info.author}")

for (item in doc.outline) {
    println("- ${item.title}")
    for (child in item.children) {
        println("  - ${child.title}")
    }
}
```

See [Metadata](reading.md#metadata) and [Bookmarks](reading.md#bookmarks).

### Read the form fields

```kotlin
for (field in doc.formFields) {
    println("${field.fullyQualifiedName}: ${field.value}")
}

// One field by its name
val email = doc.formField("email")?.value
```

See [Form fields](reading.md#form-fields).

### Check the permissions

```kotlin
if (!doc.permissions.canPrint) {
    println("The document asks not to be printed")
}
```

KitePDF does not enforce the permissions. See [Permissions](reading.md#permissions).

## Viewing in Compose

### Show a whole document

```kotlin
@Composable
fun PdfViewer(doc: PdfDocument) {
    KiteDocView(
        state = rememberKiteDocViewState(doc),
        modifier = Modifier.fillMaxSize(),
    )
}
```

The pages scroll down, and pinch zoom works with no setup. See [Compose viewer](compose-viewer.md).

### Show one page

```kotlin
KiteDocView(doc, page = 0, modifier = Modifier.fillMaxWidth())
```

### Turn pages with your own buttons

```kotlin
@Composable
fun PdfPager(doc: PdfDocument) {
    val state = rememberKiteDocViewState(doc)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        KiteDocView(
            state = state,
            modifier = Modifier.weight(1f),
            layout = KiteDocLayout.Paged(Orientation.Horizontal),
            pageSpacing = 12.dp,
        )
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { state.previousPage() } }) { Text("Previous") }
            Text("${state.currentPage + 1} / ${state.pageCount}")
            Button(onClick = { scope.launch { state.nextPage() } }) { Text("Next") }
        }
    }
}
```

See [Navigation](compose-viewer.md#navigation) and [Layouts](compose-viewer.md#layouts).

### Control the zoom from your own UI

```kotlin
@Composable
fun ZoomControls(state: KiteDocViewState) {
    val scope = rememberCoroutineScope()
    Row {
        Button(onClick = { scope.launch { state.animateZoomTo(2f) } }) { Text("2x") }
        Button(onClick = { state.resetZoom() }) { Text("Reset") }
    }
}
```

To turn off every zoom gesture, pass `zoomSpec = KiteZoomSpec.Disabled`. See [Zoom and pan](compose-viewer.md#zoom-and-pan).

## Creating and editing

### Make a new PDF

```kotlin
val bytes = PdfBuilder()
    .setInfo(title = "Invoice", author = "MyApp")
    .page {
        text(StandardFont.HelveticaBold, 24.0, 36.0, 750.0, "Invoice 2024-001")
        text(StandardFont.Helvetica, 12.0, 36.0, 720.0, "Customer: Jane Doe")
    }
    .build()
```

A page is US Letter (612 × 792 points) unless you pass a size. See [Creating PDFs](writing.md).

### Add a watermark to every page

```kotlin
val editor = doc.edit()
for (page in doc.pages) {
    editor.stampPage(page) {
        setFillRgb(0.9, 0.1, 0.1)
        text(StandardFont.Helvetica, 48.0, 200.0, 400.0, "DRAFT")
    }
}
val bytes = editor.saveIncremental()
```

See [Stamps and watermarks](editing.md#stamps-and-watermarks).

### Fill in a text field

```kotlin
val field = requireNotNull(doc.formField("full_name"))
val editor = doc.edit()
editor.setTextFieldValue(field, "Jane Doe")
val bytes = editor.saveIncremental()
```

See [Forms](editing.md#forms).

### Change the metadata

```kotlin
val editor = doc.edit()
editor.setInfo(title = "New Title", author = "New Author")
val bytes = editor.saveIncremental()
```

`setInfo` changes only the values that you pass.

### Redact a region

!!! warning
    Save with `saveRewritten()` after a redaction. An incremental save keeps the redacted content in the file.

```kotlin
val editor = doc.edit()
editor.redactRegion(doc.pages[0], KiteRectangle(left = 100.0, bottom = 500.0, right = 300.0, top = 550.0))
val bytes = editor.saveRewritten()
```

See [Redaction](editing.md#redaction).

## Rendering

### Render every page to a PNG file

```kotlin
val doc = PdfDocument.open(bytes)
for ((index, page) in doc.pages.withIndex()) {
    val png = AwtPdfRasterizer.encodeToPng(page, scale = 1.0)
    File("page_${index + 1}.png").writeBytes(png)
}
```

`AwtPdfRasterizer` runs on the JVM with the JDK alone. See [Headless rendering](rendering.md).

### Make a thumbnail

```kotlin
// A quarter of the size in each direction
val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 0.25)
```
