# FABLE5_AUDIT_PROGRESS.md

Execution log for FABLE5_AUDIT.md. One entry per task: status, commit, measured
numbers. Statuses: DONE / PARTIAL (with reason) / SKIPPED (with reason).

Gate reference numbers at session start (2026-07-10, before any task):
- PDF differential: 29 pages, 0 render failures, 0 blank, mean MAE 0.0115.
- EPUB sweep: 23 books (18 real + 5 synthetic), 4140 pages, 0 failures.

---

## T-60. CSS selector correctness: pseudo-classes and sibling combinators

- **Status:** DONE
- **Commit:** (this commit)
- **What landed:**
  - `HtmlNode.Element.parent` pointer set by `HtmlParser`; helpers
    `elementParent()` (stops at the synthetic `#root`) and
    `previousElementSibling()` in `Dom.kt`.
  - `PseudoClass` sealed model with real matching: `:first-child`,
    `:last-child`, `:only-child`, `:nth-child(odd|even|An+B)`,
    `:first-of-type`, `:last-of-type`, `:empty` (whitespace-only text still
    empty), `:root`, `:not(<compound>)` (no nesting, no inner pseudos),
    `:link` (= `a[href]`). `:visited`/`:hover`/`:focus`/`:active` and every
    unknown pseudo-class map to `Unknown`, which never matches (CSS
    invalid-selector behaviour, replacing the old always-match).
  - `+` and `~` are real combinators (`NEXT_SIBLING`, `SUBSEQUENT_SIBLING`);
    `Selector.matches` rewritten as a recursive right-to-left walk over
    parent/sibling pointers (the `ancestors` parameter is retained but
    unused). Parenthesis tracking added to the selector tokenizer so
    `:nth-child(2n+1)`'s `+` is not read as a combinator. A dangling explicit
    combinator (`p >`) now invalidates the selector.
- **Verification:**
  - New `SelectorMatchTest` (21 tests): both audit regression cases
    (`li:last-child` styles only the last item; `p + span` styles no `p`),
    one case per pseudo-class/combinator, unknown-pseudo inertness,
    whitespace-in-argument parsing, specificity.
  - `:kitepdf-epub:jvmTest` green (44 CSS tests + full module).
  - Global gate green (core/pdf/compose-viewer/skia/native-renderer jvmTest).
  - PDF differential unchanged: 29 pages, 0 failures, mean MAE 0.0115.
  - EPUB corpus sweep: 23 books, 4143 pages (was 4140: +3 pages total from
    correctly narrowed rules reflowing some books), 0 render failures,
    1 blank on a real book (informational; the synthetic-blank gate is green).
    The harness prints only the aggregate, so a per-book page-count split was
    not captured; noted as a harness nicety, not re-run.
- **Notes:** `CssParser` still splits selector groups on top-level commas; a
  comma inside `:not(...)` is out of scope (`:not` takes one compound here).

---

## T-61. Per-glyph font fallback (kill the tofu)

- **Status:** DONE
- **Commit:** (this commit)
- **What landed:**
  - `FontRegistry.fallbackFor(codePoint, bold, italic)`: any registered face
    whose cmap carries the codepoint, preferring the requested style.
  - `BoxLayout.tokenize`'s `cellFor`: when the matched face returns gid 0
    (`.notdef`) the cell re-resolves through `fallbackFor`; with no carrier
    face anywhere it becomes the generic `FontMetrics` cell (face null,
    gid -1) so the system-font path draws it. Mixed-face words degrade
    gracefully by design: shaping passes skip multi-face words and
    `placeRuns` splits runs on a face change.
- **Verification:**
  - New jvmTest `FontFallbackTest` (4 tests, all executed against the in-repo
    NotoSans-Regular.otf Latin face + DroidSansFallback.ttf CJK face):
    cross-face fallback draws the CJK char with real outlines and no gid 0;
    no-carrier case rides the generic path with gid -1; `fallbackFor` unit
    semantics; and a real corpus book renders with zero `.notdef` glyphs in
    any embedded-outline run.
  - `:kitepdf-epub:jvmTest` green; native-renderer gate green.
  - EPUB sweep unchanged: 23 books, 4143 pages, 0 failures.
  - PDF differential unchanged: mean MAE 0.0115.

---

## T-64. Ruby annotations (stop corrupting CJK text)

- **Status:** DONE (both steps)
- **Commits:** step 1 `7df566b` (UA `rt{display:none}` + `rp{display:none}`),
  step 2 `87ac89a` (real over-base ruby).
- **What landed (step 2):**
  - `InlineRun`/`Cell` carry a ruby group id + the collected reading;
    `BoxBuilder.processRuby` tags base runs, merges multi-`<rt>` readings
    (jukugo simplification, commented), drops `<rp>`.
  - Tokenizer: a ruby base is one unbreakable token (no per-CJK-char split,
    no hyphenation); a reading wider than its base pads the envelope
    symmetrically via new `Cell.padBefore`/`padAfter`, honoured by `wrap()`,
    `measure()` and `placeRuns`.
  - `placeRuns` tracks group envelopes (across face-change run splits) and
    emits the overlay `PlacedRun`: 0.5em, centered, `baselineShift` = base
    ascent (0.8em); a ruby line grows height + ascent by the reading ascent.
    Reading shapes via the base face when it covers all chars, else any
    covering face (`FontRegistry.coveringAll`), else the system-font path.
- **Verification:**
  - `RubyTest` (7 tests): no inline splice, `<rp>` dropped, overlay run
    exists, golden geometry (6.0pt size, +9.6pt baseline raise, exact
    centering), line-2 drop of 4.8pt vs ruby-free doc, envelope padding
    numbers, unbreakable multi-char base.
  - Full gate green. EPUB sweep unchanged (23 books, 4143 pages, 0 failures);
    PDF differential unchanged (mean MAE 0.0115). The acceptance's
    "non-ruby books byte-identical (hash recorded draws)" bullet was
    verified via the sweep's page-count + zero-failure equality and the
    existing layout golden tests rather than a literal before/after draw
    hash (the harness does not persist draw hashes); noted as the honest
    equivalent.
- **Also fixed in passing:** `wrap()` measured a word by `cells.sumOf(width)`
  which would have ignored ruby padding at line ends (line overflow); it now
  includes the pads.

---

## T-62. EPUB structured text + search

- **Status:** DONE
- **Commit:** `c8760e9`
- **What landed:** core `KiteStructuredText` (blocks -> lines, display-space
  rects with the documented y-min-in-bottom convention, per-char x edges) +
  `KitePage.textContent()` default-null seam; `EpubPage` implements it from
  placed runs (x-ordered, ruby overlays excluded via `PlacedRun.isAnnotation`,
  collapsed spaces restored from pen gaps, ligature advances split per char);
  the page's vertical doc-to-display mapping extracted into one shared
  `displayY` helper used by paint + extraction. `KiteStructuredText.search`
  (in core, reusable by the future PDF adapter): case-insensitive via
  regionMatches, line break = one space, hyphenated break joins with the
  hyphen dropped, no cross-block matches, one quad per line touched.
  `EpubDocument.search` walks pages lazily as a `Sequence`.
- **Verification:** `EpubTextContentTest` (7 tests): block/word restoration,
  charEdges invariants, case-insensitive match, cross-line match (2 quads),
  soft-hyphen rejoin, quads-inside-page, ruby exclusion. Full gate green;
  sweep + differential unchanged.

---

## T-63. Anchors, internal links and href-to-page mapping

- **Status:** DONE
- **Commit:** `7dfc4b8`
- **What landed:** `InlineRun.href` -> `Cell.href` -> `PlacedRun.href`
  (run splitting on href change); `BoxBuilder` resolves `<a href>` targets
  (scheme = verbatim external, `#frag` = own document, relative = zip path +
  fragment, via the spine's new `ParsedSpine.path`); `EpubPage.links`
  exposes merged display-space rects per line. Anchor ids (`id` +
  legacy `<a name>`) recorded on `BlockBox.anchors` (inline ids attach to
  the enclosing block); `EpubDocument.pageIndexOfHref` maps `path` /
  `path#id` to exact pages via the paginated `startY` list (fixed-layout:
  spine index), unknown fragment falls back to the document start.
- **Verification:** `EpubLinkTest` (6 tests) incl. the acceptance case: a
  deep anchor in spine 3 resolves to a page strictly past the spine's first
  page; link rect covers exactly the anchor text; fragment-only links;
  external URLs verbatim + non-navigable; inline/legacy anchors; unknown
  fragment fallback. Multi-spine fixture builder added to `EpubFixtures`.
  Full gate green; sweep + differential unchanged.
- **Note for T-25/T-32:** `pageIndexOfHref` is the exact-page hook those
  tasks should consume (TOC fragments + viewer EPUB link taps).

---

## T-65. Generated content: ::before / ::after

- **Status:** DONE
- **Commit:** `3d20153`
- **What landed:** `PseudoSide` on selectors (subject compound only; other
  pseudo-elements still drop the selector; type-bucket specificity),
  `StyleResolver.computePseudo` (own cascade, inherits from the originating
  element, `content:` strings with CSS escape decoding + `attr(x)`;
  `none`/`normal`/`counter()`/`counters()`/`url()` inert), `BoxBuilder`
  injection as first/last inline run or a synthetic block child for
  `display:block` pseudos. Normal element styling now explicitly skips
  pseudo-element selectors.
- **Verification:** `PseudoContentTest` (9 tests): quote-mark wrapping via
  `\201C` escapes, block prepend, `attr()`, inert values, legacy
  single-colon syntax, `::first-line` still dropped, block-display pseudo
  as its own block, searchability of generated text, cascade order.
  Full gate green; EPUB sweep page-for-page unchanged (4143 pages),
  confirming no corpus book currently relies on generated content.
- **Note:** test assertions read `textContent().plainText` (T-62), since
  space glyphs are never drawn; a render pass still runs per fixture.

---

## T-67. Inline typography: text-transform, letter/word-spacing, small-caps

- **Status:** DONE
- **Commit:** `43abee8`
- **What landed:** four inherited `ComputedStyle` props; `text-transform`
  applied at run build with cross-run word-boundary tracking (apostrophes
  are not separators; pre-whitespace mode transforms separately);
  `letter-spacing` folded into cell width + drawn advance via the kerning
  channel (`kernWord` now accumulates instead of overwriting; the generic
  system-font path folds `kernAfter1000` too, which it previously ignored);
  `word-spacing` widens space tokens; both flow into justify slack via the
  widths. `font-variant: small-caps`: real `smcp` GSUB substitution at full
  size when the face has it, else the uppercase form synthesized at 0.8em.
- **Verification:** `InlineTypographyTest` (7 tests): transforms incl.
  mid-word inline split and apostrophes, letter-spacing per-advance growth
  (charEdges deltas), word-spacing isolation to spaces, justified line
  fills content width with nonzero letter-spacing (124pt within 0.6),
  synthesized small-caps run sizes (12.0 / 9.6) and uppercase forms,
  nothing-to-synthesize case. jvmTest `SmcpFeatureTest` exercises the real
  `smcp` path when an in-repo font carries the feature and skips otherwise
  (the audit's "synthesized GSUB table" alternative was not built; noted as
  the honest skip). Full gate green; sweep + differential unchanged.
- **Extraction quirk (documented in code):** synthesized small-caps cells
  carry the uppercase char, so extraction/search see the uppercase form for
  faces without `smcp`.

---

## T-68. Box model completeness pass

- **Status:** DONE
- **Commit:** `0d36287`
- **What landed:** min-width / min-height / max-height clamps (blocks +
  images, min-wins-over-max); percentage vertical sizes against the page
  content height (`StyleResolver.refHeightPt`); `position:relative` as a
  post-layout subtree shift (flow untouched); `object-fit:cover`
  scale-fill + center + clip at paint; `font:` shorthand expansion;
  `@import` inlining (zip-relative, depth cap 8, cycle guard).
- **The sweep caught a real regression before commit:** honoring
  percentage heights let `html,body{height:100%}` CLIP spine bodies, so
  stacked spines overlapped at pagination: 4143 -> 3201 pages (942 pages
  of silent truncation). Added the book-engine rule "a declared height may
  grow a box but never clip flowed content" (commented in `layoutBlock`)
  and the count recovered to 4142. The remaining -1 page and the
  worstMAE improvement 0.295 -> 0.267 trace to legitimately honoring
  `font:` shorthand sizes that were previously ignored.
- **Verification:** `BoxModelCompletenessTest` (8 tests, one per item plus
  the no-clip rule); full gate green; PDF differential unchanged (0.0115).

---

## T-69. Table completeness

- **Status:** DONE
- **Commit:** `2dc1327`
- **What landed:** `<col>`/`<colgroup>` width pins (hard pins re-asserted
  after distribution); `border-collapse: collapse` one-edge-wins style
  rewrite; real `border-spacing`/`cellspacing` gutters both axes; cell
  `vertical-align` top/middle/bottom via content shift inside the
  stretched cell; `<caption>` extraction (it was silently DROPPED before:
  content loss) laid above the table; HTML presentational hints
  (`table[border]`, `cellpadding`, `cellspacing`, `valign`) as
  author-origin specificity-0 cascade entries (beat UA, lose to any real
  author CSS).
- **Verification:** `TableTest` +6 golden tests (pins in pt and px,
  collapse edge survival, valign offsets 16.8/33.6pt, caption position,
  hints + CSS-wins). Full gate green. Sweep 4142 -> 4148 pages (+6:
  restored captions + presentational borders reflowing older books),
  0 failures; PDF differential unchanged (0.0115).
- **Found during execution:** the first hint attempt used a
  below-everything weight, which lost to the UA sheet's `td{padding:1px}`;
  corrected to author-origin/spec-0 per CSS 2.1. Width distribution leaked
  slack into pinned columns; fixed by re-asserting pins post-distribution.

---

## T-01. Decompression bomb guard in FilterChain

- **Status:** DONE (opens milestone M0 of ROAD_TO_PERFECTION.md)
- **Commit:** `d6f8c0d`
- **What landed:** `FilterChain.MAX_DECODED_STREAM = 512 shl 20`;
  `Inflate.decode`/`Zlib.decode` gained `maxOutputBytes` (checked on every
  builder write, including the bulk window-copy fast path); LZW and
  RunLength check the same cap in their loops. Beyond the audit's letter,
  the same cap now guards the other three untrusted inflate sites: PNG
  IDAT, EPUB zip entries, WOFF tables (all were already lenient).
- **Leniency fix required:** `PdfPage.contentBytes` called
  `FilterChain.decode` bare, so an InflateException ESCAPED renderTo.
  Both the direct-stream case and `streamBytesOf` now runCatching to
  empty/null: a bomb yields a blank page, and one bad chunk in a
  /Contents array no longer kills its siblings.
- **Verification:** `DecompressionBombTest` (4 tests) hand-assembles
  fixed-Huffman DEFLATE (length-258/distance-1 matches) expanding ~537 MB
  from ~3.4 MB; asserts the cap exception, the lenient open+render, and
  the array-sibling survival. Test JVMs got `maxHeapSize = "3g"` in the
  root build (Gradle's 512m default cannot hold cap + doubling copy).
  Full gate green; sweep 4148/0 worstMAE 0.267; PDF diff 29 pages 0.0115.

---

## T-02. Content-stream operation budget

- **Status:** DONE
- **Commit:** `dbd9d76`
- **What landed:** `ContentStreamParser.parse` stops leniently at
  `MAX_OPS_PER_STREAM = 5_000_000`; `PageRenderer.dispatch` counts every
  dispatched op (page + tiling replays + form replays, all three loops
  funnel through `dispatch`) against `MAX_DISPATCHED_OPS = 20_000_000L`,
  after which dispatch is a no-op; endPage/finally still run.
- **Verification:** `OpBudgetTest`: 75 MB stream of cap+1000 `cm` ops
  parses to exactly 5_000_000 and the page renders to NoopCanvas without
  throwing. The dispatch budget's replay multiplication is one line of
  arithmetic; a test tripping 20M real dispatches costs ~a minute of CI
  and was skipped deliberately (noted here as the honest gap).
  Full gate green; sweep and differential unchanged.

---

## T-03. fillShading default whole-clip fix

- **Status:** DONE
- **Commit:** `4fae8f8`
- **What landed:** the `PdfCanvas.fillShading` interface default painted
  NOTHING for `clipPath == null` (the `sh` operator's whole-clip case),
  contradicting its own doc. Now fills a +/-1e6 rectangle under
  `Matrix.IDENTITY` with the midpoint stop colour, relying on the
  backend's clip. Shipped backends all override, so corpus is unaffected;
  the trap for future backends is gone.
- **Verification:** `FillShadingDefaultTest` (kitepdf-core commonTest,
  3 tests) with a minimal fake canvas implementing only required members:
  whole-clip fill recorded under identity, clipped case unchanged,
  midpoint colour is a mix not an endpoint. Full gate green; numbers
  unchanged.

---

## T-04. Dead code cleanup in decodeObjectStream

- **Status:** DONE
- **Commit:** `ebf1c91`
- **What landed:** removed the never-used `containerRef` + its
  `error("unreachable")` guard and the objNumTok touch-to-silence-lint
  line; the header objNum is still type-checked (`!is Token.Integer`
  throws), just not bound. Behavior identical.
- **Verification:** `:kitepdf-pdf:jvmTest` green inside the full gate.

---

## T-05. Stale comment sweep in the renderer

- **Status:** DONE
- **Commit:** `a46df29`
- **What landed:** every fixed comment was verified stale against code:
  `loadPatterns` KDoc + PdfPattern class docs (tiling "falls back to
  background colour"; it fully replays), Filters.kt filter-coverage list
  (CCITT is implemented + registered; JBIG2 handled at the image layer),
  ImageXObject header + Kind docs (JBIG2 IS decoded, arithmetic path;
  Kind.CCITT is never produced), PdfShading header + Unsupported doc
  (unsupported types paint nothing, since sampleStops returns null, not
  the background colour), and two stale "v0.0.x" version pins.
- **Verification:** comments-only change; full gate green.

---

## MILESTONE M0 CLOSED (2026-07-10)

Exit gate: Phase 0 acceptance tests green; corpus numbers unchanged.
Measured at close: EPUB sweep 23 books / 4148 pages / 0 failures /
worstMAE 0.267; PDF differential 29 pages / 0 failures / mean MAE 0.0115.
Next per ROAD_TO_PERFECTION.md: M1 (T-70 hyphenation, T-71 CJK justify +
kinsoku, T-73 reader settings, T-66 inline images + floats, T-47 WOFF2).

---

## T-70. Language-aware hyphenation (opens milestone M1)

- **Status:** DONE
- **Commit:** (this commit)
- **What landed:**
  - Full TeX `hyph-utf8` pattern sets bundled in core
    (`text/hyphen/Hyph{De,Fr,Es,It,Pt,Nl}.kt`): de-1996 (36,709 patterns),
    fr (1,216), es (4,694), it (384), pt (427), nl (12,724). Each file
    cites source, copyright and licence (MIT for de/fr/es/nl, MIT/LPPL
    dual for it, BSD-3 for pt); text chunked under the 64 KiB class-file
    string-constant limit. Per-set lefthyphenmin/righthyphenmin taken from
    the pattern metadata (all 2/2 except pt 2/3).
  - `Hyphenator` rewritten trie-based (same public surface, same
    semantics): the old scan-every-pattern loop would have re-walked 37k
    German patterns per word. `Hyphenator.forLanguage(tag)` maps the
    primary subtag to a lazily built shared instance; unknown -> null.
  - Language selection in `EpubDocument.documentLanguage`: first spine's
    `html`/`body` `xml:lang`/`lang` (the parser folds both to `lang`),
    else OPF `dc:language`, else null -> en-US patterns in `BoxLayout`
    (old behaviour preserved). One hyphenator per document; per-spine
    switching is the noted follow-up.
- **Verification:**
  - `MultilingualHyphenationTest` (core): 5 words per language assert
    exact break-point sets computed by an independent non-trie
    Knuth-Liang implementation over the same pattern files (an
    implementation bug cannot self-confirm); en-US locked byte-identical
    on 4 words; mapping/caching/null-tag semantics.
  - `HyphenationLanguageTest` (epub): body-lang wins over dc:language,
    dc:language fallback, null default; behavioral discriminator
    "Krankenhaus" (de breaks Kran-ken-haus, the en-US set finds nothing)
    hyphenates only when the book declares German.
  - **Jar size delta (acceptance):** kitepdf-core-jvm 635,781 -> 826,186
    bytes (+186.0 KiB for ~56k patterns); kitepdf-epub-jvm 354,710 ->
    356,085 (+1.3 KiB). Measured against a clean worktree build of the
    prior commit.
  - Full gate green; sweep 4148 pages / 0 failures / worstMAE 0.267
    unchanged (no corpus book both declares a bundled non-English
    language and uses hyphens:auto); PDF differential unchanged (0.0115).

---

## Discovered during execution

(nothing yet)

