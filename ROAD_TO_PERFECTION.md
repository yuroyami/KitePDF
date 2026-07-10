# ROAD_TO_PERFECTION.md : From a Correct Engine to the Perfect Reader

**Relationship to FABLE5_AUDIT.md:** that document is the task encyclopedia
(per-task files, steps, acceptance criteria). THIS document is the map: it
defines what "perfect" means for KitePDF's four goals, sequences the remaining
audit tasks into milestones with exit gates, and adds the tasks the audit does
not yet cover (T-80..T-85, specified below in the same style). Every ground
rule from FABLE5_AUDIT.md Section 1 applies verbatim (main-only, no
attribution, no em-dashes, zero new deps in core/pdf/epub, lenient salvage,
the Section 1.5 regression gate before every commit). Log everything to
FABLE5_AUDIT_PROGRESS.md exactly as before.

---

## 1. THE FOUR GOALS, MADE MEASURABLE

Perfection is not a feeling; each goal has a numeric or demonstrable exit.

### Goal 1: Parse and read ALL kinds of PDFs and EPUBs (including exotic)

DONE when:
- The PDF differential harness holds mean MAE <= 0.02 vs mutool on a corpus
  of >= 150 pages spanning: encrypted (RC4/AES-128/AES-256), JBIG2, JPX,
  Type3 fonts, mesh shadings (types 4..7), text clipping, tiling patterns,
  CJK with predefined CMaps, tagged and damaged files. 0 render failures,
  0 unexplained blanks.
- The EPUB sweep renders every page of >= 30 real books, 0 failures, and the
  book set includes: an RTL book, a vertical-writing (tategaki) book, a
  float-heavy layout, a WOFF2-only book, a poetry book (pre/white-space), a
  table-heavy technical book, and a fixed-layout children's book.
- Malformed input never crashes: the mutation fuzzer (T-51) is green in CI.

### Goal 2: Write all kinds of PDFs

DONE when:
- From-scratch builds, incremental edits, GC rewrites, redaction, form fill,
  font embedding + subsetting: all mutool-accepted (already largely true).
- Encrypted documents can be CREATED and EDITED (T-83).
- A signing scaffold exists so an app can produce signed PDFs with its own
  crypto (T-84, stretch).
- The writer round-trip property suite (T-52) is green across 25 seeds.

### Goal 3: Render and view perfectly (navigable, clickable, selectable, fast)

DONE when, in the sample app, for BOTH a PDF and an EPUB:
- Tapping an internal link or a TOC entry lands on the exact page (T-32,
  T-82); external links reach the app callback.
- Search highlights hits with correct quads, and next/prev-hit navigation
  works (T-30/T-81, T-33).
- Long-press + drag selects text and yields the correct string (T-80).
- Scrolling and pinching never jank: rasterization is off the main thread
  (T-14), scroll-back is cache-hits (T-15), and the measured budgets hold:
  open < 50 ms, corpus mean render < 35 ms/page on the JVM benchmark,
  flate decode within 2x of native zlib (T-10).
- RTL page progression and fixed-layout spreads behave (T-85).
- And yes: the whole thing draws through Compose canvas (raster or live
  vector mode), zero WebViews, zero fragments, one viewer for both formats.

### Goal 4: Customizable to the brim

DONE when a reader app can, without forking:
- Override font family, line-height, justification, text/background colors,
  and kill publisher CSS, all as a proper cascade layer with instant re-flow
  (T-73), plus the existing page size / margins / font size / ReaderTheme.
- Style the viewer chrome (already: overlay slot, widgets, colors, zoom
  spec, layout modes) and add its own page decorations via the public
  rasterizer and canvas seam.
- Observe everything: page, zoom, links, selection, search hits, layout
  progress, via PdfViewState and the document APIs.

### Explicit NON-GOALS (do not wander here)

- JavaScript execution, CSS flexbox/grid, `<video>`/`<audio>` playback,
  MathML layout. Books that truly need a browser get a browser.
- LCP DRM (licensing-gated) and EPUB media overlays (SMIL read-aloud):
  ecosystem features, revisit only on explicit user request.
- Pixel-matching MuPDF for substitute (non-embedded) fonts. Match general
  viewers, not MuPDF's exact URW glyphs.

---

## 2. CURRENT POSITION (2026-07-10, commit f388e76)

- PDF differential: 29 pages, mean MAE 0.0115, 0 failures.
- EPUB sweep: 23 books, 4148 pages, 0 failures, worstMAE 0.267
  (informational; reflow diverges from mutool by design).
- DONE from Phase 4E: T-60, T-61, T-62, T-63, T-64, T-65, T-67, T-68, T-69.
- Engine-side data for interactivity EXISTS (links, anchors, search,
  structured text for EPUB); the viewer does not consume it yet.
- Nothing from Phases 0/1/2/3/5 has started.

---

## 3. THE MILESTONES

Execute in order. Within a milestone, the listed order is the dependency
order. A milestone is CLOSED only when its exit gate passes and the progress
log records the numbers.

### M0. Foundations (do first, one sitting)

Tasks: T-01, T-02, T-03, T-04, T-05 (FABLE5_AUDIT.md Phase 0).
Why first: decompression-bomb and op-budget caps protect every later
milestone's fuzzing and corpus growth; they are tiny and independent.
EXIT: Phase 0 acceptance tests green; corpus numbers unchanged.

### M1. The Complete Reflow Reader (EPUB engine depth, finish Phase 4E)

Tasks, in order: T-70 (language-aware hyphenation), T-71 (CJK justification
+ kinsoku openers), T-73 (reader settings surface), T-66 (inline images +
floats), T-47 (WOFF2).
EXIT: per-task acceptance green; sweep still 0 failures with page-count
diffs explained in the log; a float fixture and an inline-image fixture
render correctly; `EpubSettings(fontFamily=..., lineHeightScale=...,
usePublisherCss=false)` visibly restyles a real book.

### M2. The Interactive Viewer (both formats: navigate, click, search, select)

Tasks, in order:
1. T-25 (KiteDocument metadata + outline seam)
2. T-81 (PDF textContent adapter, NEW, below) then T-30 (PDF search API,
   implemented as a thin layer over the shared core search)
3. T-31 (viewport -> page hit testing)
4. T-32 (PDF link taps) + T-82 (EPUB link taps + TOC navigation, NEW, below)
5. T-33 (search highlights + outline panel widgets)
6. T-80 (text selection, NEW, below)
7. T-34 (file-open sugar), T-85 (RTL progression + spreads, NEW, below)
EXIT: the Goal 3 demonstrable list, verified in the sample app on desktop
(`./gradlew :sample:run`) and recorded as a checklist in the progress log;
scene tests cover every interaction headlessly.

### M3. The Performance Pass (measured, budgeted)

Tasks, in order: T-10 (platform zlib), T-11 (dynamic Huffman), T-13 (single
glyph layout pass), T-12 (image decode cache), T-16 (document thread
safety), T-17 (lazy pageCount), T-14 (off-main-thread raster), T-15 (bitmap
LRU).
EXIT: benchmark report shows open < 50 ms and corpus mean render < 35
ms/page on this machine, flate decode <= 2x `java.util.zip`, deflate output
<= 1.25x zlib level 6; `ConcurrencyStressTest` green; before/after numbers
for every task in the log.

### M4. Exotic Format Completeness

Tasks, in order: T-41 (text clip modes), T-40 (shading types 1/4/5/6/7),
T-42 (Type3 fonts), T-43 (luminosity soft masks), T-46 (predefined CJK
CMaps), T-45 (JBIG2 MMR/Huffman/halftone), T-83 (encrypted write/edit, NEW,
below), T-72 (vertical writing, the second-largest task), T-44 (JPX, the
largest task, do it last when everything else is green), T-84 (signing
scaffold, STRETCH).
EXIT: Goal 1's corpus thresholds; grow `corpus/pdf` and `corpus/epub` with
a fixture per new feature as you go (generated fixtures are fine; real
files are better; ask the user to drop in books/PDFs when a category is
missing, e.g. a real tategaki novel).

### M5. API Perfection + Release

Tasks: Phase 2 in audit order (T-20, T-21, T-22, T-26, T-27, T-28, then
T-23 + T-24 together, then T-25 if not already done in M2), then Phase 5
(T-51, T-52, T-50, T-53), then T-54 (prepare 0.2.0, ASK THE USER before
publishing anything).
Why last for the renames: T-23/T-24 are repo-wide mechanical churn; doing
them after the feature work avoids rebasing every in-flight change, and
they MUST land before 0.2.0 (last cheap breaking window).
EXIT: `explicitApi()` everywhere, no split packages, docs truth pass done,
CHANGELOG migration table written, tag prepared but unpushed.

---

## 4. NEW TASK SPECS (audit style; log them like any T-xx)

### T-80. Viewer text selection (PDF + EPUB) [BLOCKERS: T-31, T-81]

- **Files:** `kitepdf-compose-viewer/.../PdfViewState.kt`, `PdfGestures.kt`,
  `PdfView.kt`, new `SelectionOverlay.kt`.
- **Problem:** goal 3 says "text selectable"; the engine ships structured
  text with per-char edges (`KitePage.textContent()`), but the viewer has
  no selection at all.
- **Do exactly this:**
  1. Selection model on `PdfViewState`:
     ```kotlin
     public data class TextSelection(
         val pageIndex: Int,
         /** Inclusive char range into the page's KiteStructuredText, in
          *  block-flattened reading order. */
         val start: Int,
         val end: Int,
         val text: String,
         /** Display-space quads (KiteStructuredText convention). */
         val quads: List<Rectangle>,
     )
     public var selection: TextSelection? (snapshot state, private set)
     public fun clearSelection()
     public var onSelectionChange: ((TextSelection?) -> Unit)?
     ```
  2. Gesture: long-press starts selection at the hit char (via T-31
     `hitTest` -> page point -> nearest char by scanning line bounds +
     `charEdges`; write one helper `charIndexAt(page, x, y): Int?` next to
     the T-31 math and unit-test it directly). Drag extends to the char
     under the pointer; both anchors clamp to the same page (cross-page
     selection is OUT of scope, note it). Release finalizes; tap clears.
     Long-press must not fight the existing pan/zoom: while a selection
     drag is active, consume the drag exclusively.
  3. Paint: reuse T-33's quad-overlay path with a distinct color
     (`PdfViewColors.selectionHighlight`, default 0x664285F4). Quads come
     from the same char-range -> per-line-quad walker as search; factor
     that walker out of `KiteStructuredText.search` into an internal
     `quadsFor(block, range)` so search and selection share it, rather
     than duplicating.
  4. Copy: the viewer does NOT touch the clipboard itself; apps read
     `selection.text`. Add a `PdfSelectionActions` example to the sample
     app that copies via `LocalClipboardManager` (sample only).
  5. Pages whose `textContent()` is null (PDF before T-81): long-press is
     a no-op; guard, do not crash.
- **Acceptance:** scene-test (SceneTestDriver) drags across two lines of a
  known fixture: `selection.text` equals the extraction substring exactly,
  quads count == lines touched, `onSelectionChange` fired; pinch/pan still
  work after clearing; golden render shows the overlay pixels.

### T-81. PDF `textContent()` adapter (unify extraction across formats)

- **Files:** `kitepdf-pdf/.../PdfPage.kt`, `text/StructuredText.kt`, new
  `text/KiteTextAdapter.kt`.
- **Problem:** `KitePage.textContent()` returns null for PDF pages, so
  every viewer feature built on the core model (search UI, selection,
  copy) works for EPUB only. `PdfStructuredText` already has spans, lines,
  blocks and glyph geometry; it just speaks page user-space (y-up).
- **Do exactly this:**
  1. Map `PdfStructuredText` -> `KiteStructuredText`: blocks 1:1, lines
     1:1; line text = the line's span texts joined (spans are already in
     reading order); bounds transformed through `pageToDeviceBase()` into
     display space (y-down; remember the Rectangle convention: y-min in
     `bottom`). Char edges: walk each span's glyphs, advance = glyph
     `advanceWidth` x fontSize/1000 transformed by the horizontal scale of
     the display transform; multi-char glyph text splits its advance
     evenly (same rule as the EPUB extractor).
  2. `PdfPage` overrides `textContent()` returning a lazily-built,
     memoized instance.
  3. Implement T-30's `PdfDocument.search(...)` as a thin delegate to
     `textContent().search(...)` per page (Sequence, lazy), keeping the
     `PdfSearchHit` names from the audit as typealiases or thin wrappers
     over `KiteSearchHit` so both audits' acceptance tests pass. If exact
     hyphen-rejoin behavior differs from the T-30 spec, the T-30 spec
     wins; adjust the core walker only with EPUB tests still green.
  4. Rotated pages: the display transform folds /Rotate in; add a fixture
     with /Rotate 90 and assert quads land inside the ROTATED display box.
- **Acceptance:** T-30's own acceptance bullets pass through this path
  (built PDF, hyphenated line break, case-insensitive, quads in page);
  the /Rotate 90 case; EPUB tests untouched and green.

### T-82. EPUB viewer navigation: link taps + TOC [BLOCKERS: T-31, T-63, T-25]

- **Files:** `kitepdf-compose-viewer/.../PdfView.kt`, `PdfViewState.kt`,
  `PdfWidgets.kt`; `kitepdf-epub/.../EpubDocument.kt`.
- **Do exactly this:**
  1. Promote href navigation to public API:
     `public fun EpubDocument.pageOf(href: String): Int?` delegating to the
     internal `pageIndexOfHref` (KDoc: accepts `path.xhtml` and
     `path.xhtml#id`, zip-root-relative, as `EpubPage.links` carry them).
  2. In the T-32 tap handler, replace the "EPUB pages: skip" guard: when
     the hit page is an `EpubPage`, hit-test `page.links` rects (display
     space; the T-31 mapping already lands in display coordinates). An
     internal href (no URI scheme) navigates via
     `document.pageOf(href)` + `animateScrollToPage`; a scheme'd href goes
     to the existing `onLinkTap` callback.
  3. TOC: `PdfOutlinePanel` (T-33) must work unchanged for EPUB because it
     consumes `KiteDocument.outline` (T-25). Verify T-25's EPUB outline
     items use `pageIndexOfHref` for fragments (T-25 step 4's PARTIAL
     fallback is now removable); fix if not.
  4. Optional-underline affordance: links already render underlined+blue
     via the UA sheet; do not add extra chrome.
- **Acceptance:** scene test: multi-spine fixture, tap an internal link on
  page 0 targeting a deep anchor in spine 3 -> `currentPage` becomes the
  exact anchor page (not the spine start); external URL tap fires
  `onLinkTap` and does NOT navigate; a TOC entry with a fragment navigates
  exactly. All three headless.

### T-83. Create and edit ENCRYPTED PDFs

- **Files:** `kitepdf-pdf/.../writer/PdfEditor.kt`, `PdfObjectWriter.kt`,
  `ClassicXrefWriter.kt`, `PdfBuilder.kt`, `crypto/StandardSecurityHandler.kt`
  (+ a new `crypto/Encryptor.kt`).
- **Problem:** `PdfEditor` refuses encrypted documents; `PdfBuilder` cannot
  produce them. "Writes all kinds of PDFs" requires both directions.
- **Do exactly this:**
  1. `Encryptor`: the write-side mirror of `Decryptor`. For V4/AES-128 and
     V5/AES-256 (R6): encrypt strings and streams of newly written indirect
     objects with the object key (V4) or the file key (V5), fresh random IV
     per object via an injectable random source (deterministic in tests;
     remember Date/random constraints in workflows do not apply here, but
     tests need reproducibility, so inject). Do NOT implement public-key
     handlers.
  2. `PdfEditor`: replace the `require(!base.isEncrypted)` with: supported
     when the document authenticated; every staged object is encrypted with
     the SAME parameters as the base document before serialization; the
     trailer keeps the original /Encrypt reference. RC4 documents: keep the
     refusal (legacy write support is not worth it; message says so).
  3. `PdfBuilder`: `fun encrypt(userPassword: String, ownerPassword: String
     = userPassword, permissions: PdfPermissions = ALL)` producing a
     V5/AES-256 R6 /Encrypt dict (derive U/O/UE/OE/Perms exactly per ISO
     32000-2 Algorithm 8/9/10; the verify-side math already exists in
     StandardSecurityHandler, invert it) and encrypting all strings and
     streams except the /Encrypt dict itself and any /Metadata when
     `encryptMetadata = false`.
  4. Document ID: encrypted files need a real /ID; reuse `DocumentId`.
- **Acceptance:** build an encrypted doc -> reopen with the password ->
  text extracts; wrong password -> `WrongPasswordException`;
  `mutool draw -p <password>` renders it (extend WriterOracleTest, skip
  without mutool); incremental edit of an AES-256 fixture: mutool renders
  the edited file with the password and the stamp is visible; the
  unencrypted round-trip suite is byte-identical to before (no accidental
  encryption of plain docs).

### T-84. Digital signature scaffold (STRETCH; do only after everything else)

- **Files:** `kitepdf-pdf/.../writer/` new `PdfSigner.kt`.
- **Do exactly this:** prepare-then-sign flow: (1) `prepareSignature(editor,
  fieldName, rect?, pageIndex)` stages a signature AcroForm field whose /V
  dict has /Filter /Adobe.PPKLite, /SubFilter /adbe.pkcs7.detached, a
  /Contents placeholder of caller-chosen size (default 16 KiB of zeros)
  and a /ByteRange placeholder; (2) `saveForSigning()` returns the bytes +
  the exact ByteRange (computed after layout, patched in place, fixed-width
  numbers); (3) `embedSignature(bytes, byteRange, cms: ByteArray)` writes
  the caller's DER CMS into /Contents hex. NO crypto in the library: the
  CMS blob comes from the app (JVM apps can use `java.security`; that is
  their code, not ours).
- **Acceptance:** ByteRange arithmetic test (the two ranges cover the whole
  file except /Contents exactly); a doc signed with a throwaway JVM-test
  keypair (jvmTest only, `java.security` in the TEST) opens in mutool and
  reopens in KitePDF with the field present. External validators are
  manual; say so in the log.

### T-85. RTL page progression + fixed-layout spreads

- **Files:** `kitepdf-compose-viewer/.../PdfView.kt`, `PdfViewSpecs.kt`;
  `kitepdf-epub` metadata already carries `rightToLeft`.
- **Do exactly this:**
  1. `PdfLayout.Paged` gains `reverseLayout: Boolean = false`; the pager
     passes it through so page N+1 sits to the LEFT when true. Add
     `PdfLayout.pagedFor(doc: KiteDocument)` helper: EPUB with
     `metadata.rightToLeft` (surface it through T-25's `KiteMetadata`,
     add `val rightToLeft: Boolean = false`) -> reversed horizontal pager.
  2. `PdfLayout.Spread(orientation)` mode: two page slots per pager item
     (indices 2k, 2k+1; RTL swaps visual order), each slot the existing
     `PageBox`; odd trailing page centers alone. Only sane for
     fixed-layout/PDF; document that reflowable EPUB should not use it.
  3. `nextPage()`/`previousPage()` remain logical (index +1/-1) in both.
- **Acceptance:** scene test with an RTL fixture: after `nextPage()`, the
  visually-left slot shows the new page; spread mode renders pages 0+1
  side by side and navigation steps by spread; LTR defaults byte-identical
  behavior to today.

---

## 5. STANDING DUTIES (every milestone, not tasks)

- Run the Section 1.5 gate before every commit; record sweep page counts
  and MAE movements with EXPLANATIONS (the T-68 truncation catch is the
  model: an unexplained page-count drop is a stop-the-line event).
- Grow the corpus when you touch a feature with no real-world coverage;
  generated fixtures immediately, and ask the user for real files per
  missing category (tategaki novel, WOFF2 book, signed PDF, JPX scans).
- Keep FABLE5_AUDIT_PROGRESS.md the single source of truth for status;
  this file's milestone checkboxes are summaries, not the ledger.
- New public API always ships with KDoc in the house voice and a docs page
  touch-up lands in M5's truth pass either way.

## 6. MILESTONE LEDGER (tick + date on close)

- [ ] M0 Foundations (T-01..T-05)
- [ ] M1 Complete Reflow Reader (T-70, T-71, T-73, T-66, T-47)
- [ ] M2 Interactive Viewer (T-25, T-81, T-30, T-31, T-32, T-82, T-33, T-80, T-34, T-85)
- [ ] M3 Performance Pass (T-10, T-11, T-13, T-12, T-16, T-17, T-14, T-15)
- [ ] M4 Exotic Completeness (T-41, T-40, T-42, T-43, T-46, T-45, T-83, T-72, T-44, [T-84 stretch])
- [ ] M5 API + Release (Phase 2, T-51, T-52, T-50, T-53, T-54 ask-first)

Phase 4E prehistory (already closed before this file existed): T-60, T-61,
T-62, T-63, T-64, T-65, T-67, T-68, T-69.
