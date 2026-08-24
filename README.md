# KitePDF

A pure-Kotlin document engine for Kotlin Multiplatform: read, create, edit and
render PDFs, and read reflowable EPUB 2/3, from `commonMain`.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/kitepdf?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.yuroyami/kitepdf)
[![Docs](https://img.shields.io/badge/docs-yuroyami.github.io-1f6feb)](https://yuroyami.github.io/KitePDF/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/KitePDF/)** · a guide for each task,
plus the generated API reference.

## What you get

KitePDF ships its own PDF engine: lexer, xref parser, content-stream interpreter,
font engine, filters, encryption, writer and editor. The same core reads
reflowable EPUB 2/3 books. There is no platform PDF engine underneath, no JNI and
no native binary, so one code path runs on Android, iOS, JVM, Kotlin/Native, JS
and Wasm.

The engine modules hold three `expect` declarations in total: a mutex, a thread
id, and the deflate/inflate hook. All three live in `kitepdf-core`. Everything
else is shared by every target. Drawing a page to a screen is a separate, opt-in
artifact. KitePDF is pre-1.0, and the API changes between minor versions.

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
docs use `PdfDocument`: it also carries the password overload, `openOrNull` and
`edit()`.

## Install

Seven artifacts are published, all at `0.6.0`. Add one document artifact. Add one
renderer only when you draw pages.

| Artifact | Add it when |
| --- | --- |
| `io.github.yuroyami:kitepdf` | You want both formats. This is the usual choice. It re-exports both handlers and adds `KiteDoc`, which opens a file without being told which format it is. |
| `io.github.yuroyami:kitepdf-pdf` | You want PDF only, with no EPUB reflow engine on the classpath. |
| `io.github.yuroyami:kitepdf-epub` | You want EPUB only. |
| `io.github.yuroyami:kitepdf-core` | Never add it yourself. It holds geometry, `KiteCanvas`, the font engine, the stream filters and the hyphenation data, and it arrives with any of the three artifacts above. |
| `io.github.yuroyami:kitepdf-compose-viewer` | You draw with Compose Multiplatform. It gives you `KiteDocView` (one composable for PDF and EPUB alike) and the viewer state. |
| `io.github.yuroyami:kitepdf-native-renderer` | You want page-to-image through the platform's own canvas: AWT, `android.graphics`, CoreGraphics, Canvas2D. |
| `io.github.yuroyami:kitepdf-skia-renderer` | You want page-to-image through Skia/Skiko, with one API across JVM, Android, Apple, Linux and web. |
| `io.github.yuroyami:kitepdf-net` | You load documents from a URL. Optional, and the only artifact that pulls in Ktor; add a Ktor engine next to it. |

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitepdf:0.6.3")
        }
    }
}
```

That one line covers reading, text extraction, form filling, editing, redaction,
encryption and building PDFs, plus the EPUB reader. Its runtime dependencies are
`kotlin-stdlib` and KiteImage, which supplies the JPEG, PNG, GIF, JPX, JBIG2 and
CCITT decoders the core calls into. The same artifact works in a plain Android or
JVM project: add it to your ordinary `dependencies { }` block.

### Declare the document artifact next to the renderer

All three renderer modules depend on `:kitepdf-pdf` with `implementation` instead
of `api`, and their public signatures still name types from it:
`KiteDocView(document: KiteDocument)`,
`AwtPdfRasterizer.encodeToPng(page: PdfPage, …)`. A renderer on its own puts
those classes on the runtime classpath but not the compile classpath, and the
build fails with unresolved references to `PdfDocument` and `PdfPage`. Declare
both lines:

```kotlin
implementation("io.github.yuroyami:kitepdf:0.6.3")                   // or kitepdf-pdf
implementation("io.github.yuroyami:kitepdf-skia-renderer:0.6.3")     // exactly one renderer
```

The three renderers are alternative backends for the same `KiteCanvas` interface.
Choose the one that matches how your app already draws.

## Open a document

Call the handler when you know the format, `KiteDoc` when you don't:

```kotlin
val pdf  = PdfDocument.open(bytes)
val book = EpubDocument.open(bytes)

import io.github.yuroyami.kitepdf.document.KiteDoc
val doc = KiteDoc.open(bytes)          // sniffs the bytes, returns either
KiteDoc.formatOf(bytes)                // Pdf | Epub | null, from the header alone
```

Bytes are not the only way in. There is a file path (JVM, Android, Apple, Linux,
Windows, Android NDK), a `File` and an `InputStream` (JVM, Android), an Android
content `Uri`, `NSData` and `NSURL` (Apple), Base64 and `data:` URIs everywhere,
and a URL through the optional `kitepdf-net` artifact. Each has an `...OrNull`
twin. All of them end in the same byte array: the engine reads the file whole,
then parses and lays out chapters on demand.

## Open a big book at the right page

A reflowable EPUB has to be laid out before it has pages. KitePDF reads and
lays out one chapter at a time, so resuming at chapter 20 waits for chapter 20
rather than for the whole book:

```kotlin
val state = rememberKiteDocViewState(book, savedBookmark)
KiteDocView(state, Modifier.fillMaxSize())

val savedBookmark = state.currentBookmark()   // save on pause
```

The rest loads in the background, nearest chapter first, and a chapter landing
above the reader does not move their page. On a 26-chapter book that turns
986 ms into 3 ms; end to end, including reading a 9.9 MB file and parsing it,
2 ms. A bookmark survives a font size change, so reader settings keep the
place. PDF pages are fixed, so none of this applies: a PDF is one chapter that
is ready as soon as it opens.

## Read and extract text

`open` throws when it cannot parse the file. `openOrNull` returns `null` instead.
Both read the xref chain first. When that chain is unusable, they scan the whole
file for `N G obj` headers, so truncated and lightly damaged files still open.

```kotlin
import io.github.yuroyami.kitepdf.text.search

val doc = PdfDocument.open(bytes)             // or open(bytes, "secret")
val maybe = PdfDocument.openOrNull(bytes)     // null instead of a throw

doc.version                   // "1.7"
doc.info.title                // Info dictionary
doc.bookmarks                  // bookmark tree
doc.pages[3].label            // "iv", from /PageLabels

val page = doc.pages[0]
page.extractText()            // plain string
page.structuredText.blocks    // each block holds lines, each line holds spans
page.search("invoice")        // List<PdfSearchHit>
doc.search("invoice")         // Sequence<PdfSearchHit>, lazily across pages
```

Every span carries bounds, plus per-character edge positions for building
selection rectangles. Extraction uses the font's `/ToUnicode` CMap when the font
has one. `PdfDocument` also exposes `xmp` (parsed, or null), `attachments`,
`permissions`, `viewerPreferences`, `pageMode`, `pageLayout`, `language`,
`articleThreads`, `optionalContent`, `markInfo`, `documentJavaScripts`,
`acroForm`, `formFields` and `resolveDestination`.

## Fill forms and edit

`doc.edit()` returns a `PdfEditor`. It stages your changes and writes them two
ways. `saveIncremental()` appends an update section to the original bytes.
`saveRewritten()` rebuilds the file from a reachability walk. Redaction requires
`saveRewritten()`: `saveIncremental()` throws instead of writing a file that
still contains the redacted content.

```kotlin
import io.github.yuroyami.kitepdf.core.KiteRectangle

val filled = doc.edit().apply {
    setTextFieldValue(doc.formField("ApplicantName")!!, "Jane Doe")
    setCheckbox(doc.formField("AgreeToTerms")!!, checked = true)
    setChoiceValue(doc.formField("Country")!!, "Norway")
}.saveIncremental()

val redacted = doc.edit().apply {
    redactRegions(doc.pages[0], listOf(KiteRectangle(72.0, 700.0, 320.0, 720.0)))
}.saveRewritten()
```

Text and choice fields get a new `/AP /N` appearance stream, built from the
field's `/DA`. Checkboxes and radio groups switch `/AS` to an existing appearance
state and clear their siblings. All four field types clear `/NeedAppearances`.
Redaction deletes the covered text and images from the content stream instead of
painting over them. Three gaps remain, listed under [Limits](#limits).

## Create a PDF

`PdfBuilder` writes a file page by page, as in the first example. The rest of the
writer:

- `.setInfo(title = "Report", author = "Jane Doe")` fills the Info dictionary.
- `drawImage(logo, x = 400.0, y = 700.0, width = 96.0, height = 48.0)` draws an image inside `page { }`. Create the image with `PdfImage.rgba(pixels, width = 128, height = 64)`, from `io.github.yuroyami.kitepdf.writer`.
- All 14 standard fonts are available, with widths from the URW++ AFM metrics.
- `EmbeddedFont.load(bytes)` loads a custom font. It subsets the TrueType outlines by default and emits a CIDFontType2/Identity-H font with a matching `/ToUnicode`. CFF outlines also work.
- `PdfBuilder.encrypt(userPassword, ownerPassword)` writes an AES-256/R6 encrypted file. On the read side the engine handles RC4, AES-128 and AES-256, across revisions R2 to R6.

## Read an EPUB

```kotlin
import io.github.yuroyami.kitepdf.epub.EpubDocument

val book = EpubDocument.open(bytes, pageWidth = 400.0, pageHeight = 640.0)
book.tableOfContents        // nav.xhtml on EPUB 3, toc.ncx on EPUB 2
book.search("chapter")      // Sequence<KiteSearchHit>
book.withFontSize(15.0)     // repaginates; the original stays valid
```

| Area | What the reader handles |
| --- | --- |
| CSS | A full cascade over the parsed HTML: selectors, specificity and a UA stylesheet. Reflow then paginates the result to the page size you ask for. |
| Fonts | Embedded TTF, OTF, WOFF and WOFF2, including obfuscated files |
| Hyphenation | Full Liang pattern sets for German, French, Spanish, Italian, Portuguese and Dutch |
| CJK | Per-character breaking, inter-character justification, kinsoku line-break rules |
| Layout | Ruby, bidi, Arabic joining, floats, tables, SVG, vertical writing modes |

## Draw a page

Compose Multiplatform, through `kitepdf-compose-viewer`:

```kotlin
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import io.github.yuroyami.kitepdf.compose.*

@Composable
fun Viewer(doc: PdfDocument) {
    val state = rememberKiteDocViewState(doc)
    KiteDocView(
        state = state,
        layout = KiteDocLayout.Paged(Orientation.Horizontal),
        zoomSpec = KiteZoomSpec(maxZoom = 6f),
        renderSpec = KiteRenderSpec.Rasterized(),
        onLinkTap = { _ -> false },   // return true once you have handled the action
    )
}
```

Headless, through either rasterizer artifact:

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.skia.PdfPageRasterizer

val awtPng = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)    // JVM only
val skiaPng = PdfPageRasterizer.encodeToPng(doc.pages[0], scale = 2.0)  // any Skiko target
```

`AwtPdfRasterizer` is the JVM entry point of `kitepdf-native-renderer`; the Apple,
Android and JS backends have their own. They take a `PdfPage`, so they are PDF
only; `EpubPageRasterizer` is the EPUB half of the Skia renderer, and the Compose
viewer takes either format through `KiteDocView`.

## Targets

The four document artifacts share one target set. The renderers do not, and that
difference is the usual cause of a first build that will not resolve.

| Artifact | Where it runs |
| --- | --- |
| `kitepdf`, `-pdf`, `-epub`, `-core` | Android (minSdk 21), JVM, iOS arm64, simulator arm64 and x64, macOS arm64, tvOS, watchOS, Linux x64 and arm64, Windows (mingwX64), Android Native, JS (browser and Node), wasmJs (browser and Node), wasmWasi (Node) |
| `-compose-viewer` | Android (minSdk 24), JVM, iOS arm64 and simulator arm64, macOS arm64, JS and wasmJs (browser) |
| `-native-renderer` | Android (minSdk 29), JVM, iOS arm64, simulator arm64 and x64, macOS arm64, tvOS, JS (browser) |
| `-skia-renderer` | Android (minSdk 21, see note), JVM, iOS arm64, simulator arm64 and x64, macOS arm64, tvOS, Linux x64 and arm64, JS and wasmJs (browser) |

On Android, `-skia-renderer` pulls `org.jetbrains.skiko:skiko-android`, which
JetBrains publishes to `https://maven.pkg.jetbrains.space/public/p/compose/dev`
rather than to Maven Central, so add that repository. Everything else here
resolves from Maven Central alone, and `-native-renderer` is the recommended
Android renderer.

The full matrix, and the reason behind each gap, is on the
[Platform support](https://yuroyami.github.io/KitePDF/platforms/) page.

## Limits

JVM/AWT and Skia are the two complete renderers. Every row below is a limit you
may reach.

| Limit | What it means for you |
| --- | --- |
| Page edits are not cumulative | `editPageContent`, `stampPage` and `redactRegions` each rebuild the page from the original document's snapshot, so a second call on the same page discards the first. Use one of the three per page per `PdfEditor`. |
| Redaction skips a shared form | A Form XObject is guarded by object number alone, so the same form reached under a second CTM keeps its content. |
| Redaction leaves the field entry | `/AcroForm /Fields` is not pruned when a widget annotation is removed. |
| Redaction keeps a clipping path | A vector path in the region is removed, unless it also sets a clip (`W`): then only its paint goes and its coordinates stay, because dropping the clip would let everything it clips paint over the rest of the page. A line width set through an ExtGState `/LW` is not seen either, so a stroke's ink is padded by the last `w` operator instead. |
| Annotations are read-only | They parse and appear on `PdfPage.annotations`, but there is no authoring API. The only annotation KitePDF writes is the widget for `PdfSigner`'s own signature field. |
| `PdfSigner` runs no cryptography | It stages the signature field, reserves `/Contents` and patches `/ByteRange`. It cannot validate a signature. Your application supplies the CMS blob. |
| Writing encrypts more narrowly than reading | `PdfBuilder` creates AES-256/R6 only, and editing an encrypted document requires AES-128 or AES-256. RC4 documents open and decrypt, but you cannot edit them. |
| CoreGraphics drops Standard-14 text | It paints embedded glyph outlines only, so text in a standard font does not appear. It also ignores per-image alpha. |
| Android and CoreGraphics drop `RAW` images | Neither has a path for `KiteImageData.Kind.RAW`, so both draw a gray placeholder. `RAW` is what a successful JPEG, JPX or JBIG2 decode produces, which covers most images in most files. |
| Canvas2D drops every image | It draws a placeholder in place of each one. |
| The Compose canvas drops rotation | It reads translation and scale magnitudes from the CTM, so rotation and shear are lost. |
| Three rasterizers use the wrong page box | `AwtPdfRasterizer`, `AndroidPdfBitmapRenderer` and `ApplePdfRasterizer` size their output from the raw MediaBox and apply a plain Y-flip, so they ignore `/Rotate`, `/CropBox` and non-zero box origins. `PdfPageRasterizer` (Skia) and the Compose viewer use the rotated, cropped box and are correct. `/UserUnit` is parsed and then applied nowhere. |
| Chained image filters are not unwound | `/Filter [/ASCII85Decode /DCTDecode]` hands still-ASCII85-encoded bytes to the JPEG decoder. |
| Shading meshes are approximated | Coons (type 6) and tensor (type 7) patch meshes tessellate to a fixed 8×8 grid of flat-colored quads, and the tensor patch's four interior control points are read for stream alignment and then discarded. Triangle meshes (types 4 and 5) use fixed depth-3 subdivision. |
| ICC profiles are not applied | An `/ICCBased` stream is mapped to Gray, RGB or CMYK by its `/N` count alone. Rendering intents and overprint are ignored. |
| Structured text has no word segmentation | You get blocks, lines and spans, where a span is one text-drawing run. |
| There is no structure tree | `markInfo` reports whether a document declares itself tagged. `/StructTreeRoot` is not parsed. |
| EPUB fixed layout needs a fully fixed book | A hybrid book that mixes fixed and reflowable spine items uses the reflow path for the whole book. |
| English hyphenation is reduced | It ships a small common-word pattern set rather than the full `hyph-en-us` data. |
| CI does not cover every target | Pull requests run JVM tests only. Pushes to `main` also run the core, PDF and EPUB suites on the iOS simulator, macOS and JS/Node. Nothing in CI exercises Android rendering, Canvas2D, wasm or Linux/Windows native. |

## Testing

769 tests across 172 test files. A differential harness compares the JVM/AWT
backend page by page against MuPDF, and only that backend. See
[DIFFTEST.md](kitepdf-native-renderer/DIFFTEST.md). A local run over 36 pages
reports a mean absolute error of 0.0062 and a worst page of 0.0281. The PDF
corpus is not committed, so a clean checkout and CI do not reproduce that run.

If a PDF renders incorrectly, please open an issue with the file attached. Every
rendering fix ships with a regression test.

## Sample app

`sample/` is a Compose Multiplatform sample. It opens a PDF and exercises the
API. The desktop entry point runs standalone. The Android and iOS entry points
are meant for your own host project.

## License

Apache-2.0. One file, `Encodings.kt`, carries character encoding tables from
[MuPDF](https://mupdf.com/) by Artifex Software and keeps its AGPL-3.0
attribution in the file header. Standard-14 font metrics come from URW++ AFM
files.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
