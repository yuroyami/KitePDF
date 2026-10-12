# Editing and redaction

`doc.edit()` gives a `PdfEditor` for a document that you [opened](reading.md#opening-a-pdf). The editor collects your changes and writes them when you save. The `PdfDocument` itself does not change.

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

The editor saves in one of two ways:

| Save | What it writes | Use it for |
| --- | --- | --- |
| `saveIncremental()` | The original bytes, then the changes at the end | Form filling, stamps, metadata, page changes, signing |
| `saveRewritten()` | A new file with only the objects that the document still uses | Redaction, a smaller file |

[Save modes](#save-modes) tells more about each one.

## Forms

### Values that the reader is filling in

`PdfFormState` holds what a form shows now, while someone fills it in. It keeps the current value of each field, and the renderer draws from it. It does not change the file:

```kotlin
val state = PdfFormState(doc)
state.setValue("employee.name", "Jane Doe")
state.setHidden("manager_only", true)

// The box shows Jane Doe
page.renderTo(canvas, ctm, state)
// Still the value in the file
doc.formField("employee.name")?.value
```

- `state.onChange { }` tells you about each change, so a viewer can redraw only the fields that changed. It returns a function that stops the listening.
- `state.reset(name)` gives one field the value in the file again. `resetAll()` does this for every field.
- `state.resetForm()` gives fields their default values, as a reset button does.
- `setReadOnly` and `isReadOnly` change and read whether the reader can edit a field.

A form field that has no appearance of its own draws from its other entries: the background and the border from `/MK`, and the value in the font of `/DA`.

### Save the values to a file

The editor has one method for each type of field:

| Method | Field type |
| --- | --- |
| `setTextFieldValue(field, value)` | Text |
| `setCheckbox(field, checked)` | Check box |
| `setButtonValue(field, exportValue)` | Radio group. `"Off"` clears the group. |
| `setChoiceValue(field, value)` | Drop-down list and list box |

!!! warning
    Each method throws `IllegalArgumentException` for a field of another type, or for a field that has no indirect reference. `setTextFieldValue` also throws for a field whose widget has no `/Rect`, because the editor needs the rectangle to draw the value.

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

`setTextFieldValue` does these things:

- It sets the value of the field (`/V`).
- It draws a new appearance for the value, with the font size and the color from the field's `/DA`. A multiline field wraps the value at its width and starts at the top. A single-line field draws one line in its middle. `/Q` sets the alignment of each line.
- For a rich text field (`isRichText`), it also writes the value as the rich value (`/RV`), one paragraph for each line.
- It sets the form's `/NeedAppearances` to false, so that readers use the new appearance.

!!! warning
    The new appearance always uses Helvetica. A character above U+007F, such as "é", can show as a different character in other readers (see [Standard fonts](writing.md#standard-fonts)).

A check box or a radio button that has no appearance of its own gets one, from its `/MK` background and border and the mark that `/MK /CA` names. Without that appearance, a ticked box stays blank in a reader that does not draw appearances of its own. The editor draws a mark only where the file tells which button owns which value: a field with one button owns the value that you set, and the buttons of a radio group take their names from `/Opt`. A radio group that has neither keeps its appearances as they are.

`PdfFormState` does not write a file. To save what the reader filled in, call the method of each field in `state.changedFields`:

```kotlin
val editor = doc.edit()
for (name in state.changedFields) {
    val field = doc.formField(name) ?: continue
    val value = state.value(name) ?: continue
    if (field.type == PdfFormField.FieldType.Text) {
        editor.setTextFieldValue(field, value)
    }
}
val saved = editor.saveIncremental()
```

## Stamps and watermarks

`stampPage` draws over a page and keeps what the page already draws. The block uses the same drawing calls as [`PdfBuilder`](writing.md#shapes-and-colors), in the same coordinates:

```kotlin
// A watermark on every page
for (page in doc.pages) {
    editor.stampPage(page) {
        setFillGray(0.7)
        text(StandardFont.Helvetica, 20.0, 50.0, 50.0, "Confidential")
    }
}
```

!!! warning
    A stamp can use the standard fonts only. `drawImage` and an `EmbeddedFont` throw `UnsupportedOperationException` in a stamp.

- The page content and the stamp each run between their own save and restore, so a setting of the page does not change the stamp.
- A second stamp on the same page goes over the first one. It does not replace it.
- The editor adds the fonts of the stamp to the resources of the page, with names that do not clash with the names of the page.

An approval box with a date:

```kotlin
val editor = doc.edit()
editor.stampPage(doc.pages[0]) {
    // The box
    setLineWidth(1.5)
    setStrokeRgb(0.0, 0.0, 0.0)
    rectangle(400.0, 700.0, 150.0, 80.0).stroke()

    // The text in the box
    setFillRgb(0.2, 0.2, 0.2)
    text(StandardFont.HelveticaBold, 12.0, 410.0, 760.0, "APPROVED")
    text(StandardFont.Helvetica, 10.0, 410.0, 740.0, "2025-06-17")
}
val stamped = editor.saveIncremental()
```

## Page content

`editPageContent` gives you the operations of a page's content stream as a list. Your function returns the list to write back, so you can remove, reorder or keep operations:

```kotlin
// Remove all text from a page
editor.editPageContent(doc.pages[0]) { ops ->
    ops.filter { it.operator !in setOf("Tj", "TJ", "'", "\"") }
}

// The same, in one call
editor.removeAllText(doc.pages[0])
```

- Each call gets the content as the earlier calls on the same editor left it, so the calls add up in order.
- An operation that you add can use only the resources that the page already has. To draw with a new font, use [`stampPage`](#stamps-and-watermarks).
- `removeAllText` is not redaction. It removes every text operator of the page, and it keeps the images and the paths.

## Pages

The editor can rotate, remove, reorder and copy pages:

```kotlin
val editor = doc.edit()

// Turn page 1 by 90 degrees clockwise
editor.rotatePage(doc.pages[0], 90)

// Remove page 3
editor.removePage(doc.pages[2])

// Add page 1 of another document at the end
editor.appendPage(other, sourceIndex = 0)

// Add all pages of another document at the end
editor.mergeDocument(other)

val bytes = editor.saveIncremental()
```

| Method | What it does |
| --- | --- |
| `rotatePage(page, degrees)` | Sets the rotation of the page. The angle must be a multiple of 90. |
| `removePage(page)` | Removes the page. The last page cannot go. |
| `appendPage(source, sourceIndex)` | Copies a page of another document to the end. |
| `mergeDocument(source)` | Copies every page of another document to the end. |
| `graftPage(source, sourceIndex)` | Copies a page of another document and returns its reference, but does not add it to the pages. |
| `insertPageAt(position, pageRef)` | Puts a page reference from `graftPage` at a position, counted from 0. |
| `setPageOrder(refs)` | Makes the pages exactly this list of references. Leave a page out to remove it. |

A copied page brings everything that it uses (fonts, images and content) with it. After `saveIncremental()`, a removed page stays in the bytes of the file. `saveRewritten()` drops it.

## Metadata

`setInfo` changes only the values that you pass. It keeps every other entry of the document information:

```kotlin
editor.setInfo(
    title = "Annual Report",
    author = "Finance Dept",
    subject = "FY2025",
    keywords = "financial, annual",
    creator = "MyApp v1.0",
    producer = "KitePDF",
)
```

## Redaction

Redaction removes content from the file. It does not only paint a black box over it. The text, images and paths in a region go out of the content stream, so no tool can extract them:

```kotlin
val redactionRect = KiteRectangle(left = 100.0, bottom = 600.0, right = 300.0, top = 650.0)
editor.redactRegion(doc.pages[0], redactionRect)

// Several regions of one page
editor.redactRegions(
    doc.pages[0],
    listOf(
        KiteRectangle(100.0, 600.0, 300.0, 650.0),
        KiteRectangle(50.0, 400.0, 500.0, 450.0),
    ),
)

val bytes = editor.saveRewritten()
```

The rectangles are in points, from the bottom-left corner of the page. The order of the corners does not matter.

!!! warning
    Always save with `saveRewritten()` after a redaction. An incremental save keeps the original bytes, so the redacted content stays in the file. `saveIncremental()` throws `IllegalStateException` after a redaction.

### What redaction removes

- Text that meets a region. The text after it keeps its place.
- Images that meet a region, with their data.
- Paths (fills, strokes and curves) whose ink reaches a region, with the width of the pen.
- Content in a form XObject, in the coordinates of each place that draws the form. When one form draws in several places, each place that needs a different redaction gets its own copy. A place that no region meets keeps the original form.
- Annotations that meet a region, with their text, appearance, actions and attached files.
- Form fields whose widget the redaction removes. The field leaves the form and loses its value, its default value, its names and its appearance.

Then the editor paints an opaque black box over each region.

The editor empties each object that it takes off the page. So a reference that the editor does not rewrite, such as the structure tree of a tagged PDF, cannot bring the content back.

Redaction removes too much rather than too little:

- A text run or a path that is partly in a region goes out whole.
- A path that also sets a clip keeps its shape and stops painting. The clip still cuts what comes after it.

Some large shapes stay under the black box, because they hide nothing that the box does not cover:

- A path is tested by its segments. So a background rectangle or a page border whose edges are outside every region stays.
- A shading (`sh`) paints its whole clip. It goes out when the edge of its clip meets a region or the clip is inside a region. A shading that covers the whole page stays.

!!! warning
    Redaction does not remove the content below. Check for it before you share a redacted file:

    - Content that a soft mask (`/SMask` in an ExtGState) draws.
    - A stroke whose width comes from `/LW` in an ExtGState. Redaction tests that stroke as a thin line, so a wide stroke near a region can stay.
    - An annotation whose `/Rect` the editor cannot read.
    - An attached file that the catalog's `/Names /EmbeddedFiles` tree also names.

### Several calls

The calls add up. A second region does not undo the first. A stamp or a content edit that you made before the redaction is redacted with the page. The black box of an earlier call is painted again with the new one, so a later call cannot uncover part of an earlier region.

## Save modes

### saveIncremental

`saveIncremental()` writes the original bytes, then the new and changed objects, a new cross-reference section and a trailer that points back to the original one (ISO 32000-1, 7.5.6).

- The original objects stay as they are.
- The file gets bigger with each save.
- A signature stays valid, because the bytes that it signs do not change.
- With no changes, it returns a copy of the original bytes.

### saveRewritten

`saveRewritten()` writes a new file. The file has only the objects that the catalog and the document information can reach, with new object numbers and no link to the original file.

- Objects that nothing uses any more, such as a replaced content stream, are dropped.
- A signature does not stay valid, because the signed bytes change.
- `saveRewritten(useObjectStreams = true)` packs the objects into compressed object streams.

!!! warning
    `saveRewritten()` writes an encrypted document without encryption. Encrypt the file again before you share it, or use `saveIncremental()` when you did not redact.

## Encrypted documents

You can edit a document with AES encryption (AES-128 or AES-256) after you open it with its password. Pass a secure random source to `edit()`, because the editor encrypts each new object with a new random IV. `saveIncremental()` writes the changes with the encryption of the document, so the same password opens the result:

```kotlin
import kotlin.random.asKotlinRandom

// On the JVM and Android
val secureRandom = java.security.SecureRandom().asKotlinRandom()

val doc = PdfDocument.open(encryptedBytes, password = "password")
val out = doc.edit(secureRandom).apply { setInfo(title = "Reviewed") }.saveIncremental()
```

`edit()` throws for a document with RC4 encryption, and for a document that did not open with its password. To make a new encrypted document, see [Password protection](writing.md#password-protection).

## Digital signatures

`PdfSigner` prepares a signature. KitePDF does no cryptography itself: your app makes the CMS signature (a DER `SignedData`), for example with `java.security` on the JVM:

```kotlin
val editor = doc.edit()
val signer = PdfSigner(doc, editor)

// Add an invisible signature field on the first page
signer.prepareSignature("Signature1")

// The file to sign, and the byte range that the signature covers
val target = signer.saveForSigning()
val cms = mySignedData(target.bytes, target.byteRange)

val signed = PdfSigner.embedSignature(target.bytes, target.byteRange, cms)
```

- `prepareSignature` takes a `rect` and a `pageIndex` for a visible field.
- The CMS must fit in 16 KiB. For a bigger one, pass `placeholderSize` to `PdfSigner`.
- `prepareSignature` throws for an encrypted document.

!!! warning
    Do not change the bytes between `saveForSigning()` and `embedSignature`. One changed byte makes the signature invalid.

To check a signed file, open it and call `validate` on its signature (see [Signatures](reading.md#signatures)).

## Raw objects

`allocateReference`, `addObject`, `updateObject` and `setTrailerEntry` change the objects of the file directly. They need an opt-in to `@KiteRawApi`, because a wrong object can make a file that readers refuse. `addFlateStream` adds a compressed stream and returns its reference.
