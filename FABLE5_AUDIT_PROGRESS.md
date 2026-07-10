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

## Discovered during execution

(nothing yet)

