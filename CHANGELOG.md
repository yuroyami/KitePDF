# Changelog

All notable changes to KitePDF are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.2.0]: https://github.com/yuroyami/KitePDF/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/yuroyami/KitePDF/releases/tag/v0.1.0
