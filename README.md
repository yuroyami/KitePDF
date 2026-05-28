# KitePDF

![status](https://img.shields.io/badge/status-experimental-orange)
![kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)
![multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)
![core deps](https://img.shields.io/badge/core%20deps-kotlin--stdlib%20only-2EA44F)
![license](https://img.shields.io/badge/license-Apache--2.0-blue)

A Kotlin Multiplatform library for **reading, rendering, editing and writing PDFs**.

- **Pure-Kotlin core** — no platform PDF engine under the hood. The same code paths run on JVM, Android, iOS and Browser/JS.
- **Zero runtime deps** for the core. The `:kitepdf` artifact only requires `kotlin-stdlib`.
- **Drop-in Compose UI** — render a page with one `@Composable`.
- **Headless rasterizers** for server-side / CI use cases (PDF → PNG / JPEG).

## Modules

KitePDF is published as four artifacts so you only pull in what you need.

| Artifact | What it gives you | Depends on |
|---|---|---|
| `com.yuroyami.kitepdf:kitepdf` | Core: parse / decrypt / render / extract text / edit / write. Headless. | `kotlin-stdlib` |
| `com.yuroyami.kitepdf:kitepdf-compose` | `@Composable PdfPageView(page)` for Compose Multiplatform. | core + Compose |
| `com.yuroyami.kitepdf:kitepdf-skia` | `PdfPageRasterizer` — render a page to a Skia `Image` or PNG bytes (JVM). | core + Skiko |
| `com.yuroyami.kitepdf:kitepdf-native-renderer` | Per-platform raster backends: AWT (JVM), `android.graphics.Canvas` (Android), CoreGraphics (iOS), Canvas2D (JS). | core |

```kotlin
// Gradle (Kotlin DSL)
dependencies {
    implementation("com.yuroyami.kitepdf:kitepdf:0.0.1")
    // optional:
    implementation("com.yuroyami.kitepdf:kitepdf-compose:0.0.1")
}
```

## Quick start

### Read a PDF

```kotlin
import com.yuroyami.kitepdf.PdfDocument

val doc = PdfDocument.open(pdfBytes)
println("${doc.pageCount} pages — PDF ${doc.version}")
println(doc.pages[0].extractText())
```

### Open an encrypted PDF

```kotlin
val doc = PdfDocument.open(pdfBytes, password = "secret".encodeToByteArray())
if (!doc.isAuthenticated) error("Wrong password")
```

### Render a page (Compose Multiplatform)

```kotlin
@Composable
fun MyPdfScreen(pdfBytes: ByteArray) {
    val doc = remember(pdfBytes) { PdfDocument.open(pdfBytes) }
    PdfPageView(page = doc.pages[0], modifier = Modifier.fillMaxWidth())
}
```

### Render a page to PNG (server-side, no UI)

```kotlin
import com.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer

val png: ByteArray = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
File("page1.png").writeBytes(png)
```

### Edit an existing PDF (incremental update)

`edit()` opens a writer that **appends** changes to the original bytes — the
original is preserved verbatim, which is also what makes this the right
foundation for digital signing later on.

```kotlin
val editor = doc.edit()

// Change metadata
editor.setInfo(title = "Reviewed", author = "Alice")

// Fill a form field
val nameField = doc.formField("ApplicantName")!!
editor.setTextFieldValue(nameField, "Jane Doe")

// Overlay a watermark
editor.stampPage(doc.pages[0]) {
    setFillRgb(0.8, 0.1, 0.1)
    text(StandardFont.HelveticaBold, 48.0, x = 120.0, y = 400.0, "DRAFT")
}

val updatedBytes: ByteArray = editor.saveIncremental()
```

### Redact a region (true content removal)

Unlike a black box drawn on top, this removes the underlying text/image bytes
from the file so they cannot be extracted or recovered.

```kotlin
editor.redactRegion(doc.pages[0], Rectangle(left = 72.0, bottom = 700.0, right = 320.0, top = 720.0))
val safeBytes = editor.saveRewritten()   // full rewrite, original bytes dropped
```

### Build a new PDF from scratch

```kotlin
import com.yuroyami.kitepdf.writer.PdfBuilder
import com.yuroyami.kitepdf.writer.StandardFont

val bytes = PdfBuilder()
    .setInfo(title = "Hello", author = "KitePDF")
    .page(width = 612.0, height = 792.0) {
        text(StandardFont.HelveticaBold, size = 24.0, x = 72.0, y = 720.0, "Hello, world!")
        setFillRgb(0.9, 0.95, 1.0); rectangle(72.0, 600.0, 200.0, 80.0); fill()
    }
    .build()
```

## Feature matrix

Legend: ✅ done · 🟡 partial · ❌ not yet.

### Reading

| | |
|---|---|
| Lexer / parser, classic xref + xref-streams, `/Prev` chain, object streams | ✅ |
| Filters: FlateDecode (+ PNG/TIFF predictors), ASCIIHex, ASCII85, RunLength, LZW | ✅ |
| Filters: CCITTFax, JBIG2, JPEG 2000 | ❌ |
| Encryption: Standard Security Handler V1 / V2 / V4 / V5 / V6 (RC4, AES-128, AES-256) | ✅ |
| Encryption: public-key | ❌ |
| Colour: DeviceGray / DeviceRGB / DeviceCMYK / Indexed | ✅ |
| Colour: Cal* / Lab / ICCBased | 🟡 (fall back to a sensible device family) |
| Colour: Pattern / Separation / DeviceN | ❌ |
| Fonts: Standard 14, WinAnsi/MacRoman/Standard + `/Differences`, `/ToUnicode` | ✅ |
| Fonts: TrueType (`/FontFile2`), CFF / OpenType-CFF (`/FontFile3`), Type 1 (`/FontFile`) | ✅ |
| Fonts: Type 0 composite (Identity-H/V, `/CIDToGIDMap`, `/W` widths) | ✅ |
| Fonts: Type 3 (synthetic) | ❌ |
| Images: JPEG (incl. CMYK / YCCK) | ✅ |
| Images: CCITTFax / JBIG2 / JPEG 2000 / soft masks / `/Decode` invert | 🟡 |
| Annotations (24 subtypes parsed; Link / Highlight / Underline / StrikeOut rendered; custom `/AP` rendered) | ✅ |
| AcroForm (read-only) | ✅ |
| Outlines, named destinations, page labels, page boxes, attachments, OCG, XMP metadata | ✅ |
| Transparency: ExtGState, all 16 blend modes, transparency groups | ✅ |
| Transparency: soft-mask compositing | 🟡 |

### Rendering

| | |
|---|---|
| Path / paint / text operators, full text state machine, clipping (`W` / `W*`) | ✅ |
| Per-glyph outline rendering (TrueType, CFF, Type 1) | ✅ |
| Form XObject recursion | ✅ |
| Tiling / shading patterns | ❌ |
| Backends: Compose Canvas, AWT, Skia, `android.graphics.Canvas`, CoreGraphics, Canvas2D | ✅ |

### Editing & writing

| | |
|---|---|
| Incremental update writer (append-only; original bytes preserved) | ✅ |
| Full from-scratch builder (`PdfBuilder` + `ContentStreamBuilder`) | ✅ |
| `/Info` metadata edit | ✅ |
| Form filling: text (`/Tx`) | ✅ |
| Form filling: buttons / choices (`/Btn`, `/Ch`) | ❌ |
| Watermark / overlay (`stampPage`) | ✅ |
| True region redaction (text bytes removed, intersecting images dropped) | ✅ |
| Encrypted-write, digital signing (PKCS#7) | ❌ |
| Linearization (progressive load) | ❌ |

## Architecture

```
+----------------------------------------------------------------------+
| :sample          Compose Multiplatform demo app                      |
+----------------------------------------------------------------------+
                                ↓
+----------------------------------------------------------------------+
| :kitepdf-compose / :kitepdf-skia / :kitepdf-native-renderer          |
|   Backend bindings — paint paths/glyphs/images into the host canvas. |
+----------------------------------------------------------------------+
                                ↓
+----------------------------------------------------------------------+
| :kitepdf      (pure Kotlin, kotlin-stdlib only)                      |
|                                                                      |
|   PdfDocument.open(bytes, password?) → pages / extractText / edit()  |
|   PdfPage.renderTo(canvas)                                           |
|   PdfBuilder().page { … }.build()                                    |
|                                                                      |
|   ┌─ render ──── PageRenderer · GraphicsStack · Matrix · PdfPath ─┐  |
|   ┌─ font ────── TrueType · CFF · Type 1 · Type 0 / CIDFont ──────┐  |
|   ┌─ writer ──── PdfEditor (incremental) · PdfBuilder · ContentSt ┐  |
|   ┌─ crypto ──── MD5 / SHA-256 / RC4 / AES-128 / AES-256 (pure) ──┐  |
|   ┌─ parser ──── Lexer · Parser · XrefParser · PdfObject ─────────┐  |
|   ┌─ filters ─── Flate · ASCIIHex · ASCII85 · RLE · LZW ──────────┐  |
+----------------------------------------------------------------------+
```

`:kitepdf` is intentionally headless and dependency-free so it can ship into
CLI tools, servers and Compose iOS frameworks without dragging Skia or a
platform PDF kit along with it. The backend modules are opt-in.

## Running tests

```bash
./gradlew :kitepdf:jvmTest        # core suite
./gradlew :kitepdf:allTests       # all KMP targets that have a runner
```

The `:kitepdf-native-renderer` module ships a **differential test harness**
that renders PDFs with both KitePDF and MuPDF and compares pixel diffs to
catch rendering regressions. Drop real-world PDFs into
`kitepdf-native-renderer/corpus/` (they are gitignored) and run:

```bash
brew install mupdf-tools   # one-time — provides the `mutool` oracle
MUTOOL="$(which mutool)" ./gradlew :kitepdf-native-renderer:jvmTest --tests "*DifferentialTest*"
# → kitepdf-native-renderer/build/difftest/report.md
```

## Status

Pre-1.0, API may shift. Tracked correctness gaps are listed in the feature
matrix above. PDF is a deep spec — see [Acknowledgements](#acknowledgements)
for what KitePDF deliberately defers to other formats / future work.

## License

Apache 2.0. A small number of encoding tables are ported from MuPDF and
retain their AGPL-3.0 headers in the relevant source files.

## Acknowledgements

Architectural reference: [MuPDF](https://mupdf.com/) by Artifex Software.
Encoding tables and the Adobe Glyph List are ported from MuPDF. Standard 14
font widths derive from URW++ AFM files that ship with MuPDF. CFF / Type 2
charstring decoder follows Adobe Tech Notes 5176 + 5177. Standard Security
Handler follows ISO 32000-1 §7.6.
