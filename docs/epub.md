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

// or straight off disk, on JVM, Android and Apple:
val fromDisk = EpubDocument.openFile("/books/moby-dick.epub")
```

For every other source (a stream, an Android content Uri, Base64, a URL) and
for opening a file without knowing whether it is a book or a PDF, see
**[Loading a document](loading.md)**.

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

In Compose, `KiteDocView` is the ready-made viewer. It takes any
`KiteDocument`, so a book goes in exactly where a PDF would:

```kotlin
KiteDocView(document = book, modifier = Modifier.fillMaxSize())

// Night mode, applied at render, so switching never re-lays-out:
KiteDocView(document = book, theme = ReaderTheme.Dark)
```

Paged/continuous layouts, zoom, selection, search highlights, TOC panels and
link taps all work the same as for PDF; see the
[Compose viewer guide](compose-viewer.md).

## Opening a big book fast

A reflowable book has to be laid out before it has pages, and laying out a
whole novel takes seconds. KitePDF lays out one chapter at a time instead, so
a reader resuming at chapter 20 waits for chapter 20, not for chapters 0 to 19.

`EpubDocument.open` reads the container, the OPF and the table of contents, and
stops there. It is under half a millisecond on every book in the local corpus,
including a 9.9 MB one. A chapter's HTML is read and parsed when that chapter is
first laid out.

Save a bookmark when the reader leaves, and open at it when they come back:

```kotlin
val state = rememberKiteDocViewState(book, savedBookmark)
KiteDocView(state, Modifier.fillMaxSize())

// later, e.g. in onPause
val savedBookmark = state.currentBookmark()
```

The rest of the book lays out in the background, nearest chapter first, while
the reader reads. A chapter landing above them does not move their page.

On the local corpus this turns opening at the last chapter from 986 ms into
3 ms for a 26-chapter book, and from 2085 ms into 71 ms for an 11-chapter one.
End to end, including reading the file and parsing it, that 26-chapter book
goes from 11.3 ms to 2.0 ms.

### Positions: two kinds

| Type | What it is | Lives as long as |
|---|---|---|
| `KiteLocation(chapter, page)` | where a page is in the layout you have now | the current font size and page size |
| `KiteBookmark` | where the reader is in the text | forever, across any re-flow |

Use a location to move around, a bookmark to remember. `document.locate(bookmark)`
turns one into the other and lays out that single chapter to do it.

### Changing settings without losing the place

```kotlin
val mark = state.currentBookmark()
val bigger = book.withFontSize(16.0)
val newState = rememberKiteDocViewState(bigger, mark)
```

The reader stays on the same paragraph, and only its chapter is re-flowed
before the page appears. The sample app in `sample/` does exactly this: pick
"EPUB book", then change the font size and watch the page count change while
the words on screen do not.

### What still lays out the whole book

`pageCount` and `pages` are the totals for the entire document, so asking for
either lays every chapter out. So does `KiteDocLayout.Spread`, because it pairs
pages by index and inserting a chapter would re-pair the book underneath the
reader. Use `knownPageCount` with `isComplete` for a running total, and
`pageCountIn(chapter)` for one chapter.

Laying out any chapter also reads the first one. The writing mode (horizontal
or vertical) is one decision per book, read from chapter 1; the hyphenation
language is chosen per spine item from its own `xml:lang`/`lang`, falling
back to the book's OPF language. That is one extra chapter, never the whole
book.

### Where embedded fonts come from

An `@font-face` in a stylesheet belongs to the whole book, and its `url()`
resolves against that stylesheet's folder. An `@font-face` inside a document's
own `<style>` block belongs to that document only, the same as every other rule
in a `<style>` block. Put shared fonts in a stylesheet, which is where books
normally put them.

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
(shared with `PdfDocument`): `metadata`, `outline`, `pageCount`, and
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
  never shows tofu. An embedded font is always measured from its own tables;
  text on the fallback path is measured with the exact Standard-14 metrics,
  Cyrillic included.
- **Hyphenation**: Knuth-Liang patterns for English, German, French,
  Spanish, Italian, Portuguese, Dutch, and Russian, selected per spine
  item from its own language tag. Seven of those languages ship a full
  pattern set. English ships a small common-word set rather than the full
  `hyph-en-us` data.
- **CJK**: inter-character justification with kinsoku line-break rules, ruby
  annotations, and vertical writing (`vertical-rl` and `vertical-lr`) with
  upright CJK and rotated Latin. Selection, search and link rectangles follow
  the columns, so a tap lands on the glyph under it.
- **Marks**: GPOS attachment onto a base letter, onto a ligature component,
  and onto the mark below, so two stacked diacritics sit one above the other
  instead of overprinting.
- **Layout**: floats with exclusion bands, tables (including
  `table-layout: fixed`), `position: absolute`/`relative`/`fixed`, inline
  images on the baseline, `::before`/`::after` generated content,
  `text-transform`, letter/word spacing, and small-caps. Known limitation:
  in `direction: rtl` text, `text-indent` shifts from the left edge rather
  than the inline-start (right) edge; lines still stay inside the content
  box.

## Books that are not quite right

Two habits of real EPUBs that the engine absorbs rather than rejecting.

**Encodings.** The spec says UTF-8 or UTF-16. Books ship Windows-1252 anyway,
sometimes while their own XML declaration claims UTF-8. Every entry is read by
weighing the evidence: a byte order mark first, then UTF-16 without one, then
the document's declaration (`<?xml encoding>`, `<meta charset>`, or the legacy
`http-equiv`), and finally the bytes themselves. Text that is not valid UTF-8
is read as Windows-1252, which never fails.

**Archives.** The reader handles ZIP64 records and entries whose sizes only
the trailing data descriptor knows, and it verifies every entry's CRC. A
mismatch is reported, not fatal: half a broken book beats no book.

## Accessibility

`readingOrder()` gives one page's content in the order a reader that speaks
would say it, each item carrying the role its source element declared.

```kotlin
for (item in page.readingOrder()) {
    when (item.role) {
        EpubRole.HEADING -> speakHeading(item.text, item.headingLevel)
        EpubRole.IMAGE -> describe(item.text)          // the alt text
        else -> speak(item.text)
    }
}
```

Left out: anything marked `aria-hidden="true"` or `role="presentation"`, and
an image with `alt=""`, which is how authors mark decoration. `aria-label`
replaces an element's text, and both `aria-hidden` and `epub:type` reach down
the subtree, so a footnote's paragraphs stay footnote.
