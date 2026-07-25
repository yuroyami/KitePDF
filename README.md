# KitePDF

A document engine for Kotlin Multiplatform: read, create, edit and render PDFs,
and read reflowable EPUB 2/3, from `commonMain`.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/kitepdf?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.yuroyami/kitepdf)
[![Docs](https://img.shields.io/badge/docs-yuroyami.github.io-1f6feb)](https://yuroyami.github.io/KitePDF/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## What you get

A PDF engine written from scratch in Kotlin — lexer, xref parser, content-stream
interpreter, font engine, filters, encryption, writer and editor — plus a
reflowable EPUB 2/3 reader built on the same substrate. There is no platform PDF
engine underneath, no JNI and no native binary, so the same code path runs on
Android, iOS, JVM, Kotlin/Native, JS and Wasm.

Almost all of it is common code. The engine modules contain three `expect`
declarations in total (a mutex, a thread id, and the deflate/inflate hook, all in
`kitepdf-core`); everything else — parsing, layout, rasterisation geometry,
crypto, fonts — is one implementation shared by every target. Drawing the
resulting primitives to a screen is a separate, opt-in artifact.

KitePDF is pre-1.0. The API moves between minor versions.

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont

val bytes = PdfBuilder()
    .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Hello from PdfBuilder") }
    .build()

val doc = PdfDocument.open(bytes)
doc.pageCount                // 1
doc.pages[0].extractText()   // "Hello from PdfBuilder"
```

`KitePDF.open(bytes)` is a one-argument alias for `PdfDocument.open(bytes)`. The
docs use `PdfDocument` throughout, since it also carries the password overload,
`openOrNull` and `edit()`.

## Install

Seven artifacts are published, all at `0.2.0`. You need one document artifact,
and one renderer only if you are drawing pages.

| Artifact | Add it when |
| --- | --- |
| `io.github.yuroyami:kitepdf` | You want both formats. This is the usual choice. It has no code of its own — it is `api(kitepdf-pdf) + api(kitepdf-epub)` and a 12-line marker file. |
| `io.github.yuroyami:kitepdf-pdf` | PDF only, with no EPUB reflow engine on the classpath. |
| `io.github.yuroyami:kitepdf-epub` | EPUB only. |
| `io.github.yuroyami:kitepdf-core` | Never directly. Geometry, `KiteCanvas`, the font engine, the stream filters and the hyphenation data. It arrives transitively with any of the three above. |
| `io.github.yuroyami:kitepdf-compose-viewer` | You draw with Compose Multiplatform. Gives you `PdfView`, `EpubView` and the viewer state. |
| `io.github.yuroyami:kitepdf-native-renderer` | You want page-to-image through the platform's own canvas: AWT, `android.graphics`, CoreGraphics, Canvas2D. |
| `io.github.yuroyami:kitepdf-skia-renderer` | You want page-to-image through Skia/Skiko, with one API across JVM, Android, Apple, Linux and web. |

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitepdf:0.2.0")
        }
    }
}
```

That covers reading, text extraction, form filling, editing, redaction,
encryption and building PDFs, plus the EPUB reader. Its runtime dependencies are
`kotlin-stdlib` and KiteImage, which supplies the JPEG, PNG, GIF, JPX, JBIG2 and
CCITT decoders the core calls into.

The same artifact works in a plain Android or JVM project — add it to your
ordinary `dependencies { }` block.

### A renderer needs the document artifact declared alongside it

All three renderer modules depend on `:kitepdf-pdf` with `implementation` rather
than `api`, while their public signatures name types from it:
`rememberPdfViewState(document: PdfDocument)` and
`AwtPdfRasterizer.encodeToPng(page: PdfPage, …)`. A renderer artifact on its own
therefore puts those classes on the runtime classpath but not the compile
classpath, and the build fails with unresolved references to `PdfDocument` and
`PdfPage`. Declare both lines:

```kotlin
implementation("io.github.yuroyami:kitepdf:0.2.0")                   // or kitepdf-pdf
implementation("io.github.yuroyami:kitepdf-skia-renderer:0.2.0")     // exactly one renderer
```

The three renderers are alternative backends for the same `KiteCanvas`
interface. Pick the one that matches how your app already draws.

## Read a document

`open` throws on a file it cannot make sense of; `openOrNull` returns `null`
instead. Both parse the xref chain first and fall back to a full byte scan for
`N G obj` headers when the chain is unusable, so truncated and lightly corrupted
files still open.

```kotlin
val doc = PdfDocument.open(bytes)             // or open(bytes, "secret")
val maybe = PdfDocument.openOrNull(bytes)     // null instead of a throw

doc.version                 // "1.7"
doc.info.title              // Info dictionary
doc.xmp                     // parsed XMP, or null
doc.outlines                // bookmark tree
doc.attachments             // embedded files
doc.pages[3].label          // "iv" — from /PageLabels
```

Also on `PdfDocument`: `permissions`, `viewerPreferences`, `pageMode`,
`pageLayout`, `language`, `articleThreads`, `optionalContent`, `markInfo`,
`documentJavaScripts`, `acroForm`, `formFields` and `resolveDestination`.

## Extract and search text

`extractText()` returns a plain string. `structuredText` returns the geometry:
blocks, then lines, then spans, each with bounds, plus per-character edge
positions for building selection rectangles. Both go through the font's
`/ToUnicode` CMap where one exists.

```kotlin
import io.github.yuroyami.kitepdf.text.search

val page = doc.pages[0]
page.extractText()

for (block in page.structuredText.blocks)
    for (line in block.lines)
        for (span in line.spans) println("${span.text} @ ${span.bounds}")

page.search("invoice")      // List<PdfSearchHit>
doc.search("invoice")       // Sequence<PdfSearchHit>, lazily across pages
```

## Fill forms and edit

`doc.edit()` returns a `PdfEditor` that stages changes and serialises them two
ways. `saveIncremental()` appends an update section to the original bytes;
`saveRewritten()` rebuilds the file from a reachability walk. Redaction requires
`saveRewritten()`, and `saveIncremental()` throws rather than silently producing
a file that still contains the redacted content.

```kotlin
import io.github.yuroyami.kitepdf.core.Rectangle

val filled = doc.edit().apply {
    setTextFieldValue(doc.formField("ApplicantName")!!, "Jane Doe")
    setCheckbox(doc.formField("AgreeToTerms")!!, checked = true)
    setChoiceValue(doc.formField("Country")!!, "Norway")
}.saveIncremental()

val redacted = doc.edit().apply {
    redactRegions(doc.pages[0], listOf(Rectangle(72.0, 700.0, 320.0, 720.0)))
}.saveRewritten()
```

Text and choice fields get a freshly synthesised `/AP /N` appearance stream from
the field's `/DA`. Checkboxes and radio groups switch `/AS` to an existing
appearance state and clear their siblings. All four clear `/NeedAppearances`.

Redaction removes the covered text and images from the content stream rather
than painting over them. It has three known gaps, listed under
[Limits](#limits).

## Create a PDF

```kotlin
import io.github.yuroyami.kitepdf.writer.PdfImage

val logo = PdfImage.rgba(pixels, width = 128, height = 64)

val out = PdfBuilder()
    .setInfo(title = "Report", author = "Jane Doe")
    .page {
        text(StandardFont.TimesBold, 18.0, 72.0, 720.0, "Quarterly report")
        drawImage(logo, x = 400.0, y = 700.0, width = 96.0, height = 48.0)
    }
    .build()
```

All 14 standard fonts are available, with widths from the URW++ AFM metrics.
Custom fonts load with `EmbeddedFont.load(bytes)`, which subsets the TrueType
outlines by default and emits a CIDFontType2/Identity-H font with a matching
`/ToUnicode`. CFF outlines are supported too.

`PdfBuilder.encrypt(userPassword, ownerPassword)` writes an AES-256/R6 encrypted
file. On the read side the engine handles RC4, AES-128 and AES-256 across
revisions R2 to R6.

## Read an EPUB

```kotlin
import io.github.yuroyami.kitepdf.epub.EpubDocument

val book = EpubDocument.open(bytes, pageWidth = 400.0, pageHeight = 640.0)

book.pageCount
book.tableOfContents        // nav.xhtml on EPUB 3, toc.ncx on EPUB 2
book.search("chapter")      // Sequence<KiteSearchHit>
book.withFontSize(15.0)     // repaginates; the original stays valid
```

Reflow runs a CSS cascade over the parsed HTML (selectors, specificity and a UA
stylesheet), then boxes and paginates it to whatever page size you ask for.
Embedded TTF/OTF/WOFF/WOFF2 fonts are used, including obfuscated ones. Liang
hyphenation ships full pattern sets for German, French, Spanish, Italian,
Portuguese and Dutch. CJK gets per-character breaking, inter-character
justification and kinsoku line-break rules; ruby, bidi, Arabic joining, floats,
tables, SVG and vertical writing modes all work.

## Draw a page

Compose Multiplatform, via `kitepdf-compose-viewer`:

```kotlin
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.compose.PdfLayout
import io.github.yuroyami.kitepdf.compose.PdfRenderSpec
import io.github.yuroyami.kitepdf.compose.PdfView
import io.github.yuroyami.kitepdf.compose.PdfZoomSpec
import io.github.yuroyami.kitepdf.compose.rememberPdfViewState

@Composable
fun Viewer(doc: PdfDocument) {
    val state = rememberPdfViewState(doc)
    PdfView(
        state = state,
        layout = PdfLayout.Paged(Orientation.Horizontal),
        zoomSpec = PdfZoomSpec(maxZoom = 6f),
        renderSpec = PdfRenderSpec.Rasterized(),
        onLinkTap = { _ -> false },   // return true once you have handled the action
    )
}
```

Headless through `kitepdf-native-renderer`, which uses the platform's own
canvas. This entry point is JVM-only; the Apple, Android and JS backends have
their own:

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer

val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
```

Headless through `kitepdf-skia-renderer`, which is one common-code call on every
target Skiko ships for:

```kotlin
import io.github.yuroyami.kitepdf.skia.PdfPageRasterizer

val png = PdfPageRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
```

`EpubView`, `rememberEpubViewState` and `EpubPageRasterizer` are the EPUB
equivalents.

## Targets

The four document artifacts share one target set. The renderers do not — this is
the usual cause of a first build that will not resolve.

| Target | `kitepdf`, `-pdf`, `-epub`, `-core` | `-compose-viewer` | `-native-renderer` | `-skia-renderer` |
| --- | :---: | :---: | :---: | :---: |
| Android | yes (minSdk 21) | yes (minSdk 24) | yes (minSdk 29) | yes (minSdk 21) |
| JVM | yes | yes | yes | yes |
| iOS arm64, simulator arm64 | yes | yes | yes | yes |
| iOS x64 | yes | no | yes | yes |
| macOS arm64 | yes | yes | yes | yes |
| tvOS arm64, simulator arm64 | yes | no | yes | yes |
| watchOS arm32, arm64, device arm64, simulator arm64 | yes | no | no | no |
| Linux x64, arm64 | yes | no | no | yes |
| Windows (mingwX64) | yes | no | no | no |
| Android Native arm32, arm64, x86, x64 | yes | no | no | no |
| JS | yes (browser, Node) | yes (browser) | yes (browser) | yes (browser) |
| wasmJs | yes (browser, Node) | yes (browser) | no | yes (browser) |
| wasmWasi | yes (Node) | no | no | no |

Some renderer gaps come from the toolkit underneath. Compose Multiplatform
publishes no Intel-Apple variants, Skiko publishes nothing for Windows-native or
watchOS, and the CoreGraphics backend needs a 64-bit `CGFloat`, which watchOS's
arm64_32 ABI does not give it. The rest are simply not built yet: the native
renderer's Canvas2D backend would work on `wasmJs` but that target is not
declared, and the Compose viewer omits tvOS and Linux. `minSdk 29` on the native
renderer is `Paint.setBlendMode`; below that, blend modes would fall back to
`SRC_OVER`.

Intel macOS, tvOS x64 and watchOS x64 are off everywhere — Kotlin 2.3 deprecated
those targets.

## Limits

**Page edits are not cumulative.** `editPageContent`, `stampPage` and
`redactRegions` each rebuild the page from the original document's snapshot
rather than from staged changes, so a second edit to the same page silently
discards the first. Until that is fixed, each page can take one of these three
calls per `PdfEditor`.

**Redaction is incomplete in three known ways.** Shared Form XObjects are
guarded by object number alone, so the same form reached under a second CTM is
skipped. `/AcroForm /Fields` is not pruned when a widget annotation is removed.
Vector paths that fall inside the region pass through the operator filter
untouched — only text and images are removed.

Annotations are read-only. They parse and are exposed on `PdfPage.annotations`,
but there is no authoring API. The one place the library writes an annotation is
`PdfSigner`, and only the widget for its own signature field.

`PdfSigner` stages the signature field, reserves `/Contents` and patches
`/ByteRange`. It performs no cryptography and cannot validate a signature. Bring
your own CMS.

Structured text is blocks, lines and spans, where a span is one text-drawing
run. There is no word segmentation.

`markInfo` tells you whether a document declares itself tagged.
`/StructTreeRoot` is not parsed, so there is no structure tree to walk.

On the write side, encryption is narrower than on the read side: `PdfBuilder`
creates AES-256/R6 only, and editing an encrypted document requires AES-128 or
AES-256. RC4 documents open and decrypt, but cannot be edited.

**Two renderers are complete; the others are partial.** JVM/AWT and Skia handle
the full canvas. CoreGraphics paints only embedded glyph outlines, so
Standard-14 text comes out blank, and it ignores per-image alpha. Android and
CoreGraphics have no path for `ImageXObject.Kind.RAW` and fall back to a grey
placeholder; `RAW` is what a successful JPEG, JPX or JBIG2 decode produces, so
that covers most images in most files. Canvas2D paints a placeholder for every
image. The Compose canvas takes translation and scale magnitudes off the CTM, so
rotation and shear are lost.

**Page geometry.** The convenience rasterizers `AwtPdfRasterizer`,
`AndroidPdfBitmapRenderer` and `ApplePdfRasterizer` size their output from the
raw MediaBox and apply a plain Y-flip, so `/Rotate`, `/CropBox` and non-zero
box origins are ignored. `PdfPageRasterizer` (Skia) and the Compose viewer use
the rotated, cropped box and are correct. `/UserUnit` is parsed and then applied
nowhere.

Chained image filters are not unwound: `/Filter [/ASCII85Decode /DCTDecode]`
hands still-ASCII85-encoded bytes to the JPEG decoder.

Coons (type 6) and tensor (type 7) patch meshes tessellate to a fixed 8×8 grid
of flat-coloured quads, and the tensor patch's four interior control points are
read for stream alignment and discarded. Triangle meshes (types 4 and 5) use
fixed depth-3 subdivision.

ICC profiles are not applied — an `/ICCBased` stream is mapped to Gray, RGB or
CMYK by its `/N` count alone. Rendering intents and overprint are ignored.

EPUB fixed layout works when the whole book is fixed. Hybrid books that mix
fixed and reflowable spine items fall back entirely to the reflow path. English
hyphenation ships a small common-word pattern set rather than the full
`hyph-en-us` data.

CI gates pull requests on JVM tests only. Pushes to `main` additionally run the
core, PDF and EPUB suites on the iOS simulator, macOS and JS/Node. Nothing in CI
exercises Android rendering, Canvas2D, wasm or Linux/Windows native.

## Testing

769 tests across 172 test files.

The JVM/AWT backend is compared page-by-page against MuPDF by a differential
harness — see [DIFFTEST.md](kitepdf-native-renderer/DIFFTEST.md). A local run
over 36 pages reports a mean absolute error of 0.0062 and a worst page of
0.0281. The PDF corpus is not committed, so that is a local measurement rather
than something a clean checkout or CI reproduces. The harness covers the JVM/AWT
backend only.

If a PDF renders incorrectly, please open an issue with the file attached. Every
rendering fix lands with a regression test.

## Sample app

`sample/` is a runnable Compose Multiplatform app that opens a PDF and exercises
the API on Android, iOS, desktop and the browser.

## Documentation

[Guides and API reference](https://yuroyami.github.io/KitePDF/) — getting
started, reading, writing, editing, the Compose viewer, headless rendering,
EPUB, and the generated reference.

## License

Apache-2.0. One file, `Encodings.kt`, carries character encoding tables ported
from MuPDF and keeps its AGPL-3.0 attribution in the file header.

Architectural reference: [MuPDF](https://mupdf.com/) by Artifex Software.
Standard-14 font metrics derive from URW++ AFM files.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
