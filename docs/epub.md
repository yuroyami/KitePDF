# Reading EPUBs

KitePDF ships a complete reflowable EPUB 2/3 reader as a second document
handler on the same rendering core the PDF engine uses. Open a book, get
paginated pages, and render them through the exact same canvas seam; the
Compose viewer, the headless rasterizers, search, and text selection all work
identically for both formats.

## Opening a book

```kotlin
import io.github.yuroyami.kitepdf.epub.EpubDocument

val book = EpubDocument.open(epubBytes)
println("${book.pageCount} pages at ${book.pageWidth} x ${book.pageHeight} pt")
```

`open` throws `EpubFormatException` with a message naming the first
structural failure ("META-INF/container.xml missing or unreadable", "OPF not
found at ...", "spine is empty ...", "spine has no readable documents").
`EpubDocument.openOrNull(bytes)` returns null instead.

Reflowable books are paginated to the page size you ask for:

```kotlin
val book = EpubDocument.open(
    epubBytes,
    pageWidth = 400.0,     // points
    pageHeight = 640.0,
    fontSize = 12.0,       // body size; author CSS scales relative to it
    margin = 36.0,
)
```

Fixed-layout (pre-paginated) books keep their authored viewport;
`book.isFixedLayout` tells you which kind you have.

## Rendering pages

`EpubDocument.pages` is a `List<EpubPage>`, and every page renders through
the shared canvas the same way a `PdfPage` does:

```kotlin
// Any canvas backend works: AwtCanvas, SkiaCanvas, ComposeCanvas, ...
val canvas = AwtCanvas(graphics2d)
book.pages[0].renderTo(canvas)
```

In Compose, `EpubView` is the drop-in viewer:

```kotlin
EpubView(document = book, modifier = Modifier.fillMaxSize())
```

It shares the `PdfView` machinery, so paged/continuous layouts, zoom,
selection, search highlights, TOC panels, and link taps work the same way;
see the [Compose viewer guide](compose-viewer.md).

## Reader settings

Everything a reading app's settings sheet needs is on `EpubSettings`. The
overrides are applied as a dedicated cascade origin that beats the
publisher's CSS (including `!important`):

```kotlin
val night = book.withSettings(
    book.settings.copy(
        fontFamily = ReaderFontFamily.SERIF,   // or SANS_SERIF / MONOSPACE; null = publisher fonts
        lineHeightScale = 1.4,
        textColor = RgbColor(0.9, 0.9, 0.9),
        backgroundColor = RgbColor(0.1, 0.1, 0.12),
        justify = true,                        // null = as authored
        usePublisherCss = true,                // false = UA + reader styles only
    ),
)
```

`withSettings` (and the `withFontSize` / `withPageSize` / `withMargin`
shorthands) re-flow the book without re-parsing it: the zip, DOM, CSS, and
fonts are all reused, so a font-size slider stays responsive on large books.

## Metadata and table of contents

```kotlin
val meta = book.epubMetadata          // EPUB-specific: identifier, cover path, direction, ...
println("${meta.title} by ${meta.creators.joinToString()}")

for (entry in book.tableOfContents.entries) {
    val page = entry.href?.let { book.pageOf(it) }
    println("${entry.label} -> page $page")
}
```

`EpubDocument` also implements the format-neutral `KiteDocument` interface
(shared with `PdfDocument`): `metadata`, `outlines`, `pageCount`, and
per-page `textContent()` behave the same for both formats, so reader UI can
be written once.

## Search, text, and links

```kotlin
// Engine-level search across the whole book.
for (hit in book.search("whale")) {
    println("page ${hit.pageIndex}: ${hit.text}")
    // hit.quads are page-space rectangles, ready for highlight overlays
}

// Structured text with geometry, per page.
val text = book.pages[0].textContent()

// Internal links resolve to page indices.
val target: Int? = book.pageOf("chapter2.xhtml#section-3")
```

## Typography

The layout engine covers what real books use:

- **Embedded fonts**: TrueType, OpenType/CFF, WOFF, and WOFF2 (via a
  pure-Kotlin Brotli decoder), with per-glyph fallback so mixed-script text
  never shows tofu.
- **Hyphenation**: Knuth-Liang patterns for English, German, French,
  Spanish, Italian, Portuguese, and Dutch, selected by the book's language.
- **CJK**: inter-character justification with kinsoku line-break rules, ruby
  annotations, and vertical writing (`writing-mode: vertical-rl`) with
  upright CJK and rotated Latin.
- **Layout**: floats with exclusion bands, tables, inline images on the
  baseline, `::before`/`::after` generated content, `text-transform`,
  letter/word spacing, and small-caps.
