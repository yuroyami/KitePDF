# Creating PDFs

`PdfBuilder` makes a new PDF in common code, so it runs on every target that KitePDF supports. It is in the `kitepdf-pdf` artifact, which the `kitepdf` artifact includes. [Getting started](getting-started.md) shows how to add it.

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont

val pdf: ByteArray = PdfBuilder()
    .setInfo(title = "My Report", author = "Kotlin App")
    .page {
        text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Hello, world!")
    }
    .build()
```

`build()` returns the whole file. Save it or send it as it is.

## Pages

Each `page` call adds one page and runs its block at once. The pages keep the order of the calls. A page is US Letter unless you pass a width and a height in points (1/72 inch):

```kotlin
PdfBuilder()
    .page {
        text(StandardFont.Helvetica, 14.0, 50.0, 750.0, "This page is US Letter")
    }
    .page(width = 595.0, height = 842.0) {
        text(StandardFont.Helvetica, 14.0, 50.0, 750.0, "This page is A4")
    }
    .build()
```

| Size | Width × height in points |
| --- | --- |
| US Letter | 612 × 792 |
| A4 | 595 × 842 |
| A5 | 420 × 595 |

### Pages drawn outside a block

The block of `page` cannot suspend. When your drawing code is a suspend function, get a content builder from `newPageContent()`, draw into it, then add it with `addPage()`:

```kotlin
suspend fun addTitlePage(builder: PdfBuilder) {
    val content = builder.newPageContent()
    val title = loadTitle()
    content.text(StandardFont.Helvetica, 14.0, 50.0, 750.0, title)
    builder.addPage(content = content)
}
```

## Coordinates

The origin (0, 0) is the bottom-left corner of the page. X grows to the right and y grows up, in points. So text drawn at y = 700 on a US Letter page has its baseline 700 points above the bottom edge, or 92 points below the top edge.

To measure from the top, as on Android or on the web, subtract from the height of the page:

```kotlin
val pageHeight = 792.0
fun fromTop(y: Double) = pageHeight - y

PdfBuilder()
    .page {
        text(StandardFont.Helvetica, 12.0, 72.0, fromTop(100.0), "100 points below the top")
    }
    .build()
```

## Metadata

`setInfo` writes the document information that most readers show in their document properties. It writes only the values that you pass:

```kotlin
PdfBuilder()
    .setInfo(
        title = "Q4 Report",
        author = "Finance Team",
        subject = "Financial Results",
        keywords = "quarterly, revenue, forecast",
        creator = "MyApp/1.0",
        producer = "KitePDF",
    )
```

## Text

`text` draws one line of text with its baseline at (x, y):

```kotlin
text(StandardFont.Helvetica, 14.0, 100.0, 700.0, "Hello, world!")
```

### Standard fonts

The 14 standard fonts need no font file, because every PDF reader has them:

| Family | `StandardFont` values |
| --- | --- |
| Helvetica | `Helvetica`, `HelveticaBold`, `HelveticaOblique`, `HelveticaBoldOblique` |
| Times | `TimesRoman`, `TimesBold`, `TimesItalic`, `TimesBoldItalic` |
| Courier | `Courier`, `CourierBold`, `CourierOblique`, `CourierBoldOblique` |
| Symbols | `Symbol`, `ZapfDingbats` |

A standard font has only the characters of Latin-1 (U+0000 to U+00FF). The builder writes a question mark for each other character. To write other scripts, [embed a font](#embedded-fonts).

!!! warning
    A character above U+007F, such as "é", can show as a different character. MuPDF and Poppler show "café" as "cafØ". Embed a font for text with accents.

### Text width

`stringWidth` measures a string in a standard font, so you can center it, fit it in a column or cut it short:

```kotlin
import io.github.yuroyami.kitepdf.writer.stringWidth

val width = StandardFont.Helvetica.stringWidth("Hello", fontSize = 14.0)
// About 31.9 points, so this x centers the word on a US Letter page
val x = (612.0 - width) / 2
```

### Text line by line

For several lines in one font, use the text operators. `nextLine` moves down by the leading that `setLeading` sets:

```kotlin
PdfBuilder()
    .page {
        beginText()
        setFont(StandardFont.TimesRoman, 18.0)
        setLeading(22.0)
        moveText(100.0, 700.0)
        showText("Line 1")
        nextLine()
        showText("Line 2")
        endText()
    }
    .build()
```

`setCharSpacing` adds space after each character. `setWordSpacing` adds space after each space character, in standard fonts only.

### Embedded fonts

Load a TrueType (`.ttf`) or OpenType CFF (`.otf`) font, then draw with it as with a standard font:

```kotlin
import io.github.yuroyami.kitepdf.writer.EmbeddedFont

val notoSans = EmbeddedFont.load(fontBytes)

val pdf = PdfBuilder()
    .page {
        text(notoSans, 16.0, 72.0, 700.0, "你好世界")
    }
    .build()
```

- The PDF gets only the glyphs that the document draws. Pass `subset = false` to embed the whole font.
- The PDF also gets a map from each glyph back to its character, so a reader can copy and search the text.
- A character that the font does not have draws as the font's missing-glyph shape.
- The builder maps each character to one glyph. It does not shape text, so Arabic letters do not join and right-to-left text runs left to right.
- `load` throws `UnsupportedOperationException` for a font that has only CFF2 outlines.

A TrueType collection (`.ttc`) gives its first font. Pass `faceIndex` for another one. `TrueTypeFont.faceCount(bytes)` tells how many fonts a collection has:

```kotlin
val mincho = EmbeddedFont.load(ttcBytes, faceIndex = 1)
```

## Shapes and colors

### Colors

Each color component goes from 0.0 to 1.0. The fill color paints areas and text. The stroke color paints lines:

```kotlin
// Red fill, blue stroke
setFillRgb(1.0, 0.0, 0.0)
setStrokeRgb(0.0, 0.0, 1.0)

// 50% gray fill, black stroke
setFillGray(0.5)
setStrokeGray(0.0)

// CMYK, for print
setFillCmyk(0.0, 0.1, 0.9, 0.0)
setStrokeCmyk(0.0, 0.0, 0.0, 1.0)
```

### Paths

Build a path with `moveTo`, `lineTo`, `rectangle` and `closePath`. Then paint it with `stroke`, `fill` or `fillAndStroke`. Each call returns the content builder, so you can chain the calls:

```kotlin
// A stroked square, 2 points wide
setLineWidth(2.0)
moveTo(100.0, 100.0)
    .lineTo(200.0, 100.0)
    .lineTo(200.0, 200.0)
    .lineTo(100.0, 200.0)
    .closePath()
    .stroke()

// A filled rectangle with an outline
setFillRgb(1.0, 1.0, 0.0)
setStrokeRgb(0.0, 0.0, 0.0)
rectangle(100.0, 100.0, 200.0, 200.0).fillAndStroke()
```

### Clipping

`clip` cuts all later drawing to the current path, by the nonzero winding rule. `clipEvenOdd` uses the even-odd rule. A clip needs a painting call after it. Call `endPath` to clip without painting the path:

```kotlin
rectangle(100.0, 100.0, 300.0, 300.0).clip().endPath()
```

Only `restore` can remove a clip.

### Save and restore

!!! tip
    Wrap a few drawing calls in `save()` and `restore()` to keep their settings to themselves. Anything you change in between (a color, a line width, a clip or a rotation) goes back to how it was after `restore()`.

    Without them, each setting stays for the rest of the page:

    - A fill color paints every later shape in that color.
    - A rotation turns everything that you draw after it.
    - A clip cuts off everything that you draw after it.

    Always use them as a pair. A `save()` without a `restore()` keeps the settings for the rest of the page. A `restore()` without a `save()` before it breaks the PDF rules.

`transform` multiplies the current matrix by `[a b c d e f]`. This draws a red square turned 45 degrees around the point (450, 300):

```kotlin
save()
transform(0.7071, 0.7071, -0.7071, 0.7071, 450.0, 300.0)
setFillRgb(1.0, 0.0, 0.0)
rectangle(0.0, 0.0, 50.0, 50.0).fill()
restore()
```

### Other operators

`raw` adds content stream operators that the builder has no method for, such as a dash pattern:

```kotlin
raw("[6 3] 0 d")
moveTo(72.0, 540.0).lineTo(523.0, 540.0).stroke()
```

!!! warning
    The builder does not check the text of `raw`. A mistake in it makes a page that readers draw wrong or refuse.

## Images

### Make an image

`PdfImage` takes 8-bit pixels, top row first, or the bytes of an encoded file:

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfImage

// 4 bytes per pixel: R, G, B, A
val logo = PdfImage.rgba(rgbaPixels, width = 100, height = 100)

// 3 bytes per pixel: R, G, B
val photo = PdfImage.rgb(rgbPixels, width = 100, height = 100)

// 1 byte per pixel
val scan = PdfImage.gray(grayPixels, width = 100, height = 100)

// The JPEG file as it is, with no new encoding
val picture = PdfImage.jpeg(jpegBytes, width = 800, height = 600)

// A JPEG 2000 file as it is
val map = PdfImage.jpx(jp2Bytes, width = 2000, height = 1500)
```

- `rgba` puts the alpha channel in a soft mask. When every pixel is opaque, the image gets no mask.
- `jpeg` and `jpx` do not read the size from the file, so pass the real size of the image. Pass `grayscale = true` to `jpeg` for a grayscale JPEG.

!!! warning
    `rgb`, `gray`, `jpeg` and `jpx` keep your array and do not copy it. The builder reads the array only when `build()` runs. Do not change or reuse the array until `build()` returns. If you decode frames into one buffer, every page shows the last frame. `rgba` copies the pixels, so you can reuse its array at once.

### Draw an image

`drawImage` fills a rectangle with the image. (x, y) is the bottom-left corner of the rectangle:

```kotlin
PdfBuilder()
    .page {
        drawImage(photo, x = 100.0, y = 500.0, width = 200.0, height = 200.0)
    }
    .build()
```

When several pages draw the same `PdfImage` object, the PDF holds the image once.

## Password protection

`encrypt` protects the new document with AES-256. The user password and the owner password both open the file. The permissions ask readers not to print, copy or change the document, but a reader can ignore them (see [Permissions](reading.md#permissions)).

!!! warning
    Pass a random source that is secure for cryptography. The builder makes the file key from it. `Random.Default` is not secure. A seeded `Random(seed)` is good only for test files.

```kotlin
import io.github.yuroyami.kitepdf.PdfPermissions
import kotlin.random.asKotlinRandom

// On the JVM and Android
val secureRandom = java.security.SecureRandom().asKotlinRandom()

val bytes = PdfBuilder()
    .page {
        text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "Confidential")
    }
    .encrypt(
        userPassword = "user-pass",
        ownerPassword = "owner-pass",
        permissions = PdfPermissions.allowAll.copy(canModifyContents = false),
        random = secureRandom,
    )
    .build()
```

## Compression

`build()` compresses the content stream of each page. `build(compress = false)` leaves the content streams as plain text, so you can read them in a text editor. Images and embedded fonts are compressed either way.

## A complete page

An invoice page with a colored header, a line and a small table:

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.File

fun main() {
    val pdf = PdfBuilder()
        .setInfo(title = "Invoice 123", author = "Example Shop", creator = "MyApp/1.0")
        .page {
            // A blue header box
            setFillRgb(0.2, 0.4, 0.8)
            rectangle(0.0, 650.0, 612.0, 142.0).fill()

            // The title in white
            setFillRgb(1.0, 1.0, 1.0)
            text(StandardFont.HelveticaBold, 36.0, 50.0, 730.0, "INVOICE")
            text(StandardFont.Helvetica, 14.0, 50.0, 690.0, "Invoice 123")

            // The customer in black
            setFillRgb(0.0, 0.0, 0.0)
            text(StandardFont.Helvetica, 12.0, 50.0, 600.0, "Bill to:")
            text(StandardFont.Helvetica, 11.0, 50.0, 580.0, "Jane Doe")

            // A gray line
            setStrokeRgb(0.8, 0.8, 0.8)
            setLineWidth(1.0)
            moveTo(50.0, 540.0).lineTo(562.0, 540.0).stroke()

            // The table header on a light gray band
            setFillRgb(0.95, 0.95, 0.95)
            rectangle(50.0, 520.0, 512.0, 20.0).fill()
            setFillRgb(0.0, 0.0, 0.0)
            text(StandardFont.HelveticaBold, 11.0, 60.0, 528.0, "Description")
            text(StandardFont.HelveticaBold, 11.0, 350.0, 528.0, "Unit price")
            text(StandardFont.HelveticaBold, 11.0, 450.0, 528.0, "Qty")
            text(StandardFont.HelveticaBold, 11.0, 500.0, 528.0, "Total")

            // One row
            text(StandardFont.Helvetica, 10.0, 60.0, 500.0, "Consulting")
            text(StandardFont.Helvetica, 10.0, 350.0, 500.0, "$150.00")
            text(StandardFont.Helvetica, 10.0, 450.0, 500.0, "8")
            text(StandardFont.Helvetica, 10.0, 500.0, 500.0, "$1,200.00")
        }
        .build()

    // JVM only. On other targets, save the bytes with the platform's file API.
    File("invoice.pdf").writeBytes(pdf)
}
```

## See also

- [Reading PDFs](reading.md)
- [Editing and redaction](editing.md)
- [Headless rendering](rendering.md), to turn a page into an image
