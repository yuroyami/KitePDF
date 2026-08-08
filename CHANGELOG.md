# Changelog

All notable changes to KitePDF are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
the 2026-08-06 concurrency audit. No public API changes; two behavioural
changes are called out below.

### Fixed

- The 2026-08-05 iOS text-cache crash is now fixed at the root instead of
  narrowed: skiko's text stack shares process-global state with the host UI
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
to Maven Local for the EducHaiti reader work; Central publication can follow
unchanged.

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
  `mkdocs.yml` aligned with the family template.
- Em dashes removed repo-wide per KITE.md, including 687 from Kotlin comments
  across 191 files, verified comment-only.
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

The multi-format, API-perfection release: the engine becomes a MuPDF-style
core + handlers architecture, gains a complete EPUB reader, closes the PDF
completeness gaps (shadings, Type3, JPX, JBIG2, CJK CMaps, soft masks), and
lands the breaking API cleanup that 0.2.0 exists for.

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
  `:kitepdf-core` (format-agnostic substrate: geometry, canvas, fonts, images,
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

### Added since the EPUB milestone (M2-M5)

- Viewer feature set: engine-level text search with per-page highlight quads,
  viewport hit testing, link taps (PDF link annotations and EPUB hrefs) with
  internal go-to-page handling, outline/TOC panels, text selection with
  long-press drag handles, page thumbnails, RTL reading progression, and
  two-page spreads.
- Format-neutral document seam: `KiteDocument` exposes metadata, outlines,
  structured text, and search for both handlers; `PdfPage.textContent()`
  adapts PDF structured text to it.
- Performance and concurrency (M3): platform zlib fast paths on JVM/Android
  with a dynamic-Huffman pure-Kotlin deflate elsewhere, one glyph-layout pass
  per text run, a per-document decoded-image cache, thread-safe
  `PdfDocument` (concurrent page rendering), lazy `pageCount` from `/Count`,
  off-main-thread rasterization, and a page-bitmap LRU in the viewer. Corpus
  mean render time: 9.7ms/page on the reference machine.
- PDF completeness (M4): text clipping modes 4-7; shading types 1, 4, 5 and
  6/7 (approximated); Type 3 fonts; luminosity soft masks; 47 predefined CJK
  CMaps; complete JBIG2 (MMR, Huffman symbol dictionaries and text regions,
  refinement, pattern/halftone regions); a from-scratch JPEG 2000 (JPX)
  decoder, byte-exact against OpenJPEG on lossless configurations; encrypted
  PDF creation and editing (AES-256/R6 write support in `PdfBuilder.encrypt`
  and `PdfEditor`); vertical writing (tategaki) for EPUB; and a digital
  signature scaffold (`PdfSigner`: prepare, ByteRange, embed; the CMS blob
  comes from the application).
- API perfection (M5): explicit API mode across all published modules;
  `KitePDF.VERSION` generated from the Gradle version; a `String` password
  overload with the documented UTF-8-then-Latin-1 rule; `KiteWarnings`, a
  process-global warning sink for the lenient salvage paths; CMYK color
  operators in the writer's content builder.
- Test hardening: a deterministic mutation fuzzer (2600 seeded mutants per
  run, wired into every build) and seeded writer round-trip property tests;
  CI now also tests iOS simulator, macOS, and JS(Node) targets on main.

### Fixed since the EPUB milestone

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
