# Creating PDFs from scratch

Use `PdfBuilder` to create a complete PDF from nothing in pure Kotlin, on any target platform (JVM, Android, iOS, JavaScript, etc.).

## Basic workflow

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont

val pdf = PdfBuilder()
    .setInfo(title = "My Report", author = "Kotlin App")
    .page { 
        text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Hello, world!")
    }
    .build()
// pdf is a ByteArray; write it to disk or send over the network
```

The builder handles the full PDF structure: object graph (catalog, page tree, content streams), FlateDecode compression, font and image resources, and serialization to a valid PDF 1.7 file.

## Installation

Add the `kitepdf` artifact to your build:

=== "Kotlin Multiplatform"

    ```kotlin
    dependencies {
        commonMainImplementation("io.github.yuroyami:kitepdf:0.2.0")
    }
    ```

=== "Android / JVM"

    ```gradle
    dependencies {
        implementation("io.github.yuroyami:kitepdf:0.2.0")
    }
    ```

## Adding pages

### Simple page with a block

```kotlin
PdfBuilder()
    .page { 
        text(StandardFont.Helvetica, 14.0, 50.0, 750.0, "This is page 1")
    }
    .page(width = 595.0, height = 842.0) { 
        text(StandardFont.Helvetica, 14.0, 50.0, 750.0, "This is A4")
    }
    .build()
```

By default, pages are US Letter size (612 × 792 points). You can customize width and height; common sizes are:

- **US Letter**: 612 × 792 pt
- **A4**: 595 × 842 pt
- **A5**: 420 × 595 pt

Pages are added in call order and appear in the final PDF in that order.

### Imperative page building

If you need to drive page layout from async code, use `newPageContent()` and `addPage()`:

```kotlin
val builder = PdfBuilder()
val content = builder.newPageContent()
// … draw into content (fonts and images registered here)
content.text(StandardFont.Helvetica, 14.0, 50.0, 750.0, "Async layout")
builder.addPage(612.0, 792.0, content)
builder.build()
```

Both `newPageContent()` and `addPage()` register fonts and images with the builder when they're first used.

## Document metadata

Set the PDF's `/Info` dictionary with `setInfo()`:

```kotlin
PdfBuilder()
    .setInfo(
        title = "Q4 Report",
        author = "Finance Team",
        subject = "Financial Results",
        keywords = "quarterly, revenue, forecast",
        creator = "MyApp/1.0",
        producer = "KitePDF/0.1.0"
    )
    .page { /* … */ }
    .build()
```

Only non-null fields are written. Metadata appears in the PDF's document properties panel in most readers.

## Available fonts

KitePDF uses the 14 standard Type 1 fonts, which every conforming PDF reader provides without embedding. No font files are needed.

- **Helvetica** (4 variants):
  - `StandardFont.Helvetica`
  - `StandardFont.HelveticaBold`
  - `StandardFont.HelveticaOblique`
  - `StandardFont.HelveticaBoldOblique`

- **Times Roman** (4 variants):
  - `StandardFont.TimesRoman`
  - `StandardFont.TimesBold`
  - `StandardFont.TimesItalic`
  - `StandardFont.TimesBoldItalic`

- **Courier** (4 variants):
  - `StandardFont.Courier`
  - `StandardFont.CourierBold`
  - `StandardFont.CourierOblique`
  - `StandardFont.CourierBoldOblique`

- **Symbolic** (2 fonts):
  - `StandardFont.Symbol`
  - `StandardFont.ZapfDingbats`

### Computing text width

To lay out or truncate text, compute its width in advance:

```kotlin
import io.github.yuroyami.kitepdf.writer.stringWidth

val width = StandardFont.Helvetica.stringWidth("Hello", fontSize = 14.0)
// width ≈ 41 points (in text-space units)
```

Use this to center text, fit it in a column, or append an ellipsis when it overflows.

## Drawing text

The simplest form:

```kotlin
content.text(StandardFont.Helvetica, 14.0, 100.0, 700.0, "Hello, world!")
```

This draws a single line of text with its baseline at coordinates (100, 700) in user space. The bottom-left corner of the page is the origin; Y increases upward.

For finer control, use the text operators:

```kotlin
content.beginText()
content.setFont(StandardFont.TimesRoman, 18.0)
content.moveText(100.0, 700.0)      // move baseline to (100, 700)
content.showText("Line 1")
content.nextLine()                   // move to next line (uses leading set by setLeading)
content.showText("Line 2")
content.endText()
```

!!! note
    Text with code points above U+00FF (outside Latin-1) will substitute `?` because standard fonts are single-byte encoded. Arbitrary Unicode requires an embedded font (a future feature).

## Colors and graphics

### Fill and stroke colors

Set RGB colors for fills (areas, text fill) and strokes (outlines):

```kotlin
content.setFillRgb(1.0, 0.0, 0.0)      // Red fill
content.setStrokeRgb(0.0, 0.0, 1.0)    // Blue stroke
content.setLineWidth(2.0)               // Stroke width in points
content.rectangle(50.0, 50.0, 200.0, 100.0)
content.fill()                          // Fill with red
```

For grayscale:

```kotlin
content.setFillGray(0.5)    // 50% gray
content.setStrokeGray(0.0)  // Black
```

### Paths and shapes

Build a path from basic operations and paint it:

```kotlin
// Draw and stroke an outline
content.moveTo(100.0, 100.0)
content.lineTo(200.0, 100.0)
content.lineTo(200.0, 200.0)
content.lineTo(100.0, 200.0)
content.closePath()           // Close the path (line back to start)
content.stroke()              // Stroke with current color and line width

// Draw and fill a rectangle
content.setFillRgb(0.2, 0.8, 0.2)
content.rectangle(50.0, 50.0, 150.0, 100.0)
content.fill()

// Fill and stroke in one operation
content.setFillRgb(1.0, 1.0, 0.0)
content.setStrokeRgb(0.0, 0.0, 0.0)
content.rectangle(100.0, 100.0, 200.0, 200.0)
content.fillAndStroke()
```

### Clipping

Clip to the current path using the nonzero winding rule:

```kotlin
content.rectangle(100.0, 100.0, 300.0, 300.0)
content.clip()
content.endPath()           // Painting operator required after clip()
// All subsequent drawing is clipped to the rectangle
```

Or use the even-odd rule:

```kotlin
content.rectangle(100.0, 100.0, 300.0, 300.0)
content.clipEvenOdd()
content.endPath()
```

## Embedding images

### Image formats

Create images from raw pixel data or JPEG:

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfImage

// 8-bit RGBA (4 bytes per pixel: R, G, B, A)
val imageWithAlpha = PdfImage.rgba(pixelBytes, width = 100, height = 100)

// 8-bit RGB (3 bytes per pixel: R, G, B)
val imageRgb = PdfImage.rgb(pixelBytes, width = 100, height = 100)

// 8-bit grayscale (1 byte per pixel)
val imageGray = PdfImage.gray(pixelBytes, width = 100, height = 100)

// JPEG passthrough (no re-encoding)
val imageJpeg = PdfImage.jpeg(jpegBytes, width = 800, height = 600)
```

RGBA images automatically separate the alpha channel into a PDF `/SMask` for transparency. If all pixels are fully opaque, the mask is omitted.

### Drawing images

Only available inside `page { }` blocks:

```kotlin
PdfBuilder()
    .page {
        val image = PdfImage.rgb(pixels, 100, 100)
        drawImage(image, x = 100.0, y = 500.0, width = 200.0, height = 200.0)
    }
    .build()
```

The image fills the rectangle from (x, y) to (x + width, y + height). Coordinates follow PDF's bottom-left origin and Y-up convention.

!!! tip
    Images are deduplicated by identity. If you pass the same `PdfImage` instance to multiple pages, it's embedded once and referenced by all pages.

## Coordinate system

PDF uses a **left-hand coordinate system with origin at the bottom-left**:

- **X-axis** runs left-to-right.
- **Y-axis** runs bottom-to-top (opposite of many graphics APIs).
- **(0, 0)** is the bottom-left corner of the page.

When drawing text at `(100, 700)` on a US Letter page (792 pt tall), the text baseline is 92 points above the bottom edge.

If you're used to top-left origins (like Android Canvas or web DOM), remember to flip Y:

```kotlin
// Convert from top-left (like Android) to bottom-left (PDF)
val pageHeight = 792.0
val textY_pdf = pageHeight - textY_topLeft
content.text(font, size, textX, textY_pdf, "Text")
```

## Complete example

A titled page with a colored box and some text:

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont

fun main() {
    val pdf = PdfBuilder()
        .setInfo(
            title = "Invoice #123",
            author = "E-Commerce System",
            creator = "MyApp/1.0"
        )
        .page {
            // Draw a blue header box
            setFillRgb(0.2, 0.4, 0.8)
            rectangle(0.0, 650.0, 612.0, 142.0)
            fill()

            // Title in white
            setFillRgb(1.0, 1.0, 1.0)
            text(StandardFont.HelveticaBold, 36.0, 50.0, 730.0, "INVOICE")
            text(StandardFont.Helvetica, 14.0, 50.0, 690.0, "Invoice #123-2025")

            // Content in black
            setFillRgb(0.0, 0.0, 0.0)
            text(StandardFont.Helvetica, 12.0, 50.0, 600.0, "Bill To:")
            text(StandardFont.Helvetica, 11.0, 50.0, 580.0, "John Doe")
            text(StandardFont.Helvetica, 11.0, 50.0, 565.0, "john@example.com")

            // Horizontal line
            setStrokeRgb(0.8, 0.8, 0.8)
            setLineWidth(1.0)
            moveTo(50.0, 540.0)
            lineTo(562.0, 540.0)
            stroke()

            // Table header with light gray background
            setFillRgb(0.95, 0.95, 0.95)
            rectangle(50.0, 520.0, 512.0, 20.0)
            fill()

            setFillRgb(0.0, 0.0, 0.0)
            text(StandardFont.HelveticaBold, 11.0, 60.0, 528.0, "Description")
            text(StandardFont.HelveticaBold, 11.0, 350.0, 528.0, "Unit Price")
            text(StandardFont.HelveticaBold, 11.0, 450.0, 528.0, "Qty")
            text(StandardFont.HelveticaBold, 11.0, 500.0, 528.0, "Total")

            // Item rows
            text(StandardFont.Helvetica, 10.0, 60.0, 500.0, "Consulting Services")
            text(StandardFont.Helvetica, 10.0, 350.0, 500.0, "$150.00")
            text(StandardFont.Helvetica, 10.0, 450.0, 500.0, "8")
            text(StandardFont.Helvetica, 10.0, 500.0, 500.0, "$1,200.00")
        }
        .build()

    // Write to file (JVM example; use platform APIs on other targets)
    java.nio.file.Files.write(
        java.nio.file.Paths.get("invoice.pdf"),
        pdf
    )
}
```

## Compression and serialization

By default, `build()` applies FlateDecode compression to all content streams:

```kotlin
val compressedPdf = PdfBuilder().page { /* … */ }.build(compress = true)   // default
val uncompressedPdf = PdfBuilder().page { /* … */ }.build(compress = false)
```

Compressed PDFs are smaller (~40-60% for typical documents) and equally valid. Uncompressed PDFs are useful for debugging or manual inspection.

## Rendering pages to images

To convert PDF pages to rasterized images (PNG, Bitmap, etc.), see the [rendering guide](rendering.md). The `kitepdf-native-renderer` or `kitepdf-skia-renderer` modules provide platform-specific and unified APIs.

## See also

- [Reading and extracting text](reading.md)
- [Editing existing PDFs](editing.md)
- [Rendering pages to images](rendering.md)
