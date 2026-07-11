# Reading PDFs

Open, decrypt, and extract content from PDF documents. KitePDF handles encrypted PDFs, pages with advanced geometry, text extraction, bookmarks, annotations, and form fields.

## Opening a PDF

Open any PDF from a byte array:

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument

val bytes = File("example.pdf").readBytes()
val doc = PdfDocument.open(bytes)
println("${doc.pageCount} pages: PDF ${doc.version}")
```

`open` throws (`PdfFormatException`, `WrongPasswordException`) on files it
cannot read. When null-on-failure fits your call site better, use
`PdfDocument.openOrNull(bytes)`; the EPUB handler has the matching pair
(`EpubDocument.open` throws `EpubFormatException` with a message naming the
first structural failure, `EpubDocument.openOrNull` returns null instead).

### Encrypted PDFs

PDF encryption is transparent; pass the password to unlock it:

```kotlin
val doc = PdfDocument.open(bytes, "mypassword".encodeToByteArray())
```

For unencrypted PDFs, pass an empty byte array (the default):

```kotlin
val doc = PdfDocument.open(bytes)  // same as PdfDocument.open(bytes, byteArrayOf())
```

After opening, check whether the document is encrypted and authenticated:

```kotlin
val doc = PdfDocument.open(bytes, password)
if (doc.isEncrypted) println("Document is encrypted")
if (!doc.isAuthenticated) println("Wrong password or needed password was empty")
```

### Document Permissions

Most encrypted PDFs carry usage hints (print, copy, modify, etc.) set by the author. These permissions are advisory; KitePDF surfaces them so your application can choose to honour the author's intent:

```kotlin
val perms = doc.permissions
if (!perms.canCopyContents) println("Author restricts copying")
if (!perms.canPrintHighResolution) println("Low-resolution printing only")
```

Access all permissions via the `PdfPermissions` object:

- `canPrint`: printing allowed
- `canModifyContents`: editing PDF content allowed
- `canCopyContents`: copy/extract text allowed
- `canModifyAnnotations`: add/modify annotations allowed
- `canFillForms`: fill form fields allowed
- `canExtractForAccessibility`: extract for accessibility tools allowed
- `canAssembleDocument`: insert/delete/rotate pages allowed
- `canPrintHighResolution`: high-resolution printing allowed

For unencrypted documents, `permissions` always reports allow-all.

## Pages

Iterate the page list or access a specific page by index:

```kotlin
val doc = PdfDocument.open(bytes)
val page = doc.pages[0]
println("${page.width} x ${page.height} pt")
println("Rotation: ${page.rotation}°")

for (page in doc.pages) {
    println("Page ${page.index + 1}: ${page.label}")
}
```

### Page Boxes

PDF defines several rectangular regions on a page. The most common are:

```kotlin
val page = doc.pages[0]
val mediaBox = page.mediaBox       // Full page boundary in user-space units (1/72 inch)
val cropBox = page.cropBox         // Region to display (defaults to mediaBox)
val bleedBox = page.bleedBox       // Region for printing with bleed (defaults to cropBox)
val trimBox = page.trimBox         // Trim boundary after printing (defaults to cropBox)
val artBox = page.artBox           // Meaningful content extent (defaults to cropBox)

println("Media: ${mediaBox.width} x ${mediaBox.height} pt")
```

Each rectangle has `.left`, `.bottom`, `.right`, `.top`, `.width`, `.height` properties.

### User Unit

Large pages (architectural drawings, posters) may use a scaled user unit:

```kotlin
val userUnit = page.userUnit      // Default 1.0 (each unit = 1/72 inch)
                                   // 2.0 means each unit = 2/72 inch (page is twice as large)
val effectiveWidth = page.width * userUnit
```

## Text Extraction

### Simple Text

Extract all text from a page as a plain string:

```kotlin
val text = page.extractText()
```

This walks the content stream for text-showing operators (`Tj`, `TJ`, `'`, `"`), decodes strings using PDFDocEncoding (or UTF-16BE if a BOM is present), and joins them with line breaks heuristically inserted on text-positioning commands.

!!! note
    Extraction decodes each show string through the font that draws it, resolving `/Encoding` (with `/Differences`) and the `/ToUnicode` CMap, including composite Type0/CID fonts and the predefined CJK CMaps. Text without any Unicode mapping (some symbolic subsets) falls back to a best-effort byte interpretation.

### Structured Text

When you need geometry alongside text (for highlighting, search, selection), use structured text:

```kotlin
val structured = page.structuredText
for (block in structured.blocks) {
    for (line in block.lines) {
        for (span in line.spans) {
            println("${span.text} at (${span.origin.first}, ${span.origin.second})")
            println("  Font: ${span.fontSpec}, size: ${span.fontSize}pt")
            println("  Bounds: ${span.bounds}")
        }
    }
}
```

Structured text clusters character runs into:
- **Spans**: glyphs sharing font, size, and baseline
- **Lines**: spans whose Y origins cluster within tolerance
- **Blocks**: lines grouped by vertical spacing (paragraph-like chunks)

Each `PdfTextSpan` carries:
- `text`: the decoded string
- `fontSpec`: family/weight/style of the font used
- `fontSize`: point size
- `origin`: baseline position in PDF user units (x, y) as a `Pair<Double, Double>`
- `bounds`: bounding rectangle (heuristic: ascender + descender estimates)

Convert to plain text (with paragraph breaks) via:

```kotlin
val plainText = structured.plainText  // "\n\n" between blocks, "\n" between lines
```

## Document Metadata

### Info Dictionary

Access the document's metadata (title, author, dates, etc.):

```kotlin
val info = doc.info
println("Title: ${info.title}")
println("Author: ${info.author}")
println("Created: ${info.creationDate}")
println("Custom fields: ${info.custom}")
```

Fields in `PdfDocumentInfo`:

- `title`, `author`, `subject`, `keywords`: strings (nullable)
- `creator`, `producer`: application names (nullable)
- `creationDate`, `modDate`: `PdfDate?` (nullable)
- `trapped`: enum: `True`, `False`, or `Unknown`
- `custom`: map of non-standard string entries

!!! tip
    PDF 2.0 prefers XMP metadata; most PDFs still use the `/Info` dict. For raw XMP, see [XMP Metadata](#xmp-metadata).

### XMP Metadata

Raw XMP as UTF-8 XML:

```kotlin
if (doc.xmpMetadataXml != null) {
    println(doc.xmpMetadataXml)
}
```

Parsed XMP (Dublin Core + Adobe PDF + XMP-basic properties):

```kotlin
val xmp = doc.xmp  // PdfXmpMetadata? (null if no XMP stream)
if (xmp != null) {
    println("Creator tool: ${xmp.creatorTool}")
    println("Dates: created ${xmp.createDate}, modified ${xmp.modifyDate}")
}
```

Falls back to the `/Info` dict when XMP is absent.

## Outline / Bookmarks

Navigate the document's bookmark tree:

```kotlin
for (outline in doc.outlines) {
    println("${outline.title} (count: ${outline.count})")
    for (child in outline.children) {
        println("  - ${child.title}")
    }
}
```

Each `PdfOutline` entry carries:

- `title`: bookmark label
- `children`: nested bookmarks
- `rawDestination`: unresolved destination (use `doc.resolveDestination()` to convert)
- `action`: typed action (GoTo, URI, Launch, JavaScript, etc.)
- `count`: visible descendant count; negative if closed by default
- `isOpen`: true if the outline is expanded by default
- `italic`, `bold`: style flags
- `color`: RGB colour hint (nullable)

To resolve a bookmark destination to a page index:

```kotlin
for (outline in doc.outlines) {
    val dest = doc.resolveDestination(outline.rawDestination)
    val view = dest?.view
    if (view is PdfDestination.ViewFit.XYZ) {
        println("Jumps to page ${dest.pageIndex}, position (${view.left}, ${view.top})")
    }
}
```

## Annotations

Annotations are interactive elements: links, highlights, notes, etc.

```kotlin
val page = doc.pages[0]
for (annot in page.annotations) {
    println("${annot.subtype} at ${annot.rect}")
    when (annot.subtype) {
        PdfAnnotation.Subtype.Link -> {
            println("  URL: ${annot.uri}")
            println("  Destination: ${annot.rawDestination}")
        }
        PdfAnnotation.Subtype.Highlight -> println("  Highlight: ${annot.contents}")
        else -> println("  Contents: ${annot.contents}")
    }
}
```

Supported subtypes:
- **Link**: URL or named-destination hyperlink
- **Highlight**, **Underline**, **StrikeOut**, **Squiggly**: text markups
- **Text**: sticky-note popup
- **FreeText**, **Square**, **Circle**, **Polygon**, **PolyLine**, **Ink**: drawing/text markup
- **Stamp**, **Caret**, **FileAttachment**, **Sound**, **Movie**: metadata or embedded content
- **Widget**: form field annotation
- **Other**: for unsupported annotation types

Each `PdfAnnotation` carries:

- `subtype`: annotation type
- `rect`: placement rectangle (left, bottom, right, top)
- `contents`: text (e.g. note text or link label)
- `color`: border/highlight colour (nullable)
- `uri`: URL (Link annotations only)
- `action`: typed action (GoTo, URI, Launch, etc.)
- `rawDestination`: unresolved destination (Link annotations; resolve with `doc.resolveDestination()`)
- `appearanceStream`: optional visual representation (Form XObject stream)
- `raw`: the underlying PDF dictionary for callers that need fields we didn't extract

## Form Fields

Interactive form fields (text boxes, buttons, dropdowns, signatures):

```kotlin
for (field in doc.formFields) {
    println("${field.fullyQualifiedName}: ${field.value} (${field.type})")
}

// Look up a field by name
val field = doc.formField("employee.name")
if (field != null) {
    println("Type: ${field.type}")
    println("Value: ${field.value}")
    println("Read-only: ${field.isReadOnly}")
}
```

Each `PdfFormField` carries:

- `fullyQualifiedName`: dot-separated path (e.g. "parent.child.fieldName")
- `partialName`: this field's own name (parent names stripped), nullable
- `type`: `Text`, `Button`, `Choice`, `Signature`, or `Unknown`
- `value`: the current value (text for text fields, selected name for buttons/dropdowns), nullable
- `defaultAppearance`: variable-text appearance string (`/DA`), nullable
- `flags`: bit flags (read-only, multi-line, etc.)
- `isReadOnly`: true if the field cannot be edited
- `isMultiline`: true for multi-line text fields
- `quadding`: text alignment: 0 = left, 1 = centre, 2 = right
- `rect`: widget placement rectangle (nullable)
- `fieldReference`, `widgetReference`: indirect references (for editing via `PdfEditor`)

To fill form fields, use the editor (see the [editing guide](editing.md)):

```kotlin
val editor = doc.edit()
val field = doc.formField("employee.name")!!
editor.setTextFieldValue(field, "Jane Doe")
val updated = editor.saveIncremental()
```

## Advanced

### Page Labels

Access formatted page labels (e.g. "i", "ii", "1", "A-1"):

```kotlin
for (page in doc.pages) {
    println("Label: ${page.label}")  // "1", "i", "A-1", etc.
}
```

Falls back to one-based page index if `/PageLabels` is not defined.

### Viewer Preferences

Access document viewer hints (single page / two-page layout, hide menus, etc.):

```kotlin
val prefs = doc.viewerPreferences
val layout = doc.pageLayout        // SinglePage, TwoPageLeft, TwoPageRight, etc.
val mode = doc.pageMode            // UseNone, UseOutlines, UseThumbs, UseOC, etc.
```

### Article Threads

Reading-order sequences for multi-column layouts:

```kotlin
for (thread in doc.articleThreads) {
    println("Thread with ${thread.beads.size} beads")
}
```

### Attachments

Embedded files supplementary to the document:

```kotlin
for (attachment in doc.attachments) {
    println("${attachment.filename}: ${attachment.size} bytes")
}
```

### Language

Document language (BCP 47 tag):

```kotlin
println("Language: ${doc.language}")  // "en-US", "fr-CA", null
```

### Optional Content / Layers

Visibility configuration for optional content (layers):

```kotlin
if (doc.optionalContent != null) {
    println("This PDF has layers")
}
```

### Tagged PDF / Accessibility

Metadata for tagged PDFs (content structure for accessibility):

```kotlin
if (doc.markInfo != null) {
    println("This PDF is tagged for accessibility")
}
```

## Installation

Add KitePDF to your `build.gradle.kts`:

=== "Kotlin (KMP)"

    ```kotlin
    dependencies {
        commonMainImplementation("io.github.yuroyami:kitepdf:0.2.0")
    }
    ```

=== "Android / JVM"

    ```kotlin
    dependencies {
        implementation("io.github.yuroyami:kitepdf:0.2.0")
    }
    ```

Next: [Editing PDFs](editing.md): modify documents, fill forms, and save changes.
