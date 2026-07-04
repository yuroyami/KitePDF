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

## 1. Current state — audit (what exists today)

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
| XHTML parse (blocks) | 🟡 flat | ✅ DOM tree | 1 |
| Inline runs (b/i/a/span/sup) | ❌ | ✅ | 1 |
| Real font metrics | ❌ heuristic | ✅ | 1 |
| Images (jpg/png/gif/svg) | ❌ | ✅ | 1,5 |
| CSS parse + cascade | ❌ | ✅ common subset | 2 |
| Box model (margin/pad/border/bg) | ❌ | ✅ | 3 |
| Lists / tables | ❌ | ✅ | 3 |
| text-align / justify / indent | ❌ | ✅ | 2,3 |
| Page-break control, widows/orphans | ❌ | ✅ | 3 |
| Embedded fonts (@font-face + deobfuscation) | ❌ | ✅ | 4 |
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
