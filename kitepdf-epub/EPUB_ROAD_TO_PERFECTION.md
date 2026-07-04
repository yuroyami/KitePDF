# EPUB — Road to Perfection

Roadmap + honest audit for `:kitepdf-epub`, from today's minimal proof-of-concept to a
reader that renders real EPUB 3 books at parity with a mainstream engine. Written to be
picked up in a fresh session with zero prior context.

- **Module**: `:kitepdf-epub` — a document handler on `:kitepdf-core` (the shared "fitz").
  It reuses the core font engine, codecs, compression and render `Canvas`; it adds only
  EPUB's own concern (unzip, follow the spine, lay out HTML/CSS). It never touches
  `:kitepdf-pdf`.
- **Architecture context**: see the memory note `taxonomy.md`. Core is format-agnostic;
  the `Canvas` seam is de-PDF'd (`drawGlyphs`, `fillPath`, `strokePath`, `drawImage`,
  `fillShading`, `pushClip`/`popClip`, transparency groups, soft masks).
- **Oracle**: `mutool draw book.epub -o page-%d.png` renders EPUB (MuPDF ships an HTML/CSS
  engine, `source/html/` + `source/reflow/`). So the existing differential-test approach
  (`:kitepdf-native-renderer` difftest vs `mutool`) **extends directly to EPUB**. MuPDF's
  HTML engine is also the architectural reference to read.

---

## 0. Status — Phases 1-3 done; Phase 4 started (2026-07-04)

### Phase 4 — typography (embedded fonts done; bidi/CJK/hyphenation next)

**The cross-backend advance-scale bug is fixed** (the §5 landmine / Phase-4 prerequisite):
`advanceWidth` is always 1/1000 em, so every `drawGlyphs` backend now advances by
`advanceWidth * fontSize/1000` (Skia/Android/CoreGraphics/Canvas2D were dividing by
`unitsPerEm`); outline scale stays `fontSize/unitsPerEm`. PDF difftest re-run green
(`:kitepdf-native-renderer:jvmTest`, incl. the embedded-font oracle tests).

**Embedded `@font-face` fonts render with real outlines:**
- `CssParser.parseAll` now collects `@font-face` rules (family, `src` url list, weight, style);
  `@font-face` inside `@media` is captured too.
- `Sha1.kt` (pure Kotlin) + `Deobfuscate.kt` — **IDPF** (SHA-1 of the whitespace-stripped
  package unique-id, XOR first 1040 bytes) and **Adobe** (UUID-hex key, XOR first 1024) font
  de-mangling, dispatched from `META-INF/encryption.xml`.
- `FontRegistry`/`EmbeddedFace` — load each face's TrueType file from the zip (deobfuscating
  when listed), parse via core `TrueTypeFont`, and match a run's `font-family` + weight/style
  to a face (exact → relaxed weight → any of the family), else Standard-14 fallback.
- The cascade carries the specific `font-family` name (`ComputedStyle.fontFamilyName`); layout
  uses embedded `hmtx` advances (normalised to 1/1000 em) for wrapping and emits `TextGlyph`s
  with real outlines + the face's `unitsPerEm`, drawn via `drawGlyphs(hasOutlines=true)`.
- 104 EPUB tests (SHA-1 vectors, IDPF/Adobe round-trips, `@font-face` parse, and a JVM
  end-to-end render with the in-repo `DroidSansFallback.ttf` incl. an IDPF-obfuscated case).

**Scope:** TrueType (`glyf`) faces only for now. Not yet: OpenType-CFF advances (core `CffFont`
doesn't expose them), WOFF/WOFF2 (per-table inflate / brotli), **bidi** (Arabic/Hebrew reorder),
**CJK** line-breaking + vertical writing, **hyphenation** (Knuth-Liang), **shaping** (GSUB/GPOS
ligatures/kerning). Those are the rest of Phase 4.

### Rendering harness (Phase 7 testing, started)

The ~130 unit tests record the Canvas call stream but never paint. Two JVM gates in
`:kitepdf-native-renderer` (a test-only dependency on `:kitepdf-epub`) now exercise the real
raster path via `AwtCanvas`:

- **`EpubRasterTest`** — rasterises `EpubPage` to a `BufferedImage` and asserts on pixels: text
  leaves ink, headings sit near the top, `background-color` fills a region, borders paint edges,
  backgrounds sit behind text, images blit their pixels. Catches coordinate-flip / paint-order /
  outline / `fillPath` bugs the RecordingCanvas tests can't.
- **`EpubDifferentialTest`** — renders a synthetic corpus (plus any `-Dkitepdf.epub.corpus` dir)
  through the raster path; **Gate 1** no render throws, **Gate 2** no blank content page. With
  `mutool` present it also records a per-book page-0 MAE vs the oracle (synthetic MAEs currently
  0.002-0.13). That MAE is **informational** — EPUB reflow/pagination diverge from mutool, so
  pixel parity is a real gate only for fixed-layout books (Phase 5), not reflowable ones.

Still missing: a spec-valid book corpus (`epub3-samples`/Readium + `epubcheck`), a mean-MAE trend,
and golden-image regression baselines.

---

## (historical) Phases 1, 2 & 3 landed

### Phase 3 — box-model layout (done)

**A real CSS box tree replaces the flat block list.** The Phase-2 walker flattened to a
linear list, which can't express a container's background/border wrapping its children;
Phase 3 builds a nested box tree, resolves the full box model to geometry, and paints it.

- `LayoutBox.kt` — the box tree: `BlockBox` (container), `TextBlockBox` (anonymous inline
  formatting context holding runs → line boxes), `ImageBox`; each carries resolved geometry
  (border-box x/y/w/h) in document space (y grows down across all pages).
- `BoxBuilder.kt` (replaces `StyledWalker`) — DOM+cascade → box tree; inline content gathers
  into anonymous `TextBlockBox`es interleaved with child block boxes; markers, whitespace.
- `BoxLayout.kt` — resolves margin/border/padding/width (auto-fill, explicit, `max-width`),
  **collapses adjacent sibling margins**, stacks children, and lays inline content into lines
  with **`text-align: justify`** (inter-word stretch), first-line `text-indent`, `line-height`,
  sup/sub. Anonymous boxes carry no decorations (their `BlockBox` owns them).
- `Paginator.kt` — slices the positioned tree into pages; lines/images never split; background/
  border boxes attach to every page they span.
- `EpubDocument`/`EpubPage` rewired — paints **backgrounds + per-side borders** via `fillPath`
  (clipped to the page band, document-y → y-up), then text via `drawGlyphs`, images via
  `drawImage`. Cascade now resolves per-side `border` (width/style/color, `medium` default,
  paints only when style ≠ none) + `width`/`height`/`max-width`.

- `BoxLayout.layoutTable` + `TableBox`/`TableRowBox` — **auto table layout**: measure each
  cell's preferred (unwrapped) and minimum (longest word) width, size columns (shrink-to-fit /
  proportional distribute / min-overflow), lay cells at their column width, row height = tallest
  cell, cells stretched to the row. Flattens `thead/tbody/tfoot`. (No row/col spans yet.)
- `Paginator` page-break control — forced `break-before`/`break-after` (`page`/`always`) start a
  new page; `break-inside: avoid` moves a block whole to the next page when it fits there;
  **orphans/widows** (min 2) stop a paragraph leaving a single dangling line at a page edge.

Now painted: block/inline layout, margins (+collapse), padding, borders, backgrounds, justify,
width/max-width, nested-box geometry, **tables**, **page-break control (force + widows/orphans +
break-inside)**, multi-doc continuous pagination. 91 EPUB tests (box geometry 9, box tree 17,
cascade 21, render+paint 15, tables 6, page-break 6, …).

Phase-3 polish also done: **table `colspan`/`rowspan`** (occupancy-grid placement, columns
topped-up by spanning cells, rowspan cells stretched over their rows) and **auto-margin
centering** (`margin:0 auto` centers a width-constrained block or shrink-to-fit table). 94 EPUB
tests. Tiny nits still open: `border-collapse` separate-spacing model, fixed `table-layout`,
cell `vertical-align`.

**Phase 3 is complete. Next: Phase 4 (typography)** — embedded `@font-face` fonts + IDPF/Adobe
deobfuscation (⚠️ fix the cross-backend advance-scale bug first, see §5), bidi (Arabic/Hebrew),
CJK line-breaking, hyphenation.

---

### Phase 2 — CSS engine (done)

**A real pure-Kotlin CSS cascade now drives layout**; the Phase-1 hard-coded tag styling is
gone. New `css/` package:

- `CssValues.kt` — lengths→pt (px×0.75, pt/em/rem/%/ex/in/cm/mm/pc + font-size keywords),
  colours (#hex, `rgb()/rgba()`, ~50 named).
- `Selector.kt` — type/`*`/`.class`/`#id`/`[attr op val]`, descendant + child `>`, grouping;
  specificity; right-to-left matching against the ancestor chain; pseudo-elements drop the rule.
- `Css.kt` + `CssParser.kt` — forgiving parser: strip comments, brace-match rules, `!important`,
  shorthand expansion (`margin`/`padding`/`list-style`); `@media` flattened, `@font-face`/`@page`
  skipped.
- `UaStylesheet.kt` — EPUB UA defaults adapted from MuPDF's `html_default_css` (the oracle).
- `ComputedStyle.kt` + `StyleResolver.kt` — the cascade: match → pick winner per property by
  (origin/importance, specificity, order) → apply onto parent-inherited seed → resolve lengths
  against the element's own font size. Inline `style=""` beats selectors.
- `StyledWalker.kt` (replaces `DomFlattener`) — `display` decides block/inline/list-item/none;
  font/colour/weight/style/family/valign/white-space from computed style; **indent accumulates**
  margin-left+padding-left down the tree; block spacing = sibling-collapsed vertical margins;
  markers from `list-style-type` + ordinal (decimal/roman/alpha/disc/circle/square, `start`).
- `Layout.kt` reworked — per-run font size **and family** (Times/Helvetica/Courier base-14),
  colour, **`text-align`** left/right/center (justify→left, Phase 3), first-line **`text-indent`**.
- `FontMetrics.kt` — added Helvetica (sans) metrics alongside Times/Courier.

Applied now: display, font size/weight/style/family, colour, text-align, text-indent,
line-height, margins (spacing+indent), white-space (pre), list markers, sup/sub. Computed but
**not painted yet** (Phase 3 box model): padding, borders, backgrounds, `background-color`,
justify, real tables. 62 EPUB tests total (cascade 17, walker 17, render/CSS 11, +metrics/parser/path).

**Next: Phase 3 (box-model layout)** — backgrounds + borders via `fillPath`, padding, justified
text, list/table grids, page-break control. The computed values are already resolved and waiting.

---

### Phase 1 — reflow engine (done)

**Phase 1 is done** (DOM + inline runs + real metrics + images + headings/emphasis). The
proof-of-concept is now a real reflow engine for a plain novel. What shipped:

- `Dom.kt` + `HtmlParser.kt` — MiniXml tokens folded into an `HtmlNode` tree with tag-soup
  recovery (void elements; optional end tags for `p`/`li`/`dd`/`dt`/`tr`/`td`, barrier-aware
  so nested lists don't mis-close).
- `InlineRun.kt` — `DomFlattener` walks the tree into a styled `Block` stream: emphasis
  (`b/strong`, `i/em`, `code`→mono, `a` link, `sup`/`sub`), headings (UA scale + bold),
  blockquote indent, list markers (`•` / `1.`), `<pre>` whitespace, HTML whitespace collapse.
- `FontMetrics.kt` — **`advanceEm` is gone.** Real Standard-14 (Times/Courier) AFM advances
  via char→glyph-name (WinAnsi/Standard + AGL)→width, driving both wrap and backend advances.
- `Layout.kt` — `LayoutEngine`: greedy wrap by real advances, per-run bold/italic/mono
  `FontSpec` on the Canvas fallback path, sup/sub shrink+shift, list-marker gutter, page
  fill that never splits a line and collapses top-of-page gaps.
- `EpubDocument.kt` — reworked pipeline (parse→flatten→layout→paginate); href resolution with
  `.`/`..` normalization + percent-decode; images via `img@src`.
- Images: new **public** core factory `ImageXObject.fromEncodedImage(bytes)` (sniffs format).
  **PNG** is decoded in pure Kotlin (`render/PngDecoder.kt`, reuses core `Zlib`) into a
  `Kind.RAW` image → renders on every backend with no backend change. **JPEG** works
  (delegated to the platform decoder, exactly like PDF `DCTDecode`). **GIF returns null and is
  skipped.** PngDecoder covers colour types 0/2/3/4/6 at bit depths 1/2/4/8/16, all five
  filters, palette + alpha `tRNS`; not yet: Adam7 interlace, colour-key `tRNS` on gray/RGB.
- Tests: 34 EPUB + 13 core (`commonTest`, all targets), covering parser tag-soup, whitespace,
  emphasis/heading render, metrics, path resolution, pagination, `img`→`drawImage`, and
  pixel-accurate PNG decode (grey/RGB/RGBA/palette, Sub/Up/Paeth filters, 1-bit, interlace-null).

**Immediate next step (before the rest of Phase 2):** GIF decode (or drop it — rare in EPUB),
then start **Phase 2 (CSS engine)**. A pure-Kotlin baseline **JPEG decoder in core** (so JPEG
too renders without a platform loader, closing the shared PDF/EPUB JPEG gap) is the other
loose end, tracked as its own core-codec task.

---

## 1. Current state — audit (original proof-of-concept, pre-Phase-1)

> Superseded by §0. Kept for the record of where this started.

Three files, ~450 lines. It opens a real EPUB and paints text through the core Canvas
(`EpubRenderTest` proves it end to end into `RecordingCanvas`). Precisely:

### `ZipReader.kt`
- ✅ Central-directory parse; reads entries by name.
- ✅ STORED (0) + raw DEFLATE (8) via core `Inflate`.
- ❌ No ZIP64, no encryption/`META-INF/encryption.xml`, no streaming data descriptors
  (sizes come only from the central directory), no CRC verification.

### `MiniXml.kt`
- ✅ Forgiving tokenizer → flat `List<XmlToken>` (Open/Close/Text); attributes; entity
  decode (named subset + numeric `&#…;`/`&#x…;`); skips comments/PI/DOCTYPE; CDATA as text;
  namespace prefixes stripped in `localName`.
- ❌ No tree, no well-formedness, no HTML tag-soup recovery (implicit tag closing,
  optional end tags like `<li>`/`<p>`), no `<pre>` whitespace preservation, no full HTML5
  named-entity table.

### `EpubDocument.kt` / `EpubPage`
- ✅ `open(bytes)`: `META-INF/container.xml` → first `rootfile@full-path` → OPF →
  `manifest` (id→href) + `spine` (idref order) → resolve hrefs against the OPF dir → read
  each spine doc → `extractBlocks`. Returns null on unreadable/no-spine/no-text.
- ✅ `extractBlocks`: flushes text on a fixed block-tag set
  `{p,div,h1..h6,li,blockquote,br,tr,title}`; skips `{script,style,head}`.
- ✅ Layout: greedy word-wrap; fixed page geometry (400×640 pt default, 36 pt margin,
  12 pt font, 1.4 line-height); fixed pagination; one blank line between blocks.
- ✅ Render: `renderLine` builds per-char `TextGlyph`s and calls `drawGlyphs` on the
  **fallback path** (`hasOutlines=false`, `FontSpec(Serif)`), y-up page space → device.

**Hard limits to fix (the audit's real content):**
1. **Metrics are a heuristic.** `advanceEm(ch)` is a rough per-char em guess used for BOTH
   wrapping AND the widths handed to the backend. Real readers need real font metrics.
2. **Inline structure is discarded.** `b/i/em/strong/a/span/sup/sub/code` become plain
   text — no bold, italic, links, super/subscript. Only block boundaries survive.
3. **No CSS at all.** Linked stylesheets, `<style>`, and `style=""` are ignored.
4. **Headings render at body size** (`h1` == `p`); no font-size/weight variation.
5. **No box model** — no margins/padding/borders/backgrounds, no indentation, no alignment
   or justification, single hard-coded black on transparent.
6. **No images, lists markers, or tables** (`tr` is merely a block break; `img` dropped).
7. **No embedded fonts** (`@font-face`), **no bidi/RTL**, **no CJK line-breaking**, **no
   hyphenation**, **no ligatures/kerning/shaping**.
8. **No navigation/TOC**, **no metadata** (title/author/cover/reading-direction), **no
   fixed-layout** support.
9. **Pagination is naive** — fixed lines-per-page, no page-break control, no
   widows/orphans, no image-aware breaking.

### Capability scorecard

| Capability | Today | Target | Phase |
|---|---|---|---|
| OCF unzip (stored+deflate) | ✅ | ✅ (+ZIP64, obfuscation) | 1,4 |
| container → OPF → spine | ✅ basic | ✅ (props, linear, multiple renditions) | 1,6 |
| Metadata (dc:*, cover, dir) | ❌ | ✅ | 6 |
| Navigation (nav.xhtml / NCX) → TOC | ❌ | ✅ | 6 |
| XHTML parse (blocks) | ✅ DOM tree | ✅ DOM tree | ~~1~~ done |
| Inline runs (b/i/a/span/sup) | ✅ | ✅ | ~~1~~ done |
| Real font metrics | ✅ Standard-14 | ✅ | ~~1~~ done |
| Images (jpg/png/gif/svg) | 🟡 png+jpg | ✅ | 1,5 (gif/svg later) |
| CSS parse + cascade | ✅ common subset | ✅ common subset | ~~2~~ done |
| Box model (margin/pad/border/bg) | ✅ painted | ✅ | ~~3~~ done |
| Lists / tables | ✅ (+ col/rowspan) | ✅ | ~~3~~ done |
| text-align / justify / indent | ✅ | ✅ | ~~2,3~~ done |
| Page-break control, widows/orphans | ✅ | ✅ | ~~3~~ done |
| Embedded fonts (@font-face + deobfuscation) | 🟡 TrueType | ✅ | 4 (CFF/WOFF) |
| Bidi / RTL | ❌ | ✅ | 4 |
| CJK + vertical writing | ❌ | ✅ | 4 |
| Hyphenation | ❌ | ✅ | 4 |
| Shaping (ligatures/kerning, GSUB/GPOS) | ❌ | 🟡 | 4 |
| SVG content + inline SVG | ❌ | ✅ | 5 |
| MathML | ❌ | 🟡 | 5 |
| Fixed-layout EPUB | ❌ | ✅ | 5 |
| Text extraction / search / a11y | ❌ | ✅ | 6 |
| Malformed-book recovery | 🟡 | ✅ | 7 |
| Lazy / incremental layout, perf | ❌ | ✅ | 7 |
| Shared Document/Page API + dispatcher | ❌ | ✅ | 7 |

Out of scope (mirrors the PDF north star): Media Overlays (SMIL audio sync), EPUB
scripting (JS), and DRM/LCP decryption.

---

## 2. What `:kitepdf-core` already gives you (reuse, do not rebuild)

- **Font engine** (`font/`): `TrueTypeFont`, `CffFont`, `Type1Font` parse programs and
  yield glyph **outlines** + `hmtx` **advance metrics**; `TtfCMap` maps unicode→gid;
  `Encodings`, `GlyphList`. `Standard14Widths` has AFM metrics for the 14 base fonts.
  → Phase 1 real metrics; Phase 4 embedded `@font-face` fonts (real outlines via
  `drawGlyphs(hasOutlines=true)`).
- **Canvas** (`render/PdfCanvas`): `fillPath` (backgrounds, borders, rules, list bullets),
  `strokePath`, `drawImage`, `drawGlyphs`, `pushClip`/`popClip`, transparency groups, soft
  masks. Everything EPUB draws goes through here → works on all 6 backends for free.
- **Images** (`render/ImageXObject` + `ImageRaster`): decode + colorspaces + `/Decode` +
  masks + bpc unpack. ⚠️ **JPEG/JBIG2/JPX are platform-delegated** (a core gap the PDF side
  also has) — EPUB images inherit it until core gets pure-Kotlin codecs.
- **Color** (`render/RgbColor`, `ColorSpace`): CSS colors → `RgbColor`.
- **Compression** (`compression/Inflate`): zip. `Deflate`/`Zlib` if ever writing EPUB.
- **NOT available in core yet**: the structured-text model (`text/StructuredText`) lives in
  `:kitepdf-pdf`, not core, and there is **no bidi, no hyphenation, no shaping** anywhere
  (PDF never needed them — producers pre-shape). EPUB must add these or promote a shared
  version into core.

---

## 3. Phased roadmap

Each phase is independently shippable and testable. Effort is a rough size (S≈days,
M≈1-2 wk, L≈3-6 wk, XL≈2-3 mo) for one focused engineer. **Land phases in order** — later
phases assume the DOM + style tree from Phases 1-2.

### Phase 1 — Make the current path real (M)
Goal: a plain novel looks correct. Replace flat strings with a real DOM + inline runs +
real metrics + images.
- **DOM**: turn `MiniXml` tokens into a lightweight element tree (`HtmlNode`:
  element(tag, attrs, children) | text). Handle implicit closing for `p`/`li` tag soup.
- **Inline model**: walk the tree into a block/inline structure. Inline runs carry
  style flags (bold from `b/strong`, italic from `i/em`, `a` link, `sup`/`sub`,
  `code`→mono). Blocks: `p`, `h1..h6` (with size scale), `div`, `li`, `blockquote`,
  `pre` (preserve whitespace).
- **Real metrics**: drop `advanceEm`. Load a default serif via `Standard14Widths`
  (Times) for widths; map char→glyph-name→width via `Encodings`. Line-break and position
  by true advances. (Keep drawing via the fallback `drawGlyphs` for now — the backend's
  substitute face is metric-compatible.)
- **Images**: resolve `img@src` against the spine doc's dir → `ZipReader.read` → build an
  `ImageXObject` from the raw bytes (reuse core) → `canvas.drawImage`, scaled to column
  width, as a block. (JPEG still needs a backend decoder — same as PDF.)
- **Headings/emphasis visible**: size headings, embolden/italicize runs (via `FontSpec`
  bold/italic on the fallback path).
- Files: new `HtmlParser.kt`, `Dom.kt`, `InlineRun.kt`, `FontMetrics.kt` (default-face
  metrics), rework `EpubDocument` layout to consume the DOM.
- Test: RecordingCanvas — assert bold runs carry `fontSpec.bold`, headings a larger
  `fontSize`, an `img` produces a `drawImage`. First `mutool` differential fixtures.

### Phase 2 — CSS engine (L)
Goal: honor author styling. Pure-Kotlin CSS, common subset. Reference MuPDF `source/html/`.
- **Parse** CSS from: linked `<link rel=stylesheet>` (manifest items), `<style>` blocks,
  and `style=""` inline. Tokenizer + rule/declaration parser.
- **Selectors** (common subset): type, `.class`, `#id`, descendant, child `>`, grouping,
  `*`, attribute `[..]`, pseudo `:first-child`/`:nth-child` (defer exotic).
- **Cascade**: specificity + source order + `!important`; the EPUB UA default stylesheet;
  inheritance for inherited properties.
- **Computed style** per element: `font-family/size/style/weight`, `color`,
  `background-color`, `text-align`, `line-height`, `text-indent`, `margin`, `padding`,
  `border`, `display` (block/inline/inline-block/none/list-item/table*), `width/height`,
  `vertical-align`, `white-space`. Units: px/pt/em/rem/%/ex; resolve against font-size and
  containing block.
- Files: `css/CssTokenizer.kt`, `css/CssParser.kt`, `css/Selector.kt`, `css/Cascade.kt`,
  `css/ComputedStyle.kt`, `css/UaStylesheet.kt`.
- Test: computed-style unit tests (given HTML+CSS, assert resolved values); RecordingCanvas
  for color/align/indent taking effect.

### Phase 3 — Box-model layout engine (L→XL)
Goal: correct block/inline layout with the real box model. This is the heart.
- **Formatting**: block formatting context (stacked blocks, margin collapsing) + inline
  formatting context (line boxes, baseline alignment, `vertical-align`).
- **Boxes**: content/padding/border/margin; draw backgrounds + borders via `fillPath`;
  `width`/`height`/`max-width`; auto margins (centering).
- **Text**: `text-align` left/right/center/**justify** (inter-word stretch), `line-height`,
  `text-indent`, `white-space` (normal/nowrap/pre/pre-wrap).
- **Lists**: `ul`/`ol`/`li` markers (disc/decimal/roman…), nesting, indentation.
- **Tables**: `table`/`tr`/`td` — a basic auto table-layout grid (row/col spans later).
- **Pagination**: break blocks across pages honoring `break-before/after/inside`,
  `page-break-*`, widows/orphans; never split a line; keep images whole; move
  break-inside:avoid blocks. This replaces the fixed lines-per-page chunking.
- Files: `layout/BlockLayout.kt`, `layout/InlineLayout.kt`, `layout/LineBreaker.kt`,
  `layout/TableLayout.kt`, `layout/Paginator.kt`, `paint/BoxPainter.kt`.
- Test: layout unit tests (assert box geometry); `mutool` differential on styled fixtures.

### Phase 4 — Typography (L→XL)
Goal: real fonts + world scripts.
- **Embedded fonts**: `@font-face` → resolve `src` font file from the zip →
  **deobfuscate** if listed in `META-INF/encryption.xml` (IDPF font-mangling algorithm AND
  the older Adobe algorithm, keyed off the package unique-identifier) → feed to the core
  font engine → `drawGlyphs(hasOutlines=true)` with real outlines. ⚠️ This is where the
  **cross-backend advance-scale bug** bites (Skia/Android/CG/JS use `/upm`, AWT/Compose use
  `/1000`) — fix that unify-to-`/1000` pass first (see landmines).
- **Font matching**: family/weight/style → embedded face or system fallback; synthetic
  bold/oblique when a face is missing.
- **Bidi**: implement the Unicode Bidirectional Algorithm (reorder RTL Arabic/Hebrew runs);
  no equivalent exists in core. Promote to core so PDF extraction can reuse it.
- **CJK**: line-breaking rules (break between ideographs, kinsoku), full-width metrics,
  and (stretch) vertical writing mode.
- **Hyphenation**: Knuth-Liang patterns (per `lang`) or a dictionary; justify quality
  depends on it.
- **Shaping** (stretch): GSUB/GPOS from the embedded font for ligatures/kerning/marks —
  large; can start with `kern` table + standard ligatures only.
- Files: `font/FontFace.kt`, `font/Deobfuscate.kt`, `text/Bidi.kt`, `text/Hyphenator.kt`,
  `text/CjkBreak.kt`, optional `font/Shaper.kt`.
- Test: RTL fixture (visual order), embedded-font fixture (outlines not fallback), CJK
  fixture, hyphenation unit tests.

### Phase 5 — Rich content (L)
- **SVG**: build an SVG→Canvas mini-renderer (paths, shapes, gradients, text, transforms).
  Share it with a future `:kitepdf-svg` handler — put it in core or a shared module. Covers
  SVG content documents AND inline `<svg>` AND `img` pointing at `.svg`.
- **Images polish**: `object-fit`, intrinsic sizing, `srcset` (defer), `picture` (defer),
  animated GIF first frame.
- **MathML** (stretch/hard): a basic presentation-MathML layout, or render via an embedded
  font + fallback. Consider deferring past 1.0.
- **Fixed-layout EPUB**: detect `rendition:layout=pre-paginated`; honor the viewport meta;
  render each page at its declared size (no reflow) — a separate, simpler layout path.
- Files: `svg/…` (shared), `layout/FixedLayout.kt`.

### Phase 6 — Navigation, metadata, accessibility (M)
- **TOC**: parse EPUB 3 `nav.xhtml` (`epub:type=toc/landmarks/page-list`) and EPUB 2
  `toc.ncx`. Expose a `TableOfContents` tree with hrefs → (spine index, fragment).
- **Metadata**: `dc:title/creator/language/identifier`, `meta` refines, cover image,
  `page-progression-direction` (rtl books), reading order.
- **Text extraction / search / selection**: as blocks are laid out, emit into a structured
  text model (promote `StructuredText` to core, or build EPUB's own) with per-glyph boxes;
  enables find + quads + accessibility reading order. Reuse the PDF stext model if promoted.
- Files: `nav/TocParser.kt`, `EpubMetadata.kt`, wire extraction into layout.

### Phase 7 — Production hardening + API (M→L)
- **Robustness**: recover from malformed zips, missing/duplicate OPF, tag soup, bad
  encodings (sniff UTF-8/16/1252), broken hrefs. Mirror the PDF `PdfRepair` philosophy.
- **Performance**: lazy spine loading (parse+lay-out a doc only when its page is needed),
  incremental/streamed layout, layout caching keyed on (doc, viewport, font-size), page
  virtualization for huge books.
- **Reflow vs scroll modes**; user overrides (font-size, margins, theme) that re-flow.
- **Shared `Document`/`Page` interface in core** (the `fz_document`/`fz_page` equivalent)
  so `PdfPage` and `EpubPage` implement one type; add a magic-byte `KiteDocument.open`
  dispatcher. Currently `EpubPage.renderTo` only mirrors `PdfPage.renderTo` by convention.
- **Backend convenience**: an EPUB rasterizer entry point per renderer (today only PDF has
  `PdfPageRasterizer`-style helpers); re-point renderers to depend on `:kitepdf-core`
  instead of `:kitepdf-pdf` where they only need the Canvas.
- **Publish config** for `:kitepdf-epub` (+ `:kitepdf-core`) — they currently have no
  vanniktech plugin / POM, so the umbrella POM would dangle (see `taxonomy.md`).

---

## 4. Testing strategy

- **Unit** (`commonTest`, all targets): parser (zip/xml/css), computed-style resolution,
  box geometry, bidi/hyphenation. Fast, deterministic.
- **RecordingCanvas** (in core): drive `EpubPage.renderTo(RecordingCanvas())` and assert
  the emitted call stream (drawGlyphs text/position/`fontSpec`, `fillPath` backgrounds,
  `drawImage`). This is the primary render-correctness gate — no platform needed. Pattern:
  `EpubRenderTest`.
- **Differential vs `mutool`** (`:kitepdf-native-renderer` jvmTest, extend the existing
  difftest): `mutool draw book.epub` → reference PNGs; render kitepdf-epub → PNGs; MAE per
  page, worst-first report. **MuPDF is the EPUB oracle just as it is the PDF oracle.**
- **Corpus**: build `kitepdf-epub/corpus/` from `epub3-samples`, Readium test books, and
  hand-authored fixtures covering: plain novel, styled textbook (headings/lists/tables),
  images, embedded fonts, RTL (Arabic/Hebrew), CJK (vertical), MathML, fixed-layout,
  malformed. Track a mean-MAE trend like the PDF harness.
- **Validity**: run books through `epubcheck` to confirm the corpus is spec-valid before
  trusting a diff.

---

## 5. Cross-cutting decisions & landmines

- **CSS engine is from-scratch pure Kotlin.** No dependency; read MuPDF `source/html/`
  (`css-apply.c`, `css-parse.c`, `layout.c`) as the reference for a lean, book-focused
  subset. Do NOT aim for full web CSS — aim for what EPUB content actually uses.
- **Replace `advanceEm` early (Phase 1).** It currently drives both wrapping and the widths
  handed to the backend. Everything downstream inherits its inaccuracy.
- **Fix the cross-backend advance-scale bug before Phase 4.** Embedded-font outline advance
  is `/upm` on Skia/Android/CoreGraphics/JS but `/1000` on AWT/Compose. Unify to `/1000`
  (widths are 1/1000 em); re-verify the PDF difftest (grades AWT + Skia) stays green. The
  fallback path EPUB uses today is already consistent, so this only bites once EPUB draws
  real outlines.
- **Embedded-font obfuscation** must be handled or fonts render as tofu: implement both the
  IDPF and Adobe deobfuscation algorithms (both XOR the first 1040/1024 bytes with a key
  derived from the package unique-identifier).
- **Bidi/hyphenation/shaping do not exist anywhere in the stack.** They are new builds, not
  reuses. Bidi and the stext model are the two things worth promoting into `:kitepdf-core`
  so the PDF side benefits too.
- **Share SVG with the future `:kitepdf-svg` handler.** Build the SVG→Canvas renderer once,
  in core or a shared module.
- **JPEG-in-core gap** is shared with PDF: EPUB images need it too. A pure-Kotlin baseline
  JPEG decoder in core unblocks both.
- **A shared `Document`/`Page` interface** (fz_document/fz_page) is the right long-term API;
  retrofitting it later is cheap while there are only two handlers.

---

## 6. Definition of "perfection" (done)

`:kitepdf-epub` renders `epubcheck`-valid EPUB 3 reflowable books at visual parity with
`mutool`/Readium for the common surface — text, author CSS, images, lists, tables, links,
embedded fonts, RTL + CJK, hyphenated justification — plus EPUB 2 (NCX) navigation,
fixed-layout books, TOC/metadata, and text search/accessibility, degrading gracefully on
malformed books, on every KMP target. Media overlays, EPUB scripting, and DRM stay out of
scope.

**Suggested next session**: start Phase 1 (DOM + inline runs + real metrics + images). It
turns the proof-of-concept into something that renders a real novel correctly and unlocks
the differential-vs-mutool harness that guides everything after.
