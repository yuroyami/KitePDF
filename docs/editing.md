# Editing, forms & redaction

Edit PDF documents in place: modify form fields, add watermarks and stamps to pages, redact sensitive content, and save changes either incrementally or with garbage collection.

## Overview

Open a document with [`PdfDocument.open()`](reading.md#opening-a-pdf) and call `doc.edit()` to get a `PdfEditor` instance. The editor stages changes (new objects, replacements, deletions) and saves them in one of two modes:

- **Incremental**: appends changes to the original bytes, preserving the original content. Ideal for form-filling, watermarking, and metadata updates. This mode is the foundation for digital signatures.
- **Rewritten**: writes a fresh PDF containing only reachable objects, with edits applied and unreachable objects dropped. Required for **true redaction**, since the removed content is completely gone, not hidden in the file.

```kotlin
val doc = PdfDocument.open(pdfBytes)
val editor = doc.edit()
editor.setInfo(title = "Processed")
editor.stampPage(doc.pages[0]) {
    setFillRgb(0.8, 0.1, 0.1)
    text(StandardFont.HelveticaBold, 48.0, 120.0, 400.0, "DRAFT")
}
val updated = editor.saveIncremental()
```

!!! note
    The `PdfDocument` instance itself is never mutated. Edits are staged in the editor and only written when you call `saveIncremental()` or `saveRewritten()`.

## Fill form fields

PDF interactive forms (AcroForms) are fully readable via [`doc.formFields`](reading.md#form-fields), and text fields can be filled programmatically.

### Text field filling

Call `editor.setTextFieldValue(field, value)` to set a text field and regenerate its appearance (the visual representation viewers display):

```kotlin
val doc = PdfDocument.open(formBytes)
val editor = doc.edit()

for (field in doc.formFields) {
    if (field.type == PdfFormField.FieldType.Text) {
        val newValue = when (field.fullyQualifiedName) {
            "employee.name" -> "Alice Johnson"
            "employee.date" -> "2025-06-17"
            else -> null
        }
        if (newValue != null) {
            editor.setTextFieldValue(field, newValue)
        }
    }
}

val filled = editor.saveIncremental()
```

The method:

- Updates the field's `/V` (value) entry.
- Regenerates the widget's `/AP /N` (normal appearance), using the field's `/DA` (default appearance) string to recover font, size, and colour.
- Clears the form's `/NeedAppearances` flag so conforming viewers use the appearance we generated.
- Works only with `/Tx` (text) fields. Buttons and choice fields are not yet supported.

!!! warning
    Text fields without a widget `/Rect` or indirect reference cannot be filled; the editor needs these to construct and store the appearance stream.

### Worked example: form fill + stamp + save

```kotlin
val doc = PdfDocument.open(formBytes)
val editor = doc.edit()

// Fill a text field
val nameField = doc.formField("recipient.name")
if (nameField != null && nameField.type == PdfFormField.FieldType.Text) {
    editor.setTextFieldValue(nameField, "Jane Doe")
}

// Add a watermark to every page
for (page in doc.pages) {
    editor.stampPage(page) {
        setFillGray(0.7)
        text(StandardFont.Helvetica, 20.0, 50.0, 50.0, "Confidential")
    }
}

// Update metadata
editor.setInfo(producer = "MyApp v1.0", author = "Admin")

// Save
val bytes = editor.saveIncremental()
```

## Stamp and watermark pages

Overlay text, graphics, or images onto an existing page without altering its original content.

### stampPage

Call `editor.stampPage(page) { ... }` with a lambda in the `ContentStreamBuilder` DSL to draw onto a page:

```kotlin
editor.stampPage(doc.pages[0]) {
    setFillRgb(0.8, 0.1, 0.1)
    text(StandardFont.HelveticaBold, 48.0, 120.0, 400.0, "DRAFT")
}
```

The content stream builder offers a complete drawing API:

- **Graphics state**: `save()`, `restore()`, `transform(a, b, c, d, e, f)`, `setLineWidth(w)`.
- **Colour**: `setFillRgb(r, g, b)`, `setStrokeRgb(r, g, b)`, `setFillGray(g)`, `setStrokeGray(g)`.
- **Paths**: `moveTo(x, y)`, `lineTo(x, y)`, `rectangle(x, y, w, h)`, `closePath()`, `stroke()`, `fill()`, `fillAndStroke()`, `endPath()`.
- **Clipping**: `clip()`, `clipEvenOdd()`.
- **Text**: `beginText()`, `endText()`, `setFont(font, size)`, `moveText(tx, ty)`, `showText(text)`, `setLeading(leading)`, `setCharSpacing(spacing)`, `setWordSpacing(spacing)`, `nextLine()`.
- **Convenience**: `text(font, size, x, y, text)`: a single-line helper that wraps `BT/Tf/Td/Tj/ET`.
- **Raw escape hatch**: `raw(content)`: append literal PDF content stream source.

Coordinates are in the default user space (origin at bottom-left, units are points).

### How stampPage works

The original page content is preserved and wrapped in `q` (save graphics state) / `Q` (restore). Your overlay is appended in its own `q` / `Q` block, so drawing attributes don't leak. Any standard fonts referenced in your overlay are merged into the page's `/Resources` under auto-generated names (e.g. `KF1`, `KF2`) so the resulting page is self-contained.

```kotlin
// A watermark on every page
for (page in doc.pages) {
    editor.stampPage(page) {
        setFillGray(0.9)
        // Text baseline at (50, 50)
        text(StandardFont.Helvetica, 24.0, 50.0, 50.0, "Watermark")
    }
}
```

### Worked example: date-stamped approval

```kotlin
val editor = doc.edit()
editor.stampPage(doc.pages[0]) {
    setFillRgb(0.0, 0.0, 0.0)
    setLineWidth(1.5)
    
    // Draw a box
    rectangle(400.0, 700.0, 150.0, 80.0)
    stroke()
    
    // Text inside
    setFillRgb(0.2, 0.2, 0.2)
    text(StandardFont.HelveticaBold, 12.0, 410.0, 760.0, "APPROVED")
    text(StandardFont.Helvetica, 10.0, 410.0, 740.0, "2025-06-17")
}
val stamped = editor.saveIncremental()
```

## Edit page content

For more sophisticated edits, parse and transform a page's content stream directly.

### editPageContent

```kotlin
fun editPageContent(page: PdfPage, transform: (List<Operation>) -> List<Operation>)
```

The content stream is parsed into a list of `Operation` objects (operator name + operands), passed to your lambda, and re-serialized. This lets you filter, reorder, or modify drawing commands:

```kotlin
// Remove all text from a page
editor.editPageContent(doc.pages[0]) { ops ->
    ops.filter { it.operator !in setOf("Tj", "TJ", "'", "\"") }
}
```

Or use the built-in shorthand:

```kotlin
editor.removeAllText(doc.pages[0])
```

!!! note
    `editPageContent` only reorders/removes/keeps existing operations; it doesn't introduce new resource dependencies (fonts, images). To add content with its own resources, use [`stampPage`](#stamp-and-watermark-pages) instead.

## Redaction

True redaction: permanently removing sensitive content from a PDF; requires rewriting the document. Unlike painting black boxes, redaction actually deletes the underlying text and images so they cannot be extracted or recovered.

### redactRegion and redactRegions

```kotlin
val redactionRect = Rectangle(left = 100.0, bottom = 600.0, right = 300.0, top = 650.0)
editor.redactRegion(doc.pages[0], redactionRect)

val bytes = editor.saveRewritten()  // Required!
```

Or redact multiple regions at once:

```kotlin
val rects = listOf(
    Rectangle(100.0, 600.0, 300.0, 650.0),
    Rectangle(50.0, 400.0, 500.0, 450.0),
)
editor.redactRegions(doc.pages[0], rects)
val bytes = editor.saveRewritten()
```

### How redaction works

The redaction engine:

1. **Parses the page's content stream** to find all text, images, and paths.
2. **Tracks text and image positions** through the graphics and text state machines (CTM, text matrix, font metrics).
3. **Tests intersection** with each redaction rectangle: if a text run, character sequence, or image overlaps the rectangle, it is **removed entirely** (bytes deleted from the stream) and replaced with a spacing adjustment so surviving text keeps its position.
4. **Paints opaque black boxes** over each redaction region to cover visual traces.

The decision is **deliberately conservative**: a run touching a region is removed wholesale, so partial overlaps over-remove rather than risk leaving redacted content.

### Why saveRewritten() is mandatory

!!! warning
    **Always use `saveRewritten()` after redaction.** An incremental save would append the new (redacted) content while leaving the original, unredacted bytes in the file; where they remain **fully recoverable** by extracting earlier objects in the incremental chain, defeating redaction entirely.

```kotlin
editor.redactRegion(doc.pages[0], Rectangle(100.0, 600.0, 200.0, 650.0))
// ❌ WRONG: val bytes = editor.saveIncremental()  // Original text still in file!
// ✅ RIGHT:
val bytes = editor.saveRewritten()  // Creates a fresh PDF, drops unreachable content
```

### Worked example: redact SSN and save

```kotlin
val doc = PdfDocument.open(idCardBytes)
val editor = doc.edit()

// Redact a rectangular area containing the SSN (coordinates in points)
editor.redactRegion(doc.pages[0], Rectangle(
    left = 50.0,
    bottom = 100.0,
    right = 200.0,
    top = 130.0
))

// Mandatory for redaction
val redacted = editor.saveRewritten()
```

### Redaction limitations

- **Vector paths** (strokes, fills, curves) within a region are left as-is; only text and images are removed.
- **Form XObjects** (content streams referenced from `/XObject`) are not recursed into; content inside them is preserved.
- **Image data objects** that are dropped are no longer drawn or referenced, but their data stream is not yet purged from the file.

Future versions will handle these cases more aggressively.

## Save modes

### saveIncremental

```kotlin
fun saveIncremental(): ByteArray
```

Appends changes to the original byte buffer (ISO 32000-1 §7.5.6):

- Original objects are left untouched.
- Only new/changed objects, a fresh xref section, and a trailer pointing back via `/Prev` are appended.
- The resulting file is larger but supports the full incremental-update chain.
- Required for digital signature workflows (the signature signs only the appended byte range).

**Use for**: form-filling, metadata updates, watermarks, small edits.

**Fails if**: redaction has been staged. Call [`saveRewritten()`](#saverewritten) instead.

```kotlin
val editor = doc.edit()
editor.setInfo(title = "Reviewed")
editor.stampPage(doc.pages[0]) { text(StandardFont.Helvetica, 12.0, 100.0, 100.0, "OK") }
val bytes = editor.saveIncremental()  // OK
```

### saveRewritten

```kotlin
fun saveRewritten(): ByteArray
```

Writes a brand-new PDF file from scratch, containing only objects reachable from the catalog and `/Info`:

- Unreachable objects (e.g. old content streams, replaced annotations) are dropped; garbage collection.
- Object numbers are renumbered densely and sequentially.
- The file is self-contained and has no `/Prev` chain.
- **Required after redaction**: the original bytes are not retained, so redacted content is truly gone.

**Use for**: redaction, cleanup, file shrinking.

**Incompatible with**: digital signatures (signatures sign a byte range in the original file; rewriting invalidates them).

```kotlin
val editor = doc.edit()
editor.redactRegion(doc.pages[0], Rectangle(100.0, 600.0, 200.0, 650.0))
val bytes = editor.saveRewritten()  // Removes redacted text permanently
```

## Metadata

Set document metadata via `editor.setInfo()`:

```kotlin
editor.setInfo(
    title = "Annual Report",
    author = "Finance Dept",
    subject = "FY2025",
    keywords = "financial, annual",
    creator = "MyApp v1.0",
    producer = "KitePDF 0.1.0",
)
```

All parameters are optional; only non-null fields are changed. Existing `/Info` entries (standard or custom) are preserved if they are not overridden.

## Incremental updates & digital signing

The editor's incremental-save mode is the foundation for digital signature workflows:

1. Open and make edits via the editor.
2. Call `saveIncremental()` to append changes; this yields the "unsigned" byte range.
3. Sign that byte range (typically `[0, savedBytes.size)`) with a cryptographic signature algorithm.
4. Write the signature object into the document (another incremental append).

KitePDF does not yet provide signing utilities, but the incremental framework is ready. Unsigned PDFs produced by incremental edits are valid inputs to any external PDF signing library.

## Encrypted documents

Editing encrypted PDFs is not yet supported. To modify an encrypted PDF, decrypt it first (pass the password to [`PdfDocument.open()`](reading.md#encrypted-pdfs)), then edit and save:

```kotlin
// Editing encrypted documents is not yet supported
val doc = PdfDocument.open(encryptedBytes, "password".encodeToByteArray())
// editor.setInfo(...) would raise an error
```

Once support is added, the writer will encrypt newly written strings and streams to match the document's security handler.
