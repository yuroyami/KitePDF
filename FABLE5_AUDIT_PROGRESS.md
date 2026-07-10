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

## Discovered during execution

(nothing yet)
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

