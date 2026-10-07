# Changelog

All notable changes to KitePDF are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `KiteDocLayout.Continuous` takes a `fit`. `KitePageFit.PAGE` shrinks each page of the strip
  until the whole page shows, centred across the strip. `KitePageFit.WIDTH`, the default, keeps
  the pages across the full width (#437).
- A continuous strip draws the pages next to the pages on screen into the bitmap cache once it
  rests, so a scroll to them shows their bitmap at once. `KiteDocLayout.Continuous.prefetchPages`
  sets how many on each side, 1 by default. Compose draws ahead on Android only, so the strip now
  does it on every platform. The slots of a strip share one rasterizer, so a slot also finds a
  page that another slot drew before it left the screen. The constructor and `copy` of
  `KiteDocLayout.Continuous` take `fit` and `prefetchPages` with defaults, so source compiles
  unchanged, while a binary built against 0.12.0 that constructs or copies one needs a rebuild
  (#437).
- `ReaderTheme.withImages(ReaderImages.LineArt)` makes a reading theme recolour line art, as it
  does text: a grey symbol or equation drawn as an image, on white or on transparent paper. A
  black symbol on dark paper then keeps the contrast of black text. Photos and coloured artwork
  keep their colours, and a theme still leaves every image alone unless asked (#458).
- A video shows its subtitle and caption tracks. The track that the element marks `default`
  shows over the picture at once, and a menu on the transport bar picks another track or none.
  `EpubMedia.tracks` lists the `<track>` elements of a media element, with their kind, language
  and label. `KiteMediaLabels` gains `subtitles` and `subtitlesOff` (#483).
- The media elements of a book share a `KiteMediaSession`. An element keeps its place while its
  book is open, and shows that place, paused, when the page comes back. Starting one
  element pauses any other that plays. When the app leaves the screen on Android or iOS, a video
  pauses, and an audio element follows `KiteMediaSession.audioInBackground`. `KiteMediaOverlay`
  and `EpubMediaPlayer` take a `session`, and `EpubMediaPlayer` a `place` to keep (#481).
- The transport bar of a media element seeks: a tap on its line goes there, and a drag scrubs.
  A screen reader moves the line as a slider, five seconds a step. The bar shows the elapsed and
  the total time (#479).
- The transport bar of a media element has a mute button and a speed menu from 0.5× to 2×, which
  keeps the pitch. `KiteMediaLabels` sets the words that the controls say to a screen reader, so
  an app can translate them. `KiteMediaOverlay` and `EpubMediaPlayer` take them as `labels` (#484).
- `KiteReadAloud` speaks a media overlay clip that has no audio through the new `speak` parameter,
  which hands the app's speech engine the clip and its text with the book's pronunciation hints.
  A book that only speaks needs no player. `EpubDocument.readingOrderOf(href)` gives the text of
  any element in reading order. Two more W3C EPUB tests pass (#525).
- `EpubDocument.markNarration(href, playing)` gives an element the book's
  `media:active-class` and its chapter's root the `media:playback-active-class`, as EPUB Reading
  Systems 3.3, 9.2.3 asks. A chapter whose rules use one of the classes is laid out again (#525).

- A roll book, `rendition:layout roll` of EPUB 3.4, reads as one strip: each chapter is one page at
  the size its viewport sets, and the strip shows the pages across the full width with no gap
  between them. `EpubRendition.isRoll` is true for such a book, and also for a pre-paginated book
  whose flow is `scrolled-continuous`, as EPUB Reading Systems 3.4 asks. A roll ignores the layout
  overrides of its spine. `KiteDocLayout.forDocument` picks the strip. Four more W3C EPUB tests
  pass (#506).
- An image that the spine lists, a PNG, JPEG or GIF, shows as a page of its own at its pixel
  size. Its fallback document gives the image its alternative text. Before, the page showed the
  fallback document's text (#614).
- A book whose `rendition:flow` is `scrolled-continuous` or `scrolled-doc` now scrolls: each
  reflowable chapter is one page as tall as its content (EPUB 3.3). `EpubSettings.scrolled` turns
  this on or off for every book. `KiteDocLayout.forDocument` picks the layout the book asks for, and
  the new `KiteDocLayout.Paged.fit` option `KitePageFit.WIDTH` shows a chapter that fills the width
  and scrolls down. `KiteDocument.topOf` gives the height of a bookmark's place on its page, so a
  link to an element far down a tall page shows that element. Four more W3C EPUB tests pass (#505).
- The wheel moves a page that overflows the view, a zoomed page or a page fit to the width. Once the
  page is at its edge, the next wheel gesture turns it (#505).
- `EpubDocument.markupErrors(chapter)` lists where a chapter breaks the rules of XML: a tag that
  never closes, an invalid name, a value without quotes, an undeclared entity or prefix, and
  more. `KiteXml.wellFormednessErrors` in `kitepdf-core` does the check for any XML text, and
  gives each error as a `KiteXmlError` with a line and a column. `KiteDocView` shows the first
  error across the top of the chapter's first page, unless `showMarkupErrors` is false. The
  chapter still lays out what it can. Two more W3C EPUB tests pass. Before this change, such a
  chapter showed no sign of the error (#517).

- `KiteDocView` opens a web or mail address that `onLinkTap` does not take. It asks the reader
  in a small prompt first, then opens the address through Compose's `LocalUriHandler`. The new
  `externalLinks` parameter takes a `KiteExternalLinks`, which sets the schemes the viewer opens
  (`http`, `https` and `mailto` by default) and whether it asks first. Null opens nothing.
  `KiteViewerStrings` has three new strings for the prompt. Two more W3C EPUB tests pass. Before
  this change, such a link did nothing (#519).

- A frame's `contentWindow`, and a frame's `parent` and `top`, are windows of another origin:
  `postMessage` carries a structured clone between the two windows, and a `MessagePort` can
  move to another window and stay entangled. Any other member throws a `SecurityError`. A frame
  element gets its `load` event, and a frame that a script adds loads and runs its scripts.
  Before this change, `contentWindow` was null, a frame's `parent` was itself, and a frame that
  a script added ran no script (#613).

- An `<object>` whose `data` is an HTML or XHTML document of the book shows that document in
  place of its fallback, and the document's scripts run, as a frame's do. The fallback shows when
  the book does not hold the document. Before this change, the object always showed its
  fallback (#612).

- An `<iframe>` in a chapter shows the document of the book that it names, laid out at the
  frame's size, and the document's scripts run in a window of their own. That window has the
  book's origin and `navigator.epubReadingSystem`, and it cannot change the chapter or the
  frame's size. A tap on a frame goes to its document, and a chapter whose
  frames run scripts counts as scripted. Five more W3C EPUB tests pass. Before this change, a
  frame's box stayed empty and its scripts never ran (#528).

- An `<img>` in a book's scripts loads its picture as in a browser: `load` and `error` fire, and
  `complete`, `naturalWidth`, `naturalHeight` and `decode()` follow the load. A canvas draws an
  image once it has loaded, and throws for a broken one. Before this change, every image was
  complete at once, `load` never fired, and the natural size read the `width` and `height`
  attributes (#611).

- A book's scripts can read and write the pixels of a `<canvas>`: `ImageData`,
  `createImageData`, `getImageData`, `putImageData`, `toDataURL` and `toBlob` work, in sRGB or
  Display P3 and in 8-bit or float16 pixels. The pixels are the same on every target and round as
  Chromium rounds them. `EpubScriptRunner` and `EpubScriptSession` take `fontOutlines`, which the
  pixels of text need. Before this change, these methods were missing (#610).

- `KiteRasterCanvas` is a `KiteCanvas` in plain Kotlin that draws into memory, the same on
  every target. `KiteRaster` gains `encodePng`, `encodeJpeg` and `toImageData`.
  `SvgImage.fromElement` takes the images that an `<image>` names by its `href`.

- A book's scripts can draw on a `<canvas>`. `getContext('2d')` answers a
  `CanvasRenderingContext2D`, with `Path2D`, gradients, patterns, text, shadows, filters, every
  composite operation and every blend mode. The drawing shows on the page where the canvas sits,
  and a later drawing paints the page again. Before this change, `getContext` answered `null`
  (#501).

- SVG images take `mix-blend-mode` and `isolation`, and paint the stroke of text and a
  gradient or pattern fill of text through the outlines of the host font. Before this change,
  such text painted filled in black, and text with `fill="none"` painted black too.

- A gradient in an SVG image takes the `fr` radius of SVG 2, so its focal circle can have a size.

- A book's scripts have `DOMPoint`, `DOMPointReadOnly`, `DOMQuad`, `DOMMatrix`,
  `DOMMatrixReadOnly` and `WebKitCSSMatrix`. A `DOMMatrix` reads a CSS transform list, with 3D
  functions and `calc()`. `getComputedStyle` answers `transform` as a `matrix()` (#609).

- A book's scripts can move a `ReadableStream`, a `WritableStream` or a `TransformStream`
  through `postMessage` and `structuredClone` by naming it in the `transfer` list. The new
  stream reads from or writes to the original, which stays locked (#608).

- A book's scripts have the Streams Standard: `ReadableStream` with default and byte readers,
  `tee`, `pipeTo`, `pipeThrough`, async iteration and `ReadableStream.from`, `WritableStream`,
  `TransformStream`, `CountQueuingStrategy`, `ByteLengthQueuingStrategy`, `TextEncoderStream`
  and `TextDecoderStream`. `Blob.stream()` and `Blob.textStream()` work, so every File API test
  of web-platform-tests passes (#536).

- A book's scripts have `AbortController` and `AbortSignal`, with `AbortSignal.abort`, `timeout`
  and `any`. `addEventListener` takes a `signal`, and an abort removes the listener (#607).

- A book's scripts have `structuredClone`, `window.postMessage`, `MessageChannel`, `MessagePort`
  and `MessageEvent`. A clone keeps what HTML keeps, and a `transfer` list detaches buffers and
  moves ports. `postMessage` delivers to the chapter's own window in a task, when the target
  origin is `*`, `/` or the book's. A port holds its messages until it starts. Before this
  change, `postMessage` dropped every message (#534).

- `EpubScriptRunner` and `EpubScriptSession` take an `instanceKey`, a value the app keeps for its
  reader, which goes into the book's origin. Each reader's copy of a book gets an origin of its
  own, as EPUB 3.3 asks. Without a key each runner makes a random one, so a book no longer has
  one origin for every reader (#521).

- A book's scripts have `FormData`, `FormDataEvent` and `SubmitEvent`. `new FormData(form,
  submitter)` takes the entries of the form as HTML builds them, and fires `formdata` at the
  form. A click on a submit button and `requestSubmit` fire `submit` with its submitter and then
  `formdata`, as `submit()` fires `formdata` alone. A control in a disabled fieldset no longer
  activates (#531).

- `EpubDocument.setFragment` and `fragmentOf` hold the fragment the reader reached in each
  chapter. The element that the fragment names matches `:target` in the layout, so a book can
  style the note a link lands on. Only a chapter whose style sheets use `:target` is laid out
  again. `KiteDocView` calls `setFragment` for each link, table of contents entry and bookmark
  the reader follows (#550).

- A book's scripts have `DOMParser` and `XMLSerializer`. `parseFromString` makes an HTML document
  for `text/html` and an XML document for the four XML types, with a `parsererror` document for XML
  that is not well-formed, and the XML parser stops at the first well-formedness error as a
  browser's does. A parsed document keeps its document type, processing instructions and CDATA
  sections as `DocumentType`, `ProcessingInstruction` and `CDATASection` nodes. `serializeToString`
  writes a node as XML with the namespace declarations it needs. `KiteXml.htmlEntity` looks up one
  of HTML's named character references (#543).

- EPUB chapters set tate-chu-yoko: `text-combine-upright: all`, EPUB's
  `-epub-text-combine-horizontal: all` and its older `-epub-text-combine: horizontal`, and their
  `-webkit-` names. In vertical text the element's text stands upright and side by side in the
  space of one character, an em down the column, squeezed across the column when it is wider than
  an em, so a date's digits no longer run sideways one by one. Each element makes its own
  composition, a child that inherits the property included, a line never breaks inside one, and it
  takes one emphasis mark as one character would. The page text keeps its characters inside that
  em, and horizontal text is as it was (#508).

- EPUB chapters draw emphasis marks: `text-emphasis-style`, `text-emphasis-color`,
  `text-emphasis-position` and the `text-emphasis` shorthand, under their `-epub-` and `-webkit-`
  names too. Each letter takes its mark at half its size, from the book's font when the font has
  the mark, centred over it and lifted as ruby is, over or under a line and right or left of a
  column, outside the letter's own ruby, and the line makes room for the marks as it does for
  ruby. Spaces, punctuation other than the few symbols CSS names, controls and combining marks
  take none, a filled or open shape alone is a circle across a line and a sesame down a column,
  and a string draws its first character. The marks stay out of the page text (#508).

- EPUB chapters read `text-underline-position` and `-epub-text-underline-position`. `under` sets
  the underline below the descenders instead of just under the baseline where they cross it, and
  in vertical text `left` and `under` draw it clear of the column on the left while `right` draws
  it on the right, where it used to touch every column on the left. The value is the one of the
  element that draws the line, so a descendant's own value does not move it (#508).

- EPUB chapters read `text-orientation` and `-epub-text-orientation`, with EPUB's
  `vertical-right` and `sideways-right` as `mixed` and `sideways`. In vertical text `upright`
  stands every letter up, one an em down the column and set in the middle of it, with no
  ligature or kerning between them, and `sideways` turns the Japanese as well as the Latin text,
  where one fixed rule used to stand CJK characters up and turn everything else. The value
  inherits and leaves horizontal text as it was (#508).

- EPUB chapters read `line-break` and `-epub-line-break`, which used to share one table of the
  marks that may not start a line. `strict`, and `auto` with it as Unicode's default, keep small
  kana, the long vowel mark, iteration marks such as 々, middle dots, fullwidth `!` and `?`, wide
  suffixes such as `％` and the CJK hyphens off the start of a line; `normal` lets the small kana,
  the long vowel mark and the hyphens start one; `loose` lets everything but the closing marks
  start one and a wide prefix such as `￥` end one; and `anywhere` breaks between any two
  characters, inside a Latin word too, keeping only a letter and its marks together. The katakana
  middle dot used to start a line under every value. The value inherits (#508).

- EPUB chapters read `full-width` in `text-transform`, and EPUB's `-epub-fullwidth`, alone or
  beside a case: letters, digits and signs take their full-width forms and half-width katakana
  its ordinary width, so Latin text in a vertical chapter stands upright as the Japanese around it
  does. The table comes from Unicode's width pairs, through `tools/generate_full_width.py`, and a
  value with a word it does not know leaves the declaration out (#508).

- EPUB chapters read `word-break` and `-epub-word-break`. With `break-all` a line may end between
  any two letters of a word, so each line fills to its edge, with no hyphen and never just before
  a comma, a stop or a closing quote. With `keep-all` Chinese, Japanese and Korean letters stay
  together as a word does, so Korean breaks only at its spaces and Japanese after a closing mark
  or before an opening one. The value inherits (#508).

- EPUB chapters read `text-align-last` and `-epub-text-align-last`: the last line of a block,
  and each line that a `<br>` ends, align as it says, and with `auto` as `text-align` does but at
  the start when that justifies. `start` and `end` follow the text's direction, the value
  inherits, and the reader's justify setting puts it back to `auto` (#508).

- `EpubMetadata.titleRightToLeft` and `creatorsRightToLeft` give the base direction of the title
  and of each creator, so a host can show a right-to-left title or name in its own run of text the
  right way round. A value's own `dir` decides when it says `ltr` or `rtl`, else the package's
  does, and with `auto` or no direction at all its first strong character does, as EPUB 3.3 asks.
  Seven more W3C EPUB tests pass (#510).

- `KiteDocViewState.isScrollInProgress` says whether the view is scrolling or turning a page,
  by a gesture or an animation, and reads observe it, so a `snapshotFlow` can wait for the view to
  settle before it counts `currentLocation` as a place the reader went to (#524).

- `SvgImage.hasIntrinsicSize` is false for an SVG whose root gives only a viewBox, which has a
  ratio and no size of its own, so a layout can give it the width of its box (#569).

- `EpubScriptSession.unloadChapters()` closes the engine of every chapter and keeps the session
  open, as a reading system unloads chapters: each starts over from its markup when next used.
  `EpubScriptRunner` calls it to make room for another runner where engines share one thread (#553).

- A book's scripts have `Blob`, `File` and `FileReader` of the File API, and
  `URL.createObjectURL` and `URL.revokeObjectURL`. A `FileReader` reads in tasks of its own, with
  its events in the order the API gives, and `readAsText` takes the charset of the blob's type
  through a MIME type parser of the MIME Sniffing Standard, `WhatwgMimeType`, which the
  standard's web-platform-tests data checks. A `blob:` URL has the book's origin, and an image, a
  style sheet, an `@import` or a font loads from it as from a file of the book: the store sits on
  the book, so another document over it finds the blob too, and what a chapter shows keeps its
  blob after the script revokes the URL, while a chapter whose engine closes revokes the rest.
  339 tests of web-platform-tests in `FileAPI/` pass in a chapter; the rest wait on
  streams (#536) and KiteJS, for `async` functions and `Float16Array`
  (#533).

- A book's scripts have `TextEncoder` and `TextDecoder` of the Encoding Standard, with every
  encoding and label of its table: UTF-8, UTF-16, the 28 single-byte encodings, gb18030, GBK,
  Big5, EUC-JP, ISO-2022-JP, Shift_JIS, EUC-KR, `replacement` and `x-user-defined`, with `fatal`,
  `ignoreBOM`, streaming and `encodeInto` as the standard says, and `atob` and `btoa` of the HTML
  Standard. The decoders are `WhatwgEncoding` in `kitepdf-epub`, over the standard's indexes,
  which `tools/generate_encoding_tables.py` packs. 11,249 tests of web-platform-tests in
  `encoding/` pass in a chapter, and all of `atob`'s; the rest wait on KiteJS, for
  `SharedArrayBuffer`, `Float16Array` and a rest parameter in an arrow function (#532).

- A book's scripts have `URL` and `URLSearchParams` of the WHATWG URL Standard, over a URL
  parser of its own in `kitepdf-epub`: special schemes and their ports, `..` that stops at the
  root, so an address resolved against a chapter stays inside the book, IPv4 and IPv6 hosts, host
  names through UTS #46 and NFC of Unicode 17 with Punycode, and percent-encoding of each part.
  A URL of the book has the book's origin. The web-platform-tests of the standard run against
  it, its URL, setter and host data on the parser and its JavaScript tests in a chapter, and so
  do Unicode's IdnaTestV2 and NormalizationTest. The W3C tests `ocf-url_parse-leaking-relative`
  and `ocf-url_parse-path-absolute` pass (#520).

- `KiteDataUrl` in `kitepdf-core` decodes a `data:` URL as the WHATWG Fetch standard does: its
  media type, and its body percent-decoded, then Base64-decoded when it says so, forgiving white
  space and missing padding. `KiteDataUrl.isDataUrl` and `KiteDataUrl.essenceOf` answer without
  decoding. `SvgImage` uses it for an `<image>` and no longer boxes each byte (#514).

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
- EPUB hyphenation covers more than 60 languages: every set of the hyph-utf8 project whose
  licence an Apache-2.0 library can carry, 71 in all, with their exception lists. English uses
  the full `hyph-en-us` set instead of a list of about sixty patterns, and `en-GB` and the other
  British-spelling regions get `hyph-en-gb`. The rest of a language tag picks among a language's
  sets, such as `de-1901`, `el-polyton` and `sr-Latn`, and Serbian with no script hyphenates both
  alphabets. Only the sets a book asks for are read, into a trie of flat arrays.
  `tools/generate_hyphenation.py` writes the sets from a hyph-utf8 checkout, each with its
  copyright and licence in its header (#207).

### Fixed

- In right-to-left text, a mark such as an Arabic vowel sign was drawn left of its base by the
  width of that base, so it sat over the next letter. Each mark now sits where HarfBuzz puts it
  (#622).
- A book in a language with no bundled hyphenation patterns, such as Czech, was hyphenated with
  the English patterns, so its words broke where English ones would. It is now not hyphenated,
  and soft hyphens still break (#615).
- A word with an `İ` in it had every later hyphenation point one place to the right, because
  lower-casing made that letter two characters (#616).
- A word with a combining mark, such as a vowel sign of Devanagari or a decomposed accent, was
  never hyphenated, and no break now goes before a mark (#617).
- A word with punctuation next to it, such as a comma, a full stop or a quotation mark, was never
  hyphenated, nor was a word with an apostrophe in it, such as `l’université` (#618).
- A number inside right-to-left text draws as a paragraph of the text draws it. A canvas without
  a layout of its own forced the digits right to left with the letters, so the text engine
  shaped them with the letters, and on macOS they drew differently from a paragraph of the same
  text. Such a part now goes to the engine in logical order (#600).
- A script reads an input's `value` sanitized by its type, as HTML says and Chromium does. A
  number that is not valid reads as empty, a range is clamped and rounded to its step, a colour
  reads as `#rrggbb`, and a date, month, week or time that is not valid reads as empty. A
  textarea's `value` has LF line breaks. `:placeholder-shown`, `:in-range`, `:out-of-range` and
  `:invalid` read the same value (#605).

- A colour written with `hsl()`, `hwb()`, `lab()`, `lch()`, `oklab()`, `oklch()` or `color()`
  paints, where KitePDF dropped the declaration. `rgb()` takes the space syntax with `none` and a
  slash before the alpha, and every CSS named colour is read. A colour outside sRGB is clipped to
  it, as in Chromium. A legacy `rgb()` that mixes numbers and percentages, or commas and spaces, is
  no colour, as CSS Color 4 says (#606).

- A chapter's scripts see the fragment the reader reached it at. `location.hash` gives it, the
  element it names matches `:target` once the chapter is parsed, and a move to another fragment
  fires `popstate` and then `hashchange`, as in Chromium. A script that sets `location.hash` or
  clicks a link of its own chapter moves at once. `HashChangeEvent` and `PopStateEvent` are new
  (#550).

- In an XHTML chapter, a script's `innerHTML`, `outerHTML` and `insertAdjacentHTML` read and write
  XML, as in a browser, where they used HTML. Each element they write declares its namespace, an
  empty element closes itself, and markup that is not well-formed throws a `SyntaxError`. An
  attribute value written as XML keeps its tabs and line breaks as character references, in
  `XMLSerializer` too (#548).
- A form control's checkedness, an option's selectedness, a control's value and its indeterminate
  flag are state that the host keeps beside the element, as HTML has them, where setting `checked`
  wrote the `checked` attribute. `outerHTML` and `[checked]` no longer see an attribute the author
  never wrote, an attribute set after a script changed the state no longer changes it, and `reset()`
  restores the state from the attributes. A script's selectors and the page's style sheets match
  `:checked`, `:placeholder-shown`, `:valid`, `:invalid`, `:in-range` and `:indeterminate` on that
  state, and an indeterminate checkbox does not match `:checked`, as in Blink (#552).
- An HTML chapter that leaves out `<html>`, `<head>` or `<body>` gets them, as HTML's parser makes
  them, so its scripts find `document.body` and `document.head`. The elements that belong in a head
  go into it, the rest into the body, a second `<html>` or `<body>` adds its attributes to the first,
  and comments and white space go where HTML puts them. `DOMParser` makes HTML documents the same
  way, and `innerHTML` on an `html` element makes a head and a body. The layout reads the same tree, so such a chapter now has the body's default margin, as in a
  browser (#547).
- A script's insert or replace that would make no valid document throws a `HierarchyRequestError`,
  as the DOM's pre-insert checks say, where it put text, a second root element or a misplaced
  document type into the document. `replaceChild` checks the replace rules, and `append`, `prepend`,
  `before`, `after`, `replaceWith` and `replaceChildren` check all their nodes at once, so a failed
  call inserts none of them (#604).
- A chapter's scripts see its document type, its processing instructions and its CDATA sections as
  `DocumentType`, `ProcessingInstruction` and `CDATASection` nodes, where the document began at its
  root element. White space beside the root element is no longer a text node of the document.
  `compatMode` is `BackCompat` for an HTML chapter or parsed HTML that a document type puts in quirks
  mode, as HTML's parser decides it. `createProcessingInstruction`, `createCDATASection` and
  `DOMImplementation.createDocumentType` make those nodes, `createDocument` takes a document type,
  and `createHTMLDocument` gives its document one (#546).
- In an XHTML chapter, `document.write`, `writeln`, `open` and `close` throw an `InvalidStateError`,
  as a browser's do in an XML document, where they wrote into the chapter. An HTML chapter is written
  as before (#603).
- A Type 1 font whose `/lenIV` is -1 runs its charstrings and subroutines as they are, as
  FreeType does, where every glyph threw while it was decrypted and drew nothing (#597).
- An embedded Type 1 font decrypts each of its subroutines before a glyph calls it, as it already
  decrypted each charstring. A subroutine ran still encrypted, so every letter that called one drew
  noise, and every flex drew its points as moves: 69 of the 132 glyphs of a pdfTeX CMR10 came out
  wrong, among them i, n, l, T, A and R, and all 132 now match fontTools (#596).
- `CoreGraphicsCanvas` draws host-font text through CoreText, so a letter that Times, Helvetica
  or Courier lacks draws from a face of the cascade that has it, and a run whose letters join or
  reorder, such as an Arabic word, draws as one shaped line fitted to the width the document
  gives it, as on the other canvases since #588. The canvas filled one character at a time from
  one of those faces, so every Arabic, Hebrew, Thai or Devanagari letter of a document without
  embedded fonts drew nothing on macOS and iOS, as did a letter outside the BMP and an emoji.
  Latin text keeps a pen per glyph, and a glyph with no outline, such as a colour emoji, draws
  through CoreText (#589).
- In a browser, a page draws again once Compose's text has downloaded the font for characters it
  drew as boxes. No face in a browser covers every script, so Compose fetches a fallback face for
  the code points that none of its faces has, and draws its own text again when the face lands,
  while `KiteDocView` kept the page it had drawn, with a box for each such character, in a PDF
  with a non-embedded Japanese font for example. Each page now keeps one paragraph for each face
  its host-font text asked for, made of the characters it drew in that face, which Compose marks
  stale with the rest when a font lands. The page then draws again in both render modes, as do its
  thumbnail, its form fields and a cached raster that comes back on screen. Other platforms draw
  host-font text without Compose's text and do not change (#595).
- The mouse wheel scrolls a `KiteDocLayout.Continuous` layout while text is selected. A selection
  holds the strip's scrolling for as long as it is on screen, so a finger cannot slide the page
  from under the words, and that hold stopped the wheel too: after a mouse selection on the
  desktop or the web the page did not move until a click cleared it. The hold now applies while
  the last pointer over the view was a finger or a pen, so a mouse keeps the wheel and the
  selection scrolls with its page, while a finger swipe still leaves the page in place (#594).
- In a browser, text in a generic font family draws in the bundled face of its family. Skia
  knows one face there, Roboto, so the text of a serif book drew in Roboto: `KiteDocView`
  stretched or squeezed each word to its Times width, so the words of one line looked like
  different sizes, and `EpubPageRasterizer` set each Roboto letter at its Times advance, with
  uneven gaps and no bold or italic. Where the host has no face of the family, `ComposeCanvas`
  and `SkiaCanvas` now draw the URW face of the standard 14 fonts that the layout measured the
  text with, so each glyph fills its own advance. A run with a character those faces lack keeps
  the host's text, and a host with a face of the family keeps drawing it. `standardFaceGlyphs`
  gives a run those outlines (#593).
- The mouse wheel turns the page of a horizontal `KiteDocLayout.Paged` or `Spread` layout on the
  desktop and the web. Its pager took only the sideways wheel, so a reader with a mouse could
  not page a book without the keyboard. Down goes forward and up goes back, one page or spread
  for each wheel gesture, so a trackpad flick and its slowing tail turn one page. A zoomed page
  keeps the wheel, and `userScrollEnabled` turns it off (#592).
- `AndroidNativeCanvas` keeps an off-centre radial shading where it is below API 31. Android
  draws a gradient between two circles from API 31 on, and below it the canvas drew one around
  the end circle only, so the highlight of a sphere moved to the end circle's centre on API 29
  and 30, in `AndroidPdfBitmapRenderer` as well. It now draws the pixels of the shading there,
  as the Compose canvas has since #413, and both share the solver in `twoCircleParameter`
  (#591).
- `SkiaCanvas` looks up the host face of a font once and keeps it. It asked the host again on
  every run of host text, walking the candidate families with a fontconfig match for each one the
  host lacks, 1.7 ms a run for serif and 5 ms for sans-serif on Linux, so eight pages of an Arabic
  book took 2.3 s, nine tenths of it in that lookup. They now take 80 ms (#590).

- `AwtCanvas`, `AndroidNativeCanvas`, `SkiaCanvas` and `Canvas2dCanvas` draw a run of host-font
  text whose letters join or reorder, such as an Arabic word or a Devanagari syllable, as one
  string that the platform's text engine shapes, fitted to the width the document gives it. Each
  letter used to draw alone at its own pen, so Arabic came out in isolated forms that never
  joined, while the Compose canvas already joined them. Latin, Greek, Cyrillic and CJK text keeps
  a pen per glyph. The cutting into parts is shared in `hostTextParts` of `kitepdf-core`, and the
  Android canvas is now tested under Robolectric's native graphics (#588).

- `SkiaCanvas` draws a character that its host face lacks in a host face that has it, whether
  or not the font names a language. Only a CJK language took a fallback face, so every letter of
  a script the Latin faces lack, such as the Arabic of a book without embedded fonts, drew
  nothing, and such a page showed only its punctuation and digits (#587).

- `PdfEditor.saveIncremental` of a PDF that opened through repair appends a cross-reference
  table that lists every object and names no `/Prev`, and writes out again the objects that
  lived inside an object stream, so the saved file opens without a repair in KitePDF and in
  mutool. It used to throw `PdfFormatException` when the file had no `startxref`, which also
  failed `PdfSigner`, and otherwise named the broken table as `/Prev` (#586).

- A kid of a PDF page tree that is no dictionary, such as a missing object or `null`, is a blank
  page, and a kid dictionary without `/Type /Page` is read as a page, as MuPDF reads both, where
  both used to drop out of the page list and move every later page to the index, label and
  destinations of the page before it. The blank page keeps the kid's reference, so
  `PdfEditor.removePage` can remove it (#585).

- `PdfDocument.open` throws `PdfFormatException`, and `openOrNull` returns null, for a damaged
  file in which no catalog leads to a page tree, as mutool refuses it, where it used to return a
  document whose `pageCount`, `pages`, `outline` and every other catalog read threw on first use.
  When the trailer names a broken catalog and the file holds another, repair takes the other, as
  pdf.js does (#584).

- A PDF page whose `/Contents` is null, as a file that `saveRewritten` wrote from a damaged source
  can have it, or of a type that no content can have is an empty page that still draws its
  annotations, where `renderTo`, `textContent()` and `contentBytes` used to throw (#583).

- A glyph of an embedded TrueType font that reads past the end of the font draws nothing, as a
  broken CFF or Type 1 glyph already did, where it used to fail the whole page with
  `PdfFormatException`, so one damaged glyph cost every other glyph, image and shape on the page
  (#582).

- A PDF reference to an object that the file does not hold reads as null, as ISO 32000-1 has it,
  where it used to fail the whole call. A page whose image, graphics state, font descriptor,
  embedded font program, ToUnicode map or marked content properties named a missing object drew
  nothing and had no text, and a missing outline, attachment tree or form field list failed
  `outline`, `attachments` and `acroForm`. Now the missing entry is skipped and the rest of the
  page draws (#581).

- An `<svg>` written in a sentence of an EPUB chapter flows on its line as an `<img>` does, where
  it used to break the paragraph, ending the line before it and starting a new one after it. An
  `<svg>` is inline unless a style sheet makes it a block or floats it, and one alone in its block
  keeps a box of its own, as a cover page has it (#580).

- The text that an SVG draws is part of its page text, in an SVG spine item of an EPUB, in an
  `<svg>` written in a chapter and in a standalone SVG document, so search and selection find it.
  The runs of glyphs along one baseline make a line, with a space where a `<tspan>` or a new run
  leaves a gap, and lines set one under another make a block, as a paragraph written as one
  `<text>` per line is. An element with an id inside such an SVG, a `<tspan>` included, is a
  fragment that a link or a media overlay reaches, at one rectangle per line of its text, or at
  the box of what it draws when it draws no text, where `locateFragment` used to answer null and
  a link went to the top of the chapter. An SVG file that an `<img>` shows stays a picture, and
  its text stays out of the page text, as in a browser. `SvgImage.textContent` gives the text of
  an SVG drawn under any matrix (#523).

- EPUB style sheets get the CSS-wide keywords `inherit`, `initial` and `unset` on every property,
  where only a few properties used to read them. `color: initial` no longer keeps the parent's
  colour, `margin-left: inherit` no longer leaves the margin at 0, `border-top: inherit` no
  longer draws a medium border of its own, and `unset` on an inherited property such as
  `word-break` or `text-transform` inherits instead of resetting it. On a shorthand such as
  `margin`, `border` or `font` the keyword sets each of its longhands (#579).

- EPUB chapters draw `text-decoration: overline`, which the style resolver used to drop, so an
  overline drew nothing and `underline overline` drew the underline alone. The line runs just over
  the text, with the colour and size of the element that draws it, and in vertical text it runs
  right of the column, or left of it when `text-underline-position: right` takes the right for the
  underline (#578).

- Text copied or searched from a `<pre>` block, or any element with `white-space: pre` or
  `pre-wrap`, keeps the spaces its layout kept: a run of spaces used to read as one and the
  indentation at the start of a line was lost, so a code listing came out flat. The line text now
  holds each space and the ones a line starts with, each spanning its share of their room, and
  this holds for the no-break spaces that indent a paragraph as well (#576).

- A no-break space no longer collapses into the space beside it or lets a line break there, so
  the run of them that a book converted from a word processor puts before a paragraph indents
  it, and a number stays on the line of its unit in `10&nbsp;km`, as it does with a narrow
  no-break or a figure space. An ideographic space indents a Japanese paragraph again, keeps its
  full width between two sentences, hangs at the end of a line and stretches like a character
  when the line is justified, and the em, en, thin and hair spaces keep their widths too. A
  zero-width space is now a place where a line may break, where it used to glue the words on its
  two sides into one (#577).

- A CSS declaration whose value KitePDF cannot read no longer undoes the one before it: the
  style resolver used to keep only the last value of each property and drop it when it could not
  read it, so `width: 90%; width: calc(...)` or a vendor keyword after a standard one lost both,
  and the property fell back to its inherited or initial value. Every declaration now applies in
  cascade order, which also puts a shorthand such as `background` or `columns` and its longhands
  in that order, where they met in the order of a hash map (#575).

- A word wider than the line, such as a long link, breaks after as many characters as fit and
  goes on at the next line, where it used to run off the right edge of the page and lose its end.
  The reader's style sheet sets `overflow-wrap: break-word` on the root, so a book need not ask
  for it, and a book's own `overflow-wrap`, `word-wrap` or `word-break: break-word` now takes
  effect, `normal` included. The break never parts a letter from its combining marks, and copied text joins the
  pieces with nothing between them (#574).

- A justified paragraph no longer stretches a line that a `<br>` ends, so a short line of verse
  or of an address keeps its natural width at the start of its line, as CSS asks of the last
  line and of every line before a forced break (#573).

- A right-to-left paragraph indents its first line from the right edge, where its lines start,
  and a justified one sets its last line, and any other line it does not stretch, at the right
  edge too. The indent used to sit at the left end of the first line, so an Arabic or Hebrew
  paragraph showed none, and the last line of justified text sat against the left edge (#572).

- A DOCTYPE with an internal subset ends after it, so a chapter whose DOCTYPE declares entities
  no longer shows `]>` at the top of its first page, and an SVG that Illustrator exported reads
  as it should. `KiteXml` reads the subset's general entities and expands a reference to one in
  text and attribute values, the first declaration of a name binding, as a non-validating XML
  reader must. An external entity is never fetched and stands for nothing. Expansion stops 16
  entities deep and after a million characters for the document, so nested entities cannot
  exhaust memory (#571).

- HTML's named character references decode in an EPUB chapter, an SVG and every other file
  `KiteXml` reads: all 2125 names that end in a semicolon, which hold every entity the XHTML 1.0
  and 1.1 DTDs declare. Before, only `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;` and `&nbsp;`
  decoded, so a book that writes `&mdash;`, `&rsquo;` or `&eacute;`, as its XHTML DOCTYPE lets it,
  showed the names on the page. A name the table does not have stays as text (#570).

- `KiteReadAloud` follows the reader to another place in the book. When the reader moves the
  viewer by a link, the table of contents, a page turn or a scroll, the reading goes on from the
  first clip at or after that place once the view settles, also while it is paused, as Media
  Overlays 3.3 asks for navigation during playback. Before, it read the rest of the chapter the
  reader had left, and turned the view back to it. The page turns the reading makes to follow its
  text do not count, and it turns no page while the reader moves the view (#524).

- A `<video>` without `controls` in `KiteMediaOverlay` can be paused: a tap pauses and plays it
  again, and shows the transport bar while it stays paused and for three seconds once it plays.
  Before, nothing on such a video took a tap once it played, so a looping one played until its
  page left the screen, and an `autoplay` one stayed muted for good. Its first tap now turns on
  the sound that autoplay muted, as the first touch of a video with controls does (#480).

- In `KiteDocLayout.Spread`, a page that asks for one side of a spread and has no partner sits in
  that half with the other half empty, such as an EPUB's `page-spread-right` first page or a
  `page-spread-left` last page, or the first page of a PDF whose `/PageLayout` puts it on the
  right. Only a page that asks for no side, or to be centred, shows in the middle (#504).

- A media overlay that several chapters of an EPUB share gives each chapter only the clips whose
  text is in it, so read-aloud reads each clip once, in the order of the book, instead of reading
  the whole overlay again for every chapter. Three W3C tests that need it now pass (#522).

- An EPUB image that is a block or floats, and has no `width` or `height`, takes its intrinsic
  size as an inline one does, 0.75 pt a pixel, or a user unit of an SVG that gives its own size,
  instead of the width of its column. A small ornament under `img { display: block }` no longer
  blows up to the column, and a floated one leaves the text room beside it. A larger picture still
  scales down to its column, and an SVG with only a viewBox still fills it (#569).

- Every layer of an EPUB `background-image` paints, the first on top, each with its own
  `background-size`, `background-position` and `background-repeat` from the comma lists of the
  longhands or the `background` shorthand. A `linear-gradient` tiles by its `background-size`,
  a stop may be `currentColor`, and stops that differ in alpha paint, blending in premultiplied
  space so a fade to `transparent` keeps its colour. Only the first layer painted, a gradient
  filled the whole box, and one with `currentColor` or a stop of another alpha painted nothing,
  so the grid idiom of two layered gradients left a page blank. A repeated layer now tiles only
  the part of its box on the page, so a long box past the tile budget still paints. Seven W3C
  EPUB tests of the viewport's grid now pass (#503).
- `EpubDocument.fetchRemoteResources` returns only once `remoteArrivals` counts what it waited
  for. A call that asked for a URL just as its bytes landed could find them and return while the
  count still lacked them (#567).
- An SVG that gives one of `width` and `height` and a `viewBox` takes the other side from the
  viewBox's aspect ratio. It took the viewBox's own extent, so `width="30"` with
  `viewBox="0 0 20 40"` made a 30 by 40 box and drew a third too small, on a line, in a block
  and through `img` alike (#566).
- An SVG placed straight in an EPUB block, such as a `p` or a `div`, takes the size its `width`
  and `height` attributes give. It filled the text column, so a 20 pixel icon drew 304 points
  square and two of them took two pages. A size in percent still fills the column (#565).
- An SVG written inline in an EPUB chapter takes the chapter's style sheets, as in a browser: a
  class rule of the document fills its paths, a selector reaches into it from its host, and a rule
  outranks a presentation attribute. The SVG's own `style` element, a `style` attribute and an
  `!important` keep the precedence of the cascade, and an SVG an `img` points at keeps only its
  own style. Paths styled by a document class painted plain black. `SvgImage.fromElement` takes
  the declarations a host document gives each element for it (#509).
- The EPUB `q` element draws quotation marks, chosen by the language its chapter declares, with
  the marks of each language from CLDR 48.2.0: “ ” for English, « » for French, „ “ for German,
  「 」 for Japanese, and the inner pair for a quotation inside one. `content` takes
  `open-quote`, `close-quote`, `no-open-quote` and `no-close-quote`, and the `quotes` property
  sets the marks. The package's `dc:language` picks none, as EPUB Reading Systems 3.3, 3.7
  requires (#511).
- `TocEntry.spineIndex` counts the chapters an EPUB keeps. It counted the spine's itemrefs, so an
  entry after a spine file missing from the zip pointed one chapter too far, and the last one past
  the end of the book (#563).
- An EPUB spine item whose fallback chain holds no XHTML or SVG document, and whose bytes are not
  markup, leaves the spine. Its bytes were laid out as text, so a disk image in the spine became
  23 pages of noise, and a book of nothing else now refuses to open with `EpubFormatException`.
  A document under the wrong media type still renders (#518).
- Each EPUB chapter lays out in its own writing mode. The book took the mode of its first
  chapter, so a vertical chapter after a horizontal one laid out horizontally, and a horizontal
  chapter after a vertical one laid out in columns (#507).
- A right-to-left page progression no longer turns a book's text right to left. The spine's
  `page-progression-direction` set the base direction of every chapter, so the English pages of
  a right-to-left book came out right-aligned with their periods at the front. A chapter that
  declares no direction now reads in its language's, from its own `lang` or `xml:lang` or the
  book's. And a book in a right-to-left language, Arabic, Hebrew or Persian among them, whose
  spine sets no direction now progresses right to left (#512).
- Hebrew and Arabic in a host font read the right way round in the Compose viewer. The layout
  hands the canvas each run in the order it draws, and the text engine ran the bidi algorithm on
  it again, so every right-to-left word showed its letters reversed, and Arabic letters took
  their joining forms from the wrong neighbours. A right-to-left part now goes to the engine in
  logical order under a right-to-left override (#486).
- An EPUB chapter decodes each picture once, however many elements name it. Each `<img>` held its
  own decoded copy, so a picture used all through a chapter cost its pixels once per use, and a
  page that named an 800 by 1158 picture 1,651 times ran a 3 GB heap out of memory (#560).

- An SVG `rect` with a negative width or height paints nothing, as SVG 2 says, instead of
  throwing out of the whole render, and one with no width or height paints no empty fill (#561).

- The budget of `EpubScriptPolicy` no longer counts the DOM the library sets up in a chapter's
  engine. That setup takes a few hundred milliseconds, and seconds on a slow device or the first
  time on the web, and when it outlived the budget the engine stopped it, so no script of the
  chapter ran, not even one that would have finished at once. The budget of opening a chapter
  now starts once the DOM is ready, and `EpubScriptSession` says that it evaluates its DOM
  first in each engine (#554).

- On JavaScript and WebAssembly two scripted documents can be open at once. The page's one
  thread holds one open KiteJS engine, and nothing made the runners share it, so while one runner
  had an engine open the next one's would not open: a second book's scripts never ran, and a
  second `PdfScriptRunner` threw out of each call. The runners now take turns there: one that
  needs an engine closes the one another runner has open, unless a script of that runner is
  running. A book's runner unloads its chapters, which start over from their markup when next
  used, and a PDF runner opens its engine again at its next script and runs the document's own
  scripts again first. A PDF engine that will not open is now a recorded failure, as a chapter's
  is, and not an exception (#553).

- An attribute in a book's scripts has the namespace, prefix and local name the DOM Standard
  gives it, where it had only the lower-cased local name the layout keys it by, so the
  `getAttribute('epub:type')` of a footnote script read nothing while `getAttribute('type')`
  answered for it, `svg.getAttribute('viewbox')` answered in an XHTML chapter, the `NS` methods
  ignored the namespace, an `Attr` had no prefix or namespace, and `[title]` matched `tItLe` in
  an XHTML chapter. An XHTML chapter names its attributes as XML does, a declared prefix to its
  namespace, and an HTML chapter as HTML's parser does, lower-cased, with the case table of SVG
  and the foreign attributes of `xlink:`, `xml:` and `xmlns`. `setAttribute`, `setAttributeNS`,
  `toggleAttribute`, the `Attr` of `getAttributeNode` and `createAttributeNS`, and
  `NamedNodeMap`, with its named properties, follow the standard, and `outerHTML` writes each
  name as the serializer of HTML does. The layout reads the attributes as before. The attribute
  tests of web-platform-tests' `dom/nodes` run in a chapter, and pass but for five that wait on
  fixes KiteJS made after 0.2.0 (#545).

- A checked radio button no longer matches `:indeterminate` when the other buttons of its group
  are unchecked, as the group it looked in for a checked button left the radio itself out (#551).

- A book's style sheets and its scripts read the selectors of Selectors 4, where `:not()` took
  one simple selector, a pseudo-class it did not know never matched instead of making the
  selector invalid, a script's query that a browser rejects answered, and `:is()` in a style
  sheet broke at its comma into two selectors. A selector is read from the tokens of CSS Syntax
  3, with escapes, and has `:is()`, `:where()` and `:-webkit-any()`, `:not()` and `:has()` of
  any selector list, `:nth-child()` and `:nth-last-child()` with `of S`, `:nth-of-type()`,
  `:nth-last-of-type()` and `:only-of-type`, the `i` and `s` flags of an attribute selector and a
  namespace prefix that `@namespace` declares, `:lang()` by the extended filtering of RFC 4647,
  `:dir()`, `:any-link`, `:defined`, `:open`, `:scope`, and the states of a form control, which
  it reads from the control's attributes. Specificity is that of Selectors 4, and `:where()`
  counts nothing. One invalid selector drops its whole rule, and a valid pseudo-class or
  pseudo-element that never holds in a paginated book, as `:hover` or `::selection`, keeps the
  rule and matches nothing. A chapter's query takes the case of names from the kind of its
  document, throws a `SyntaxError` where Chromium does, and has `:scope` as the DOM Standard
  gives it, `closest()` included. A query called without its argument throws a `TypeError`, and
  its errors name the method, as Chromium's do. A selector nested beyond 32 levels is dropped
  with a warning, so a hostile one cannot exhaust a thread's stack. The selector tests of
  web-platform-tests' `dom/nodes` run in a chapter, and pass but for the attribute case of #545
  and the `:target` of #550 (#549).

- The objects of a book's scripts have the interfaces a browser gives them, with their class
  strings, where `String(document.body)` was `[object Object]` and only sixteen element
  interfaces existed. Each element of HTML has the interface HTML names for its tag, and
  `HTMLUnknownElement` for a name HTML does not have, with every attribute HTML reflects read and
  written by the type, the default and the keywords HTML gives it; an element of SVG has its SVG
  interface and one of MathML is a `MathMLElement`. The document, the window, `location`,
  `navigator`, the storages, an element's `style`, its attributes and its rectangles have their
  interfaces too, each a property of the window that a `for`-`in` does not list. An element's
  `style` is a `CSSStyleDeclaration` with `cssText`, `setProperty` and its priority,
  `removeProperty` and custom properties, and the document has the members of HTML's `Document`,
  such as `URL`, `characterSet`, `compatMode`, `forms` and `createEvent`, with `new Document()`
  and `document.implementation`. The 59,683 reflection tests of web-platform-tests pass in a
  chapter, and so does its test of the element interfaces, but for the half of it that parses
  each element with `DOMParser` (#543) (#538).

- A book's scripts see the comments of their chapter, where the tree dropped them and
  `createComment` made a text node, so a script that walked `childNodes` to a comment, as a
  template engine or a lazy loader marks its places, found none. A comment is a `Comment`, a
  `CharacterData` of `nodeType` 8 and `nodeName` `#comment`, which `innerHTML`, `outerHTML`,
  `insertAdjacentHTML` and `document.write` parse and serialize, and an element's `textContent`
  leaves out. The page shows none of them: the tree of a chapter's scripts takes its comments
  from a second parse of the chapter that keeps them, and the tree it hands the layout drops them
  again (#544).

- `childNodes`, `children`, the collections of `getElementsByTagName`, `getElementsByClassName`
  and `getElementsByName`, and those of a form, a select, a table, a row, a map and the document
  are live in a book's scripts: each is the same object on each read and follows the tree as it
  changes, where each read made an array of the nodes there at that moment, so a loop that
  emptied a parent through the `childNodes` it held never ended, and a script that called `.map`
  on one worked here and in no browser. A `NodeList` and an `HTMLCollection` are legacy platform
  objects of Web IDL, with indexed properties and, for a collection, named ones, and
  `querySelectorAll` answers a static `NodeList` (#542).

- `tagName` and `nodeName` keep their case in an XHTML chapter, which a book's scripts now see as
  an `XMLDocument`, as a browser opens one, where every chapter had upper-case names. A chapter
  served as `text/html` is an `HTMLDocument`, whose HTML elements have upper-case names and whose
  `createElement` lowercases its argument. An element's namespace and local name are the ones a
  parser gives it, so an SVG element keeps the case of its name, such as `linearGradient`, and
  `createElementNS` keeps the prefix and name it is given (#541).

- An event handler of a book's scripts runs at its place among the listeners of its target, where
  it ran before every listener whatever their order. HTML gives a handler the place it took when
  it was first set: setting it again keeps the place, null or a removed attribute takes it out,
  and setting it once more puts it at the end, so a listener that stops the event now keeps a
  later handler from running. A handler of the markup takes its place when the parser would make
  its element, so a script adds its listeners ahead of the handlers of the elements after it. The
  handlers of an element are those of HTML's GlobalEventHandlers, the window's add
  WindowEventHandlers, the body's window handlers such as `onload` are the window's through
  `document.body` too, and the document has `onreadystatechange`. The code of an attribute sees
  the form of its control, as a browser compiles it, and a window handler no longer sees the
  document's names. A handler set to an object keeps it, and any other value is null (#539).

- The DOM of a book's scripts no longer runs what a script patched. It is JavaScript in the
  chapter's realm, and since a chapter's built-ins are writable (#537), a book that patched
  `Array.prototype.push`, `Map.prototype.get`, `Object.defineProperty` or the `next` of the
  generator prototype, or hung a getter on `Object.prototype`, changed what `appendChild`, an
  event's dispatch or `URLSearchParams` did, and could see the host functions on `__kite`. The
  DOM now takes every built-in it calls before the book's first script runs and calls them
  uncurried, as Node does with its primordials: its descriptors, dictionaries and tables have no
  prototype, its iterations step generators by the `next` it took, its checks of an interface
  read the prototype chain and not `Symbol.hasInstance`, its own algorithms call one another and
  not the public methods a script can replace, the host table is a frozen copy that no script
  can reach, and the entry points the reader calls cannot be replaced. A test runs one chapter
  with and without a script that replaces every method of every built-in the DOM could reach and
  checks the two logs are the same, and another reads the DOM's source and fails on a call of a
  method by name, as Node's `prefer-primordials` rule does (#540).

- A book's scripts can add to and wrap the built-in objects, as a browser's can, so a polyfill
  such as core-js, which Babel's output loads, no longer stops at its first line. A chapter's
  engine ran with the built-ins sealed, as a PDF's does, where the seal guarded nothing, since
  each chapter has an engine of its own and every check that matters is on the Kotlin side.
  `KiteJsScriptEngine` takes `sealBuiltins`, on by default, and `EpubScriptRunner` turns it off
  (#537).

- In a book's scripts, the promise jobs of a callback the reader calls run before its next
  callback, as HTML runs a microtask checkpoint after each one. Before, a timer's jobs waited for
  every timer due in the same round, and a listener's jobs for every listener of the tap or of
  the load events. Each such callback now runs in a call of its own into the engine. A script's
  own `dispatchEvent` still runs its listeners at once. An animation frame that an earlier
  callback of its frame cancels no longer runs (#535).

- `DOMException` in a book's scripts is the one of Web IDL: its `name`, `message` and `code` are
  getters of its prototype that refuse an object that is not one, `code` is the legacy code of
  the name, the 25 legacy constants sit on the constructor and the prototype, it must be called
  with `new`, and an instance is an `Error` underneath, with what the engine gives one. A script
  that tells errors apart by `e.code === DOMException.NOT_FOUND_ERR` now does. Its derived
  `QuotaExceededError`, with `quota` and `requested`, is there too, and an interface object is a
  property of `window` that a `for`-`in` does not list, as in a browser. The `DOMException`
  tests of web-platform-tests run in a chapter (#530).

- A tag whose quoted attribute value holds a `>`, such as `onclick="if (n > 0) next()"`, no
  longer ends at it, which spilled the rest of the tag into the text and broke the handler, and
  the code of a `<script>` or `<style>` keeps a `<` that opens no tag, as an HTML tokenizer reads
  them. `KiteXml` serves the EPUB, SVG, XPS and package parsers alike (#529).

- A resource that an EPUB document carries in a `data:` URL loads: an image source, a CSS
  background, a font of an `@font-face`, a stylesheet or an `@import`, a script and an SVG image
  draw from the bytes the URL holds, as EPUB 3.3 allows. `EpubDocument.resource` and
  `resourceType` answer for one. A script that sets `location` to a `data:` URL is refused with a
  failure, since EPUB Reading Systems 3.3 never opens one as a page (#514).

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

- An image that the shared decoders refuse draws as a placeholder on every canvas. This includes a
  JPEG whose data is damaged. Before, Skia, Android, Java's ImageIO, Apple's Image I/O and the
  browser each decoded what they could, so an arithmetic-coded JPEG showed on one platform and not
  on another. A placeholder now marks a decoder gap of KitePDF itself. `ImageDecoder.decode` stays
  for apps, but KitePDF no longer calls it. On AWT, the placeholder was a solid mid-grey box one
  image height too low. It is now the light grey box with a thin outline that the other canvases
  draw (#184).
- `KiteReadAloud` now gives the element it reads the book's own active classes, so the text being
  read shows the style the book chose, and the rest of the chapter the style it sets while
  playing. Its own highlight now marks the text only where the book's rules do not style the
  active class. Pass `bookStyles = false` to keep the old behavior. The new parameters come before
  `onClip`, so a call that passes `onClip` by position must name it. One more W3C EPUB test
  passes (#525).
- A chapter without its own `dir`, CSS `direction` or `lang` now reads left to right. Before, it
  took its direction from the package's `dc:language`, so a chapter of an Arabic book read right to
  left. EPUB Reading Systems 3.3, 3.7 asks a reading system not to take a document's direction from
  the package. Hyphenation still falls back to `dc:language`, since it runs only when the book or
  the reader asks for it (#564).
- A file in the book's zip that the manifest does not list no longer loads: an image, a style
  sheet, a font, a frame or a media file that a chapter names there draws nothing, and
  `EpubDocument.resource` answers null for it. EPUB Reading Systems 3.3 asks a reading system not
  to use such a file. Each refusal goes to `KiteWarnings`. No book of the test corpus names such a
  file. One more W3C EPUB test passes (#516).

- Breaking: `EpubLayout` has a third value, `ROLL`. A `when` over it that lists every value needs
  a branch for it (#506).
- Breaking: every event method of `PdfScriptHandler` and `EpubScriptHandler` is a `suspend`
  function, such as `runAction`, `commit`, `chapterOpened`, `tap` and `pumpTimers`. A handler
  that overrides one adds `suspend`, and a caller outside a coroutine wraps the call in one.
  The synchronous helpers of `PdfScriptRunner`, such as `run`, `setFieldValue` and
  `formattedValue`, do not change (#489).

- On JavaScript and Wasm, a long document script no longer freezes the page. The script pauses
  about every 16 ms, so the page draws and runs its timers. A call that comes while a script is
  paused waits for it. The engine pauses only on a runtime with WebAssembly stack switching
  (Chrome 137 and newer, Node 25 and newer); on any other runtime the script runs in one go, as
  before. `KiteScriptEngine` has a new `evaluatePausing` for this. The synchronous helpers never
  pause. This needs `kitejs-api` and `kitejs-quickjs` 0.5.0 (#489).

- Binary data now crosses between an EPUB script and the library as bytes. Before, it crossed as
  a string with one character for each byte. `getImageData`, `putImageData`, `toBlob`,
  `TextEncoder`, `TextDecoder`, `FileReader` and blob URLs take about half the time they took.
  On a canvas of 500 by 500 pixels, `getImageData` took 56 ms where it took 123 ms, measured once
  on the JVM. This needs `kitejs-api` and `kitejs-quickjs` 0.6.0.
- Breaking: a function that `KiteScriptEngine.defineFunction` binds gets an `ArrayBuffer`, a typed
  array or a `DataView` as a `ByteArray` of the bytes it views. A `ByteArray` that the function
  returns reaches the script as a `Uint8Array`. An engine that implements `KiteScriptEngine` must
  convert binary data the same way, or EPUB scripts lose it.

- A scripted chapter opens two to six times faster after the first one. Each engine loads the
  bytecode of a large script that another engine of the process compiled, so the DOM of a
  chapter is parsed once per process. A warm chapter set up in 25 to 28 ms on the JVM, 34 to 38
  ms on Node, 31 to 33 ms on Wasm and 84 to 86 ms in a macOS debug build, and now sets up in 10
  to 13, 19 to 26, 14 to 16 and 13 to 15 ms. The first engine of a desktop JVM process also
  opens about 300 ms sooner. This needs `kitejs-api` and `kitejs-quickjs` 0.4.0 (#555).

- `kitepdf-javascript` runs document scripts on KiteJS's QuickJS engine, through `kitejs-api`
  and `kitejs-quickjs` 0.3.0, where it used KiteJS's Rhino engine. Scripts that Rhino could not
  run now run: `const` in a `for` head, async functions, `SharedArrayBuffer`, `Float16Array`,
  and strict code called from sloppy code. The script thread has 16 MiB of stack on the JVM,
  Android and Kotlin/Native, and a script may use 8 MiB of it. On JavaScript and Wasm the engine
  is a WebAssembly module that compiles before the first script runs (#598).

- `PdfScriptHandler` and `EpubScriptHandler` have a new `suspend fun prepare()`, which a
  viewer calls once before any other call. `KiteDocView` sends nothing to a script before it
  returns. A handler that does not override it keeps working (#598).

- `kitepdf-javascript` depends on `kitepdf-epub`, whose scripts it now runs (#41).

- `KiteXml.tokenize` and `KiteXml.parse` in `kitepdf-core` take `keepComments`, false by
  default, and give a comment as the new `KiteXmlToken.Comment` and `KiteXmlNode.Comment` when
  it is true. Without it they read a document as before, but a `when` over either sealed class
  needs a branch for the new kind (#544).

- `KiteXml.tokenize` in `kitepdf-core` takes `keepNames`, false by default, and keeps each
  attribute's name as the markup writes it, with its prefix and its case, when it is true, where
  it gives the lower-cased local name otherwise. Of two attributes with one name the first then
  wins, as in HTML's tokenizer (#545).

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

- A PDF image under a finer mask, as in a layered scan, keeps its own samples. A draw at a
  smaller size applies the mask block by block, so the full-size copy on the mask's grid no
  longer exists. A 300 dpi stencil over 150 dpi ink, drawn at half size, took 71 ms and 113 MB
  before and 29 ms and 17 MB now. Reading `pixelBytes` of such an image still gives the samples
  on the mask's grid, built on each read (#476).

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
