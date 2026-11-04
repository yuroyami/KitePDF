# GPT audit of KitePDF

Audit date: **2026-11-04**. Source snapshot: **`c4e7574b143689a8d115aba6dcd605cc4cc909f3`**. Declared release: **0.13.0**. This is the requested audit review file; no GitHub issues, pull requests or production fixes were made.

## Assessment

The audit found **52 actionable defects or verification defects: 17 P1, 34 P2 and 1 P3**. The strongest findings demonstrate failures when individually supported features are combined: encrypted PDFs with object streams; writer save/restore with font encoding; redaction with inherited resources or replacement text; transparency groups with masks/blend modes; Compose tiling with live forms; and offscreen EPUB composition with a small layout budget. Several scripting failures were reproduced in the released 0.13.0 binary, not just an experimental build.

The current evidence does **not** support a claim of perfection, identical behavior across all targets, every ebook format, or performance superiority over PDFium, MuPDF and Acrobat. Source sharing helps portability but does not establish semantic equivalence of the actual canvases, schedulers, runtime libraries and integration paths. Specific counterexamples are recorded below.

This is a broad audit pass across the requested lanes, not an exhaustive proof over the approximately 572 production Kotlin files and 513,602 lines, including generated data. It combines source review, bounded functional reproductions, independent-reader comparisons, current-toolchain tests, published-artifact inspection and explicit coverage gaps. No nanosecond measurements, device-wide parity certification, complete parameter Cartesian product, or exhaustive upstream audit is claimed. Remaining verification work and opportunities are kept separate from defects.

## Reading the findings

P1 means high-priority data integrity, availability, ownership, privacy or trust-contract failure. P2 means substantive fidelity, API, lifecycle, performance or integration behavior. P3 means lower-impact diagnostic/documentation correctness. Priority is separate from evidence strength.

An **executed reproduction** states exactly which implementation and dependencies ran. A **source-established finding** traces a concrete input/parameter path and its consequence but does not claim a full-device reproduction. A **coverage gap** or **candidate** is not promoted as a defect. Proposed changes and regression tests are recommendations, not work already performed. Findings have not been exhaustively deduplicated against the entire historical issue tracker; an existing issue (#609) was read to match the project's evidence/remediation style.

All 52 promoted findings currently belong to **KitePDF**, including the two image metadata defects: the pinned ImageKodec API already supports the required behavior, and KitePDF selects defaults that discard it. Possible generic KiteJS cancellation work and component-preserving ImageKodec APIs remain explicitly upstream follow-ups, not excuses to duplicate those engines downstream. The adjacent ImageKodec checkout is older than KitePDF's pinned release, so codec conclusions use the exact 0.3.0 artifact instead.

## Finding index

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| [C-01](#C-01) | P1 | Password form values are published as plaintext accessibility descriptions | Source established |
| [C-02](#C-02) | P2 | `userScrollEnabled=false` still allows edge drags to turn a zoomed page or spread | Source established |
| [C-03](#C-03) | P2 | Switching live forms on or off leaves deep-zoom tiles painted with the old widget policy | Source established |
| [C-04](#C-04) | P2 | Tile and thumbnail effects keep old font environments after the main page refreshes | Source established |
| [C-05](#C-05) | P2 | Scripted EPUB changes refresh pixels but leave stale accessible text and link targets | Source established |
| [C-06](#C-06) | P2 | Cancelling a pending text keystroke accepts and persists it as a successful fallback | Isolated function probe |
| [C-07](#C-07) | P2 | Delayed text keystrokes overwrite newer host resets or script writes | Isolated function probe |
| [C-08](#C-08) | P2 | Replacing `scripts` while editing keeps sending keystrokes to the old handler | Source established |
| [C-09](#C-09) | P2 | Supersampled tiles reuse the capped bitmap's hairline width and thin strokes incorrectly | Arithmetic probe |
| [C-10](#C-10) | P2 | Read-only text fields, checkboxes and radio buttons are advertised as enabled actionable controls | Source established |
| [C-11](#C-11) | P2 | Vectorized offscreen EPUB pages repeatedly relayout chapters that the memory budget immediately evicts | Source + released cache probe |
| [JS-01](#JS-01) | P1 | A script that fails before event dispatch copies the previous field's result into another field | Current source + released binary |
| [JS-02](#JS-02) | P1 | Lazy PDF engine bootstrap spends the time since runner construction and fails after five idle seconds | Current source + released binary |
| [JS-03](#JS-03) | P1 | The document-wide script budget does not count the script currently running | Current source + released binary |
| [JS-04](#JS-04) | P1 | Closing a PDF runner from its own callback abandons an open native engine | Current source + released binary |
| [JS-05](#JS-05) | P2 | Cancelling a runner coroutine does not stop its script or its side effects | Current source + released binary |
| [JS-06](#JS-06) | P2 | Clearing a timeout from another due timer does not cancel its callback | Current source + released binary |
| [JS-07](#JS-07) | P2 | The public `keystroke(commit=true)` argument is ignored | Current source + released binary |
| [JS-08](#JS-08) | P2 | An erroring interval causes unbounded diagnostic retention and quadratic copying | Source established |
| [MEDIA-01](#MEDIA-01) | P2 | Remote playback cannot recover when `allowRemote` changes from false to true | Source established |
| [MEDIA-02](#MEDIA-02) | P2 | Changing `session` leaves an active player registered with the old session | Source established |
| [PDF-01](#PDF-01) | P1 | Valid encrypted PDFs with object streams cannot open | Current-source probe |
| [PDF-02](#PDF-02) | P1 | `ContentStreamBuilder.save()/restore()` restores the PDF font but not the writer's string encoding | Current-source probe |
| [PDF-03](#PDF-03) | P2 | Linear text extraction ignores restored font encodings after `Q` | Current-source probe |
| [PDF-04](#PDF-04) | P1 | Redaction removes glyphs but retains their `/ActualText` replacement | Current-source probe |
| [PDF-05](#PDF-05) | P1 | Redacted images survive through inherited `/Pages /Resources` | Current-source probe |
| [PDF-06](#PDF-06) | P1 | Redaction ignores line widths and miter limits set through ExtGState | Current-source probe |
| [PDF-07](#PDF-07) | P1 | Xref-stream negative widths bypass decoded-data limits | Source established |
| [PDF-08](#PDF-08) | P1 | Object-stream counts allocate before being checked against available headers | Source established |
| [PDF-09](#PDF-09) | P1 | Signature trust accepts a non-CA certificate as an issuer | Source established |
| [RENDER-01](#RENDER-01) | P2 | Negative tiling-pattern steps make valid patterned fills disappear | Current-source probe |
| [RENDER-02](#RENDER-02) | P2 | The plain Kotlin raster canvas ignores isolation and knockout parameters | Current-source probe |
| [RENDER-03](#RENDER-03) | P2 | A translucent subgroup does not knock out its preceding sibling | Current-source probe |
| [RENDER-04](#RENDER-04) | P2 | Soft masks erase the backdrop needed by a masked blend operation | Current-source probe |
| [RENDER-05](#RENDER-05) | P2 | A failed offscreen render leaves the plain raster canvas drawing into an orphan layer | Current-source probe |
| [RENDER-06](#RENDER-06) | P1 | Tiling work-limit arithmetic overflows before conversion to Long | Arithmetic + source |
| [FMT-01](#FMT-01) | P1 | CBZ page measurement escapes salvage on an overflowing TIFF directory offset | Current-source subset probe |
| [FMT-02](#FMT-02) | P2 | Valid legacy ZIP filenames collapse under unconditional UTF-8 decoding | Current-source subset probe |
| [FMT-03](#FMT-03) | P1 | SVG reference depth does not bound exponentially repeated drawing work | Current-source subset probe |
| [FMT-04](#FMT-04) | P1 | SVG keeps every named filter result, even when no later primitive reads it | Current-source subset probe |
| [FMT-05](#FMT-05) | P2 | Rendering a symbol skips its own style and viewport | Current-source subset probe |
| [FMT-06](#FMT-06) | P2 | A missing local font source prevents every later fallback from being tried | Source established |
| [FMT-07](#FMT-07) | P2 | Font source selection changes author precedence based on filename extension | Source established |
| [FMT-08](#FMT-08) | P2 | A global CSS import visited set removes valid repeated imports and changes the cascade | Source established |
| [FMT-09](#FMT-09) | P2 | Remote-resource work has no book disposal or concurrency bound | Source established |
| [CODEC-01](#CODEC-01) | P2 | Convert standalone image samples to the viewer's color space before discarding their profile | Pinned codec probe + bridge source |
| [CODEC-02](#CODEC-02) | P2 | Apply image orientation consistently to decoding and intrinsic dimensions | Pinned codec probe + bridge source |
| [DIST-01](#DIST-01) | P2 | The README's headless Skia recipe omits its required JVM native runtime | Published artifact inspection |
| [DIST-02](#DIST-02) | P2 | Java 21 bytecode is published without a JVM-version constraint or a clear consumer requirement | Published artifact inspection |
| [CI-01](#CI-01) | P2 | Selecting an unsupported web test module succeeds without running tests | Current Python runner reproduced |
| [CI-02](#CI-02) | P2 | Web tests choose npm versions from unrelated cached dependencies | Current Python runner reproduced |
| [BENCH-01](#BENCH-01) | P3 | The generated benchmark report labels minimum timings as medians | Source established |

## Executed validation

The regular Kotlin Toolchain **0.13.0**, Kotlin **2.4.21**, JDK **21** JVM build succeeded after configuring Java to use the managed environment's existing HTTP proxy. Initial network failures were environmental; stale pre-audit build outputs were never counted as fresh passes. Targeted scratch probes used the compiler/dependency versions stated in each lane, and do not substitute for full-target tests.

| Check | Passed | Aborted / unavailable | Failed |
| --- | ---: | ---: | ---: |
| Current `kitepdf-core` JVM suite | 457 | 2 | 0 |
| Current `kitepdf-pdf` JVM suite | 873 | 5 | 0 |
| Current `kitepdf-epub` JVM suite | 876 | 43 | 0 |
| Current `kitepdf-cbz` JVM suite | 34 | 0 | 0 |
| Current `kitepdf-svg` JVM suite | 118 | 0 | 0 |
| Current `kitepdf-xps` JVM suite | 41 | 1 | 0 |
| Current `kitepdf-compose-viewer` JVM suite | 471 | 0 | 0 |
| Current `kitepdf-javascript` JVM suite | 201 | 4 | 0 |
| Public-corpus Python tool tests | 11 | 0 | 0 |

The eight Kotlin suites total **3,071 passes, 55 aborted tests and zero failures**. Core's two missing cases require full Unicode bidi files. PDF's five require GoldenHour, a discoverable mutool, DroidSansFallback, or OpenJPEG's encoder. EPUB's 43 primarily require reference fonts, HarfBuzz and full normalization/IDNA/encoding files; one is an opt-in benchmark. XPS's one requires a discoverable mutool. Scripting's four are opt-in KiteJS/Doom probes. MuPDF **1.27.2** was nevertheless invoked explicitly in the independent scratch probes, so those oracle comparisons did execute. Passing existing suites does not contradict the new findings: the implicated cross-products are not asserted by those suites.

Public corpus verification checked **95 documents, 95,551,335 bytes**, with manifest SHA-256 `d3e563f25fc033d1658d82e15d06a879b969c87fe19f56f773589c658c581aaa`. All listed sizes/hashes matched. Maven Central metadata was inspected for all 13 public KitePDF artifacts at 0.13.0. That is artifact availability/metadata evidence, not successful consumption on every platform.

Commands: `./kotlin test -m kitepdf-core -p jvm`; `./kotlin test -m kitepdf-pdf -m kitepdf-epub -m kitepdf-cbz -m kitepdf-svg -m kitepdf-xps -p jvm`; `python3 -m unittest discover -s tools -p test_corpus.py -v`; `python3 tools/corpus.py verify`. Supporting logs remain in `/workspace/scratch/audit-core-gate.log` and `/workspace/scratch/audit-format-gate.log`. They are supporting workspace evidence; the findings and their essential reproduction details are contained in this file.

The additional command `./kotlin test -m kitepdf-compose-viewer -m kitepdf-javascript -p jvm` also exited 0. Its log is `/workspace/scratch/audit-ui-script-gate.log`. Existing viewer scene tests were executed, but the newly proposed scene regressions in individual findings were not added or run. No failure was hidden by changing source, expected output, baseline or dependency configuration.

### Pre-commit full JVM gate

Before committing this report, `./kotlin test -p jvm` completed with exit code 0 on the same source snapshot: **3,420 tests passed, 66 were aborted and none failed** across all executed JVM module suites. This is the later, broader run; the table above preserves the initial targeted audit results. No production code, expected output or differential baseline changed.

The AWT differential sweep scored 240 of 282 available pages at 96 DPI, with zero render/comparison failures and mean MAE **0.0012**, unchanged from the pre-existing report at its displayed precision. The committed per-page baseline checks passed; the suite identified the vertical-text page as better than baseline, and no baseline was rewritten for this documentation-only change. Skia scored 211 of 253 available pages across 53 documents, with zero render failures and mean MAE **0.0098**. The EPUB sweep rendered 55 books and 6,178 pages, reporting zero failures and two blanks; its informational worst page-0 MAE was **0.3891**.

The PDFium parity gate completed on 362 of 404 available pages from 199 documents and passed using the existing exemption policy. Its raw classifications included three PDFium-better pages, so a passing gate must not be read as zero discrepancies or universal parity. The existing JVM media and network suites also passed. Specific new regression proposals, device/browser execution and release-performance measurements remain unperformed. Log: `/workspace/scratch/audit-precommit-jvm.log`; detailed differential reports are under `kitepdf-native-renderer/build/` and `kitepdf-skia-renderer/build/`.


## Compose viewer audit lane

Snapshot: KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. Owner of all findings below: **KitePDF**, primarily `kitepdf-compose-viewer`; these are not KiteJS or ImageKodec defects. No source edits, issue writes, or commits were made. Paths below are repository-relative. All findings are source-proven unless a more specific verification level is stated. A source-proven finding establishes the control/data-flow defect, but is not a claim of a completed Android/iOS/web end-to-end reproduction.

<a id="C-01"></a>

### C-01 [P1] Password form values are published as plaintext accessibility descriptions

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageSemantics.kt:101-120,228-231`; compare `KiteFormInput.kt:83-105,239` in the same directory.

**Trigger:** A PDF text widget has the Password field flag (`/Ff 8192`), `KiteDocView` has a `scripts` handler, and its `formState` contains an entered value. Either rendering mode and any page layout are affected once the page builds its accessibility nodes.

**Evidence and impact:** `accessNodes` reads `formState.value(fieldName)` for every non-choice field and stores it unmodified in `PageAccessNode.state`. `describe(TEXT_FIELD)` copies that string to Compose `stateDescription`. There is no password check or password semantics on this separate page node. Thus a masked password is available as plaintext in the semantics tree, and can be exposed by accessibility readout/inspection even when the actual editing field is unfocused. The input path explicitly recognizes the same flag and applies `PasswordVisualTransformation`, so masking that input does not fix the second node.

**Verification:** Source-proven. Minimal regression: extend `PageSemanticsSceneTest` with a `/FT /Tx /Ff 8192 /T (password)` widget, set its form value to a unique secret, render each mode, and assert no node's text, state description or content description contains that secret, with and without keyboard focus. Also check that the node exposes appropriate password semantics.

**Remediation:** Carry a password attribute into `PageAccessNode`, omit/mask its value, and apply password semantics. Audit reading-order/appearance-derived nodes for duplicate disclosure too.

<a id="C-02"></a>

### C-02 [P2] `userScrollEnabled=false` still allows edge drags to turn a zoomed page or spread

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1146-1147,1168,2183-2207`; `KiteGestures.kt:312-330`.

**Trigger:** `layout = Paged(...)` or `Spread(...)`, `userScrollEnabled = false`, default `panEnabled = true`, and a page that overflows after zooming or width fitting. Drag beyond its edge by at least 64 dp.

**Evidence and impact:** The flag gates the Compose pager, keyboard paging, and wheel paging, but both layout branches always pass a non-null `PageTurn` into `kiteTransformGestures`. That handler accumulates `pastEdge` and directly calls `state.turn(...)`, bypassing the disabled pager input. This breaks the documented use of the flag to drive paging exclusively from application controls. Both render modes, both orientations, and reversed reading order retain this bypass.

**Verification:** Source-proven. Existing `ZoomedPanSceneTest.a_drag_past_the_edge_of_a_zoomed_page_turns_it` exercises exactly this edge-turn path but never sets `userScrollEnabled=false`; repeat it with the flag disabled and assert the page stays unchanged, then re-enable it and assert normal turning.

**Remediation:** Pass no `PageTurn` when user scrolling is disabled, or gate edge turning explicitly while preserving the independently enabled pan gesture.

<a id="C-03"></a>

### C-03 [P2] Switching live forms on or off leaves deep-zoom tiles painted with the old widget policy

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:999-1031,1564-1580`.

**Trigger:** A rasterized PDF exceeds `maxBitmapLongSide` and has visible tiles. Change `scripts` between null and a handler without changing the page, geometry, theme or decorator.

**Evidence and impact:** The outer whole-page raster includes `drawsFormLayer` in its effect key, and the shared LRU key contains `withoutWidgets`. However, `PageTiles` keeps a separate `bitmaps` map whose `remember` keys omit `drawsFormLayer`. Its effect restarts on the flag change but skips every existing tile at `if (tile in bitmaps) continue`. Enabling forms therefore leaves stale file widget appearances below the live fields; hiding or clearing a field can leave its old value visible. Disabling forms leaves tiles that omit widgets covering the newly rendered whole-page bitmap, making form fields disappear. The bug persists until another tile-invalidating change or slot disposal.

**Verification:** Source-proven. Reproduce with a one-page PDF containing a visibly colored widget, `Rasterized(maxBitmapLongSide=128)`, a 200 px viewport, and a mutable `scripts` argument. Wait for the tile, toggle the handler, then hide/clear the widget or turn the handler off. Capture tile pixels after settling. Existing `TileSceneTest` covers static cap/zoom but no form-policy transition.

**Remediation:** Include widget suppression in the tile-map identity and make the map/effect/cache share the same complete rendering key.

<a id="C-04"></a>

### C-04 [P2] Tile and thumbnail effects keep old font environments after the main page refreshes

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1564-1565`; `KiteWidgets.kt:182,207-217`; compare `KiteDocView.kt:1404` and `KitePageRasterizer.kt:498-504`.

**Trigger:** Change `LocalFontFamilyResolver` while a page's tiles or thumbnails remain composed, keeping sizes, theme and content version unchanged. This matters when text uses Compose's host-font path, especially web and the explicit `hostTextOffMain=false` path.

**Evidence and impact:** `rememberKitePageRasterizer` correctly creates a new rasterizer when its text measurer/font environment changes, and the main page effect is keyed on it. Neither the tile map/effect nor thumbnail `produceState` is keyed on that rasterizer. They do not call the cache again, so the cache's correct `fontEnvironment` key cannot help. The main bitmap refreshes with the new fonts while opaque tiles or thumbnails continue showing the previous fonts until another key changes. A later asynchronous font arrival is a separate mechanism and does not cover replacing a resolver with an already-resolved environment.

**Verification:** Source-proven. Extend `FontEnvironmentSceneTest` to force tiles and to compose `KiteThumbnailStrip`, using two resolvers with visibly distinct fallback faces. Verify both paths re-render, not just the main `onPageRendered` count.

**Remediation:** Key those effects and the retained tile map on the rasterizer/font environment; apply one rendering-key definition across all screen raster clients.

<a id="C-05"></a>

### C-05 [P2] Scripted EPUB changes refresh pixels but leave stale accessible text and link targets

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageSemantics.kt:155-170,179-181`; `KiteDocViewState.kt:968-976,1054-1059`; `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1084-1093`.

**Trigger:** An already visible EPUB page finishes building its semantics, then a script changes the text or links while the page retains its size and index. A `:target` restyle that hides/reveals content can trigger the same issue without an enabled script handler.

**Evidence and impact:** EPUB intentionally reuses each `EpubPage` object when relayout preserves its size. Rendering observes `contentVersionOf(page)`, which includes `chapterVersion`. `PageSemantics` instead runs once under keys `(page, formState, strings, formRevision)` and never observes the EPUB content version. Its retained nodes keep removed text, obsolete labels and old rectangles indefinitely. A stale link node activates a tap at its old rectangle against the current document, so it can activate a different link from the one the screen reader announces.

**Verification:** Source-proven. Start with one short paragraph/link, wait for its semantic label, mutate its text/href/location through the book's scripting API while keeping a single page, then assert the old label disappears and activating the new node follows the new link in both render modes.

**Remediation:** Invalidate semantics on the same page-content version as rendering, and reject results built for an obsolete version before installing them.

<a id="C-06"></a>

### C-06 [P2] Cancelling a pending text keystroke accepts and persists it as a successful fallback

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteFormInput.kt:187-204,277-294`; compare `ScriptCall.kt:13-18`.

**Trigger:** An ordinary text field calls a suspending `PdfScriptHandler.keystroke`; before it finishes, the input's effect is cancelled, for example by a field switch or leaving composition.

**Evidence and impact:** `askScript` catches `Exception`, including `CancellationException`, and substitutes `edit.applyTo(confirmed)`. It then writes the fallback through `scripts.formState.setValue`. The later `current()` check at the call site runs only after this write and cannot prevent it. A pending keystroke decision is converted into an accepted edit on cancellation, and that stale write can race the blur/commit work. The repository's separate `scriptCall` helper correctly rethrows cancellation, showing the intended cancellation contract.

**Verification:** **Reproduced in an isolated Kotlin mechanism probe**, not a full Compose scene. `/workspace/scratch/audit-compose-probes/Probe.kt` includes the unchanged `askScript` function extracted verbatim from the audited source, with minimal handler/FormState doubles. Kotlin 2.4.10 and coroutines 1.11.0 ran a handler blocked in `awaitCancellation()`, cancelled its calling job, and printed `cancelled keystroke persisted=pending`. The source that returned no accepted value nevertheless caused a write. The fixture is temporary audit evidence, not a production test.

**Remediation:** Rethrow cancellation before the error fallback, check coroutine/session liveness before writes, and explicitly transfer only authorized pending work to the blur owner. Add a scene test with a controllably suspended keystroke handler.

**Scope clarification for C-06:** This cancellation path requires a handler that cooperatively suspends and propagates cancellation. The bundled JVM/Native runner currently blocks cancellation as described in JS-05; its ordinary execution is not the demonstrated cancellation-throw path.

<a id="C-07"></a>

### C-07 [P2] Delayed text keystrokes overwrite newer host resets or script writes

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteFormInput.kt:277-294`.

**Trigger:** An ordinary text field has an outstanding suspending keystroke callback. While it waits, host/external code resets the field or writes a newer accepted value. The old callback then returns.

**Evidence and impact:** The function captures `fieldRevision` before calling the handler, but enforces that revision only for editable choice fields. For text it unconditionally calls `setValue(fieldName, kept)`. The old result replaces a newer change without detecting that its base value became obsolete. The local `KeystrokePipeline` also continues from its old confirmed value, so simply rejecting one stale store is not enough unless the pipeline is reconciled.

**Verification:** **Reproduced with the same isolated original-function probe** as C-06. A handler waited on a `CompletableDeferred`; the probe wrote `new_host_value` while it waited, then released a stale answer. Output: `concurrent host value overwritten=stale_keystroke`. Full Compose and real runner integration remain to be tested.

**Remediation:** Add revision-aware compare-and-set/session checks for ordinary text and refresh/rebase the local pipeline when the externally accepted field value changes. Keep this distinct from C-06: it occurs with no cancellation or exception at all.

**Scope clarification for C-07:** Host/external state mutations and custom suspending handlers are concrete triggers. This does not assert that the same bundled JVM runner executes a timer concurrently inside its serialized engine call.

<a id="C-08"></a>

### C-08 [P2] Replacing `scripts` while editing keeps sending keystrokes to the old handler

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteFormInput.kt:60-71,130-143,187-197`; `KiteDocView.kt:263-266`; `KiteDocViewState.kt:845-860`.

**Trigger:** Keep the same document/state and focused text widget but replace a non-null `scripts` handler with another non-null handler in recomposition, for example when switching script policy or recreating an engine.

**Evidence and impact:** The input composition key is only `(fieldName, formInputGeneration)`. Its `remember` pipeline and `LaunchedEffect(Unit)` survive a handler replacement; the running effect captures the old `scripts` and continues sending edits to its form state. The visible form layer now draws the new handler because `state.scripts` is updated, and blur uses that current handler. Keystroke processing, displayed form state, and final commit therefore refer to different handlers. The old handler can also be retained after the host intends to dispose it.

**Verification:** Source-proven. Use two instrumented `PdfScriptHandler` instances with separate form states and counters. Focus under A, replace `scripts` with B without moving focus, send a keystroke, and assert only B is called and the display and commit use B's state. Test null transitions separately, because leaving the input composition masks this particular non-null-to-non-null defect.

**Remediation:** Make handler identity part of the input/session key and define an explicit, ordered handoff of outstanding edits and focus before replacing it.

<a id="C-09"></a>

### C-09 [P2] Supersampled tiles reuse the capped bitmap's hairline width and thin strokes incorrectly

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1447-1460,1474-1495,1571-1573,1586-1591`.

**Trigger:** `Rasterized(quality > 1f, preserveHairlines=true)` and the requested raster exceeds `maxBitmapLongSide`, so a full-resolution tile covers the capped base bitmap.

**Evidence and impact:** `rasterPlanFor` computes compensation from the **capped** width, `max(1f, raster.width / (base.width * bucket))`. `PageTiles` renders at the **uncapped** `full.width`, but receives this same hairline value. Its bitmap is then reduced by the higher full-resolution scale. Example: a square 200 px slot, zoom 1, quality 2, cap 128 gets a 128 px base with hairline 1, but 400 px tiles also with hairline 1. On screen a tile hairline is 0.5 px rather than the requested preserved 1 px; the thin-stroke floor is reduced likewise. Raising supersampling quality can make fine traces/rules lighter, contrary to `preserveHairlines`.

**Verification:** **Reproduced the sizing arithmetic using the original extracted Kotlin functions** `rasterPlanFor`, `zoomBucket`, and `fitWithin`, with minimal `IntSize`/spec value-type doubles. `/workspace/scratch/audit-compose-probes/HairlineProbe.kt` printed:

```text
quality=1.0 capped=128 tileFull=200 tileHairline=1.0 screenHairline=1.0
quality=2.0 capped=128 tileFull=400 tileHairline=1.0 screenHairline=0.5
quality=4.0 capped=128 tileFull=800 tileHairline=1.0 screenHairline=0.25
```

The widths above are nominal geometric widths, not measured antialias coverage. Pixel oracle reproduction has not run. A regression should render zero-width and subpixel strokes with quality 1/2/4, both above and below the tile cap, and compare integrated coverage after settling. Existing tile tests use default quality and cannot catch this cross-product.

**Remediation:** Calculate a separate tile hairline from its actual raster-to-screen scale, and include that value in the tile rendering/cache key.

<a id="C-10"></a>

### C-10 [P2] Read-only text fields, checkboxes and radio buttons are advertised as enabled actionable controls

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageSemantics.kt:120,228-254`; `KiteFormLayer.kt:218`.

**Trigger:** A PDF field is read-only, either by `/Ff` or by live `formState`, and the viewer exposes its semantic node with a scripts handler.

**Evidence and impact:** `accessNodes` computes `enabled = !formState.isReadOnly(...)`, but `describe` observes it only for `CHOICE`. Text fields, checkboxes, radio buttons and buttons all install an enabled click action and omit `disabled()`. The actual widget tap path refuses read-only edits, so assistive technology announces an actionable control and reports a successful click although the control cannot be changed. This is an accessibility state-contract defect, not a bypass of the read-only value check.

**Verification:** Source-proven. Add read-only variants to `PageSemanticsSceneTest`, assert disabled semantics and absence of a mutating click action, and toggle the live flag to verify the state updates.

**Remediation:** Apply the already-computed enabled state consistently to every field node.

<a id="C-11"></a>

### C-11 [P2] Vectorized offscreen EPUB pages repeatedly relayout chapters that the memory budget immediately evicts

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1141-1155,1175,1688-1694`; `KiteDocViewState.kt:276-277`; `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1096-1102,1121-1139,1242-1244,2060-2064`.

**Trigger:** A multi-chapter EPUB, `EpubSettings(layoutCacheBytes=0)` or a budget too small for the visible chapter plus a neighbor, `KiteRenderSpec.Vectorized()`, and `KiteDocLayout.Paged(offscreenPages=1)` or a spread that composes pages from another chapter offscreen. Use two short one-page chapters to make the boundary immediate.

**Evidence and impact:** Paged mode composes the neighbor but records page geometry only for the current page. `chaptersOnScreen()` pins only chapters represented by that geometry. An offscreen vector slot sees `!page.isContentLoaded`, calls `loadContent()`, and increments `loads`, which is also its effect key. EPUB's `publishLive` immediately trims the live chapter cache; the current chapter is pinned, so the newly loaded unpinned neighbor is the only possible eviction victim. The neighbor therefore remains `isContentLoaded=false` even after a successful load. Incrementing `loads` recomposes and starts the same load again. The viewer continuously relays out an invisible chapter while resting, spending CPU and allocating layout trees; on web the work also runs on the UI thread. The same unconditional loop has no retry bound when a load consistently fails.

**Verification:** Source-proven retry lifecycle, with its immediate-eviction premise additionally reproduced against released EPUB 0.13.0. With two one-page chapters, budget zero and chapter 0 pinned, five successive loads of chapter 1 each returned with chapter 0 resident and chapter 1 nonresident (`offscreen-probe.log`). The automatic Compose loop itself was not executed in a scene. No release timing is claimed. A focused scene regression can wrap the neighboring page's `loadContent` with an atomic counter, pin the first chapter, wait for the visible page, then pump idle frames and assert the count stops growing. Existing `DroppedChapterSceneTest` tests a dropped chapter in Continuous mode and does not exercise a composed but unpinned neighbor.

**Remediation:** Load vector content only when its slot is needed, or maintain explicit ownership for composed neighbor chapters within a separate bounded prefetch policy. Stop immediate retries when a load cannot remain resident; retry on visibility/version changes or explicit bounded backoff rather than a self-incremented effect key.

### Reviewed combinations and remaining gaps

| Area | Source paths/combination reviewed | Outcome / limits |
| --- | --- | --- |
| Layout and input | Continuous/Paged/Spread/SinglePage, both orientations, reversed paging, zoom floor/range, scroll toggle, selection coexistence, edge drag and wheel | C-02; no full gesture matrix executed |
| Raster caches | Whole page vs tiles vs thumbnail, cap/quality/hairline, forms toggle, font environment, remote/chapter version, cache budget | C-03, C-04, C-09; cache-size bounds and whole-page widget/font keys inspected |
| Forms/lifecycle | Live layer vs base raster, input/choice generation, focus/blur, cancellation, external field writes, handler replacement | C-06 through C-08; two isolated coroutine probes executed |
| Accessibility | Text/reading order, links, role/state, password, read-only, EPUB mutation | C-01, C-05, C-10; no physical screen-reader run |
| Coordinate paths | Page/display/viewport mapping, crop/rotation transform use, spread physical placement, fit-width and strip constraints | Static review; interactions during in-flight pager movement still need targeted validation |
| Render backend | ComposeCanvas entry points, image cache ownership, masks/groups, platform dispatch contracts | Partial source review only; no new differential or device raster oracle run |
| Runtime resource bounds | Global raster gate, priority, cancellation handoff, LRU bounds, loading/failure guards | C-11; source lifecycle review plus released cache probe, automatic Compose loop untested; no heap/leak soak or release benchmark |

The report does not establish nanosecond costs, frame-time percentiles, real retained heap on Android/iOS, native renderer parity, or complete public API cross-product coverage. These require release-build device/browser runs. Existing JVM scene tests often have useful individual feature coverage, but the specific transitions above cross their separate test dimensions. Two additional candidates intentionally remain unpromoted: non-finite positive numeric specs are accepted, and page hit geometry during pager motion appears centered independently of moving page placement; validate their user-visible consequences before filing.

## JavaScript, media and remote-loading audit

Audited KitePDF checkout `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. Findings belong to **KitePDF** unless explicitly noted. No repository source was changed and no issues were posted.

### Validation provenance

Seven scripting defects below were reproduced both with all 11 current common/JVM `kitepdf-javascript` source files freshly compiled into an isolated scratch jar, and with the published `kitepdf-javascript-jvm:0.13.0` binary. All 11 source files byte-for-byte match the Maven Central 0.13.0 sources jar. Both runs used published `kitepdf-core-jvm`, `kitepdf-pdf-jvm`, and `kitepdf-epub-jvm` 0.13.0, ImageKodec 0.3.0, KiteJS API/QuickJS 0.7.0, coroutines 1.11.0 and datetime 0.8.0. Execution was JVM/Linux x64, JDK 21, 512 MiB heap. The isolated source compilation used the available Kotlin compiler 2.4.20, not the project's requested 2.4.21. The published-binary reproduction avoids relying on that compiler difference. This was targeted functional validation, not a full project build, release benchmark, or Native/web run.

Scratch evidence: `/workspace/scratch/js-audit/Probe.kt`, `probe-pinned.log`, `probe-released.log`, `config.json`, `compile-pinned.log`. These are supporting scratch artifacts, not requested deliverables; essential snippets and results are included below.

Published-binary output:

```text
stale first=true a=LEFT
stale second=true b=LEFT failures=[validate: unexpected token in expression: '}']
cancel due timers lines=[first, second] failures=[]
document budget 50ms output=finished elapsedMs=308 failures=[]
epoch clock threw KiteScriptException: acrobat-api: script was interrupted
keystroke commit=true accepted=true
delayed first call threw KiteScriptException: acrobat-api: script was interrupted
reentrant close failures=[action: this engine is running a script; close it once that call has returned] underlyingClosed=false
cancelAndJoin after 100ms waitedMs=404
```

The millisecond figures show that a configured bound or cancellation did not stop execution; they are not throughput claims or estimates of nanosecond overhead.

<a id="JS-01"></a>

### JS-01 [P1] A script that fails before event dispatch copies the previous field's result into another field

**Owner:** KitePDF, `kitepdf-javascript`. **Status:** reproduced on current source and released 0.13.0 binary.

**Locations:** `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:678-691`, `:455-462`; `PdfScriptHost.kt:62-66`; `AcrobatApi.kt:635-665`.

**Trigger:** Two text fields have validation scripts. The first changes its `event.value`; the second has a syntax error. Commit the first, then the second.

```kotlin
// Using auditPdf() from the fixture below:
PdfScriptRunner(auditPdf("event.value = 'LEFT';", "if (")).use { runner ->
    check(runner.setFieldValue("a", "first"))
    check(runner.setFieldValue("b", "intended"))
    println(runner.formState.value("b")) // LEFT, not intended
}
```

**Cause:** `dispatch()` prepares a local `unchanged` result, but uses it only if the engine is absent or `defineValue` fails. It then calls `evaluate()`, which catches a `KiteScriptException`, and unconditionally returns `host.lastEventResult`. A parse failure happens before `__kiteEvent` runs, so its `finally` cannot publish a result for the new field. The host still contains the previous field's result. `commitOnThread()` stores that stale value and reports success. A previous `rc=false` can also incorrectly reject an unrelated field. The same stale-result mechanism affects other event kinds.

**Impact:** Silent cross-field data corruption after a malformed script, and potentially after engine interruption before the event wrapper executes. Saving the form can persist the wrong value. This violates lenient salvage: a broken script should not import another field's state.

**Remediation:** Initialize a dispatch-specific result before evaluation and distinguish whether this invocation actually reported a result. Keep the current behavior for an event whose body throws after changing its own value, while falling back to the current event's unchanged input when the wrapper never starts. A dispatch token also protects reentrant event nesting.

**Regression:** First validate sets `LEFT`, next validate is syntactically invalid, third is valid. Assert each field's value and acceptance independently; repeat with a previous `rc=false` and a forced engine interruption.

<a id="JS-02"></a>

### JS-02 [P1] Lazy PDF engine bootstrap spends the time since runner construction and fails after five idle seconds

**Owner:** KitePDF, `kitepdf-javascript`. **Status:** reproduced on current source and released 0.13.0 binary.

**Locations:** `PdfScriptRunner.kt:183-189`, `:209-235`, `:694-705`, `:718-728`, under `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/`.

**Trigger:** Construct a runner with default policy, wait longer than its five-second budget before the first script action, then run a trivial script. This is a normal form journey when no document-open script exists and the reader clicks the first scripted control later.

```kotlin
PdfScriptRunner(auditPdf()).use { runner ->
    Thread.sleep(5_200)
    runner.run(auditScript("'ok'"))
    // Throws KiteScriptException: acrobat-api: script was interrupted
}
```

The same failure occurs immediately with a valid epoch-valued custom clock, e.g. `clock = { 1_800_000_000_000L }`.

**Cause:** `eventStartedAt` begins at zero. `ensureEngine()` installs `deadlinePassed`, then directly evaluates `AcrobatApi.SOURCE` and `AformApi.SOURCE`. The current event's start time is assigned only after `ensureEngine()` returns. Bootstrap therefore compares the current clock with zero. These bootstrap evaluations are outside the runner's evaluation error handler, so failure escapes instead of being appended to `failures`. `openEngine` was already assigned, leaving a partially initialized engine for later calls.

**Impact:** A normal delayed first interaction fails, and the runner may remain partially initialized. Public custom clocks that represent actual epoch milliseconds also break first use.

**Remediation:** Treat trusted API setup separately from the document event budget, or start a dedicated bounded initialization clock before setup. Publish `openEngine` only after initialization succeeds; close and clear a failed partial engine. Initialize the user event's timer immediately before its script.

**Regression:** Test immediate and delayed first use, a runner with no document scripts, epoch and monotonic custom clocks, and initialization failure followed by retry.

<a id="JS-03"></a>

### JS-03 [P1] The document-wide script budget does not count the script currently running

**Owner:** KitePDF, `kitepdf-javascript`. **Status:** reproduced on current source and released 0.13.0 binary.

**Locations:** `PdfScriptRunner.kt:698-705`, `:718-728`; contract in `PdfScriptPolicy.kt:23-28`.

**Trigger:** Set a positive `documentBudgetMillis` with `budgetMillis=0`, which is the documented combination for an overall limit without a per-event limit, and run one long script.

```kotlin
PdfScriptRunner(auditPdf(), policy = PdfScriptPolicy(
    budgetMillis = 0, documentBudgetMillis = 50,
)).use { runner ->
    println(runner.run(auditScript(
        "var start=Date.now(); while(Date.now()-start < 300) {} 'finished';"
    ))) // finished; ~308 ms; no failure
}
```

**Cause:** The interrupt hook compares only completed-call `documentSpent` against the document limit. The current call is added in `finally`, so it cannot stop itself using the document-wide limit. An infinite first event with no per-event/instruction bound remains infinite. With both budgets configured, the total can overshoot by a complete event. In addition, the `onStillRunning` continuation path resets `eventStartedAt`; accounting in `finally` then loses the elapsed time in previously approved slices.

**Impact:** A configured sandbox execution limit is ineffective for the running event. This is a correctness and resource-budget failure, not ordinary scheduler timing uncertainty.

**Remediation:** Use separate monotonic timestamps for total event accounting and the per-event prompt window. Compare `documentSpent + currentEventElapsed` while executing, with overflow-safe arithmetic and nested-call accounting. Do not reset accumulated document time when the host grants another event slice.

**Regression:** An infinite first event with only document budget must stop; several events must share one bound; repeatedly approving `onStillRunning` must not bypass the document limit.

<a id="JS-04"></a>

### JS-04 [P1] Closing a PDF runner from its own callback abandons an open native engine

**Owner:** KitePDF, `kitepdf-javascript`; KiteJS correctly refuses to close an engine while executing. **Status:** reproduced on JVM with exact KiteJS 0.7.0.

**Locations:** `PdfScriptRunner.kt:779-808`; `kitepdf-javascript/src@jvm/io/github/yuroyami/kitepdf/javascript/ScriptThread.jvm.kt:19-25`. Relevant upstream contract: KiteJS 0.7.0 sources `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:197-205`; `bridge/QuickJsHost.kt:22-37`.

**Trigger:** An application handles `onAlert`, `onRequest` or `onConsole` by closing its runner. PDF callbacks are documented to execute on the runner's own thread, and unlike EPUB listeners their KDoc does not forbid calls back into the runner.

```kotlin
lateinit var runner: PdfScriptRunner
runner = PdfScriptRunner(auditPdf(), onAlert = { runner.close(); 1 })
runner.run(auditScript("app.alert('close now');"))
runner.close() // now a no-op; cleanup cannot be retried
```

**Cause:** The deferred-close branch applies only to a supplied engine or a web/in-place thread. On an owned JVM/Native thread, `thread.call` executes inline. `engine.close()` therefore runs while QuickJS is executing the host callback and throws `JsEngineError`. The `finally` blocks still clear `openEngine` and shut down the owning executor. The runner's `closed` flag prevents subsequent cleanup. QuickJS remains registered in its strong global engine registry and its native pointer is not freed.

**Observed:** The action records `this engine is running a script; close it once that call has returned`. Reflection on the saved underlying engine after the call found `closed=false`, while the runner had dropped it and stopped its thread. This is actual unreleased engine state, not just an allocation spike or a cache.

**Remediation:** Defer close whenever execution is inside the runner on its own thread, regardless of platform; interrupt the event and release its engine only after unwinding to the outermost runner call. Preserve a recoverable engine reference if close fails. Cover callback-triggered close, simultaneous external close and double close.

<a id="JS-05"></a>

### JS-05 [P2] Cancelling a runner coroutine does not stop its script or its side effects

**Owner:** KitePDF for runner cancellation propagation and owned thread waits. There is a separate upstream KiteJS integration limitation on web pausing, described below. **Status:** reproduced on JVM with exact dependencies; other platforms inspected statically.

**Locations:** `PdfScriptRunner.kt:118-121`, `:718-728`; `EpubScriptRunner.kt:159-163`, `:216-220`; `src@jvm/.../ScriptThread.jvm.kt:19-21`; `src@native/.../ScriptThread.native.kt:30-35`.

**Trigger:** Start `runner.runAction()` in a coroutine, cancel that coroutine while it executes, then join. For a 500 ms bounded script, cancellation after 100 ms still waited another 404 ms. A `LONG_RUNNING` infinite script requires a separate explicit `runner.close()` to finish.

```kotlin
val runner = PdfScriptRunner(auditPdf(), policy = PdfScriptPolicy.LONG_RUNNING)
runBlocking {
    val job = launch(Dispatchers.Default) {
        runner.runAction(auditScript(
            "var start=Date.now(); while(Date.now()-start < 500) {}"
        ))
    }
    delay(100)
    job.cancelAndJoin() // waits for script completion, not cancellation
}
runner.close()
```

**Cause:** The suspend entry points use synchronous `Future.get()` on JVM/Android and `runBlocking` on Native. They do not capture the caller's `Job` in the engine interrupt check. Coroutine cancellation alone does not interrupt these blocking waits. The deadline checks consult only policy and the explicit runner `closed` flag. Cancelling a Compose effect therefore does not itself cancel a running document script or prevent that script's later host mutations.

**Remediation:** Add a suspending, cancellable work submission mechanism and a call-scoped atomic cancellation signal consumed by the engine interrupt hook. Cleanup must still run on the engine owner thread. Define how cancelled queued calls are removed and how active cancellation affects document state. Do not run caller cancellation handlers directly against the native engine from another thread.

**Upstream boundary:** KiteJS `WebBridge.kt` currently waits for pausing runs with bare `suspendCoroutine` (`settled`, lines 127-142 in GitHub main blob `b677a0dd9ffd091e4f055dc37604c453ce7523d2`). That does not add automatic `Job` cancellation either. A generic cancellation-aware QuickJS API belongs in **KiteJS**, not a second WebAssembly bridge inside KitePDF. No standalone upstream failure was executed on web, so this is an inspected integration limit, not a second reproduced issue.

<a id="JS-06"></a>

### JS-06 [P2] Clearing a timeout from another due timer does not cancel its callback

**Owner:** KitePDF, `kitepdf-javascript`. **Status:** reproduced on current source and released 0.13.0 binary.

**Locations:** `PdfScriptHost.kt:357-370`; `PdfScriptRunner.kt:659-664`.

**Trigger:** Schedule two timeouts for the same tick; the first clears the second. Pump after both are due.

```javascript
app.setTimeOut('app.clearTimeOut(second); console.println("first");', 10);
var second = app.setTimeOut('console.println("second");', 10);
```

With an initially zero custom clock advanced to 10 before `pumpTimers`, output is `[first, second]`, with no failures. Clearing the second timer should prevent its callback.

**Cause:** `dueTimers()` snapshots all due entries, removes all one-shot timers before any callback runs, and returns only their code strings. `pumpTimers()` executes this detached list without checking timer IDs again. The first callback can no longer find/remove the second timer, and its callback still runs. Repeating timers cancelled during the same batch also have a stale callback in the snapshot.

**Impact:** Stale callbacks can mutate fields or issue host requests after the document explicitly cancelled them. Interactive PDFs can apply an abandoned action during the same frame.

**Remediation:** Snapshot ordered timer IDs, keep cancellation/generation state until execution, and verify each timer is still active immediately before invoking it. Preserve one-callback-per-pump fairness for zero-period intervals.

<a id="JS-07"></a>

### JS-07 [P2] The public `keystroke(commit=true)` argument is ignored

**Owner:** KitePDF, `kitepdf-javascript`. **Status:** reproduced on current source and released 0.13.0 binary.

**Locations:** `PdfScriptRunner.kt:397-417`.

**Trigger:** A text field's `/AA /K` script rejects final commitment but accepts ordinary typing:

```javascript
if (event.willCommit) event.rc = false;
```

```kotlin
PdfScriptRunner(auditPdf(
    "if(event.willCommit) event.rc=false;", keystroke = true,
)).use { runner ->
    println(runner.keystroke("a", "x", commit = true).accepted) // true
}
```

**Cause:** The public method accepts `commit` but never forwards or reads it. `keystrokeHere()` hardcodes `"willCommit" to false`. Therefore `commit=false` and `commit=true` are indistinguishable.

**Impact:** Consumers using the exposed flag cannot perform the requested final keystroke check. The dedicated `setFieldValue` path behaves differently, so valid public API combinations disagree.

**Remediation:** Wire the parameter through with explicit final-commit event-value/change semantics, or deprecate/remove it through a documented compatibility transition and direct callers to the supported commit API. Do not leave a silently ignored public behavior switch.

<a id="JS-08"></a>

### JS-08 [P2] An erroring interval causes unbounded diagnostic retention and quadratic copying

**Owner:** KitePDF, `kitepdf-javascript`. **Status:** source-proven algorithmic behavior; no long-duration RSS benchmark performed.

**Locations:** `PdfScriptRunner.kt:165-176`, `:659-663`, `:701-705`; `PdfScriptPolicy.kt:23-28`.

**Trigger:** A document starts `app.setInterval("throw new Error('tick')", 0)`. The viewer pumps the timer repeatedly. Each failure is caught, so the interval stays active and the document continues. The default document budget is unlimited, and its per-event time limit does not stop a short throw.

**Cause:** Every failure is permanently appended to `failureList`; `failureCopy = failureList.toList()` copies all prior entries after every failure. N failures retain N throwable/cause/stack chains and copy N(N+1)/2 references over time. JavaScript's 512 MiB engine allocation limit does not cover these Kotlin allocations.

**Impact:** An accidentally broken recurring callback can make a long reader session consume increasing memory and GC time. At 60 failures per second for one hour, the code would copy approximately 23.3 billion references in aggregate; this is an arithmetic extrapolation, not a measured memory result. Retained state also grows without bound.

**Remediation:** Bound retained diagnostics, keep a total/dropped count, optionally deduplicate repeated failures, and publish snapshots without copying the entire history on every event. Expose a callback or bounded stream for applications that need durable logging. Verify after many failure cycles that retained count and per-event allocation stay bounded.

<a id="MEDIA-01"></a>

### MEDIA-01 [P2] Remote playback cannot recover when `allowRemote` changes from false to true

**Owner:** KitePDF, `kitepdf-media`. **Status:** source-proven state/effect-key defect; Compose scene reproduction not executed.

**Locations:** `kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaOverlay.kt:193-200`, `:218-240`; `MediaSources.kt:25-32`.

**Trigger:** Keep the same `EpubMedia` instance in `EpubMediaPlayer`, initially set `allowRemote=false`, and tap play for an element whose only source is HTTPS. Alternatively let that element autoplay. Later recompose with `allowRemote=true` after the application grants access.

**Cause:** The first attempt has no playable sources and stores `unplayable=true`. That state is remembered only by `media`, not by `allowRemote`, `document` or player capability. The play button disappears, `start()` immediately returns for an unplayable element, and `LaunchedEffect(media)` does not restart when permission changes.

**Impact:** The reader cannot play a now-authorized source until the media object or composition is replaced. A failed initial `newPlayer` followed by a newly available player has the same irreversible failure state.

**Remediation:** Key the retry/failure state to the inputs that determine playability, and define an explicit retry/reconfiguration policy. Test false-to-true permission changes after failure, capability recovery, and replacement documents with a stable media object. Do not automatically restart already-playing media unnecessarily.

<a id="MEDIA-02"></a>

### MEDIA-02 [P2] Changing `session` leaves an active player registered with the old session

**Owner:** KitePDF, `kitepdf-media`. **Status:** source-proven effect-capture defect; Compose scene reproduction not executed.

**Locations:** `KiteMediaOverlay.kt:243-256`, under `kitepdf-media/src/io/github/yuroyami/kitepdf/media/`; `KiteMediaSession.kt:48-65`.

**Trigger:** Recompose `EpubMediaPlayer` with the same `media` and existing player but replace its public `session` argument, for example when moving a reader between two host-controlled playback groups.

**Cause:** `DisposableEffect(open)` captures the original session for registration, unregister and saved place; `LaunchedEffect(open)` captures it for `session.started(open)`. Neither effect is keyed on `session`. The new session receives no registration, while the old session continues controlling that player. The `place` saved on disposal is also captured when the player effect starts, so changing the public place key can save into the old slot.

**Impact:** The documented one-player-at-a-time rule fails in the new group, and a different player in the old group can unexpectedly pause the moved player. Playback position can be retained under a stale key.

**Remediation:** Separate player ownership/close from session membership. Re-key membership and activity observation on `(open, session)`, with a defined saved-place handoff, without closing the player merely because the session changes. Verify that the old session no longer affects it and the new session pauses it correctly.

### Shared minimal PDF fixture for the executable scripting reproductions

This self-contained fixture uses public API only. It creates two text fields with either validation or keystroke scripts; it is deliberately ASCII so the byte offsets equal string offsets.

```kotlin
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.javascript.PdfScriptPolicy
import io.github.yuroyami.kitepdf.javascript.PdfScriptRunner

fun auditScript(source: String) = PdfAction.JavaScript(source, PdfDictionary(emptyMap()))

fun auditPdf(
    first: String = "", second: String = "", keystroke: Boolean = false,
): PdfDocument {
    fun hex(s: String) = "<" + s.encodeToByteArray().joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    } + ">"
    val event = if (keystroke) "/K" else "/V"
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
        "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R] >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /V () /Rect [20 120 180 160] /AA << $event << /S /JavaScript /JS ${hex(first)} >> >> >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (b) /V () /Rect [20 20 180 60] /AA << $event << /S /JavaScript /JS ${hex(second)} >> >> >>",
    )
    val body = StringBuilder("%PDF-01.7\n")
    val offsets = ArrayList<Int>()
    objects.forEachIndexed { i, value ->
        offsets += body.length
        body.append("${i + 1} 0 obj\n$value\nendobj\n")
    }
    val xref = body.length
    body.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { body.append("${it.toString().padStart(10, '0')} 00000 n \n") }
    body.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return PdfDocument.open(body.toString().encodeToByteArray())
}
```

### Coverage, capabilities and remaining evidence gaps

| Area | Evidence reviewed or executed | Limits of this pass |
|---|---|---|
| PDF scripting | Runner, Acrobat host/API and AF helpers; event dispatch, initialization, clock budgets, close, timers, cancellation; 7 focused JVM failures reproduced | Not an exhaustive Acrobat JavaScript conformance run. The existing PDFium parity suite passed in the later full JVM gate; this is not exhaustive Acrobat API conformance |
| EPUB scripting | Runner lifecycle, per-chapter engine dispatch, pausing lock, setup-budget exclusion, listener ownership, LRU chapter behavior, relevant lifecycle tests | DOM/CSS findings appear in the structured-format audit below; no web, Native or Apple execution |
| KiteJS integration | Exact 0.7.0 binary and source artifact for JVM; engine close guard and strong registry traced; main web bridge inspected | No full upstream engine audit; no claims about memory safety of all C/JNI code; web source is blob-pinned main, not a resolved release tag |
| Native script threads | POSIX queue, detached pthread lifetime and `StableRef` disposal on successful/failed creation inspected | No device GC/ARC/stack or cancellation stress run |
| Media | Public parameter effects, remembered failure state, session/player membership and read-aloud cleanup reviewed | MEDIA findings remain source-established; the existing JVM media suite later passed, but the specific new transitions and Android/iOS background behavior remain untested |
| Remote loading | `RemoteSources.kt`, body-size checks, streaming channel loop, cancellation rethrow, diagnostics URL sanitization and HTTPS filter reviewed | No new confirmed defect asserted; no live redirect/TLS/interruption matrix run |

Capabilities and limits visible in source: PDF JavaScript is explicitly opt-in through the optional module; external requests are routed to host callbacks. Default policy permits scripts but limits events; `DENY` still initializes an engine on several PDF API paths because `ensureEngine()` precedes the policy check. This is unnecessary work and can trigger JS-02, but is not evidence that document scripts run under DENY. Several Acrobat field setters intentionally accept and ignore unsupported appearance/list operations (`PdfScriptHost.kt:149-150`, `:217-223`), so surface API presence is not full Acrobat behavior parity. These remain capability gaps pending a separate assessment of the public contract and target scope.

Additional follow-up leads, not promoted to defects without a scene or stress reproduction: read-aloud teardown launches unowned asynchronous markup clearing on a per-instance mutex (`KiteReadAloud.kt:196-207`), which may race a new reading of the same book; failures before `read()` normal cleanup may leave a non-null completed `reading` job; global bytecode cache limits permit one entry larger than its byte ceiling. Eight live EPUB chapter engines each have a 512 MiB engine limit, so this is not a shared document/process memory budget. Each chapter engine also owns a 16 MiB stack reservation off web. Those figures are configured maxima/reservations, not measured retained RSS.

## PDF pipeline audit contribution

Audited revision: KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`.
Owner of every finding below: **KitePDF**, not KiteJS or ImageKodec.

### Validation boundary

All 89 current `kitepdf-pdf/src/**/*.kt` files compiled successfully in an isolated scratch directory with cached Kotlin/JVM 2.4.20, JVM target 21. Compilation initially used the pre-existing **0.12.0 core jar**, Kotlin stdlib/coroutines, and ImageKodec 0.3.0. The complete probe was then rerun with published **core 0.13.0** replacing that core jar; every reported outcome was unchanged (`probe-pinned.log`). Thus the reproductions execute current PDF parsing/editing/writing/extraction implementations, but are **not a clean build of the entire current repository**. No source files or tests in the repository were changed. MuPDF 1.27.2 was an independent reader where stated. No release performance claim follows from these runs.

**Final current-build confirmation:** After the regular toolchain build completed, the entire PDF probe was rerun with the freshly compiled current core/PDF/EPUB classes and stdlib 2.4.21 first on its classpath. All six reproduced PDF outcomes were unchanged. Log: `/workspace/scratch/audit-current-pdf.log`. This removes the earlier core-version caveat for the final results; the later full JVM gate is recorded above, while non-JVM platform execution remains unverified.

Harness and output: `/workspace/scratch/audit-pdf/PdfAuditProbe.kt`, `make_fixtures.py`, `probe.log`, `current-pdf.jar`, `compile.log`. Each fixture named below is in that scratch directory. The report below includes the essential triggers so it is reviewable without retaining the harness.

<a id="PDF-01"></a>

### PDF-01 [P1] Valid encrypted PDFs with object streams cannot open

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:907-918`, compared with `:851-877`; recovery has the same limitation in `parser/PdfRepair.kt:138-145`.

The ordinary indirect-object path calls `maybeDecrypt(...)` before consumers decode stream filters. `decodeObjectStream` instead parses the containing `/ObjStm` directly and passes its encrypted `rawBytes` to `FilterChain.decode`, without decrypting it. ISO 32000-1 sections 7.5.7 and 7.6 encrypt the containing object stream; its member strings are not decrypted separately. These two supported capabilities fail when combined.

**Reproduction:** Create a simple PDF containing `Hello World`, then use:

```sh
mutool clean -Z -E aes-256 -O owner -U pw plain.pdf encrypted-objstm.pdf
```

Call `PdfDocument.open(bytes, "pw")`. Actual: `PdfFormatException: Repair found no catalog that leads to a page tree`. MuPDF independently extracts `Hello World`. Controls produced with only `-Z`, and with only `-E aes-256 -O owner -U pw`, both open and extract correctly in the current PDF implementation. This establishes an interaction defect, not a bad password or unsupported fixture.

**Impact:** Ordinary password-protected optimized PDFs fail to open or lose compressed objects. The library's independent encryption and object-stream test coverage does not establish their combined support.

**Remediation:** Decrypt the container under its own object number/generation before filter decoding; apply the same ordering in repair after discovering security metadata, without double-decrypting member strings. Add independent AES-128/AES-256/RC4 object-stream controls, valid/invalid passwords, and rewrite/edit cases.

**Status:** Reproduced with current PDF sources under the validation boundary above; independent-reader confirmed.

<a id="PDF-02"></a>

### PDF-02 [P1] `ContentStreamBuilder.save()/restore()` restores the PDF font but not the writer's string encoding

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/ContentStreamBuilder.kt:35-43`, `:111-126`, `:133-136`.

`currentEmbedded` determines whether `showText` emits two-byte Identity-H glyph codes or a single-byte string. `setFont` mutates it, while `save` and `restore` merely emit `q` and `Q`. PDF's graphics state includes the selected text font (ISO 32000-1, 8.4 and 9.3); after `Q`, the reader restores the outer font while the builder continues encoding for the inner font.

**Reproduction:** Load DejaVuSans with `EmbeddedFont.load`, then:

```kotlin
PdfBuilder().page {
    text(embedded, 20.0, 72.0, 700.0, "BEFORE")
    save()
    text(StandardFont.Helvetica, 20.0, 72.0, 650.0, "INNER")
    restore()
    beginText(); moveText(72.0, 600.0); showText("AFTER"); endText()
}.build()
```

Actual final bytes are `(AFTER) Tj` under the restored `/CF1` Identity-H font. MuPDF extracts `BEFORE`, `INNER`, then replacement characters instead of `AFTER`. The generated fixture is `font-save-restore.pdf`. A reverse switch, or switching between two embedded fonts, can encode the wrong glyph IDs or record glyph usage for the wrong subset too.

**Impact:** Supported public builder calls compose into visibly corrupted output. Round-tripping through KitePDF alone misses this case because PDF-03 makes its linear extractor decode with the wrong inner font as well.

**Remediation:** Stack and restore `currentEmbedded` along with emitted `q/Q`; test standard-to-embedded, embedded-to-standard, different embedded fonts, nested saves, and independent-reader output.

**Status:** Reproduced with current PDF sources; independent-reader confirmed.

<a id="PDF-03"></a>

### PDF-03 [P2] Linear text extraction ignores restored font encodings after `Q`

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextExtractor.kt:85-106`, `:128-133`, `:163-187`.

The extractor stores one mutable `font` and recognizes `Tf`, but never handles `q`/`Q`. Its documented choice to omit geometric transforms does not make character decoding independent of the graphics-state font. A restored font can have a different encoding or ToUnicode map.

**Reproduction:** Use two Helvetica resources: `/F1` with normal encoding, `/F2` with `/Encoding << /BaseEncoding /WinAnsiEncoding /Differences [65 /Z] >>`. Content:

```text
BT /F1 20 Tf 72 700 Td (A) Tj ET
q BT /F2 20 Tf 72 650 Td (A) Tj ET Q
BT 72 600 Td (A) Tj ET
```

`extract-q-restore.pdf` yields **A, Z, Z** from `PdfPage.extractText()`. MuPDF yields **A, Z, A**, matching the restored font. This is separate from the malformed builder output in PDF-02: this input is a valid document.

**Impact:** Text extraction, applications using that text, and self-roundtrip writer tests can return plausible but incorrect characters.

**Remediation:** Preserve font and relevant text parameters through graphics-state saves/restores even if linear extraction intentionally omits positional CTM tracking. Cover nested restoration with different single-byte and composite encodings.

**Status:** Reproduced with current PDF sources; independent-reader confirmed.

<a id="PDF-04"></a>

### PDF-04 [P1] Redaction removes glyphs but retains their `/ActualText` replacement

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:223-307` (no `BDC`/marked-content scrubbing); `text/TextExtractor.kt:110-117` consumes retained `/ActualText`; `writer/PdfEditor.kt:859-863` serializes the retained operations.

Marked-content dictionaries pass through the catch-all operator branch unchanged. `/ActualText` is replacement text for enclosed marks (ISO 32000-1, 14.9.4), and may carry exactly the text being removed.

**Reproduction:** In a normal page stream, wrap text at `(72,700)` in:

```text
/Span << /ActualText (SECRET_REPLACEMENT) >> BDC
BT /F1 24 Tf 72 700 Td (SECRET) Tj ET
EMC
```

Redact `KiteRectangle(60.0, 680.0, 300.0, 740.0)` and call `saveRewritten()`. Actual decoded rewritten stream retains `/ActualText (SECRET_REPLACEMENT)` and replaces only the text showing operation with `[-4056] TJ`. The secret is still present in reachable stream data; KitePDF's extractor explicitly emits it when encountering the retained `BDC`.

**Impact:** The documented guarantee that redacted text cannot be extracted or recovered is false for tagged/accessibility-rich PDF content, even after a full rewrite.

**Remediation:** Track marked-content spans while redacting and remove or recompute replacement/alternate text associated with removed marks. Include inline property dictionaries, named `/Properties` dictionaries, and structure-tree replacements; do not silently claim complete scrubbing if some representations cannot be updated.

**Status:** Reproduced with current PDF sources, including inspecting decoded streams after reopen. Fixture: `redacted-actualtext.pdf`.

<a id="PDF-05"></a>

### PDF-05 [P1] Redacted images survive through inherited `/Pages /Resources`

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:883-892`, `:1084-1094`; `writer/PdfEditorRedaction.kt:353-374`.

Redaction copies inherited resources onto the leaf page and removes the image's name there. The ancestor `/Pages` dictionary is not changed. That ancestor is reachable from the catalog/page tree and still references the original image; the rewrite's reference-based collection therefore retains it. Merely shadowing the inherited name on the leaf does not remove the ancestor's reference.

**Reproduction:** One-page PDF whose `/Resources << /XObject << /Im0 6 0 R >> >>` is on the `/Pages` node, with no leaf `/Resources`. Draw `/Im0` into `[100,100,200,200]`, redact `[90,90,210,210]`, then `saveRewritten()`. The image uses 15 grayscale samples `SECRET_IMAGE_123`, which makes survival easy to check without a decoder. Actual: the `Do` is removed from the rewritten page, but a reachable image stream still contains all 15 original samples. No other page draws the image.

**Impact:** Visually removed scanned/image content remains recoverable despite the rewrite and advertised resource pruning. This also wastes space in documents where all uses of an inherited image were removed.

**Remediation:** Before collecting rewritten objects, normalize page-resource ownership or prune ancestor resources against actual descendant needs. Keep shared images only when a surviving use requires them. Test inherited resources on multiple ancestor levels and combinations of redacted/unredacted children.

**Status:** Reproduced with current PDF sources; reopened output and inspected all reachable decoded streams. Fixture: `redacted-inherited-image.pdf`.

<a id="PDF-06"></a>

### PDF-06 [P1] Redaction ignores line widths and miter limits set through ExtGState

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:225-238`, `:307`, `:515-519`; contrast `render/PageRenderer.kt:1488-1492` and `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/GraphicsState.kt:122-125`.

The renderer applies `/LW` and `/ML` through `gs`; redaction handles only the direct `w` and `M` operators, and retains `gs` without applying it to its shadow state. Its stroke bound therefore disagrees with the drawn stroke (ISO 32000-1, 8.4.5).

**Reproduction:** Resource `/Wide << /LW 100 /ML 1 >>`; content `q /Wide gs 100 100 m 200 100 l S Q`. Redact `[120,130,180,145]`, which intersects the real 100-unit-wide stroke. Actual rewritten content still contains the original `m`, `l`, and `S` operations. Redaction used default width 1 and miter limit 10, giving only 5 units of padding around y=100, while the actual stroke reaches y=150. The black box covers only the selected visual area; the underlying vector is retained.

**Impact:** Public redaction semantics vary according to two equivalent ways of setting the pen. A signature/diagram stroke selected by its visible ink can survive unchanged.

**Remediation:** Load and apply relevant ExtGState entries in the redaction state machine, propagate them through form calls, and use one shared graphics/text state implementation where practical. Verify equivalent `w/M` versus `gs /LW /ML` documents produce equivalent removals.

**Status:** Reproduced retention with current PDF sources, supported by explicit renderer/source geometry comparison. Fixture: `redacted-extgstate-stroke.pdf`.

### Additional source-established findings, not executed as adversarial experiments

<a id="PDF-07"></a>

### PDF-07 [P1] Xref-stream negative widths bypass decoded-data limits

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/XrefParser.kt:133-148`, `:153-175`, `:181-186`.

`/W` values are narrowed to `Int` without validating nonnegative bounded widths. Only a zero sum is rejected. With `/W [0 0 -1]`, `rowSize=-1`; `readField(...,-1)` runs no iterations and returns zero, the cursor becomes progressively negative, and `cursor+rowSize > decoded.size` never rejects an empty stream. A declared `/Index [0 N]` therefore creates N `InUse` entries from no data. Work and memory are controlled by a count, not by available rows. Validate widths, arithmetic and `/Index` pairs before walking rows, and bound counts by the decoded length. **Status: source-established; no exhaustion run performed.**

<a id="PDF-08"></a>

### PDF-08 [P1] Object-stream counts allocate before being checked against available headers

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:913-924`; recovery duplicates the pattern in `parser/PdfRepair.kt:142-145`.

`/N` is narrowed to `Int` and used as `IntArray(n)` before a single header pair is validated; repair constructs `ArrayList(n)` at the same stage. A tiny otherwise parseable object stream can request a huge array independent of decoded stream length. Read `/N` as a checked value and require a feasible number of header pairs within `/First`/decoded bytes before allocating. Avoid treating catch-and-repair as allocation validation, especially on Wasm/Native. **Status: source-established; no exhaustion run performed.**

<a id="PDF-09"></a>

### PDF-09 [P1] Signature trust accepts a non-CA certificate as an issuer

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/SignatureValidator.kt:202-219`; `crypto/sig/X509.kt:32`, `:72-74` parses `isCa` but the chain walk does not consult it.

`chainOf` accepts an issuer whenever its subject matches and its key verifies the next certificate; `result` reports `isTrusted=true` when any certificate in that chain is a supplied anchor. Thus a CA-issued end-entity certificate (`basicConstraints CA:FALSE`) can issue an arbitrary further signer certificate, and a mathematically valid PDF signature from that signer gets a chain to the trusted CA. RFC 5280 path validation requires CA authorization for intermediate issuers; signature verification alone does not supply it. Key usage, path-length constraints and unrecognized critical extensions are also not validated here. This is separate from the explicitly documented omission of certificate-date checks and the separately reported revocation status; those documented omissions are not findings in this audit.

**Remediation:** Enforce certificate path constraints before exposing trust, or expose the result as signature-linkage information until actual trust-path validation is available. Test a genuine CA chain against the same chain with a CA:FALSE intermediate. **Status: source-established; certificate experiment not run.**

### Coverage and limits

Reviewed parser/xref/repair flow, object-stream resolution, ordinary decryption ordering, incremental/rewrite serialization, object reachability, builder font encoding, linear extraction, redaction operator state/resources, and signature chain decisions. Runtime probes covered the encryption/object-stream cross-product, writer save/restore across font kinds, extraction with alternate encodings, marked-content redaction, inherited image resources, and ExtGState pen redaction.

No claim of complete spec coverage, clean full-tree test pass, device/Native/Wasm execution, interrupted-save durability, heap stress validation, constant-time cryptography, full certificate conformance, or release performance measurement. No nanosecond claims were made. Existing tests examined include encryption integration/write, object-stream writing, redaction core/text-state/path/shading/inherited resources, and resolution guards. Their independent feature coverage does not cover the interactions demonstrated above.

## Renderer audit: evidence and scope

Audited KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. All findings below belong to **KitePDF**, not KiteJS or ImageKodec.

Validation was deliberately bounded: 10 by 10 pixel direct-canvas probes and 100 by 100 point generated, valid PDF pages. The current source files for `KiteRasterCanvas`, `RasterScan`, `RasterBlend`, every JVM native-renderer source, every `kitepdf-pdf/.../render` source, and every Skia-renderer source were compiled directly into a scratch output directory. The initial compile used cached 0.12.0 dependency jars; all three probe executables were subsequently rerun successfully with published **KitePDF core/pdf/epub 0.13.0** and **ImageKodec 0.3.0** replacing those cached runtime jars. The current renderer-source classes remained first on the runtime classpath, and every result was unchanged. Compiler: Kotlin 2.4.20; runtime: Temurin 21.0.12.1+1 on Linux x64. Skia probes used the **declared Skiko 0.153.0** Java and Linux x64 runtime artifacts fetched from Maven Central. Independent PDF oracle: **MuPDF 1.27.2**. This validates the changed renderer sources and exercised call paths; it is **not** a clean build of all HEAD modules with the declared Kotlin 2.4.21 toolchain, nor a published-artifact consumer check.

Reproduction sources and outputs are in `/workspace/scratch/audit-render/`: `fixtures.py`, `RenderProbe.kt`, `PdfProbe.kt`, `SkiaProbe.kt`, `compile_probe.py`, `probe.log`, `pinned-runtime-classpath.txt`, and `pinned-probe.log`. No production source was changed. The fixtures use small finite geometry and ordinary PDF graphics operations.

<a id="RENDER-01"></a>

### RENDER-01 [P2] Negative tiling-pattern steps make valid patterned fills disappear

**Owner:** KitePDF PDF renderer. **Status:** reproduced with current `PageRenderer` and AWT sources; independently checked against MuPDF 1.27.2.

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1855` computes index ranges with `abs(xs)` and `abs(ys)`, but line 1898 places each cell at `i * xs, j * ys`. `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KitePattern.kt:103` preserves the signed `/XStep` and `/YStep` from the input.

**Trigger and reproduction:** On a 100 by 100 point PDF page, define a colored tiling pattern with `/BBox [0 0 10 10] /XStep -10 /YStep 10`, `/Matrix` omitted, `/Resources << >>`, and cell content `1 0 0 rg 0 0 5 5 re f`. Fill a rectangle away from the origin with `/Pattern cs /P scn 40 40 40 40 re f`. PDF permits either sign for the nonzero step values (ISO 32000-1, 8.7.3.1, Table 75). Negating a step changes index ordering, not the infinite lattice of cell positions.

**Observed:** AWT renders **0 red pixels** for `/XStep -10`, but **400 red pixels** for the otherwise identical `/XStep 10` control. MuPDF renders **400 red pixels** for the negative-step document. The affected fill is completely missing, not a small antialias difference. The same mismatch applies independently to negative `/YStep`.

**Cause:** Positive pattern coordinates select positive indices using the absolute step, then those indices are translated into negative coordinates using the original signed step. None of the generated cells intersects the requested region.

**Fix direction:** Use a consistent index/placement convention: enumerate the lattice using positive step magnitudes throughout, or derive signed index intervals and sort their endpoints. Preserve cell content orientation. Add sign combinations `(+,+), (-,+), (+,-), (-,-)` to the same PDF oracle fixture, including nonidentity pattern matrices and a region away from the origin.

<a id="RENDER-02"></a>

### RENDER-02 [P2] The plain Kotlin raster canvas ignores isolation and knockout parameters

**Owner:** KitePDF core. **Status:** reproduced from current `KiteRasterCanvas`, `RasterScan`, and `RasterBlend` sources.

**Evidence:** `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:188` always allocates a transparent layer. Its `GroupState` at line 193 retains only alpha and blend mode; it discards both `isolated` and `knockout`. The final composition at line 199 therefore cannot implement either flag. The public contract describes both flags in `KiteCanvas.kt:184` and `KiteCanvas.kt:189`.

**Trigger and reproduction:** Use a 10 by 10 `KiteRasterCanvas`, a path covering the canvas, and the identity matrix.

1. Paint opaque red. Open a group with `isolated=false, knockout=false, alpha=1, blendMode=Normal`; paint opaque blue with `Multiply`; close the group. Expected: black, since blue multiplies the red backdrop. Actual center pixel: `0xff0000ff`, opaque blue.
2. Clear the canvas to white. Open a group with `isolated=true, knockout=true, alpha=1, blendMode=Normal`; paint opaque red, then blue at alpha 0.5; close the group. Expected: pale blue over the white page, approximately `0xff7f7fff`. Actual: `0xff800080`, purple from the red sibling that should have been knocked out.

**Impact:** The same valid graphics stream produces different colors depending on the selected canvas. The PDF renderer's raster fallback does not rescue these cases: a plain non-isolated group at alpha 1 with Normal group blending bypasses `compositeExactly`; a group with Normal member paints likewise does not select that fallback. This affects public `KiteRasterCanvas` consumers and prevents treating the plain Kotlin canvas as a semantic reference for native backends.

**Fix direction:** Implement the group state machine, including direct backdrop painting for eligible non-isolated groups and shape-aware knockout for elementary objects. Add the two direct-canvas tests and equivalent PDF render tests. Do not infer coverage from the existing ordinary translucent-group test, which never varies these flags.

<a id="RENDER-03"></a>

### RENDER-03 [P2] A translucent subgroup does not knock out its preceding sibling

**Owner:** KitePDF native and Skia renderers. **Status:** reproduced end to end on AWT and Skia 0.153.0, with MuPDF 1.27.2 as an independent PDF oracle. Apple and browser counterparts were inspected but not executed.

**Evidence:** `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:947` composites a closing Normal subgroup with `AlphaComposite.SrcOver` even while its parent is a knockout group. `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:752` detects the knockout parent and forces a layer, but line 758 assigns only the child's blend mode to the layer's restore paint. Parent knockout never reaches the restore operation.

**Trigger and reproduction:** A 100 by 100 point PDF page invokes an outer Form XObject with `/Group << /S /Transparency /I true /K true >>`. The outer form paints an opaque red rectangle covering the page, sets `/ca 0.5`, and invokes an inner Form XObject with `/Group << /S /Transparency /I true >>`. The inner form paints an opaque blue rectangle over the same area. Use a white page background. A subgroup counts as an elementary object in its containing group; its shape must remove the earlier red object before the blue subgroup's opacity is applied (ISO 32000-1, 11.4.6).

**Observed center pixels:**

| Renderer | ARGB |
| --- | --- |
| AWT | `0xff7f0080` |
| Skia 0.153.0 | `0xff7f007f` |
| MuPDF 1.27.2 | `0xff8181ff` |

The missing green channel exposes the red sibling incorrectly surviving under blue. The minor difference between ideal `0xff7f7fff` and the oracle's `0xff8181ff` is immaterial to that distinction.

**Impact:** Nested form groups are a normal way that authoring tools represent transparency. Flat knockout tests can pass while equivalent nested content changes color. The same source pattern exists in CoreGraphics group composition (`CoreGraphicsCanvas.kt:715`) and Canvas2D `endTransparencyGroup` (`Canvas2dCanvas.kt:504`), but those targets still need their own runtime regression checks.

**Fix direction:** Preserve parent knockout semantics when compositing a child group as one object. Track the child's shape separately from its opacity so transparent pixels outside its shape do not erase the entire parent layer. A simple global source-copy of the group's rectangular buffer is not generally correct. Add nested combinations of parent knockout, child isolation, child alpha, and child blend mode to the differential fixture set.

<a id="RENDER-04"></a>

### RENDER-04 [P2] Soft masks erase the backdrop needed by a masked blend operation

**Owner:** KitePDF Skia renderer, with equivalent source paths in Android, Canvas2D, and the plain raster canvas. **Status:** reproduced end to end with current Skia renderer sources and declared Skiko 0.153.0; AWT and MuPDF both provide matching controls.

**Evidence:** `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:810` captures the content in a fresh transparent layer with a default `Paint`. The original fill's Multiply operation runs against that transparent layer at line 816. The layer is masked with `DST_IN` and ultimately restored using ordinary source-over at line 854. No operation reinstates the content's original blend mode against the page backdrop. The Android implementation begins the same sequence at `AndroidNativeCanvas.kt:629`. Canvas2D explicitly finishes with `composite(content, 1.0, "source-over")` at `Canvas2dCanvas.kt:563`. Plain raster uses `KiteBlendMode.Normal` at `KiteRasterCanvas.kt:227`.

**Trigger and reproduction:** On a 100 by 100 point page, first paint an opaque blue rectangle. Set an ExtGState with `/BM /Multiply` and `/SMask << /S /Alpha /G 5 0 R >>`, where the mask form fills its entire 100 by 100 box with opaque white. Paint an opaque red rectangle over the blue rectangle. The mask is fully opaque, so enabling it must not change the result of the underlying Multiply operation.

**Observed:** Skia outputs **opaque red**, `0xffff0000`. AWT and MuPDF 1.27.2 both output **black**, `0xff000000`. This is a complete loss of blend semantics, with no ambiguity about mask interpolation or numeric tolerance.

**Impact:** Combinations of ordinary soft masks and non-Normal blend modes change document colors on Skia even though the same document works on AWT. This directly limits cross-platform rendering parity. Android, browser, and pure-raster impact is established by the corresponding source sequence; device/browser execution of the full Kotlin backends remains outstanding.

**Fix direction:** Preserve the masked paint's blend against its real backdrop. A single elementary paint can apply its blend when the masked result is composited; callbacks containing multiple blend modes need proper per-object or backdrop-aware composition. AWT's `LayerBlends`/mixed-blend handling provides an existing comparison. Include the invariant that an all-opaque soft mask leaves every blend mode unchanged, with Alpha/Luminosity masks and mask-transfer functions varied independently.

<a id="RENDER-05"></a>

### RENDER-05 [P2] A failed offscreen render leaves the plain raster canvas drawing into an orphan layer

**Owner:** KitePDF core. **Status:** reproduced from current raster source with a deliberately thrown ordinary `IllegalStateException`, not resource exhaustion.

**Evidence:** `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:257` pushes a layer and invokes `content()` at line 258 without `try/finally`; the pop at line 259 is skipped on failure. `applySoftMask` repeats this pattern around `render()` at lines 209-211 and `renderMask(this)` at lines 214-216. `beginPage` and `endPage` at lines 77-78 are empty, so starting the next page does not repair the state.

**Minimal reproduction:**

```kotlin
val canvas = KiteRasterCanvas(10, 10)
val box = KiteRectangle(0.0, 0.0, 10.0, 10.0)
try {
    canvas.rasterStep(box, KiteMatrix.IDENTITY) { scope ->
        scope.render { error("ordinary callback failure") }
        true
    }
} catch (_: IllegalStateException) {
    // Consumer reports the failed effect and continues using the canvas.
}
val rect = KitePath.Builder().apply {
    rectangle(0.0, 0.0, 10.0, 10.0)
}.build()
canvas.fillPath(rect, KiteMatrix.IDENTITY, RgbColor(1.0, 0.0, 0.0), false)
check(canvas.toRaster()[5, 5] == 0xffff0000.toInt())
```

**Observed:** The final pixel is `0x00000000`. The red paint went to the abandoned top layer, while `toRaster()` reads `layers.first()`. Later paints can remain invisible for the lifetime of the canvas. The thrown exception is correctly delivered to the caller; it is the canvas's failure recovery that is defective.

**Fix direction:** Restore layer, clip, and group depths in `finally` around callbacks, including callbacks that leave an inner group open before failing. Test successful reuse after an exception from the content callback and from the mask callback. Define whether an unsuccessful offscreen operation is discarded or partially committed; the current silent capture of unrelated future paints satisfies neither behavior.

## Renderer coverage and limits

| Area | Work completed | What is not established |
| --- | --- | --- |
| PDF tiling | Signed step parsing, enumeration, cell placement, bounded AWT/MuPDF fixture | Every tiling type, extreme scaling, nested patterns, all clipping cases |
| Transparency | Isolation and knockout state inspected; simple and nested cases reproduced on plain raster, AWT, Skia | Full non-isolated mixed-blend group algebra across all backends |
| Soft masks | Backend routing reviewed; opaque Alpha mask plus Multiply reproduced against AWT/MuPDF | Complete BC/TR matrices, every nested-mask combination, actual Apple/Android execution |
| Raster lifecycle | Layer handoff and failure cleanup inspected and reproduced | Long-duration retained-heap/native-memory measurements |
| Native ownership | CoreGraphics creation/release and Skia temporary-resource paths inspected | Apple ARC/GC runtime behavior, native heap profile, leak-free certification |
| Fonts and geometry | Sampled TrueType caching/parsing, host font selection, stroke pen, scan converter, shader and image routing | Full font-format conformance, shaping coverage, all host font combinations |
| Performance | Source inspection of repeated raster/layer work and allocation boundaries | Release-build throughput, nanosecond latency, GC/dispatch distributions, device frame deadlines |
| Cross-platform | Current JVM sources executed; Skiko 0.153.0 Linux runtime executed | Native Apple, Android device/Robolectric, full Kotlin JS/Wasm backends |

A suspected Canvas2D gradient-transform defect was **rejected**: a bounded browser experiment on Chromium 151.0.7922.173 returned identical pixels whether a gradient was created before or after the drawing CTM. Do not report it as a defect. Image decoding color-space/metadata findings are owned by the ImageKodec integration lane and are not duplicated here.

The native lock's hashed thread identity and Skia cleanup through managed-object finalizers were noted as further review areas. No collision was induced, no native memory growth was measured, and neither is presented here as a reproduced defect or measured regression.

<a id="RENDER-06"></a>

### RENDER-06 [P1] Tiling work-limit arithmetic overflows before conversion to Long

**Owner:** KitePDF PDF renderer. **Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1856-1864,1896-1898`. Each index span is computed as `Int` before conversion to `Long`. A bounded arithmetic check with `i0 = -2147483648` and `i1 = 2147483646` gives `span=-1`; two such spans multiply to `tiles=1`, although each mathematical span is 4,294,967,295. The maximum-tile guard therefore accepts a count that is not the size of the subsequent nested ranges. Finite extreme bounds and small steps can reach saturated endpoints through `Double.toInt()`.

**Impact:** The intended page-work cap is unreliable, and this loop checks no cancellation at its iteration boundary. The operation budget inside `dispatch` can suppress drawing without terminating enumeration. No oversized render or exhaustion experiment was run; the finding is established by integer arithmetic and the loop's source, not a measured hang.

**Remediation:** Widen before subtraction, reject invalid/saturated ranges, and compare multiplication against the budget without overflow. Break enumeration directly on cancellation or work exhaustion. Add bounded tests of the count calculation at signed-Int limits and ensure both positive and negative pattern-step fixes preserve the guard.

## Format pipeline audit contribution

Audited checkout: KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. All defects below belong in **KitePDF**, not ImageKodec or KiteJS. No tracked source, tests or dependency configuration were edited.

Evidence labels: **Reproduced, source subset** means the named source files from this checkout were compiled with cached Kotlin/JVM 2.4.20 and exercised directly. SVG/ZIP probes used the existing `kitepdf-core-jvm-0.12.0.jar` for unchanged dependency classes; they are not a clean build or test of the complete 0.13.0 distribution. ImageDims has no non-stdlib dependency. **Source-established** means the causal control flow was inspected but the full document journey was not executed. Times from the tiny probes are deliberately omitted as performance evidence: no release-build benchmark, warmup study or platform comparison was performed.

<a id="FMT-01"></a>

### FMT-01 [P1] CBZ page measurement escapes salvage on an overflowing TIFF directory offset

**Evidence:** Reproduced, source subset. **Affected:** all targets sharing ImageDims; JVM exception reproduced, Native/JS/Wasm outcomes not run. **Owner:** KitePDF `kitepdf-cbz`.

- `kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/ImageDims.kt:61-65` reads the TIFF IFD offset as Int and checks `ifd + 2 > b.size`. An offset of `Int.MAX_VALUE` wraps that addition negative and reaches `u16(ifd)`.
- `kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/CbzPage.kt:35-42` invokes this reader while evaluating the public page dimensions, outside an exception/salvage boundary. `CbzDocument` chooses page entries by filename extension, while ImageDims sniffs the actual payload; a `page.png` entry containing a TIFF header reaches this path even though `.tiff` is not in the CBZ extension list.

Minimal payload, eight bytes: `49 49 2A 00 FF FF FF 7F`. Direct `ImageDims.of(payload)` printed:

```text
java.lang.ArrayIndexOutOfBoundsException: Index 2147483647 out of bounds for length 8
  at ImageDims.le16(ImageDims.kt:115)
  at ImageDims.tiff(ImageDims.kt:65)
```

Document reproduction: put those bytes in a ZIP entry named `page.png`, open using CbzDocument, and read `pages[0].displayWidth`. The page-size lookup, including viewer layout before drawing, throws instead of producing the documented fallback dimensions/grey page. The direct ImageDims operation was executed; this ZIP-to-viewer journey was source-traced.

**Remediation:** validate unsigned offsets and directory entries with subtraction-based or Long range checks before access; retain a salvage boundary in page header measurement. Regression should assert both width/height lookup and render are recoverable for this payload, including Wasm execution.

<a id="FMT-02"></a>

### FMT-02 [P2] Valid legacy ZIP filenames collapse under unconditional UTF-8 decoding

**Evidence:** Reproduced, source subset with independent JDK ZIP reader. **Owner:** KitePDF `kitepdf-core` ZIP reader, visible through CBZ and any consumer of ZipReader.

`kitepdf-core/src/io/github/yuroyami/kitepdf/core/zip/ZipReader.kt:297-309` reads the flags but always calls UTF-8 `decodeToString` for the filename. It neither selects the legacy ZIP charset when general-purpose flag 11 is clear nor reads the Unicode Path extra field. `:347` then rejects the entire archive when two decoded strings compare equal.

The probe used `ZipOutputStream(out, Charset.forName("IBM437"))` to write two entries, `é.png` and `â.png`, with tiny payloads. The local flag value was `8` (data descriptor, UTF-8 flag absent). Output:

```text
KitePDF names=[]
JDK entry=é.png
JDK entry=â.png
```

Both leading filename bytes are invalid standalone UTF-8 and turn into the same replacement character, so a legal archive appears empty. Even without a collision, exposed names and natural ordering are wrong. This is a container-decoding defect, not an image-codec failure. EPUB normally requires UTF-8 names, so the strongest real-document case is a legacy comic ZIP, not a conforming EPUB.

**Remediation:** honor the ZIP UTF-8 flag and supported Unicode-path metadata, with a documented legacy fallback (CP437 for the baseline ZIP convention). Compare duplicate names after correct decoding. Regression should include a two-name collision case, ordinary UTF-8, and a Unicode-path extra-field case.

**Final current-build confirmation for FMT-02:** The ZIP probe also ran against the regular toolchain build of current core with stdlib 2.4.21. It still returned an empty KitePDF name list while the JDK returned both distinct names. Log: `/workspace/scratch/audit-current-zip.log`.

<a id="FMT-03"></a>

### FMT-03 [P1] SVG reference depth does not bound exponentially repeated drawing work

**Evidence:** Reproduced with a small, acyclic SVG and a no-op recording canvas. **Owner:** KitePDF `kitepdf-svg`.

`kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:223-224` checks cancellation and depth only. `:376-379` follows every `<use>` reference independently. `MAX_DEPTH` at `:1704` is 32; there is no shared draw-operation budget or DAG expansion budget for this render. Symbols avoid an extra traversal of their own node, so a chain of symbols containing two uses of the previous symbol doubles actual leaf work per level.

Probe construction: symbol `s0` contains one 1-by-1 rectangle; `sN` contains two `<use href='#s(N-1)'/>` elements; the page contains `<use href='#sN'/>`. Actual fill counts:

| Chain depth | SVG bytes | Rectangle fills |
| --- | ---: | ---: |
| 8 | 624 | 256 |
| 12 | 868 | 4,096 |
| 16 | 1,116 | 65,536 |

This is an operation-count result, not a timing claim. Larger chains were not executed. Source implies the same doubling continues while the depth guard permits it. Rendering without a cancellation callback can spend excessive work on a small file. Text/link extraction also renders into a special canvas with `stop = null` (`:145`), so optional rendering cancellation is not a universal escape hatch. EPUB-embedded SVG shares this implementation.

**Remediation:** pass a shared work budget through render, symbol/use expansion, bounds passes, patterns, masks and filters; detect actual cycles separately from legal depth. Validate operation counts with bounded DAG fixtures, and verify budget exhaustion still balances clips/groups and salvages the page. XPS already has a page-wide `XpsRenderBudget`, a useful in-repository comparison.

<a id="FMT-04"></a>

### FMT-04 [P1] SVG keeps every named filter result, even when no later primitive reads it

**Evidence:** Reproduced, source subset, in a capped 128 MiB JVM. **Owner:** KitePDF `kitepdf-svg`.

- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgFilter.kt:131-166` stores every primitive with a `result` name in `named` until the entire filter finishes. There is no liveness reclamation or total intermediate-byte ceiling.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/FilterImage.kt:16-20` allocates four Floats per pixel, 16 bytes per pixel per image.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1108` accepts every filter child as a primitive; `:1396-1405` only reduces resolution based on the per-image 4,000,000-pixel threshold, not the number of live images.

The executed probe ran 32 `FilterPrimitive.Flood` operations on a 1000-by-1000 source. With no `result` names it completed. Giving each flood a distinct unused name caused:

```text
named=false outputPixels=1000000
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
  at FilterImage.<init>(FilterImage.kt:20)
  at FilterOps.flood(FilterImage.kt:320)
  at FilterContext.apply(SvgFilter.kt:184)
  at FilterContext.run(SvgFilter.kt:161)
```

The XML equivalent is a filter containing 32 `<feFlood flood-color='red' result='unusedN'/>` children. Each is independent and none reads the earlier results; the final output can be evaluated with constant live image storage. At these dimensions, named results alone request 512,000,000 bytes across the run, before source/working images. The internal filter path was executed; a full backend render of that XML was not.

**Remediation:** determine each result's last use and release dead results; enforce a total live-intermediate budget and primitive/work budget. A per-image pixel cap does not bound total filter memory. Preserve identical pixels for the named/unnamed dead-result cases.

<a id="FMT-05"></a>

### FMT-05 [P2] Rendering a symbol skips its own style and viewport

**Evidence:** Reproduced, source subset with geometry/color recording. **Owner:** KitePDF `kitepdf-svg`.

`kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:374-377` draws a symbol's children directly with the `<use>` paint and translation. It never resolves the symbol's presentation attributes, `viewBox`, `preserveAspectRatio`, or the `<use>` width/height viewport. The non-symbol branch calls `walk(target, ...)`, which does apply target paint, making this a symbol-specific gap.

Input:

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100">
  <defs><symbol id="s" viewBox="0 0 10 10" fill="red">
    <rect width="10" height="10"/>
  </symbol></defs>
  <use href="#s" x="20" y="30" width="100" height="50"/>
</svg>
```

Expected under SVG symbol/use viewport and inheritance rules: red rectangle spanning `(45,30)` to `(95,80)` for default `xMidYMid meet`. Actual recorded output: black rectangle spanning `(20,30)` to `(30,40)`, matrix scale 1 with translation `(20,30)`. Even a symbol without a viewBox loses its own `fill` declaration. Existing `SvgFeaturesTest.use_of_a_symbol_paints_its_children` puts `fill` on the child and omits viewport attributes, so it cannot detect either behavior.

**Remediation:** instantiate the symbol's viewport and inherited paint before walking its children, including width/height overrides, zero dimensions, meet/slice/none, clipping and symbol attributes. Check both standalone SVG and an SVG within an EPUB.

**Final current-build confirmation for FMT-05:** The symbol probe also ran against the regular toolchain build of current SVG/core with stdlib 2.4.21. It still recorded the black unscaled rectangle `(20,30)-(30,40)`. Log: `/workspace/scratch/audit-current-svg-symbol.log`.

<a id="FMT-06"></a>

### FMT-06 [P2] A missing local font source prevents every later fallback from being tried

**Evidence:** Source-established; full EPUB reproduction not executed. **Owner:** KitePDF `kitepdf-epub`.

`kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:567-571` has a `?: continue` for a missing remote font, but none for a missing or unreadable local font. It unconditionally returns `program?.let { ... }` from the first local iteration. A null local program returns from `loadFace` instead of advancing to the next candidate.

Minimal author CSS:

```css
@font-face { font-family: Example; src: url(missing.ttf), url(working.ttf); }
p { font-family: Example; }
```

Include a valid `working.ttf` in the manifest/archive and leave `missing.ttf` absent or malformed. Both sources have the same extension so the separate source-order defect below does not affect this trigger. Expected: working.ttf supplies the face. Actual causal path: programAt(missing.ttf) returns null, loadFace returns null, working.ttf is never read. The book substitutes a host font, changing glyph coverage, line breaks and pagination. This directly conflicts with the method's own CSS Fonts 4 §4.3 comment and documented fallback behavior.

**Remediation:** continue on an unusable candidate for both local and remote paths, while distinguishing an in-flight remote resource according to the established layout policy. Test missing and corrupt first sources followed by a valid one, then the all-unusable case.

<a id="FMT-07"></a>

### FMT-07 [P2] Font source selection changes author precedence based on filename extension

**Evidence:** Source-established; no font pixel comparison executed. **Owner:** KitePDF `kitepdf-epub`.

`kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:580-587` sorts `@font-face src` URLs into TTF/OTF, WOFF, WOFF2 and other groups before loading them. CSS Fonts 4 §4.3 defines this as a prioritized list supplied by the author; different entries need not contain identical fonts or coverage. An extension-based optimization changes which face is selected.

Example: `src: url(preferred.woff2), url(fallback.ttf)`, with both valid but visibly different. KitePDF chooses fallback.ttf first. `src: url(https://example.test/preferred.woff2), url(fallback.ttf)` is worse: `remoteFirst` and `remoteSourcesOf` at `:589-593` use the same reordered list, so the higher-priority remote face is not requested through that rule at all. Suffix detection also treats query-bearing `.ttf?v=1` as an unknown format.

**Remediation:** preserve author source order; optimize decoding/caching only after selection. Test different fonts in two source formats and remote-first/local-fallback combinations. This is independent of FMT-06: it occurs even when every listed file exists and parses correctly.

<a id="FMT-08"></a>

### FMT-08 [P2] A global CSS import visited set removes valid repeated imports and changes the cascade

**Evidence:** Source-established; full EPUB reproduction not executed. **Owner:** KitePDF `kitepdf-epub`.

`kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:637-642` shares one mutable `visited` set for the whole import expansion and never removes a stylesheet after its recursive call returns. This prevents cycles, but also removes later acyclic imports of the same file at distinct cascade positions.

Use these manifested stylesheets, with the chapter linking `main.css`:

```css
/* main.css */ @import "a.css"; @import "b.css";
/* a.css */    @import "c.css"; p { color: red; }
/* b.css */    @import "c.css";
/* c.css */    p { color: blue; }
```

Expected CSS import expansion is blue, red, blue, leaving blue as the winning declaration. Actual expansion is blue, red: the second c.css is deleted because it is already in the set. The paragraph renders red. This graph contains no cycle and every import precedes normal rules in its own file.

**Remediation:** use an active recursion-path set for cycle detection and preserve each legal occurrence in source order. Parsed-sheet caching can share representation without deleting cascade occurrences. Regress both the repeated-import diamond and an actual cycle; retain an expansion/work budget separately.

<a id="FMT-09"></a>

### FMT-09 [P2] Remote-resource work has no book disposal or concurrency bound

**Evidence:** Source-established; no additional network or resource-exhaustion experiment was performed. **Owner:** KitePDF `kitepdf-epub`; caller-owned HttpClient configuration may mitigate but does not supply a document lifecycle API.

`kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubRemote.kt:90` creates a private `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. Every new URL starts a child at `:106-116`. `EpubDocument.fetchRemoteResources` at `EpubDocument.kt:204-209` requests every selected URL before awaiting any. `awaitLayout` at `EpubRemote.kt:126-131` stops waiting after a deadline but leaves those jobs running. The landed-byte budget is enforced only after fetch returns at `:145-153`; it does not bound in-flight resources or tasks. No cancellation/disposal method exists on RemoteResources or EpubDocument.

The documented decision to let a caller stop waiting without aborting book-owned fetches is reasonable. The missing complementary operation is an explicit end to the book's lifetime. A fetcher suspended on live network I/O can retain its job/resources after the viewer and caller abandon the book, and a chapter with many distinct resources initiates all of them immediately. A custom fetcher or client timeout can finish each operation eventually, but the library cannot end this work when the document is discarded or cap its fanout. The same store is shared by `withSettings`, so ownership should be designed for shared parses rather than blindly cancelling on an individual view disappearing.

**Remediation:** give the shared parsed resource store an explicit close/lease lifecycle or caller-supplied scope, add bounded concurrency and a pending/request budget, and make book shutdown cancel in-flight fetches. Preserve ordinary timeout/waiter semantics. Required validation: close while fetching, many URLs with a counting fetcher, final-owner release across withSettings, and bounded live request storage. This is an ownership design defect, not a claim that every suspended coroutine is intrinsically retained forever.

### Coverage, capabilities and remaining gaps

| Area | Reviewed | Result or limit |
| --- | --- | --- |
| CBZ | Extension filtering, natural-order entry route, lazy dimension sniffing, decode-cache route | FMT-01, FMT-02; image decoder algorithms belong to ImageKodec and were not audited here |
| SVG | Use/symbol traversal, depth/cancellation, nested viewports, image resolution, filters, text/link pass routes | FMT-03 to FMT-05; no browser raster corpus or cross-platform rendering comparison performed |
| EPUB | Parsed-spine/resource caches, settings and remote sharing, stylesheet imports, web-font source selection, image-spine handling, layout staging and budget boundaries | FMT-06 to FMT-09; no exhaustive CSS, bidi, shaping, pagination, media overlay or script DOM conformance claim |
| XPS/OpenXPS | Package part resolution, split parts, page/header caches, font selection/TTC offsets, renderer work budget, glyph/text/link routes, resource dictionaries | No additional source-established defect promoted in this lane; sideways text, synthetic styles and extraction box approximations are already disclosed in docs/xps.md |
| Network | Ktor streaming fetch, response limits, cancellation passthrough, diagnostic URL redaction | Source review only; no live server/redirect/engine matrix run |
| Format coverage | KiteDocFormat and umbrella dispatch | End-to-end direct handlers are PDF, EPUB, CBZ, SVG and XPS/OpenXPS. No MOBI/AZW/FB2/DjVu/CBR/CHM direct handler exists in this checkout's enum/dispatch; an exact-word search across module source, README and docs found no alternate reader route. Those are opportunities relative to the user's goal, not an assertion that current docs promise them |

Capability details: XPS/OpenXPS is read/render only (explicit in its Module.md and docs); it is not an XPS writer. Standalone SVG supplies no external-resource loader, so external image references need embedding or the SvgImage loader API. CBZ currently recognizes JPG/JPEG/PNG/GIF/BMP/WebP extensions, even though the underlying image dependency supports more formats. These are boundary facts/opportunities unless a public claim states otherwise.

Unfinished review candidates, **not findings**: font container total allocation budget and WOFF integer validation, cumulative retained parsed DOM/font data beyond layoutCacheBytes, render exceptions balancing beginPage/endPage in SVG/CBZ, and more XPS brush/path corner cases. They require call-path verification and/or representative reproduction before promotion. No measured nanosecond performance, Apple/Android device, JS/Wasm runtime, Swift/ObjC integration, or all-parameter Cartesian product coverage is claimed.

### Reproduction artifacts outside the repository

The executed small probes are retained in `/workspace/scratch/audit-formats/`: `ImageDimsProbe.kt`, `ZipCharsetProbe.kt`, `SvgFanoutProbe.kt`, `SvgFilterMemoryProbe.kt`, `SvgSymbolProbe.kt`, and `compile.py`. Their results above are self-contained so the consolidated report need not depend on scratch retention. `legacy-cbz.zip` is the generated two-entry ZIP. No full project gate was run by this lane.

## Codec integration audit

### Provenance and verification limits

KitePDF checkout: `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. Its `libs.versions.toml:20` pins ImageKodec `0.3.0`. The adjacent ImageKodec checkout is `f25823b74d76b6708faaaced3daf7cb5c42eec1d`, with `version=0.2.0`; that checkout alone would not establish a defect in the dependency used by current KitePDF.

Downloaded the exact Maven Central `io.github.yuroyami:imagekodec-jvm:0.3.0` binary and sources, and executed the probes below against that binary on JDK 21. Artifact URLs:

- https://repo.maven.apache.org/maven2/io/github/yuroyami/imagekodec-jvm/0.3.0/imagekodec-jvm-0.3.0.jar
- https://repo.maven.apache.org/maven2/io/github/yuroyami/imagekodec-jvm/0.3.0/imagekodec-jvm-0.3.0-sources.jar

SHA-256:

- Binary: `bdaf1a3c14786cefeace1c8beb98b7f8450fa4f82a195a983382203d2bcd8587`
- Sources: `920187702ef4d1e081eb18936153bc34aea7e34e51737b81b04f60f131cbbe73`

`diff -qr` finds all common Kotlin source files in the source artifact byte-identical to the adjacent checkout except `ImageInfo.kt`. Consequently, cited ImageKodec source lines below refer to files also verified in the pinned artifact, not an assumption about current upstream. Artifact sources live under `commonMain/io/github/yuroyami/imagekodec/`; local checkout equivalents live under `imagekodec/src/commonMain/kotlin/io/github/yuroyami/imagekodec/`.

No repository source was edited. No GitHub issue or fix was created. Codec facade behavior was executed against the published binary; the current KitePDF bridge and its callers were traced in source. The bridge was not compiled or run as part of these probes. This is not an exhaustive codec conformance or all-platform audit.

<a id="CODEC-01"></a>

### CODEC-01 [P2] Convert standalone image samples to the viewer's color space before discarding their profile

**Owner:** KitePDF, not ImageKodec.

**Primary evidence:** `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:481-487`; `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageAdapter.kt:39-46`.

**Trigger:** An EPUB, CBZ, SVG image or XPS image brush contains a PNG with non-sRGB `gAMA`/`cHRM`, or an image with a non-sRGB ICC profile. The issue is independent of Compose `Vectorized` versus `Rasterized` mode because it occurs before either backend sees the image.

**Cause:** `fromEncodedImage` calls `ImageKodec.decode(bytes)` without `colorTarget`. ImageKodec explicitly defaults that argument to `ColorTarget.Source` (`ImageKodec.kt:97-109`), which keeps the stored samples. `toKiteImageData` then labels those samples `DeviceRGB`, sets `resolvedColorSpace = DeviceRGB`, and drops the encoded bytes and profile. There is no remaining profile for a renderer to apply. ImageKodec already supplies the required conversion when called with `ColorTarget.Srgb`.

**Observed reproduction:** A 1x1 RGB PNG storing `(128,128,128)` and `gAMA=100000` describes linear-light gray. On the exact 0.3.0 binary:

```text
probe=ImageInfo(PNG 1x1)
raw=1x1 ff808080 corrected=1x1 ffbcbcbc
```

`raw` is `decode(bytes, false, ColorTarget.Source)` and `corrected` is `decode(bytes, true, ColorTarget.Srgb())`. Orientation is immaterial for this PNG. Independently, the sRGB transfer function gives `round((1.055 * (128/255)^(1/2.4) - 0.055) * 255) = 188`, matching `0xBC`, not `0x80`.

A fixture can be generated without third-party image encoders:

```python
import struct, zlib
be = lambda n: struct.pack('>I', n)
def chunk(t, body):
    return be(len(body)) + t + body + be(zlib.crc32(t + body) & 0xffffffff)
png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', be(1) + be(1) + bytes([8,2,0,0,0]))
       + chunk(b'gAMA', be(100000))
       + chunk(b'IDAT', zlib.compress(bytes([0,128,128,128])))
       + chunk(b'IEND', b''))
open('linear-gray.png', 'wb').write(png)
```

**End-to-end entry points:** `EpubDocument.kt:1601-1605`, `CbzPage.kt:54-61`, `SvgImage.kt:395` and `:1439`, `XpsBrushes.kt:80-83`. A package containing the generated PNG follows the same bridge. A full Compose screenshot was not produced.

**Impact:** Clearly incorrect brightness and color in ordinary supported documents; wider-gamut or HDR content can be misrepresented even though the upstream codec implements conversion. Alpha and raster rendering options do not restore discarded metadata.

**Remediation:** Give standalone image decoding a color-managed destination, or preserve and apply the source profile consistently through every backend. This change belongs in the standalone image path; PDF image XObjects have their own `/ColorSpace` rules and should not inherit the change blindly. Add a browser/reference comparison for linear PNG, ICC RGB and grayscale, and an alpha-bearing fixture.

**Verification:** Pinned codec binary reproduced; current bridge source established. No device, browser, or full document render executed for this finding.

<a id="CODEC-02"></a>

### CODEC-02 [P2] Apply image orientation consistently to decoding and intrinsic dimensions

**Owner:** KitePDF, not ImageKodec.

**Primary evidence:** `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:481-487`; `kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/CbzPage.kt:35-42` and `:60-71`.

**Trigger:** A JPEG with EXIF orientation 6 or 8, common in phone photos, used as an EPUB image, a CBZ page or an SVG image. Mirrored orientations are affected too.

**Cause:** The same bridge leaves ImageKodec's `applyOrientation=false` default in place. `toKiteImageData` preserves stored width, height and sample order, then drops the metadata. No later backend knows the image should rotate. CBZ additionally determines the page's intrinsic aspect ratio through `ImageDims.of` before decoding, so fixing pixels alone would still leave the page geometry wrong.

**Observed reproduction:** Generated a 4x2 JPEG with EXIF tag 274 set to 6 using Pillow:

```python
from PIL import Image
im = Image.new('RGB', (4, 2), 'red')
exif = Image.Exif()
exif[274] = 6
im.save('rotate90.jpg', exif=exif)
```

On the exact ImageKodec 0.3.0 binary:

```text
probe=ImageInfo(JPEG 4x2 Rotate90)
raw=4x2 fffe0000 corrected=2x4 fffe0000
```

The codec recognizes and implements the orientation. The bridge selects the `raw` behavior. The solid-color fixture establishes the dimension error; an asymmetric corner-color fixture should be used for a future full rendering regression to test rotation and mirroring as well.

**Impact:** Normal photos and scans display sideways or mirrored, and 90-degree orientations give the wrong natural aspect ratio. Browser image rendering normally uses image-provided orientation; this creates observable EPUB/SVG parity failures as well as CBZ usability problems. Rendering mode changes cannot repair metadata already discarded.

**Remediation:** Handle orientation in the standalone image bridge and update intrinsic-size probes, especially CBZ's header-only size path, to use oriented dimensions. Preserve PDF image XObject semantics separately. Decide and document how authored CSS `image-orientation` overrides are supported rather than always rotating all image uses unconditionally.

**Verification:** Pinned codec binary reproduced; current bridge and CBZ geometry source established. Current EPUB/SVG CSS orientation handling was not comprehensively audited, and the issue is scoped to the default image path. No claim that every XPS image should apply EXIF orientation is made.

### Further concrete source observations requiring an independent fixture

These are not included in the two confirmed integration findings above.

1. **PDF `/ColorSpace` on JPX images is discarded on successful decode.** `KiteImageData.kt:424-427` and `:433-443` replace the already-resolved PDF space with `DeviceRGB` or `DeviceGray`; only the failure branch at `:445-449` retains `resolvedCs`. ISO 32000-1, Table 89 and 7.4.9, permit the image dictionary to supply the space; a raw one-component JPX codestream used with an Indexed space is a useful next fixture. ImageKodec's public `JpxDecoder.Result` exposes converted gray/RGB samples, so a complete fix may also require an upstream component-preserving API. No JPX specimen/reference render was produced here, so color precedence and component conversion still need an independent reproduction before issue posting.
2. **XPS explicit color profile is parsed away.** `XpsBrushes.kt:74` strips `{ColorConvertedBitmap imageURI profileURI}` down to `imageURI`. The external profile is never loaded by that function. This is distinct from embedded image profile handling, but needs an XPS package with an external profile and a reference raster before finalizing the full semantics and expected result.

### Existing bounded resource observation, not a release benchmark

A single previously launched probe used the exact 0.3.0 binary on JDK 21 with `-Xmx128m`, a 100-byte JBIG2 input, a requested output of 1x1, one 1x1 pattern, and a halftone sampling grid of 4096x4096. It returned one output byte (`80`) in `620.810 ms`. Source: `Jbig2Decoder.kt:568-625` accepts a halftone grid independently of output size and decodes the full grid before composing the small output; `decodeGeneric` continues decoding synthetic end-of-data bits rather than inspecting `MqDecoder.exhausted` in its pixel loop. This establishes that the output rectangle alone does not bound decode work. It does not establish a production latency figure or an unbounded-loop defect. No additional exhaustion experiment was performed. A future upstream resource-budget review should cover this path and aggregate allocation across dictionaries and bitplanes.

### Capability wiring and coverage

| Capability | Upstream 0.3.0 | KitePDF inspected integration | Audit status |
|---|---|---|---|
| PNG/JPEG/GIF/BMP/JP2/WebP/TIFF/AVIF/JXL static decode | Facade dispatch exists | All routed by `fromEncodedImage` | Dispatch inspected; not each format decoded in this audit |
| Embedded color conversion | `ColorTarget.Srgb` | Default `Source`, metadata then discarded | CODEC-01 |
| EXIF orientation | `applyOrientation`, `probe.orientation` | Default false, no retained orientation | CODEC-02 |
| JPEG/JPX reduced decoding | API and implementation exist | PDF `KiteImageSamples` uses them | Source traced; no throughput or peak-memory measurements |
| Animation frames | `decodeAnimation` | `fromEncodedImage` uses static decode | First-frame behavior, not a new defect unless a separate animation contract promises otherwise |
| TIFF additional pages | `decodePage`/`probePage` | Static bridge uses first page | Capability not wired through this bridge; no claim about broader document API support |
| PDF JBIG2/CCITT | Parameterized codec APIs | Routed directly from core | Selected source inspection; no exhaustive reference corpus |
| PNG gamma normal decoding | Executed pinned JVM binary | Source traced through bridge | Reproduced codec behavior |
| JPEG EXIF rotation | Executed pinned JVM binary | Source traced through bridge | Reproduced codec behavior |
| Native/JS/Wasm codec parity | Common source inspected | No native build needed for the two bridge defects | No runtime validation on these targets |

Not performed: exhaustive ImageKodec fuzzing, release-build profiling, peak-memory/GC runs, all ICC profile classes, all JPX/JBIG2 features, independent AVIF/JXL conformance, Apple framework ABI checks, or published JS/Wasm artifact execution. No general claim of ImageKodec safety or complete format parity follows from this audit.

## Distribution, documentation and test-infrastructure audit

Owner of these findings: **KitePDF**. Source revision is the same as the other lanes. Published metadata was fetched from Maven Central for all 13 public artifacts at 0.13.0, plus the JVM child modules and Skiko 0.153.0. Metadata presence verifies publication and declared variants, not successful consumption on every target. No publication, repository configuration or dependency version was changed.

<a id="DIST-01"></a>

### DIST-01 [P2] The README's headless Skia recipe omits its required JVM native runtime

**Evidence:** `README.md:103-120` says a headless PDF-to-image application needs just the PDF and native-renderer artifacts, with Skia offered as an interchangeable alternative. Its prerequisites table mentions only Skia's Android repository. `kitepdf-skia-renderer/module.yaml:34-48` includes the six platform runtime jars only as test dependencies. Published `kitepdf-skia-renderer-jvm:0.13.0` runtime metadata depends on `skiko:0.153.0`; `skiko-awt:0.153.0` metadata does not depend on any `skiko-awt-runtime-*` jar.

**Trigger/impact:** Follow that README recipe on a plain JVM with the Skia alternative and no Compose runtime dependency. The dependency graph has Skia's Java bindings but no native implementation for first render. The repository's own renderer tests receive the missing runtime and therefore do not exercise this consumer configuration. `docs/getting-started.md:35` already documents the actual requirement, so the two setup guides contradict one another.

**Remediation:** Include a host-runtime example and deployment/architecture selection in the README, link it from the headless recipe, and extend the coordinate-only consumer check with a minimal Skia consumer. Keep runtime selection explicit where shipping all six binaries would unnecessarily increase size.

**Verification:** Current source plus exact published dependency metadata inspected. A clean Skia-only Gradle consumer was not executed; no runtime stack trace is claimed here.

<a id="DIST-02"></a>

### DIST-02 [P2] Java 21 bytecode is published without a JVM-version constraint or a clear consumer requirement

**Evidence:** `common.module-template.yaml:7-11` selects Java 21 and bytecode release 21. Direct inspection of the published `kitepdf-core-jvm-0.13.0.jar` finds class-file major **65**. Both the root `kitepdf-core:0.13.0` JVM variant and its `kitepdf-core-jvm:0.13.0` child omit `org.gradle.jvm.version` from API/runtime variant attributes. Inspected JVM metadata for the viewer and renderers has the same omission. `README.md:479` and `docs/platforms.md:12` merely mark JVM support as yes; the getting-started JVM recipe does not state Java 21.

**Trigger/impact:** A Gradle application targeting Java 17 or 11 has no producer JVM-version attribute with which to reject this incompatible variant during resolution. The supplied classes require Java 21, so an application running on Java 17 or 11 cannot load them successfully even if dependency resolution succeeds. The existing consumer check always uses JDK 21 (`consumer-test/build.gradle.kts:22-24`), leaving this boundary untested.

**Remediation:** Publish the required JVM version on both root and child variants and document it. Add a lower-JVM resolution check that fails with an intentional compatibility diagnostic. If a lower baseline is a product goal, lower the bytecode target only after verifying APIs and dependencies; changing documentation alone cannot make major-65 classes load there. If the Kotlin Toolchain omits the attribute, track that publishing defect upstream while validating the artifacts KitePDF releases.

**Verification:** Published artifact and JSON metadata inspected directly. No Java 17 runtime was installed or executed; the load incompatibility follows from the verified class-file version, not a fabricated execution result.

<a id="CI-01"></a>

### CI-01 [P2] Selecting an unsupported web test module succeeds without running tests

**Evidence:** `scripts/web-tests/run.py:29-35,181-185,194-207` intersects requested modules with hardcoded lists, but does not reject unmatched names or require any selected run. `docs/platforms.md:59` specifically says `kitepdf-net` is tested on Node, although it is absent from `JS_MODULES` and its tests live only in `test@jvm`.

**Executed reproduction:** `python3 scripts/web-tests/run.py --platform js --module kitepdf-net` exits **0**, prints no test result and invokes no Kotlin linking/test command. A misspelled module name follows the same path. This does not mean the default runner executes zero tests; its ordinary included modules have nonzero-test checks.

**Impact:** A targeted validation command can be green while checking nothing, and the documented web network coverage is absent. This can conceal a consumer-specific browser/Node failure in the optional network module.

**Remediation:** Reject unknown or unsupported module/platform selections, report the exact selected matrix, and fail when the requested selection yields no executions. Correct the coverage table or add real cross-platform network tests with the appropriate engine/mock support.

<a id="CI-02"></a>

### CI-02 [P2] Web tests choose npm versions from unrelated cached dependencies

**Evidence:** `scripts/web-tests/run.py:76-90` scans every JS/Wasm klib in the entire toolchain Maven cache and selects the numerically largest version for each npm name. `:101-109` installs that version for an import in the current linked output. It never restricts the package metadata to this project's resolved dependency graph. The cache is restored across revisions in `.github/workflows/ci.yml`.

**Executed bounded reproduction:** Imported the unchanged Python module, assigned a temporary `CACHE`, and placed two tiny klib ZIPs under `.m2.cache/example/library-js/1.0/` and `/2.0/`, declaring `example-runtime` versions `1.0.0` and `2.0.0`. `klib_npm_versions()` returns `{'example-runtime': '2.0.0'}` without consulting the linked project's resolved artifacts. The consequence for a project linked against the 1.0 artifact follows from the installation call path; no linked project was executed by this fixture. No npm installation or external package execution was needed.

**Impact:** Merely building a different revision/project can change the runtime used to test unchanged linked code. A warm cache and clean cache can therefore produce different results; the installed JavaScript runtime may not match the Kotlin bindings under test. This is a dependency-selection defect even if current cached versions happen to be compatible.

**Remediation:** Derive npm requirements from the actual linked compilation's resolved klibs, resolve version conflicts explicitly, and preserve a reproducible dependency manifest. Do not use the download cache as a dependency graph. Test that adding an unrelated newer klib cannot change the selected runtime.

<a id="BENCH-01"></a>

### BENCH-01 [P3] The generated benchmark report labels minimum timings as medians

**Evidence:** `kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/BenchmarkTest.kt:40` prints "Median of timed iterations after warmup". The shared `bench` function at `:186-199` retains and returns only the minimum. Increasing `KITEPDF_BENCH_SCALE` adds more chances for a fast minimum rather than estimating a median.

**Impact:** Every table in that generated report misstates its statistic, encouraging invalid comparisons with median-based competitors. This finding does not assert that selecting a minimum is itself wrong for a deliberately best-case regression gate.

**Remediation:** Label the statistic accurately or compute the promised median, retain samples, and report dispersion, warmup, build mode, hardware and corpus. Keep regression thresholds distinct from public performance claims. No new throughput numbers or nanosecond measurements were produced in this audit.

### Distribution and measurement coverage limits

- Maven metadata for all 13 public 0.13.0 artifacts was readable. The seven format artifacts each advertise 61 variants; optional artifacts advertise fewer. A variant listing is not an execution or native-loader test.
- The JVM consumer check covers the umbrella, platform renderer and scripting. It does not cover Compose, Skia, net, media, Swift/XCFramework consumption, Android shrinkers or native 16 KiB page alignment.
- The checked-in ABI dumps protect declared surfaces, but no old-client binary suite or previous-release behavioral compatibility matrix was executed here. The Apple framework script was inspected; export/header usability still needs a real Swift consumer on Apple hardware.
- Existing render-budget tests average a whole document and use fastest-of-three timings. They do not establish per-page tail latency, frame deadlines, cross-platform superiority, retained memory or uncertainty. No conclusion that KitePDF matches/exceeds PDFium, MuPDF or Acrobat performance is justified by this audit.
- The licensed corpus manifest and NOTICE structure were reviewed. The corpus integrity check passed; that is not a legal review of every dependency, bundled table/font, redistribution obligation or native license.

## Cross-library capability and comparison matrix

| Capability | What ships in this snapshot | Comparison evidence and limit |
| --- | --- | --- |
| PDF parse, text, render, create and edit | Public PDF module plus umbrella, writer/editor and multiple canvases | Current JVM suites and specific MuPDF comparisons executed; encryption/ObjStm, font-state and redaction combinations fail |
| EPUB 2/3 | Reflow/fixed layout, CSS, fonts, scripts and optional media | EPUB suite ran with stated oracle skips; CSS fallback/import, symbol and offscreen-cache defects show end-to-end gaps |
| CBZ | ZIP with JPG/JPEG/PNG/GIF/BMP/WebP page extensions | Codec supports more formats than the CBZ filename filter exposes; legacy names and orientation need correction |
| SVG | Document/image handler, symbols, filters and an image-loader API | Small geometry/filter probes executed; symbol semantics fail; no full browser conformance verdict |
| XPS/OpenXPS | Read/render/text/link paths | JVM suite ran; one raster oracle skipped; no writer/editing promise inferred |
| Other ebooks | No direct MOBI/AZW/FB2/DjVu/CBR/CHM handler found in the dispatch or source search | User goal exceeds shipped handlers; adding them is product work, not a regression by itself |
| Direct Compose drawing | Vectorized and Rasterized modes, layouts, live forms, selection and semantics | Concrete mode/layout/cache/lifecycle combinations reviewed; the complete continuous-valued parameter space was not enumerated |
| Optional script engine | KiteJS API/QuickJS 0.7.0 through `kitepdf-javascript` | Real released JVM execution; no standalone full KiteJS conformance audit |
| Image codec breadth | ImageKodec 0.3.0 supports substantially more than basic JPEG/PNG | Exact codec probes show gamma/orientation work upstream but are not selected downstream; no exhaustive AVIF/JXL/JPX conformance claim |
| MuPDF | Local independent oracle 1.27.2 used for selected valid PDFs | It confirms the reported specific discrepancies; this does not establish universal correctness of either engine |
| PDFium | Repository pins pypdfium2 5.13.0; local package is 5.13.0, engine 153.0.7999.0 | Existing parity harness inspected and sampled sweep passed during the full JVM gate, with its existing exemption policy; not a universal parity verdict |
| Acrobat | No runnable licensed reference or version pin provided/exercised | No measured feature/performance ranking or Acrobat-output verdict is claimed |

## Parameter and lifecycle combinations still requiring systematic execution

The finding-level triggers identify concrete broken combinations. A release decision additionally needs a finite risk-based matrix, with pairwise coverage plus targeted higher-order combinations. Pairwise coverage alone cannot prove three-way interactions safe.

| Dimensions | Required representative combinations | Current audit coverage |
| --- | --- | --- |
| Layout/input | Continuous, Paged, Spread, SinglePage x orientation x reverse order x scrolling/pan/selection x fit/zoom | Source review and C-02; complete gesture scenes pending |
| Rasterization | Vectorized/Rasterized x quality x bitmap cap x hairline x theme x host fonts x decorations x cache budget | C-03/04/09/11, isolated probes and source; full image/transition matrix pending |
| Document mutation | PDF forms/annotations or EPUB scripts/resources x cached page/tile/thumbnail/accessibility x replacement handler/document | C-03/05/06/07/08, JS and media findings; transition timing on actual apps pending |
| Resource handoff | Cancellation before/after enqueue, acquire, decode, paint, callback and close x repeated/double close | Runner and raster callback probes; all Kotlin/Native/JS/Wasm schedules pending |
| PDF output | Encryption x object streams x incremental/rewrite x signatures x page-tree/resources x font subset/state | Selected failures reproduced; interrupted-write durability and broad independent-reader matrix pending |
| Graphics | Isolation x knockout x nested form x opacity x blend x soft mask x canvas | Several independent pixel counterexamples; full backend matrix pending |
| Integration | Coordinates vs source dependency x Java/Swift/JS x release/shrinker x native architecture | Published metadata and released JVM probes; clean consumers on every target pending |

## Performance verdict and useful next measurements

The audit establishes specific algorithmic/lifecycle costs, including repeated offscreen layout, cumulative diagnostic copying, redundant SVG expansion and excessive live filter intermediates. It does not establish their production percentile costs, or general relative speed. There is no valid nanosecond result to report from this environment.

For performance acceptance, retain raw samples from release builds on representative Android, Apple, desktop and browser hardware. Separate cold open, first useful page, first-font/image decoding, warm page turn, rapid navigation, sustained zoom, text extraction, scripting and save. Record median and p95/p99 with uncertainty, allocations, retained heap after repeated open/close, native bytes, binary size and frame misses. Compare the same documents, DPI, color management, font environment, warmup and output correctness across engines. A faster blank or incomplete page is not a performance success. Scheduling/micro-call measurements should follow end-to-end profiles showing that they matter, with proper clock calibration and batching.

## Opportunities, separate from defects

| Opportunity | User reach / value | Relative cost and acceptance condition |
| --- | --- | --- |
| One canonical render identity for page, tile, thumbnail and semantic data | High: prevents several cross-parameter stale-state defects | Medium: include every relevant content/form/theme/font/backend generation; transition tests must cover all consumers |
| A shared graphics/text-state model for renderer, extractor, writer and redaction | High: reduces divergent interpretations demonstrated in PDF-02/03/06 | Medium to high: preserve lenient salvage and verify independent output; avoid an unsupported wholesale rewrite |
| End-to-end resource ownership and bounded diagnostics/work | High for mobile and long reader sessions | Medium to high: explicit owners/leases and cancellation handoffs, measured retained memory and predictable failure delivery |
| Clean published-consumer matrix | High for adoption outside the author's environment | Medium: Java, Swift, JS/Wasm, Android shrinkers/ABIs/page alignment and optional native runtime setup |
| Broader comic image extensions and correctly wired color/orientation | Moderate to high, with upstream functionality already present | Low to medium for filtering, higher for color/geometry policy and cross-backend tests |
| FB2, MOBI/AZW, DjVu, CBR or CHM handlers | Potentially broad ebook reach; demand should determine order | High and format-specific: independent oracle, corpus/licensing, metadata/navigation/text/render support before advertising |

## Limits on the final assessment

Apple, Android-device, JS/Wasm runtime parity, complete native lifetime behavior, full specification tables, all published sample builds, all URL/link checks, full licensing review, every previous-release compatibility boundary and release performance/soak studies remain unverified. Review interruptions prevented additional malformed-input experiments; existing bounded evidence and source-established checks are retained with their limits. These gaps are not silently recorded as passes. Conversely, suspected Canvas2D gradient-transform behavior was tested and rejected as a defect, and documented format limitations were not repackaged as new bugs.

The next repair order should begin with incorrect saved data and redaction/trust contracts, valid PDFs that cannot open, script initialization/lifetime failures, and unbounded repeated work. Then address rendering parity, Compose state transitions, metadata wiring and distribution/verification defects. Each item can be reviewed and turned into its owning repository's issue after the owner's requested private review.
