# Reading EPUBs

KitePDF ships a complete reflowable EPUB 2/3 reader as a second document
handler on the same rendering core the PDF engine uses. Open a book, get
paginated pages, and render them through the exact same canvas seam; the
Compose viewer, the headless rasterizers, search, and text selection all work
identically for both formats.

How the reader does against the W3C EPUB 3 test suite, test by test, with the
issue for each gap, is on [EPUB conformance](epub-conformance.md).

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

Fixed-layout (pre-paginated) books keep their authored viewport. The
viewport is in CSS pixels, so a page declared 800 by 1200 is 600 by 900
points. `book.isFixedLayout` tells you which kind you have.

The viewport comes from the first `<meta name="viewport">` of the chapter, and
from the first `width` and `height` in it, as EPUB Reading Systems 3.3 asks.
A value is the number it starts with, so `width=1000px` and `width=1000mm` are
both 1000 pixels. A side given as `device-width` or `device-height`, or not
given at all, comes from the reader: a tag of `width=device-width,
height=device-height` makes the page the size you opened the book at, and a
tag with a width alone keeps the aspect ratio of that size.

A book can mix fixed and reflowable chapters. Each chapter then keeps its own
layout: a fixed chapter is one page at its viewport, and a reflowable chapter
flows over pages of the reader's size. `book.isFixedLayout` is true only when
every chapter is fixed.

`book.epubMetadata.rendition` gives the rendition properties of the whole
book: layout, spread, orientation and flow. `book.renditionOf(chapter)` gives
the values for one chapter, and the side of a spread that its first page asks
for. `KiteDocLayout.Spread` reads them to pair the pages.

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
the pages of the whole book, and a chapter that lands later would re-pair the
book underneath the reader. Use `knownPageCount` with `isComplete` for a running total, and
`pageCountIn(chapter)` for one chapter.

Laying out any chapter also reads the first reflowable one. The writing mode
(horizontal or vertical) is one decision per book, read from that chapter; the hyphenation
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
        hyphenate = true,                      // null = as authored
        usePublisherCss = true,                // false = UA + reader styles only
    ),
)
```

`hyphenate = true` breaks long words at line ends in every chapter, with the
patterns for that chapter's language, even when the book's CSS never asks for
it. `false` turns hyphenation off everywhere.

`withSettings` (and the `withFontSize` / `withPageSize` / `withMargin`
shorthands) re-flow the book without re-parsing it: the zip, DOM, CSS, and
fonts are all reused, so a font-size slider stays responsive on large books.

### Memory

A laid-out chapter costs about 165 bytes per character of text, so a whole
novel does not fit an Android app's heap. `layoutCacheBytes` (default 48 MB)
caps what stays in memory: the chapters the reader has not used for the
longest drop their pages and lay out again on the next visit, about a tenth
of a second each. Page counts, anchors and bookmarks survive the drop, so
navigation never waits. One chapter always stays, so a book that is a single
spine document keeps that document whole.

The chapters on screen stay too. `KiteDocView` names them with
`keepChapters`, so a long press or a link tap on the page the reader sees never
waits for a layout, even while a search reads the rest of the book. An app that
draws pages without the viewer can call `book.keepChapters(setOf(chapter))`.

A chapter that dropped while it was off screen comes back off the main thread
when it scrolls into view. `page.isContentLoaded` says whether a page's chapter
is in memory, and reading it lays nothing out. `page.loadContent()` lays the
chapter out again, so call it off the main thread. Until the content is back,
`KiteDocView` draws the page's paper in Vectorized mode, and a long press or a
link tap on it finds no text and no links.

Embedded fonts sit outside that budget and stay small on their own: a font
file is parsed once per book, however many stylesheets or chapters declare it,
and it keeps one outline per glyph it has drawn.

```kotlin
val book = EpubDocument.open(bytes, EpubSettings(layoutCacheBytes = 24L * 1024 * 1024))
```

## Metadata and table of contents

```kotlin
val meta = book.epubMetadata          // EPUB-specific: identifier, cover path, direction, ...
println("${meta.title} by ${meta.creators.joinToString()}")

for (entry in book.tableOfContents.entries) {
    val page = entry.href?.let { book.pageOf(it) }
    println("${entry.label} -> page $page")
}
```

An entry's label is the text of its link, with each image in it read as its `alt`,
or its `title` when it has no `alt`, and its white space collapsed, so an entry made
of a picture still has a name in the outline.

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

### Notes and other link targets

Each link on a page says what it is for, in `EpubLink.kind`. A link marked
`epub:type="noteref"` or `role="doc-noteref"`, or with the `rel="footnote"` that
EPUB 2 converters write, is a note reference. Glossary and bibliography
references have their own kinds.

`linkTarget` reads the element a link points at, without laying its chapter out.
A reader can show a footnote in a popup instead of turning the page:

```kotlin
// Link rects are in display space, y down, with the smaller y in `bottom`.
val tapped = page.links.first { x in it.rect.left..it.rect.right && y in it.rect.bottom..it.rect.top }
if (tapped.kind == EpubLinkKind.NOTE_REFERENCE) {
    book.linkTarget(tapped.href)?.let { note ->
        showNote(note.text)                     // one line per paragraph
        // note.kind is FOOTNOTE, ENDNOTE, GLOSSARY_ENTRY and so on
        // note.bookmark goes to the note itself: book.locate(note.bookmark)
    }
}
```

The text leaves out ruby readings and the back link to the call site. When the
id sits on a short inline anchor, as in `<p><a id="fn1">1.</a> The note.</p>`, the
text is the whole paragraph. A glossary term comes with its definitions. An
`epub:type` on the head, an element in it, or the html element means nothing, so
a link to the chapter's `<title>` is of kind `OTHER`, with no text.

`KiteDocView` does the hit test for you: its `onLinkTap` receives every tapped
link before the viewer scrolls, with `kind` set for a reference (see
[Compose viewer](compose-viewer.md)).

### Audio and video

A `<video>` or an `<audio>` element with controls keeps a box of its own on
the page. The box takes the element's `width` and `height`, then its CSS size,
then the size of its poster, and else a 16:9 box at the text width for a video
or a 40 pt bar for an audio player. The page paints the poster there, or a plain
grey box. The element's own children, the text for a reader
that plays nothing, are not painted. An audio element without controls is not
shown, as in a browser.

To play them in `KiteDocView`, add the optional `kitepdf-media` artifact and pass
`KiteMediaOverlay()` to the viewer's `pageOverlay` (see
[Book audio and video](media.md)).

`EpubPage.media` lists the elements on a page with their box, kind, sources in
order, poster and flags. The engine plays nothing itself: to use a player of your
own, read the bytes with `EpubDocument.resource` and hand them to a player that
you place over the box.

```kotlin
for (media in page.media) {
    val source = media.sources.firstOrNull { it.type == "video/mp4" } ?: continue
    val bytes = book.resource(source.href) ?: continue
    placePlayer(media.rect, bytes, autoplay = media.autoplay, loop = media.loop)
}
```

In `KiteDocView`, place the player in the `pageOverlay` slot with
`Modifier.displayRect(media.rect)`, and it stays on the box at any zoom (see
[Compose viewer](compose-viewer.md#draw-over-a-place-on-a-page)).

### Read-along narration

A book with media overlays pairs each piece of its text with a clip of
audio. `book.mediaOverlayOf(chapter)` gives a chapter's clips in document
order, and `book.epubMetadata.narration` gives the book's duration, narrators
and active classes. `book.locateFragment(clip.textHref)` gives the page that
shows a clip's text and one rectangle per line of it, ready for a highlight:

```kotlin
val overlay = book.mediaOverlayOf(chapter) ?: return
for (clip in overlay.clips) {
    val audio = clip.audioHref?.let(book::resource) ?: continue
    val where = book.locateFragment(clip.textHref)
    play(audio, from = clip.clipBegin, to = clip.clipEnd, highlight = where)
}
```

The engine plays nothing itself. The optional `kitepdf-media` artifact reads a
book aloud in `KiteDocView` with `KiteReadAloud` (see
[Book audio and video](media.md#read-a-book-aloud)). If you play the clips
yourself, show the active class as a highlight colour, not as a new style,
because a new style would lay the page out again.

### Scripted content and embedded documents

`book.isScripted(chapter)` says whether a chapter is scripted: its manifest item
has the `scripted` property, or its document has a `script` element.
`book.scriptedChapters` lists those chapters. The library runs no script by
itself: a scripted chapter shows what its markup shows without one, `noscript`
content included. `EpubScriptRunner` of `kitepdf-javascript` runs a book's
scripts on KiteJS, over this library's own parse and layout; see
[Scripts in an EPUB](javascript.md#scripts-in-an-epub). An element with the
`hidden` attribute does not show, as in a browser.

An `<iframe>`, and an `<object>` whose type is HTML or XHTML, keep a box on the
page. The box takes the element's `width` and `height`, then its CSS size, and
else 300 by 150 CSS pixels, as in a browser. A frame's box stays empty. An
object's box shows the element's fallback children, and grows when they need
more room. `EpubPage.embeds` lists the boxes on a page, with the document that
each one embeds:

```kotlin
for (embed in page.embeds) {
    // embed.href is a zip path for book.resource, or a URL. An app may show it in a view of its own.
    showEmbedded(embed.rect, embed.href)
}
```

The scripts of a chapter run in [Scripts in an EPUB](javascript.md#scripts-in-an-epub); the
documents of these frames and objects do not run there yet.

Each box stays on one page, as an image does: a box that does not fit what is
left of a page moves whole to the next one. Only an object taller than a page
goes on from page to page, and its `isWhole` is false there. `book.chapterPath(chapter)`
names the zip path of a chapter's own document.

### Resources inside the markup

A document can carry a small resource in itself as a `data:` URL: an image
source, a CSS `url()` for a background or a font, an SVG `href`. They load as a
file of the book does, Base64 or percent-encoded. `book.resource(url)` gives the
bytes of one and `book.resourceType(url)` its media type, and `KiteDataUrl` in
`kitepdf-core` decodes one by itself. A script cannot open a `data:` URL as a page
of its own, as EPUB Reading Systems 3.3 asks.

### Resources on the web

A book can name an image, a font or a background by an `https` URL instead of a
file in its container (EPUB 3.3, section 3.6). `book.hasRemoteResources(chapter)`
says whether a chapter does: its manifest item has the `remote-resources`
property, or its markup or styles name such a URL.

A fetch tells the URL's server that the book was opened, so the engine fetches
nothing on its own. Give it a fetcher to load them, `kitepdf-net`'s or one of
your own:

```kotlin
val settings = EpubSettings(resourceFetcher = EpubResourceFetcher(client))   // kitepdf-net
val book = EpubDocument.open(bytes, settings)
```

A fetcher gets `https` URLs only, each once per book. A plain `http` URL is
never fetched, as EPUB Reading Systems 3.3 asks. The book keeps what lands for
its whole life, 32 MiB at most.

Layout never waits for the network:

- An image whose markup gives its `width` and `height` keeps that box, and its
  page paints the picture once the bytes land. `EpubPage.remoteVersion` moves
  then, and `book.remoteArrivals` counts the arrivals for a viewer to follow.
- An image without both sizes, and a font, size the layout. A chapter laid out
  before they land keeps their absence for the life of that document, so its
  page count holds. The next document over the book, such as one from
  `withSettings`, finds them.

To have them in the first layout, wait for them first, with a time limit of
your own. `KiteDocView` waits two seconds:

```kotlin
book.awaitLayoutResources(chapter, 2.seconds)
book.prepareChapter(chapter)
```

A URL costs that wait once per book. When a wait runs out, the URLs still in
flight are left out of every later wait, so a font server that hangs delays the
first chapter that needs it, not every chapter after it. Their fetches go on.

Without a fetcher, or when a fetch fails, a remote image shows its manifest
fallback, else the empty box its markup gives, and a remote font gives way to
the next source of its `@font-face`.

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
- **Shaping**: every GSUB lookup type of an embedded font, contextual and
  chaining substitution included, for the script of each word and in the
  stages HarfBuzz uses, with the joining forms of Arabic and Syriac. Characters
  decompose and compose first, as HarfBuzz normalizes them, and combining marks
  go into the order the font expects. The syllables of Devanagari, Bengali,
  Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada and Malayalam are reordered
  around their features, with reph, half forms, pre-base matras and pre-base
  consonants, as HarfBuzz's Indic shaper does, and so are Khmer and Myanmar
  syllables, as its Khmer and Myanmar shapers do. Sinhala, Tibetan, Balinese,
  Javanese, Brahmi, Adlam and the other scripts of HarfBuzz's Universal Shaping
  Engine are reordered as that engine does. Thai and Lao sara am splits into
  nikhahit and sara aa. Words in all these scripts, in Latin, Greek, Cyrillic,
  Arabic, Urdu Nastaliq, Syriac and Hebrew, and in the scripts outside the Basic
  Multilingual Plane that HarfBuzz shapes with its default shaper, shape to the
  glyphs HarfBuzz gives, except that the Syriac abbreviation mark does not
  stretch. A font without GSUB still has its text reordered. A character outside
  the Basic Multilingual Plane, such as a mathematical letter or a CJK Extension B
  ideograph, draws from the font of the book.
- **Marks**: GPOS attachment onto a base letter, onto a ligature component,
  and onto the mark below, so two stacked diacritics sit one above the other
  instead of overprinting.
- **Bidirectional text**: the Unicode Bidirectional Algorithm (UAX #9) of
  Unicode 17 in full, with the bidi class of every character, explicit
  embeddings and isolates, and paired brackets. Each paragraph resolves as a
  whole before it breaks into lines, and a forced line break ends a paragraph.
  A bracket at a right-to-left level draws as its mirror image.
- **Layout**: floats with exclusion bands, tables (including
  `table-layout: fixed`), `position: absolute`/`relative`/`fixed`, inline
  images on the baseline, `::before`/`::after` generated content,
  `text-transform`, letter/word spacing, and small-caps. A page paints in the
  order of CSS 2.1, Appendix E: backgrounds and borders, then floats, then lines
  and block images in document order, then positioned boxes by `z-index`.
- **Flex layout**: `display: flex` lays its children out as flex items, in a
  row or a column and either one reversed. `flex-wrap`, `justify-content`,
  `align-items`, `align-self`, `align-content`, `gap`, `order` and `flex` with
  its grow, shrink and basis apply, and so do auto margins. Loose text in the
  container becomes an item of its own. An item's size starts from the width
  of its content, so a short caption stays on one line. A container keeps its
  items on one page when it fits a page. `inline-flex` still lays out inline,
  and an image does not stretch across its line.
- **Columns**: `column-count`, `column-width` and `columns` lay a block out in
  columns, with `column-gap` (1em when `normal`) and `column-rule` between
  them. The columns balance. A child with `column-span: all` takes the whole
  width and starts a new set of columns below it. A block kept together with
  `break-inside: avoid`, and a block with a background or a border, moves into
  a column whole. A set of columns that fits a page moves to the next page
  whole when the rest of the page is too short. A longer set starts a page,
  fills whole pages of columns in reading order, and balances the last page.
  Vertical writing does not lay out columns.
- **Grid layout**: `display: grid` places its children in tracks.
  `grid-template-columns` and `grid-template-rows` take lengths, percentages,
  `fr`, `auto`, `min-content`, `max-content`, `minmax()`, `fit-content()` and
  `repeat()`, including `repeat(auto-fill, ...)` and `repeat(auto-fit, ...)`,
  which repeat as often as the tracks fit. Items take line numbers, negative
  ones counted from the end, and spans, through `grid-column`, `grid-row` and
  `grid-area`. The others fill the rows in order. `gap`, `grid-auto-rows`,
  `grid-auto-columns`, `justify-items`, `justify-self`, `align-items`,
  `align-self`, `justify-content` and `align-content` apply. A fixed row grows
  to hold its tallest item, where CSS would let the item overflow into the
  next row. Named lines and areas, `grid-auto-flow: column`, `dense`,
  `subgrid` and `inline-grid` are not read: such an item is placed
  automatically. A grid keeps its items on one page when it fits a page.
- **Visual effects**: `opacity` below 1 paints the box and its content as one
  group at that opacity. `overflow` other than `visible` clips the content to
  the padding box, and the box's own border stays. `visibility: hidden` keeps
  the box's room and paints nothing of its own, and a child can show itself
  again with `visibility: visible`. A box with an opacity or a clip paints
  where a positioned box with `z-index: 0` paints. Inline elements take none
  of the three. `border-radius` rounds the background, the border, a block
  image and an overflow clip. `box-shadow` paints the outer shadows outside the
  box, and a blur fades out in a few steps rather than as a true blur. An inset
  shadow does not paint, a rounded border takes the colour of its first edge
  with a width, and in vertical writing corners stay square and shadows do not
  paint.
- **Backgrounds**: the first layer of `background-image` paints over the
  background colour: a raster or SVG file, or a `linear-gradient` with an
  angle or a `to` direction and positioned stops. `background-size` (`cover`,
  `contain` or lengths), `background-position` and `background-repeat` apply,
  as do the same parts of the `background` shorthand. A `url()` in a stylesheet
  resolves against that stylesheet's folder. A gradient whose stops differ in
  alpha does not paint, and a radial gradient does not paint.
- **Transforms**: `transform` with `translate`, `scale`, `rotate`, `skew` and
  `matrix`, about its `transform-origin`, moves the paint of a box and all it
  holds. It does not move the layout. The box paints where a positioned box
  with `z-index: 0` paints. Links and fragment rectangles move with it. The
  text that selection and search use moves with a box that only moves and
  scales, and stays where the layout put it for a turned or skewed box. Only
  two-dimensional transforms apply, and vertical writing does not transform.
  Known limitation:
  in `direction: rtl` text, `text-indent` shifts from the left edge rather
  than the inline-start (right) edge; lines still stay inside the content
  box.

### Mathematics

A `<math>` element renders as presentation MathML. A formula sits on the baseline of its line
and grows the line when it is taller. With `display="block"` it takes a line of its own,
centred, with half an em of room above and below.

- **Tokens**: `mi`, `mn`, `mo`, `mtext`, `ms` and `mspace`. A single-letter identifier is italic.
  `mathvariant` sets bold, italic, sans-serif and monospace, and it maps double-struck, script
  and fraktur letters to their Unicode math letters, such as ℝ.
- **Layout**: `mrow`, `mfrac` with `linethickness`, `msub`, `msup`, `msubsup`, `munder`,
  `mover`, `munderover`, `msqrt`, `mroot`, `mtable` with `columnalign`, `mstyle` with
  `displaystyle`, `scriptlevel` and `mathvariant`, `mphantom`, `menclose` with box and strike
  notations, the legacy `mfenced`, and `semantics`, which shows its presentation child.
- **Sizes and spaces**: each script level draws 0.71 times as large, down to half the base
  size. Operators take the spaces that TeX gives them. Fences stretch to the height of the row
  they enclose. A large operator such as ∑ grows in display style, and its limits sit above and
  below it there, and at script places inline.

The glyphs draw in the host font, and the engine measures them with the Standard 14 metrics, so
a formula needs no font in the book. The text of a page reads a formula as its `alttext`, or as a
linear form such as `x=(−b±√(b^2−4ac))/(2a)`, so selection, search and the reading order find it
in its place. Content MathML does not render: a formula shows its presentation markup. In
vertical text, a formula reads as its linear text.

## Books that are not quite right

Three habits of real EPUBs that the engine absorbs rather than rejecting.

**Encodings.** The spec says UTF-8 or UTF-16. Books ship Windows-1252 anyway,
sometimes while their own XML declaration claims UTF-8. Every entry is read by
weighing the evidence: a byte order mark first, then UTF-16 without one, then
the document's declaration (`<?xml encoding>`, `<meta charset>`, or the legacy
`http-equiv`), and finally the bytes themselves. Text that is not valid UTF-8
is read as Windows-1252, which never fails.

**Archives.** The reader handles ZIP64 records and entries whose sizes only
the trailing data descriptor knows, and it verifies every entry's CRC. A
mismatch is reported, not fatal: half a broken book beats no book.

**Resources the engine cannot render.** A manifest item can name a `fallback`
item. A spine item that is not XHTML or SVG shows the first document of its
fallback chain, and an image that does not decode draws the first fallback
that does. The chain stops after 16 hops and at an item it has seen. An
`epub:switch` shows its first `case` for XHTML, SVG or MathML, else its
`default`.

## Accessibility

`readingOrder()` gives one page's content in the order a reader that speaks
would say it, each item carrying the role its source element declared.

```kotlin
for (item in page.readingOrder()) {
    when (item.role) {
        KiteRole.HEADING -> speakHeading(item.text, item.headingLevel)
        KiteRole.IMAGE -> describe(item.text)          // the alt text
        else -> speak(item.text)
    }
}
```

`bounds` gives each item's place on the page in display space: points from the
top-left corner, y down. A text item takes the box of its words, and a picture
the box it is drawn in.

Left out: anything marked `aria-hidden="true"` or `role="presentation"`, and
an image with `alt=""`, which is how authors mark decoration. `aria-label`
replaces an element's text, and both `aria-hidden` and `epub:type` reach down
the subtree, so a footnote's paragraphs stay footnote.

A book can tell a speech engine how to say a word with the `ssml:ph` and
`ssml:alphabet` attributes. The item for that element then carries them as
`pronunciation` and `alphabet`, and holds that element's words only, so the
text around it goes to items of its own. `book.epubMetadata.pronunciationLexicons`
lists the book's pronunciation lexicons (PLS documents) for the engine to load:

```kotlin
for (path in book.epubMetadata.pronunciationLexicons) engine.addLexicon(book.resource(path))
for (item in page.readingOrder()) {
    if (item.pronunciation != null) speakPhonemes(item.pronunciation, item.alphabet) else speak(item.text)
}
```
