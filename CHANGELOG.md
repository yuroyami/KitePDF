# Changelog

All notable changes to KitePDF are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- An implementation report against the W3C EPUB 3 test suite (w3c/epub-tests at 54092b42),
  in the suite's own format (`docs/epub-conformance.json`), and a docs page that lists its 206
  tests by section and level with each result, how it is known and the issue for each gap.
  `EpubConformanceTest` packs every test book and decides 157 of the results through the API;
  CI checks the suite out and fails when a result moves away from the report. The page's
  summary gives the counts as the gaps close (#497).

- `KiteCanvas.rasterStep(region, ctm, step)` runs a `KiteRasterStep` on the device pixels
  of a region: the step reads the backdrop, has content painted into rasters of the box
  through the canvas, works on the pixels in plain Kotlin and draws the result back in a
  blend mode at an alpha, all through a `KiteRasterScope`. A `KiteRaster` holds straight
  ARGB pixels. The method has a default that returns false, so a canvas written before it
  still compiles and its caller draws without the step. Every shipped canvas implements it:
  `AwtCanvas`, `SkiaCanvas`, `ComposeCanvas`, `Canvas2dCanvas`, `AndroidNativeCanvas` and
  `CoreGraphicsCanvas`. Which of them can read the backdrop, and where, is in the rendering
  guide (#209, #308).

- `KiteDocView(epubScripts = EpubScriptRunner(book))` runs a book's scripts in the viewer: a
  scripted chapter's run when the reader first reaches it, a tap on one of its pages goes to the
  scripts first and a link there is followed only when no script prevented it, the timers are
  pumped a frame at a time, a change of `location` goes the way of a tapped link, and a chapter
  that a script changed is drawn again with the pages it has now. The new parameter comes last,
  so a call that names its arguments compiles as before (#41).

- `kitepdf-javascript` runs the scripts of an EPUB's chapters on KiteJS, over the library's own
  parse and layout, with no web engine: `EpubScriptRunner(book)` runs a chapter's scripts when
  it opens, sends a tap to the element under it as `pointerdown`, `mousedown`, `pointerup`,
  `mouseup` and `click`, says whether a script prevented it, pumps the timers and passes on a
  change of `location`. A chapter is a window of its own, with a DOM over its tree, events,
  timers, `console`, an in-memory `localStorage` and `navigator.epubReadingSystem`; nothing
  reaches outside the book. What a script changes is laid out again, with the pages the chapter
  gains or loses, and the changes belong to the book. Each chapter's engine opens on a thread of
  its own, and at most `EpubScriptRunner.LIVE_CHAPTERS` stay open, one on JavaScript and
  WebAssembly: the chapter used least recently closes its engine, and its scripts start over
  from its markup when it opens again (#495, #498). `EpubScriptPolicy` bounds each call. In
  `kitepdf-epub`, `EpubScriptSession` does the same over any `KiteScriptEngine`, with
  `liveChapters` for the bound, and `EpubScriptHandler` is what a viewer needs from it. Each
  book has an origin of its own, shared by its chapters (#500); a script that measures after a
  change gets the layout as the tree stands, as in a browser (#499); an element's `style`
  answers CSS property names only and writes numbers back as a browser does, so jQuery 1.7.1
  runs; and `failures` keeps the last `EpubScriptSession.MAX_FAILURES`. The scripted books of
  the public corpus, six W3C and IDPF samples, run in `ScriptedBookGateTest` (#496). `KiteXmlNode.Element.attrs` and `KiteXmlNode.Text.text` can be set, for
  the tree a script layer changes (#41).

- `EpubDocument.chapterChanges` counts the changes the book's scripts made to its chapters,
  for every document over the book, and `EpubPage.chapterVersion` says how many reached a
  page's chapter. A viewer takes the page counts again when the first moves, since a change
  can add or take pages, and draws a page again when the second does. A chapter that scripts
  run in keeps one page even when it has nothing to show, so that its scripts can fill it
  (#41).

- `EpubDocument.chapterPath(chapter)` names the zip path of a chapter's own document. `EpubLink` has a public
  constructor, for a link that comes from outside the page's markup. In `KiteDocView`, an
  element of the `pageOverlay` hands such a link to `KitePageOverlayScope.followLink(href,
  rect)`, and it goes the way of a tapped link: `onLinkTap` sees it first, then the viewer
  follows a link inside the book (#41).

- SVG draws `filter`: a `<filter>` with any of the seventeen primitives of Filter
  Effects 1, from `feGaussianBlur`, `feOffset`, `feFlood`, `feMerge`, `feBlend`,
  `feComposite`, `feColorMatrix`, `feComponentTransfer` and `feDropShadow` to
  `feMorphology`, `feTile`, `feImage`, `feTurbulence`, `feConvolveMatrix`,
  `feDisplacementMap` and the two lighting primitives with their three lights,
  with named results, subregions, both kinds of units and
  `color-interpolation-filters`; and the filter functions `blur()`,
  `drop-shadow()`, `grayscale()`, `sepia()`, `saturate()`, `hue-rotate()`,
  `invert()`, `opacity()`, `brightness()` and `contrast()`, alone or in a chain. The
  filter runs in plain Kotlin on the pixels of a raster step, so it draws on every
  canvas that implements `rasterStep`; another canvas draws the element unfiltered
  (#209).

- A book's images, fonts and backgrounds that it names by an `https` URL load through
  `EpubSettings.resourceFetcher`, an `EpubResourceFetcher` (EPUB 3.3, 3.6). `kitepdf-net`
  ships one on a Ktor client, `EpubResourceFetcher(client)`, capped at 16 MiB a resource.
  Nothing is fetched without a fetcher, and a plain `http` URL never is. An image with a
  declared size keeps its box and paints once its bytes land, which `EpubPage.remoteVersion`
  and `EpubDocument.remoteArrivals` announce; `KiteDocView` draws the page again then. An
  image without one, and a font, size the layout, so `EpubDocument.fetchRemoteResources`
  fetches them ahead of it. `EpubDocument.awaitLayoutResources` waits for them within a
  time limit, once per URL for the whole book, so a server that hangs delays one chapter
  and not each (#492); `KiteDocView` waits up to two seconds before it lays a chapter out.
  `EpubDocument.hasRemoteResources` reads the manifest's `remote-resources` property.
  `kitepdf-epub`, and with it the `kitepdf` artifact, now depends on
  `kotlinx-coroutines-core`. The `EpubSettings` constructor and `copy` take a
  thirteenth parameter with a default, so source compiles unchanged, while a binary built
  against 0.12.0 that constructs or copies an `EpubSettings` needs a rebuild (#38).

- `KiteDocument.prepareChapter(chapter, checkpoint)` lays a chapter out in steps and calls
  `checkpoint`, a suspend function, between two, so that a caller on a thread that must
  keep drawing frames can suspend there. An `EpubDocument` steps through each block as it
  builds the chapter's boxes and as it lays them out, then through the pages; any other
  document prepares the chapter at once. In a browser the viewer lays a chapter out in
  slices of 8 ms with a frame drawn between two, where a long chapter froze the page for
  its whole layout. A wrapper that overrides `prepareChapter(chapter)` with `by`
  delegation should override this one too (#389).

- `io.github.yuroyami:kitepdf-media`, a new optional artifact: `KiteMediaOverlay()` in
  the viewer's `pageOverlay` plays the `<video>` and `<audio>` elements of an EPUB page
  on their boxes, with KitePlayer, which decodes through FFmpeg. A tap or `autoplay`
  starts an element (autoplay muted, until the first touch of its controls), and
  `controls`, `muted` and `loop` work as in a browser. It plays the book's own files,
  and an `https` URL only with `allowRemote = true`; a source of any other scheme,
  `http` and `file` included, never plays (#491). It publishes Android (`minSdk` 26),
  iOS and the desktop JVM. Without it, nothing changes and no codec ships (#31).
  The bar of a video has a full-screen button, which shows the same player over the
  whole window in a `Dialog`, so the video goes on without a break; the button, a back
  gesture or Escape brings it back to its box. On Android, full screen hides the system
  bars and turns a landscape video to landscape in an activity that handles the turn
  itself, and on iOS 16 and later it asks the window scene for landscape (#482).
- `EpubPage.document`: the book a page belongs to, for the bytes of its media (#31).
- `KiteReadAloud(state, playing)` in `kitepdf-media` reads an EPUB aloud with its media
  overlays and follows the text in `KiteDocView`. It starts at the first clip on the
  reader's page, plays each clip from its `clipBegin` to its `clipEnd` out of the book's
  own audio, and goes on with the next chapter that has an overlay (EPUB Reading Systems
  3.3, 9.1 and 9.2.2). The text being read gets one entry in `KiteDocViewState.highlights`,
  with the id `READ_ALOUD_HIGHLIGHT_ID`, and the viewer turns to its page. The book's
  active class shows as that highlight and is never added to the element. A clip without
  audio, or with audio the player cannot open, is skipped (#36).

- `FontSpec.language` carries the BCP 47 tag of a substitute font's language, and
  `FontSpec.cjkScript`, `FontSpec.hostFaces` and `FontSpec.languageSample` tell a
  custom canvas which host faces draw it. A PDF CIDFont names its language through
  the Adobe character collection of its CIDSystemInfo or its predefined CMap. The
  constructor and `copy` take a fifth parameter with a default, so source compiles
  unchanged, while a binary built against 0.12.0 that constructs a `FontSpec` needs
  a rebuild (#472).

### Fixed

- A table of contents entry made of an image takes the image's text alternative as its label,
  its `alt` before its `title`, as EPUB Reading Systems 3.3 asks, where it had an empty one; an
  SVG gives its `title` and MathML its `alttext`, a link with nothing else gives its own `title`,
  and a label's white space collapses. A heading over a list of entries, `<li><span>Plates</span>
  <ol>...`, no longer takes the label and link of its first entry (#526).

- `EpubDocument.linkTarget` ignores an `epub:type` on the head, on an element in it, or on the
  html element, as EPUB 3.3 asks, so a link to a `<title epub:type="footnote">` is a place in the
  chapter, of kind `OTHER` with no text, and not an empty footnote (#527).

- A fixed-layout page reads its viewport tag as EPUB Reading Systems 3.3 asks: the first
  `<meta name="viewport">` of a chapter sizes it and a later one is ignored, the first `width`
  and `height` in it count, and a value is the number it starts with whatever unit follows, so
  `width=1000px, height=600mm` is 1000 by 600 pixels. A side given as `device-width` or
  `device-height`, or not given, comes from the reader's page, a lone width or height keeping
  its aspect ratio. Of two overrides of one property on a spine entry, such as
  `rendition:layout-reflowable rendition:layout-pre-paginated`, the first counts (#502).

- A package metadata value has the white space at its ends stripped and each run inside
  collapsed to one space, as EPUB 3.3 asks: `<dc:creator>  Dave    Cramer </dc:creator>` reads
  "Dave Cramer". A value split by a comment reads whole, and an element of white space alone,
  such as an empty `dc:creator`, is no value (#515).

- An element with the `hidden` attribute no longer shows, as in a browser: the user agent sheet
  now has `[hidden] { display: none }` (#41).

- An inline frame or an HTML object of an EPUB keeps to one page, as an image does. A frame
  that moved whole to the next page was listed on the page before it too, with a box that
  ran past its bottom, and an object split between two pages with its fallback. A chapter of
  one object without fallback had no page at all. `EpubEmbed.isWhole` is false only for an
  object taller than a page (#41).
- A non-isolated transparency group whose paints blend composites as ISO 32000-1,
  11.4.8 says wherever the canvas can read the page under it. Its paints blend with the
  page, and the page's part comes out of the group again before the group composites in
  its own alpha and blend mode, so a group with a blend mode of its own no longer paints
  as if it were isolated, nor does one at an alpha below 1 on Android and CoreGraphics.
  In a non-isolated knockout group, each object blends with the page and replaces what
  the objects before it left within its shape, where it blended against a transparent
  backdrop before. The group goes through a raster step: on AWT and Canvas2D always, on
  Skia outside a layer, on a CoreGraphics bitmap context, and on the bitmaps of
  `AndroidPdfBitmapRenderer` and `KitePageRasterizer`. Where the canvas cannot read the
  page, as a Compose canvas on screen cannot, the group paints as a layer, as before, and
  so does a knockout group whose objects would take more than 100 million pixels of
  renders, three of its box for each (#308).
- An EPUB image that gives both sizes and `object-fit: contain` keeps the box its markup
  asks for, and its picture fits inside it, centred. A block image shrank its box to the
  picture's aspect, so the text after it moved up, and an inline one stretched its
  picture over the box as `fill` does (#490).
- A non-embedded CJK font draws in a host face of its own language and style: a
  Japanese Mincho font draws in a Mincho face, not a Chinese Song face or a Gothic
  one. The Serif flag of the font descriptor picks Mincho over Gothic. On Skia and
  CoreGraphics such text lost its Han, kana and Hangul characters, since the Latin
  faces those canvases picked have none (#472).
- In a desktop Compose scene that draws on a thread other than the AWT event dispatch
  thread, such as the `ImageComposeScene` of a test, a screenshot tool or a server, a
  Vectorized page and the form layer draw their system-font text again. Since #428
  such a page showed no text, and an EPUB page none at all. Their canvas draws inside
  the scene's own draw pass, where Compose draws the scene's text (#464).

### Changed

- `kitepdf-javascript` depends on `kitepdf-epub`, whose scripts it now runs (#41).

- `KitePageRasterizer` keeps the coverage of the glyphs it draws from their own outlines,
  up to 4 MB, as MuPDF keeps its glyph cache. It fills each glyph's path once for each size
  and position, adds a run of opaque text into one bitmap and draws that once, so a page of
  3,800 Helvetica glyphs spends about 60% less on its text, and a page drawn again fills no
  glyph. A glyph's origin rounds to a quarter of a pixel below 24 pixels to the em, so text
  in a raster can move by an eighth of a pixel at most. Vectorized pages still draw each
  glyph as a path, so that they stay sharp under a pinch (#382).

- `KitePageRasterizer.rasterizeOffMain` draws system-font text on its own thread, so a
  page with such text, as in a book without embedded fonts, renders off Main in one
  pass and takes no time on the UI thread. On the desktop JVM, iOS and macOS the text
  goes through Skia's paragraph engine with the family names, style and glyph
  rasterization that Compose's text uses (#131), and on Android through Android's own
  text stack with the paint Compose's text uses (#487), so it draws the same pixels,
  fallback faces included. On the desktop JVM a page of such a book at 1080 by 1728
  rasters in about 13 ms, where it took 17 ms, 16 of them on the UI thread. A
  `ComposeCanvas` made directly draws such text and host glyph outlines on any thread
  there too. The synchronous `rasterize` still requires the UI thread, and the browser
  keeps Compose's text on its one thread.

- A PDF's JPEG no longer decodes when it loads only to check the file, which took
  about a third of a page's render time; its first draw decodes it. A file whose
  headers KiteImageCodec reads and whose data it cannot decode now reaches a canvas
  as a `Kind.RAW` image whose decode fails, with the file in `encodedBytes`, and the
  built-in canvases hand it to the platform decoder there, as they do a `Kind.JPEG`
  image. A custom `KiteCanvas` that draws such a file through its platform should do
  the same (#475).

- A media element without a poster paints a plain grey box. The play triangle on it
  promised playback that the engine does not do; `kitepdf-media` draws its own play
  button (#31).

- Vectorized pages share a bounded cache of converted image bitmaps across redraws
  and page recycling. `imageCacheBudgetBytes` defaults to 16 MiB per viewer state;
  zero disables reuse between draws. Image identities survive ordinary PDF, CBZ
  and EPUB source reconstruction without retaining decoded image buffers. Cold
  conversion still runs on the UI thread (#371).

- Compose synchronous `KitePageRasterizer.rasterize` calls now require the platform UI
  thread and throw before drawing on another thread. Direct `ComposeCanvas` host text
  has the same check. Migrate worker exports to `rasterizeOffMain`, which now also accepts
  live form state and copies it for a coherent export. Headless JVM exports dispatch host
  text asynchronously to the AWT event dispatch thread without a coroutine Main provider.
  Custom desktop Compose scenes must use that thread for their own text too (#428).

## [0.12.0] - 2026-09-29

This release checks the digital signatures of a signed PDF, gives every page a
reading order for screen readers, and lays out MathML, flex, grid and
multi-column pages in EPUB. It decodes a PDF's JPEG and JPEG 2000 images at the
size they draw, and moves the image engine to KiteImageCodec 0.2.0. It closes
127 issues. Most of them are Compose viewer bugs in threading, layout, gestures
and forms. Some API changes break source compatibility with 0.11.0. The Changed
section lists them.

### Added

- EPUB books render presentation MathML. A formula sits on its line, or centred
  on a line of its own with `display="block"`, with fractions, scripts, limits,
  radicals, tables, stretching fences, `mstyle`, `menclose` and the legacy
  `mfenced`. Scripts shrink by 0.71 a level down to half size, and operators take
  TeX's spaces. Text extraction, search and the reading order read a formula as
  its `alttext` or a linear form, in its place in the sentence. An `epub:switch`
  case for MathML now renders (#32).

- `KitePage.isContentLoaded` says whether a page's content is in memory, and
  `KitePage.loadContent` brings it back. An EPUB page answers false while the
  layout budget has dropped its chapter. `KiteDocView` uses them, so a Vectorized
  draw, a long press or a link tap on such a page no longer lays the chapter out
  on the UI thread. The page shows its paper until the content is back (#377).

- `PdfDocument.signatures` and `PdfSignature.validate` check the digital signatures
  of a signed PDF. A signature is `Valid`, `DigestMismatch`, `Invalid`,
  `Unsupported` or `Malformed`. The result names the signer, gives the certificate
  chain, says whether the chain reaches a trust anchor of the caller, and says
  whether the file changed after signing. KitePDF checks CMS, CAdES and PKCS #1
  signatures and document timestamps, with RSA (PKCS #1 v1.5 and PSS) and ECDSA on
  P-256, P-384 and P-521 (#203).
- `PdfCertificate.revocation` says whether each certificate of a signature's chain
  is `Good`, `Revoked` (with `revokedAt`) or `Unknown`. KitePDF reads the CRLs and
  OCSP responses of the document security store, of the signature, and of
  Acrobat's `adbe-revocationInfoArchival` attribute, and those that the caller
  passes to `validate` as `revocationData`. Only data that the issuer signed
  counts, or an OCSP responder that the issuer delegated to (#447).
- `PdfSignatureValidation.changes` lists what later revisions changed after a
  signature, such as a field value, an annotation, a signature, the document
  security store or page content, and says whether each change is permitted.
  `PdfSignature.certificationLevel` gives the DocMDP level of a certification
  signature, and a `/Lock` on the signature field applies too.
  `areChangesPermitted` sums it up (#448).

- `PdfDocument.fontCacheBudgetBytes` and `dropFontCache`: a document parses each
  font once for all the pages, thumbnails and text extractions that use it, within
  a budget of 32 MB by default. `PdfFont.retainedBytes` gives the estimate that
  the budget counts (#383).
- `KiteImageData.toShrunkRgbaBytes` converts an image and averages it down a band
  of rows at a time. Every canvas uses it, so a large scan drawn small no longer
  becomes one full-size RGBA array first, and a scan above 40 megapixels draws
  when it is drawn small (#381).
- `KiteDocument.keepChapters` names the chapters on screen, and `EpubDocument`
  keeps them laid out past its layout budget. `KiteDocView` calls it, so a long
  press, a link tap or a Vectorized draw on a visible page no longer lays a
  dropped chapter out again on the UI thread (#377).
- `KiteDocView.pageOverlay` draws over each page in the page's own frame.
  `KitePageOverlayScope.pageRect` and `displayRect` place an element on a
  rectangle of the page, and the element stays there at any zoom (#30).
- `KiteDocViewState.pageRectToViewport` and `displayRectToViewport` give the
  viewport rectangle of a rectangle on a page. They are `hitTest` and
  `hitTestDisplay` in reverse (#30).
- `EpubMetadata.rendition` and `EpubDocument.renditionOf` give the rendition
  properties of EPUB 3.3: layout, spread, orientation and flow for the book and
  for each chapter, and the side of a spread that a chapter's first page asks
  for (#37).
- `KiteDocLayout.Spread.firstPageAlone` shows the first page alone and pairs the
  pages after it (#37).
- `EpubDocument.isScripted` and `scriptedChapters` tell which chapters are
  scripted: the manifest marks them, or they have a `script` element (#40).
- An `<iframe>`, and an `<object>` of an HTML or XHTML type, keep a box on the
  page, 300 by 150 CSS pixels unless they set a size. An object's box shows its
  fallback children. `EpubPage.embeds` lists the boxes with the document that
  each one embeds (#40).
- `EpubReadingItem.pronunciation` and `alphabet` carry a book's `ssml:ph` and
  `ssml:alphabet`, and the reading order gives an element with a pronunciation
  an item of its own. `EpubMetadata.pronunciationLexicons` lists the book's
  pronunciation lexicons (#39).
- `EpubDocument.mediaOverlayOf` reads a chapter's media overlay into its clips,
  with nested sequences flattened, and `EpubMetadata.narration` gives the
  book's duration, narrators and active classes. `EpubDocument.locateFragment`
  gives the page and the line rectangles of the element that a fragment names,
  so an app can highlight the clip that plays (#36).
- EPUB pages paint `opacity`, `overflow` and `visibility`. A box below full
  opacity paints with its content as one group, an overflow other than
  `visible` clips the content to the padding box, and a hidden box keeps its
  room and paints nothing of its own (#28).
- EPUB pages paint `border-radius` on the background, the border, block images
  and overflow clips, and `box-shadow` as outer shadows whose blur fades in a
  few steps (#28).
- EPUB pages paint a `background-image`, a raster or SVG file or a
  `linear-gradient`, with its size, position and repeat. A `url()` in a
  stylesheet now resolves against that stylesheet's folder (#28).
- EPUB pages paint two-dimensional `transform`s about their `transform-origin`.
  Links and fragment rectangles move with the box, and so does the page text
  of a box that only moves and scales (#28).
- `KitePage.readingOrder()` gives the content of a page of any format in the
  order a screen reader says it, as `KiteReadingItem`s with a `KiteRole`. A PDF
  page reads its structure tree, with the role map, `/Alt`, `/ActualText` and
  `/Lang`, and leaves artifacts out; a page without tags reads its text blocks
  in layout order (#208).
- `KiteDocView` gives a screen reader each page's text in reading order, one node
  per item at its place, its links as buttons, and with a `scripts` handler its
  form fields with their state. A link or a field node acts as a tap does.
  `KiteReadingItem.bounds` gives an item's place on its page, and
  `KiteViewerStrings.link` names a link that has no words. A PDF figure takes its
  place from its layout `/BBox` attribute, or else from what it paints. A tagged
  PDF page that holds only figures now reads them, and content that a form draws
  inside a tagged sequence of the page joins that sequence (#427).
- `KitePage.hyperlinks` gives the links of an XPS or an SVG page as `KiteLink`s.
  Each covers the box of what it draws, and leads to an address outside the
  document or to a page and a height inside it. XPS reads `FixedPage.NavigateUri`
  and the `LinkTarget` names of its page references, and SVG reads `<a href>`.
  `KiteDocView` follows these links and names them to a screen reader.
  `KitePath.bounds` gives the box of a path under a matrix (#433).
- EPUB lays out `display: flex` (CSS Flexible Box Layout 1): rows and columns
  and their reversed forms, wrapping, `justify-content`, `align-items`,
  `align-self`, `align-content`, `gap`, `order`, `flex` and auto margins. A flex
  container kept its children stacked in one column before. A container that
  fits a page moves to the next page whole, and a link that is itself a block,
  such as a flex item or `a { display: block }`, now covers its box (#33).
- EPUB lays out columns (CSS Multi-column Layout 1): `column-count`,
  `column-width`, `columns`, `column-gap`, `column-rule` and
  `column-span: all`. The columns balance. A set that is taller than a page
  starts a page and fills whole pages of columns, so the text still reads column
  after column. A block with columns laid out as one column before (#34).
- EPUB lays out `display: grid` (CSS Grid Layout 1): track lists with lengths,
  percentages, `fr`, `auto`, `minmax()`, `repeat()` and `repeat(auto-fill)`,
  items placed by line numbers and spans or filled in row by row, `gap`, auto
  rows and columns, and the alignment properties. A grid kept its children
  stacked in one column before. In a flex or grid layout, an image without a
  size of its own counts at its natural size (#35).
- A rich text field draws its rich value (`/RV`, XHTML with `/DS` as the default
  style): paragraphs and spans with their font family, size, weight, style,
  colour, alignment and underline. A multi-line text field wraps its value and
  breaks it at line breaks, where it drew one line before. Filling a rich text
  field keeps `/RV` in step with the new value, and `PdfFormField.isRichText`
  says which fields have one (#204).
- SVG draws `<pattern>` paint servers and `mask`. A pattern tiles its content over
  a fill or a stroke with its units, `viewBox`, `patternTransform` and `href`
  chain. A mask shows the element by the luminance of its content, or by its
  alpha with `mask-type: alpha`, inside the mask's region (#209).

### Changed

- Breaking: the image engine is now KiteImageCodec 0.2.0, the new name of
  KiteImage, and `kitepdf-core` keeps it to itself. KitePDF's own API has no
  engine type in it, so an app that uses only KitePDF changes nothing. An app
  that calls the engine directly now adds `io.github.yuroyami:kiteimagecodec`
  itself, imports the package `io.github.yuroyami.kiteimagecodec`, and calls
  `KiteImageCodec.decode` where it called `KiteImage.decode`.
- `kitepdf-compose-viewer` passes Compose runtime, foundation and ui on to apps,
  because `KiteDocView`'s API takes and returns their types. An app that already
  declares Compose sees no change.
- A PDF's JPEG or JPEG 2000 image without a mask keeps only its encoded data,
  and a draw decodes it at the size it draws: at a half, a quarter or an eighth
  of its pixels, inside the JPEG's own transform or by dropping wavelet levels.
  A 3,000 by 2,000 JPEG loaded and drawn at an eighth allocates 2.3 MB, where
  the full decode alone took 24 MB. The image cache counts such an image as its
  file size, so a scan stays cached. `KiteImageData.retainedBytes` gives what an
  image holds (#381).

- Breaking: `KiteDocView.onLinkTap` receives every link that the reader taps, in
  every format, before the viewer acts on it. It used to get only the links the
  viewer could not follow. Return `true` to keep the viewer from acting, or
  `false` to let it follow the link, turn the page or run the script as before.
  A host can now show a note in place, or keep a history for a back button, in a
  PDF, an EPUB, an XPS or an SVG file alike (#444). A host that returns `true`
  for every link stops the viewer from following links inside the document.
- Breaking: `KiteLinkAction` gives `uri`, `target`, `kind`, `pageIndex` and
  `rect` for every format. `KiteLinkAction.Uri` is gone: an EPUB link arrives as
  `KiteLinkAction.Epub`, and an XPS or SVG link as `KiteLinkAction.Plain`. The
  new `KiteLinkKind` says whether a link refers to a note, a glossary entry or a
  bibliography entry.
- Breaking: `KiteDocView.onEpubReferenceTap` is removed. A reference now reaches
  `onLinkTap` with its `kind` set.
- In `KiteDocLayout.SinglePage`, a link inside an EPUB book that `onLinkTap` does
  not take now goes on to `onTap`, as a PDF link does. The view could not move,
  so the tap did nothing.

- The Compose viewer asks the platform decoder for a JPEG at 1/2, 1/4 or 1/8 of
  its size when it draws that small, and averages only the rest. That is the path
  for a JPEG that the core cannot decode, such as an arithmetic-coded one. Skia
  decodes straight to the smaller size, and Android passes it as `inSampleSize`.
  On Android, `ImageDecoder.decodeRaw` no longer copies the whole image into a
  second `IntArray` (#381).

- A page whose bitmap the `maxBitmapLongSide` cap cuts down now draws the part
  on screen again in tiles of 1,024 pixels at full resolution, over the capped
  bitmap. Deep zoom stays sharp at any zoom, and a very tall page is sharp at
  zoom 1. The tiles follow a pan once it rests, and they go through the bitmap
  cache, so panning back is a lookup (#375).

- Two pages rasterize at once, and a page on screen renders before the pages
  drawn ahead of it and before thumbnails. One process-wide lock used to render
  every page in arrival order, so thumbnails and prefetched pages could delay the
  page that the reader looks at. The page bitmap cache is now thread-safe (#370).

- In a browser, `KiteDocView` lays out an EPUB chapter away from the reader only
  after the view has rested for 400 ms, and it renders a page in one pass instead
  of two. The reader's chapter and its neighbours still lay out at once. A scroll
  or a pinch no longer stutters while a large book loads (#389).

- `EpubRole` and `EpubReadingItem` are now deprecated names of the shared
  `KiteRole` and `KiteReadingItem`, and `epubType` of `sourceType`. Code that
  uses the old names still compiles, and a library built against the old
  classes needs a rebuild (#208).

- `KitePage.drawsHostFontText` lets a page say that it draws text in a host
  font. `KitePageRasterizer.rasterizeOffMain` then renders the page on Main at
  once, and a page that drew such text renders there at once the next time.
  Such a page was drawn in full off Main first, and that bitmap was thrown
  away. An EPUB page answers from its layout, so a book without fonts of its
  own renders each page once, not twice (#131).

- `KiteDocLayout.Spread` pairs pages as the document declares: an EPUB's
  page-spread properties and `rendition:spread`, and a PDF's `/PageLayout` of
  TwoPageRight or TwoColumnRight. A document that declares nothing pairs as
  before (#37).
- An EPUB that mixes fixed-layout and reflowable chapters lays out each chapter
  its own way. `EpubDocument.isFixedLayout` is true only when every chapter is
  fixed (#37).

- A `PdfDocument` whose `/Count` declares more than 200 pages reports its one
  chapter as not ready until `prepareChapter(0)` builds the page list, and
  `isComplete` is false until then. `KiteDocView` builds the list off the main
  thread and shows `chapterPlaceholder` meanwhile, where the first frame built
  every page object (#387).
- `KiteSelectionMenu` places itself above the selection, or below it when there
  is no room above. Its `alignment` is now nullable, and null is the default; pass
  an alignment to pin the menu as before. A long press on a page without text no
  longer stops the page from panning (#408).
- `EpubPage`, `XpsPage` and `SvgPage` stop a render once its `KiteCancellation`
  reads true: a book page between two lines or boxes, an XPS or SVG page before
  its next element. They drew the whole page before (#370).
- `KiteDocView` turns the page for a PDF link that names NextPage, PrevPage,
  FirstPage or LastPage, and runs a script link in its `scripts` handler, once
  `onLinkTap` lets the link go. Both only went to `onLinkTap` before. A link to a place on a page
  (`/XYZ`, `/FitH`, `/FitBH`, `/FitR`) brings that place to the top of the
  viewport at the reader's zoom. A vertical `Continuous` strip scrolls there, and
  a horizontal strip or a pager pans across the page as far as the page lets it
  (#433).
- On the desktop and the web, Ctrl or Cmd with the wheel zooms `KiteDocView`, and
  the page keys and Ctrl or Cmd with plus, minus and 0 work once a press gives the
  view the focus. A mouse press and drag on text selects at once (#411).
- The pan of a zoomed page goes on after a quick release and slows down, and in
  `Paged` and `Spread` a drag past the edge of a zoomed page turns it (#410).
- `KiteDocViewState.selectionBounds` gives the box of the selection in viewport
  pixels (#408).
- A screen reader finds each page, the page buttons, the thumbnails, the colours
  of the selection menu and the input of a form field by name. `KiteViewerStrings`
  and `LocalKiteViewerStrings` let a host translate the names (#427).
- EPUB books follow the manifest fallback chain: a spine item that is not XHTML or
  SVG shows its fallback document, and an image that does not decode draws its
  fallback. An `epub:switch` shows one branch, where it painted all of them (#27).
- EPUB `<video>` and `<audio>` elements keep a box, painted with the poster or a
  placeholder. `EpubPage.media` lists them with their sources and flags, and
  `EpubDocument.resource` and `resourceType` read the book's files for a player
  (#29).

## [0.11.0] - 2026-09-25

This release adds `kitepdf-xps` for XPS and OpenXPS, runs the JavaScript inside
PDF forms, and shapes complex scripts in EPUB the way HarfBuzz does. It closes
156 issues. Most of them are rendering, font and colour bugs that a comparison of
pages with MuPDF and PDFium found. Some API changes break source compatibility
with 0.10.0. The Changed section lists them.

### Added

- `KiteDocView.onHighlightTap`, `KiteDocViewState.highlightAt`, and optional
  `KiteHighlight.id`: hosts can open edit/delete controls when a saved mark is tapped.
  Hit testing follows painted quads through zoom and pan, prefers the topmost mark,
  and excludes transient search hits. Returning false preserves normal link/page taps.
- `KiteDocLayout.Continuous.contentPadding` provides scrollable clearance for floating
  reader controls without cutting the document viewport off at the system safe area.
- `KiteDocView.onEpubReferenceTap` receives a tapped EPUB reference to a note, a
  glossary entry or a bibliography entry before the viewer scrolls to it. A host can
  show the note in place with `EpubDocument.linkTarget` and keep the reader on the
  page (#277).

### Changed

- Breaking: `PdfScriptRunner.onAlert` takes a `PdfScriptAlert` and returns the
  number of the button that the reader pressed. The new `formState` and `policy`
  parameters come right after `document`, so pass the other arguments by name (#234).
- Breaking: `KiteShading.FlatQuad` is removed. `KiteShading.PatchMesh.quads` is
  replaced by `patches` and `colorTable` (#196).
- Breaking: `PdfAnnotation` has a new `additionalActions` parameter before `raw`.
  Pass `raw` by name (#238).
- Breaking: `KiteDocFormat` has a new `Xps` value. A `when` over it without an
  `else` branch must handle `Xps` (#205).
- A `KiteCanvas` wrapper that delegates with `by` must override both overloads of
  `drawImage` and of `applySoftMask`. The renderer calls the new overloads for an
  image with a blend mode other than Normal and for a soft mask with a transfer
  function (#113, #68).
- `ExtGState`, `GraphicsState`, `SoftMask.MaskGroup`, `RecordingCanvas.Call.Image`,
  `PageRenderer`, `KiteJsScriptEngine`, `KiteDocView`, `KiteDocLayout.Continuous`,
  `KiteHighlight` and `KiteRenderSpec` have new parameters with default values.
  They are source compatible with 0.10.0, but not binary compatible.
- Compose Multiplatform 1.12.1 and Ktor 3.6.0. The build uses AGP 9.4.1,
  Gradle 9.8.0 and Dokka 2.3.0-Beta.

### Fixed

- A link or a fragment that names an image's `id` finds the image's page in an
  EPUB. The id was never an anchor, so the link went to the chapter start (#36).
- Viewer tap handlers now use current host callbacks after recomposition, so annotation
  actions do not retain the state from before a mark was created or edited.

### XPS and issue fixes

- Added the `kitepdf-xps` XPS/OpenXPS handler with OPC package discovery,
  fixed pages, paths, glyphs, obfuscated fonts, brushes and resource dictionaries.
  The umbrella opener recognizes XPS before the CBZ fallback. The module is
  unreleased; its documentation lists rendering and conformance limits (#205).
- Added lazy ComicInfo.xml metadata and page bookmarks for CBZ archives (#206).
- Added `KiteScrollPosition` and continuous viewport-offset save/restore in both
  scroll axes, including reopening and right-to-left layout direction (#251).
- EPUB font stacks try later embedded families before their generic fallback
  (#104). Underlines, strikethroughs and inline backgrounds paint across styled
  spaces and line wrapping (#103, #168).
- SVG percentage geometry resolves against the nearest viewport, including
  nested SVGs and viewBox user coordinates (#177).
- Android repeats odd-length PDF dash arrays before passing them to the native
  path effect (#105). Inline images resolve scoped colour-space resources and
  retain exact sample boundaries (#114). View usage rules hide print-only PDF
  layers in the default display configuration (#57).
- AWT soft masks preserve the existing backdrop and work on RGB destinations
  while retaining per-paint blend modes (#78, #80). Mask colours no longer
  paint over the page on AWT; the other backend work in #79 remains open.
- CI pins a colour-managed MuPDF oracle instead of using distribution packages
  whose colour configuration changes the differential result (#223).
- Platform documentation separates declared targets from tests, compile checks
  and targets with no CI verification (#193).
- Two generated EPUB sweep fixtures cover inline decorations and backgrounds,
  adding one page each without changing the existing fixture bytes.

### Additional reader fixes

- SVG embedded stylesheets support type, class, ID, universal and compound
  selectors with specificity, source order, inline styles and `!important`.
  Unsupported selectors and at-rules are skipped without leaking styles (#89).
- EPUB images stay upright in vertical writing and keep CSS width/height in
  physical axes. Block and inline `object-fit: cover` images crop to their
  frames in horizontal and vertical writing (#100, #170).
- Matrix scalar coordinate methods remove per-point `Pair` and boxed-number
  allocations from all six canvas backends, retaining `transformPoint` for
  existing callers (#185).
- `KiteCanvasDecorator` exposes the page paint pass through both viewer render
  specs and the public rasterizer. Replacing a decorator invalidates cached
  pixels; system-font retries use fresh wrappers (#132).
- Two additional EPUB sweep fixtures add one page each. All 26 existing books
  retain their page counts.

### Follow-up fixes

- A reference to a missing object no longer fails every page of a PDF with
  layers. Optional content reads it as null and hides nothing (#252).
- An inline image whose computed length does not end at `EI` no longer drops
  the rest of the page. Text extraction, the editor and redaction split inline
  images where the renderer does (#254, #266).
- The Deflate oracle test reads the CI oracle through `MUTOOL`, and reports
  skipped instead of passed when the oracle is absent (#260).
- AWT soft masks composite the masked content as one layer. Masked pages
  render faster than before #78, one blend mode survives the mask, and mixed
  blend modes keep the exact backdrop path (#256).
- An AWT soft mask whose `/BBox` cannot be read covers the page instead of
  erasing the content, and a mask past the raster budget applies at a lower
  resolution instead of being skipped (#255, #264).
- AWT blend modes work on RGB surfaces (#272).
- Inherited page boxes, resources and rotation, form and appearance geometry,
  annotation and widget rectangles, article beads and Type 3 font matrices may
  be indirect objects. A reference to a missing object reads as absent (#273).
- `CssValues.alpha` reads the alpha of a CSS colour. EPUB backgrounds keep
  their alpha, and a transparent one paints nothing. A forced reader text colour
  removes author backgrounds, and the Sepia theme keeps light fills light (#253).
- EPUB `system-ui` and the `ui-*` font families resolve to their generic face,
  and `font-family: inherit` keeps the parent's family (#257).
- A space between two inline elements no longer grows the line, and copied text
  keeps it (#259). An inline image in `vertical-lr` text sits in its own column
  (#261).
- Underlines and line-throughs skip inline blocks, floats and positioned boxes,
  and keep the colour and thickness of the element that draws them. EPUB reads
  `text-decoration-color` (#265, #271).
- An SVG `<image>` with an auto, empty or unreadable width or height takes its
  intrinsic size or ratio (#263). Clip path content inherits style from the clip
  path's own ancestors, not from the element that uses the clip (#269).
- XPS text with a gradient or image fill and a missing font paints in one colour
  of the brush instead of disappearing (#267). XPS clips leave out figures that
  are marked as unfilled (#268).
- CI uploads the XPS raster test output when that test fails (#270).
- The continuous viewer keeps the centre page as the reading position when it
  leaves the screen, and a start page past the end opens at the last page (#258,
  #262).

### More fixes

- SVG text reads its `x`, `y`, `dx`, `dy` and `rotate` lists per character, and
  `text-anchor` aligns each text chunk as one piece across its `<tspan>`s
  (#181).
- An image whose soft mask has a `/Matte` colour has that preblend undone, so its
  edges lose the matte-coloured fringe. `KiteImageData.softMaskMatte` carries
  the colour (#159).
- Text notes, file attachments, carets, stamps and free text annotations with no
  appearance stream get one: the note or attachment icon, the caret, the stamp
  name in a frame, and the free text in its `/DA` font and colour (#164).
- A shading pattern follows the space of the stream that paints it, so a pattern
  inside a form moves with the form, and the painted path clips it under the
  current matrix (#93). An uncoloured tiling pattern paints in the colour given
  with its name, and its cell cannot set a colour of its own (#94).
- Stroked and clipping text in a font without an embedded program uses the
  outlines of the host face on AWT, Skia, Compose and Android 14 or later. The
  new `KiteCanvas.hostGlyphOutline` supplies them. A canvas without host
  outlines fills stroked text in the stroke colour instead of drawing nothing
  (#85).
- Search and selection find text that fills nothing: invisible text such as an
  OCR layer, outlined and clipping text, and text in a colour that paints
  nothing. Type 3 text reaches the text layer too (#274).
- A block image with a border, padding or background paints them, and the picture
  sits inside them (#101).
- An SVG chapter reads as one image named by its `<title>`, else its `<desc>`, and
  a fixed-layout SVG chapter takes its page size from its `viewBox` (#26).
- An `<svg>` inside an inline element, such as a MathJax equation in a `<span>`,
  flows on the line like an `<img>`, sized by its `width` and `height` in the
  element's own font. An `<svg>` with `display: none` takes no space (#275).
- An `<svg>` written in a chapter loads its `<image>` from the chapter's folder,
  so cover pages in a folder, as Project Gutenberg books have them, draw their
  cover (#276).
- A JPEG keeps the colour space and `/Decode` array of its image, so an inverted,
  spot colour or ICC-tagged JPEG shows its own colours. Eight-bit images in a grey
  space, an ICC matrix profile or CalRGB convert through tables, about twenty times
  faster than before (#72).
- `EpubLink.kind` says whether a link is a note, glossary or bibliography
  reference. `EpubDocument.linkTarget` reads the text, kind and a reflow-safe
  bookmark of the element a link points at, so a reader can show a footnote in
  place (#227).
- Redaction finds text where the renderer draws it. Text could survive a region
  drawn over it when the page carried the font size in the text matrix, set
  leading or spacing before a text object, raised text with `Ts`, used a Type 3
  font or a font missing from the resources, or drew a form that inherits its
  font (#278).
- Vertical CJK text in a PDF, drawn with a Type 0 font in writing mode 1 such
  as `Identity-V`, stands in columns instead of one row across the page. Text
  extraction, search and redaction follow the columns. `PdfFont.isVertical` and
  `PdfFont.verticalMetrics` expose the writing mode and the `/W2` metrics (#124).
- An embedded Type 1 font keeps every glyph. The glyph count after `/CharStrings`
  was read as the length of a glyph program, so the first glyphs of the font drew
  nothing (#279).
- A CFF or Type 1 font draws at the size its own `FontMatrix` gives. A font of
  2048 units per em drew about twice too large (#140).
- A Type 0 font over a CID-keyed CFF program finds each glyph through the charset
  of the program (ISO 32000-1, 9.7.4.2). Text in such a font drew the wrong glyphs
  or fell back to a system font. This hit most CJK subset fonts and every OpenType
  CFF font that `PdfBuilder` embeds (#280).
- `PdfBuilder` embeds an OpenType CFF font at its true size when the font is not
  1000 units per em. `CffSubsetter.subset` takes the units per em in a new
  `unitsPerEm` parameter and writes them into the `FontMatrix` of the subset (#281).
- A font embedded as a whole OpenType file (`/FontFile3` with `/Subtype /OpenType`)
  draws from its own CFF or TrueType outlines instead of a system font. An OpenType
  CFF font without a `FontMatrix` takes its units per em from its `head` table, as in
  FreeType (#282).
- An SVG shape filled with a gradient shows the gradient inside the shape. The
  shape was transformed twice, so the gradient painted far from the shape, or off
  the page, and the shape stayed empty (#283).
- Axial and radial shadings keep their shape under a non-uniform or skewed
  transformation on every backend. A stretched radial shading drew circles instead
  of ellipses, and a skewed axial shading drew its bands at the wrong angle (#69).
- A radial shading keeps its start circle on AWT, Compose and Android. The
  highlight of a sphere or a button no longer turns into a plain fade around one
  centre. Android draws both circles from API 31 on; below that, only circles that
  share a centre draw exactly (#71).
- Compose, Android, CoreGraphics and Canvas2D honour the `/Extend` flags of axial
  and radial shadings. A gradient that the document confines to a band or a ring
  flooded the whole clip region (#70).
- Text filled in a pattern colour shows the pattern inside its glyphs. A title
  filled with a gradient drew as flat black type (#88).
- Mesh shadings (free-form, lattice, Coons and tensor) no longer show a grid of
  pale lines between their cells. Opaque cells now overlap by about half a device
  pixel, as the cells of a function shading already did (#126).
- A stroke in a pattern colour paints the pattern inside the stroke. A closed
  path was filled instead, an open line painted nothing, and stroked text used
  the fallback colour. The new `KitePath.strokeOutline` builds the area that a
  stroke paints, with its width, dashes, caps and joins (#284).
- SVG gradients honour `spreadMethod`, `stop-opacity` and the alpha of a stop
  colour, and a gradient stroke shows the gradient along the stroke instead of
  one flat colour. XPS gradient brushes honour `SpreadMethod`. The new
  `KiteShading.spreadOver` repeats or mirrors an axial or radial shading over a
  region (#179, #180, #286, #287).
- Every canvas samples an image the same way, by MuPDF's rules. An image drawn
  smaller than its pixels is averaged down first, so thin lines in a scanned page
  fade to grey instead of dropping out. An image enlarged more than twice keeps
  hard pixel edges unless its `/Interpolate` entry is true, and Compose no longer
  blurs it. Images from EPUB, SVG, XPS and CBZ files stay smooth, as in a browser.
  The new `imageSampling` holds the rules (#122, #123).
- A PDF keeps at most 32 MB of decoded images for reuse, and drops the image used
  least recently first. Before, it kept every image it had drawn, so a 40-page
  photo book held 230 MB, enough to run an Android app out of memory. Set
  `PdfDocument.imageCacheBudgetBytes` to change the budget, and call the now public
  `dropDecodedImageCache` when the app runs low on memory (#116).
- An image drawn many times on a page, such as a tiled background, a watermark or a
  row of icons, converts to a platform bitmap once instead of once per draw. On AWT,
  a page that stamps one image 32 times renders in 1.9 ms instead of 25 ms. Each
  canvas keeps at most 16 MB of these bitmaps (#117).
- AWT applies the constant alpha and blend mode of a transparency group once, to
  the whole group. The first paint in a group replaced the group's settings, so a
  half-transparent logo or shadow drew fully opaque and a Multiply group drew as
  a normal paint (#77).
- Text in a font that the document does not embed lands where the document puts
  it on Skia and Compose. Skia drew each run with the widths of the host face, so
  a line laid out to a fixed width ended far short of its place. Compose ignored
  character and word spacing (#121).
- A soft mask on CoreGraphics and Android gates the content by the alpha or the
  luminosity of its group. On CoreGraphics the group painted onto the page in its
  own colours, so a mask drawn in blue turned the masked artwork blue. Android read
  a luminosity mask as an alpha mask (#79).
- Canvas2D composites a transparency group once, with its alpha and blend mode, and
  gates a soft mask by the alpha or the luminosity of its group. Before, shapes that
  overlap in a translucent group drew darker where they overlap, and a Multiply group
  drew as a normal paint. A soft mask painted its own colours onto the page (#77, #161).
- EPUB tables with `border-collapse: collapse` resolve each shared border by the
  rules of CSS 2.1. A `hidden` border hides the edge, the wider border wins, and the
  style decides between equal widths. The table's own border joins the collapse
  instead of painting next to the outer cell borders, and a collapsed table has no
  padding (#210).
- A stroke under a matrix that scales x and y differently has an elliptical pen on
  every canvas, as ISO 32000-1, 8.4.3.2 requires. Before, each canvas drew a round
  pen of the mean scale, so a chart stretched to fit drew its vertical lines too thin
  and its horizontal lines too thick. Dash lengths now stretch with the matrix too.
  The new `strokePen` and `KiteMatrix.keepsCircles` hold the rule (#108).
- `onPageRendered` fires once for each fresh page bitmap. It could fire twice for one
  bitmap when the raster finished just as the viewer started to watch for it, which
  happens more often on a busy machine (#229).
- Images on CoreGraphics stand the same way as on the other canvases. Each image
  drew upside down, because the canvas flipped it for a y-down bitmap while
  CoreGraphics already draws the first row at the top of the image (#289).
- An image paints with the blend mode of the graphics state on every canvas, as
  ISO 32000-1, 11.3.5 requires. Before, an image under Multiply drew as a normal
  paint and hid what was under it. A new `KiteCanvas.drawImage` overload carries
  the blend mode. A canvas that does not override it paints the image alone in
  an isolated group with that blend mode (#113). The renderer calls the new
  overload only for an image with a blend mode other than Normal, so a canvas
  wrapper that overrides only the old overload still sees each Normal image (#290).
- `ApplePdfRasterizer.renderToPngData` returns a PNG with the page the right way up.
  It threw on every call, because it cast Objective-C objects to Core Foundation
  pointers, which Kotlin/Native checks at run time. It also drew the page upside
  down (#288).
- A soft mask reads its /BC backdrop colour and its /TR transfer function, as
  ISO 32000-1, 11.6.5.2 requires. A luminosity mask with a white backdrop hid the
  content outside its group instead of showing it, and an inverting transfer
  function swapped the parts that show and the parts that hide. A mask dictionary
  given as an indirect object was ignored. A new `KiteCanvas.applySoftMask`
  overload takes the transfer function as a `KiteMaskTransfer` table. Compose and
  Android apply it as the straight line closest to the table, which is exact for
  an inverter (#68).
- An image whose `/Mask` is a grey image of more than 1 bit per sample, without the
  stencil flag, is masked by it. Its grey levels become alpha, as in MuPDF. Before,
  the image painted unmasked. ISO 32000-1, 8.9.6 requires the flag, so the file is
  out of spec, and a 1-bit mask without the flag still reads as a stencil (#160).
- A page's DefaultGray, DefaultRGB and DefaultCMYK colour spaces replace the device
  spaces that its content selects, as ISO 32000-1, 8.6.5.6 requires. This covers
  `g`, `rg`, `k`, `cs`, images and shadings. Before, a calibrated document drew its
  device colours uncalibrated. The defaults of a form XObject come from its own
  resources, as the clause says. MuPDF also lets a form inherit the page's (#150).
- Each published module keeps a dump of its public API in its `api/` directory, and
  CI fails when the code and the dump differ. An API change now shows up as a diff
  in review (#202).
- Every backend gives a thin stroke the same weight. A line width of 0 is one device
  pixel, as ISO 32000-1, 8.4.3.2 requires. Any other line is at least a fifth of a
  pixel, as in MuPDF. Before, Skia and Compose drew every thin line one pixel wide,
  so an ECG grid of 0.15-unit lines turned black. AWT, Android, CoreGraphics and
  Canvas2D drew a zero-width line at a tenth of a pixel. `hairlineWidthPx` in the
  Compose viewer is now the width of a zero-width stroke, still 1 by default
  (#109, #110).
- An SVG `stroke-width` of 0 paints no stroke, as SVG 1.1, 11.4 requires. Before, it
  drew a thin outline (#292).
- Mesh shadings match MuPDF to within a few colour levels. Free-form and lattice
  meshes blend their vertex colours at every pixel, where each cell had one flat
  colour. Coons and tensor patches follow their curved surface, and a tensor patch
  uses its four interior points, which were read and then dropped. Each mesh draws
  as one image, so a translucent mesh has no darker seams where cells overlap. A
  mesh with a `/Function` blends the parametric value and then looks it up, as
  ISO 32000-1, 8.7.4.5.5 requires. `KiteShading.PatchMesh` holds `MeshPatch`
  control points in place of `FlatQuad` cells (#196).
- AWT anti-aliases the edges of a clip path, as MuPDF, Skia and Compose do. A
  gradient, an image, a mesh or a pattern stroke inside a curved clip had jagged
  edges, and so did text in a clipping render mode. A rectangular clip keeps every
  whole pixel that it touches, as in MuPDF (#285).
- The lexer reads a real number and an operator without building a String. On a
  dense page of 140,000 operators, a content stream parses in 21 ms instead of 28 ms
  and allocates 30 MB instead of 87 MB. On Kotlin/Wasm a real is now the nearest
  double: the String parse there can be one unit off in the last place (#119).
- The content stream parser lexes each token once. It lexed each operand twice, and
  an integer up to four times. On the same page, a content stream parses in 14 ms
  instead of 21 ms and allocates 24 MB instead of 30 MB. Arrays and dictionaries in
  the document body also stop lexing tokens twice. An integer operand is always a
  number, and a dictionary operand is never a stream, as ISO 32000-1, 7.8.2
  requires (#293).
- Text in one of the standard 14 fonts that a PDF does not embed, such as
  Helvetica, Times or Courier, draws from real outlines. KitePDF bundles the URW
  fonts that MuPDF uses, which have the metrics of the Adobe originals. Before,
  a system font drew the glyphs at the standard widths, so letters crowded or
  left gaps. Names that stand for a standard font, such as `Arial,Bold` or
  `Times,Italic`, find it too. The bundle adds about 500 KB. `PdfFont.hasOutlines`
  tells whether a font draws from outlines, embedded or bundled. The
  DifferentialTest mean falls from 0.0029 to 0.0017 (#298).
- Accented letters and many symbols in an embedded Type1C font draw again. The
  table of CFF standard strings stopped at `germandbls`, so é, ü, ©, ° and every
  other glyph from SID 150 up had no name (#303).
- An image drawn without rotation, or turned by a multiple of 90 degrees, covers
  every pixel it touches, as in MuPDF: its edges move outwards onto whole pixels
  before it is drawn. On AWT, an edge pixel was filled only when the image
  covered its centre, so image edges and scaled images sampled differently from
  MuPDF. Every canvas now applies `gridFitImage`. Three image fixtures match
  MuPDF to the pixel, and the DifferentialTest mean falls from 0.0017 to 0.0016
  (#300, #301).
- DeviceCMYK converts to the colours that PDFium and MuPDF draw. KitePDF used
  the polynomial of pdf.js, which was 5 to 10 times further from both engines
  than they are from each other. It now uses PDFium's table, which approximates
  the Adobe conversion from US Web Coated (SWOP) to sRGB. The initial colour of
  DeviceCMYK, black ink alone, is now the converted colour instead of pure black.
  The DifferentialTest mean falls from 0.0016 to 0.0010 (#299).
- ZapfDingbats codes from 128 up draw the right symbols. The built-in encoding put
  the bracket ornaments of codes 128 to 141 at code 161, so 121 codes named the
  wrong glyph (#304).
- Text extraction returns ZapfDingbats symbols, such as ✔ and ♠, through Adobe's
  ZapfDingbats glyph list. Before, it returned the raw byte (#305).
- On AWT, text renders about three times faster. Each glyph is filled once for
  each size, subpixel position and colour, and copied after that, as MuPDF and
  PDFium do. Glyphs also snap to MuPDF's subpixel grid, so a line of text sits on
  one pixel row. The DifferentialTest mean falls from 0.0010 to 0.0007 (#306).
- Every backend honours whether a transparency group is isolated, when a paint
  inside blends in a mode other than Normal. An isolated group blends against a
  transparent backdrop, and a non-isolated group against the page. AWT and
  Canvas2D painted every such group straight onto the page, and Skia, Compose,
  Android and CoreGraphics isolated every group (ISO 32000-1, 11.4.5, #125).
- A knockout group draws as ISO 32000-1, 11.4.6 describes: where two of its paints
  overlap, the later paint replaces the earlier one instead of compositing over it.
  No backend read the knockout flag before (#125).
- A non-isolated group with an alpha below 1 blends its paints with the page, and
  then mixes the result with the page by that alpha. Before, its paints blended
  against a transparent backdrop. Android and CoreGraphics cannot start a layer
  from the page, so there the group keeps the old result. #308 lists this and two
  rarer cases that are still approximate (#125).
- Every predefined CJK CMap of ISO 32000-1, Table 118 now maps codes to the right
  CIDs. The Unicode-keyed CMaps, such as UniJIS-UCS2-H and UniGB-UTF16-H, fell back
  to CID = code, so an embedded font drew the wrong glyphs. All CMap tables now use
  a compact encoding: with the new ones they take 212 KB, against 157 KB before
  (#198).
- Text in a CJK font that is not embedded and has no ToUnicode map draws and
  extracts. The text comes from the code of a Unicode-keyed CMap, or from the CID
  through Adobe's CID-to-Unicode table of the font's collection, as ISO 32000-1,
  9.10.2 describes. Before, such text drew nothing and extracted as U+FFFD. The
  four tables add 148 KB (#309).
- A page drawn again, at another zoom or after a scroll back, no longer parses its
  content again. `PdfDocument` keeps parsed content up to
  `operationCacheBudgetBytes`, 16 MB by default, and `dropOperationCache` frees it.
  A form XObject drawn many times, and a Type 3 glyph, now parse once instead of
  on every draw. On a dense magazine page of 113,000 operators, a render again on
  AWT takes 5 to 8 ms less, and the part before rasterization falls from 13 ms to
  2.5 ms (#118).
- Fills, strokes and glyphs no longer build a new path object for each paint. AWT,
  Compose and Android reuse one path per canvas, and size a new one from its
  segments. Skia reuses one path builder and frees each path once it is drawn.
  Canvas 2D draws into the context's own path, as CoreGraphics already did. A
  clip still keeps a path of its own. On the dense magazine page, a render at
  scale 2 on AWT allocates 16 MB instead of 22 to 25 MB (#130).
- An ICC profile made of lookup tables, which most CMYK press profiles are, now
  converts colours through its tables instead of the device fallback. KitePDF reads
  the `mft1`, `mft2` and `mAB ` tables, with the relative colorimetric intent and
  black point compensation, and interpolates as Little CMS does, as MuPDF converts
  them. A CMYK profile is read for the first time. Through Apple's Generic CMYK
  profile and MuPDF's own CMYK profile, twelve swatches match mutool within one
  level. D50 white now converts to exact sRGB white, which it missed by 0.04 percent
  in blue (#200).
- TrueType collections (`.ttc`) and CFF2 fonts are read. A collection gives its
  first face, or any face through `TrueTypeFont.parse(bytes, faceIndex)`, and
  `TrueTypeFont.faceCount` counts them. A CFF2 program, the outlines of an OpenType
  variable font, draws at its default instance. `EmbeddedFont.load` embeds a face
  of a collection as a font of its own, chosen with `faceIndex`. Before, a
  collection or a CFF2 font fell back to a substitute face, and the writer refused
  a collection (#199).
- A page render can be cancelled. `KitePage.renderTo` takes a `KiteCancellation`,
  which the PDF renderer reads every 32 operators; once it reads true, the page
  paints nothing more and returns with every clip and group closed. In the Compose
  viewer, cancelling the coroutine of `rasterizeOffMain` now stops a page and throws
  a `CancellationException`, so a page the reader scrolled past stops rendering
  instead of running to the end (#188).
- ICC colours convert through the rendering intent the document names: the `ri`
  operator, `/RI` in an extended graphics state, and the `/Intent` of an image. A
  lookup-table profile reads the table of that intent and compensates its black
  point, and the absolute colorimetric intent keeps the colour of the paper, as
  Little CMS converts them for MuPDF. `/UseBlackPtComp` turns the compensation on
  and off. A colour set before `ri` converts again, so the later intent paints it.
  Before, every paint used the relative colorimetric intent. On seven test pages,
  most of them in a press profile with a table per intent, all 68 swatches match
  mutool within one level. Overprint is still ignored (#201).
- An RGB lookup-table profile interpolates on a grid of 17 points a side, the grid
  MuPDF asks Little CMS for, instead of 33. The page of RGB swatches scores 0.00010
  against mutool instead of 0.00047 (#201).
- A CMYK output intent converts DeviceCMYK. The catalog of a PDF/X file names the
  profile of its press as its output intent, and DeviceCMYK now converts through that
  profile wherever the resources name no `/DefaultCMYK`, as MuPDF does. Before,
  DeviceCMYK always converted through a table for U.S. Web Coated (SWOP), so the
  black of a press profile measured #231f20 where mutool draws #111111. MuPDF also
  proofs RGB and grey content through the press, and KitePDF does not (#312).
- A grey ICC profile maps full grey to white, whatever its white point tag says.
  With a D65 tag, full grey drew as #ebffff and mid grey as #768295, where mutool
  draws #ffffff and #808080 (#311).
- Devanagari and Bengali text shapes: each syllable is reordered around its features,
  as HarfBuzz's Indic shaper does. The base consonant is found, a leading Ra and virama
  becomes a reph, consonants before the base take half forms, and a pre-base matra moves
  before them. Joiners are hidden, and a lone matra gets a dotted circle. Before, a
  Hindi word such as हिन्दी drew its i matra after the consonant and no conjuncts.
  Against `hb-shape`, all 60 test words in the two scripts give the same glyphs (#211).
- Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada and Malayalam text shapes. Each
  script places its reph and its matras as HarfBuzz's Indic shaper places them, and a
  Ra after a virama that the font forms into a pre-base shape moves before the base, as
  Malayalam needs. A font with only the old script tags, such as `deva`, shapes by the
  old specification. A font with no tag for the script that falls back to `DFLT` or
  `latn` is not reordered, as in HarfBuzz. The shaper also follows HarfBuzz 14.4 more
  closely in all nine scripts. It reads the category and position of each character
  from HarfBuzz's table for Unicode 17, and it splits syllables by the longest match. A
  vowel sequence that would draw like another vowel, such as अ and the aa matra, gets a
  dotted circle. Characters decompose and compose as HarfBuzz normalizes them: Bengali
  Rra stays whole, and Na with a nukta composes into Nnna when the font has it. A danda
  or a Vedic sign no longer decides the script of a word.
  GSUB matching passes over ZWJ, ZWNJ and the other default ignorable characters where
  HarfBuzz does, and the Hangul fillers draw instead of hiding. Against `hb-shape`, all
  208 test words and 3,575 random Indic words give the same glyphs (#211).
- Arabic and Syriac letters take the joining forms Unicode gives them. The joining types
  of U+0600 to U+08FF come from ArabicShaping.txt of Unicode 17, and a format character,
  such as a right-to-left mark, is transparent. Before, a hamza joined the letter before
  it, so the yeh of شيء drew in its medial form, and a direction mark broke the join
  between two letters. Syriac letters now join, and Alaph takes its fin2, fin3 and med2
  forms, as HarfBuzz's Arabic shaper gives them (#315).
- The marks of Syriac and of the Arabic extended blocks sort by their combining class,
  and the Arabic modifier marks of UTR #53, such as hamza below and small high seen, go
  first among the marks of their class, as HarfBuzz orders them. Against `hb-shape`,
  1,500 random Arabic, Urdu and Syriac words give the same glyphs (#318).
- A letter with combining marks draws with the glyph the font has for the whole letter,
  as HarfBuzz composes them. Before GSUB, a character decomposes when the font has no
  glyph for it or when marks follow it, the marks go into canonical order, and a mark
  composes with the letter before it when the font has a glyph for the result. So t
  with a combining diaeresis draws as ẗ, and alef with hamza above as أ. The data
  covers the Basic Multilingual Plane of Unicode 17, and the combining classes now cover
  every mark in it. Against `hb-shape`, 1,951 random Latin, Greek, Cyrillic and Hebrew
  words with marks give the same glyphs (#316).
- Thai and Lao sara am splits into nikhahit and sara aa, and the nikhahit moves back over
  the tone marks before it, as HarfBuzz and Uniscribe order them. The marks of a word now
  go into canonical order inside the shaper, after that step, instead of before shaping
  starts, as in HarfBuzz. So the text of the page keeps the order of the book. Against
  `hb-shape`, 985 random Thai and Lao words with marks give the same glyphs (#317).
- Khmer text shapes as HarfBuzz's Khmer shaper shapes it. A coeng and Ro move before the
  base and take `pref`, a pre-base vowel moves to the start of the syllable, a split vowel
  decomposes into U+17C1 and itself, and a broken syllable gets a dotted circle. Against
  `hb-shape`, 12 Khmer words and 798 random Khmer words give the same glyphs (#317).
- Myanmar text shapes as HarfBuzz's Myanmar shaper shapes it. A kinzi goes after the base,
  a medial Ra and a pre-base vowel go before it, and `rphf`, `pref`, `blwf` and `pstf` apply
  one at a time. A font made only for the `mymr` tag from before the Myanmar shaping spec
  keeps the default features, as in HarfBuzz. A dotted circle that a shaper inserts now
  has no glyph class until a lookup replaces it, as in HarfBuzz, so a lookup that passes
  over base glyphs stops at it. Against `hb-shape`, 10 Myanmar words and 799 random
  Myanmar words give the same glyphs (#317).
- Sinhala, Tibetan and the other scripts that HarfBuzz shapes with its Universal Shaping
  Engine shape as it does, for the Basic Multilingual Plane: Mongolian, Tagalog, Hanunoo,
  Buhid, Tagbanwa, Limbu, Tai Le, Buginese, Tai Tham, Balinese, Sundanese, Batak, Lepcha,
  Syloti Nagri, Phags-pa, Saurashtra, Kayah Li, Rejang, Javanese, Cham, Tai Viet, Meetei
  Mayek, Tifinagh, N'Ko and Mandaic. A repha moves after the base, a pre-base vowel moves
  before it, a broken cluster gets a dotted circle, and a joining script takes its joining
  forms. A font without GSUB now has its text reordered too, as in HarfBuzz. A ligature no
  longer joins marks that attach to different components of an earlier ligature, because
  the components of a ligature are now tracked as HarfBuzz tracks them. Against `hb-shape`,
  17 Sinhala and Tibetan words and 5,347 random words in 27 fonts give the same glyphs (#317).
- A character outside the Basic Multilingual Plane, such as a mathematical letter, a CJK
  Extension B ideograph or a letter of Adlam or Brahmi, is one character to the layout. It
  draws the glyph that the font gives it, where it drew two missing glyphs before, and its
  text on the page is the whole character. The shapers reach these characters too: the
  Universal Shaping Engine shapes Brahmi, Chakma, Grantha, Adlam and its other scripts
  outside that plane, and the script of each word, its marks, their classes and their
  decompositions come from tables of Unicode 17 for every plane. The ideographs of planes 2
  and 3 and Tangut break between characters, as CJK ideographs do. Against `hb-shape`,
  9,903 random words in 50 fonts of the Universal Shaping Engine and 5,427 random words in
  40 other fonts outside the plane give the same glyphs (#319).
- A bracket or another character with a mirror image, such as `(`, `«` or `≤`, draws as
  its mirror at a right-to-left bidi level when the font has it, as HarfBuzz mirrors it in a
  right-to-left run. The other right-to-left characters take the font's `rtlm` forms, and
  left-to-right characters take its `ltrm` forms, so an Old Hungarian font gives its
  left-to-right glyphs. Against `hb-shape`, 895 random right-to-left words with mirrored
  characters in six fonts give the same glyphs (#321).
- Bidirectional text follows the whole Unicode Bidirectional Algorithm of Unicode 17. Every
  character has its own bidi class, so Syriac, Thaana, N'Ko, Adlam and the other
  right-to-left scripts lay out right to left (#320). Explicit embeddings and isolates,
  nonspacing marks and paired brackets resolve as UAX #9 says, and each paragraph resolves
  as a whole before it breaks into lines. A bracket around right-to-left text in a
  left-to-right sentence keeps its glyph. All 91,707 tests of BidiCharacterTest.txt and all
  770,241 cases of BidiTest.txt pass (#323).
- `text-transform` and synthesized small caps change the case of letters outside the Basic
  Multilingual Plane, such as Adlam, Deseret and Osage, through the simple case mappings of
  Unicode 17. `capitalize` gives the first letter of a word its titlecase, as CSS asks, so
  a digraph such as ǆ becomes ǅ (#322).
- A page paints in the order of CSS 2.1, Appendix E. Lines and block images paint in
  document order, so text after an image paints over it where the two overlap. Positioned
  boxes paint after the flow, in the order of their `z-index`, and a float paints after the
  backgrounds of the flow (#172).
- Text in an embedded font shapes through every GSUB lookup type. Contextual and
  chained contextual substitution, multiple and alternate substitution, and reverse
  chaining now apply along with single and ligature substitution. The lookups run for the script
  of each word, in the stages HarfBuzz uses, and a lookup flag skips the glyphs its
  GDEF class excludes. Combining marks go into the order HarfBuzz normalizes them to.
  Before, a per-glyph table took single and ligature lookups of a few features, so
  Urdu Nastaliq and most contextual forms did not shape. Against `hb-shape`, all 57
  test words in Latin, Greek, Cyrillic, Arabic, Urdu, Hebrew and Thai now give the
  same glyphs. Indic scripts still need their own reordering (#211).
- In a book whose embedded font has ligatures, such as fi and ffi, the text of the
  page keeps every character a ligature draws. Before, a ligature kept only its first
  character, so search, selection and copy saw "fnd ofce" for "find office" (#314).
- The scene tests of the viewer wait for background work, such as a chapter layout
  or a page raster, on the wall clock instead of a count of frames. The count came to
  under four seconds, which a loaded CI runner can need for a twelve-chapter book.
  `IncrementalEpubSceneTest` runs in the default suite again, and its opening test
  waits until the chapter is laid out, not only until its placeholder shows (#294, #213).
- Three scene tests of text selection no longer fail on a loaded machine. A frame
  that ran past the long-press timeout while a test finger was down turned the
  gesture into a long press. The tests now send the first move with no frame after
  the press (#310).
- A finger held still on a selection handle before it drags keeps the selection.
  The long press under the handle reached its timeout and started a new selection
  at the handle, so a pause of half a second lost the words already chosen (#313).
- The differential test checks each page against its own recorded score, not only
  the mean of all pages. A page fails when its mean error, its fraction of changed
  pixels or its largest channel error gets clearly worse. A new fixture fails until
  its score is recorded, and a recorded score moves only in a run with
  `-Dkitepdf.diff.updateBaseline=true` (#195).

## [0.10.0] - 2026-09-13

A Chinese novel still ran a 192 MB Android heap out of memory after 0.9.0,
because each of its 41 chapters parsed its own copy of the book's font. This
release fixes that and more than 80 rendering and layout bugs found by
auditing PDF, SVG and EPUB output. It also adds `kitepdf-javascript`, which
runs the JavaScript inside PDFs.

### Added

- `PdfAnnotation.isInvisible` and `PdfAnnotation.isNoZoom` expose two more
  annotation flags, both of which the renderer now honours.
- `PdfAnnotation.borderStyle` and `borderDash` carry the border style and
  dash from `/BS`, or the dash from the older `/Border` array.
- `ExtGState.dashArray` and `dashPhase` carry a graphics state dictionary's
  `/D`, and `RecordingCanvas.Call.Stroke` records the dash it was given.
- `EpubSettings.hyphenate` turns hyphenation on or off for the whole book,
  whatever its CSS says. Each chapter uses the patterns of its own language,
  and the book itself is not changed.
- `GraphicsState.softMaskCtm` keeps the matrix a soft mask was set under.
- `KiteColorSpace.paintsNothing` is true for a colour space that paints
  nothing, such as a Separation named `/None`.
- `kitepdf-javascript`, a new artifact, runs the JavaScript that PDFs carry
  on KiteJS. `PdfScriptRunner` runs the document-level scripts and
  JavaScript actions under an instruction budget, so a script cannot hang
  the app. It covers the targets KiteJS builds for.
- `KiteScriptEngine` in `kitepdf-core` is the interface the rest of KitePDF
  talks to, so no other module depends on a script engine.
- `PdfPage.renderTo(canvas, deviceCtm, annotations)` draws only the
  annotations a filter accepts. `{ false }` renders the page without its
  markup, for printing, a clean thumbnail, or an editor that redraws its
  annotation layer on its own.

### Changed

- Built with Kotlin 2.4.20. The viewer now depends on Compose Multiplatform
  1.12.0, the stable release, instead of 1.12.0-beta02, and the Skia renderer
  on Skiko 0.150.1, the version that Compose release is built on. An app that
  uses both gets one Skiko, not two.
- `EpubSettings`, `PdfAnnotation`, `ExtGState`, `GraphicsState` and
  `RecordingCanvas.Call.Stroke` gained constructor parameters, and
  `PageRenderer.render` gained one. Source stays compatible, but anything
  compiled against 0.9.0 needs recompiling.

### Fixed

- A book whose chapters each declare the same `@font-face` in their own
  `<style>` block, which is how some converters write embedded fonts, parsed
  that font once per chapter and kept every copy, with its own glyph caches,
  for the life of the book. The layout budget never saw it: on a 41-chapter
  Chinese novel the budget reported 23 MB while the process held 340 MB, and
  a 192 MB Android heap died with `OutOfMemoryError` however small the budget
  was set. A font file is now parsed once per book and shared by every rule
  that names it. The same book now holds 32 MB.
- An embedded TrueType font kept two copies of every glyph it had drawn, the
  boxed points and the path built from them, about 4 KB per glyph for a glyf
  record of 200 bytes. Only the path is kept now.
- An indexed colour set by a colour operator picked the wrong palette entry:
  every index above zero became the last one, and selecting the space painted
  black instead of entry 0.
- An indexed palette over a Lab base, and a Lab image with no `/Decode`, were
  scaled 0 to 1 instead of Lab's own ranges and rendered near black.
- Painting in a Separation named `/None` covered the artwork with opaque
  white. It now paints nothing, as the specification requires.
- Lab, CalGray and CalRGB colours with a D50 white point had a yellow cast.
- An ICC profile that cannot be applied now falls back to the declared
  `/Alternate` space instead of a device space guessed from `/N`.
- A malformed PDF function aborted the whole page. It now degrades, and no
  function can produce a NaN colour.
- The PostScript calculator's `not` was logical only, and a stitching
  function took its output count from its first piece instead of its own
  `/Range`.
- A form, tiling cell, Type 3 glyph or annotation appearance that left a `q`,
  a clip or a marked-content section open leaked it into the rest of the
  page: the clip, the matrix or a hidden layer outlived the stream, and one
  malformed stamp displaced every annotation drawn after it.
- Past 64 nested `q`, every restore consumed a real saved state. The limit
  is now 4096, and saves and restores keep pairing past it.
- Reusing one `PageRenderer` for several pages painted a page black after a
  page with a stray `d1`.
- An unclosed hidden layer in the page content erased every annotation on
  the page, and an annotation on a switched-off layer was drawn anyway.
- A cyclic visibility expression overflowed the stack, a form with partial
  resources showed hidden layer content, and
  `PdfOptionalContent.isVisibleByDefault` disagreed with the renderer for the
  `/Unchanged` base state.
- A checkbox whose `/AS` names a missing state rendered checked, an indirect
  `/F` or `/AS` was ignored, an Invisible vendor annotation was drawn, a
  NoZoom stamp was stretched to its rectangle, and `/CA` was not applied to
  appearance streams.
- A synthesized highlight washed out the text under it and covered the
  bounding box of a rotated quadrilateral instead of the quadrilateral.
- Text in a font missing from the page resources painted nothing. It now
  paints in Helvetica, as other readers do.
- Invisible Type 3 text (render modes 3 and 7) was painted over the page,
  and a Type 3 font with a name for its `/Encoding` drew no glyphs.
- Arial, Times New Roman and Courier New without `/Widths` were measured at a
  flat half em. They now use the matching Standard 14 metrics.
- A pattern colour space with no pattern named painted black, `sh` ignored
  the active soft mask, and a tiling cell that drew past its box flooded the
  whole fill.
- A dash set through a graphics state dictionary drew solid.
- A line cap, join or miter operator with no operand reset the parameter to
  zero, and a curve after a close or a rectangle started from the wrong
  point.
- A zero-length stroke with square caps painted a square, and a move-then-close
  with round caps drew nothing on AWT but a dot on Skia.
- An inline image using the `/I` abbreviation for Indexed painted its raw
  indices as grey.
- Gradients are sampled at 256 stops instead of 32, so a gradient with many
  bands keeps them, and type 4 mesh shadings read their padded vertex
  records correctly.
- A standalone SVG file drew upside down in the viewer.
- An SVG whose viewBox has a different shape from its width and height was
  stretched. It now follows `preserveAspectRatio`, whose default scales
  evenly and centres, so a circle stays a circle. `slice` clips to the
  viewport.
- SVG lengths in `pt`, `pc`, `in`, `cm` and `mm` were read as points, so
  they came out at three quarters of their size, and `em` ignored the font
  size. They now use CSS pixels, 96 to the inch, as SVG requires. The
  file's own width and height follow the same rule, so a drawing that is
  `210mm` wide opens at its real size instead of its viewBox size.
- Dashed SVG strokes drew solid, and round caps and joins drew square. The
  miter limit now starts at 4, the SVG default.
- An SVG shape whose only paint is a gradient stroke drew nothing. It now
  strokes in the middle colour of the gradient.
- SVG text lost the space before a `<tspan>`, so a label read "Helloworld",
  and a `<tspan>` inside another `<tspan>` lost its text.
- An SVG group with `opacity` showed darker seams where its shapes overlap.
  The group is now composited once, as one layer.
- An SVG `<switch>` drew all of its children instead of the first one whose
  conditions pass.
- An SVG clip path made only of `<use>` or `<text>` elements, or with no
  shapes at all, let the content through unclipped, and a clip path in
  `objectBoundingBox` units clipped to almost nothing.
- An SVG `transform` written in a `style` attribute was ignored.
- An EPUB horizontal rule painted nothing, so scene breaks disappeared. It
  now draws a thin line across the column.
- A block image sized with the HTML `width` and `height` attributes drew a
  third too large.
- A fixed-layout page treated CSS pixels as points, so its content drew at
  75 percent of its authored size and drifted off the page art. The page size
  is now in points too: a viewport of 800 by 1200 pixels makes a page of 600
  by 900 points.
- An inline image right after a word, with no space between them, was never
  drawn.
- A block image was always centred. It now starts at the left edge, or the
  right edge in right-to-left text, unless both margins are `auto`, and its
  margins narrow the room it can fill.
- In a right-to-left list the marker stayed at the left edge, far from its
  item.
- An explicit `text-align: left` inside right-to-left text was flipped to
  the right.
- A link wrapped around a block had no clickable area over the block.
- A comic page did not call the canvas page begin and end hooks, unlike every
  other page type.
- A WebP comic page drew nothing and opened at 800 by 1200 whatever its real
  size. Page sizes now come from the WebP and TIFF headers, lossless WebP and
  TIFF images decode wherever an image is read (comics, EPUB and SVG), and a
  comic page that cannot decode shows a grey sheet instead of nothing. Lossy
  WebP still has no decoder.
- A `ReaderTheme` built inline in a composable was a new cache key on every
  recomposition, so the viewer rendered its pages again each time. Themes now
  compare by value.
- `KitePageRasterizer.rasterize` now says in its docs that the page fills the
  bitmap width, and logs a warning when the height does not match the page's
  aspect ratio. The docs of the vectorized render mode now say that it draws
  on the UI thread.
- Clipped text over a gradient or an image got a solid bar in every word gap.
  A space in a font with outlines now adds nothing to a text clip.
- A matrix change inside a clipping text object moved the clip, and stroked
  and clipping text ignored each glyph's own offset.
- A Type 3 glyph was not clipped to the box its `d1` declares.
- A dotted line (`[0 12] 0 d` with round caps) drew as dashes, and a line
  with a zero gap drew dashed, on AWT and Compose.
- On AWT, a clip and a transparency group that did not nest restored each
  other's state, and blending onto a transparent surface blended against
  black.
- A soft mask was applied to each object inside a transparency group instead
  of once to the group, so every overlap came out darker, and a soft mask
  moved with any `cm` after the `gs` that set it.
- Axial and radial shadings painted outside their `/BBox`, a shading
  pattern's `/Background` was never painted, and its own graphics state was
  ignored.
- A tiling pattern fill under a constant alpha or a blend mode painted
  opaque and unblended.
- A soft mask finer than its image was sampled down to the image's grid, so
  soft edges came out blocky. A soft mask stored as JPEG or JPEG 2000, or at
  2, 4 or 16 bits, was dropped and the image painted opaque, and a soft
  mask's `/Decode` was ignored.
- A square, circle, line, polygon or ink annotation without an appearance
  stream drew a 1-point solid line whatever its border said. It now strokes
  at its declared width and dash, and a square or circle stays inside its
  rectangle.
- A nested SVG `<svg>` element ignored its position, size and viewBox.

## [0.9.0] - 2026-09-08

A reader app that opened a normal-length EPUB on Android ran out of memory a
few seconds later and died. This release fixes that, along with the blank
base-14 text on Linux that had been waiting behind it.

### Added

- `EpubSettings.layoutCacheBytes`: a memory budget for laid-out chapters,
  48 MB by default. The chapters the reader has not used for the longest
  drop their pages and lay out again on the next visit; page counts, anchors
  and bookmarks survive the drop.

### Fixed

- Opening a reflowable book laid out every chapter in the background and
  kept all of them, about 165 bytes per character of text, so a
  normal-length novel filled a 192 MB Android heap within seconds and the
  host app died with `OutOfMemoryError`. Laid-out pages now stay within the
  budget above.
- Every visible EPUB page was rasterized again each time a chapter finished
  laying out, and faded in again, because the document handed the viewer a
  new page object per lookup and the viewer keys its bitmap cache on the
  object. On a 40-chapter book the two visible pages were rendered 30 times
  each. A location now answers the same page object for the life of the
  document.
- An `OutOfMemoryError` inside the rasterizer escaped the failure guard,
  which caught only `Exception`, and killed the host app instead of leaving
  the page placeholder in place.
- Each glyph in a laid-out chapter owned its own one-character `String`;
  glyphs for the same character now share one.
- The Skia renderer drew no text at all for a Standard-14 font on a host
  without Helvetica, Times New Roman or Courier New. It asked for those three
  by name, which macOS and Windows carry and a plain Linux box does not, and
  Skia answers a family it cannot find with a font that paints nothing and
  reports no error. A page of base-14 text came out blank on Linux while
  rendering correctly everywhere else. Each family now lists the
  metric-compatible stand-ins other systems ship (Liberation, Nimbus, DejaVu,
  Noto and friends), falls back to any face the host does have, and skips the
  run rather than drawing with a font that cannot paint.

## [0.8.2] - 2026-08-30

The second half of the 0.8.1 story, found by auditing rather than by a report.

### Fixed

- Structured text: the synthesised-space threshold (a quarter of the font
  size) had rescaled with 0.8.0's effective font size the same way the line
  tolerance had. On fonts that pad their em box it grew past real word gaps,
  so dense chart text glued words together (`HIRL(60m)`, `RVR150m`). The
  threshold is now a tenth of the font size, floored at 0.25pt, calibrated
  so that a normal document keeps its exact mutool-matching spacing and the
  0.8.0-regressed chart returns to its 0.7.0 spacing, byte for byte. Both
  the line text and the search/selection path share the rule.

## [0.8.1] - 2026-08-30

One fix, shipped fast because 0.8.0 broke real parsers.

### Fixed

- Structured text: 0.8.0's effective font size silently rescaled the
  line-clustering tolerance from an accidental 0.5pt to half the em box.
  On dense layouts (aeronautical charts, tables) whose fonts pad the em,
  distinct rows packed 1-1.4pt apart merged into one line and their spans
  interleaved in reading order (#23). A line is a shared baseline, so the
  tolerance is now a small slack around it: 5% of the font size, floored at
  0.5pt. This restores 0.7.0's grouping on the reported file exactly, while
  keeping 0.8.0's correct `fontSize` values.

## [0.8.0] - 2026-08-29

Two new formats. Comic archives and SVG each get their own artifact, and
`KiteDoc.open` recognises them the way it already recognised PDF and EPUB, so a
comic reader can pull `kitepdf-cbz` without the EPUB engine coming with it.

Text geometry got a pass as well. A span now reports the size a reader actually
sees rather than the number in the font operator, and character and word
spacing move the pen between glyphs on every backend instead of only at the
start of the next run.

The rest is hardening. Archive reading, remote downloads and page rasterizing
all gained ceilings and fail closed on malformed input, and creating an
encrypted document now demands a real platform random source instead of
quietly accepting a non-cryptographic one.

### Added

- Russian hyphenation patterns (hyph-utf8, contributed by ksokolovskiy in
  #7); a ru-tagged book with `hyphens: auto` now breaks long words instead
  of falling back to the inert en-US set.
- SVG, as a format of its own. A new `kitepdf-svg` module holds the renderer
  that was buried in the EPUB handler, and `SvgDocument` opens a standalone
  `.svg` as a one-page document at its own viewport, drawn as vectors.
  `KiteDoc` recognizes it as `KiteDocFormat.Svg`.
- SVG drawing catches up: `<use>` and `<symbol>` (with a cycle guard),
  `<image>` from a `data:` URI or a file the caller loads, `<text>` and
  `<tspan>` measured against standard-font metrics, linear and radial
  gradients as fill, `clip-path`, `display`, `visibility`, `fill-opacity`,
  `stroke-opacity`, and a `style=""` declaration outranking the matching
  attribute. A fixed-layout comic whose pages are an SVG wrapping one JPEG
  now draws.
- ICC colour. An `/ICCBased` space was resolved by component count alone, so a
  wide-gamut photo rendered as if it were sRGB. Matrix/TRC profiles (RGB and
  grey) are now read and applied. A profile that IS sRGB stays on the device
  path, where the colour passes through byte-exact.
- EPUB: `EpubPage.readingOrder()`, one page's content in the order a reader
  that speaks would say it, with the role each element declared. `aria-hidden`,
  `role="presentation"` and `alt=""` are left out; `aria-label` replaces the
  text; `aria-hidden` and `epub:type` inherit down the subtree.
- EPUB: `table-layout: fixed`, and real `position: absolute` / `fixed`
  placement (the nearest positioned ancestor is the containing block, and
  `right`, `bottom` and a height from `top`+`bottom` all resolve).
- EPUB: `writing-mode: vertical-lr` lays out, and selection, search and link
  rectangles follow the columns on any vertical page.
- Text: GPOS mark-to-mark and mark-to-ligature attachment, so two stacked
  diacritics sit one above the other instead of overprinting.
- Forms: a checkbox or radio widget with no `/AP` of its own gets one drawn
  from its `/MK` colours, so a ticked box actually looks ticked.
- `ZipReader` reads ZIP64 records and entries sized only by a trailing data
  descriptor, and verifies every entry's CRC-32 (lenient by default, strict on
  request, or ask with `verify`).
- `TextEncoding` in core: text is decoded by weighing a byte order mark, a
  UTF-16 NUL pattern, the document's own declaration, the caller's hint, and
  finally UTF-8 validity, falling back to Windows-1252. Books that ship
  1252 while claiming UTF-8 now read correctly.
- CBZ comic archives. A new `kitepdf-cbz` module reads a ZIP of images as a
  document, one page per image, in natural filename order (`page2` before
  `page10`). `KiteDoc.open` recognizes a CBZ on its own; `CbzDocument.open`
  is the direct route. Pages size themselves 1 px = 1 pt, sizes come from
  image headers so opening does not decode the archive, and packaging noise
  (`ComicInfo.xml`, `Thumbs.db`, hidden files) is ignored. WebP pages are
  detected but render blank until the image engine learns WebP.
- The full MacExpertEncoding vector. Expert-set fonts (old-style figures,
  small capitals) selected the right glyphs for only six codes before.
- CalGray and CalRGB colour spaces apply their gamma, whitepoint and matrix
  instead of collapsing to the device space.
- EPUB: each spine document hyphenates in its own language, so a bilingual
  anthology stops applying chapter one's patterns to everything.
- EPUB: a book whose OPF declares `primary-writing-mode: vertical-rl` and no
  `page-progression-direction` now reads right to left, as vertical books do.

### Changed

- Remote document loading now streams into a bounded buffer instead of calling
  an unbounded `bodyAsBytes`; the default ceiling is 128 MiB and every URL API
  has a `maxBytes` overload. ZIP entries likewise have configurable 128 MiB
  decompressed-size and 100,000-record ceilings.
- Page rasterizers reject non-finite scales and default to a 40-megapixel
  allocation ceiling, published once as
  `io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS`. The
  Compose viewer raises its rasterizer's ceiling to cover
  `maxBitmapLongSide` squared, so a larger configured cap keeps rendering.
  `EpubSettings` now rejects non-finite, negative, and geometrically
  impossible page settings at construction time.
- AES-256 creation now requires an explicitly supplied platform CSPRNG, and
  encrypted editing fails at startup without one. The write path no longer
  silently uses Kotlin's non-cryptographic `Random.Default` for keys or IVs.
- Pull requests now run the JVM, Apple and JS gates, CI jobs have least-privilege
  permissions and timeouts, and Android host tests are attached for modules
  with common tests.
- The XML reader and the CSS value parsers moved from `kitepdf-epub` to
  `kitepdf-core` as `KiteXml`, `KiteXmlNode` and `CssValues`, all public. The
  EPUB cascade and the SVG renderer read the same syntax, and the SVG module
  needed an XML parser that was not inside a book handler.
- `KiteTextLine` knows whether it is a vertical column, so its char edges run
  down the page and hit-testing swaps axes to match.
- `ZipReader` moved from `kitepdf-epub` to `kitepdf-core`
  (`io.github.yuroyami.kitepdf.core.zip`). The old name still compiles as a
  deprecated typealias for one release.
- `KiteDoc.open`'s unreadable-bytes error now names all three formats.
- `PdfDocument.bytes` returns a defensive copy, making the documented
  immutability true. Zero-copy access moved to `rawBytes` behind the
  `KiteRawApi` opt-in.

### Fixed

- Structured text: `PdfTextSpan.fontSize` now reports the effective rendered
  size (the `Tf` size times the text-matrix scale). Producers that write
  `/F1 1 Tf` and carry the size in `Tm` reported 1.0 for every span (#22),
  which also collapsed the line-clustering tolerance and the synthesised-space
  threshold that scale off it.
- Text rendering: `Tc` character spacing and `Tw` word spacing now move the pen
  between glyphs inside a run, on every backend. Before, only the start of the
  next run honoured them, so spaced text painted condensed and then jumped,
  and structured-text bounds and char edges came out narrow.
- `extractText`: the line-break and word-gap heuristics now scale with the
  effective (Tm-scaled) font size, so size-in-Tm documents stop growing
  spurious newlines and word breaks.
- Malformed ZIP offsets, truncated central records and ZIP64 extras now fail
  closed instead of indexing outside the archive. Stored entries are bounded,
  encrypted/header-mismatched entries are refused, false EOCD signatures in a
  comment are ignored, and hostile names cannot inject control characters into
  CRC warnings. A directory holding more records than it declares is refused,
  while a literal 65,535-entry archive and ZIP64 writers that sentinel the
  EOCD disk fields stay readable.
- PDF object parsing caps container nesting at 256 levels, so a hostile
  content stream of thousands of `[` tokens is skipped as garbage instead of
  overflowing the call stack.
- Remote `openUrlOrNull` no longer swallows coroutine cancellation. URL
  credentials, query tokens and fragments are redacted from HTTP status,
  size-limit and transport failures, including nested transport exceptions.
- Oversized Compose page bitmaps are no longer retained above the cache budget;
  byte accounting and raw-image row/pixel arithmetic cannot overflow, and
  truncated huge images are rejected before allocating an RGBA buffer.
- Cyclic SVG gradient references and deeply nested SVG trees no longer consume
  the call stack. Non-finite SVG viewports, gradient stops and coordinates are
  rejected or safely normalized.
- CBZ natural sorting compares arbitrarily long digit runs exactly, without
  overflowing `Long`. Base64 loading now rejects malformed padding, impossible
  tails and non-zero discarded bits without boxing every decoded byte.
- The umbrella artifact's custom POSIX source-set graph is attached after the
  default hierarchy, so Linux, Windows and Android Native receive the documented
  native file APIs instead of silently omitting those source sets. Its `stdio`
  actuals are separated into LP64, ILP32 and LLP64 families, preventing Kotlin
  metadata and Dokka from commonizing incompatible `CLong`/`size_t` signatures.
- Compose viewer: the Paged layout no longer jumps while background chapters
  land, opens directly at a saved Flow bookmark's chapter, keeps zoom and
  selection through landings, and no longer loses the bookmark if opening is
  interrupted. Navigating to a location that no longer exists now clamps to
  the nearest page instead of doing nothing (#5).
- EPUB: first lines of indented paragraphs no longer overflow the content box
  and clip at the page edge. The line breaker fitted every line against the
  full width and the `text-indent` was added afterwards at placement, so a
  packed first line stuck out by up to the indent width (#6).
- EPUB: Cyrillic fallback text is measured with the exact Standard-14 widths
  instead of a flat half-em, so Russian text no longer draws squeezed and
  line-fit decisions use real advances (#6).
- A form XObject or a Type3 char proc with no `/Resources` of its own now
  reads the page's, as the spec asks. Both were looking at an empty map, so
  every name they used resolved to nothing and the stream painted nothing.
- An out-of-flow inline box is blockified (CSS 9.7), which is what gives
  `<img style="position:absolute">` a box of its own instead of a line slot.
  The anonymous inline container also stopped inheriting its parent's
  `position`, which was shifting relative boxes twice.
- Redaction removes a shading (`sh`) whose visible region touches a redacted
  area. A shading paints its whole clipping region, so it is judged by the
  clip's boundary the same way vector paths are judged by their segments; a
  page-wide background shading survives under the black box like any other
  background.
- The Skia differential harness can no longer pass falsely: it runs on the
  same hardened MuPDF oracle as the AWT harness (new internal
  `kitepdf-difftest` module), a failed oracle render or a page-size mismatch
  now fails the test instead of scoring perfect, the drop-in corpus is the
  repo `corpus/pdf`, and the stale allowance for blank Standard-14 text is
  gone.
- The native (non-Skia) renderers reach content parity. Android and
  iOS/macOS draw decoded (RAW) images, which is what every successful JPEG,
  JPEG 2000 and JBIG2 decode produces; the browser Canvas2D backend paints
  them too instead of a placeholder for everything. iOS and macOS render
  Standard-14 (non-embedded) text through the system font instead of leaving
  it blank. The Android and CoreGraphics image transforms also placed the
  unit square wrong for the standard device transform, drawing outside the
  page; both now use the same mapping Skia does, proven by pixel tests.
- Compose no longer flattens a rotated, reflected or sheared image to its
  scale: the full transform reaches the bitmap.
- Filling a form field no longer resurrects a field an earlier redaction call
  in the same editor removed, and `/NeedAppearances` is cleared even when the
  form dictionary is written straight into the catalog.
- On iOS and macOS, an image drawn with an alpha value now actually renders
  translucent.

## [0.7.0] - 2026-08-24

Big EPUBs open at the page the reader left off, instead of paginating the whole
book first.

A reflowable book has no pages until it is laid out, and KitePDF laid out every
chapter before it would show one. On the local corpus that was 86% to 96% of
the time between opening a file and seeing a page: a 9.9 MB book spent 986 ms
there. A reader resuming at chapter 20 paid for chapters 0 to 19 as well.

Now each chapter is laid out on its own, on demand, and the one being read goes
first. Resuming at the last chapter, desktop JVM, local corpus:

| book | chapters | before | after |
|---|---|---|---|
| 6.epub (9.9 MB) | 26 | 986 ms | 3 ms |
| 11.epub (5.0 MB) | 11 | 2085 ms | 71 ms |
| 1.epub | 19 | 729 ms | 12 ms |
| 18.epub | 35 | 320 ms | 7 ms |

The rest of the book lays out in the background, nearest chapter first, and a
chapter landing above the reader does not move the page they are on. A reader
who scrolls ahead onto a chapter that is not ready yet waits on its
placeholder, and lands at the start of that chapter when it arrives.

Chapters are also read and parsed one at a time now. Opening a book reads the
container, the OPF and the table of contents, and nothing else. Two things paid
for that: a chapter's HTML is parsed when that chapter is first laid out, and a
stylesheet is parsed once per file instead of once per chapter that links it
(one corpus book was reading and parsing the same three sheets 42 times over
its 11 chapters).

| book | chapters | open before | open after |
|---|---|---|---|
| 11.epub (5.0 MB) | 11 | 26.2 ms | 0.4 ms |
| 13.epub (5.8 MB) | 7 | 18.9 ms | 0.4 ms |
| 16.epub (6.0 MB) | 8 | 18.6 ms | 0.2 ms |
| 6.epub (9.9 MB) | 26 | 10.2 ms | 0.1 ms |

End to end, opening a book and showing its last chapter: 6.epub 11.3 ms to
2.0 ms, 18.epub 12.1 ms to 4.3 ms, 11.epub 87.4 ms to 58.3 ms. Laying out a
whole book costs the same as before.

Closes the request in
[issue #3](https://github.com/yuroyami/KitePDF/issues/3).

EPUB footnote links land on the note. An inline element wrapping block
children (the shape FB2 conversions emit for footnotes) used to flatten into
one paragraph and anchor to the top of its document, so tapping a footnote
opened the start of the notes section. The inline now splits around its
blocks as CSS requires, the note keeps its own block structure, and the link
lands on the note itself. Reported in
[issue #4](https://github.com/yuroyami/KitePDF/issues/4).

Redaction stops leaving content behind.

`redactRegions` promised that redacted content is removed rather than covered.
In several shapes it was not, and each shape was a different route to the same
outcome: a file the caller ships believing it is clean.

**A second edit to a page discarded the first.** `editPageContent`, `stampPage`
and `redactRegions` each rebuilt from the page as it was opened, never from what
an earlier call had staged. Two stamps left only the second. Worst of all, two
`redactRegion` calls left the first region's content in the file. All three now
read what is already staged, so calls compose in the order they are made.

**A form drawn in several places was rewritten once.** One form XObject can be
invoked many times, and each place sees the redaction rectangle in a different
part of the form. Rewriting the shared stream once was wrong in both directions
at the same time: the other places were never tested against the region, and
because they draw the same object they lost content that was never in a region.
Each place that needs a different redaction now gets its own copy, and a place
no region touches keeps drawing the original untouched.

**A redacted widget's form field stayed reachable.** Dropping the widget from
the page's `/Annots` left `/AcroForm /Fields` and `/AcroForm /CO` pointing at
the same object, so its value, default value, name and appearance stream
survived the rewrite. The field is now detached from both.

**Vector paths in a region were left in the stream.** A signature or a chart
drawn as vector art *is* its coordinates, so painting a black box over it left
the shape recoverable. Paths whose ink reaches a region are now removed, with
the pen width accounted for, and a path that also sets a clip keeps its
construction and stops painting rather than disappearing and letting everything
after it escape the clip.

Underneath all of that, one change of approach: **redaction no longer relies on
the garbage collector to delete a secret.** An object taken off the page is
emptied as well as unlinked, so a reference somewhere the editor does not
rewrite cannot bring its contents back. That covers the paths nobody enumerated,
which is the only way this kind of guarantee holds.

`docs/editing.md` now lists what redaction removes and the limits that remain,
replacing three stated limitations of which two had already been fixed.

### Breaking changes and migration

- **Every spine item now starts on a fresh page.** A short chapter used to
  share its page with the next one, which no mainstream reader does and which
  is what made whole-book layout unavoidable. Page counts rise by roughly one
  per chapter boundary (6.epub 480 to 491, 18.epub 502 to 515).
- **`EpubDocument.outline` no longer paginates the book.** Entries carry a
  `target` bookmark instead, and `pageIndex` is filled in only once the book is
  fully laid out. `KiteOutlinePanel` navigates by `target`, so a table of
  contents opens instantly and a tap lays out that one chapter.
- **`KiteDocViewState.currentPage` counts slots, not pages.** For a PDF, and
  for a book that has finished laying out, it is still the page index. While a
  reflowable book is paginating, each chapter that is not ready holds one slot,
  so it reads low until they land. `currentLocation` is the exact answer.
- `KiteDocViewState.nextPage`/`previousPage` cross chapter boundaries and lay
  out the next chapter when they need to.
- **An `@font-face` inside a document's own `<style>` block now belongs to that
  document.** It used to reach every chapter in the book, which is not what a
  `<style>` block does anywhere else. Faces declared in a stylesheet are still
  the whole book's, which is where real books put them.

### Fixed

- A `url()` in a stylesheet resolves against that stylesheet's folder, as CSS
  says. It used to resolve against the folder of whichever document linked the
  sheet, so an `@font-face` in a book that keeps its CSS and its chapters at
  different depths silently fell back to a system font.
- A fixed-layout document with no `<meta name=viewport>` falls back to the
  reader's current page size. The fallback used to be frozen at whatever the
  book was first opened with, so `withPageSize` did not reach it.

### Added

- **`KiteLocation(chapter, page)`** for a position in the current layout, and
  **`KiteBookmark`** for one that survives a re-flow. Save a bookmark when the
  reader leaves, hand it back when they return, and they land in the same
  paragraph even if the font size changed.
- **`rememberKiteDocViewState(document, bookmark)`**, plus
  `state.currentLocation`, `state.currentBookmark()`, `state.scrollTo(location)`,
  `state.scrollTo(bookmark)`, `state.knownPageCount` and `state.isComplete`.
- **The chapter API on `KiteDocument`**: `chapterCount`, `prepareChapter`,
  `isChapterReady`, `pageCountIn`, `page(location)`, `pageIndexOf`,
  `locationOf`, `bookmarkOf`, `locate`, `isComplete`, `knownPageCount`. Every
  one has a one-chapter default, so `PdfDocument` and any third-party handler
  answer them without writing code.
- **`KiteDocView(chapterPlaceholder = ...)`** for what a chapter shows while it
  is still being laid out.
- **`EpubDocument.bookmarkOf(href)`**, which turns an internal link into a
  position without laying anything out.
- `KitePageIndicator` marks the total with `~` until the book is complete.

### Notes

- `pageCount` and `pages` still lay out every chapter and still return exactly
  what they did. Use `knownPageCount` with `isComplete` for a running total.
- `KiteDocLayout.Spread` pairs pages by index, so it lays the document out
  fully before composing. It is meant for fixed-layout content anyway.
- Laying out any chapter also reads chapter 1, because the writing mode and the
  hyphenation language are one decision per book and both come from it. That is
  two chapters, never the whole book.
- Byte-level streaming (reading parts of the file instead of all of it) is a
  separate problem. Reading the bytes was never what made a book slow to open:
  it is 0 to 11 ms on the corpus.

---

An API naming pass, earlier in the same cycle. Two things were wrong. The
viewer called itself PDF while serving both formats: the Compose viewer, its
state, its layouts and its widgets were all named `Pdf*` even though every one
of them drives an EPUB exactly as it drives a PDF. And six core types carried bare names that collide
with Compose, Android, Skia and AWT types, which forced aliased imports at
almost every call site.

The prefix now means what it says: `Kite*` for anything that serves both
formats, `Pdf*` and `Epub*` only where the type really is one format, and no
shared type left with a name a host app already uses.

### Breaking changes and migration

Every old name still resolves, deprecated, for this release cycle. Most are
`typealias`es or one-line wrappers, so `ReplaceWith` fixes call sites in the
IDE.

| old | new |
|---|---|
| `PdfView(state, ...)` | `KiteDocView(state, ...)` |
| `PdfView(document = pdf, ...)`, `EpubView(document = book, ...)` | `KiteDocView(document = anyKiteDocument, ...)` |
| `rememberPdfViewState(pdf)`, `rememberEpubViewState(book)` | `rememberKiteDocViewState(anyKiteDocument)` |
| `PdfViewState` | `KiteDocViewState` |
| `PdfViewColors` | `KiteDocViewColors` |
| `PdfLayout` | `KiteDocLayout` |
| `PdfZoomSpec` | `KiteZoomSpec` |
| `PdfRenderSpec` | `KiteRenderSpec` |
| `PdfHighlight` | `KiteHighlight` |
| `PdfMarkerSide` | `KiteMarkerSide` |
| `PdfSelectionMenu`, `PdfSelectionMenuItem`, `PdfSelectionMenuDefaults` | `KiteSelectionMenu`, `KiteSelectionMenuItem`, `KiteSelectionMenuDefaults` |
| `PdfSelectionHandleEdge`, `PdfSelectionHandlePainter`, `PdfSelectionHandleDefaults` | `KiteSelectionHandleEdge`, `KiteSelectionHandlePainter`, `KiteSelectionHandleDefaults` |
| `PdfRasterizer`, `rememberPdfRasterizer()` | `KitePageRasterizer`, `rememberKitePageRasterizer()` |
| `PdfPageIndicator`, `PdfNavigationControls`, `PdfThumbnailStrip`, `PdfOutlinePanel` | `KitePageIndicator`, `KiteNavigationControls`, `KiteThumbnailStrip`, `KiteOutlinePanel` |
| `PdfDocument.outlines` | `PdfDocument.bookmarks` |
| `WrongPasswordException` | `KiteWrongPasswordException` |
| `TrueTypeFont.GlyphOutline.toPdfPath()` | `toKitePath()` |

Six core types also lost bare names that collide with types every consumer
already imports. The codebase itself was the evidence: `Matrix` was imported
`as PdfMatrix` in fifteen files and `BlendMode` `as PdfBlendMode` in six,
purely to dodge the Compose, Android, Skia and AWT types of the same name.
Those aliases are gone now.

| old | new | collided with |
|---|---|---|
| `core.Rectangle`, `core.render.Rectangle` | `core.KiteRectangle` | `java.awt.Rectangle` |
| `core.render.Matrix` | `core.render.KiteMatrix` | `androidx.compose.ui.graphics.Matrix`, `android.graphics.Matrix` |
| `core.render.BlendMode` | `core.render.KiteBlendMode` | Compose, Android and Skia `BlendMode` |
| `core.render.ColorSpace` | `core.render.KiteColorSpace` | Compose and Android `ColorSpace` |
| `core.font.FontFamily` | `core.font.KiteFontFamily` | `androidx.compose.ui.text.font.FontFamily` |
| `core.render.ImageXObject` | `core.render.KiteImageData` | (PDF jargon; EPUB builds these too) |
| `compose.TextSelection` | `compose.KiteTextSelection` | |
| `compose.PageHit` | `compose.KitePageHit` | |

`Rectangle` was reachable from two packages; only `io.github.yuroyami.kitepdf.core.KiteRectangle`
remains. One caveat on the aliases: Kotlin expands a type alias for type
positions, constructors, companion members and enum entries, but not for
nested classifiers, so `ImageXObject.Kind` has to become `KiteImageData.Kind`
by hand.

`KitePDF.open(bytes)` is deprecated. It read as the entry point for the whole
library while only ever returning a `PdfDocument`; use `PdfDocument.open` for a
PDF or `KiteDoc.open` for either format. `KitePDF.VERSION` stays.

Two changes are more than a rename:

- **`onLinkTap` now receives a `KiteLinkAction`, not a `PdfAction`.** An EPUB
  href used to be wrapped in a fabricated `PdfAction.Uri` carrying an empty
  dictionary, so an EPUB-only app had to import PDF types to read a URL back
  out. EPUB links now arrive as `KiteLinkAction.Uri`; PDF links arrive as
  `KiteLinkAction.Pdf` with the parsed `PdfAction` untouched, so nothing is
  lost. Both answer `link.uri`, so opening web links needs no `when`. The
  deprecated `PdfView`/`EpubView` wrappers keep the old callback type.
- **`PdfDocument.outlines` is now `bookmarks`.** It sat one letter away from
  the format-neutral `PdfDocument.outline`, with a different element type.

Also removed, both long past the one cycle they were promised: the
`PdfCanvas`/`PdfPath`/`PdfShading`/`PdfPattern`/`PdfFunction` aliases from
0.2.0, and `IosPdfRasterizer` from 0.0.2 (use `ApplePdfRasterizer`).

`EpubPage.width`/`height` are deprecated in favour of `displayWidth`/
`displayHeight`, which every `KitePage` answers. They were the same numbers
under a name that means something else on `PdfPage`, where `width` is the
`/MediaBox` and ignores `/CropBox` and `/Rotate`.

### Added

- **`KiteDoc`, the format-neutral opener**, in `io.github.yuroyami.kitepdf.document`
  in the `kitepdf` umbrella artifact. `KiteDoc.open(bytes)` reads the format out
  of the bytes and returns a `KiteDocument`, and `KiteDoc.formatOf(bytes)` answers
  `Pdf`, `Epub` or null from the header alone. Both handlers' own `open` are
  unchanged and are still the direct route when you already know the format.
- **More ways in than a byte array.** Every one is a thin adapter that ends in
  the same `open(bytes)`, since the engine has no incremental reader.

  | source | call | targets |
  |---|---|---|
  | Base64 or a `data:` URI | `KiteDoc.openBase64(text)` | all |
  | file path | `KiteDoc.openFile(path)` | JVM, Android, Apple, Linux, Windows, Android NDK |
  | `java.io.File`, `InputStream` | `KiteDoc.open(file)` / `open(stream)` | JVM, Android |
  | Android content `Uri` | `KiteDoc.open(context, uri)` | Android |
  | `NSData`, `NSURL` | `KiteDoc.open(data)` / `open(url)` | Apple |
  | remote URL | `KiteDoc.openUrl(url, client)` | `kitepdf-net` |

  The Base64 reader takes a bare payload or a whole data URI, either alphabet,
  padded or not, and ignores line breaks.
- **`EpubDocument.openFile(path)`** on JVM, Android and Apple, matching the
  `PdfDocument` one that already existed.
- **`io.github.yuroyami:kitepdf-net`**, a new optional artifact: `KiteDoc.openUrl`
  and `KiteDoc.downloadBytes`. It is the only place Ktor enters the build, so
  the engine artifacts keep their kotlin-stdlib + KiteImage dependency set. You
  supply the `HttpClient`, so timeouts, auth, retries and logging stay yours.
  Ktor does not ship for `androidNative*` or `wasmWasi`, so neither does this.
- **`KiteDocView(document = ..., theme = ...)`.** The convenience overload takes
  a reading theme, which used to be reachable only through `EpubView` or by
  building `KiteDocViewColors` by hand. Themes have always worked for PDF too.

### Fixed

- Internal ticket codes (`T-14`, `T-80`, `T-32/T-82`, ...) are gone from the
  KDoc and comments. They meant nothing to anyone reading the published API.
- The Maven descriptions for `kitepdf-compose-viewer` and
  `kitepdf-skia-renderer` said "PDF" only. Both have handled EPUB since 0.2.0.

## [0.6.3] - 2026-08-19

A switch to turn text selection off, for viewers that show a document as a
picture rather than as prose.

### Added

- **`PdfView(selectionEnabled = ...)`**, on both the state-based composable and
  the `PdfView(document = ...)` shorthand. The default, `true`, is exactly the
  behaviour every existing caller already has. `false` removes text selection
  outright: the long-press gesture is not attached, so it cannot compete with
  panning; nothing anchors a selection; no wash and no thumbs are painted; and a
  selection already on screen is dropped, which hands back the pan and scroll
  locks it was holding. Zoom, pan, tap, links and page navigation are untouched.
  Intended for a chart, a scan, a trace, a generated report, where selecting a
  text label means nothing to the reader.

## [0.6.2] - 2026-08-19

Draggable selection thumbs. The two markers bounding a text selection were
paint only, which read as a broken control: they look exactly like the grab
handles every other reader has, and they did nothing.

### Changed

- **The selection thumbs are grab targets.** Pressing within 24.dp of either
  marker drags that end of the selection while the other end stays anchored,
  and hauling one past the other swaps the two ends instead of collapsing the
  selection. The gesture sits in front of the long-press detector and only
  claims a press that landed on a thumb, so long-press selection, tap, pan and
  pinch are unchanged everywhere else; the finger's offset from the boundary is
  captured on grab, so the selection does not jump when a thumb is touched. No
  new API and nothing to enable. A selection still lives on one page.
- The grab region is the boundary line, not whatever a custom
  `PdfSelectionHandlePainter` draws around it, so a replacement marker cannot
  paint itself out of reach. It is measured in screen pixels, which keeps the
  touch target one size at every zoom level while the marker itself keeps
  scaling with the text.

## [0.6.1] - 2026-08-08

Link annotations. A consumer reported that links inside a PDF were neither
visible nor clickable; all three causes below are additive fixes, so this is a
drop-in upgrade from 0.6.0.

### Fixed

- **Rectangle corners are normalised (§7.9.5).** A rectangle array holds two
  *diagonally opposite* corners in **any order**, and the consumer must sort
  them. `Rectangle.fromPdfArray`, `PdfAnnotation`'s `/Rect` and `PdfPage`'s box
  reader all took the four numbers positionally, so a producer writing
  `[x2 y2 x1 y1]` yielded an inside-out box: `width` and `height` went negative.
  A negative-size border painted nothing, and the viewer's containment test
  (`y < bottom || y > top`) could not be satisfied by any point at all, so every
  link on such a page was simultaneously invisible and untappable. The single
  annotation fixture used a well-ordered rect, which is why no test caught it.
  `Rectangle.normalized()` is public for callers holding a rectangle from
  elsewhere.
- **`/Border` and `/BS /W` are honoured (§12.5.4).** A link declaring
  `/Border [0 0 0]` asks for no visible frame, which is what a link styled as
  coloured text wants; the synthesized appearance drew a box around it anyway.
  `/BS` supersedes `/Border`, and an undeclared width still falls back to a
  hairline so nothing that used to be visible disappears.

### Added

- `PdfAnnotation.borderWidth`, the declared width in points, or null when the
  annotation declares neither `/BS /W` nor `/Border`.
- `onTap` and `onLinkTap` on the convenience `PdfView(document = …)` and
  `EpubView(document = …)` overloads. They were only on the state-based
  `PdfView`, so links were permanently inert in the shorthand form: the
  dispatcher reads `onLinkTap?.invoke(action) == true`, and a null callback
  makes that false, which sends the tap on to `onTap` as an ordinary page tap.
  Both default to null, so this is source- and binary-compatible.

## [0.6.0] - 2026-08-06

Thread-safety hardening across the engine, closing every confirmed finding of
a full concurrency audit. No public API changes; two behavioural changes are
called out below.

### Fixed

- The iOS text-cache crash is now fixed at the root instead of narrowed:
  skiko's text stack shares process-global state with the host UI
  thread, which no library lock can exclude. The off-main raster now probes a
  page on the pool with system-font text skipped and, only when that fallback
  engages (EPUB body text, PDFs without embedded outlines), re-renders the
  page on the main dispatcher. Pages whose glyphs all carry embedded outlines
  keep rendering entirely off-main.
- `Standard14Widths` and the predefined-CMap table cache are no longer
  mutable process-global maps: both are immutable maps of per-entry lazies
  built at init, so concurrent renders and main-thread text extraction can
  no longer corrupt them. This also fixes a waste bug where every lookup of
  an unbundled `Uni*` CMap name re-decoded and re-stored a null.
- `PdfDocument`'s cycle guard was rewritten: it now tracks every thread
  parsing an object (not just the first), covers the object-stream decode
  path (a crafted `/ObjStm` whose `/Length` pointed back into itself
  recursed without bound, an uncatchable crash on iOS), releases its claim
  on every exit path (an out-of-bounds xref offset used to leak it), and
  caps resolution depth as a backstop. New `ResolutionGuardTest` covers
  both crafted-file cases on all targets.
- `SvgImage.render` and TrueType glyph parsing are reentrant: the
  destination canvas and the file cursor now travel as parameters instead
  of instance fields, so concurrent renders of the same page or font no
  longer hijack each other's state.
- The glyph outline memo caches on `TrueTypeFont` and `CffFont` are guarded
  by a per-face lock (faces are shared across every `EpubDocument` derived
  from one parse via `withSettings`).
- `KiteLock`'s native spinlock yields after a bounded spin instead of
  busy-waiting forever, so a high-QoS waiter can no longer starve a
  lower-QoS lock holder on Darwin.
- `PdfThumbnailStrip` rasterization is routed through the same guarded
  helper as the main view: a page that fails to rasterize keeps its
  placeholder instead of aborting the host app.
- `KiteWarnings.sink` is `@Volatile`, so installing a sink mid-render is
  safely published to worker threads.
- Switching `PdfView` layouts (Continuous to Paged/Spread and back) now
  keeps the reading position: the incoming layout seeds from the live
  adapter position instead of a value the outgoing layout only publishes
  after composition, which lagged one switch behind.

### Changed

- `PdfImage.rgb`/`gray`/`jpeg`/`jpx` document their array ownership: the
  caller's array is referenced, not copied, and must stay untouched until
  `build()` returns. `rgba` is unaffected.

## [0.5.1] - 2026-08-01

Selection you can feel and see, and margin markers that pick a side. Published
to Maven Local only; the next Maven Central release carries these changes.

### Added

- Selection boundary handles: a caret down the boundary line plus a dot
  beneath it, at the leading edge of the first selected quad and the trailing
  edge of the last. They are indicators rather than drag targets; the
  selection still grows by the long-press drag that created it. Styled by the
  new `PdfViewColors.selectionHandle`, opaque by default where the selection
  wash is translucent, and sized against the boundary line's own height so
  they scale through thumbnails and deep zoom.
- Selection haptics, on every platform with a haptic engine: a long-press
  buzz when the selection anchors and one `TextHandleMove` tick each time the
  selected TEXT changes while dragging. Ticking on text rather than on pixels
  is what keeps a slow drag from rattling.
- `PdfHighlight.edgeMarkerSide` with `PdfMarkerSide.Start`/`End`. `End` (the
  right margin in display space) is the pre-0.5.1 behaviour and stays the
  default; `Start` mirrors the same clamp into the left margin, including the
  refusal to paint when text runs into it.

### Changed

- Nothing breaking. Both additions are defaulted parameters; 0.5.0 call sites
  compile and render identically.

## [0.5.0] - 2026-07-31

Scanned books stop rendering as black pages. An image in a PDF may nominate a
second image as its `/Mask`, the stencil that decides which of its pixels are
allowed to paint, and KitePDF never read that entry. Scanners write school
books that way: a photo of the paper underneath, a near-solid block of black
ink on top, and a stencil in the shape of the letters that lets the ink through.
Without the stencil the ink covered the page, and the only things left visible
were the small figures drawn after it. Twenty-three of the 95 real documents in
the verification corpus were unreadable for that reason and now render.

0.4.0 was published to Maven Local only and never reached Maven Central, so
0.5.0 is the release that carries both it and this fix.

### Added

- `/Mask` in both of its forms (ISO 32000-1 section 8.9.6). A stencil `/Mask`
  is a 1-bit image XObject, coded with JBIG2, CCITT or Flate, whose samples say
  which of the base image's pixels may paint: with the default `/Decode [0 1]`
  a 0 sample paints and a 1 sample is masked out, and `/Decode [1 0]` on the
  mask swaps the two. It is decoded into the same 8-bit alpha plane `/SMask`
  already produces, so both masking forms composite through one tested path.
- Colour-key `/Mask`, the array form: 2 x n bounds tested against the image's
  source samples, before `/Decode` and colour conversion as the specification
  requires, making every pixel that falls inside all of them transparent. The
  ranges are public as `ImageXObject.colorKeyMask`.
- Composite-grid alignment. A stencil is usually finer than the layer it masks:
  a scanned textbook page pairs a 300 dpi stencil with a 75 dpi block of ink.
  The image is resampled up onto the stencil's grid rather than the stencil
  down onto the image's, because the ink layer holds no detail of its own to
  lose while the stencil holds the letter shapes. Beyond a size ceiling, or for
  sample depths other than 8 bits, the mask is instead sampled onto the image's
  grid, which still paints the right pixels, just more coarsely.
- Regression coverage: stencil polarity in both directions, a stencil finer than
  its image and one coarser than it, colour-key masking and a colour-key array
  of the wrong arity, `/SMask` winning over `/Mask`, an undecodable mask and a
  truncated one. A synthetic PDF drives the whole path through the AWT
  rasterizer, so the raster gate needs no corpus file.

### Changed

- An image carrying a `/Mask` is no longer held in the per-document decoded
  image cache. Those are the ink layer of a scan, one use per page, and holding
  a page-sized composite for each page of a book would cost far more than the
  reuse it would save. `/ImageMask` stencils were already treated this way.

### Fixed

- `/SMask` takes precedence when an image carries both masks, which is what the
  specification asks for. Only one of the two is ever resolved.
- A mask that cannot be decoded, is the wrong shape, or declares an absurd size
  leaves its image painted unmasked. A renderer fed untrusted files must degrade
  to the old behaviour there, never blank the page and never throw.

### Measured

JVM suites: 791 tests across the six tested modules, 0 failures (compose-viewer
33, core 95, epub 269, native-renderer 72, pdf 318, skia-renderer 4). The
Compose viewer also compiles for Android, iOS device and simulator, macOS, JS,
and Wasm. On the 95-document verification corpus, the 23 affected books went
from 95 to 100 percent black pixels per page down to the 2 to 10 percent a page
of text should have, and pages were checked against `mutool` renders of the
same files.

## [0.4.0] - 2026-07-31

Three viewer features for apps that annotate what they display. Text selection
now holds the page still instead of competing with pan and scroll, highlights
can each carry their own colour, and a highlight can put a marker in the page
margin so a reader sees that a note exists without reading the page first.

### Added

- `PdfViewState.isSelectionActive`, the explicit signal that text selection owns
  the gesture. It is raised the instant the long press fires, which is before
  `selection` exists, and it survives the finger lifting, so the page also stays
  put while the user reaches for a copy button. `clearSelection()` lowers it,
  and so does a long press that anchored nothing, which keeps a stray press on
  a margin from freezing the viewer.
- `PdfViewState.highlights`, a second overlay channel taking `PdfHighlight`
  entries. Each carries a `KiteSearchHit` plus an optional `color`, so an app
  can paint notes by category. A null colour paints
  `PdfViewColors.searchHighlight`, exactly what the existing channel does, and
  `searchHighlights` itself is unchanged. `KiteSearchHit` deliberately gains no
  colour field; it stays a pure text-search result.
- `PdfHighlight.edgeMarker` and `PdfHighlight.edgeMarkerColor`, a rounded marker
  in the page's right margin, level with the highlighted text. Every dimension
  is a fraction of the rendered page width, so it keeps its proportions in a
  thumbnail and at deep zoom alike, and its inner edge is clamped past the
  highlighted quads so it never paints over the words.
- Regression coverage for all three: the selection lock across a whole gesture
  including the window before a selection object exists, a pointer-driven
  one-finger drag that must not pan a zoomed page under a selection, a
  pointer-driven strip drag that must not scroll under one, per-highlight
  colours against the unchanged default, and the marker's position, its
  clearance from the text and its scaling at 1x and 2x.

### Fixed

- A drag that started as a text selection no longer pans the page underneath
  it. Claiming the drag was never enough on its own: the pinch handler reads its
  pan on the Initial pointer pass, before the selection detector sees anything,
  and `calculatePan` ignores consumption, so both pan sites fired anyway. They
  now gate on `isSelectionActive`. Zoom is untouched, so a two-finger pinch
  still works mid-selection.
- The continuous strip and both pagers no longer scroll under an active
  selection. Suppressing pan alone still let the list carry the page away.

### Changed

- The private per-page overlay renderer is now `Modifier.highlightOverlay`
  rather than `searchHighlightOverlay`, since it paints three channels. No
  public API changed with it.
- Documentation: the Compose viewer guide gains "Highlights" and "Text
  selection" sections covering both channels, the margin marker and the
  selection lock.

### Measured

JVM suites: 778 tests across the six tested modules, 0 failures (compose-viewer
33, core 95, epub 269, native-renderer 68, pdf 309, skia-renderer 4). The
Compose viewer also compiles for Android, iOS device and simulator, macOS, JS,
and Wasm.

## [0.3.1] - 2026-07-26

Compose system-font fallback rendering now keeps the advance widths assigned by
PDF and EPUB layout.

### Fixed

- System-font runs are shaped once and then scaled to the document glyph
  advances. A host substitute font can have wider metrics than the requested
  font. The old renderer painted that wider run without adjustment, which made
  adjacent publisher-serif words collide at larger EPUB text sizes.
- The width correction preserves ligatures, right-to-left shaping, combining
  marks, and non-uniform text-matrix scaling. Invalid or degenerate dimensions
  retain the previous unscaled fallback.

### Added

- Regression coverage for exact metric scaling, invalid dimensions, and two
  adjacent serif runs at 21 and 29 pixels.

### Measured

JVM suites: 772 tests across the six tested modules, 0 failures. The Compose
viewer also compiles for Android, iOS device and simulator, macOS, JS, and Wasm.

## [0.3.0] - 2026-07-25

Rendering correctness and supply chain. Image decoding moves out to KiteImage,
function-based shadings lose their seams, embedded glyph outlines get their
transform order fixed on every canvas, and the differential harness loses every
way it could report a false green.

### Changed

- `:kitepdf-core` decodes images through KiteImage
  (`io.github.yuroyami:kiteimage:0.1.0`), declared `api`. This is the module's
  first runtime dependency beyond `kotlin-stdlib`; everything else in it stays
  stdlib-only. `JpegDecoder`, `PngDecoder`, `GifDecoder`, `JpxDecoder`,
  `Jbig2Decoder` and `MqDecoder` are gone, and `CcittFaxFilter` keeps its
  `PdfDictionary` parameter parsing while delegating the algorithm. All six were
  `internal` in 0.2.0, so no public API was removed, but KiteImage's public API
  now sits on the consumer compile classpath.
- Decoder coverage widens with the move. JPEG gains progressive SOF2, restart
  intervals, 4:1:1 and YCCK. PNG gains 1/2/4-bit depths, the Average and Paeth
  filters, and color-key `tRNS`. GIF gains complete LZW (KwKwK and deferred
  clear) and interlace. `ImageXObject.fromEncodedImage` sniffs the format and
  additionally accepts BMP and JP2 from EPUB and CBZ content.
- Android `compileSdk` moves from 36 to 37 in all eight modules, because Compose
  Multiplatform 1.12.0-beta02 requires it. Consumers of the published Android
  artifacts must compile against API 37 or later. `minSdk` is unchanged: 21 for
  the engine and the Skia renderer, 24 for the Compose viewer, 29 for the native
  renderer.
- Deprecated Kotlin Multiplatform configuration removed:
  `kotlin.mpp.androidSourceSetLayoutVersion` and the `js(IR)` target form.

### Fixed

- Function-based (type 1) shadings no longer show a hairline grid where the
  sampled cells meet. Adjacent cells now overlap by half a device pixel, applied
  only at full alpha under `BlendMode.Normal` with a finite non-singular CTM,
  capped at half a cell, and computed shear-aware so the overlap stays half a
  pixel in device space. The outer domain boundary is unchanged.
- Embedded glyph outlines composed their transform in the wrong order on all
  four canvases (Skia, Android, CoreGraphics and Canvas2D). Scale, then offset,
  then device space is now applied consistently.
- The shading-type dispatch in `paintComplexShading` is exhaustive again. The
  `else -> Unit` catch-all that silently swallowed unhandled types is gone.

### Added

- MuPDF oracle hardening. `pageCountDetailed` and `renderDetailed` return sealed
  results instead of nullables, carrying the reason a call failed. Both enforce a
  60 second timeout with escalating termination, capture the exit code, and
  validate the output rather than trusting a zero exit. Page-count parsing
  tolerates diagnostics printed before the count.
- The differential harness validates `kitepdf.diff.maxpages`, the DPI, the diff
  budget and the corpus path, and reports oracle and comparison failures in
  their own section instead of folding them into the score.
- Regression suites for the paths above: `MuPdfOracleTest`,
  `OracleFailureHandlingTest`, `DifferentialConfigurationTest`, `ImageDiffTest`,
  `EmbeddedGlyphTransformTest` and `FunctionShadingSeamTest`. The
  failure-handling suite pins the three false-green cases: a discovered but
  broken oracle cannot pass as a zero score, a page-geometry mismatch cannot be
  rescaled into a score, and a truncated page count cannot pass with finite
  scores.
- The EPUB PNG test fixture writes real per-chunk CRC-32 and Adler-32 values.
  The previous fixture used dummy values that the old decoder ignored and
  KiteImage rejects. The raster test now decodes the fixture and asserts the
  pixels it produces.

### Documentation

- README rewritten for newcomers, carrying the artifact map that was missing.
  Seven modules are published, the README listed four, and `:kitepdf-core`
  appeared on no page at all. It now documents the trap that breaks a first
  build, where all three renderer modules hold their engine dependency as
  `implementation` while exposing `PdfDocument` and `PdfPage` in their own
  signatures.
- The "no expect/actual" claim is dropped from the README and three docs pages.
  `:kitepdf-core` has three, and the JVM `PlatformFlate` actual is
  `java.util.zip`. Corrected alongside it: the sample app is a JVM entry point
  only, the engine does not compile for every Kotlin target, and the EPUB
  hyphenation language list.
- Shared Kite Dokka theme, `Module.md` for all seven modules, and an
  `mkdocs.yml` matching the other Kite libraries.
- Em dashes removed repo-wide, including 687 from Kotlin comments across 191
  files, verified comment-only.
- A long-standing packaging constraint is now written down: on Android,
  `kitepdf-skia-renderer` resolves `org.jetbrains.skiko:skiko-android`, which
  JetBrains publishes to the Compose dev repository rather than to Maven
  Central, so that repository has to be added. This has been true since the
  module first shipped and is unchanged in 0.3.0. Every other target and every
  other artifact resolves from Maven Central alone. On Android,
  `kitepdf-native-renderer` remains the recommended renderer.

### Build

- The vanniktech publish plugin is declared at the root with `apply false`.
  Applying it only to sibling modules loaded its shared build service under two
  classloaders and left `publishAndReleaseToMavenCentral` unable to configure.
- CI builds a multi-target matrix and publishes the documentation site.
  `kotlin-js-store/yarn.lock` was refreshed so the js job stops failing
  `kotlinStoreYarnLock` on lock drift.

### Measured

Differential run against `mutool` at 96 DPI: 36 pages, 0 render failures, 0 blank
pages, 0 oracle or comparison failures, mean MAE 0.0062 versus MuPDF. JVM suites:
769 tests across the six modules, 0 failures.

## [0.2.0] - 2026-07-11

The multi-format release: the engine becomes a MuPDF-style core + handlers
architecture, gains a complete EPUB reader, closes the PDF completeness gaps
(shadings, Type3, JPX, JBIG2, CJK CMaps, soft masks), and lands the breaking
API cleanup this release exists for.

### Breaking changes and migration

Explicit API mode is enabled everywhere, the format-neutral core types lost
their `Pdf` prefix, and every `:kitepdf-core` package moved under
`io.github.yuroyami.kitepdf.core.*` to eliminate split packages (which broke
JPMS consumers and confused R8). `:kitepdf-pdf` keeps the root package.

| old import | new import |
|---|---|
| `io.github.yuroyami.kitepdf.render.PdfCanvas` | `io.github.yuroyami.kitepdf.core.render.KiteCanvas` |
| `io.github.yuroyami.kitepdf.render.PdfPath` | `io.github.yuroyami.kitepdf.core.render.KitePath` |
| `io.github.yuroyami.kitepdf.render.PdfShading` | `io.github.yuroyami.kitepdf.core.render.KiteShading` |
| `io.github.yuroyami.kitepdf.render.PdfPattern` | `io.github.yuroyami.kitepdf.core.render.KitePattern` |
| `io.github.yuroyami.kitepdf.render.PdfFunction` | `io.github.yuroyami.kitepdf.core.render.KiteFunction` |
| `io.github.yuroyami.kitepdf.render.Matrix` | `io.github.yuroyami.kitepdf.core.render.Matrix` |
| `io.github.yuroyami.kitepdf.Rectangle` | `io.github.yuroyami.kitepdf.core.Rectangle` |
| `io.github.yuroyami.kitepdf.KitePage` (and `KiteDocument`, `KiteMetadata`, `KiteOutlineItem`, `KiteStructuredText`) | `io.github.yuroyami.kitepdf.core.*` |
| `io.github.yuroyami.kitepdf.parser.{Lexer, PdfObject, ...}` (core files) | `io.github.yuroyami.kitepdf.core.parser.*` |
| `io.github.yuroyami.kitepdf.font.*` | `io.github.yuroyami.kitepdf.core.font.*` |
| `io.github.yuroyami.kitepdf.{compression, filters, text}.*` | `io.github.yuroyami.kitepdf.core.{compression, filters, text}.*` |

Deprecated `typealias`es for the five renamed types ship in `:kitepdf-pdf`
(`PdfCanvas = KiteCanvas`, ...) for this release cycle only.

Other breaking changes:

- `EpubDocument.open` now returns a non-null document or throws
  `EpubFormatException` naming the first structural failure; use
  `EpubDocument.openOrNull` (and the new `PdfDocument.openOrNull`) for
  null-on-failure call sites. `PdfFormatException` and `EpubFormatException`
  share the new `KiteFormatException` supertype in core.
- `Parser`, `XrefParser` (pdf) and `LzwFilter`, `Predictors` (core) are now
  `internal`.
- The raw object-model surface (`PdfDocument.xref`/`trailer`/`resolve`,
  `PdfEditor.addObject`/`updateObject`/`allocateReference`/`setTrailerEntry`)
  now requires opting in to `@KiteRawApi` (a warning, not an error: stable
  file format, unstable Kotlin surface).
- `EpubDocument.metadata` was renamed `epubMetadata`; the format-neutral
  `metadata` (title/authors/language/cover) comes from `KiteDocument`.

### Added

- EPUB support: a second document handler, `:kitepdf-epub`, built on the shared
  core and proving the multi-format architecture.
  - Pure-Kotlin reflowable EPUB 2/3 reader: container/OPF parsing, metadata,
    table of contents, and pagination to fixed page sizes.
  - CSS engine: cascade with user-agent, author, and reader origins, selector
    matching including pseudo-classes and sibling combinators, and
    `::before`/`::after` generated content.
  - Box-model layout: block and inline flow, margins/borders/padding, tables,
    `float`/`clear` with exclusion bands, inline images on the baseline, and
    `position: relative`.
  - Typography: per-glyph font fallback, embedded fonts (TrueType,
    OpenType/CFF, WOFF, and WOFF2 via a from-scratch pure-Kotlin Brotli
    decoder), Unicode bidirectional text, Knuth-Liang hyphenation
    with bundled TeX patterns for English, German, French, Spanish, Italian,
    Portuguese, and Dutch, CJK inter-character justification with kinsoku
    line-break rules, ruby annotations, `text-transform`, letter and word
    spacing, and synthesized small-caps.
  - Structured text extraction and search over laid-out pages; anchors,
    internal links, and href-to-page navigation.
  - Reader settings (`EpubSettings`): font family, line-height scale, text and
    background colors, forced justification, and a publisher-CSS toggle,
    applied as a dedicated cascade origin that overrides author `!important`.
- Module taxonomy: the single `:kitepdf` module is split MuPDF-style into
  `:kitepdf-core` (the shared core: geometry, canvas, fonts, images,
  compression, text) and `:kitepdf-pdf` (the PDF handler), joined by
  `:kitepdf-epub`; the renderers are renamed to `:kitepdf-skia-renderer` and
  `:kitepdf-compose-viewer`, and `:kitepdf` remains as an umbrella artifact
  that pulls in everything.
- Pure-Kotlin image codecs in the core: PNG (decoder and encoder), JPEG, GIF,
  and JBIG2, replacing platform-specific decode paths so images render
  identically on every target.
- Robustness hardening against hostile documents: a 512 MiB decompression
  bomb guard across the Flate/LZW/RunLength filters and the PNG, EPUB-zip, and
  WOFF inflate sites, plus content-stream operation budgets (5 million parsed
  operators per stream, 20 million dispatched per page), so crafted inputs
  degrade to truncated output instead of exhausting memory or CPU.
- Custom font embedding in the writer: TrueType (`glyf`) and OpenType/CFF (`.otf`)
  programs are embedded as composite Type0 fonts (Identity-H) with a generated
  `/ToUnicode` map, so emitted text round-trips back to Unicode through the reader.
- From-scratch font subsetting for both formats, keeping only the glyphs a
  document actually draws:
  - TrueType subsetter with `glyf`/`loca` renumbering, a rebuilt SFNT, and a
    `/CIDToGIDMap` stream.
  - CFF subsetter that rewrites the CFF INDEX/DICT/charset/FDSelect structures
    and emits a bare `CIDFontType0C` `/FontFile3`.
  - Subset `/BaseFont` names carry the standard six-letter subset tag.

### Changed

- The renderer seam is format-neutral: `Canvas.drawText` is replaced by
  `Canvas.drawGlyphs`, which takes positioned glyph runs instead of
  PDF-specific text state, so non-PDF handlers drive the same canvas.
- Continuous integration now builds and tests the JVM target on every push and
  pull request, with `mupdf-tools` installed so the differential oracle tests
  run against `mutool` in CI.

### Fixed

- Trust-critical PDF fixes: encryption key authentication, explicit
  wrong-password signalling instead of garbage output, and a redaction leak
  where removed content could survive in the written file.
- Render correctness: page rotation, origin, and crop-box handling; image
  decode fixes; parser error recovery; embedded-glyph advances unified to
  1/1000 em; the default shading-fill path now paints unclipped `sh`
  operations instead of dropping them.
- Font subsystem hardening: CFF Type2 charstring edge cases, CJK CMap
  codespace ranges, and embedded CMap streams.
- EPUB pagination now compiles and runs on non-JVM targets: a JVM-only
  `putIfAbsent` call was replaced with the multiplatform `getOrPut`.
- Oracle tests that previously printed a message and returned early (reported as
  passing) when `mutool` or a test font was absent now use JUnit assumptions, so
  they report as skipped instead of silently green. No real assertion was weakened.

### Added later in the same cycle

- Viewer feature set: engine-level text search with per-page highlight quads,
  viewport hit testing, link taps (PDF link annotations and EPUB hrefs) with
  internal go-to-page handling, outline/TOC panels, text selection with
  long-press drag handles, page thumbnails, RTL reading progression, and
  two-page spreads.
- Format-neutral document seam: `KiteDocument` exposes metadata, outlines,
  structured text, and search for both handlers; `PdfPage.textContent()`
  adapts PDF structured text to it.
- Performance and concurrency: platform zlib fast paths on JVM/Android
  with a dynamic-Huffman pure-Kotlin deflate elsewhere, one glyph-layout pass
  per text run, a per-document decoded-image cache, thread-safe
  `PdfDocument` (concurrent page rendering), lazy `pageCount` from `/Count`,
  off-main-thread rasterization, and a page-bitmap LRU in the viewer. Corpus
  mean render time: 9.7ms/page on the reference machine.
- PDF completeness: text clipping modes 4-7; shading types 1, 4, 5 and
  6/7 (approximated); Type 3 fonts; luminosity soft masks; 47 predefined CJK
  CMaps; complete JBIG2 (MMR, Huffman symbol dictionaries and text regions,
  refinement, pattern/halftone regions); a from-scratch JPEG 2000 (JPX)
  decoder, byte-exact against OpenJPEG on lossless configurations; encrypted
  PDF creation and editing (AES-256/R6 write support in `PdfBuilder.encrypt`
  and `PdfEditor`); vertical writing (tategaki) for EPUB; and a digital
  signature scaffold (`PdfSigner`: prepare, ByteRange, embed; the CMS blob
  comes from the application).
- API cleanup: explicit API mode across all published modules;
  `KitePDF.VERSION` generated from the Gradle version; a `String` password
  overload with the documented UTF-8-then-Latin-1 rule; `KiteWarnings`, a
  process-global warning sink for the lenient salvage paths; CMYK color
  operators in the writer's content builder.
- Test hardening: a deterministic mutation fuzzer (2600 seeded mutants per
  run, wired into every build) and seeded writer round-trip property tests;
  CI now also tests iOS simulator, macOS, and JS(Node) targets on main.

### Fixed later in the same cycle

- A latent AWT soft-mask perf bug (an unclipped surface allocated a
  100-megapixel offscreen buffer per luminosity mask, ~1.1s per page).
- Non-exhaustive shading dispatch on the JS, Apple, and Android native
  canvases (they had not compiled since the shading work landed).
- A glyf-parser crash on fonts with non-monotonic contour end points (found
  by the mutation fuzzer).
- mocha's 2-second default timeout killing slow crypto tests on JS.

## [0.1.0] - 2026-06-17

- Initial public release, published to Maven Central under
  `io.github.yuroyami:kitepdf`.
- Pure-Kotlin PDF engine for Kotlin Multiplatform: parser, renderer, writer,
  editor, encryption, and font handling, callable from `commonMain` and running
  unchanged across Android, iOS, JVM, JS, Wasm, and Kotlin/Native.

[0.3.1]: https://github.com/yuroyami/KitePDF/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/yuroyami/KitePDF/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/yuroyami/KitePDF/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/yuroyami/KitePDF/releases/tag/v0.1.0
