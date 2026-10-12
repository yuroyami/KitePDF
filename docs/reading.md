# Reading PDFs

`PdfDocument` reads a PDF file: its pages, text, metadata, bookmarks, annotations, form fields and signatures. It is in the `kitepdf-pdf` artifact, which the `kitepdf` artifact includes. [Loading a document](loading.md) shows every source that KitePDF can open, and how to open a file whose format you do not know.

## Opening a PDF

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument
import java.io.File

val doc = PdfDocument.open(File("example.pdf").readBytes())
println("${doc.pageCount} pages, PDF ${doc.version}")
```

`open` throws `PdfFormatException` for a file that it cannot read, and `KiteWrongPasswordException` when the password is wrong. A file whose cross-reference table is damaged still opens, because KitePDF rebuilds the table from the bytes.

### Encrypted PDFs

Pass the password, and KitePDF decrypts each object as it reads it:

```kotlin
val doc = PdfDocument.open(bytes, password = "secret")
```

A `String` password goes in as UTF-8 first. When the UTF-8 bytes fail and the password has characters outside ASCII, KitePDF tries it once more as Latin-1, as MuPDF does. For any other encoding, pass the password as a `ByteArray`.

With `allowInvalidPassword = true`, a wrong password does not throw. The document then opens with `isAuthenticated` set to false, and its text and images do not decode. `doc.isEncrypted` tells you whether a file has encryption at all.

### Permissions

An encrypted PDF can ask the reader not to print, copy or change it. KitePDF reads these permissions but does not enforce them, so your app decides whether to follow them:

```kotlin
val permissions = doc.permissions
if (!permissions.canCopyContents) println("The author asks you not to copy the text")
if (!permissions.canPrintHighResolution) println("Print at low resolution only")
```

`PdfPermissions` has `canPrint`, `canPrintHighResolution`, `canModifyContents`, `canCopyContents`, `canModifyAnnotations`, `canFillForms`, `canExtractForAccessibility` and `canAssembleDocument` (which covers inserting, deleting and rotating pages). An unencrypted document allows everything.

## Pages

```kotlin
val page = doc.pages[0]

// The size that a viewer shows, after the crop box and the rotation
println("${page.displayWidth} x ${page.displayHeight} pt")

for (page in doc.pages) {
    println("Page ${page.index + 1} has the label ${page.label}")
}
```

`page.width` and `page.height` give the size of the media box, which ignores `/CropBox` and `/Rotate`. Use `displayWidth` and `displayHeight` for the size on screen. `page.rotation` is the `/Rotate` value of the file, and `rotationNormalized` turns it into 0, 90, 180 or 270.

`page.label` is the label from `/PageLabels`, such as "iii" or "A-1". In a file without page labels, it is the page number, starting at 1.

### Page boxes

```kotlin
// The whole sheet, in points (1/72 inch)
val mediaBox = page.mediaBox

// The part that a viewer shows (the media box when the file sets none)
val cropBox = page.cropBox

// The part to print with bleed (the crop box when the file sets none)
val bleedBox = page.bleedBox

// The finished page after the trim (the crop box when the file sets none)
val trimBox = page.trimBox

// The meaningful content (the crop box when the file sets none)
val artBox = page.artBox
```

Each box is a `KiteRectangle` with `left`, `bottom`, `right`, `top`, `width` and `height`. A page with no usable media box gets a US Letter box (612 by 792 points) and a warning in the log, as in MuPDF.

A very large page, such as an architectural drawing or a poster, can set a user unit. `page.userUnit` is 1.0 by default, which means 1/72 inch for each unit. A value of 2.0 makes each unit 2/72 inch:

```kotlin
val widthInInches = page.width * page.userUnit / 72.0
```

## Text

### Plain text

`extractText()` gives the text of a page as one string:

```kotlin
val text = page.extractText()
```

KitePDF decodes each string through the font that draws it. It uses the font's `/Encoding`, its `/Differences` and its `/ToUnicode` CMap, which covers composite Type 0 fonts and the predefined CJK CMaps. A CJK font without `/ToUnicode` finds its text through its CMap or its Adobe character collection, as ISO 32000-1, 9.10.2 describes. For text with no Unicode mapping at all (such as some symbol fonts), KitePDF gives its best reading of the bytes.

The text of a form XObject comes in its place on the page. A marked-content sequence with `/ActualText` gives that text instead of the text inside it.

PDF has no lines, only positions. KitePDF adds a line break where the text moves to a new line, so the line breaks are an approximation.

### Structured text

`structuredText` gives the text with its position, which you need for a selection, a highlight or a search of one area:

```kotlin
for (block in page.structuredText.blocks) {
    for (line in block.lines) {
        for (span in line.spans) {
            println("${span.text} at ${span.origin}, ${span.fontSize} pt")
        }
    }
}
```

- A span is a run of glyphs with the same font, size and baseline.
- A line is a group of spans whose baselines are at about the same height.
- A block is a group of lines with no large gap between them, which is usually a paragraph.

| Span property | What it is |
| --- | --- |
| `text` | The decoded text |
| `fontSpec` | The family, weight and style of the font |
| `fontSize` | The size in points as drawn: the `Tf` size times the scale of the text matrix |
| `origin` | The start of the baseline, as (x, y) in PDF user space |
| `bounds` | An approximate box, from the baseline and an estimate of the ascender and descender |
| `charEdgePoints` | The point on the baseline where each character starts, or null |

`structuredText.plainText` joins the blocks with a blank line and the lines with a line break.

Vertical text makes columns instead of lines. A Type 0 font whose CMap has writing mode 1 (such as `Identity-V`) draws its glyphs down the page, as ISO 32000-1, 9.7.4.3 describes. Each column is a `PdfTextLine` whose spans run from top to bottom, and the columns of a block come from right to left, in the order that vertical CJK text reads.

`page.textContent()` gives the same text as a `KiteStructuredText`, the shared type that EPUB pages also give. Its coordinates are in display space: points from the top-left corner of the page as it shows, with y going down. Search, selection and the Compose viewer use this form. A vertical line has `vertical = true` there, so a highlight follows the column.

## Search

```kotlin
import io.github.yuroyami.kitepdf.text.search

// Every hit in the document, one page at a time
for (hit in doc.search("invoice")) {
    println("Page ${hit.pageIndex + 1}: ${hit.text}")
}

// The hits on one page, with case
val hits = doc.pages[0].search("Total", ignoreCase = false)
```

A hit has one rectangle (`quads`) for each line that it touches, in display space. A match can go across a line break inside a block, where the break counts as one space. After a hyphen at the end of a line, the break counts as nothing and the hyphen drops out. A match never goes from one block into the next.

`doc.search` is a lazy `Sequence`, so a UI can show the first hits while KitePDF still reads later pages. To paint the hits in the viewer, see [Highlights](compose-viewer.md#highlights).

## Metadata

`doc.info` is the `/Info` dictionary:

```kotlin
val info = doc.info
println("Title: ${info.title}")
println("Author: ${info.author}")
println("Created: ${info.creationDate}")
```

`PdfDocumentInfo` has `title`, `author`, `subject`, `keywords`, `creator`, `producer`, `creationDate`, `modDate`, `trapped` and `custom` (the entries that the standard does not name).

`doc.xmpMetadataXml` gives the XMP packet as a string, and `doc.xmp` reads its common Dublin Core, Adobe PDF and XMP Basic properties:

```kotlin
val xmp = doc.xmp
if (xmp != null) {
    println("Creator tool: ${xmp.creatorTool}")
    println("Created ${xmp.createDate}, modified ${xmp.modifyDate}")
}
```

Both are null when the file has no XMP. PDF 2.0 prefers XMP, but most files still have an `/Info` dictionary, so read both when you need all the metadata.

## Bookmarks

`doc.outline` is the outline that every format gives, as `KiteOutlineItem` entries. Each entry has a `title`, its `children`, a `pageIndex` and a `target` that the viewer can scroll to:

```kotlin
for (item in doc.outline) {
    println("${item.title} goes to page ${item.pageIndex}")
}
```

For the details that only PDF has, read `doc.bookmarks`:

```kotlin
for (bookmark in doc.bookmarks) {
    val destination = doc.resolveDestination(bookmark.rawDestination)
    val view = destination?.view
    if (view is PdfDestination.ViewFit.XYZ) {
        println("${bookmark.title}: page ${destination.pageIndex}, at (${view.left}, ${view.top})")
    }
}
```

`PdfOutline` has `title`, `children`, `action`, `rawDestination`, `count`, `isOpen`, `italic`, `bold` and `color`. A negative `count` means that the entry starts closed. `resolveDestination` turns a raw destination (an array, a name or a dictionary) into a page index and a view, or gives null when it cannot.

## Annotations

```kotlin
for (annotation in page.annotations) {
    when (annotation.subtype) {
        PdfAnnotation.Subtype.Link -> println("Link to ${annotation.uri}")
        PdfAnnotation.Subtype.Highlight -> println("Highlight: ${annotation.contents}")
        else -> println("${annotation.subtype} at ${annotation.rect}")
    }
}
```

`PdfAnnotation.Subtype` has one value for each annotation type of ISO 32000-1 (such as `Link`, `Highlight`, `Text`, `Ink` and `Widget`), and `Other` for any other type.

Each `PdfAnnotation` has its `subtype`, its `rect` in page space, its `contents`, `color`, `uri`, `action` and `rawDestination`, and its appearance stream. It also has the geometry of its type (`quadPoints`, `inkLists` or `vertices`), its border, and its `flags` with `isHidden`, `isInvisible` and `isNoZoom`. `raw` gives the annotation dictionary for any other entry.

A media annotation (`Screen`, `Movie` or `RichMedia`) draws its own appearance, which is usually a poster image. KitePDF does not play the media.

## Form fields

```kotlin
for (field in doc.formFields) {
    println("${field.fullyQualifiedName}: ${field.value} (${field.type})")
}

// A field by its full name
val field = doc.formField("employee.name")
if (field != null && !field.isReadOnly) {
    println("Value: ${field.value}")
}
```

`type` is `Text`, `Button`, `Choice`, `Signature` or `Unknown`. A `PdfFormField` also has these properties:

| Property | What it is |
| --- | --- |
| `fullyQualifiedName` | The full name with its parents, such as `parent.child.name` |
| `partialName` | The field's own name, without its parents |
| `value` and `defaultValue` | The current value and the value after a reset |
| `choiceOptions` | The options of a choice field, each with its index, export value and label |
| `flags` | The field flags, also as `isReadOnly`, `isMultiline` and `isRichText` |
| `maxLength`, `quadding`, `defaultAppearance` | The text limit, the alignment (0 left, 1 center, 2 right) and the `/DA` string |
| `tooltip` | The `/TU` text, which a screen reader reads |
| `widgets` | Each place where the field draws. A radio group has one widget for each button, and a field on several pages has one for each page. |
| `additionalActions` | The scripts of the field, such as `keystroke`, `calculate` and `mouseDown`, see [JavaScript](javascript.md) |
| `fieldReference` and `widgetReference` | The object references that `PdfEditor` uses |

Some files put their widgets on the page and never build the `/AcroForm` field tree. KitePDF reads those widgets too, as Chrome does, so `formFields` is not empty for such a file.

A field with no appearance of its own draws one from its entries. A multi-line field wraps its value. A rich text field draws its rich value (paragraphs and spans with their font family, size, weight, style, color, alignment and underline) in the standard fonts, with `/DS` as the default style.

To fill a field, use the editor:

```kotlin
val editor = doc.edit()
editor.setTextFieldValue(doc.formField("employee.name")!!, "Jane Doe")
val filled: ByteArray = editor.saveIncremental()
```

[Editing and redaction](editing.md) covers every kind of field. For a form that the reader fills in the viewer, with its scripts, see [JavaScript](javascript.md).

## Signatures

`doc.signatures` lists the signed signature fields of a document. `validate` checks one signature against the file:

```kotlin
// DER certificates that your app trusts
val anchors = listOf(rootCertificateDer)

for (signature in doc.signatures) {
    val result = signature.validate(anchors)
    println("${signature.field.fullyQualifiedName}: ${result.status}")
    println("Signed by ${result.signer?.commonName}")
    if (result.isModifiedAfterSigning) println("The file changed after this signature")
}
```

`validate` calculates the hash of almost the whole file, so call it off the main thread.

| Status | Meaning |
| --- | --- |
| `Valid` | The signature matches the signed bytes and the signer's certificate. |
| `DigestMismatch` | The signed bytes changed after the signature. |
| `Invalid` | The signature does not match the signer's certificate, or its signed attributes name a different certificate. |
| `Unsupported` | The signature uses an algorithm or an encoding that KitePDF does not check. |
| `Malformed` | KitePDF cannot read the byte range, the signature or its certificates. |

The result also gives:

- `signer` and `chain`: the signer's certificate, then each issuer that KitePDF finds in the signature, in the document security store (`/DSS`) or in your trust anchors. KitePDF checks the signature of each link in the chain.
- `isTrusted`: true when the status is `Valid` and the chain has one of your trust anchors.
- `isModifiedAfterSigning`: true when the file has bytes that the signature does not cover. Usually a revision came after the signature. That can be a permitted change, such as a second signature or a filled field.
- `signedTime`: the time in the signed data. The signer gives it, or for a document timestamp, the timestamp authority gives it.

!!! warning
    KitePDF does not check the validity dates of the certificates. `notBefore` and `notAfter` give the dates, so check them yourself.

Each certificate of the chain has a `revocation`: `Good`, `Revoked` (with `revokedAt`) or `Unknown`. KitePDF reads the CRLs and OCSP responses that the document has: in its document security store, in the revocation field of the signature, and in Acrobat's `adbe-revocationInfoArchival` attribute. You can add data that you got yourself:

```kotlin
val result = signature.validate(anchors, revocationData = listOf(crlDer, ocspResponseDer))
if (result.signer?.revocation == PdfRevocation.Revoked) {
    println("Revoked on ${result.signer?.revokedAt}")
}
```

KitePDF uses only data that the certificate's issuer signed, or that an OCSP responder signed for the issuer. A root certificate has no issuer, so its revocation stays `Unknown`. KitePDF gets nothing from the network.

KitePDF checks these encodings: `adbe.pkcs7.detached`, `ETSI.CAdES.detached`, `adbe.pkcs7.sha1`, `adbe.x509.rsa_sha1` and document timestamps (`ETSI.RFC3161`). It checks RSA (PKCS #1 v1.5 and PSS) and ECDSA on the P-256, P-384 and P-521 curves, with SHA-1, SHA-256, SHA-384 and SHA-512.

The byte range must skip exactly the signature's own `/Contents` string. Content in that gap makes the signature `Malformed`. When a CAdES signature names its certificate in a signed attribute, that certificate must be the one that signed.

When a revision came after the signature, `changes` lists what it changed, and `areChangesPermitted` tells you whether every change is allowed:

```kotlin
for (change in result.changes.orEmpty()) {
    val verdict = if (change.isPermitted) "permitted" else "not permitted"
    println("${change.subject}: $verdict")
}
```

A certification signature sets what may change (`certificationLevel`, ISO 32000-1, 12.8.2.2):

- Level 1 allows no change.
- Level 2 allows filling in form fields and signing.
- Level 3 also allows annotations.

Without a certification signature, the rules of level 3 apply, as in Acrobat. A lock on the signature field (`/Lock`) removes the fields that it names from the fields that may change. The document security store may always grow, and a document timestamp may always come after, as long-term validation needs. Any other change (such as new page content or a new form field) is never permitted. `changes` is null when KitePDF cannot read the signed revision to compare it.

`name`, `reason`, `location` and `signingTime` are what the signing app wrote. When `isModifiedAfterSigning` is true, a later revision can have replaced them.

## Other document properties

```kotlin
// How the file asks a viewer to show it
val layout = doc.pageLayout
val mode = doc.pageMode
val preferences = doc.viewerPreferences

// The language of the document, as a BCP 47 tag such as "en-US", or null
val language = doc.language

// Articles: the reading order of a layout with columns
for (thread in doc.articleThreads) {
    println("${thread.title}: ${thread.beads.size} parts")
}

// Files inside the PDF
for (attachment in doc.attachments) {
    println("${attachment.filename}: ${attachment.size} bytes")
    val content: ByteArray = attachment.bytes
}
```

`doc.optionalContent` lists the layers of a document (optional content groups) with their names and their default visibility. KitePDF draws each layer as the default configuration of the file says, and you cannot change that visibility.

## Tagged PDF and reading order

A tagged PDF has a structure tree that names its headings, paragraphs, lists, tables and figures. `doc.markInfo?.marked` is true for such a file.

`readingOrder()` gives the content of a page in the order that a screen reader reads it, each item with its role. An EPUB page gives the same `KiteReadingItem`, so one loop reads both formats:

```kotlin
for (item in page.readingOrder()) {
    when (item.role) {
        KiteRole.HEADING -> speakHeading(item.text, item.headingLevel)
        // A figure gives its /Alt text
        KiteRole.IMAGE -> describe(item.text)
        else -> speak(item.text)
    }
}
```

A tagged page follows its structure tree, not the order in which its content draws:

- Each paragraph, heading, list item, table cell, caption and figure is one item.
- The role map turns a custom type into a standard one. `sourceType` keeps the type that the file names, such as `Footnote`, and `language` gives the nearest `/Lang`.
- Artifacts (such as running heads and page numbers) and content that no element names stay out.
- Content that a form XObject draws inside a tagged sequence of the page belongs to that sequence.

A page without tags gives its text blocks in layout order.

`bounds` gives the place of each item on the page, in display space. A text item has the box of its words. A figure has the `/BBox` of its layout attributes, or else the box of what it paints inside its clip. `bounds` is null when neither exists.

## Next

[Editing and redaction](editing.md) shows how to fill forms, stamp and change pages, redact content and save the result.
