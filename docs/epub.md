# Reading EPUBs

`EpubDocument` reads EPUB 2 and EPUB 3 books, both reflowable and fixed-layout. It is in the `kitepdf-epub` artifact, which the `kitepdf` artifact includes. A book renders through the same canvas as a PDF, so the Compose viewer, the headless renderers, search and text selection work the same way for both formats.

[EPUB conformance](epub-conformance.md) shows how KitePDF does against the W3C EPUB 3 test suite, test by test.

## Opening a book

```kotlin
import io.github.yuroyami.kitepdf.epub.EpubDocument

val book = EpubDocument.open(epubBytes)
println("${book.pageCount} pages at ${book.pageWidth} x ${book.pageHeight} pt")

// From a file path, on the JVM, Android and Apple targets
val fromDisk = EpubDocument.openFile("/books/moby-dick.epub")
```

[Loading a document](loading.md) shows every other source, and how to open a file whose format you do not know.

`open` throws `EpubFormatException` when the book has no `META-INF/container.xml`, no package file, an empty spine, or no document that KitePDF can read. The message names the first failure.

KitePDF lays out a reflowable book at the page size that you give:

```kotlin
val book = EpubDocument.open(
    epubBytes,
    // The page size, in points
    pageWidth = 400.0,
    pageHeight = 640.0,
    // The body text size. The book's CSS sizes are relative to it.
    fontSize = 12.0,
    margin = 36.0,
)
```

A fixed-layout (pre-paginated) book keeps the viewport that its author gave it. The viewport is in CSS pixels, so a page of 800 by 1200 pixels is 600 by 900 points. `book.isFixedLayout` tells you which kind of book you have.

The viewport comes from the first `<meta name="viewport">` of a chapter, and from the first `width` and `height` in it, as EPUB Reading Systems 3.3 asks:

- A value is the number that it starts with, so `width=1000px` and `width=1000mm` are both 1000 pixels.
- A side that is `device-width`, `device-height` or missing takes its size from the reader. With `width=device-width, height=device-height`, the page has the size that you opened the book at.
- A viewport with a width only keeps the aspect ratio of the size that you opened the book at.

A book can mix fixed and reflowable chapters. Each chapter then keeps its own layout: a fixed chapter is one page at its viewport, and a reflowable chapter flows over pages of the reader's size. `book.isFixedLayout` is true only when every chapter is fixed.

`book.epubMetadata.rendition` gives the rendition properties of the whole book: layout, spread, orientation and flow. `book.renditionOf(chapter)` gives the values for one chapter, and the side of a spread that its first page asks for. `KiteDocLayout.Spread` reads them to pair the pages.

### Scrolled books

KitePDF does not cut a book whose `rendition:flow` is `scrolled-continuous` or `scrolled-doc` into pages. Each reflowable chapter is one page, as wide as the reader's page and as tall as its content, so `pageCountIn(chapter)` is 1. A page break in the book's CSS does not cut the chapter. A chapter shorter than a page keeps the page height.

- The `rendition:flow-*` property of a spine entry has priority over the value of the book.
- `EpubSettings.scrolled` has priority over both. `true` scrolls every book, `false` cuts every book into pages, and `null` (the default) follows the book.
- KitePDF always cuts a chapter in vertical writing into pages.
- `book.topOf(bookmark)` gives the height of a bookmark's fragment on its page, so a viewer can show an element far down a tall page.

`KiteDocLayout.forDocument(book)` gives the viewer layout that the flow asks for, see [The layout a document asks for](compose-viewer.md#the-layout-a-document-asks-for).

### Rolls

A roll (`rendition:layout roll` of EPUB 3.4) is a webtoon or a scroll: a strip of plates with no gap between them. Each chapter of a roll is one page at the size of its viewport, as in a fixed-layout book, and `book.isFixedLayout` is true.

`book.epubMetadata.rendition.isRoll` is true for a roll. It is also true for a pre-paginated book whose flow is `scrolled-continuous`, because EPUB Reading Systems 3.4 asks to read such a book as a roll. A roll ignores the `rendition:layout-*` properties of its spine entries.

`KiteDocLayout.forDocument(book)` gives a roll a `Continuous` strip, which shows the pages with no gap, whatever `pageSpacing` says.

## Rendering pages

`book.pages` is a `List<EpubPage>`, and each page renders through the shared canvas, as a `PdfPage` does:

```kotlin
// Any canvas works: AwtCanvas, SkiaCanvas, ComposeCanvas and others
val canvas = AwtCanvas(graphics2d)
book.pages[0].renderTo(canvas)
```

In Compose, `KiteDocView` takes a book where it takes a PDF:

```kotlin
KiteDocView(document = book, modifier = Modifier.fillMaxSize())
```

The [Compose viewer](compose-viewer.md) guide covers layouts, zoom, selection, highlights and link taps for every format. [Colors and themes](compose-viewer.md#colors-and-themes) shows the night and sepia themes, which change the colors at render time and do not lay out the book again.

## Opening a big book fast

A reflowable book has no pages until KitePDF lays it out, and a layout of a whole novel takes seconds. So KitePDF lays out one chapter at a time. A reader who opens the book at chapter 20 waits for chapter 20, not for chapters 0 to 19.

`EpubDocument.open` reads the container, the package file and the table of contents, and stops there. KitePDF reads and parses the HTML of a chapter when it first lays out that chapter. The viewer then lays out the rest of the book in the background, nearest chapter first. A chapter that lands above the reader does not move the page on screen. [Opening at a saved position](compose-viewer.md#opening-at-a-saved-position) shows the code.

### Positions: two kinds

| Type | What it is | How long it stays correct |
| --- | --- | --- |
| `KiteLocation(chapter, page)` | The place of a page in the current layout | Until the font size or the page size changes |
| `KiteBookmark` | The place of the reader in the text | Always, through any reflow |

Use a location to move around, and a bookmark to remember a place. `document.locate(bookmark)` turns a bookmark into a location, and lays out only that chapter to do it.

### Changing settings without losing the place

```kotlin
val mark = state.currentBookmark()
val bigger = book.withFontSize(16.0)
val newState = rememberKiteDocViewState(bigger, mark)
```

The reader stays on the same paragraph, and KitePDF lays out only that chapter before the page shows. In the sample app in `sample/`, pick "EPUB book" and change the font size: the page count changes, while the words on screen stay.

### What still lays out the whole book

- `pageCount` and `pages` are the totals of the whole book, so a read of either one lays out every chapter.
- `search` lays out every chapter while you go through its hits.
- `KiteDocLayout.Spread` pairs the pages of the whole book, so it needs every chapter. A chapter that lands later would change the pairs under the reader.

Use `knownPageCount` with `isComplete` for a running total, and `pageCountIn(chapter)` for one chapter.

The layout of any chapter also reads the first reflowable chapter. The writing mode (horizontal or vertical) is one decision for each book, and KitePDF takes it from that chapter. That is one more chapter, never the whole book. The hyphenation language comes from each spine item's own `xml:lang` or `lang`, and else from the language of the book.

### Where embedded fonts come from

An `@font-face` in a style sheet belongs to the whole book, and its `url()` resolves against the folder of that style sheet. An `@font-face` inside a document's own `<style>` block belongs to that document only, the same as every other rule in a `<style>` block. Put shared fonts in a style sheet, which is where books usually put them.

## Reader settings

`EpubSettings` holds everything that the settings screen of a reading app needs. KitePDF applies the reader's settings as a separate cascade origin, which has priority over the publisher's CSS (`!important` included):

```kotlin
val night = book.withSettings(
    book.settings.copy(
        // SERIF, SANS_SERIF or MONOSPACE, or null for the publisher's fonts
        fontFamily = ReaderFontFamily.SERIF,
        lineHeightScale = 1.4,
        textColor = RgbColor(0.9, 0.9, 0.9),
        backgroundColor = RgbColor(0.1, 0.1, 0.12),
        // null keeps what the book says
        justify = true,
        // null keeps what the book says
        hyphenate = true,
        // false keeps only the default and reader styles
        usePublisherCss = true,
    ),
)
```

| Setting | What it does |
| --- | --- |
| `pageWidth`, `pageHeight`, `fontSize`, `margin` | The page size and the body text size, in points, and the page margin |
| `fontFamily` | Replaces the publisher's fonts with a serif, sans-serif or monospace font |
| `lineHeightScale` | Multiplies the line height |
| `textColor` and `backgroundColor` | Replace the colors of the text and the page |
| `justify` | Justifies the text, or turns justification off |
| `hyphenate` | `true` hyphenates every chapter with the patterns of its language, even when the book's CSS does not ask for it. `false` turns hyphenation off everywhere. |
| `usePublisherCss` | `false` ignores the book's own CSS |
| `layoutCacheBytes` | The memory budget for laid-out chapters, see [Memory](#memory) |
| `resourceFetcher` | Gets images and fonts that the book names by URL, see [Resources on the web](#resources-on-the-web) |
| `scrolled` | Scrolls every chapter, or cuts every chapter into pages, see [Scrolled books](#scrolled-books) |

`withSettings`, and the shorthands `withFontSize`, `withPageSize` and `withMargin`, lay out the book again without a new parse. The new document shares the archive, the DOM, the CSS and the fonts, so a font size slider stays fast on a large book.

### Memory

A laid-out chapter takes about 165 bytes for each character of text on the JVM, so a whole novel does not fit the heap of an Android app. `layoutCacheBytes` (48 MB by default) limits what stays in memory:

- When the budget is full, KitePDF drops the pages of the chapters that the reader used least recently. A chapter lays out again on the next visit, in about a tenth of a second.
- Page counts, anchors and bookmarks stay after a drop, so navigation never waits.
- One chapter always stays, so a book that is a single spine document keeps that document.
- The chapters on screen also stay. `KiteDocView` names them with `keepChapters`, so a long press or a link tap on the page that the reader sees never waits for a layout, even while a search reads the rest of the book. An app that draws pages without the viewer can call `book.keepChapters(setOf(chapter))`.

A chapter that dropped while it was off screen lays out again off the main thread when it comes into view. `page.isContentLoaded` tells you whether the chapter of a page is in memory, and a read of it lays nothing out. `page.loadContent()` lays the chapter out again, so call it off the main thread. Until the content is back, `KiteDocView` draws only the paper of the page in the vectorized mode, and a long press or a link tap there finds no text and no links.

Embedded fonts are outside this budget and stay small. KitePDF parses each font file once for each book, however many style sheets or chapters declare it, and keeps one outline for each glyph that it has drawn.

```kotlin
val book = EpubDocument.open(bytes, EpubSettings(layoutCacheBytes = 24L * 1024 * 1024))
```

## Metadata and table of contents

```kotlin
// EPUB metadata: identifier, cover path, page direction and more
val meta = book.epubMetadata
println("${meta.title} by ${meta.creators.joinToString()}")

for (entry in book.tableOfContents.entries) {
    val page = entry.href?.let { book.pageOf(it) }
    println("${entry.label} is on page $page")
}
```

The label of an entry is the text of its link. An image in the link reads as its `alt`, or as its `title` when it has no `alt`, and KitePDF collapses the white space. So an entry that is only a picture still has a name in the outline.

`EpubDocument` is also a `KiteDocument`, the shared type that `PdfDocument` implements. `metadata`, `outline`, `pageCount` and the `textContent()` of each page work the same way for both formats, so you can write the reader UI once.

## Search, text and links

```kotlin
// Search the whole book
for (hit in book.search("whale")) {
    println("Page ${hit.pageIndex}: ${hit.text}")
}

// The text of one page, with its position
val text = book.pages[0].textContent()

// The page of a link inside the book
val target: Int? = book.pageOf("chapter2.xhtml#section-3")
```

A hit's `quads` are in display space: points from the top-left corner of the page, with y going down. The search follows the rules of [PDF search](reading.md#search). `pageOf` gives a page of the whole book, so it lays out the chapters before its target. `book.bookmarkOf(href)` gives a bookmark instead, with no layout, and `locate` lays out only the chapter of the bookmark.

### Notes and other link targets

Each link on a page says what it is for, in `EpubLink.kind`. A link with `epub:type="noteref"` or `role="doc-noteref"`, or with the `rel="footnote"` that EPUB 2 converters write, is a note reference. Glossary and bibliography references have their own kinds.

`linkTarget` reads the element that a link points to, without a layout of its chapter. A reader can show a footnote in a popup instead of a page turn:

```kotlin
// Link rectangles are in display space, with the smaller y in `bottom`
val tapped = page.links.first { x in it.rect.left..it.rect.right && y in it.rect.bottom..it.rect.top }
if (tapped.kind == EpubLinkKind.NOTE_REFERENCE) {
    book.linkTarget(tapped.href)?.let { note ->
        // One line for each paragraph
        showNote(note.text)
    }
}
```

`note.kind` is `FOOTNOTE`, `ENDNOTE`, `NOTE`, `GLOSSARY_ENTRY`, `BIBLIOGRAPHY_ENTRY` or `OTHER`. `note.bookmark` goes to the note itself, with `book.locate(note.bookmark)`.

The text of a note leaves out ruby readings and the link back to the call. When the `id` is on a short inline anchor, as in `<p><a id="fn1">1.</a> The note.</p>`, the text is the whole paragraph. A glossary term comes with its definitions. An `epub:type` on the head, on an element in it, or on the `html` element means nothing, so a link to the `<title>` of a chapter has the kind `OTHER` and no text.

`KiteDocView` does the hit test for you: its `onLinkTap` gets every tapped link before the viewer scrolls, with `kind` set for a reference, see [Notes in place](compose-viewer.md#notes-in-place).

A book can style the element that a link goes to with `:target`, such as a footnote that lights up when the reader gets to it. `KiteDocView` tells the book each fragment that the reader goes to, through a link, the table of contents or a bookmark. Another viewer can do the same with `setFragment`:

```kotlin
// The element with the id fn1 now matches :target
book.setFragment(chapter, "fn1")

// Gives "fn1"
book.fragmentOf(chapter)
```

KitePDF lays out again only a chapter whose style sheets use `:target`.

### Audio and video

A `<video>` element, or an `<audio>` element with controls, keeps a box of its own on the page. The box takes the element's `width` and `height`, then its CSS size, then the size of its poster. Without any of these, a video gets a 16:9 box at the width of the text, and an audio player gets a bar of 40 pt. The page paints the poster in the box, or a plain gray box. The page does not paint the element's own children (the text for a reader that plays nothing). An audio element without controls does not show, as in a browser.

To play them in `KiteDocView`, add the optional `kitepdf-media` artifact, see [EPUB audio and video](media.md).

`EpubPage.media` lists the media elements of a page with their box, kind, sources in order, poster and flags. The engine plays nothing itself. To use a player of your own, read the bytes with `book.resource` and give them to a player that you put over the box:

```kotlin
for (media in page.media) {
    val source = media.sources.firstOrNull { it.type == "video/mp4" } ?: continue
    val bytes = book.resource(source.href) ?: continue
    placePlayer(media.rect, bytes, autoplay = media.autoplay, loop = media.loop)
}
```

In `KiteDocView`, put the player in the `pageOverlay` slot with `Modifier.displayRect(media.rect)`, and it stays on the box at any zoom, see [Draw over a place on a page](compose-viewer.md#draw-over-a-place-on-a-page).

### Read-along narration

A book with media overlays pairs each piece of its text with a clip of audio:

- `book.mediaOverlayOf(chapter)` gives the clips of a chapter in document order.
- `book.epubMetadata.narration` gives the duration, the narrators and the active classes of the book.
- `book.locateFragment(clip.textHref)` gives the page that shows the text of a clip, with one rectangle for each line of that text, for a highlight.

```kotlin
val overlay = book.mediaOverlayOf(chapter) ?: return
for (clip in overlay.clips) {
    val audio = clip.audioHref?.let(book::resource) ?: continue
    val where = book.locateFragment(clip.textHref)
    play(audio, from = clip.clipBegin, to = clip.clipEnd, highlight = where)
}
```

The engine plays nothing itself. The optional `kitepdf-media` artifact reads a book aloud in `KiteDocView` with `KiteReadAloud`, see [Read a book aloud](media.md#read-a-book-aloud). When you play the clips yourself, show the active class as a highlight color, not as a new style, because a new style lays out the page again.

### Scripts and embedded documents

`book.isScripted(chapter)` tells you whether a chapter has scripts: its manifest item has the `scripted` property, or its document has a `script` element. `book.scriptedChapters` lists those chapters.

The library runs no script by itself. Without a script runner, a chapter with scripts shows what its markup shows, `noscript` content included. `EpubScriptRunner` of `kitepdf-javascript` runs the scripts of a book on KiteJS, over KitePDF's own parse and layout, see [Scripts in an EPUB](javascript.md#scripts-in-an-epub). An element with the `hidden` attribute does not show, as in a browser.

An `<iframe>`, and an `<object>` whose type is HTML or XHTML, keep a box on the page. The box takes the element's `width` and `height`, then its CSS size, and else 300 by 150 CSS pixels, as in a browser.

- A frame shows the document that it names when that document is in the book. KitePDF lays out that document at the size of the box and clips it to the box. A frame that names a URL outside the book stays empty.
- An object shows its document in the same way when the book has that document. Else it shows the fallback children of the element, and the box grows when they need more room.

`EpubPage.embeds` lists the boxes of a page, with the document that each box shows:

```kotlin
for (embed in page.embeds) {
    // A path in the book for book.resource, or a URL that your app can show in a view of its own
    showEmbedded(embed.rect, embed.href)
}
```

Each box stays on one page, as an image does: a box that does not fit on the rest of a page moves whole to the next page. Only an object taller than a page continues from page to page, and its `isWhole` is false there. `book.chapterPath(chapter)` gives the path of a chapter's own document in the book.

### Files the manifest leaves out

A book lists every file that it uses in the manifest of its package. When a chapter names a file of the archive that the manifest leaves out, KitePDF does not use that file: the image does not draw, the style sheet does not apply, and `book.resource(path)` gives null. EPUB Reading Systems 3.3 asks a reading system not to use such a file. Install a `KiteWarnings.sink` to see each file that KitePDF refuses.

### Resources inside the markup

A document can hold a small resource in itself as a `data:` URL: an image source, a CSS `url()` for a background or a font, or an SVG `href`. KitePDF loads it as it loads a file of the book, in Base64 or percent-encoding. `book.resource(url)` gives its bytes, `book.resourceType(url)` gives its media type, and `KiteDataUrl` in `kitepdf-core` decodes one on its own. A script cannot open a `data:` URL as a page of its own, as EPUB Reading Systems 3.3 asks.

### Resources on the web

A book can name an image, a font or a background by an `https` URL instead of a file in its container (EPUB 3.3, section 3.6). `book.hasRemoteResources(chapter)` tells you whether a chapter does: its manifest item has the `remote-resources` property, or its markup or styles name such a URL.

!!! warning
    A request to the URL tells its server that the reader opened the book. So the engine gets nothing from the network unless you give it a fetcher.

Use the fetcher of `kitepdf-net`, or one of your own:

```kotlin
// EpubResourceFetcher(client) comes from kitepdf-net
val settings = EpubSettings(resourceFetcher = EpubResourceFetcher(client))
val book = EpubDocument.open(bytes, settings)
```

A fetcher gets `https` URLs only, each one once for each book. KitePDF never gets a plain `http` URL, as EPUB Reading Systems 3.3 asks. The book keeps what it gets for as long as the book is open, up to 32 MiB.

The layout never waits for the network:

- An image whose markup gives its `width` and `height` keeps that box, and its page paints the picture when the bytes arrive. `EpubPage.remoteVersion` then changes, and `book.remoteArrivals` counts the arrivals, so a viewer can draw the page again.
- An image without both sizes, and a font, change the size of the layout. A chapter that lays out before they arrive keeps the layout without them for as long as that document lives, so its page count does not change. The next document of the book, such as one from `withSettings`, uses them.

To have them in the first layout, wait for them first, with a time limit of your own. `KiteDocView` waits two seconds:

```kotlin
book.awaitLayoutResources(chapter, 2.seconds)
book.prepareChapter(chapter)
```

A URL costs that wait once for each book. When a wait runs out, later waits skip the URLs that are still loading, so a font server that does not answer delays only the first chapter that needs it. The requests for those URLs continue.

Without a fetcher, or when a request fails, a remote image shows its manifest fallback, and else the empty box that its markup gives. A remote font gives way to the next source of its `@font-face`.

## Typography

The layout engine covers what real books use. The open issues with the [`area:epub` label](https://github.com/yuroyami/KitePDF/issues?q=is%3Aissue+is%3Aopen+label%3Aarea%3Aepub) list the CSS and typography features that it does not cover yet.

### Fonts

- TrueType, OpenType with CFF outlines, WOFF and WOFF2 (through a Brotli decoder in Kotlin).
- A fallback for each glyph, so text in mixed scripts never shows empty boxes.
- KitePDF measures an embedded font from its own tables. Text in a fallback font uses the metrics of the Standard 14 fonts, Cyrillic included.

### Hyphenation

- KitePDF uses the Knuth-Liang patterns and exception lists of the hyph-utf8 project: 71 sets for more than 60 languages.
- Each spine item uses its own language tag, and else the `dc:language` of the book. A chapter without a language uses American English.
- The rest of the tag selects among the sets of one language: `en-GB` for British English, `de-1901` for the traditional German spelling, `el-polyton` for polytonic Greek, and `sr-Latn` or `sr-Cyrl` for an alphabet of Serbian.
- KitePDF does not hyphenate a language that has no set in the library, such as Czech, whose patterns have only the GPL license.
- Punctuation next to a word does not stop the hyphenation of that word.
- `tools/generate_hyphenation.py` generates the sets again from a hyph-utf8 checkout.

### CJK and vertical writing

- Justification between characters, with the kinsoku rules for line breaks.
- Ruby annotations.
- Vertical writing (`vertical-rl` and `vertical-lr`), with upright CJK and rotated Latin text. Selection, search and link rectangles follow the columns, so a tap finds the glyph under it.

### Shaping

KitePDF shapes text to the glyphs that HarfBuzz gives:

- It applies every GSUB lookup type of an embedded font, contextual and chaining substitution included, for the script of each word and in the order of HarfBuzz's stages.
- It gives Arabic and Syriac their joining forms.
- It decomposes and composes characters first, as HarfBuzz normalizes them, and puts combining marks into the order that the font expects.
- It reorders the syllables of Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada and Malayalam around their features (reph, half forms, pre-base matras and pre-base consonants), as HarfBuzz's Indic shaper does. It reorders Khmer and Myanmar syllables as HarfBuzz's Khmer and Myanmar shapers do.
- It reorders Sinhala, Tibetan, Balinese, Javanese, Brahmi, Adlam and the other scripts of HarfBuzz's Universal Shaping Engine as that engine does.
- It splits Thai and Lao sara am into nikhahit and sara aa.
- It reorders text in a font without GSUB too.

This covers words in all these scripts, in Latin, Greek, Cyrillic, Arabic, Urdu Nastaliq, Syriac and Hebrew, and in the scripts outside the Basic Multilingual Plane that HarfBuzz shapes with its default shaper. A character outside the Basic Multilingual Plane (such as a mathematical letter or a CJK Extension B ideograph) draws from the font of the book.

GPOS attaches a mark to a base letter, to a part of a ligature, and to the mark below it, so two diacritics above one letter sit one above the other.

### Bidirectional text

- KitePDF implements the Unicode Bidirectional Algorithm (UAX #9) of Unicode 17 in full: the bidi class of every character, explicit embeddings and isolates, and paired brackets.
- Each paragraph resolves as a whole before it breaks into lines, and a forced line break ends a paragraph.
- A bracket at a right-to-left level draws as its mirror image.
- A chapter takes its base direction from its own `dir`, its CSS `direction`, or its own `lang` on `html` or `body`. The `dc:language` of the package never sets it, as EPUB Reading Systems 3.3, 3.7 asks. A chapter with none of these reads from left to right, even in an Arabic book.

### Layout

- Floats, tables (with `table-layout: fixed`), `position: absolute`, `relative` and `fixed`, inline images on the baseline, `::before` and `::after` content, `text-transform`, letter and word spacing, and small caps.
- A page paints in the order of CSS 2.1, Appendix E: backgrounds and borders, then floats, then lines and block images in document order, then positioned boxes by `z-index`.

### Flex layout

- `display: flex` lays out its children as flex items, in a row or a column, and in reverse.
- `flex-wrap`, `justify-content`, `align-items`, `align-self`, `align-content`, `gap`, `order`, `flex` (with grow, shrink and basis) and auto margins apply.
- Loose text in the container becomes an item of its own.
- The size of an item starts from the width of its content, so a short caption stays on one line.
- A container keeps its items on one page when they fit on a page.

### Columns

- `column-count`, `column-width` and `columns` lay out a block in columns, with `column-gap` (1em for `normal`) and `column-rule` between them.
- The columns balance.
- A child with `column-span: all` takes the whole width and starts a new set of columns below it.
- A block with `break-inside: avoid`, a background or a border moves into a column whole.
- A set of columns that fits on a page moves whole to the next page when the rest of the page is too short. A longer set starts a page, fills whole pages of columns in reading order, and balances the last page.

### Grid layout

- `display: grid` puts its children in tracks.
- `grid-template-columns` and `grid-template-rows` take lengths, percentages, `fr`, `auto`, `min-content`, `max-content`, `minmax()`, `fit-content()` and `repeat()`, with `repeat(auto-fill, ...)` and `repeat(auto-fit, ...)`.
- Items take line numbers (a negative number counts from the end) and spans, through `grid-column`, `grid-row` and `grid-area`. The other items fill the rows in order.
- `gap`, `grid-auto-rows`, `grid-auto-columns`, `justify-items`, `justify-self`, `align-items`, `align-self`, `justify-content` and `align-content` apply.
- A row of fixed size grows to hold its tallest item, where CSS lets the item go into the next row.
- A grid keeps its items on one page when they fit on a page.

### Visual effects

- `opacity` below 1 paints the box and its content as one group at that opacity.
- `overflow` other than `visible` clips the content to the padding box, and the border of the box stays.
- `visibility: hidden` keeps the space of the box and paints nothing of its own. A child can show itself again with `visibility: visible`.
- A box with an opacity or a clip paints where a positioned box with `z-index: 0` paints.
- `border-radius` rounds the background, the border, a block image and an overflow clip.
- `box-shadow` paints the outer shadows outside the box.

### Backgrounds

- The first layer of `background-image` paints over the background color: a raster or SVG file, or a `linear-gradient` with an angle or a `to` direction and stops at given positions.
- `background-size` (`cover`, `contain` or lengths), `background-position` and `background-repeat` apply, and so do the same parts of the `background` shorthand.
- A `url()` in a style sheet resolves against the folder of that style sheet.

### Transforms

- `transform` with `translate`, `scale`, `rotate`, `skew` and `matrix`, about its `transform-origin`, moves the paint of a box and its content. It does not move the layout.
- A box with a transform paints where a positioned box with `z-index: 0` paints.
- Links and fragment rectangles move with the box. The text for selection and search moves with a box that only moves or scales.

### Mathematics

A `<math>` element renders as presentation MathML. A formula sits on the baseline of its line, and makes the line taller when it is taller. With `display="block"`, a formula takes a line of its own, in the center, with half an em of space above and below.

- Tokens: `mi`, `mn`, `mo`, `mtext`, `ms` and `mspace`. An identifier of one letter is italic. `mathvariant` sets bold, italic, sans-serif and monospace, and maps double-struck, script and fraktur letters to their Unicode math letters, such as ℝ.
- Layout: `mrow`, `mfrac` with `linethickness`, `msub`, `msup`, `msubsup`, `munder`, `mover`, `munderover`, `msqrt`, `mroot`, `mtable` with `columnalign`, `mstyle` with `displaystyle`, `scriptlevel` and `mathvariant`, `mphantom`, `menclose` with box and strike notations, `mfenced`, and `semantics` (which shows its presentation child).
- Sizes and spaces: each script level draws 0.71 times as large, down to half the base size. Operators take the spaces that TeX gives them. Fences stretch to the height of the row inside them. A large operator such as ∑ grows in display style, with its limits above and below it. Inline, its limits go at the script places.

The glyphs draw in the host font, and the engine measures them with the Standard 14 metrics, so a formula needs no font in the book. The text of a page reads a formula as its `alttext`, or as a linear form such as `x=(−b±√(b^2−4ac))/(2a)`, so selection, search and the reading order find the formula in its place.

## Books that are not quite right

KitePDF reads these problems of real books instead of refusing the book.

### Markup that is not well-formed XML

EPUB 3.3 requires an XHTML or SVG chapter to be well-formed XML, but a book can still have a tag that never closes, or a stray `&`. The engine lays out what it can, and `markupErrors` tells you what is wrong:

```kotlin
for (error in book.markupErrors(chapter)) {
    println("line ${error.line}, column ${error.column}: ${error.message}")
}
```

`KiteDocView` shows the first error across the top of the chapter's first page, as EPUB Reading Systems 3.3 asks. Pass `showMarkupErrors = false` to hide it. `KiteXml.wellFormednessErrors` in `kitepdf-core` checks any XML text in the same way.

### Encodings

The specification allows UTF-8 or UTF-16, but books also come in Windows-1252, sometimes with an XML declaration that says UTF-8. KitePDF reads the encoding of each file from these signs, in this order:

1. A byte order mark.
2. UTF-16 without a byte order mark.
3. The declaration of the document (`<?xml encoding>`, `<meta charset>` or `http-equiv`).
4. The bytes themselves.

KitePDF reads text that is not valid UTF-8 as Windows-1252, which never fails.

### Archives

KitePDF reads ZIP64 records, and entries whose sizes only the data descriptor after them knows. It checks the CRC of every entry. When a CRC does not match, KitePDF logs a warning and still uses the bytes, so a damaged book still opens.

### Resources that KitePDF cannot render

A manifest item can name a `fallback` item:

- A spine item that is not XHTML or SVG shows the first document of its fallback chain.
- An image that does not decode draws the first fallback that does.
- The chain stops after 16 steps, and at an item that it has already passed.

An `epub:switch` shows its first `case` for XHTML, SVG or MathML, and else its `default`.

## Accessibility

`readingOrder()` gives the content of a page in the order that a screen reader reads it. Each item has the role that its source element gives:

```kotlin
for (item in page.readingOrder()) {
    when (item.role) {
        KiteRole.HEADING -> speakHeading(item.text, item.headingLevel)
        // The alt text
        KiteRole.IMAGE -> describe(item.text)
        else -> speak(item.text)
    }
}
```

`bounds` gives the place of each item on the page, in display space. A text item has the box of its words, and a picture has the box that it draws in.

- Anything with `aria-hidden="true"` or `role="presentation"` stays out, and so does an image with `alt=""`, which is how authors mark decoration.
- `aria-label` replaces the text of an element.
- `aria-hidden` and `epub:type` apply to the whole subtree, so the paragraphs of a footnote stay part of the footnote.

A book can tell a speech engine how to say a word, with the `ssml:ph` and `ssml:alphabet` attributes. The item for that element then has them as `pronunciation` and `alphabet`, and holds only the words of that element, so the text around it goes to items of its own. `book.epubMetadata.pronunciationLexicons` lists the pronunciation lexicons (PLS documents) of the book for the speech engine to load:

```kotlin
for (path in book.epubMetadata.pronunciationLexicons) {
    engine.addLexicon(book.resource(path))
}
for (item in page.readingOrder()) {
    val phonemes = item.pronunciation
    if (phonemes != null) speakPhonemes(phonemes, item.alphabet) else speak(item.text)
}
```
