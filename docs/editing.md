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
- Regenerates the widget's `/AP /N` (normal appearance), using the field's `/DA` (default appearance) string to recover font, size, and color.
- Clears the form's `/NeedAppearances` flag so conforming viewers use the appearance we generated.
- Buttons and choice fields have their own methods: `setCheckbox(field, checked)`, `setButtonValue(field, exportValue)` for radio groups, and `setChoiceValue(field, value)` for dropdowns and list boxes.

A checkbox or radio widget that ships no `/AP` of its own gets one drawn, from
the widget's `/MK` background and border plus the ZapfDingbats mark `/MK /CA`
names (the check by default, the filled circle for a radio). Without it a
ticked box stays blank in any reader that does not regenerate appearances.

Which widget owns which value cannot be guessed, so an appearance is drawn only
where the file says: a lone widget owns the value being set, and a radio group's
kids take their names from `/Opt`. A group with neither is left exactly as it
was, rather than having every radio light up at once.

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

Stamps compose. A second stamp on the same page overlays the first rather than replacing it, and gets its own font resource names.

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
- **Color**: `setFillRgb(r, g, b)`, `setStrokeRgb(r, g, b)`, `setFillGray(g)`, `setStrokeGray(g)`.
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

Edits compose. The transform receives the page's content as it stands after any earlier edit on the same editor, so successive calls build on each other in call order, and a stamp or an edit staged earlier is redacted along with the original page content.

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
val redactionRect = KiteRectangle(left = 100.0, bottom = 600.0, right = 300.0, top = 650.0)
editor.redactRegion(doc.pages[0], redactionRect)

val bytes = editor.saveRewritten()  // Required!
```

Corner order does not matter: a rectangle is two opposite corners in either order (ISO 32000-1, 7.9.5) and KitePDF normalises it, so an inside-out rectangle redacts the same area rather than silently nothing.

Or redact multiple regions at once:

```kotlin
val rects = listOf(
    KiteRectangle(100.0, 600.0, 300.0, 650.0),
    KiteRectangle(50.0, 400.0, 500.0, 450.0),
)
editor.redactRegions(doc.pages[0], rects)
val bytes = editor.saveRewritten()
```

### How redaction works

The redaction engine:

1. **Parses the page's content stream** to find all text, images, paths and form invocations.
2. **Tracks positions** through the graphics and text state machines (CTM, text matrix, font metrics, pen width).
3. **Tests intersection** with each redaction rectangle: if a text run, character sequence, image or path overlaps the rectangle, it is **removed entirely** (bytes deleted from the stream), and a text run is replaced with a spacing adjustment so surviving text keeps its position.
4. **Recurses into every form XObject** the page invokes over a region, mapping the rectangle into that invocation's own coordinate space and copying the form when two invocations need different redactions.
5. **Detaches and empties** annotations and form fields that fall in a region.
6. **Paints opaque black boxes** over each redaction region to cover visual traces.

The decision is **deliberately conservative**: a run touching a region is removed wholesale, so partial overlaps over-remove rather than risk leaving redacted content.

### Why saveRewritten() is mandatory

!!! warning
    **Always use `saveRewritten()` after redaction.** An incremental save would append the new (redacted) content while leaving the original, unredacted bytes in the file; where they remain **fully recoverable** by extracting earlier objects in the incremental chain, defeating redaction entirely.

```kotlin
editor.redactRegion(doc.pages[0], KiteRectangle(100.0, 600.0, 200.0, 650.0))
// ❌ WRONG: val bytes = editor.saveIncremental()  // Original text still in file!
// ✅ RIGHT:
val bytes = editor.saveRewritten()  // Creates a fresh PDF, drops unreachable content
```

### Worked example: redact SSN and save

```kotlin
val doc = PdfDocument.open(idCardBytes)
val editor = doc.edit()

// Redact a rectangular area containing the SSN (coordinates in points)
editor.redactRegion(doc.pages[0], KiteRectangle(
    left = 50.0,
    bottom = 100.0,
    right = 200.0,
    top = 130.0
))

// Mandatory for redaction
val redacted = editor.saveRewritten()
```

### What redaction removes

- **Text runs** whose box meets a region, with a spacing adjustment left behind so surviving text keeps its position.
- **Images** whose placed area meets a region, including the data stream: the `/XObject` entry is pruned, so `saveRewritten()` does not carry it.
- **Vector paths** (fills, strokes, curves) whose ink reaches a region, with the pen width accounted for. A path that also sets a clip keeps its construction and stops painting, because dropping a clip would change everything drawn after it.
- **Form XObject content**, redacted in each invocation's own coordinate space. One form can be drawn in several places, and each place sees the region in a different part of the form, so each place that needs a different redaction gets its own copy. Redacting one place does not blank the others, and a place no region touches keeps drawing the original.
- **Annotations** whose `/Rect` meets a region, together with their contents: an annotation taken off the page is also restaged without its text, appearance, action and caption entries.
- **Form fields** belonging to a removed widget, detached from both `/AcroForm /Fields` and `/AcroForm /CO` (the calculation order), and emptied of value, default value, rich value, appearance state, selection index, name, tooltip and mapping name.

The principle behind that last pair is worth stating, because it explains the shape of the rest: **redaction does not rely on the garbage collector to delete a secret.** An object taken off the page is emptied as well as unlinked, so a reference somewhere the editor does not rewrite cannot bring its contents back.

### Redaction limitations

- **Deliberately conservative.** A text run or a path that only partly overlaps a region is removed whole, rather than risk leaving part of it behind.
- **A large uniform fill survives.** A path is judged by its segments, so a background rectangle or page border whose edges lie outside every region is kept. It conceals nothing the black box does not already cover, and the alternative deletes the page's artwork.
- **Soft masks are not inspected.** Content reached only through an `/ExtGState /SMask` luminosity group is not redacted.
- **Shadings are judged by their clip.** An `sh` operator paints its whole clipping region, so it is removed when that region's boundary touches a redacted area or sits wholly inside one. A page-wide (unclipped or region-surrounding) shading survives under the black box, like any full-page background.
- **Line width set through an ExtGState is not seen.** Only the `w` operator is tracked, so a stroke whose width comes from `/LW` is padded as if it were hairline. This is a library-wide gap: the renderer does not read `/LW` either.
- **A form shared between two pages, redacted in separate calls**, can leave the second page showing the first page's redaction. Over-removal, not a leak.
- **A form invoked from two different parent forms** decides per parent whether the original must stay intact, so one parent's descent can claim it before the other parent's untouched invocation is reached. Over-removal, not a leak.
- **An annotation whose `/Rect` cannot be read stays on the page**, so a widget with a malformed rectangle inside a region keeps its value.
- **An embedded file also listed in the catalog's `/Names /EmbeddedFiles` tree** stays in the document. Emptying the annotation orphans its own reference, but the name tree is a catalog structure the editor does not rewrite.

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
editor.redactRegion(doc.pages[0], KiteRectangle(100.0, 600.0, 200.0, 650.0))
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
    producer = "KitePDF 0.5.0",
)
```

All parameters are optional; only non-null fields are changed. Existing `/Info` entries (standard or custom) are preserved if they are not overridden.

## Incremental updates & digital signing

The editor's incremental-save mode is the foundation for digital signature workflows:

1. Open and make edits via the editor.
2. Call `saveIncremental()` to append changes; this yields the "unsigned" byte range.
3. Sign that byte range (typically `[0, savedBytes.size)`) with a cryptographic signature algorithm.
4. Write the signature object into the document (another incremental append).

`PdfSigner` implements this flow: `prepareSignature(fieldName)` stages the signature field and placeholder, `saveForSigning()` returns the bytes plus the exact `/ByteRange` to sign, and `PdfSigner.embedSignature(bytes, byteRange, cms)` patches your DER `SignedData` in without moving a byte. KitePDF does no signature cryptography itself; the CMS blob comes from your application (on the JVM, `java.security` builds one in a few lines).

## Encrypted documents

AES-encrypted documents (V4/AESV2 and V5/AES-256) can be edited directly: open with the password, edit, save. The editor re-encrypts every staged object to match the document's security handler, so the output opens with the same password:

```kotlin
val doc = PdfDocument.open(encryptedBytes, "password".encodeToByteArray())
val out = doc.edit().apply { setInfo(title = "Reviewed") }.saveIncremental()
```

Documents encrypted with legacy RC4 (V1/V2) are refused for editing; decrypt-and-rebuild those first. Creating new encrypted documents works too: `PdfBuilder.encrypt(userPassword, ownerPassword)` produces an AES-256 (R6) file.
