# FABLE5_AUDIT.md : The KitePDF Road-to-100% Pilot Document

**Written by:** the auditing session of 2026-07-05, after reading the full source of
`:kitepdf-core`, `:kitepdf-pdf`, `:kitepdf-epub`, `:kitepdf-compose-viewer`,
`:kitepdf-skia-renderer`, `:kitepdf-native-renderer`, the build scripts, and the
differential-test history.

**Written for:** the next agent working on this repository. You are expected to
execute the tasks below EXACTLY as written. Do not improvise scope. Do not skip
the acceptance criteria. Do not "simplify" a task into something easier that
looks similar. When a task says "verify with command X", run command X and read
its output before claiming the task is done.

---

## 0. HOW TO USE THIS DOCUMENT (read this twice)

1. Work through tasks **in the order they appear inside each phase**, and phases
   in order (Phase 0 first). Tasks marked `[BLOCKER: T-xx]` cannot start until
   task T-xx is complete.
2. Every task has an **Acceptance** section. A task is done when every bullet in
   its Acceptance section is true, and not before. If you cannot make a bullet
   true, STOP that task, write down why in `FABLE5_AUDIT_PROGRESS.md` (create it
   at repo root), and move to the next task. Never silently weaken a criterion.
3. After finishing each task: run the verification command(s) listed, then run
   the global regression gate (Section 1.5). Commit only when both pass.
4. One task = one commit (or a small series of commits). Never bundle unrelated
   tasks into one commit.
5. Keep a running log in `FABLE5_AUDIT_PROGRESS.md`: task ID, status
   (DONE / PARTIAL / SKIPPED+why), commit hash, measured numbers (before/after
   MAE, before/after benchmark ms, etc.). Numbers, not adjectives.
6. If you find a NEW bug while working, do not fix it inline unless it is a
   one-line fix inside the file you are already editing. Instead append it to
   the "Discovered during execution" section at the bottom of
   `FABLE5_AUDIT_PROGRESS.md` and continue your current task.

## 1. GROUND RULES (violating any of these is a failed task)

- **R1. Git:** work directly on `main`. Do NOT create branches. Do NOT add
  `Co-Authored-By`, "Claude", "Anthropic", or any AI attribution to commits,
  tags, PRs, or files. Ever.
- **R2. Punctuation:** never use em-dashes in any file, comment, commit message,
  or doc you write. Use commas, colons, or parentheses.
- **R3. Zero new dependencies in `:kitepdf-core` and `:kitepdf-pdf` and
  `:kitepdf-epub`.** These modules depend on kotlin-stdlib ONLY. If a task seems
  to need a library, it needs `expect`/`actual` platform code instead (JVM may
  use the JDK, Apple may use platform frameworks, JS may use browser APIs;
  common code stays pure Kotlin).
- **R4. MuPDF is the architectural reference, `mutool` is the oracle.** When in
  doubt about behavior, check what MuPDF does (source snapshot in
  `mupdf-master/`, but its thirdparty submodules are empty placeholders; do NOT
  try to build it; the Homebrew binary `/opt/homebrew/bin/mutool` is the runnable
  oracle).
- **R5. The spec is ISO 32000-1 (PDF 1.7) / ISO 32000-2 (PDF 2.0).** Cite the
  section number in code comments when implementing spec behavior, matching the
  existing comment style.
- **R6. Never break the lenient-salvage philosophy:** a single corrupt object,
  image, font, or annotation must degrade to a skip/placeholder, never abort the
  whole page or document. Every parser you touch keeps this property.
- **R7. All public API changes require KDoc** in the existing house style (look
  at `PdfDocument.kt` for the voice: explains WHY and cites spec sections).
- **R8. Match existing code style:** no wildcard imports, comment density as in
  the surrounding file, spec-section citations, `internal` for cross-file
  non-public helpers.

## 1.5 THE GLOBAL REGRESSION GATE (run before every commit)

```bash
# Unit tests, all library modules (JVM is the fast gate):
./gradlew :kitepdf-core:jvmTest :kitepdf-pdf:jvmTest :kitepdf-epub:jvmTest \
          :kitepdf-compose-viewer:jvmTest :kitepdf-skia-renderer:jvmTest \
          :kitepdf-native-renderer:jvmTest

# Differential harness (needs mutool; skips gracefully without it):
./gradlew :kitepdf-native-renderer:jvmTest --tests "*DifferentialTest*"
# Read kitepdf-native-renderer/build/difftest/report.md. The mean MAE on the
# corpus must NOT be worse than before your change. Record it in the progress log.
```

Corpus location: repo-root `corpus/` (`pdf/` has 3 files, `epub/` has 18 books,
git-ignored). Benchmark (only when a task cites perf numbers):
`KITEPDF_BENCH=true ./gradlew :kitepdf-native-renderer:jvmTest --tests "*BenchmarkTest*"`,
report at `kitepdf-native-renderer/build/benchmark/report.md`.

---

## 2. STATE OF THE PROJECT (what the audit found)

Overall assessment: the engine core is in genuinely good shape. Rendering
correctness is measured (mean MAE ~0.024 vs mutool on a 70-page mixed corpus),
the writer is oracle-verified, EPUB survived a 4140-page real-book sweep with
zero failures, and the code is unusually well-commented. The gaps that keep it
from "100%" cluster in five areas:

1. **Performance:** the pure-Kotlin flate codec is ~9x slower than native zlib
   on decode and produces ~3x larger output on encode; the Compose viewer
   rasterizes pages on the MAIN thread; there is no bitmap LRU across pages; and
   images are re-decoded on every draw.
2. **Thread safety:** `PdfDocument` is mutable-cache-based and completely
   unsynchronized, which blocks parallel page rendering (thumbnails) and is an
   undocumented footgun.
3. **API design:** internals leak from the public surface (`bytes`, `xref`,
   `trailer`), the two handlers disagree on error style (PDF throws, EPUB
   returns null), core types still carry `Pdf` names in the format-neutral
   substrate, packages are split across modules, and `explicitApi()` is off.
4. **Feature completeness:** no text search, no viewer link handling, no text
   selection, shading types 1/4/5/6/7 unsupported, Type3 fonts unsupported, JPX
   undecoded, text clip modes 4..7 missing, luminosity soft masks approximated.
5. **Robustness/security:** no decompression-bomb cap, no content-stream op
   budget, unbounded recursion guarded in some places but memory not bounded.
6. **EPUB depth (re-audited 2026-07-09):** the handler survives real books but
   silently mis-styles and drops content. Pseudo-classes and sibling combinators
   over-match (cascade correctness bugs), codepoints missing from an embedded
   face render as tofu (no per-glyph fallback), ruby readings are inlined into
   CJK text (corruption), inline images are dropped, floats / vertical writing /
   generated content are unsupported, there is no text extraction, search, or
   link surface, and hyphenation is en-US-only regardless of book language.
   Phase 4E below covers this; `kitepdf-epub/EPUB_ROAD_TO_PERFECTION.md` is the
   historical log, this document supersedes its open items.

Version status: v0.1.0 is published on Maven Central (single-module layout).
The current tree (0.1.1, unpublished) split the library MuPDF-style into
`:kitepdf-core` / `:kitepdf-pdf` / `:kitepdf-epub` / renderers / `:kitepdf`
umbrella. **This unpublished window is the last cheap chance for breaking API
changes. Phase 2 exploits it. Do Phase 2 before any 0.2.0 release.**

---

## PHASE 0: SECURITY + CORRECTNESS QUICK STRIKES

### T-01. Decompression bomb guard in FilterChain

- **Files:** `kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/filters/Filters.kt`
  (the `FilterChain` object), `kitepdf-core/.../compression/Inflate.kt`.
- **Problem:** `FilterChain.decode(stream)` inflates with no output cap. A
  crafted 1 KB flate stream can expand to gigabytes and OOM-kill the host app.
  MuPDF caps this; we do not.
- **Do exactly this:**
  1. Add to `Inflate` an optional `maxOutputBytes: Int` parameter (default
     `Int.MAX_VALUE`). Inside the inflate loop, after each write to the
     `ByteArrayBuilder`, if `out.size > maxOutputBytes` throw
     `InflateException("inflate output exceeds cap ...")`.
  2. In `FilterChain`, define `const val MAX_DECODED_STREAM = 512 shl 20`
     (512 MiB) and pass it down for flate, LZW, and RunLength decodes. Also cap
     LZW and RunLength expansion with the same constant (they have their own
     loops in `LzwFilter.kt` / `Filters.kt`).
  3. The cap failure must surface as the existing lenient path does: the object
     resolves to null / the image becomes a placeholder, the document keeps
     opening. Check `PdfDocument.resolve` catches it (it uses `runCatching`, so
     it does; write the test to prove it).
- **Acceptance:**
  - A new `commonTest` in `:kitepdf-pdf` (`DecompressionBombTest.kt`) builds a
    PDF in-memory whose content stream inflates to > cap, opens it, renders the
    page to `NoopCanvas`, and asserts: no OOM, no exception escaping, page
    renders (empty is fine).
  - Existing corpus mean MAE unchanged.
- **Verify:** the global gate (1.5).

### T-02. Content-stream operation budget

- **Files:** `kitepdf-pdf/.../content/ContentStream.kt` (`ContentStreamParser`),
  `kitepdf-pdf/.../render/PageRenderer.kt`.
- **Problem:** a malicious content stream with millions of operators (or one
  built by the repair path from garbage) allocates an unbounded `List<Operation>`
  and renders forever. `MAX_TILES` and `MAX_FORM_DEPTH` exist, but no op budget.
- **Do exactly this:**
  1. In `ContentStreamParser.parse`, add a hard cap of 5_000_000 operations per
     stream; on exceeding it, stop parsing and return what was collected
     (lenient, log-free).
  2. In `PageRenderer.render`, add a per-page dispatched-operation counter
     (include tiling-pattern and form-XObject replays) with a cap of
     20_000_000; when exceeded, stop dispatching (finally-block still runs,
     `endPage` still called).
  3. Both caps as `private const val` next to `MAX_TILES` with a comment
     explaining the adversarial input rationale.
- **Acceptance:** new test in `:kitepdf-pdf` `commonTest` that synthesizes a
  content stream with (cap + 1000) tiny ops (`1 0 0 1 0 0 cm\n` repeated),
  renders, asserts termination in bounded time and no exception. Corpus MAE
  unchanged.

### T-03. Fix `PdfCanvas.fillShading` default for the whole-clip case

- **File:** `kitepdf-core/.../render/PdfCanvas.kt` lines ~59-67.
- **Problem:** the interface-default `fillShading` does `if (clipPath == null)
  return`, so the `sh` operator (which passes `clipPath = null`, meaning "cover
  the current clip / page") paints NOTHING on any backend that does not override
  `fillShading`. All four shipped backends override it, but the default is a
  trap for every future backend and contradicts its own doc comment.
- **Do exactly this:** when `clipPath == null`, synthesize a full-surface
  rectangle path from the page dimensions captured at `beginPage` and fill with
  the midpoint stop color. This requires the default to know the page size:
  simplest correct approach is to change the default to fill a very large
  rectangle (e.g. `(-1e6, -1e6)` to `(1e6, 1e6)`) under `Matrix.IDENTITY`
  through the ctm-transformed shading midpoint color, relying on the backend's
  clip. Keep it a flat-color approximation; that is the documented contract.
- **Acceptance:** a `commonTest` with a fake canvas implementing only the
  required members records a `fillPath` call when `fillShading(shading, ctm,
  null)` is invoked. Corpus MAE unchanged (real backends all override).

### T-04. Delete dead-code smells in `PdfDocument.decodeObjectStream`

- **File:** `kitepdf-pdf/.../PdfDocument.kt` (`decodeObjectStream`).
- **Problem:** `if (containerRef.objectNumber < 0) error("unreachable")` and the
  `objNumTok.value` "touch to silence lint" line are noise; `containerRef` is
  never used for anything real.
- **Do exactly this:** remove `containerRef` entirely, remove the unreachable
  guard, rename `objNumTok` to `@Suppress`-free discard by parsing the token and
  not binding it (`lexer.nextToken() as? Token.Integer ?: throw ...` for the
  objNum, keeping the format check but no variable). Behavior identical.
- **Acceptance:** `:kitepdf-pdf:jvmTest` green; no functional change.

### T-05. Stale comment sweep in the renderer

- **Files:** `kitepdf-pdf/.../render/PageRenderer.kt`.
- **Problem:** the KDoc on `loadPatterns` (lines ~211-216) claims tiling
  patterns "fall back to background colour", but `renderTilingPattern` fully
  replays tiling cells. Doc rot misleads the next reader.
- **Do exactly this:** rewrite that comment to describe reality (PatternType 1
  replays the cell content stream via `renderTilingPattern` with `MAX_TILES`
  bound; PatternType 2 goes through `fillShading`). Grep the module for other
  comments contradicting behavior while you are there (`grep -n "falls back"
  kitepdf-pdf/src -r`) and fix any you can verify are stale.
- **Acceptance:** comments match behavior; no code change.

---

## PHASE 1: PERFORMANCE (measured, not vibes)

Every task here MUST record before/after numbers from `BenchmarkTest`
(`KITEPDF_BENCH=true`, report at `kitepdf-native-renderer/build/benchmark/report.md`)
in `FABLE5_AUDIT_PROGRESS.md`.

### T-10. Platform-native zlib via expect/actual

- **Files:** new `kitepdf-core/.../compression/PlatformFlate.kt` (expect) +
  actuals in `jvmMain`, `androidMain`, `appleMain`, and a common fallback.
- **Problem:** measured: decode ~9x slower than `java.util.zip`, encode ~3x
  larger output (fixed Huffman only). Flate dominates PDF open+render and every
  save. This is the single biggest measured perf item in the repo.
- **Do exactly this:**
  1. Define in commonMain:
     ```kotlin
     internal expect object PlatformFlate {
         /** Inflate a raw zlib (RFC 1950) stream, or null if no fast path exists. */
         fun inflateOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray?
         /** Deflate to a zlib stream at the given level, or null if no fast path. */
         fun deflateOrNull(data: ByteArray, level: Int): ByteArray?
     }
     ```
  2. `jvmMain`/`androidMain` actuals: `java.util.zip.Inflater` / `Deflater`
     (level 6). Respect `maxOutput` by inflating in chunks and aborting past the
     cap. Handle the `DataFormatException` by returning null (caller falls back
     to the pure-Kotlin path, which produces the better lenient error).
  3. `appleMain` actual: return null for now (pure-Kotlin fallback), leave a
     comment that the Compression framework (`compression_decode_buffer` with
     `COMPRESSION_ZLIB`) is the follow-up; implementing it now is optional and
     only if straightforward via cinterop-free `platform.Compression` symbols.
  4. All other targets: `actual object` returning null (there must be a default
     actual for js/wasm/linux/mingw/watchos etc.; put it in a shared source set
     or duplicate the 3-line stub per source set as the target tree requires).
  5. Wire it in `Zlib.decode` / `Zlib.encode` (`kitepdf-core/.../compression/Zlib.kt`):
     try `PlatformFlate` first, fall back to the existing pure-Kotlin
     `Inflate`/`Deflate`. IMPORTANT: `Zlib.decode` currently verifies the
     Adler-32 trailer; `java.util.zip.Inflater` already verifies it, so do not
     re-verify on the fast path.
  6. Do NOT delete the pure-Kotlin codec. It is the correctness reference and
     the only implementation on several targets. Keep its tests running.
- **Acceptance:**
  - `ZlibAdlerRegressionTest` still green (it strict-inflates via
    `java.util.zip.Inflater`).
  - New `commonTest` round-trip test: random + repetitive payloads (1 KB, 1 MB,
    6 MB) encode via `Zlib.encode` and decode via `Zlib.decode` byte-identically
    on every target the CI runs.
  - Benchmark: JVM flate decode within 2x of raw `java.util.zip` on the 6 MB
    fixture (record the number); PDF open+render mean on the corpus improves or
    holds.
  - `:kitepdf-core` still has zero external dependencies (JDK classes on jvmMain
    are fine; they are not dependencies).

### T-11. Dynamic-Huffman deflate in the pure-Kotlin encoder

- **File:** `kitepdf-core/.../compression/Deflate.kt`.
- **Problem:** fixed-Huffman-only output is ~3x larger than zlib level 6. After
  T-10 the JVM/Android path is native, but Apple/JS/native writers still emit
  fat files.
- **Do exactly this:** implement BTYPE=10 dynamic Huffman blocks: build
  literal/length and distance code-length tables from the actual token
  frequencies (package-merge or the canonical two-queue method, max code length
  15), emit the RFC 1951 code-length-code preamble (with 16/17/18 run-length
  codes), and choose per-block between stored / fixed / dynamic by computing the
  cheapest of the three encodings. Keep the existing hash-chain LZ77 matcher.
  Blocks of 64 KiB of tokens are fine.
- **Acceptance:**
  - Round-trip: everything encoded decodes byte-identically via BOTH the
    pure-Kotlin `Inflate` AND `java.util.zip.Inflater` (extend
    `ZlibAdlerRegressionTest`-style jvmTest with a strict external decode).
  - Ratio: on the 6 MB benchmark fixture and on a real content stream from
    `corpus/pdf/GoldenHour-byIOS.pdf`, output is within 1.25x of
    `java.util.zip.Deflater` level 6 (record both numbers).
  - `mutool` accepts a PDF written with the new encoder:
    `mutool draw -o /dev/null <file>` exits 0 (the `WriterOracleTest` pattern
    already does this; extend it).

### T-12. Decoded-image cache (per document)

- **Files:** `kitepdf-pdf/.../render/PageRenderer.kt`,
  `kitepdf-core/.../render/ImageXObject.kt`, `kitepdf-pdf/.../PdfDocument.kt`.
- **Problem:** every `Do` for an image XObject calls
  `ImageXObject.from(stream, resolver, fillColor)`, which re-runs the full
  filter chain + JPEG/PNG/JBIG2 decode. A page background drawn on all pages,
  or a logo stamped 40 times, decodes 40 times. The `formResourceCache` is also
  keyed by `PdfStream` (a data class holding a `Map` + `ByteArray`), so every
  lookup deep-hashes the dictionary: replace that keying too.
- **Do exactly this:**
  1. Give `PageRenderer` a `private val imageCache = HashMap<Any, ImageXObject>()`.
     Key: the identity of the `PdfStream` instance. Since `PdfStream` is a data
     class (equals/hashCode partly structural, `ByteArray` by reference), do NOT
     use it directly as a key. Create a tiny wrapper
     `private class IdentityKey(val ref: Any) { override fun hashCode() =
     ref.hashCode() /* replace with identityHashCode semantics */ }`: in pure
     common Kotlin there is no `System.identityHashCode`, so instead key by the
     stream's dictionary map instance identity is equally unavailable. Correct
     common-Kotlin approach: maintain
     `private val imageCache = ArrayList<Pair<PdfStream, ImageXObject>>()` with
     reference-equality scan (`===`), which is O(n) but n is tiny (images per
     page), OR better: cache at the RESOLVER layer keyed by object number.
     **Chosen design (implement this one):** cache decoded images on
     `PdfDocument` in a `HashMap<Long, ImageXObject>` keyed by the image
     XObject's indirect object number. Thread the object number through: in
     `loadXObjects`, keep the `PdfReference` alongside the resolved stream
     (change the map value to a small `data class XObjectSlot(val ref:
     PdfReference?, val stream: PdfStream)`), and in the `Do` handler consult
     `document.imageCache` (an `internal` member) before decoding. Images
     reached without a reference (inline in resources, rare) skip the cache.
  2. `/ImageMask` stencils are tinted by the CURRENT fill color: their decoded
     form depends on `fillColor`. Cache those keyed by
     `(objectNumber, fillColor)` or simply do not cache mask images (acceptable;
     comment why).
  3. Convert `formResourceCache` from `HashMap<PdfStream, FormResources>` to the
     same object-number keying (fall back to no caching for ref-less forms).
  4. This cache lives per `PdfDocument`, so add an `internal fun
     dropDecodedImageCache()` for future memory pressure handling and mention it
     in the class KDoc.
- **Acceptance:**
  - New `commonTest`: a synthesized PDF where one image XObject is drawn 3 times
    on one page and on 2 pages; a counting hook (make the decode path
    injectable or count via a test-only static in `ImageXObject`, whichever is
    cleaner without polluting the public API: prefer asserting identity, i.e.
    `RecordingCanvas` captures the SAME `ImageXObject` instance for all draws).
  - Corpus MAE unchanged; benchmark render ms/page improves on image-heavy
    `GoldenHour-byIOS.pdf` (record before/after).

### T-13. Single glyph-layout pass per text run

- **File:** `kitepdf-pdf/.../render/PageRenderer.kt`, `showText` +
  `strokeTextGlyphs` + `totalAdvance`.
- **Problem:** render mode 2 (fill+stroke) lays out the same bytes twice
  (`font.layoutBytes` in `showText` and again in `strokeTextGlyphs`), and
  `totalAdvance` walks the bytes a third time via `forEachGlyphAdvance`.
- **Do exactly this:** in `showText`, call `font.layoutBytes(bytes, resolveOutlines)`
  exactly once (resolve outlines if `doFill || doStroke` needs them), pass the
  resulting `List<TextGlyph>` into the stroke path, and compute the total
  advance by summing `glyph.advanceWidth` from that same list (verify
  word-spacing handling matches `forEachGlyphAdvance`: the `isWordSpace` flag
  must be derivable from `TextGlyph`; if it is not, add
  `val isWordSpace: Boolean` to `TextGlyph` populated by the layout so all three
  consumers agree).
- **Acceptance:** corpus MAE unchanged to 4 decimal places (this is a pure
  refactor); `FontPipelineTest`, `StructuredTextTest` green.

### T-14. Off-main-thread rasterization in the Compose viewer

- **Files:** `kitepdf-compose-viewer/.../PdfView.kt` (the `produceState` block
  at ~line 526), `PdfRasterizer.kt`.
- **Problem:** `rasterizer.rasterize(...)` runs synchronously inside
  `produceState` on the MAIN thread. A complex page (10-30 ms measured, worse on
  mobile) janks scrolling and pinch. The in-code comment claims Compose text
  measurement is not thread-safe on every platform, which is true only for the
  system-font fallback path (`TextMeasurer`); embedded-font pages never touch
  it.
- **Do exactly this:**
  1. Add `internal expect fun kitepdfRasterDispatcher(): CoroutineDispatcher` in
     `:kitepdf-compose-viewer` commonMain with actuals: `Dispatchers.Default`
     for jvm/android/ios/macos (Skia-backed `ImageBitmap` + `CanvasDrawScope`
     drawing is thread-safe on Skiko and on Android's software `ImageBitmap`),
     and `Dispatchers.Main` (i.e. current behavior) for js/wasm where
     workers cannot share the canvas.
  2. In `PdfPageRaster`'s `produceState`, wrap the call:
     `value = withContext(kitepdfRasterDispatcher()) { rasterizer.rasterize(...) }`.
  3. `TextMeasurer` thread-safety: `androidx.compose.ui.text.TextMeasurer` with
     its cache is NOT documented thread-safe. Guard it: in `ComposeCanvas`, the
     system-font text path (the only consumer) must be wrapped in a
     `synchronized`-equivalent. There is no common `synchronized` in KMP:
     instead, give `PdfRasterizer` a simple `Mutex` (kotlinx-coroutines is
     already a dependency of Compose) held across the whole `rasterize` call
     when and only when the page needs system-font text. Determining that ahead
     of time is not possible cheaply, so hold the mutex for every rasterize:
     serialized background rendering is still strictly better than main-thread
     rendering. Comment this clearly.
  4. Cancellation: `produceState` cancels on key change; ensure the rasterize
     loop cannot block cancellation forever: check `coroutineContext.isActive`
     is NOT available inside the synchronous renderer, so accept
     run-to-completion per page (bounded by T-02's op budget) and note it.
  5. MUST manually verify on desktop: `./gradlew :sample:run`, open a PDF, and
     confirm pages appear and scrolling does not freeze. If the sample cannot
     run headless in your environment, run
     `:kitepdf-compose-viewer:jvmTest --tests "*RenderGoldenTest*"` and
     `--tests "*PdfViewSceneTest*"` (they drive the real pipeline) and say so in
     the progress log.
- **Acceptance:**
  - `RenderGoldenTest`, `PdfViewSceneTest`, `WriteThenViewTest` green (these
    catch threading crashes on the JVM path).
  - No `Dispatchers.Main` dispatch of `rasterize` remains on jvm/android/apple
    targets (grep the diff).

### T-15. Global page-bitmap LRU cache

- **Files:** `kitepdf-compose-viewer/.../PdfView.kt`, new file
  `kitepdf-compose-viewer/.../PageBitmapCache.kt`, `PdfViewSpecs.kt`.
- **Problem:** each page slot holds its bitmap only while composed. LazyColumn
  disposes offscreen items, so scrolling back re-rasterizes every page. With
  T-14 this is off-main, but still wasted work and battery.
- **Do exactly this:**
  1. Implement `internal class PageBitmapCache(private val maxBytes: Long)`:
     LinkedHashMap-based LRU keyed by
     `data class Key(val pageIdentity: Any, val w: Int, val h: Int, val bgArgb: Int, val themeId: Int, val hairlineBits: Int)`
     (use `page` object identity for `pageIdentity`; `Float.toRawBits()` for
     hairline). Track bytes as `w * h * 4L` per entry; evict eldest until under
     budget on insert. Not thread-safe by design: confine access to the
     composition/raster coroutine via the T-14 mutex, and say so in the KDoc.
  2. Add `cacheBudgetBytes: Long = 96L * 1024 * 1024` to
     `PdfRenderSpec.Rasterized` (in `PdfViewSpecs.kt`), `0` meaning "no cache".
  3. One cache instance per `PdfViewState` (create lazily there; it must
     outlive individual page composables but die with the state).
  4. In `PdfPageRaster`: consult the cache before rasterizing; insert after.
     `onPageRendered` still fires on cache hits? NO: fire only on fresh
     rasterization (document this in the `PdfView` KDoc, param `onPageRendered`).
- **Acceptance:**
  - New jvmTest: rasterize page 0 twice through the cache path, assert the
    second call returns the SAME `ImageBitmap` instance and the rasterizer ran
    once (structure the code so this is testable: the cache lookup function
    takes a `produce: () -> ImageBitmap` lambda and a counter test can wrap it).
  - Budget test: insert bitmaps exceeding the budget, assert eviction order is
    LRU and total tracked bytes stays under budget.
  - Existing viewer tests green.

### T-16. Make `PdfDocument` safe for concurrent page rendering

- **Files:** `kitepdf-pdf/.../PdfDocument.kt`, new
  `kitepdf-core/.../core/KiteLock.kt`.
- **Problem:** `objectCache`, `objStreamCache`, `activelyResolving`,
  `pageRefToIndex`, the shared seek-based `reader`, and every `by lazy` in
  `PdfDocument` are unsynchronized. Two threads rendering two pages of the same
  document (a thumbnail strip, or T-14's dispatcher with two viewers) can
  corrupt the cache or interleave seeks on the shared `ByteReader` and produce
  garbage parses. Today nothing crashes only because the viewer renders
  serially.
- **Do exactly this:**
  1. Create `internal expect class KiteLock()` with `fun <T> withLock(block: () -> T): T`
     in `:kitepdf-core` commonMain. Actuals: JVM/Android
     `java.util.concurrent.locks.ReentrantLock` (MUST be reentrant: `resolve`
     recurses through `/Length` and ObjStm resolution); Native
     `kotlin.concurrent.Lock`-equivalent, i.e. implement with
     `kotlinx.cinterop` posix mutex OR the simplest correct choice:
     `@kotlin.concurrent.ThreadLocal`-free spin on
     `kotlin.native.concurrent`... STOP: do not overbuild. Use this decision
     rule: if `kotlin.concurrent.Lock` (stdlib, added in recent Kotlin) exists
     in the project's Kotlin 2.4 stdlib for native, use it; verify with a quick
     compile. If not, fall back to `platform.posix.pthread_mutex_t` in a
     `nativeMain` source set with recursive attribute set. JS/Wasm actual: no-op
     (single-threaded).
  2. Wrap the bodies of `PdfDocument.resolve`, `resolveFromObjectStream`,
     `decodeObjectStream`, and `buildPageList` in one document-level lock
     (`private val lock = KiteLock()`). Because the lock is reentrant, nested
     `resolve` calls from the parser callback remain correct.
  3. The shared `reader` is the reason the lock must cover the whole
     parse-from-offset (`resolveInPlace` seeks it). Alternative considered and
     REJECTED for now: per-resolve fresh `ByteReader` (cheap, `bytes` is
     shared); actually that removes the seek hazard entirely AND reduces the
     locked region. Implement BOTH: fresh `ByteReader(bytes)` per
     `resolveInPlace`/`decodeObjectStream` call, and keep the lock only around
     the two `HashMap` caches and `activelyResolving` (make those accesses
     atomic check-then-insert under the lock, but run the parse OUTSIDE the
     lock; on race, first-write-wins into the cache). This gives real
     parallelism between pages.
  4. Lazy properties (`pages`, `catalog`, `outlines`, ...): Kotlin's default
     `lazy` is `SYNCHRONIZED` on JVM but `lazy` in common code across native has
     the same default thread-safe mode in Kotlin 2.x. Confirm (check the stdlib
     doc for `LazyThreadSafetyMode` default in commonMain: it is SYNCHRONIZED).
     Then explicitly write `lazy(LazyThreadSafetyMode.SYNCHRONIZED)` for `pages`
     and `pageLabels` to make intent auditable, and leave the rest default.
  5. `pageRefToIndex` is written inside `buildPageList` and read by
     `resolveDestination`/`articleThreads` which "touch pages" first: after the
     fresh-reader change this is safe if `pages` is safe; add a comment.
  6. Document the guarantee in `PdfDocument` KDoc: "Thread-safe for concurrent
     reads and rendering after construction. Rendering the same page
     concurrently is allowed."
- **Acceptance:**
  - New jvmTest `ConcurrencyStressTest` in `:kitepdf-pdf`: open one document,
    render all pages of a multi-page synthetic doc from 8 threads
    simultaneously (each thread its own `RecordingCanvas`), repeat 20
    iterations, assert every render produced the same call count as a
    single-threaded baseline and no exception was thrown.
  - Benchmark open/render numbers do not regress more than 5%.
  - No API change visible to users.

### T-17. Lazy `pageCount` without building every `PdfPage`

- **File:** `kitepdf-pdf/.../PdfDocument.kt`.
- **Problem:** `pageCount` forces `pages`, which walks the whole page tree and
  constructs every `PdfPage`. Fine at 100 pages, wasteful at 10 000 when the
  caller only wants the count for a UI badge.
- **Do exactly this:** read the root `/Pages` node's `/Count` (resolved through
  the resolver) and return it when it is a plausible non-negative integer;
  validate lazily: keep a `by lazy` that compares `/Count` to the real walk the
  first time `pages` is materialized (no exception on mismatch; the real list
  wins and `pageCount` switches to `pages.size` from then on). Implement as:
  `override val pageCount: Int get() = if (pagesInitialized) pages.size else declaredCountOrWalk()`
  where `pagesInitialized` is a flag set inside the `pages` lazy initializer.
  A malformed `/Count` (missing/negative/non-integer) falls through to
  materializing `pages`.
- **Acceptance:** existing `DocumentTest`/`PageOpsTest` green; new test: doc
  whose `/Count` lies (says 3, tree has 2) reports 2 after touching `pages` and
  never crashes; doc with valid `/Count` reports it without materializing pages
  (assert via a page-construction counter or by timing-free structural means:
  simplest is to check `pageRefToIndex.isEmpty()` before and after, it is
  `internal`).

---

## PHASE 2: API DESIGN (do this BEFORE publishing 0.2.0; breaking changes allowed NOW)

### T-20. Enable `explicitApi()` in every published module

- **Files:** `build.gradle.kts` of `:kitepdf-core`, `:kitepdf-pdf`,
  `:kitepdf-epub`, `:kitepdf-compose-viewer`, `:kitepdf-skia-renderer`,
  `:kitepdf-native-renderer`, `:kitepdf`.
- **Do exactly this:** add `explicitApi()` inside each `kotlin { }` block. Then
  fix every resulting warning/error by writing `public` explicitly and, more
  importantly, by DEMOTING to `internal` everything that should never have been
  public. Demotion candidates you must evaluate one by one (list them with your
  verdict in the progress log):
  - `PdfDocument.bytes`, `PdfDocument.xref`, `PdfDocument.trailer` (keep
    public: the editor and power users need them, but annotate, see T-21)
  - everything under `io.github.yuroyami.kitepdf.parser` in `:kitepdf-pdf`
    (`Parser`, `XrefParser`, `PdfRepair`, `NameTree`): `internal` unless a test
    in another module uses it; tests may move or use `@VisibleForTesting`
    equivalents (there is none in KMP: prefer moving tests into the module).
  - `kitepdf-core` `compression`/`filters` internals: `Inflate` stays public
    (documented utility), `Inflater` is already internal, `Predictors`/`LzwFilter`
    likely `internal`.
- **Acceptance:** all modules compile with `explicitApi()`; the demotion table
  is in the progress log; the sample app still compiles (`:sample` is the
  consumer canary).

### T-21. Opt-in annotation for the raw object model

- **Files:** new `kitepdf-core/.../core/KiteRawApi.kt`; annotate in
  `:kitepdf-pdf`.
- **Do exactly this:** create
  ```kotlin
  @RequiresOptIn(
      message = "Raw PDF object-model API: stable file format, unstable Kotlin surface. " +
          "May change between minor releases.",
      level = RequiresOptIn.Level.WARNING,
  )
  public annotation class KiteRawApi
  ```
  and apply it to `PdfDocument.xref`, `PdfDocument.trailer`,
  `PdfDocument.resolve`, `PdfEditor.addObject`/`updateObject`/
  `allocateReference`/`setTrailerEntry`, and the `PdfObject` sealed hierarchy's
  public exposure points that survive T-20. Do NOT annotate `bytes` (it is just
  the input) or high-level API (`pages`, `outlines`, `formFields`...).
- **Acceptance:** the sample compiles without opt-in (proving the high-level
  surface is annotation-free); a deliberate use of `doc.trailer` in a scratch
  test produces the opt-in warning.

### T-22. Unify open/error semantics across handlers

- **Files:** `kitepdf-epub/.../EpubDocument.kt`,
  `kitepdf-core/.../core/WrongPasswordException.kt` (houses
  `PdfFormatException`? verify: `PdfFormatException` lives in `ByteReader.kt`),
  new `kitepdf-core/.../core/KiteFormatException.kt`.
- **Problem:** `PdfDocument.open` throws typed exceptions; `EpubDocument.open`
  returns null with the reason discarded. Callers cannot distinguish
  "not a zip" from "no OPF" from "empty spine".
- **Do exactly this:**
  1. In core, introduce `public open class KiteFormatException(message: String,
     cause: Throwable? = null) : RuntimeException`. Re-parent
     `PdfFormatException` to extend it (source-compatible; `PdfFormatException`
     keeps its name and package).
  2. Add `public class EpubFormatException(message, cause) : KiteFormatException`
     in `:kitepdf-epub`.
  3. Change `EpubDocument.open(bytes, settings)` to THROW
     `EpubFormatException` with a precise message at each current `return null`
     site ("META-INF/container.xml missing or unreadable", "OPF not found at
     <path>", "spine has no readable documents", ...).
  4. Add `public fun openOrNull(bytes: ByteArray, settings: EpubSettings = EpubSettings()): EpubDocument?`
     wrapping open in runCatching, and the matching
     `PdfDocument.openOrNull(bytes, password)` for symmetry.
  5. Update every internal caller and test; update `docs/getting-started.md`
     and `docs/reading.md` code samples (grep for `EpubDocument.open`).
- **Acceptance:** `MetadataTocTest`, `EpubDocumentPathTest`, all EPUB tests
  green after updating them for the new contract; a new test asserts the three
  distinct failure messages above; docs contain no stale `?: return` EPUB
  samples (grep).

### T-23. De-PDF the core substrate names

- **Files:** nearly everything in `kitepdf-core/src/commonMain`, plus all
  downstream usage. THIS IS A LARGE MECHANICAL TASK. Budget it accordingly.
- **Problem:** the format-neutral core exposes `PdfCanvas`, `PdfPath`,
  `PdfShading`, `PdfPattern`, `PdfFunction`, `PdfFont` (core font engine),
  `PdfFormatException`, and EPUB renders through them. The taxonomy is
  MuPDF-style (core = fitz), so core names must be format-neutral.
- **Do exactly this:**
  1. Rename IN `:kitepdf-core` ONLY, using IDE-grade find/replace over the
     whole repo per symbol, in this exact table:
     | old | new |
     |---|---|
     | `PdfCanvas` | `KiteCanvas` |
     | `PdfPath` | `KitePath` |
     | `PdfShading` | `KiteShading` |
     | `PdfPattern` | `KitePattern` |
     | `PdfFunction` | `KiteFunction` |
     | `PdfFormatException` | keep name (PDF-specific enough) but move to its own file `kitepdf-core/.../core/PdfFormatException.kt` |
     | `PdfFont` (core) | `KiteFont`? NO: verify first. If `PdfFont` lives in `kitepdf-core/.../font/PdfFont.kt` BUT models PDF-spec font dictionaries (Type0/Type1/TrueType /Widths semantics), it is PDF-domain code that merely lives in core for reuse: in that case MOVE nothing, RENAME nothing, and instead note in the progress log that `PdfFont` is PDF-domain. Decide by reading the file, not by the name. |
  2. In `:kitepdf-pdf`, add deprecated typealiases for one release cycle:
     ```kotlin
     @Deprecated("Renamed to KiteCanvas", ReplaceWith("KiteCanvas"))
     public typealias PdfCanvas = KiteCanvas
     ```
     for each renamed public type, in a single file `Compat.kt`. (Typealiases in
     `:kitepdf-pdf`, not core, so core stays clean.)
  3. `RecordingCanvas`, `NoopCanvas` keep their names.
  4. Update KDoc references (`[PdfCanvas]` etc.) as part of the rename; Dokka
     must build: `./gradlew dokkaGenerate`.
- **Acceptance:** full build + all module tests green; `dokkaGenerate`
  succeeds; grep proves `:kitepdf-epub` no longer mentions any `Pdf`-prefixed
  core type except via the compat aliases (it should import the `Kite*` names
  directly); the compat file exists and carries `@Deprecated` on every alias.

### T-24. Fix split packages across modules

- **Files:** `:kitepdf-core` sources; downstream imports.
- **Problem:** `io.github.yuroyami.kitepdf.parser` exists in BOTH core (`Lexer`,
  `PdfObject`) and pdf (`Parser`, `XrefParser`); `io.github.yuroyami.kitepdf.render`
  likewise; the root `io.github.yuroyami.kitepdf` too (`KitePage` in core,
  `PdfDocument` in pdf). Split packages break JPMS consumers and confuse R8 and
  IDE resolution.
- **Do exactly this:** move core's packages under a `core`-rooted namespace:
  - `io.github.yuroyami.kitepdf.parser.{Lexer,PdfObject,...}` (core files) →
    `io.github.yuroyami.kitepdf.core.parser`
  - `io.github.yuroyami.kitepdf.render.*` (core files) →
    `io.github.yuroyami.kitepdf.core.render`
  - `io.github.yuroyami.kitepdf.font.*` → `io.github.yuroyami.kitepdf.core.font`
  - `io.github.yuroyami.kitepdf.{KitePage,Rectangle}` →
    `io.github.yuroyami.kitepdf.core`
  - `compression`, `filters`, `text` (core's `Bidi`, `Hyphenator`) get the same
    `core.` prefix.
  `:kitepdf-pdf` keeps `io.github.yuroyami.kitepdf` (it is the flagship
  surface). `:kitepdf-epub` keeps `io.github.yuroyami.kitepdf.epub`. Do the
  moves file-by-file with `git mv` so history survives, update `package` lines
  and every import repo-wide (compiler will enumerate them). Combine with T-23
  in one working session (both are repo-wide mechanical churn) but keep the two
  in SEPARATE commits, rename first, then repackage.
- **Acceptance:** `./gradlew build` green for all targets that build in this
  environment (`jvmTest` for every module at minimum, plus
  `compileKotlinIosSimulatorArm64` and `compileKotlinJs` for `:kitepdf-core`
  and `:kitepdf-pdf` to prove non-JVM still compiles); no package is declared
  in two modules (verify:
  `find . -path ./build -prune -o -name "*.kt" -print | xargs grep -h "^package " | sort -u`
  and inspect for module overlap).

### T-25. Grow `KiteDocument` into a real format-neutral surface

- **Files:** `kitepdf-core/.../KitePage.kt` (holds `KiteDocument`),
  `kitepdf-pdf/.../PdfDocument.kt`, `kitepdf-epub/.../EpubDocument.kt`,
  `kitepdf-pdf/.../PdfOutline.kt`, `kitepdf-epub/.../TocParser.kt`.
- **Problem:** `KiteDocument` is only `pageCount` + `pages`. A format-agnostic
  viewer cannot show a title, an outline/TOC panel, or navigate a destination
  without downcasting.
- **Do exactly this:**
  1. Add to core:
     ```kotlin
     public data class KiteMetadata(
         val title: String? = null,
         val authors: List<String> = emptyList(),
         val language: String? = null,
     )
     public class KiteOutlineItem(
         val title: String,
         /** Zero-based target page, or null when unresolvable. */
         val pageIndex: Int?,
         val children: List<KiteOutlineItem> = emptyList(),
     )
     ```
  2. Extend `KiteDocument` with
     `public val metadata: KiteMetadata` and
     `public val outline: List<KiteOutlineItem>` (default implementations
     returning empty so third-party implementors do not break).
  3. `PdfDocument`: map `info.title`/`info.author` (and prefer `xmp` when info
     is absent) into `metadata`; map `outlines` recursively into
     `KiteOutlineItem` resolving each destination via `resolveDestination` to a
     page index (null when it fails). Cap recursion depth at 64.
  4. `EpubDocument`: map `EpubMetadata` and `tableOfContents` the same way
     (TOC hrefs resolve to page indices through the paginator's spine-to-page
     mapping; if no such mapping exists yet, add an internal
     `pageIndexOfHref(href: String): Int?` using each page's source-spine
     index, which `PageRender` should already know; if it does not, record
     PARTIAL and map to the first page of the spine document).
  5. Viewer: add an outline drawer widget? NO. Out of scope here; only the data
     surface. (T-33 consumes it.)
- **Acceptance:** commonTest in core proving default implementations compile
  for a minimal fake; pdf test: a doc with outlines yields resolved page
  indices; epub test: a book from `EpubFixtures` yields non-empty outline with
  page indices; no downcast needed to read a title from a `KiteDocument`.

### T-26. Generate `KitePDF.VERSION` from Gradle

- **Files:** `kitepdf-pdf/build.gradle.kts`, delete the hardcoded constant in
  `KitePDF.kt`.
- **Problem:** `KitePDF.VERSION = "0.1.1"` is hand-synced with
  `allprojects.version`. It WILL drift.
- **Do exactly this:** register a Gradle task in `:kitepdf-pdf` that writes
  `build/generated/kitepdf/Version.kt` containing
  `internal const val KITEPDF_VERSION = "<project.version>"`, add the generated
  dir to `commonMain.kotlin.srcDir(...)`, wire task dependencies so
  `compileKotlinMetadata`/every compile task depends on it, and change
  `KitePDF.VERSION` to read `= KITEPDF_VERSION`. No plugins; plain
  `tasks.register` with `outputs.dir` and an `inputs.property("version",
  version)` so it is cacheable and re-runs on version bumps.
- **Acceptance:** `grep -rn "0\.1\.1" kitepdf-pdf/src` returns nothing; bumping
  the version in `build.gradle.kts` and recompiling changes
  `KitePDF.VERSION` (verify once by temporary bump, then revert).

### T-27. String password overload + document the encoding rule

- **File:** `kitepdf-pdf/.../PdfDocument.kt` companion.
- **Do exactly this:** add
  `public fun open(bytes: ByteArray, password: String, allowInvalidPassword: Boolean = false): PdfDocument`
  that encodes per spec: for R6/V5 the password is UTF-8 (ISO 32000-2 says
  SASLprep, but every real implementation including MuPDF uses raw UTF-8;
  follow MuPDF and note it); for R<=4 encode as Latin-1-ish PDFDocEncoding:
  implement the practical rule used by MuPDF (try UTF-8 bytes; the existing
  ByteArray overload remains the escape hatch). Since the handler tries the one
  password as both user and owner, one byte encoding must be chosen per
  attempt: pass UTF-8 and, if authentication fails and the string contains
  non-ASCII, retry with a Latin-1 encoding of the same string. KDoc explains
  this exactly.
- **Acceptance:** `EncryptionIntegrationTest` extended: an AES-256 doc with a
  non-ASCII password ("héllo") opens via the String overload; an RC4 doc with
  an ASCII password opens via the String overload.

### T-28. Warning sink (the `fz_warn` equivalent)

- **Files:** `kitepdf-core/.../core/KiteWarnings.kt` (new),
  `kitepdf-pdf/.../PdfDocument.kt`, key salvage sites.
- **Problem:** lenient salvage silently caches nulls. Integrators debugging "why
  is this page blank" get nothing. MuPDF prints warnings; we swallow them.
- **Do exactly this:**
  1. Core: `public fun interface KiteWarningSink { public fun warn(message: String) }`
     plus `public object KiteWarnings { public var sink: KiteWarningSink? = null }`
     (a process-global, nullable, default null = zero cost; document that it is
     a debugging aid, not a stable event stream, and that the sink must be
     thread-safe).
  2. Call `KiteWarnings.sink?.warn(...)` at exactly these sites (start
     conservative): `PdfDocument.resolve` when `runCatching` fails (include
     object number + exception message), `PdfDocument.open` when falling to the
     repair path, `walkPageTree` when a kid is skipped, `FilterChain` when a
     filter fails, `ImageXObject.from` when a decode falls back to placeholder,
     and `PageRenderer` `Do` when an XObject is missing. Keep messages
     one-line, prefixed with the area: `"resolve: object 12 failed: ..."`.
  3. Never let a sink exception escape: wrap the call in runCatching at a
     single helper `internal fun kiteWarn(message: () -> String)` so message
     construction is lazy.
- **Acceptance:** test installs a collecting sink, opens a corrupt fixture
  (reuse `RecoveryTest` fixtures), asserts at least one warning fires and that
  rendering results are identical with sink installed vs null.

---

## PHASE 3: VIEWER FEATURES (the user-facing gap to "real reader")

### T-30. Text search API in the engine

- **Files:** `kitepdf-pdf/.../text/StructuredText.kt`, new
  `kitepdf-pdf/.../text/TextSearch.kt`.
- **Do exactly this:**
  1. Add
     ```kotlin
     public data class PdfSearchHit(
         val pageIndex: Int,
         /** One rectangle per line-fragment of the match, page user-space. */
         val quads: List<Rectangle>,
         /** The matched text as extracted. */
         val text: String,
     )
     public fun PdfStructuredText.search(
         needle: String,
         ignoreCase: Boolean = true,
     ): List<PdfSearchHit>  // pageIndex filled by the document-level overload
     ```
     Matching walks the concatenated line text with a parallel array mapping
     char offsets to span glyph boxes; a match spanning spans/lines produces
     one quad per line touched (union of the glyph boxes on that line). Matches
     may cross line boundaries within a block (treat the line break as a single
     space when the previous line does not end in a hyphen; when it ends in a
     hyphen, join without space). Do NOT try to match across blocks.
  2. Document-level:
     `public fun PdfDocument.search(needle: String, ignoreCase: Boolean = true): Sequence<PdfSearchHit>`
     lazily iterating pages (Sequence so a UI can show incremental results).
  3. Unicode: compare with `String.lowercase()` on both sides when ignoreCase;
     no locale parameter (KMP stdlib lowercase is fine; note the Turkish-i
     caveat in KDoc).
- **Acceptance:** commonTest with a built PDF (via `PdfBuilder`) containing a
  known sentence split across two lines with a hyphenated break; assert: exact
  match found, hyphen-joined match found, case-insensitive match found, quads
  lie within the page box and are non-empty per line. Corpus smoke: searching
  "the" in `GoldenHour-byIOS.pdf` page 0 structured text returns >= 1 hit
  without throwing (jvmTest, conditional on corpus presence, follow the
  `Corpus.repoCorpus` pattern).

### T-31. Viewport-to-page coordinate mapping + hit testing in the viewer

- **Files:** `kitepdf-compose-viewer/.../PdfViewState.kt`, `PdfView.kt`,
  `PdfGestures.kt`.
- **Problem:** `onTap` reports a raw viewport `Offset`; there is no way to know
  which page was tapped or where in page space. Everything interactive (links,
  selection, form taps) needs this mapping, so build it once, correctly.
- **Do exactly this:**
  1. Add to `PdfViewState`:
     ```kotlin
     public data class PageHit(
         val pageIndex: Int,
         /** Page user-space point (PDF: y-up, origin at displayBox bottom-left mapped through displayToDeviceBase inverse). */
         val x: Double,
         val y: Double,
     )
     public fun hitTest(viewportOffset: Offset): PageHit?
     ```
  2. Implementation requires the per-page on-screen geometry, which currently
     lives implicitly in the LazyColumn/Pager layout. Introduce an internal
     `pageGeometry: MutableMap<Int, Rect>` on the state (page slots report
     their bounds via `Modifier.onGloballyPositioned` in `ContinuousPageItem` /
     `PageBox`, converting to root coordinates, and remove themselves on
     dispose). `hitTest` inverts the zoom/pan `graphicsLayer` transform first
     (mind the transform origin: the layer scales around its center by default;
     replicate the exact math of the layer as composed, and unit-test it at
     zoom 1 and zoom 2), finds the containing page rect, converts to page
     device px, divides by the device scale, then maps device space back to
     page user space through the INVERSE of `displayToDeviceBase()` (add
     `Matrix.invert()` usage; it exists, `PdfPattern` uses it).
  3. Change nothing about the public `onTap` yet; T-32 consumes `hitTest`.
- **Acceptance:** jvmTest using the existing `SceneTestDriver`
  (`kitepdf-compose-viewer/src/jvmTest/.../SceneTestDriver.kt`) or
  `PdfViewSceneTest` infrastructure: compose a single-page view of a known
  page size, tap the visual center, assert the returned page point equals the
  page-space center within 1pt, at zoom 1 AND after `setZoom(2f)` with a focal
  point. If driving real input is impractical, call `state.hitTest(...)`
  directly with computed offsets; the test must still compose the real layout
  to populate `pageGeometry`.

### T-32. Link annotations: tap to navigate [BLOCKER: T-31]

- **Files:** `kitepdf-compose-viewer/.../PdfView.kt`, `PdfViewState.kt`.
- **Do exactly this:**
  1. Add `onLinkTap: ((PdfAction) -> Boolean)? = null` parameter to the full
     `PdfView` overload (consumer returns true when it handled the action, e.g.
     opened a URI; KDoc explains).
  2. On tap (before invoking the user `onTap`): `hitTest`, and when the hit
     page is a `PdfPage`, scan `page.annotations` for a Link whose `rect`
     contains the page-space point (iterate topmost-last, matching paint
     order). If the link has a destination resolvable via
     `document.resolveDestination`, call `scope.launch {
     state.animateScrollToPage(dest.pageIndex) }` and consume the tap. If it is
     a URI action, invoke `onLinkTap`; when null or returns false, ignore.
  3. EPUB pages: skip (no annotations), guard with a type check.
- **Acceptance:** jvmTest: build a 2-page PDF via `PdfBuilder` with a link
  annotation on page 0 targeting page 1 (the builder/editor already writes
  annotations: check `AnnotationTest` for the recipe; if the builder cannot add
  links, construct the PDF via `PdfEditor` on a built base). Compose the
  viewer, simulate the tap path by calling the internal tap handler directly
  with the link's center, assert `state.currentPage` becomes 1.

### T-33. Search + outline UI hooks (widgets, not chrome)

- **Files:** `kitepdf-compose-viewer/.../PdfWidgets.kt`, `PdfView.kt`.
- **Do exactly this:**
  1. Add a `searchHighlights: List<PdfSearchHit>` property (snapshot state) on
     `PdfViewState`, and paint them in both raster and vector paths as a
     translucent fill (color from a new `PdfViewColors.searchHighlight`,
     default 0x66FFEB3B) OVER the page image: in `PdfPageRaster` wrap the
     `Image` in a `Box` with a `Canvas` overlay that maps each quad through the
     same fit/scale math (share one `pageQuadToOffset` helper with T-31; do not
     duplicate the transform).
  2. Add `PdfOutlinePanel(state, outline, onNavigate)` widget in
     `PdfWidgets.kt` following the visual style of the existing widgets
     (`PdfThumbnailStrip` etc.): an indented, clickable column from
     `KiteOutlineItem` (T-25); clicking calls `animateScrollToPage`.
- **Acceptance:** `RenderGoldenTest`-style jvmTest renders a page with one
  injected highlight and asserts pixels in the quad region differ from the
  no-highlight render; widget composes without crash in the scene test.

### T-34. Document-open convenience for files (JVM/Android/Apple)

- **Files:** `kitepdf-pdf` platform source sets (create `jvmMain`,
  `appleMain` if absent).
- **Do exactly this:** add
  `public fun PdfDocument.Companion.open(path: okio...)` NO. No new deps (R3).
  JVM: `public fun PdfDocument.Companion.openFile(path: String, password: ByteArray = byteArrayOf()): PdfDocument`
  reading via `java.io.File(path).readBytes()`. Apple: `NSData`-based
  `openFile(path: String)` using `NSData.dataWithContentsOfFile` + toByteArray.
  Keep it thin sugar; KDoc says "whole file is loaded into memory".
- **Acceptance:** jvmTest writes a temp PDF then `openFile`s it; compile check
  for iosSimulatorArm64.

---

## PHASE 4: FORMAT COMPLETENESS (rendering fidelity)

Order within this phase is by (user impact x frequency in real files).

### T-40. Function-based and mesh shadings (types 1, 4, 5; 6/7 approximated)

- **Files:** `kitepdf-core/.../render/PdfShading.kt` (KiteShading after T-23),
  backends' `fillShading` implementations.
- **Current:** only axial (2) and radial (3) parse; 1/4/5/6/7 become
  `Unsupported` and draw nothing (or flat fallback).
- **Do exactly this:**
  1. Type 1 (function-based): parse `/Domain`, `/Matrix`, `/Function`; render
     by rasterizing the domain into a small grid of colored rectangles
     (64x64 cells max) in the backend-agnostic way: add a
     `KiteShading.FunctionBased` variant carrying an `eval(x, y): RgbColor`,
     and extend each backend's `fillShading` to loop cells. To avoid touching
     4 backends with bespoke code, add to the INTERFACE a default
     implementation for FunctionBased that emits `fillPath` per cell (backends
     get it for free), keeping specialized overrides only where they already
     exist for axial/radial.
  2. Types 4/5 (free-form / lattice triangle meshes): parse the vertex streams
     (bits-per-coordinate/component/flag honored; the vertex data lives in the
     STREAM of the shading dict, so the parse entry point must accept
     `PdfStream`, not just `PdfDictionary`: `PdfShading.parse` already takes
     `PdfObject`, extend the stream case). Render as Gouraud triangles: add
     `KiteShading.TriangleMesh(vertices, colors, triangles)` and a default
     interface implementation that fills each triangle with its centroid color
     (flat approximation), while `SkiaCanvas` gets true Gouraud via
     `Canvas.drawVertices` and the other backends keep the flat default.
  3. Types 6/7 (Coons/tensor patches): parse the patch geometry and TESSELLATE
     each patch into a 4x4 grid of flat-colored quads (corner-color bilinear
     interpolation). This is the documented MuPDF-equivalent approximation
     level; do not attempt exact patch evaluation.
- **Acceptance:** synthetic fixtures: extend `SyntheticPdfs.kt` (native-renderer
  jvmTest) with one PDF per shading type (write them with `PdfBuilder`,
  embedding hand-built shading dicts; type 4 vertex stream can be tiny, two
  triangles). Differential MAE vs mutool for types 1/4/5 <= 0.05 per page;
  types 6/7 <= 0.12 (approximation tolerated). Record all five numbers.

### T-41. Text clipping modes 4..7

- **Files:** `kitepdf-pdf/.../render/PageRenderer.kt` (the TODO at ~line 1211),
  `kitepdf-core` canvas interface.
- **Do exactly this:**
  1. Extend the canvas seam minimally: `pushClip(path, ctm, evenOdd)` already
     exists; text clip needs accumulating MULTIPLE glyph outlines into ONE clip
     applied at ET. In the renderer, maintain `private var pendingTextClip:
     KitePath.Builder?`: entering BT sets it to null; on `showText` with mode
     in 4..7, transform each glyph outline into USER space (same math as
     `strokeTextGlyphs`) and append to the builder (create on first use). For
     glyphs without outlines (system-font fallback), append the glyph's
     advance-width x font-size bounding box instead (approximation, comment
     it). On ET, if the builder is non-null and non-empty:
     `canvas.pushClip(built, pageCtmAtEt, evenOdd = false)` and increment
     `activeClipCount` so the existing q/Q bookkeeping unwinds it. Per spec the
     text clip persists until the enclosing Q, which `activeClipCount` already
     models.
  2. Modes 7 (clip only) must not paint; 4/5/6 paint AND accumulate: the
     existing doFill/doStroke booleans stay, add
     `val doClip = mode >= 4`.
- **Acceptance:** new synthetic fixture (SyntheticPdfs): white page, `BT 7 Tr`
  large glyph, `ET`, then a full-page red `re f`: mutool shows red only inside
  the glyph; assert MAE <= 0.03 vs mutool. Also assert a page with mode 0 text
  is unchanged (regression guard).

### T-42. Type3 fonts

- **Files:** `kitepdf-core/.../font/PdfFont.kt` (or its PDF-side home per
  T-23's verdict), `PageRenderer.kt`.
- **Current:** `/Subtype /Type3` fonts are not handled at all (grep confirms
  no Type3 font code; only `PdfFunction.Type3` exists, unrelated).
- **Do exactly this:** Type3 glyphs are content streams. Parse the font dict:
  `/CharProcs` (name → stream), `/FontMatrix`, `/Encoding` differences array,
  `/Widths`. In the renderer's `showText`, when the font is Type3, for each
  byte: look up the char proc, and replay it as a form-XObject-like nested
  content stream with CTM = `finalMatrix x FontMatrix x translation(penX, 0)`
  and the Type3 font's `/Resources` (fall back to page resources when absent,
  per spec §9.6.5). Honor `d0`/`d1` operators minimally: `d1` sets the glyph
  color to the current fill color and ignores the color operators inside (spec:
  d1 glyphs are uncolored); implement by adding both operators to the dispatch
  as state flags on a small Type3 context. Advance by `/Widths[code] x
  FontMatrix.a x fontSize` (widths are in GLYPH space, transformed by
  FontMatrix: follow §9.6.5 exactly, MuPDF `pdf_type3.c` is the reference).
  Guard recursion with the existing `formDepth`.
- **Acceptance:** synthetic fixture with a hand-written Type3 font (a filled
  square glyph and a triangle glyph, `d1`-style) rendered in a sentence;
  differential MAE <= 0.03 vs mutool; text extraction still returns the
  Encoding-mapped characters (extend `StructuredTextTest`).

### T-43. Luminosity soft masks (true luminance-to-alpha)

- **Files:** `SkiaCanvas.kt`, `ComposeCanvas.kt`, `AwtCanvas.kt`
  (`applySoftMask` implementations).
- **Current:** Luminosity masks are treated as alpha masks (documented
  approximation in the canvas KDoc).
- **Do exactly this:** in each backend's `applySoftMask`, when
  `kind == Luminosity`: render the mask group onto an OPAQUE BLACK backdrop
  offscreen (spec §11.6.5.2), then convert luminance to alpha. Skia: draw mask
  layer, then apply a `ColorFilter` color matrix that moves luma into alpha
  (`0.299R + 0.587G + 0.114B -> A`, RGB -> 0) before the DstIn composite. AWT:
  same via manual per-pixel pass on the offscreen `BufferedImage` (get the
  raster, compute luma into the alpha band). Compose: `ColorMatrix`-based
  `ColorFilter` mirrors Skia. Update the stale "Honest scope" paragraph in the
  canvas KDoc afterward.
- **Acceptance:** synthetic fixture: a gradient-luminosity SMask over a solid
  red square (build the SMask group via `PdfBuilder` raw objects). MAE <= 0.05
  vs mutool on all three graded backends (AWT + Skia harnesses; Compose via
  its golden test with a rebaselined golden and a visual sanity note in the
  progress log).

### T-44. JPX (JPEG 2000) decoder

- **Files:** new `kitepdf-core/.../render/JpxDecoder.kt`, wire in
  `ImageXObject.from` (`Kind.JPEG2000`, exactly as `Jbig2Decoder` is wired).
- **Fixture exists:** `corpus/pdf/testPDF_JPX.pdf` (330x255), current
  placeholder baseline MAE 0.2274, regen instructions in
  `MakeJpxFixture` jvmTest, `opj_compress`/`opj_decompress` at
  `/opt/homebrew/bin/`.
- **Scope warning:** this is the single largest task in this document
  (~2500 lines: JP2 box parsing, codestream SIZ/COD/QCD, tag trees, EBCOT
  tier-1 MQ bitplane coding, tier-2 packet headers, inverse 5/3 and 9/7 DWT,
  multiple-component transform, component scaling). Budget accordingly and
  implement in this order, each sub-step compiling + unit-tested against
  values dumped from `opj_decompress` on the fixture:
  1. JP2 container boxes → find the contiguous codestream (also accept raw
     codestream, PDFs embed either).
  2. Codestream markers: SIZ, COD, QCD, (COC/QCC per-component overrides),
     SOT/SOD tile-parts. Reject (return null → placeholder) on: JPP/JPX
     profiles beyond baseline, ROI (RGN marker), precincts other than default
     full-size ONLY IF setting up general precincts is infeasible; log via
     T-28's warning sink whatever you reject.
  3. Tag trees + tier-2 packet header decoding (layer-resolution-component-
     position progression orders LRCP/RLCP/RPCL at minimum).
  4. MQ arithmetic decoder: REUSE the one in `Jbig2Decoder.kt` (extract it to
     `internal object MqDecoder` shared file first, separate commit).
  5. EBCOT tier-1: three passes (significance propagation, magnitude
     refinement, cleanup) over code blocks, with the standard context models.
  6. Dequantization + inverse DWT (5/3 reversible integer, 9/7 irreversible
     float) + inverse MCT (RCT/ICT) + DC level shift → RGB/Gray raster.
  7. `/SMaskInData` (JPX alpha): support opacity channel when `cdef` box
     declares it.
- **Acceptance:** `testPDF_JPX.pdf` differential MAE <= 0.02 (from 0.2274);
  a jvmTest decodes the raw `.jp2` (regenerate via `MakeJpxFixture` if
  missing) and compares per-pixel against `opj_decompress` output (run via
  `ProcessBuilder`, skip with JUnit assumption when the binary is absent, the
  `MuPdfOracle` class shows the pattern); corpus mean improves; no other page
  regresses.

### T-45. JBIG2: MMR/Huffman + refinement + halftone

- **Files:** `kitepdf-core/.../render/Jbig2Decoder.kt`.
- **Current:** arithmetic generic/symbol/text regions only; MMR-coded regions,
  Huffman-tabled symbol dicts, refinement, and halftone regions return null.
- **Do exactly this:** implement MMR (it is CCITT G4: REUSE `CcittFax.kt`'s G4
  core by extracting its 2D-line decoder into an `internal` shared function,
  separate commit first), standard Huffman tables B.1-B.15 + custom table
  segments, refinement region decoding (template 0/1), and halftone regions
  (pattern dictionary + grayscale image via generic decoding). Follow the
  JBIG2 spec (ITU T.88) section numbers in comments as the existing decoder
  does.
- **Acceptance:** generate fixtures with `jbig2enc` if available
  (`brew install jbig2enc`; if the environment cannot install it, mark the
  halftone sub-item PARTIAL with the reason and still land MMR+Huffman, which
  CAN be tested: `mutool` can convert existing G4 CCITT fixtures);
  `testPDF_JBIG2.pdf` stays at MAE <= 0.001; new fixtures <= 0.01 vs mutool.

### T-46. Predefined CJK CMaps

- **Files:** `kitepdf-core/.../font/PredefinedCMaps.kt` (TODO at line ~26).
- **Current:** UCS2/UTF16 Unicode CMaps are synthesized; the Adobe-Japan1 /
  GB1 / CNS1 / Korea1 locale CMaps (e.g. `GBK-EUC-H`, `90ms-RKSJ-H`) are not
  shipped, so CJK PDFs using them render wrong or blank.
- **Do exactly this:** generate compact Kotlin tables from the Adobe
  cmap-resources repository (license: BSD-3, compatible; you do not have
  network access, so: check whether `mupdf-master/resources/cmaps/` contains
  the CMap files (MuPDF vendors them); if yes, write a small JVM generator
  (a jvmTest-side `main` or a scratch script) that parses those CMap text
  files into range tables and emits a `.kt` file per registry with
  delta-encoded ranges (the same style `PredefinedCMaps.kt` already uses).
  Wire lookup by name. If `mupdf-master` lacks them, implement ONLY the
  encoding-scheme CMaps that are algorithmically derivable and record the rest
  as blocked-on-assets in the progress log.
- **Acceptance:** a fixture PDF using `GBK-EUC-H` with an embedded CID font
  (synthesize via raw objects; the code points can be few) renders text that
  extracts to the right Unicode and diffs <= 0.05 vs mutool.

### T-47. EPUB: WOFF2 fonts

- **Files:** `kitepdf-epub/.../Woff.kt`, new `Woff2.kt`, `EpubDocument.kt`
  (`buildFontRegistry` currently skips `.woff2`).
- **Problem:** WOFF2 needs Brotli. Pure-Kotlin Brotli decode is large but
  bounded (~1500 lines, static dictionary ~120 KB). Decide by measurement: if
  a static dictionary blob in core is unacceptable for binary size, gate it:
  put Brotli+WOFF2 in `:kitepdf-epub` (not core), include the dictionary as a
  Base64 string constant in a separate file, and document the size cost in the
  progress log. Then implement WOFF2 table reconstruction (transformed glyf/
  loca) per the W3C spec.
- **Acceptance:** round-trip test: take a corpus book with a `.woff2` face
  (grep the 18 books' OPFs; if none has one, convert one of its .ttf fonts
  with `woff2_compress` if available, else mark BLOCKED), assert the face
  registers and a page using it renders non-blank with the embedded outlines
  (`hasOutlines == true` in the recorded draw calls).

### T-48. EPUB: incremental layout (open-time responsiveness)

- **Files:** `kitepdf-epub/.../EpubDocument.kt`, `Paginator.kt`,
  `BoxLayout.kt`.
- **Problem:** first `pages` access lays out the ENTIRE book synchronously.
  A large book blocks for seconds before page 1 appears.
- **Do exactly this:** restructure `pageRenders` to lay out PER SPINE
  DOCUMENT lazily: pages of spine k are computed when first requested; page
  counts before full layout are estimates. Since `KiteDocument.pageCount`
  must stay stable for the viewer, do it in two modes: keep the current eager
  behavior as the default, add
  `EpubSettings.lazyLayout: Boolean = false`, and when true expose
  `pageCount` as the laid-out-so-far count plus a `layoutProgress:
  Float`/`isLayoutComplete` pair, and lay out remaining spines on access.
  Wire nothing into the viewer yet (a reader app can drive it). This keeps
  the change additive and honest about the tradeoff.
- **Acceptance:** test with a 3-spine fixture: `lazyLayout = true` gives
  pages of spine 0 without touching spine 2 (instrument via a counting
  layout hook or by corrupting spine 2's HTML and asserting no throw until
  its pages are requested); default mode behavior unchanged (full sweep test
  `EpubRenderTest` green).

---

## PHASE 4E: EPUB DEPTH (from skeleton reader to book engine)

Added 2026-07-09 after a line-by-line re-read of `:kitepdf-epub`. The 4140-page
corpus sweep proves the handler never crashes; it does NOT prove fidelity. The
tasks below fix silent wrong output first (T-60, T-61, T-64), then close the
feature gap to a real reader. Everything here obeys the ground rules (R1-R8),
especially R3 (zero dependencies) and R6 (lenient salvage). The corpus gate for
this phase: the EPUB sweep (`EpubRenderTest` full-corpus run) must keep
rendering every page of all 18 books; record page-count diffs per task in
`FABLE5_AUDIT_PROGRESS.md` (some tasks legitimately change page counts; say
which books and why).

### T-60. CSS selector correctness: pseudo-classes and sibling combinators

- **Files:** `kitepdf-epub/.../css/Selector.kt`, `css/StyleResolver.kt`,
  `Dom.kt` (`HtmlNode`), `HtmlParser.kt`.
- **Problem (two real over-match bugs):**
  1. `SimpleSelector.matches` never checks pseudo-classes; they only bump
     specificity. So `li:last-child { border: none }` applies to EVERY `li`.
  2. `+` and `~` are not combinators in `Selector.parse`: in `p + span`, the
     compound tokenizer keeps `p`, silently discards `span` (the `else -> i++`
     tolerance branch), and the rule matches every `p`.
- **Do exactly this:**
  1. Give `HtmlNode.Element` a `var parent: HtmlNode.Element? = null`, set by
     `HtmlParser` when a child is appended. Sibling and index queries become
     walks of `parent.children` (elements only, per spec, for `nth-child` and
     `-of-type`).
  2. Implement real matching for: `:first-child`, `:last-child`,
     `:only-child`, `:nth-child(odd|even|An+B)`, `:first-of-type`,
     `:last-of-type`, `:empty` (element children and non-whitespace text both
     count as non-empty), `:root`, `:not(<simple selector>)` (one compound
     argument, no nesting), and `:link` (matches `a[href]`; `:visited`,
     `:hover`, `:focus`, `:active` never match in a paginated renderer).
  3. An UNKNOWN or unimplemented pseudo-class makes the whole selector never
     match (CSS invalid-selector behavior), replacing today's always-match.
  4. Add `Combinator.NEXT_SIBLING` (`+`) and `Combinator.SUBSEQUENT_SIBLING`
     (`~`) to parse and to `Selector.matches` (sibling walks via the new
     parent pointer).
  5. The UA sheet's `a:link` rule currently colors every `<a>` by accident;
     after this task it must still color anchors WITH `href`. Keep it as
     `a:link` plus the new correct matching.
- **Acceptance:**
  - New `commonTest` `SelectorMatchTest`: one case per pseudo-class and per
    combinator, plus the two regression cases above (`li:last-child` styles
    only the last item; `p + span` styles no `p`).
  - Unknown pseudo-class test: `p:target { color: red }` changes nothing.
  - Full EPUB corpus sweep still renders every page; style diffs are expected
    (rules now correctly narrowed): record per-book page-count changes.

### T-61. Per-glyph font fallback (kill the tofu)

- **Files:** `kitepdf-epub/.../BoxLayout.kt` (`tokenize`/`cellFor`),
  `FontRegistry.kt`.
- **Problem:** `cellFor` maps a char through the matched embedded face
  unconditionally; a codepoint absent from that face's cmap yields gid 0
  (`.notdef`) and paints tofu. Books embedding Latin-subset faces break on
  curly quotes, Greek, CJK, symbols.
- **Do exactly this:** when the matched face returns gid 0 for a non-space
  char: (a) try the other registered faces with the same bold/italic, then any
  face containing the codepoint; (b) if none, build the generic
  `FontMetrics`-path cell (face null, gid -1) so the renderer's system-font
  fallback draws it. `Cell.face` is already per-cell and the shaping passes
  already guard on single-face words, so mixed-face words degrade gracefully
  (no ligatures/kerning across faces, which is correct).
- **Acceptance:** `commonTest`: a registry whose only face lacks U+201C and
  U+4E2D; a run containing both produces cells with `face == null` (not
  gid 0). Corpus assertion: render one real book and assert no drawn
  `TextGlyph` has `gid == 0` while `hasOutlines` is true (RecordingCanvas
  scan).

### T-62. EPUB structured text + search

- **Files:** `kitepdf-core/.../KitePage.kt`, new
  `kitepdf-epub/.../EpubStructuredText.kt`, `LayoutBox.kt`
  (`PositionedLine`/`PlacedRun`).
- **Problem:** extraction/search/selection exist only for PDF. `EpubPage`
  exposes nothing, so T-30's search and any selection UI are PDF-only. The
  data already exists at layout time (placed runs carry text, x, width).
- **Do exactly this:**
  1. Core seam: `KitePage` gains
     `public fun textContent(): KiteStructuredText? = null` with a minimal
     core model: `KiteStructuredText(blocks)`, block = lines, line = text +
     one `Rectangle` in display space (top-left y-down, like
     `displayToDeviceBase`'s output convention; document it).
  2. `EpubPage.textContent()` builds it from its `PageRender` lines (the same
     margin/yUp math `renderTo` uses; extract a shared helper, do not
     duplicate).
  3. `EpubDocument.search(needle, ignoreCase = true): Sequence<KiteSearchHit>`
     mirroring T-30's semantics (hyphen-rejoin across lines, no cross-block
     matches). If the char-offset-to-quad walker factors cleanly into core,
     share it with T-30; otherwise duplicate the small matcher and note it in
     the progress log.
- **Acceptance:** fixture book: page-0 extraction contains the first
  paragraph verbatim; a phrase spanning a line break is found; quads lie
  inside the page box. PDF pages: `textContent()` may stay null for now
  (adapter is future work; KDoc says so).

### T-63. Anchors, internal links and href-to-page mapping

- **Files:** `kitepdf-epub/.../BoxBuilder.kt`, `InlineRun.kt`, `LayoutBox.kt`,
  `Paginator.kt`, `EpubDocument.kt`.
- **Problem:** `<a href>` is styled but inert: no link rects on pages, no
  anchor (`id`) positions. TOC navigation can only target a spine's first
  page (T-25 step 4's PARTIAL note) and T-32's viewer tap skips EPUB.
- **Do exactly this:**
  1. Thread `href` through `InlineRun` into `PlacedRun`; `PageRender` collects
     per-page `EpubLink(rect, href)` (display space, one rect per line
     touched); expose `EpubPage.links: List<EpubLink>`.
  2. During box building record element `id` (and legacy `<a name>`) onto its
     box; after layout+pagination build `(spinePath, fragment) -> pageIndex`.
     Expose `internal fun pageIndexOfHref(href: String): Int?` (no fragment:
     the spine's first page). This is exactly what T-25 step 4 needs; T-25
     consumes it instead of its first-page fallback.
  3. Note in T-32: once this lands, remove its "EPUB pages: skip" guard and
     navigate internal links via `pageIndexOfHref`; external hrefs go to
     `onLinkTap`.
- **Acceptance:** fixture: an intra-book link on spine 0 targeting an `id`
  deep in spine 2 resolves to the exact page (assert it is NOT spine 2's
  first page); `EpubPage.links` contains a rect covering the link text; TOC
  entries with fragments map to exact pages in `MetadataTocTest`.

### T-64. Ruby annotations (stop corrupting CJK text)

- **Files:** `kitepdf-epub/.../css/UaStylesheet.kt`, `BoxBuilder.kt`,
  `BoxLayout.kt`.
- **Problem:** no ruby handling at all:
  `<ruby>漢字<rt>かんじ</rt></ruby>` renders "漢字かんじ" inline in every
  Japanese book. Silent text corruption, the worst class of bug.
- **Do exactly this, two commits:**
  1. Commit 1 (one line, do it first): UA rules `rt{display:none}` and
     `rp{display:none}`. Readings are dropped, base text is correct.
  2. Commit 2 (real ruby): `BoxBuilder` recognizes `ruby`/`rb`/`rt`; the base
     lays out normally and the reading becomes a second run at 0.5em centered
     over the base's cells (negative-shift `PlacedRun`); a line containing
     ruby grows its height by the ruby ascent; a reading wider than its base
     pads the base advance symmetrically.
- **Acceptance:** commit 1: a ruby paragraph's rendered glyph sequence
  contains 漢字 and does NOT contain かんじ inline (RecordingCanvas text
  scan). Commit 2: golden layout numbers for base and ruby y positions;
  non-ruby books byte-identical (hash recorded draws).

### T-65. Generated content: ::before / ::after

- **Files:** `kitepdf-epub/.../css/Selector.kt`, `css/CssParser.kt`,
  `css/StyleResolver.kt`, `BoxBuilder.kt`.
- **Problem:** `Selector.parse` returns null for any selector with a
  pseudo-element, so `h2::before{content:"Chapter "}` vanishes. Books use
  this for chapter labels, footnote markers, quote glyphs.
- **Do exactly this:** keep pseudo-element selectors, tagged
  `BEFORE`/`AFTER` (others still dropped). `StyleResolver` computes an
  optional (style, content) pair per element for each side. Support `content:`
  string literals (with CSS escapes like `\201C`) and `attr(x)`; a
  `counter()`/`url()` value makes the rule inert (document why). `BoxBuilder`
  injects the content as a synthetic first/last inline run of the element
  (block-display pseudo styles: a synthetic block child). Pseudo-element
  counts in the type bucket of specificity.
- **Acceptance:** `q::before{content:"\201C"}` renders the quote mark;
  `attr(title)` case works; a `counter()` rule changes nothing; existing
  corpus sweep unchanged (no book relies on it yet: verify and note).

### T-66. Inline images and floats

- **Files:** `kitepdf-epub/.../BoxBuilder.kt` (`processInline` drops `img`;
  the "Phase 5" comment there is stale), `BoxLayout.kt`, `Paginator.kt`.
- **Problem:** an `<img>` inside a paragraph is silently DROPPED (content
  loss: inline icons, formula images, decorative separators).
  `float:left/right` is unsupported, so floated cover images and drop caps
  stack centered full-width instead of having text wrap.
- **Do exactly this:**
  1. Inline images: an inline `img` becomes an image token/cell sized from
     CSS/attrs (capped to content width), bottom on the baseline
     (`vertical-align` baseline only), advancing the pen; carried through
     `PositionedLine` as a `PlacedImage` parallel to `PlacedRun`; line height
     grows to fit. Paint in `EpubPage.renderTo` next to the run loop.
  2. Floats: parse `float`/`clear` into `ComputedStyle`. In `layoutBlock`, a
     floated child lays out at its shrink-to-fit or explicit width against
     the left/right content edge at the current y and registers an exclusion
     band (x-range, y-range). `layoutInline` takes the exclusion list and
     shortens each affected line's available width; `clear` drops the cursor
     below matching floats. Simplifications (comment them): same-side floats
     stack downward, never side by side; floats do not escape their
     containing block; pagination treats a float as one unbreakable unit.
- **Acceptance:** fixture A: paragraph with an inline image: no content loss,
  image on baseline, line height grew. Fixture B: `float:left` image with a
  long paragraph: lines beside the float are narrower (assert x/width),
  lines below are full width. Corpus sweep: record page-count diffs (floats
  will legitimately reflow some books; eyeball two and note findings).

### T-67. Inline typography: text-transform, letter/word-spacing, small-caps

- **Files:** `kitepdf-epub/.../css/StyleResolver.kt`, `css/ComputedStyle.kt`,
  `BoxBuilder.kt`, `BoxLayout.kt`.
- **Problem:** `text-transform`, `letter-spacing`, `word-spacing`,
  `font-variant: small-caps` are all ignored.
- **Do exactly this:** `text-transform` (uppercase/lowercase/capitalize)
  applied when runs are built (`Inline.makeRun`); `letter-spacing` /
  `word-spacing` as point lengths in `ComputedStyle`, added to cell/space
  advances in `tokenize` and included in the justify slack math; small-caps
  via the face's `smcp` GSUB feature when present (`substSingle`), else
  synthesized (lowercase drawn as uppercase at 0.8x size).
- **Acceptance:** one unit test per property; a justify test with nonzero
  letter-spacing still fills the line; an `smcp` test with a real feature-
  bearing face (corpus font if one has it, else synthesized table).

### T-68. Box model completeness pass

- **Files:** `kitepdf-epub/.../css/StyleResolver.kt`, `css/CssParser.kt`,
  `BoxLayout.kt`, `EpubDocument.kt` (`collectAuthorCss`).
- **Problem, one line each (all currently ignored):** `min-width`,
  `min-height`, `max-height`; percentage `height`; `position:relative`
  offsets (parsed, never applied); `object-fit: cover` (renders as fill);
  the `font:` shorthand; `@import`.
- **Do exactly this:** min/max clamps in `layoutBlock`/`layoutImage`;
  percentage height resolves against the page content height (comment the
  choice); relative = paint-time offset of the box and its subtree (no
  reflow, per CSS); cover = scale-to-fill and center-crop (needs a clip:
  `pushClip` on the shared canvas); expand `font:` shorthand
  (style/weight/size[/line-height]/family subset) in `expandShorthand`;
  resolve `@import url(...)` zip-relative through `collectAuthorCss`, depth
  cap 8, visited-set cycle guard.
- **Acceptance:** one focused test per item (6); corpus sweep diffs recorded.

### T-69. Table completeness

- **Files:** `kitepdf-epub/.../BoxBuilder.kt`, `BoxLayout.kt` (`layoutTable`),
  `css/UaStylesheet.kt`.
- **Problem:** `<col>`/`<colgroup>` widths ignored; `border-collapse`
  ignored; cell `vertical-align` middle/bottom unsupported (always top);
  `<caption>` renders wherever it sits instead of above the table; the HTML
  presentational attributes `border`/`cellpadding`/`cellspacing` (common in
  older books) are ignored.
- **Do exactly this:** `col`/`colgroup` `width` pins those columns before the
  pref/min distribution; `border-collapse: collapse` paints each shared edge
  once (wider edge wins); cell valign as a top offset when finalizing pass B
  (heights are known); caption extracted and laid above the table box;
  `table[border]`/`[cellpadding]`/`[cellspacing]` mapped to per-cell border
  and padding defaults as presentational hints (author origin, specificity
  0, so any real CSS overrides them).
- **Acceptance:** extend `TableTest` with golden numbers per feature; a
  corpus book with tables eyeballed and noted in the progress log.

### T-70. Language-aware hyphenation

- **Files:** `kitepdf-core/.../text/Hyphenator.kt` plus new per-language
  pattern files, `kitepdf-epub/.../BoxLayout.kt`, `EpubDocument.kt`.
- **Problem:** only en-US patterns exist and `BoxLayout` hardcodes
  `Hyphenator.enUs()`. `hyphens: auto` on a German or French book breaks
  words with English patterns: reader-visible wrong hyphens.
- **Do exactly this:** add TeX-derived pattern sets for de-1996, fr, es, it,
  pt, nl as separate data files in core (same compact format as `EN_US`;
  standard TeX hyphenation patterns are freely licensed, cite source and
  licence in each file header). Language selection: `xml:lang`/`lang` on
  `html`/`body` of the spine, else `dc:language`, mapped to the nearest set,
  default en-US. One hyphenator per document (dominant language; per-spine
  is the follow-up, note it).
- **Acceptance:** 5 known words per language assert exact break-point sets;
  en-US output byte-identical; the size delta of the `:kitepdf-epub` +
  `:kitepdf-core` jars recorded in the progress log.

### T-71. CJK justification + kinsoku openers

- **Files:** `kitepdf-epub/.../BoxLayout.kt`.
- **Problem:** justify distributes slack only across spaces, so spaceless CJK
  lines never justify (ragged right edge; every real reader justifies
  these). Kinsoku handles closers only: an opener (「『（【 etc.) can land at
  a line end, forbidden by JIS rules.
- **Do exactly this:** on a justified line with zero interior spaces and at
  least 2 CJK cells, distribute slack evenly between cells
  (inter-character); add a no-break-after opener set in `tokenize` (an
  opener binds to the following char, mirroring the closer logic); extend
  `CJK_CLOSERS` with small kana and the prolonged sound mark (see MuPDF's
  kinsoku tables for the exact set).
- **Acceptance:** a pure-CJK justified fixture: every line but the last
  within 0.5pt of content width; no line's last cell is an opener
  (structural assert); Latin corpus rendering unchanged.

### T-72. Vertical writing (tategaki) [do last in this phase]

- **Files:** `kitepdf-epub/.../BoxLayout.kt`, `Paginator.kt`,
  `EpubDocument.kt`, `LayoutBox.kt`.
- **Problem:** `writing-mode: vertical-rl` is parsed into `ComputedStyle` and
  never read by any layout code. Japanese novels render horizontally.
- **Scope warning:** the second-largest task in this document after T-44.
  Gate everything on the spine root/body resolving vertical; horizontal
  books must be provably unchanged.
- **Do exactly this:** when the spine root is `vertical-rl`: swap the layout
  axes (the line-length budget is the page content HEIGHT; columns advance
  right-to-left; pagination slices on x). CJK glyphs upright; Latin runs
  rotated 90 degrees via the text matrix at paint time; vertical advances
  from `vhea`/`vmtx` when the face has them, else 1em per glyph; kinsoku
  unchanged; ruby (T-64) sits to the RIGHT of its base column. Page
  progression: `metadata.rightToLeft` already exists; verify the viewer
  honors it and note the finding.
- **Acceptance:** a synthetic vertical fixture with golden glyph positions;
  a real vertical book if any of the 18 corpus books is one (else mark that
  bullet BLOCKED and say so); every horizontal corpus book's recorded draw
  stream hash-identical before/after.

### T-73. Reader settings surface

- **Files:** `kitepdf-epub/.../EpubDocument.kt` (`EpubSettings`),
  `css/StyleResolver.kt`.
- **Problem:** `EpubSettings` is page geometry only. A real reader needs:
  font family override, line-height scale, night-mode colors, justify
  toggle, publisher-CSS toggle. All are one cascade layer once the hook
  exists.
- **Do exactly this:** extend `EpubSettings` with
  `fontFamily: GenericFont? = null`, `lineHeightScale: Double = 1.0`,
  `textColor: RgbColor? = null`, `backgroundColor: RgbColor? = null` (page
  paints it under everything), `justify: Boolean? = null`,
  `usePublisherCss: Boolean = true`. Implement as a new `Origin.READER` rank
  above author-important in `StyleResolver.weight`, built from the settings;
  `usePublisherCss = false` drops author rules (UA + reader only).
  `withSettings` already re-lays-out; nothing else changes.
- **Acceptance:** one test per setting asserting the computed style or paint
  change; all-default settings render byte-identical to today.

---

## PHASE 5: TESTING, CI, DOCS, RELEASE

### T-50. Multi-target CI

- **Files:** `.github/workflows/*.yml`.
- **Current:** CI builds + tests JVM only (per CHANGELOG).
- **Do exactly this:** extend the matrix: (a) `macos-latest` job running
  `:kitepdf-core:iosSimulatorArm64Test :kitepdf-pdf:iosSimulatorArm64Test
  :kitepdf-epub:iosSimulatorArm64Test` and `macosArm64Test` where test tasks
  exist; (b) JS: `:kitepdf-core:jsNodeTest :kitepdf-pdf:jsNodeTest`; (c) keep
  the Linux JVM job as the required gate, others `continue-on-error: false`
  but only on main pushes if minutes are a concern (state the choice in the
  workflow comment). Cache Gradle + Konan (`~/.konan`).
- **Acceptance:** a green run on all jobs (push and observe, or if you cannot
  observe CI, validate locally: run the exact task lists for the mac job on
  this machine, and `jsNodeTest` too; paste results in the progress log).

### T-51. Fuzz-ish robustness harness

- **Files:** new `kitepdf-native-renderer/src/jvmTest/.../difftest/MutationFuzzTest.kt`.
- **Do exactly this:** deterministic mutation fuzzer: take each corpus PDF +
  the 10 generated fixtures, apply N=200 seeded random mutations each (byte
  flips, truncations at random offsets, region shuffles of 64-byte windows;
  seed fixed at 42 for reproducibility), and for each mutant: `PdfDocument.open`
  (both password-less and repair paths) + render page 0 to `NoopCanvas` inside
  a 10-second watchdog. PASS = no uncaught exception other than
  `PdfFormatException`/`WrongPasswordException`/`KiteFormatException`, no
  timeout, no OOM (cap the heap: fork the test JVM with `-Xmx512m` via a
  Gradle test task property or run the loop within a memory-observing
  try/catch for `OutOfMemoryError` and fail with the mutant's seed). On
  failure, write the mutant bytes to `build/fuzz/failing-<seed>-<i>.pdf` and
  fail with that path in the message.
- **Acceptance:** the suite passes on the current engine (fix any crashes it
  finds FIRST, each as its own commit with the mutant as a regression
  fixture in `commonTest` resources); runtime under 5 minutes (tune N down if
  needed, floor N=50); wired into the normal jvmTest run, not opt-in.

### T-52. Writer round-trip property tests

- **Files:** new `kitepdf-pdf/src/commonTest/.../WriterRoundTripPropertyTest.kt`.
- **Do exactly this:** seeded pseudo-random document generator (pages 1..20,
  random page sizes, text runs with random standard fonts/sizes/positions,
  rects, RGB/CMYK/Gray colors, optional incremental edit pass that stamps +
  sets info): for each of 25 seeds: build → open → assert structural
  invariants (page count, media boxes, extracted text contains every emitted
  string in order per page, info round-trips) → `saveIncremental` after an
  edit → reopen → assert original content preserved + edit present. Pure
  common code, no oracle needed (mutool coverage already exists in
  `WriterOracleTest`).
- **Acceptance:** green across 25 seeds; failures print the seed.

### T-53. Documentation truth pass + 0.2.0 release notes

- **Files:** `docs/*.md`, `README.md`, `CHANGELOG.md`,
  `docs/recipes.md`.
- **Do exactly this:** after Phases 0-4 land, sweep every doc page against the
  actual API (every code sample must compile: extract them into a scratch
  `sample`-module test file, compile, fix docs until green, then delete the
  scratch). Write the `## [0.2.0]` CHANGELOG section: module split, renames
  (T-23/T-24 with a migration table old-import → new-import), new features
  (search, links, shadings, Type3, JPX...), and the thread-safety guarantee.
  Update `docs/platforms.md` support matrix per target with what CI actually
  tests after T-50.
- **Acceptance:** zero stale samples (state how you verified), CHANGELOG
  complete, migration table present.

### T-54. Release 0.2.0 (only when the user says go)

- Do NOT publish anything to Maven Central autonomously. Prepare everything
  (`CHANGELOG`, version bump to `0.2.0` in `build.gradle.kts` +
  `gradle.properties`, `git tag v0.2.0` UNPUSHED) and then STOP and ask the
  user. Publishing recipe and gotchas are in the maintainer's session notes;
  ask the user for them.

---

## APPENDIX A: THINGS YOU MUST NOT DO

- Do not rewrite working subsystems for style. Every change traces to a task.
- Do not add kotlinx-serialization, okio, ktor, atomicfu, or ANY dependency to
  core/pdf/epub. Compose-viewer may use what Compose already brings
  (kotlinx-coroutines).
- Do not "fix" the lenient parser by making it strict. Strictness regressions
  show up as corpus render failures: the corpus count of rendered pages must
  never drop.
- Do not delete or weaken the pure-Kotlin codecs when adding platform fast
  paths; they are the only implementation on most targets.
- Do not touch `mupdf-master/` or `readium-kt-toolkit/` (read-only reference
  clones).
- Do not change the on-screen rendering defaults (background, spacing, fade
  timings) without a task saying so.
- Do not commit corpus files (git-ignored on purpose; they are user-provided).
- Do not mark a task DONE with failing or skipped acceptance bullets. PARTIAL
  with an honest reason is respected; false DONE is not.

## APPENDIX B: MENTAL MAP (where things live)

- `:kitepdf-core` = the "fitz": geometry (`Matrix`, `Rectangle`), canvas seam
  (`render/PdfCanvas.kt`), fonts (`font/`), image codecs (`render/*Decoder.kt`,
  `ImageXObject`), compression (`compression/`), filters (`filters/`),
  bidi/hyphenation (`text/`), and the `KitePage`/`KiteDocument` seam.
- `:kitepdf-pdf` = PDF handler: lexer/parser/xref/repair (`parser/`), document
  model (`PdfDocument`, `PdfPage`, annotations, forms, outlines...),
  content-stream interpreter (`render/PageRenderer.kt`), crypto (`crypto/`),
  text extraction (`text/`), writer/editor (`writer/`).
- `:kitepdf-epub` = EPUB handler: zip → HTML/CSS parse → box tree →
  `BoxLayout` → `Paginator` → `EpubPage` painting through the shared canvas.
- `:kitepdf-skia-renderer` / `:kitepdf-native-renderer` = raster backends;
  native-renderer also hosts the differential harness (jvmTest `difftest/`).
- `:kitepdf-compose-viewer` = `PdfView` composable + state + rasterizer +
  widgets.
- Oracle: `mutool` (Homebrew). Scoreboard: `DifferentialTest` → 
  `build/difftest/report.md`. Benchmarks: `BenchmarkTest` (env-gated).

## APPENDIX C: SUGGESTED EXECUTION ORDER (flattened)

T-01, T-02, T-03, T-04, T-05 (one sitting) → T-10, T-11 → T-13, T-12 → T-16,
T-17 → T-14, T-15 → T-20, T-21, T-22, T-26, T-27, T-28 → T-23 + T-24 (one
sitting, two commits) → T-25 → T-30, T-31, T-32, T-33, T-34 → T-41, T-40,
T-42, T-43 → T-46, T-45 → T-47, T-48 → T-60, T-61, T-64 (the EPUB correctness
strikes: do these before any more EPUB features) → T-62, T-63, T-65 → T-67,
T-68, T-69 → T-70, T-71, T-73 → T-66 → T-51, T-52 → T-44 (the long one, do it
when everything else is green) → T-72 (the other long one) → T-50, T-53 →
T-54 (ask first).
