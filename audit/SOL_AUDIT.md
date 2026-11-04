# SOL_AUDIT: KitePDF, ImageKodec and KiteJS

Audit date: 2026-11-04, Africa/Algiers. Requested output: this one file. During audit execution, no implementation fixes, commits, public issues, comments or issue-state changes were made.

## Result and scope

**49 evidence-backed findings: 4 P1, 43 P2 and 2 P3.** Ownership is 45 in KitePDF, two in ImageKodec and two in KiteJS. Forty-four have an executed JVM, real Compose scene, published-binary or isolated-tooling reproduction; five distribution/interop/documentation findings use source, metadata or language-contract proof without executing the affected consumer. Each finding distinguishes that evidence. PDF-05 is an already documented limitation reproduced here, not a claim of undisclosed novelty.

The strongest failures are recoverable redacted text, lost existing fields while preparing signatures, saving before a suspended field commit completes, and nonrecoverable Swift-facing errors. Other defects affect rendering, dynamic layout/cache correctness, resource ownership, valid-format support, performance and the trustworthiness of validation.

This is a substantial multi-lane audit of the available snapshot, **not a certificate of perfection, complete specification conformance, complete Cartesian parameter coverage, or performance superiority**. The tree contains 1,316 Kotlin files and about 610,612 lines including tests/examples. Coverage and unexecuted targets are recorded below instead of treating shared Kotlin source or a passing aggregate score as proof. Every reported document/code trigger is valid or an ordinary lifecycle/error-recovery sequence. Malformed-input hardening, hostile-input fuzzing and decompression-bomb discovery were excluded at your request. Existing full suites were run unchanged and naturally include their preexisting robustness tests.

## Audited versions and evidence provenance

| Component | Audited source/artifact | Relationship |
|---|---|---|
| KitePDF | `c4e7574b143689a8d115aba6dcd605cc4cc909f3`, source version 0.13.0 | Current checkout in `/workspace/KitePDF`; implementation stayed unchanged |
| ImageKodec | Published JVM/source artifacts 0.3.0; local source `f25823b74d76b6708faaaced3daf7cb5c42eec1d` | KitePDF pins 0.3.0; published source matches local Kotlin implementation, with one KDoc-only difference |
| KiteJS API/QuickJS | Published JVM/source artifacts 0.7.0 | KitePDF pins 0.7.0; no KiteJS repository checkout was available |
| Official Kotlin build | Toolchain 0.13.0, declared Kotlin 2.4.21, JVM | Existing suites compiled and executed from current source |
| Isolated Kotlin probes | Cached Kotlin 2.4.20, JDK 21 / JVM target 21 | Exact current implicated sources or current official class outputs; stated individually, not mislabeled as official release runs |
| MuPDF | `mutool` 1.27.2 | Independent PDF/XPS fixtures and explicit-path differential sweeps |
| PDFium | PDFium 153.0.7999.0 through pypdfium2 5.13.0 | Explicit-path parity run, result recorded below |

Initial cached KitePDF 0.12.0 outputs were stale. They were used only to bootstrap some isolated compilations; final relevant runtime probes use current core or official current outputs. Published upstream binaries were tested directly. Native/device/web results are not inferred from JVM execution. Allocation traffic is not retained heap; synthetic budget-clock readings are not wall time; microbenchmarks are not document-level competitive measurements.

## Validation actually executed

The ordinary JVM runs across 12 modules found **3444 tests: 3336 successful, 108 aborted, zero failed**. JUnit XML calls the assumption-aborted cases skipped; they are not counted as passes. Broad-run logs are `/workspace/scratch/audit-jvm-tests-proxy.log`, `compose-official-tests.log` and `audit-other-jvm-tests.log`. No tests or baselines were changed.

| Module | Found | Successful | Aborted | Failed/errors |
|---|---:|---:|---:|---:|
| kitepdf-cbz | 34 | 34 | 0 | 0 |
| kitepdf-compose-viewer | 471 | 466 | 5 | 0 |
| kitepdf-core | 459 | 457 | 2 | 0 |
| kitepdf-epub | 919 | 876 | 43 | 0 |
| kitepdf-javascript | 205 | 201 | 4 | 0 |
| kitepdf-media | 48 | 48 | 0 | 0 |
| kitepdf-native-renderer | 221 | 179 | 42 | 0 |
| kitepdf-net | 18 | 18 | 0 | 0 |
| kitepdf-pdf | 878 | 873 | 5 | 0 |
| kitepdf-skia-renderer | 31 | 25 | 6 | 0 |
| kitepdf-svg | 118 | 118 | 0 | 0 |
| kitepdf-xps | 42 | 41 | 1 | 0 |

The first broad renderer run could not auto-locate MuPDF and therefore did **KitePDF-only smoke**. A separate explicit-path run then passed AWT PDF, Skia PDF and EPUB differential tests, with no aborted tests in those three filtered tests:

- AWT PDF: 240 selected pages out of 282 available; 0 render failures, 0 blank pages, 0 oracle/comparison failures; normalized mean absolute error against MuPDF **0.0012** at 96 DPI.
- Skia PDF: 211 selected pages out of 253, 53 documents; 0 render failures; mean absolute error **0.0098** at 96 DPI. Its coverage differs from AWT, so these means are not a controlled backend ranking.
- EPUB: 55 books and 6,178 rendered pages; 0 render failures and 2 reported blank pages. Page-count assertions passed. Worst page-0 MuPDF MAE was 0.3891, explicitly informational because reflow differs. Blank counts and informational differences were not promoted into findings without a valid-content investigation.
- PDFium parity: passed, 1 test successful and none aborted, 362 selected pages out of 404 across 199 documents at 96 DPI. Raw classifications were PDFIUM_BETTER=3, AGREE=50, PDFIUM_DIFFERS=303, MUPDF_DIFFERS=3, NO_CONSENSUS=2 and NO_REFERENCE=1. The three raw better cases were handled by the existing specification/reader-defined exemptions, not newly accepted defects; KNOWN_GAPS is empty. The no-reference case is the UserUnit fixture where PDFium renders 400x400 while KitePDF/MuPDF render 600x600. No-consensus pages were vertical text and a checkbox. A passing verdict therefore does not mean 362 identical pages or complete parity. Log: `/workspace/scratch/audit-pdfium-tests.log`.

Exact independent-run commands (the helper only supplies this environment's inherited Java proxy and CA settings):

```sh
python3 /workspace/scratch/run-kotlin.py test -m kitepdf-native-renderer -m kitepdf-skia-renderer -p jvm '--include-classes=*DifferentialTest*' '--jvm-args=-Dkitepdf.mutool=/workspace/.kitepdf-tools/mupdf/build/release/mutool -Dkitepdf.pdfium.python=/workspace/.kitepdf-tools/pdfium-venv/bin/python'
python3 /workspace/scratch/run-kotlin.py test -m kitepdf-native-renderer -p jvm '--include-classes=*PdfiumParityTest*' '--jvm-args=-Dkitepdf.mutool=/workspace/.kitepdf-tools/mupdf/build/release/mutool -Dkitepdf.pdfium.python=/workspace/.kitepdf-tools/pdfium-venv/bin/python'
```

The targeted probes below fail supported behavior while the existing suites pass. In particular, PDF builder/extractor font-state mistakes mask each other; independent MuPDF output exposes them. The 19 PDF audit artifacts were also parsed object-by-object by MuPDF without warnings. Real Compose scenes use queued app-order effects; repeated rendering waits and vector controls rule out sampling an unfinished raster.

The reproducible harnesses and fixtures remain under `/workspace/scratch/` for your local inspection; they are not additional tracked deliverables. Every finding includes a concrete trigger, source anchors, expected/actual behavior and remediation. Relative source paths refer to the component root and audited revision. Evidence links/paths describe this workspace, not a claim that unpublished probes already exist in CI.

## Finding index

P1 means high-priority content loss/disclosure, ordinary application termination, or a save-completion contract failure. P2 means supported behavior is incorrect or carries material resource/performance/validation cost. P3 means lower-impact reporting/documentation inaccuracy. Priority is separate from proof strength and exploitability; no malformed-input exploit severity is asserted.

| Finding | Priority | Owner | Result |
|---|---|---|---|
| [PDF-01](#pdf-01) | P1 | KitePDF | True redaction retains the removed text in `/ActualText` |
| [PDF-08](#pdf-08) | P1 | KitePDF | Preparing a signature discards valid indirectly stored annotations and fields |
| [COMPOSE-01](#compose-01) | P1 | KitePDF | `commitFocusedField` returns while a suspending commit is still running |
| [DIST-01](#dist-01) | P1 | KitePDF | Normal failures from the Apple public API terminate Swift callers |
| [PDF-02](#pdf-02) | P2 | KitePDF | Redacting a shared Form XObject modifies other pages |
| [PDF-03](#pdf-03) | P2 | KitePDF | Page reordering and grafting discard inherited `/CropBox` |
| [PDF-04](#pdf-04) | P2 | KitePDF | Consecutive `setInfo` calls silently lose earlier metadata edits |
| [PDF-05](#pdf-05) | P2 | KitePDF | Standard-font creation writes Latin-1 bytes under a different PDF encoding |
| [PDF-06](#pdf-06) | P2 | KitePDF | Content builder does not restore its text encoder with `Q` |
| [PDF-07](#pdf-07) | P2 | KitePDF | `extractText()` ignores font restoration by `q`/`Q` |
| [PDF-09](#pdf-09) | P2 | KitePDF | Preparing a signature rolls back page edits staged in its editor |
| [PDF-10](#pdf-10) | P2 | KitePDF | Choice-field writer paints export values instead of display labels |
| [PDF-11](#pdf-11) | P2 | KitePDF | Filling one text field leaves its other widget appearances stale |
| [PDF-12](#pdf-12) | P2 | KitePDF | Rejecting removal of the last page corrupts subsequent editor operations |
| [COMPOSE-02](#compose-02) | P2 | KitePDF | `userScrollEnabled = false` still allows zoomed page and spread swipes |
| [COMPOSE-03](#compose-03) | P2 | KitePDF | capped raster tiles retain static widgets when the scripts/form-layer parameter changes |
| [COMPOSE-04](#compose-04) | P2 | KitePDF | outline `onNavigate` can cancel the navigation it is documented to observe |
| [FMT-01](#fmt-01) | P2 | KitePDF | SVG raster images always stretch, ignoring default and explicit preserveAspectRatio |
| [FMT-02](#fmt-02) | P2 | KitePDF | SVG symbol instances discard symbol viewport and inherited paint |
| [FMT-03](#fmt-03) | P2 | KitePDF | SVG element opacity is applied separately to fill and stroke |
| [FMT-04](#fmt-04) | P2 | KitePDF | SVG CSS color alpha is discarded |
| [FMT-05](#fmt-05) | P2 | KitePDF | EPUB outline page indices stay stale after script or :target repagination |
| [FMT-06](#fmt-06) | P2 | KitePDF | A text/link extraction racing EPUB repagination can poison the new layout's derived cache |
| [FMT-07](#fmt-07) | P2 | KitePDF | XPS Path.Stroke silently drops every non-solid brush |
| [FMT-08](#fmt-08) | P2 | KitePDF | The clipPath element's own transform is ignored |
| [FMT-09](#fmt-09) | P2 | KitePDF | SVG clip-rule is ignored and every clip uses nonzero winding |
| [MED-01](#med-01) | P2 | KitePDF | Granting remote-media access cannot recover an already rejected element |
| [MED-02](#med-02) | P2 | KitePDF | Changing a media session or place leaves the live player bound to the old values |
| [PLAT-01](#plat-01) | P2 | KitePDF | Host-font fallback discards legal shear and reflection on four backends |
| [PLAT-02](#plat-02) | P2 | KitePDF | Pure Kotlin raster canvas silently ignores isolation and knockout flags |
| [PLAT-03](#plat-03) | P2 | KitePDF | Cancellation in pure-raster callbacks permanently strands drawing in an offscreen layer |
| [PLAT-04](#plat-04) | P2 | KitePDF | Valid CP437 ZIP filenames can make the entire archive disappear |
| [PLAT-05](#plat-05) | P2 | KitePDF | Theme image identities collapse distinct functions with the same hash |
| [PLAT-06](#plat-06) | P2 | KitePDF | Closing the script runner from a host callback leaks its native engine and document graph |
| [PLAT-07](#plat-07) | P2 | KitePDF | Tiny raster steps allocate and scan the entire pure-Kotlin canvas twice |
| [SCRIPT-01](#script-01) | P2 | KitePDF | The document-wide script budget never accounts for the event currently running |
| [SCRIPT-02](#script-02) | P2 | KitePDF | Acrobat getPageBox ignores which page box the script requests |
| [IMG-01](#img-01) | P2 | KitePDF | PDF JPX images discard an explicitly supplied PDF ColorSpace |
| [IMG-02](#img-02) | P2 | KitePDF | Self-contained document images lose their declared color profiles |
| [IMG-03](#img-03) | P2 | KitePDF | Self-contained document images ignore EXIF orientation and page geometry uses the stored axes |
| [IK-01](#ik-01) | P2 | ImageKodec | decodeAnimation truncates deep samples before color conversion, so a static PNG changes color when called through the animation facade |
| [IK-02](#ik-02) | P2 | ImageKodec | An APNG thumbnail parses and copies every later frame before applying maxFrames |
| [KJS-01](#kjs-01) | P2 | KiteJS | Reclaiming a batch of Kotlin handles allocates quadratic garbage in the next QuickJS call |
| [KJS-02](#kjs-02) | P2 | KiteJS | Unreachable compiled scripts can exhaust the engine's native memory budget before the host GC reclaims their tiny wrappers |
| [DIST-02](#dist-02) | P2 | KitePDF | Web test npm resolution depends on unrelated cached artifacts and can miss newly downloaded dependencies |
| [DIST-03](#dist-03) | P2 | KitePDF | The publication consumer gate can fall back to the existing release instead of detecting missing local artifacts |
| [DIST-04](#dist-04) | P2 | KitePDF | The README's plain-JVM Skia setup omits its required native runtime |
| [DIST-05](#dist-05) | P3 | KitePDF | The benchmark report labels a minimum as a median |
| [DIST-06](#dist-06) | P3 | KitePDF | Platform documentation claims a JS network test suite which CI does not run |

## PDF reading, editing, writing, redaction and signing

All findings below belong to **KitePDF**, not KiteJS or ImageKodec. They use ordinary valid documents or valid public API sequences; none depends on malformed input. Source revision: `c4e7574b143689a8d115aba6dcd605cc4cc909f3`.

### Verification conditions

Current `kitepdf-core/src`, `kitepdf-core/src@jvm`, `kitepdf-pdf/src` and `kitepdf-pdf/src@jvm` were compiled directly with the cached Kotlin/JVM 2.4.20 compiler, Java 21 bytecode target, and the pinned published ImageKodec 0.3.0. The declared project compiler is 2.4.21, so this is direct-source functional verification, not an assertion that the official release gate passed. No old KitePDF jar was used. Build commands and compiler arguments are in `/workspace/scratch/pdf-head-build/compile.py` and `kitepdf-{core,pdf}.args`. Runtime reproductions are in `/workspace/scratch/pdf-head-build/PdfAuditRepro.kt`, executed with `python3 /workspace/scratch/pdf-head-build/run-repro.py`; their complete output is `repro.log`. All 19 produced PDF files were parsed object-by-object using MuPDF 1.27.2 `mutool show <file> grep`, each exiting 0 without warnings. Independent text checks are in `oracle.log`.

To rerun on this workspace:

```sh
python3 /workspace/scratch/pdf-head-build/compile.py
python3 /workspace/scratch/pdf-head-build/run-repro.py
/workspace/.kitepdf-tools/mupdf/build/release/mutool draw -F txt /workspace/scratch/pdf-head-build/results/widgets-after.pdf
```

<a id="pdf-01"></a>

### PDF-01 [P1] True redaction retains the removed text in `/ActualText`

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:307` preserves every `BDC` operation unchanged; `:531` replaces the corresponding visible `Tj` only. `text/TextExtractor.kt:109` then extracts `/ActualText` whether the visible show string survived or not.

**Trigger:** A normal accessibility sequence `/Span << /ActualText (SECRET-ACTUALTEXT) >> BDC BT /F1 24 Tf 72 700 Td (SECRET-VISIBLE) Tj ET EMC`, then `redactRegion(page, KiteRectangle(60.0, 680.0, 450.0, 735.0))` followed by **`saveRewritten()`**.

**Expected/actual:** The secret should be absent from the rewritten content. The visible string is removed, but the rewritten page content still contains `/ActualText (SECRET-ACTUALTEXT)` and KitePDF's own `extractText()` returns `SECRET-ACTUALTEXT`. This is recoverable confidential content despite the documented true-redaction guarantee. The output displays the black box; visual checks alone will miss it. MuPDF reports the replacement has no position when extracting, which does not remove the retained bytes.

**Fix direction:** Associate marked-content replacement properties with the content they describe and scrub/remove the affected `/ActualText`; handle both inline dictionaries and named `/Properties` without damaging unrelated uses. Add an extraction and decoded-stream check after a full rewrite, including nested and partially touched sequences. Also assess structure-tree replacement text separately; this repro establishes the inline `BDC` leak only.

**Artifact:** `results/actualtext-before.pdf`, `results/actualtext-after.pdf`; `repro.log` records retained text and the decoded stream.

<a id="pdf-02"></a>

### PDF-02 [P2] Redacting a shared Form XObject modifies other pages

**Evidence:** `writer/PdfEditorRedaction.kt:156` determines whether a form must remain pristine from only the current parent's invocation list; `:170` considers the original claimable; `:238` chooses the original form reference for the rewrite.

**Trigger:** Two pages each draw the same Form XObject once; its text is `SHARED-STAMP` at `(72,700)`. Redact the text region on **page 0 only** and call `saveRewritten()`.

**Expected/actual:** Page 1 should retain its stamp. Before editing its extracted text is `SHARED-STAMP`; after editing it is empty. MuPDF independently shows both pages blank except for the redaction box on page 0. There is no untouched invocation in page 0's local hit list, so the global form object is rewritten in place even though page 1 still uses it.

**Fix direction:** Use copy-on-write for page-local form redactions unless exclusive ownership across the document is established, and prune only the target page's unused resource entries. Extend shared-form tests across pages, resource aliases and different parent forms, not only repeated `Do` operations within one stream.

**Artifact:** `results/shared-form-before.pdf`, `results/shared-form-after.pdf`; `repro.log` and `oracle.log`.

<a id="pdf-03"></a>

### PDF-03 [P2] Page reordering and grafting discard inherited `/CropBox`

**Evidence:** `writer/PdfEditor.kt:664` materializes inherited `MediaBox`, `Resources` and `Rotate` while flattening the page tree, but omits the inheritable `CropBox`. `:714` does the same when dropping the source page's `Parent` during grafting. ISO 32000-1, 7.7.3.3 lists `/CropBox` as inheritable.

**Trigger:** A valid page inherits `/MediaBox [0 0 612 792]` and `/CropBox [50 100 300 500]` from a `/Pages` node. Reorder the pages using `setPageOrder`, or append one to another document with `appendPage`.

**Expected/actual:** The display should remain 250 x 400. After either operation it becomes 612 x 792. Previously cropped material can become visible, and page aspect ratio and framing change. Both rewrite outputs are accepted by MuPDF.

**Fix direction:** Materialize effective inherited `/CropBox` alongside the other page-tree attributes in both flatten and graft paths. Cover all page operations with inherited boxes, including structural edits after other staged changes.

**Artifact:** `results/crop-before.pdf`, `results/crop-reordered.pdf`, `results/crop-grafted.pdf`; output in `repro.log`.

<a id="pdf-04"></a>

### PDF-04 [P2] Consecutive `setInfo` calls silently lose earlier metadata edits

**Evidence:** `writer/PdfEditor.kt:155` always reads `base.trailer` and resolves through `base`, ignoring both `trailerOverrides["Info"]` and the staged version of an existing info object.

**Trigger:** One editor, then `setInfo(title = "Title A")`, `setInfo(author = "Author B")`, `saveIncremental()`.

**Expected/actual:** Both fields should survive because each call changes only its non-null arguments. After reopening, `title == null` and `author == "Author B"`. With an original `/Info`, earlier edits similarly revert to the base values. The existing writer test that performs two incremental saves reopens between calls and does not cover this same-editor sequence.

**Fix direction:** Resolve the effective staged-or-base `/Info` reference and dictionary before merging; reuse an info object created by an earlier call. Test both initially absent and existing `/Info`, and both save modes.

**Artifact:** `results/metadata.pdf`; output in `repro.log`.

<a id="pdf-05"></a>

### PDF-05 [P2] Standard-font creation writes Latin-1 bytes under a different PDF encoding

**Evidence:** `writer/PdfText.kt:41` encodes each character below U+0100 by its Unicode low byte. The standard-font dictionaries emitted by `writer/PdfBuilder.kt:187` and `writer/PdfEditor.kt:266` contain no matching `/Encoding`, so Helvetica uses its built-in StandardEncoding. Latin-1 and StandardEncoding differ. ISO 32000-1, 9.6.6 and Annex D define the encoding mapping.

**Status:** Confirmed existing implementation limitation, already explicitly acknowledged in `docs/writing.md:107-110` and `docs/editing.md:90-91`. It is included because it directly prevents the requested Latin-text fidelity; it is not presented as an undisclosed or newly discovered issue.

**Trigger:** `PdfBuilder().page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "café déjà vu") }.build()`.

**Expected/actual:** The intended Latin text should render and extract. KitePDF extracts `cafØ dØjà vu`; independent MuPDF extracts `cafØ dØj` followed by a replacement character and `vu`. The byte 0xE9 identifies `Oslash` under StandardEncoding, not `eacute`. This affects new PDFs and standard-font stamps; form appearances also reuse this encoding helper.

**Fix direction:** Choose an explicit encoding per simple-font family and encode Unicode through its actual table. WinAnsi requires CP1252 mapping, not just truncation, and Symbol/ZapfDingbats require their own mapping. Keep metrics and appearance generation aligned with that choice. Add non-ASCII oracle cases beyond metadata, whose UTF-16 path is separate and already works.

**Artifact:** `results/latin1.pdf`; `repro.log`, `oracle.log`.

<a id="pdf-06"></a>

### PDF-06 [P2] Content builder does not restore its text encoder with `Q`

**Evidence:** `writer/ContentStreamBuilder.kt:42` emits `q`/`Q` without stacking/restoring `currentEmbedded`; `:133` uses that stale value to choose single-byte versus Identity-H glyph-code encoding. The actual PDF font is graphics state and is restored by `Q` (ISO 32000-1, 8.4.2 and 9.3).

**Trigger:** Select Helvetica and write `one`; `save()`; write `two` using an embedded DejaVuSans font; `restore()`; start another text object and call `showText("three")` without changing the restored font.

**Expected/actual:** The last word should use restored Helvetica and its matching encoder. The builder emits `<0057004B005500480048>` under restored Helvetica, which MuPDF reads as replacement characters interleaved with `W K U H H`. This is a valid API sequence; selecting a font again after every restore should not be necessary.

**Fix direction:** Maintain a stack of text-encoding bindings with graphics save/restore, including switching between two embedded fonts and both embedded/standard directions. Decide and document how `raw` interacts with tracked builder state.

**Test blind spot:** KitePDF's cheap `extractText()` incorrectly reports `three` because it independently fails to restore its own font, so a self round-trip assertion masks this defect (PDF-07).

**Artifact:** `results/font-state.pdf`; the decoded content in `repro.log`, independent MuPDF text in `oracle.log`.

<a id="pdf-07"></a>

### PDF-07 [P2] `extractText()` ignores font restoration by `q`/`Q`

**Evidence:** `text/TextExtractor.kt:90` stores one current font; the operator switch at `:104` has no graphics save/restore cases. `Tf` overwrites the font permanently even when it occurs inside a saved state. It also starts a new walk for Form XObjects rather than sharing the invoking text state; that latter behavior was not separately reproduced here.

**Trigger:** A valid authored content stream first selects Helvetica, then `q` selects Symbol and shows `ABC`, then `Q` returns to Helvetica and shows `AFTER` without another `Tf`.

**Expected/actual:** MuPDF reads `BEFORE`, Greek `ΑΒΧ`, then `AFTER`. KitePDF reads `BEFORE`, `ΑΒΧ`, then `ΑΦΤΕΡ`, decoding the final Helvetica bytes through the stale Symbol encoding. Public linear extraction therefore disagrees with the rendered document and can corrupt downstream indexing, search/export consumers, or reader actions built on it.

**Fix direction:** Track/restores the relevant text graphics state on `q`/`Q` and carry the correct inherited font into a form walk, while preserving the intended inexpensive linear extraction policy. Test with distinct encodings so using the wrong font cannot accidentally give the same text.

**Artifact:** `results/extract-font-restored.pdf`; `repro.log`, `oracle.log`.

<a id="pdf-08"></a>

### PDF-08 [P1] Preparing a signature discards valid indirectly stored annotations and fields

**Evidence:** `writer/PdfSigner.kt:88` treats page `/Annots` as present only when already a `PdfArray`; `:99` does the same with `/Fields`. Neither resolves valid indirect arrays. `:96` additionally treats a directly stored `/AcroForm` dictionary as absent and replaces it. General indirect-object rules are ISO 32000-1, 7.3.10; form and annotation wiring is 12.5 and 12.7.

**Trigger:** A page's `/Annots` references an array object containing its existing text-field widget; an indirect `/AcroForm` has `/Fields` referencing another array object. Prepare a new signature and serialize using `saveForSigning()`.

**Expected/actual:** Existing field `Existing` and new field `NewSignature` should both remain. Before: `[Existing]`, one annotation. After: `[NewSignature]`, one annotation. The old field/widget has been disconnected by replacing both arrays with lists containing only the signature. All source and result objects parse cleanly in MuPDF.

**Fix direction:** Resolve existing containers through the effective editor resolver, preserve both direct and indirect dictionary forms, and append to their contents. Check all legal storage combinations, including field trees and existing signature fields. An inline `/AcroForm` loss is established by the same source branch but not separately executed in this harness.

**Artifact:** `results/sign-indirect-before.pdf`, `results/sign-indirect-after.pdf`; `repro.log`.

<a id="pdf-09"></a>

### PDF-09 [P2] Preparing a signature rolls back page edits staged in its editor

**Evidence:** `writer/PdfSigner.kt:87` copies `page.dictionary`, which is the original document dictionary, then `:90` stages it as a replacement. Catalog and form copies at `:95` and `:98` similarly read the base instead of the editor's effective state.

**Trigger:** Create one editor, call `rotatePage(doc.pages[0], 90)`, then pass that editor to `PdfSigner`, call `prepareSignature` and `saveForSigning()`.

**Expected/actual:** The signed target should contain the staged rotation and the signature. Reopening gives rotation 0. The signer silently overwrites the pending page replacement. The same base-copy design can discard stamped/repointed page content and prior form/catalog edits; the rotation case is the executed minimum repro.

**Fix direction:** Merge signature wiring into effective staged objects, just as successive page content edits already do. Test rotation, stamp, form fill and page-tree changes composed with signing in both operation orders, with explicit restrictions for unsupported combinations.

**Artifact:** `results/sign-edit-after.pdf`; `repro.log`.

<a id="pdf-10"></a>

### PDF-10 [P2] Choice-field writer paints export values instead of display labels

**Evidence:** `writer/PdfEditor.kt:427` builds the appearance from the raw `value` argument without resolving the selected `/Opt` entry's display label, although `optionIndex` separately matches its export value. ISO 32000-1, 12.7.4.4 distinguishes export and displayed text.

**Trigger:** A combo's `/Opt` is `[[(FR) (France)] [(US) (United States)]]`; call `setChoiceValue(field, "US")` and save.

**Expected/actual:** `/V` should be `US` and the visible text `United States`. The resulting `/AP` contains `(US) Tj`, and MuPDF independently displays/extracts `US`. This disagrees with KitePDF's newer `PdfChoiceOption(exportValue, label)` model and the reader's richer choice rendering.

**Fix direction:** Resolve selection through the canonical choice model, persist export values but paint labels, and share the appearance logic with the reader. Include duplicate exports, editable free text and list boxes in follow-up coverage. The stale `/I` path for free text is a separate source concern, not counted as a reproduced defect here.

**Artifact:** `results/choice-before.pdf`, `results/choice-after.pdf`; decoded appearance in `repro.log`, MuPDF output in `oracle.log`.

<a id="pdf-11"></a>

### PDF-11 [P2] Filling one text field leaves its other widget appearances stale

**Evidence:** `writer/PdfEditor.kt:319` picks only `field.widgetReference`; `:332` updates that one widget and then clears `/NeedAppearances`. `PdfFormField.widgets` correctly exposes every widget of the logical field, but this save path does not iterate them. ISO 32000-1, 12.7.3.1 permits one field to have multiple widgets.

**Trigger:** A text field has two widget kids, each with an existing appearance showing `OLD`. Call `setTextFieldValue(field, "NEW")`, then save.

**Expected/actual:** Both places showing the same field should show `NEW`. The field value becomes `NEW`, but only the first appearance is rebuilt. The second still contains `(OLD) Tj`. MuPDF independently displays/extracts `NEW` followed by `OLD`. A form preview can therefore show correct live state while its exported PDF contains conflicting values.

**Fix direction:** Regenerate every widget's appearance using its own rectangle and characteristics, preserve effective staged widget state, and ensure failure handling is atomic. Apply the same review to choices, which also target only the first widget. Cover repeated widgets on different pages and of different sizes, plus save/reopen validation in an independent reader.

**Artifact:** `results/widgets-before.pdf`, `results/widgets-after.pdf`; `repro.log`, `oracle.log`.

<a id="pdf-12"></a>

### PDF-12 [P2] Rejecting removal of the last page corrupts subsequent editor operations

**Evidence:** `writer/PdfEditor.kt:616` removes the page from the mutable tracked order before `:617` checks that the document is not empty. The exception leaves `pageOrderState` empty even though no page-tree replacement has been staged.

**Trigger:** Open an ordinary one-page PDF. Call `removePage(doc.pages[0])`, catch the documented rejection `IllegalArgumentException("Cannot remove the last page")`, then append a page from another valid document and save. This is normal API error recovery, not malformed-document input.

**Expected/actual:** The failed removal should leave the original page intact, so the subsequent append should produce `[Base, Appended]`. The rewritten result contains one page `[Appended]`: the rejected action silently changed the editor's future behavior, and the next successful action commits the unintended deletion.

**Fix direction:** Validate a prospective new order before committing it, or restore the tracked order on failure. Check failure atomicity of the other structural operations and test a failed operation followed by a successful one on the same editor.

**Artifact:** `results/failed-remove-recovery.pdf`; `repro.log` records the caught exception and the one-page output. MuPDF parses the result cleanly.

### Coverage and limits of this lane

Inspected the editor's staged graph, page-tree flatten/graft/rewrite paths, incremental serialization, builder resources and font encoding, signature preparation, redaction operation filtering and form recursion, form filling/appearance generation, linear text extraction and their nearby existing tests. The findings demonstrate gaps that normal self round-trips and local synthetic fixtures miss; they do not establish the absence of defects elsewhere.

Not completed in this lane: a complete PDF 1.7/2.0 conformance sweep; every glyph and colour algorithm; independent signed-CMS validation of all algorithms; every redaction source of replacement text; all streaming/interrupted save contracts; release performance measurements, allocation/retention profiling, or Apple/Android/JS/Wasm execution. No timing or cross-platform equality claim is inferred from this JVM probe. Existing malformed-input fuzzing and hardening were intentionally outside the user-requested scope.


## Compose viewer and public parameter interactions

Snapshot: KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. Scope excludes malformed-input hardening. No tracked implementation files changed. All four findings belong in KitePDF, not KiteJS or ImageKodec.

## Verification and reproduction

The official current-source command `./kotlin test -m kitepdf-compose-viewer -p jvm` completed: 471 tests started, 466 successful, 5 aborted, zero failed. Log: `/workspace/scratch/compose-official-tests.log`. The toolchain was invoked through `/workspace/scratch/run-kotlin.py` solely to supply this environment's Java proxy settings. Existing test success does not cover the combinations below.

A standalone Compose scene driver compiles `/workspace/scratch/compose-probe/SceneProbes.kt` against the official current main and test class outputs. It uses the repository's existing `drivenScene`/`EdtImageComposeScene` test harness, real `KiteDocView`, real `KiteDocViewState`, and current cached runtime dependencies. Reproduce with `python3 /workspace/scratch/compose-probe/run_scenes.py`. Output: `/workspace/scratch/compose-probe/scene-results.log`. The cached standalone compiler is Kotlin 2.4.20 with matching Compose compiler plugin, JVM target 21; production dependencies and implementation classes were compiled by the official Kotlin 2.4.21 toolchain. No source replacement or behavior stubs are used for the first three scenes. The outline scenes include both a deliberately suspending adapter to prove cancellation ownership and a second scene with the actual paged viewer and its own adapter.

<a id="compose-01"></a>

### COMPOSE-01 [P1] `commitFocusedField` returns while a suspending commit is still running

**Ownership:** KitePDF viewer. **Confidence:** reproduced with current actual viewer and state classes. **Affected public combinations:** `KiteDocView(scripts = handler)` with a handler that suspends, including the supported browser execution model, followed by a Save/Submit handler that awaits `state.commitFocusedField()` before reading/exporting form state.

**Primary source:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:907-912`. The related launch is `843-862`; other script posting uses `924-938`. JVM/Android/Apple `ScriptDispatcher.kt:7` uses `Dispatchers.IO.limitedParallelism(1)`; JS/Wasm uses Main. The public `PdfScriptHandler` contract promises one call at a time, in event order, and explicitly permits a web script to suspend (`kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfScriptHandler.kt:13-20`). The `commitFocusedField` KDoc promises all earlier script work has finished before returning (`KiteDocViewState.kt:899-905`).

**Cause:** `blurTextField` launches the commit in a separate coroutine. `commitFocusedField` submits an empty `withContext(lane)` block as a queue barrier. A dispatcher serializes executing segments, not entire suspending coroutine lifetimes. When the handler suspends, the empty block runs and returns. Earlier script callbacks can also overlap later callbacks across suspension, despite the interface's serialization guarantee. The same problem applies to Main on web.

**Valid trigger and actual result:** a valid one-page PDF contains text field `field` with value `old`. Attach a handler whose `commit("field", "edited")` awaits a `CompletableDeferred`, then stores the new value and returns. Focus the field, enter `edited`, await `state.commitFocusedField()`, and inspect storage before releasing the handler. The current real viewer prints:

```text
real state after commitFocusedField: entered=true, finished=false, stored=old
```

After releasing the deferred, the commit and blur finish. The previous exact-method extraction probe produces the same ordering; the full scene is stronger evidence. Expected: the await does not finish until the handler and preceding viewer script calls finish. Actual: a host can save or submit the previous field value, losing the last edit in its output.

**Remediation direction:** serialize full suspend operations through a single consumer/actor or explicitly chained jobs; let flush await the completion of the prior operation, not an empty dispatcher task. A mutex shared by the actual full operations can enforce exclusion, but queue ordering and cancellation/close handoff also need explicit treatment. Add a regression with a handler that genuinely suspends; existing blocking-handler tests cannot expose this.

<a id="compose-02"></a>

### COMPOSE-02 [P2] `userScrollEnabled = false` still allows zoomed page and spread swipes

**Ownership:** KitePDF viewer. **Confidence:** reproduced for both paged and spread layouts with the actual pointer input pipeline.

**Primary source:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1123-1147` always supplies a `PageTurn`, whereas `1168` checks `userScrollEnabled` only for the pager's own scrolling. Spread has the same mismatch at `2183-2207`, with the flag checked at `2191`. `KiteGestures.kt:312-330` accumulates a zoomed drag beyond the pan edge and calls `state.turn` without the flag. Public KDoc at `KiteDocView.kt:165-168` says disabling this lets the host drive paging exclusively through state/navigation buttons.

**Valid trigger:** six ordinary blank PDF pages; `KiteDocView(layout = KiteDocLayout.Paged(), userScrollEnabled = false, renderSpec = Vectorized())`; zoom to 3, pan to the trailing page edge, drag farther beyond it by more than the 64dp threshold, release. Repeat with `Spread()`. The scene sends a real touch press, twelve move events totaling 144px beyond the edge, and a release.

```text
disabledPaging layout=Paged userScrollEnabled=false: currentPage=1
disabledPaging layout=Spread userScrollEnabled=false: currentPage=2
```

Both start at page 0. Expected: the page remains fixed while panning within the page is still permitted. Actual: a one-page or two-page navigation occurs despite the explicit flag. This breaks hosts that lock navigation or provide their own navigation policy.

**Remediation direction:** supply no `PageTurn` while `userScrollEnabled` is false, or carry the flag into the gesture handler and rekey it when the flag changes. Cover Paged/Spread, zoomed/unzoomed, both orientations, reverse layout and RTL in regression combinations. The existing zoomed-pan scene checks the enabled path; existing gesture-flag scenes do not test this parameter.

<a id="compose-03"></a>

### COMPOSE-03 [P2] capped raster tiles retain static widgets when the scripts/form-layer parameter changes

**Ownership:** KitePDF viewer. **Confidence:** reproduced by pixels from actual Compose scenes; Vectorized control is correct.

**Primary source:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1564-1580`. `bitmaps` is remembered without `drawsFormLayer`. Changing the flag restarts the effect (`1565`), but already stored tile rectangles are skipped (`1570`), so their `skipWidgets` choice (`1573`) is never updated. The main full-page raster correctly includes `drawsFormLayer` and `rasterizer` in its `produceState` keys at `1404`.

**Valid trigger:** show a one-page 200x200 PDF with a valid red pushbutton at the center, using `SinglePage(0)` and `Rasterized(maxBitmapLongSide = 32)`. Start with `scripts = null`, wait for detailed tiles. Replace the parameter with a handler whose live form state has `setHidden("button", true)`. Wait until the fresh whole-page raster completes and another 80 scene frames pass. The base raster now excludes static widgets and the live layer correctly hides the button, but the old detailed tile paints it back. The exact same handler/page switched to Vectorized yields white:

```text
staleTiles after hidden live widget and second whole-page raster: center=Color(1.0, 0.0, 0.0, 1.0, sRGB IEC61966-2.1)
staleTiles same handler/page in Vectorized control: center=Color(1.0, 1.0, 1.0, 1.0, sRGB IEC61966-2.1)
```

Expected: enabling the handler immediately produces the same hidden widget behavior across capped raster and vector rendering. Actual: a stale static field remains visible until tile identity changes or the composition is disposed. Toggling the parameter the other direction can retain tiles that omit the static field.

There is a related missing key: the tile state/effect also omit `rasterizer`, whereas the whole-page path includes it. This can retain tiles made with the prior font environment/text measurer; that secondary trigger was identified statically, not pixel-reproduced, and should not be presented as a separately verified finding.

**Remediation direction:** include all pixel-affecting inputs in tile identity, ideally deriving the identity from the same rendering/cache key used by the whole-page raster. At minimum clear remembered tile output on `drawsFormLayer` and rasterizer changes. Add scripts null-to-handler and handler-to-null tests with an active capped raster/tile path, not only uncapped form-layer scenes.

<a id="compose-04"></a>

### COMPOSE-04 [P2] outline `onNavigate` can cancel the navigation it is documented to observe

**Ownership:** KitePDF widgets. **Confidence:** reproduced with the current real `KiteOutlinePanel`, `KiteDocView`, state and paged adapter.

**Primary source:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteWidgets.kt:324-330`. `rememberCoroutineScope()` at `289` belongs to the outline panel. Each click launches navigation in that scope, then calls `onNavigate` synchronously outside the coroutine. The public KDoc at `273-274` specifically recommends closing a drawer in this callback and says the scroll already happened.

**Valid trigger:** put `KiteOutlinePanel` in a drawer or conditional composition and set `onNavigate = { showOutline = false }`, as documented. Click an entry that requires animated paging or EPUB chapter layout. The callback removes the panel, cancelling its remembered scope before navigation completes. The deterministic scene uses a suspending adapter and the real outline panel and produces:

```text
outline onNavigate closes panel: callback=true, started=true, navigationCancelled=true, currentPage=0
outline actual Paged viewer: callbackPage=0, settledPage=4, requestedPage=5
```

The second scene uses six ordinary blank PDF pages, `KiteDocView(layout = Paged(), renderSpec = Vectorized())` behind the panel, and an outline entry targeting page index 5. After clicking, the callback fires while page 0 is current; after another 100 driven scene frames the real pager settles on page index 4 rather than the requested 5. No adapter stub is used in that control.

Expected: the callback observes completed navigation to the outline entry; disposing the panel then cannot cancel it. Actual: it runs before completion and disposing the panel cancels the in-flight operation. This is a normal integration, not malformed input or an application misuse.

**Remediation direction:** await successful navigation before invoking the callback inside the launched coroutine, consistent with the existing contract. If early callback semantics are intended instead, navigation needs an appropriate owner that survives panel disposal and the public contract needs revision. Add a drawer-disposal regression for both the page-index and bookmark/EPUB-target branches.

## Coverage and limits

Inspected Compose layout/raster/form/text/selection/navigation/render state and gesture interactions, cache keys, scripts dispatch, scroll adapters, public parameter documentation and existing scene tests. Findings above cover verified interactions between scripts and capped raster, script suspension and save, scrolling flags and zoomed gestures, and callback ownership and navigation. Existing official current-source tests pass; these are missing-combination failures, not a failing baseline.

This lane did not run Android, Apple, browser JS or browser Wasm Compose runtime scenes, release microbenchmarks, Swift/ObjC or JS consumer compilation, ABI comparisons, or a combinatorial exhaustive proof of every public argument. Cross-platform statements above derive from common code and dispatcher semantics; JVM is the runtime evidence. Do not report nanosecond or cross-platform performance superiority from these tests. Malformed-input hardening was excluded by instruction.


## EPUB, SVG, XPS and CBZ

Audit target: KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. No implementation edits. All findings below use well-formed, ordinary document features, not malformed-input hardening. Owners are KitePDF unless specified.

## Execution provenance

I compiled **every current common production `.kt` file** in `kitepdf-svg/src`, `kitepdf-xps/src`, and `kitepdf-epub/src` with cached Kotlin 2.4.20; EPUB and XPS target JVM 21. Current SVG was used by current EPUB. Initial compilation used the compatible cached 0.12.0 core API, then every probe below was rerun against a core built from current HEAD (`/workspace/scratch/pdf-head-build/kitepdf-core-head.jar`) and the exact pinned ImageKodec 0.3.0 artifact (`/workspace/scratch/upstream/imagekodec-jvm-0.3.0.jar`), with identical outputs and no linkage errors. No production-source stubs or modifications. These are standalone JVM source probes, not a claim that the official complete multiplatform build passed. No speed claims or nanosecond measurements.

Reproduction scripts and complete logs: `/workspace/scratch/formats-repro/` (`compile_svg.py`, `run_svg.py`, `SvgAudit.kt`, `compile_epub.py`, `compile_xps.py`, `make_fixtures.py`, `run_formats.py`, `FormatsAudit.kt`, `run_derived.py`, `DerivedRace.kt`). SVG output: `svg-output.txt`; EPUB/XPS output: `formats-output.txt`; controlled cache-race output: `derived-output.txt`.

<a id="fmt-01"></a>

### FMT-01 [P2] SVG raster images always stretch, ignoring default and explicit preserveAspectRatio

- **Location:** `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:400-410`, especially placement at 409.
- **Trigger:** `<svg width="100" height="100"><image href="pic.bmp" width="100" height="100"/></svg>` with a valid 2-by-1 raster. Repeat with `preserveAspectRatio="none"` and `preserveAspectRatio="xMidYMid slice"`.
- **Expected:** SVG 1.1 section 7.8 and 16.2: default `xMidYMid meet` retains the raster ratio and centers a 100-by-50 picture at y=25. `none` stretches to 100-by-100. `slice` scales to 200-by-100 and clips the horizontal overflow.
- **Actual/executed:** All three emit the identical `KiteMatrix(a=100,b=0,c=0,d=-100,e=0,f=100)`. Slice emits zero clips. The element attribute is never read. This distorts diagrams and cover art in SVG and EPUB SVG content independently of backend choice. Existing `SvgFeaturesTest.image_percentage_coordinates_and_dimensions_use_their_axis` actually asserts nonuniform stretching without opting into `none`.
- **Remedy:** Map the intrinsic raster extent through the element's preserveAspectRatio fit, including viewport clipping for slice; share the existing viewBoxFit logic where appropriate. Keep the no-aspect `none` case explicit. Regression cases must include a nonsquare raster inside a square image viewport and all alignment modes.

<a id="fmt-02"></a>

### FMT-02 [P2] SVG symbol instances discard symbol viewport and inherited paint

- **Location:** `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:374-377`.
- **Trigger:** `<svg width="200" height="200"><defs><symbol id="s" viewBox="0 0 10 10" fill="red"><rect width="10" height="10"/></symbol></defs><use href="#s" width="100" height="100"/></svg>`.
- **Expected:** SVG 1.1 sections 5.5/5.6: the instance's 100-by-100 viewport maps the symbol's 10-by-10 viewBox, and its rectangle inherits the symbol's red fill.
- **Actual/executed:** The only fill bounds are `(0,0)-(10,10)` and its color is black. drawUse walks the symbol's children directly, bypassing the symbol node, its paint resolution, viewBox, preserveAspectRatio, transforms and clipping. The same branch ignores zero-sized use instances. Existing symbol test only checks paint count for a symbol without viewBox or paint declarations.
- **Remedy:** Instantiate a symbol as its own viewport-bearing container with use width/height and symbol style/transform semantics, rather than skipping directly to children. Test viewBox scaling, overridden/inherited paint, zero dimensions, and percentage dimensions together.

<a id="fmt-03"></a>

### FMT-03 [P2] SVG element opacity is applied separately to fill and stroke

- **Location:** `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:228-244`, `1566-1568`; fill/stroke emission at 744-780.
- **Trigger:** `<rect x="10" y="10" width="80" height="80" fill="red" stroke="blue" stroke-width="20" opacity="0.5"/>`.
- **Expected:** SVG 1.1 section 14.5 applies opacity to the complete element after fill and stroke have been composited. On a transparent surface, both its center and fill/stroke overlap have final alpha 0.5; on white, the overlapping blue stroke is `(0.5,0.5,1)`.
- **Actual/executed:** RecordingCanvas receives zero transparency groups, one fill with alpha 0.5, then one stroke with alpha 0.5. The overlap accumulates to alpha 0.75 (on white, `(0.5,0.25,0.75)`), creating a seam that is absent from the specified result. Code groups only container opacity and explicitly treats a shape's opacity as per-paint. Filled/stroked text has the same compositing problem.
- **Remedy:** Apply element opacity once around the element's full rendering, including fill/stroke and multiple text runs; retain fill-opacity/stroke-opacity as per-paint controls. Avoid reapplying shape opacity during filter handling.

<a id="fmt-04"></a>

### FMT-04 [P2] SVG CSS color alpha is discarded

- **Location:** `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1563-1564`, `1577-1578`, `1628-1633`.
- **Trigger:** `<svg width="10" height="10"><rect width="10" height="10" fill="rgba(255,0,0,0.5)"/></svg>`, likewise `stroke`, `transparent`, alpha hex and CSS declarations.
- **Expected:** CSS Color alpha multiplies the corresponding fill/stroke opacity; the example paints with alpha 0.5.
- **Actual/executed:** Fill is red with alpha **1.0**. `paintValue` retains `CssValues.color`'s RGB but never reads `CssValues.alpha`; independent alpha fields retain only fill-opacity/stroke-opacity. Filter colors already read both color and alpha at 1141-1147, demonstrating that this shared parser supports the alpha syntax. The valid SVG2/CSS color is accepted as a different, opaque color rather than rejected as unsupported syntax.
- **Remedy:** Carry intrinsic color alpha together with each paint through inheritance/currentColor/fallback resolution, then multiply by fill-opacity or stroke-opacity and element opacity as appropriate. Check `transparent` explicitly and keep color-alpha inheritance separate from opacity inheritance.

<a id="fmt-05"></a>

### FMT-05 [P2] EPUB outline page indices stay stale after script or :target repagination

- **Location:** `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:104-116`, cache declaration 119-121.
- **Trigger:** Open a two-chapter reflowable EPUB with nav links to both chapters, prepare all pages and read `outline`. Chapter 1 has `<p id="grow">One paragraph.</p><p>After target.</p>` plus `#grow:target { height: 2400px; }`. Call public `setFragment(0,"grow")` to apply supported :target styling. Script edits which change pagination give the same result.
- **Expected:** Once the first chapter gains pages, the second chapter's outline pageIndex tracks its new global index.
- **Actual/executed:** At pageWidth=300, pageHeight=200, margin=20, initial pageCount=2, chapter0 pages=1, second outline index=1. After setFragment: pageCount=3, chapter0 pages=2, actual `pageIndexOf(KiteLocation(1,0))=2`, but `outline[1].pageIndex=1` and `outline` returns the same cached list. isComplete remains true across the supported edit, so the Boolean-only key never invalidates. Target bookmarks still navigate correctly; consumers displaying page numbers or navigating by the public pageIndex see incorrect data. The anchorPages cache later in the file correctly keys on treeRevision, but outlineCache does not.
- **Remedy:** Key the resolved outline by the layout/tree revision, or invalidate it whenever a chapter summary changes, preserving memoization within a revision. Also test another EpubDocument created with withSettings sharing a mutable parsed book.

<a id="fmt-06"></a>

### FMT-06 [P2] A text/link extraction racing EPUB repagination can poison the new layout's derived cache

- **Location:** `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1270-1277`.
- **Trigger/interleaving:** T1 misses a structuredTextOf/linksOf cache, captures a PageRender in `build(render(...))`, and starts deriving its value. T2 applies a script tree edit or :target change, publishing a new LiveChapter at the same chapter index. T1 finishes and stores the old derived value into the current LiveChapter without checking identity/version. A long page and background extraction increase the real overlap window.
- **Expected:** A result derived from the old page must never populate the new layout's cache; discard or return it unshared if the originating LiveChapter/version changed.
- **Actual/source proof:** `entry = live[chapter]` at 1273 is obtained after the unlocked build and is not compared to the captured entry or PageRender. Index-in-range only prevents an array fault. An empty derived cell in the new chapter accepts the old value, so subsequent public textContent calls return stale text although renderTo paints the new chapter. It lasts until another layout replacement or eviction.
- **Execution:** A deterministic JVM internal-seam probe compiles with the current EPUB jar as a friend module, pauses the callback after render capture using CountDownLatch, replaces the chapter on the other thread through replaceChapterTree, then resumes the old callback. This executes the real derived cache method and public textContent/renderTo; it is a controlled interleaving, not an uninstrumented application stress result. Observed `race public extracted=One paragraph.||After target.` while `race actually rendered=NEWCHAPTERTEXT`. See derived-output.txt. The callback returns an accurately captured old structured-text snapshot, making the store interleaving deterministic without production source changes.
- **Remedy:** Capture the source LiveChapter identity and version along with its PageRender under the lock, build outside the lock, then store only if both still match. Apply the same rule to text and links, and avoid returning a cached entry whose laidVersions is stale relative to parsed.treeVersion.

<a id="fmt-07"></a>

### FMT-07 [P2] XPS Path.Stroke silently drops every non-solid brush

- **Location:** `kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsRenderer.kt:189` (the `brushes.solid(...) ?: return` gate), brush implementation in XpsBrushes.kt:63-73.
- **Trigger:** A valid fixed page with `<Path Data="M 10,50 L 90,50" StrokeThickness="10"><Path.Stroke><LinearGradientBrush StartPoint="0,0" EndPoint="100,0"><LinearGradientBrush.GradientStops><GradientStop Color="#FF0000" Offset="0"/><GradientStop Color="#0000FF" Offset="1"/></LinearGradientBrush.GradientStops></LinearGradientBrush></Path.Stroke></Path>`. ImageBrush/VisualBrush strokes hit the same gate.
- **Expected:** ECMA-388 Path.Stroke accepts a brush and paints the stroked geometry with that brush. A line with a gradient stroke remains visible.
- **Actual/executed:** The solid-color control produces one Stroke. The otherwise identical gradient case emits BeginPage, page PushClip, PopClip and EndPage, with **no paint at all**. Independent MuPDF renders the packaged fixture at 75-by-75 pixels with 549 nonwhite pixels (`mutool draw -F png`), confirming the line is expected to be visible. The brush already works for Path.Fill and Glyphs.Fill, but stroke has no non-solid path. This is an end-to-end wiring gap in KitePDF, not an ImageKodec defect. docs/xps.md advertises solid, gradient, image and visual brushes; it gives no stroke-specific limitation, though it does explicitly disclaim full XPS conformance.
- **Remedy:** Turn the complete stroke geometry into an outline and fill it with XpsBrushes.fill, respecting dash/cap/join/miter settings and transforms. Report unsupported brush paths explicitly until implemented. Verify an independent reader renders the fixture and test stroke-only paths so a solid fill cannot hide missing strokes.

## Capabilities and review coverage

| Format/path | What source actually wires end to end | Boundaries and opportunities, separate from defects |
|---|---|---|
| EPUB | ZIP/OPF/spine/nav, per-chapter incremental layout and caching, fixed/reflowable/roll, CSS/embedded-font shaping, SVG/raster content, text/search/bookmarks/links, script-updated page trees, remote resources, media-overlay data. Current common module compiles independently and targeted valid navigation repro runs. | Deep coverage across all CSS, shaping scripts, pagination combinations and W3C normative statements was not completed. No EPUB writer/editor public contract is advertised; README honestly labels these Not yet. Dynamic-state cache correctness is defective as above. |
| SVG | Standalone one-page document and embedded SvgImage, geometry, styles subset, text, links, gradients/patterns/masks/filter raster steps, no local writer. Current common module compiled; recording repros run. | Source explicitly documents no animation, external sheets, combinator/attribute/pseudo selectors or CSS at-rules. These are capability gaps, not newly mislabeled bugs. Standard-font metrics/host fonts cannot establish cross-platform typographic parity. Symbol/image/compositing/color-alpha defects above affect supported basic rendering. |
| XPS/OpenXPS | OPC sequence/documents/pages, split pieces, fonts, paths, resource dictionaries, fills using solid/gradient/image/visual brushes, text and links; read-only. Current common module compiled and stroke-control repro run. | Documentation disclaims full conformance and identifies sideways/synthetic text as approximations. ColorConvertedBitmap currently discards its profile operand (XpsBrushes.kt:80); ContextColor profiles are not handled by xpsColor. These need separate oracle-backed color review before treating them as verified findings here. Stroke paint wiring is defective as above. |
| CBZ | ZIP image entries in natural order, metadata/bookmarks, lazy header sizing, bounded decoded-scan cache; read-only. Sources inspected, no new CBZ runtime repro by this lane. | CBZ_IMAGE_EXTENSIONS admits jpg/jpeg/png/gif/bmp/webp only, despite ImageKodec supporting TIFF/JP2/AVIF/JXL elsewhere. Broadening the filter is an opportunity after deciding package conventions, not an assumption that arbitrary images are a supported CBZ contract today. Header sizing and actual rendering must be reviewed together for EXIF orientation. ZIP filename encodings are being verified by the platform lane. |

No claims that all platform memory models, complete parameter products, external reader parity, long-duration memory behavior, or release performance have passed. This lane executed JVM current-module probes only. Apple/JS/Wasm/Android/Native runtime parity and graphics pixel oracles remain unexecuted here. The same common-source defects should reach every backend, but that is a source-level consequence, not a cross-platform execution claim.

<a id="fmt-08"></a>

### FMT-08 [P2] The clipPath element's own transform is ignored

- **Location:** `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:603-616`.
- **Trigger:** `<svg width="100" height="100"><defs><clipPath id="c" transform="translate(50,0)"><rect width="10" height="10"/></clipPath></defs><rect width="100" height="100" clip-path="url(#c)"/></svg>`.
- **Expected:** SVG 1.1 section 14.3.5 permits a clipPath transform and applies it to the clipping coordinate system. The clipping rectangle occupies x=50..60, y=0..10.
- **Actual/executed:** Current SvgImage emits clip bounds x=0..10, y=0..10. clipPathOf applies optional objectBoundingBox mapping and each child's transform, but never the referenced clipPath element's transform, so correct content is erased and content in the old location remains visible. Using an explicit transform on the clip child works, which isolates the missing step.
- **Remedy:** Compose the clipPath's author transform into the clip coordinate system in the order prescribed with clipPathUnits. Test simultaneous clipPathUnits=objectBoundingBox, clipPath transform, child transform and element transform, not just each in isolation. Probe output is included in svg-output.txt.

<a id="fmt-09"></a>

### FMT-09 [P2] SVG clip-rule is ignored and every clip uses nonzero winding

- **Location:** `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:240`, `597-628`.
- **Trigger:** A clipPath contains one `<path clip-rule="evenodd" d="M0 0 H100 V100 H0 Z M25 25 H75 V75 H25 Z"/>`, then a full-size rectangle uses the clip.
- **Expected:** SVG 1.1 sections 14.3.5/14.3.6: the inner same-winding rectangle is a hole under evenodd, so the center is clipped away. clip-rule is the relevant property in clipping, independently of fill-rule.
- **Actual/executed:** Current SvgImage emits PushClip with evenOdd=false. clip-rule is never read in the SVG module. The hole fills in under nonzero winding, so the center paints even though the valid author clip excludes it.
- **Remedy:** Preserve each clipping child's computed clip-rule while constructing the clip region. A clipPath combines multiple children as a union; simply changing a single global flag cannot represent differing per-child rules or all overlapping-child cases. Include a single path with a hole and multiple child shapes with mixed rules. Probe output included in svg-output.txt.


# Independent media-parameter review

Both findings are **executed in real current Compose scenes**, with explicit Compose state/effect dependencies traced. The scene probe uses the official current media/Compose main and test compiled outputs, the real KitePlayer 0.3.0 with the existing SilentPlayers FFmpeg backend, and SceneTestDriver with queued post-frame effects for app ordering. No production stubs or production source changes. Probe: `/workspace/scratch/formats-repro/MediaScenes.kt`; runner: `run_media_scenes.py`; output: `media-scenes-output.txt`. The official current media JVM suite separately passed all 48 tests, with zero skipped. Test log: `media-suite.log`. Existing MediaOverlaySceneTest/MediaSessionSceneTest exercise initial autoplay, source failure, page disposal/restoration and initial exclusivity, but never change allowRemote/session/place for a composed player. Owner: KitePDF `kitepdf-media`, not KitePlayer.

<a id="med-01"></a>

### MED-01 [P2] Granting remote-media access cannot recover an already rejected element

**Location:** `kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaOverlay.kt:195-200`, `218-228`, `236-241`, `270-271`. Source mapping in `MediaSources.kt:26-31`.

**Valid trigger:** Keep a page with `<video src="https://example.test/movie.mp4" autoplay="autoplay"/>` composed, initially pass `allowRemote=false`, then grant the user's remote-media preference and recompose with `allowRemote=true` for the same media. A non-autoplay element gives the same result if the reader taps play before access is enabled. No network failure is needed: with false, mediaItems yields no items, so openFirstPlayable returns null immediately after a non-null player is created.

**Actual:** The initial attempt sets unplayable=true and player=null. unplayable is remembered with media alone. Updating allowRemote does not reset it; LaunchedEffect(media) does not rerun, and start refuses all attempts while unplayable. The play button is omitted while unplayable, so the reader cannot retry a source the new policy now permits until the element leaves composition or its media value changes. A changed newPlayer factory also cannot recover a previous null factory for the same reason.

**Why it reaches consumers:** KiteMediaOverlay keys its per-element subtree with epubPage/index, not allowRemote. The public API accepts allowRemote as an ordinary composable parameter, and forwarding a changed setting does not remount the element.

**Executed result:** After initial failed autoplay, toggling false to true creates no replacement player and exposes no play button: `allowRemote false->true: createdPlayers=1, playButtons=0`. The HTTPS source was never fetched during the denied attempt.

**Expected/remedy:** Changes to the playable input set or playback capability should reset terminal failure and reschedule eligible autoplay or present a retry button. Scope remembered player/failure state and open jobs to the intended input identity, covering media, document, permission and factory policy as appropriate. Do not accidentally autoplay a formerly rejected element after explicit user pause. Add a valid HTTPS autoplay/no-autoplay permission-toggle scene with a controlled player backend.

<a id="med-02"></a>

### MED-02 [P2] Changing a media session or place leaves the live player bound to the old values

**Location:** `kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaOverlay.kt:244-258`.

**Valid trigger:** EpubMediaPlayer plays an element under session A/place A. Recompose the same media/player with session B/place B, as an app can when changing a reader tab or the per-reader exclusivity group. Start another element registered with session B.

**Actual:** DisposableEffect is keyed only on open, so it continues to retain the original session in register/unregister and its original place in onDispose. LaunchedEffect(open)'s ongoing state collection also retains the original session.started callback. The first player stays registered with A and is absent from B, so starting the second B player does not pause it. Later activity can affect unrelated A players, and disposal saves playback position under A's original place. Changing place alone also saves to the old place. BackgroundHandling receives the changed policy separately, which does not migrate the exclusivity registration or saved place.

**Executed result:** After first element starts under A, recomposing it under B and starting a second B element leaves both real players active: `first=Playing, second=Playing`. Disposing the scene gives `session A savedPlaces=1, B savedPlaces=1`, although both latest composable parameters specify B, proving stale disposal registration/place capture as well.

**Expected/remedy:** Session membership must follow the current session parameter, and saved playback identity must follow a deliberate documented policy for place changes. Separate player ownership/close from session registration: blindly adding session/place to the existing DisposableEffect key would close a still-remembered player on a session change and create a different defect. Migrate register/unregister in a session-keyed effect, update the active-state observer, and keep resource closing tied to player lifetime. Test a live A-to-B session change with two playable elements and a place-only change followed by disposal.


## Core, platform rendering, lifecycle and performance

Audited snapshot: KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`.

All findings below concern valid documents or supported API calls. No malformed-input hardening is included. Owners are KitePDF, not KiteJS or ImageKodec.

## Verification method and limits

Current source files were compiled directly with the cached Kotlin 2.4.20 JVM compiler into `/workspace/scratch/platform-probe/`. All five probes were finally rerun against the full current `kitepdf-core-head.jar` built by the PDF audit lane and the pinned published ImageKodec 0.3.0 support dependency; no old KitePDF jar was on that final runtime classpath. Current AWT source was also recompiled against current core: `AwtCanvas.kt`, `PdfBlendComposite.kt`, `RadialShadingPaint.kt`, `DashIntervals.kt`, and `RgbaPixels.kt`. The final probe outputs are in `head-runtime-results.log`. This is targeted current-source execution, not a successful official whole-project build or a test of current published KitePDF artifacts. The separate raster-step microbenchmark below measures warmed JVM production bytecode only. No end-to-end ranking, nanosecond claim, mobile memory profile, or Apple runtime claim is inferred from these probes.

<a id="plat-01"></a>

### PLAT-01 [P2] Host-font fallback discards legal shear and reflection on four backends

- Owner: KitePDF native and Skia renderers.
- Primary location: `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:316` (reconstructs transform at 322-325).
- Same implementation: `kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:210`; `kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt:202`; `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:278`.
- Trigger: valid PDF non-embedded font, such as Helvetica, under a sheared text matrix, or text with a reflection relative to the normal page coordinate flip. A custom `KiteCanvas.drawGlyphs` call with the same supported arguments is sufficient. Embedded outlines take a different, correct full-matrix path.
- Defect: these methods retain translation, angle of the first column and positive magnitudes of both columns only. Two columns need not be orthogonal and the determinant may have either sign. Reconstructing rotation times positive diagonal scales cannot preserve shear or reflection. This changes glyph shapes/placement and breaks parity between Vectorized Compose rendering and the native/Skia raster renderers. CoreGraphics and Compose preserve the full affine matrix, so they should not be swept into the defect.
- Runtime evidence: draw one unembedded `H` at 60 pt through current `AwtCanvas` on a transparent 220x220 image. Compare matrices `(1,0,0.75,-1,80,100)` and `(1,0,0,-1.25,80,100)`. Actual host-font images differ at **0 pixels** because the positive column magnitudes are identical. Drawing the same canvas's `hostGlyphOutline("H", FontSpec.SansSerif)` at 0.06 scale under each full matrix differs at **1,271 pixels**. Change the second matrix's `d` from `-1.25` to `+1.25`: host-font output again differs at **0 pixels**, while the full-matrix outline output differs at **1,656 pixels**. No reference-font mismatch can explain identical outputs for distinct transforms of the same face.
- Repro source: `/workspace/scratch/platform-probe/HostTransformProbe.kt`; source outputs `host-shear-actual.png`, `host-shear-outline.png` beside it.
- Fix direction: use the full matrix with a deliberate y-up/y-down font-coordinate conversion, as Compose does; choose a measurement font size independently and remove that scalar from the full matrix. Exercise shear, reflected x/y, rotation plus shear, and non-uniform scaling for host fonts on every backend.

<a id="plat-02"></a>

### PLAT-02 [P2] Pure Kotlin raster canvas silently ignores isolation and knockout flags

- Owner: KitePDF core renderer.
- Location: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:188`.
- Trigger: render a valid PDF transparency group through `KiteRasterCanvas`, or call its public `beginTransparencyGroup` API with `isolated=false` or `knockout=true`.
- Defect: the method unconditionally opens an empty transparent layer and saves only alpha/blend mode. Both boolean parameters are discarded. Thus every group is isolated and no paint knocks out earlier paints. This violates the `KiteCanvas` contract (ISO 32000-1 sections 11.4.5 and 11.4.6) and the class's claim to draw transparency groups.
- Runtime evidence, full-coverage interior pixel with current sources:
  - Fill the backdrop blue. Begin non-isolated, non-knockout group at alpha 1, Normal. Paint opaque red with Multiply, then end the group. `KiteRasterCanvas` returns `FFFF0000` (red), current `AwtCanvas` returns `FF000000` (black). Multiplication must see the blue group backdrop. The PDF renderer's special `compositeExactly` route does not cover this simple case: `PageRenderer.kt:1161` requires knockout, group alpha below 1, or a group blend other than Normal before it attempts that route.
  - Fill white. Begin an isolated knockout group. Paint opaque red followed by half-opacity blue over the same rectangle. Pure raster returns `FF800080`, current AWT returns `FF7F7FFF`. The latter blue paint must replace the earlier object's contribution inside its shape, leaving half-transparent blue against the white page. The PDF renderer's exact route is additionally conditioned on a non-isolated group and cannot repair this isolated knockout case.
- Repro source: `/workspace/scratch/platform-probe/RasterProbe.kt`.
- Existing `KiteRasterCanvasTest` covers isolated, non-knockout group alpha only; it does not exercise these flags.
- Fix direction: preserve group isolation/knockout state, initialize/use the appropriate backdrop, and apply knockout at elementary-object boundaries rather than ordinary source-over. Compare direct canvas and PDF-driven cases against independent output and existing native backends.

<a id="plat-03"></a>

### PLAT-03 [P2] Cancellation in pure-raster callbacks permanently strands drawing in an offscreen layer

- Owner: KitePDF core renderer.
- Locations: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:209`, `:214`, and `:257`; `beginPage` and `endPage` at lines 75-76 are no-ops.
- Trigger: a valid render is cancelled while a soft-mask `render` / `renderMask` callback or raster-step `scope.render { ... }` callback runs. An application may also throw a normal exception from these public callbacks. Catch the exception and reuse the same canvas for the next page/render.
- Defect: each callback is invoked after `layers.add(...)` and before `popLayer()`, without `try/finally`. On failure, the layer and clips remain. Subsequent drawing targets the abandoned layer while `toRaster()` always reads `layers.first()`. Page finalization and the next `beginPage` do not repair it. Besides silent missing output, repeated failures retain a full canvas-sized pixel layer each time.
- Runtime evidence: with a fresh 8x8 `KiteRasterCanvas`, call `applySoftMask(Alpha, box, IDENTITY, render={throw CancellationException("cancel")}, renderMask={})`, catch cancellation, call `endPage()`, `beginPage(8,8,IDENTITY)`, then fill the whole canvas red. `toRaster()[4,4]` is **0** (transparent), expected `FFFF0000`. Repeating with `rasterStep { scope -> scope.render { throw CancellationException("cancel") }; true }` gives the same transparent result.
- Repro source: `/workspace/scratch/platform-probe/RasterProbe.kt`.
- Fix direction: restore layer and clip depths in `finally` for both callback boundaries and guarantee begin/end-page cleanup. Check cancellation/failure from content, mask, nested filter steps, and a successful render immediately after failure. Do not catch and swallow cancellation.

<a id="plat-04"></a>

### PLAT-04 [P2] Valid CP437 ZIP filenames can make the entire archive disappear

- Owner: KitePDF core ZIP reader, affecting CBZ and other ZIP consumers.
- Locations: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/zip/ZipReader.kt:309` and duplicate rejection at `:347`.
- Trigger: a standards-compliant ZIP with general-purpose bit 11 clear and CP437 non-ASCII filenames, as permitted by PKWARE APPNOTE section 4.4.4 and Appendix D. For example, two CBZ pages named `é.png` (filename byte `82`) and `ä.png` (filename byte `84`). Each contains a valid 69-byte 1x1 PNG.
- Defect: filename bytes are always decoded as UTF-8. Both single-byte names become `�.png`; the duplicate-name test then returns `emptyMap()` for the **entire archive**. With a single non-ASCII name, name lookup still exposes the wrong filename. This is valid-format support, not malformed-input hardening.
- Runtime evidence: Python `zipfile` reads `['é.png', 'ä.png']`, `testzip()` returns `None` and both PNG payloads are readable. Current `ZipReader` returns `names=[]` and `read("é.png")=null` for the same bytes.
- Repro: `/workspace/scratch/platform-probe/cp437.cbz`, `/workspace/scratch/platform-probe/ZipProbe.kt`. To construct with Python without setting bit 11:

```python
class Cp437Info(zipfile.ZipInfo):
    def _encodeFilenameFlags(self):
        return self.filename.encode('cp437'), self.flag_bits
with zipfile.ZipFile('cp437.cbz', 'w') as archive:
    archive.writestr(Cp437Info('é.png'), valid_png_bytes)
    archive.writestr(Cp437Info('ä.png'), valid_png_bytes)
```

- Fix direction: decode UTF-8 when bit 11 says so; otherwise apply the ZIP default CP437 and, when present and valid for the original name, the Unicode Path extra field. Perform duplicate detection on correctly decoded names. Add non-ASCII distinct-name archive coverage; do not weaken genuine duplicate rejection.

<a id="plat-05"></a>

### PLAT-05 [P2] Theme image identities collapse distinct functions with the same hash

- Owner: KitePDF core reading themes and image cache identity.
- Location: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ReaderTheme.kt:54`; consumption in `LineArt.kt:31` / recoloured image creation and `KiteBitmapCache.kt:54`.
- Trigger: use `ReaderImages.LineArt` and switch between two distinct custom `mapColor` function objects which share an ordinary 32-bit hash code, while reusing the same image and canvas/bitmap cache. Public `(RgbColor)->RgbColor` inputs have no collision-free-hash requirement; an application can legitimately implement the function interface in a class with its own hash function.
- Defect: the variant identity is the string `"$background:${mapColor.hashCode()}"`, so a hash collision is treated as actual function identity. `themedLineArt` correctly recomputes the new coloured image because its function reference differs, but the bitmap cache sees the same resulting image identity and returns the earlier colour's bitmap. The KDoc assertion that two themes never share a bitmap is false.
- Runtime evidence: two function objects implementing `(RgbColor)->RgbColor` both return hash code 7, one maps every colour to red and the other to blue. Apply each theme to the same valid one-pixel black image using one current `KiteRasterCanvas`, clearing the page between draws. First theme returns `FFFF0000`; second returns **FFFF0000 again**. Apply the second theme to a fresh canvas and it correctly returns `FF0000FF`. The theme objects compare unequal, so this is not misuse of their equality contract.
- Minimal input type:

```kotlin
class SameHashMap(private val color: RgbColor) : (RgbColor) -> RgbColor {
    override fun invoke(c: RgbColor): RgbColor = color
    override fun hashCode(): Int = 7
}
```

- Repro source: `/workspace/scratch/platform-probe/ThemeProbe.kt` (uses a core-internal image constructor only to avoid involving image decoding in the fixture).
- Fix direction: keep an opaque collision-safe namespace token or full function equality/identity in the variant key. Do not use a hash's string representation as equality. Preserve sharing for actually equal themes and non-owning source-image identity behavior.

<a id="plat-06"></a>

### PLAT-06 [P2] Closing the script runner from a host callback leaks its native engine and document graph

- Owner: KitePDF JavaScript lifecycle integration. The pinned KiteJS 0.7.0 guard correctly rejects freeing an engine while a script runs.
- Locations: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:779`, especially the conditional at `:783` and unconditional ownership release at `:801-808`; reentrant dispatch in `src@jvm/io/github/yuroyami/kitepdf/javascript/ScriptThread.jvm.kt:20`; `ScriptThread.kt:21` describes a dedicated-thread capability, not a test of the current caller thread.
- Trigger: handle the supported PDF JavaScript `this.closeDoc()` request by calling `runner.close()` in the supplied `onRequest` callback. The same problem applies to closing in other script-thread callbacks such as `onAlert`. Host callbacks are documented to execute on the script thread.
- Defect: on JVM, `busy > 0` does not defer closure because the dedicated script thread reports `isOwnThread == true`. `closeHere()` reenters that thread inline and calls the engine's `close()` while its script callback is active. KiteJS throws `JsEngineError("this engine is running a script; close it once that call has returned")` before unregistering or freeing. KitePDF nevertheless sets `openEngine=null`, leaves ownership, and shuts down the script thread in `finally`. It has already set `closed=true`, and did not set `closeWaits`, so no later path retries the native close. The process-global QuickJS host registry keeps the engine, its native runtime and callback/document references alive permanently. This is a leak, not a native use-after-free or a deadlock.
- Runtime verification: the repro uses the full current `kitepdf-core-head.jar`, `kitepdf-pdf-head.jar`, the current compiled script module and published KiteJS 0.7.0 on Linux/JVM. The callback and runner are public APIs; reflection only counts the upstream private registry and checks the now-discarded runner reference. Output:

```text
registered engines before=0
request=Document(what=closeDoc) callbackThread=kitepdf-scripts
run result=Success(null)
failures=[action: this engine is running a script; close it once that call has returned]
registered engines after=1
second close=Success(kotlin.Unit)
registered engines after second close=1
runner openEngine=null
```

- Repro: `/workspace/scratch/platform-probe/ScriptCloseProbe.kt`; run with `/workspace/scratch/script-probe/runtime.cp` plus `script-close-probe.jar`. It opens a valid PDF generated by the current `PdfBuilder`, creates `PdfScriptRunner(document, onRequest = { runner.close() })`, and runs `PdfAction.JavaScript("this.closeDoc(); 'after';", ...)`.
- Upstream ownership evidence: published `QuickJsKiteJs.kt:197-205` checks active depth before changing its own closed state, calling `QuickJsHost.unregister(id)`, and freeing the pointer. `QuickJsHost` stores a strong process-global map of engine instances. Those sources are extracted under `/workspace/scratch/upstream/kitejs-quickjs-jvm-source/`.
- Fix direction: defer callback-triggered close until the active execution unwinds on its owner thread, and retain engine ownership and its thread until engine closure succeeds. Add close-from-request/alert and repeated create/run/close coverage. Recheck native targets, which share the dedicated-thread branch, but this audit only executed the JVM case.

<a id="plat-07"></a>

### PLAT-07 [P2] Tiny raster steps allocate and scan the entire pure-Kotlin canvas twice

- Owner: KitePDF core pure-Kotlin raster rendering.
- Locations: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:251`, `:264`, and the whole-canvas `composite` scan at `:285-300`. Valid end-to-end callers include SVG filters at `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1380` and PDF overprint/group raster steps.
- Trigger: run a `KiteRasterStep` for a small region on a large `KiteRasterCanvas`, render its content through `scope.render`, then composite the result with `scope.draw`. This is the supported normal filter pipeline. A 16x16 step is sufficient; its output is 256 pixels regardless of canvas size.
- Defect: the method calculates the bounded region correctly, but `render` creates `IntArray(canvas.width * canvas.height)` and then crops it. `draw` expands that tiny raster into another full-canvas `IntArray` and calls a compositing loop over the whole canvas. Every step therefore allocates at least eight bytes per **canvas** pixel and scans the canvas even though all pixels outside the step box are known transparent. Repeated small filtered objects scale with object count multiplied by full canvas area, rather than their affected areas.
- Measured current-source evidence: fixed valid 16x16 region, one opaque red rectangle, output asserted red; full HEAD core compiled directly by Kotlin 2.4.20 with normal JVM optimizations, OpenJDK 21.0.12.1 64-bit Server VM, AMD EPYC 9V74, Linux x86_64; `-Xms512m -Xmx512m -XX:+AlwaysPreTouch`. Three fresh JVM forks, each with 1,200 global warmup steps, 50 additional steps per size, then 40 samples of three steps. `com.sun.management.ThreadMXBean` measures executing-thread allocated bytes. The microbenchmark deliberately uses a direct current-source compile; the separate official Kotlin Toolchain JVM suites also passed. These controlled JVM measurements do not represent a published-artifact or mobile release benchmark.

| Canvas | Step output | Allocated bytes / step, all 3 forks | Median ms / step, 3-fork range | p05-p95 ms / step, widest fork envelope |
| --- | --- | ---: | ---: | ---: |
| 256x256 | 16x16 | 526,856 | 0.111-0.113 | 0.110-0.154 |
| 1024x1024 | 16x16 | 8,391,176 | 1.587-1.656 | 1.572-2.370 |
| 2048x2048 | 16x16 | 33,557,000 | 6.749-6.801 | 6.302-7.800 |

- The exact byte count follows the two full-page arrays plus small fixed step overhead. Timing includes allocation/GC and is environment-dependent; the quantiles show observed variation, not statistical confidence bounds. No extrapolated frame-rate or reference-renderer claims are made.
- Repro: `/workspace/scratch/platform-probe/RasterStepPerfProbe.kt`, `raster-step-perf.jar`, and `raster-step-perf-results{,-2,-3}.log`, executed with the current `/workspace/scratch/pdf-head-build/runtime.cp`.
- Fix direction: give offscreen layers an origin and bounded width/height, clip the render target to the step box, and composite the output rectangle directly into the target. Preserve the existing promise that pre-step clips do not apply in `render` and apply once in `draw`. Validate nested masks/groups and transformed regions before claiming equivalent output.

## Inspected paths and remaining platform validation

Read core lock actuals (JVM, native, web), warning-sink publication, image/bitmap caches, font outline caches, ICC profile and table caches, lazy image-sample decoding, reader-theme decoration, raster scan/blend and callback paths. Read AWT/Skia/Android/Canvas2D/CoreGraphics drawing paths, host-font transformation/fallback, group/mask/raster-step implementations and rasterizer resource ownership. CoreGraphics CF/CG ownership pairs examined include fonts, strings, attributed lines, paths, gradients, copied CFData/providers/images and pinned bitmap contexts. This is targeted source inspection, not proof that every native allocation or every lock ordering is correct.

Do not present the following as verified defects: 32-bit hash-derived native thread IDs have a theoretical collision risk; per-intent ICC/image memo arrays and line-art memo fields are unsynchronized; Skia temporary native wrappers rely on cleaners in several hot paths. Their concrete failure rate, retained-memory cost and runtime consequences have not been established here. Native lock contention/scheduler latency needs release-build measurements on real Apple and Linux targets, including cancellation/priority inversion scenarios. Full Android device/16KB-page checks, Apple ARC/GC stress, browser/wasm rendering execution and cross-platform retained-memory benchmarks remain unverified in this audit lane.

### Unverified follow-up: Apple soft-mask grouping

`CoreGraphicsCanvas.applySoftMask` (`kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:993`) applies a `CGContextClipToMask` and calls `render()` directly. `beginTransparencyGroup` (`:714`) deliberately skips a transparency layer for a non-isolated Normal full-alpha group. `PageRenderer.kt:1511` wraps a Form transparency group in a soft mask which should apply to the composited group result once. A necessary Apple-runtime check is a valid Form group containing two overlapping opaque red shapes under a uniform 50% alpha mask: both singly covered and doubly covered areas should have the same 50% contribution. Per-paint clipping could instead make the overlap contribute 75%. Existing inspected Apple soft-mask tests use one paint and do not settle this. No Apple runtime was available, so this is a review/test target and is **excluded from the finding count**; it must not be presented as a verified CoreGraphics defect.


## Document JavaScript integration

These findings belong to KitePDF's Acrobat adapter and runner, not KiteJS. Current production JavaScript-module sources except the unrelated EpubScriptRunner were compiled with Kotlin 2.4.20 against current core/PDF and pinned KiteJS 0.7.0, ImageKodec 0.3.0 and kotlinx-datetime 0.8.0. The normal project build separately compiled the complete module with declared Kotlin 2.4.21 and ran its existing tests. Harness and results: `/workspace/scratch/script-probe/compile.py`, `ScriptProbe.kt`, `results.log`.

<a id="script-01"></a>

### SCRIPT-01 [P2] The document-wide script budget never accounts for the event currently running

**Owner:** KitePDF. **Evidence:** executed current runner plus actual pinned QuickJS.

**Source:** `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:693-707` increments `documentSpent` only after evaluation returns. The interrupt callback at `:718-728` compares only this completed-event total, without adding `now - eventStartedAt`. `PdfScriptPolicy.kt:22-31` permits a document-wide budget with per-event and instruction limits disabled. In addition, `onStillRunning` approval resets `eventStartedAt`, causing previously approved time slices to disappear from the final cumulative accounting.

**Valid trigger:** Create `PdfScriptPolicy(budgetMillis = 0, documentBudgetMillis = 10, instructionBudget = 0)` and run a long, finite PDF JavaScript action. The host can legitimately want a single shared allowance across multiple events, instead of a separate cap for each event. A first event must be stopped once it has consumed the document allowance.

**Executed reproduction:** Use an `AtomicLong` clock returning `getAndIncrement()` and run `var sum=0; for(var i=0;i<10000000;i++) sum+=i; 'completed';`. The engine's real interrupt callbacks advanced that deterministic clock to **78,130 ms**, yet the result was `completed` and `failures=[]`, despite the declared 10 ms document budget. These are synthetic budget-clock readings, not measured wall-clock latency. With completed time initially zero, a single non-returning event has no document-budget stop at all. No malformed PDF or script syntax is involved.

**Expected:** interrupt when completed script time plus current event time exceeds the cumulative allowance. **Fix direction:** separate the current event's absolute start, the next still-running prompt deadline and the accumulated document time. Prompt approval can extend an event without erasing its accumulated cost. Test first-event exhaustion, many short events, approved extensions and the combinations of zero/nonzero instruction/event/document budgets.

<a id="script-02"></a>

### SCRIPT-02 [P2] Acrobat getPageBox ignores which page box the script requests

**Owner:** KitePDF. **Evidence:** executed current adapter with a valid PDF.

**Source:** `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:543` forwards `which` and `page`, but the binding in `PdfScriptHost.kt:81` discards the first argument. `PdfScriptHost.kt:276-280` always returns `page.cropBox`.

**Valid trigger:** A page has MediaBox `[0 0 612 792]` and an inherited CropBox `[50 100 300 500]`. A PDF script calls `this.getPageBox('Media', 0)` to obtain the sheet bounds, then requests `'Crop'` to obtain the cropped bounds. Both names are ordinary Acrobat API arguments.

**Executed result:** Media returned `[50,500,300,100]`, expected `[0,792,612,0]`. Crop returned the same `[50,500,300,100]`. The underlying PdfPage correctly distinguishes these boxes; the error is introduced solely at the script bridge. Trim/Bleed/Art requests hit the same unconditional branch. Scripts using page boxes for layout, drawing or placement get the wrong region even while direct Kotlin APIs are correct.

**Fix direction:** pass and resolve the requested box name through the host, implement the documented box fallback and coordinate/rotation convention consistently, and test pages whose five boxes differ. The executed minimum establishes ignored box selection; it does not claim to have verified Acrobat rotation semantics for every box.


## Images and upstream library findings

Evidence date: 2026-11-04. KitePDF HEAD c4e7574b143689a8d115aba6dcd605cc4cc909f3. ImageKodec local HEAD f25823b74d76b6708faaaced3daf7cb5c42eec1d. During audit execution, no implementation changes, public issues or commits were made. Malformed-input hardening excluded.

The published `io.github.yuroyami:imagekodec-jvm:0.3.0`, `kitejs-api-jvm:0.7.0` and `kitejs-quickjs-jvm:0.7.0` binary and source artifacts were downloaded from Maven Central into `/workspace/scratch/upstream`. Every Kotlin source file in the published ImageKodec source jar matches local source except a KDoc sentence in ImageInfo.kt. Therefore code findings below apply to the actual pinned 0.3.0, not merely an unrelated upstream checkout. KiteJS findings use published source because there is no KiteJS checkout in this workspace.

Published binary SHA-256:

- ImageKodec JVM 0.3.0: `bdaf1a3c14786cefeace1c8beb98b7f8450fa4f82a195a983382203d2bcd8587`.
- KiteJS API JVM 0.7.0: `6341c905a6df01a3f5d94f45f1080d8554d5da137f1a30a2758ddfb3682ade83`.
- KiteJS QuickJS JVM 0.7.0: `ad529d0fa98121ac3c5d3ca39456a3a9c4f7c6f23e57bbea1eb65ea06352d647`.

<a id="img-01"></a>

### IMG-01 [P2] PDF JPX images discard an explicitly supplied PDF ColorSpace

Owner: **KitePDF**, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:424-441`; see also KiteImageSamples.kt:85-89. Confirmed by execution and independent MuPDF output.

`KiteImageData.from` resolves the PDF image's declared color space at line 302. Unlike the JPEG branch, both successful JPX paths replace it with DeviceGray or DeviceRGB from the codec result. In ISO 32000-1 Table 89 the explicit PDF ColorSpace determines interpretation of image samples. JPX's special rule allows omitting the PDF ColorSpace when the codestream supplies one; it does not allow discarding one that is supplied. The codec cannot infer the containing PDF's color space, so a workaround solely in ImageKodec would be misplaced.

Valid reproduction: a 2x2 grayscale lossless JP2 holding sample 128, embedded as `/Filter /JPXDecode /ColorSpace [/CalGray << /WhitePoint [0.9505 1 1.089] /Gamma 3 >>]`. `KiteImageData.from` returns DeviceGray and RGBA `(128,128,128,255)`. The declared `CalGray.toRgb(128/255)` is about `(0.39075,0.39076,0.39075)`, or `(100,100,100)`. The pinned MuPDF binary renders the full generated PDF cleanly and reports the center pixel `(100,100,100)`. Fixture `/workspace/scratch/upstream/jpx-calgray.pdf`; actual current-core probe `/workspace/scratch/upstream/PdfImageProbe.java`. PDF lane independently checked the spec expectation.

Impact: correctly authored JPX PDFs using CalGray, CalRGB or ICCBased overrides display incorrect colors on every renderer, including Compose raster and vector modes, because the samples are mislabeled before rendering. Test both eager (mask/alpha) and lazy paths.

Remediation: preserve PDF sample interpretation in JPX integration. If JPX color transformations destroy the original components, add an upstream ImageKodec raw component API and use it; keep the PDF precedence decision in KitePDF. Preserve optional embedded-color behavior when PDF ColorSpace is absent. Regression: render valid declared-CalGray JPX against MuPDF/PDFium, including a soft mask path.

<a id="img-02"></a>

### IMG-02 [P2] Self-contained document images lose their declared color profiles

Owner: **KitePDF**, `KiteImageData.kt:481-487`, `KiteImageAdapter.kt:30-47`. Confirmed by execution with the actual pinned codec.

The common EPUB/CBZ/SVG image loader calls `ImageKodec.decode(bytes)` with its default `ColorTarget.Source`. The bridge copies these file-space samples into a new image explicitly labeled DeviceRGB. The original ICC/gAMA/cHRM/cICP profile is not stored anywhere in that result. Consequently neither native, Skia nor Compose can apply the file's color interpretation later. This is independent of PDF XObject color semantics.

Valid minimal example: 1x1 8-bit grayscale PNG storing 128 with a valid gAMA chunk of 100000 (linear gamma 1). `ImageKodec.decode(bytes,colorTarget=ColorTarget.Srgb())` returns `(188,188,188)` as expected for the sRGB display of linear 128/255. `KiteImageData.fromEncodedImage(bytes).toRgbaBytes()` returns `(128,128,128,255)`. Source-built current core and published codec were used. Fixture `/workspace/scratch/upstream/linear-gray.png`; `/workspace/scratch/upstream/PdfImageProbe.java` prints actual output.

The consumer call paths are EpubDocument.kt:1603, SvgImage.kt:395 and 1439, and CbzPage.kt:60. A book or SVG using linear PNG or a Display P3 photograph has visible color shifts regardless of viewer render mode.

Remediation: request Srgb at the standalone image-to-display bridge or retain and honor the profile. Keep raw PDF image components under the separate PDF color-space rules. Regression: valid profile-bearing images end to end through EPUB/SVG/CBZ, with reference-browser color conversion.

<a id="img-03"></a>

### IMG-03 [P2] Self-contained document images ignore EXIF orientation and page geometry uses the stored axes

Owner: **KitePDF**, `KiteImageData.kt:485-487`, `kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/CbzPage.kt:35-42`, `ImageDims.kt:115-133`. Confirmed by execution for the common decode bridge, source-traced for CBZ sizing.

The common standalone image loader leaves `applyOrientation=false`. Once decoded to raw DeviceRGB, the EXIF tag is discarded, so the renderer cannot correct it. A valid JPEG with stored size 3x2 and EXIF orientation 6 decodes in the actual pinned codec to 2x3 when orientation is requested; current `fromEncodedImage` returns 3x2 and its first pixel comes from the unrotated red row instead of the blue row that should lead after rotation.

CBZ independently sizes its page using stored image headers, so changing only the final decode would still squeeze an oriented 2x3 raster into a 3x2 page. The dimensions and rendering must change together. EPUB intrinsic image sizing also must use the oriented dimensions. This is an API/metadata cross-effect, not a malformed-file concern.

Fixture `/workspace/scratch/upstream/oriented.jpg`; both `/workspace/scratch/upstream/ImageProbe.java` and `PdfImageProbe.java` ran. Expected oriented 2x3, actual shared document image 3x2. Call sites as IMG-02.

Remediation: honor orientation at standalone image decode and consistently use displayWidth/displayHeight in intrinsic/page sizing. Do not blindly apply JPEG EXIF to PDF DCTDecode streams whose placement is controlled by PDF matrices. Regression: eight EXIF orientations with asymmetric rows, EPUB intrinsic dimensions, and CBZ page bounds.

<a id="ik-01"></a>

### IK-01 [P2] decodeAnimation truncates deep samples before color conversion, so a static PNG changes color when called through the animation facade

Owner: **ImageKodec 0.3.0**, `imagekodec/src/commonMain/kotlin/io/github/yuroyami/imagekodec/ImageKodec.kt:721-741`, with correct static ordering at 475-483. Confirmed on the published binary; local source matches.

`decode(...,colorTarget=Srgb)` converts samples deeper than 8 bits through raw16 and narrows afterward. `decodeAnimation(...,colorTarget=Srgb)` obtains 8-bit composited frames first, then converts their already truncated bytes. Even a static PNG goes down this alternate PNG branch. This violates the color target's documented full-precision conversion and makes equivalent public entry points visibly disagree.

Valid example: 1x1 grayscale 16-bit PNG with stored sample 255 and gAMA 100000. Static Srgb decode gives `0xff0c0c0c`; Srgb decodeAnimation with maxFrames=1 gives `0xff000000`. The low byte is the entire signal near black; throwing it away before a nonlinear transfer is not an 8-bit rounding difference. Gamma fixture `/workspace/scratch/upstream/gamma16.png`; executed `ImageProbe.java`.

Impact: code using the animation-safe facade for all formats loses dark detail and develops bands in deep gamma/HDR images. The Compose/Coil bindings currently use Source by default, so this particular Srgb-specific defect is not claimed as their active path until they request color-managed output. KitePDF currently calls static decode, so fixing IMG-02 by requesting Srgb will not itself activate this bug.

Remediation: convert/composite at sufficient source precision before final 8-bit reduction; at minimum route truly static PNG/AVIF/JXL through the same full-precision static color conversion. Regression: assert facade equivalence on static deep PNG and frame-specific independent oracles for animation.

<a id="ik-02"></a>

### IK-02 [P2] An APNG thumbnail parses and copies every later frame before applying maxFrames

Owner: **ImageKodec 0.3.0**, `PngDecoder.kt:110-122,353-360`, and public contract `ImageKodec.kt:695-696`. Confirmed source path and allocation measurement on valid APNG files.

`decodeAnimation` calls a complete `parse(data,wantAnimation=true)` before slicing the result to maxFrames. Parsing reads/CRC-checks every fcTL/fdAT and copies each fdAT payload once in `r.bytes` and again in `copyOfRange`. Thus `maxFrames=1` does not stop reading past the first frame as the public contract says, and cancellation is first checked only after parsing the entire file. This is relevant even for completely valid files: a paused APNG in a list incurs memory/CPU proportional to all future frames before its first frame appears.

Measured using published release JVM artifact, JDK21, `-Xmx512m`, `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, 5 warmups and 10 measured decodes per valid file. All files have 256x256 identical seeded random RGB frames, and all return just one frame. Independent Pillow loads every frame and reports exactly 1, 8 and 64 respectively.

| File frames | Input bytes | Per-call allocated bytes, min / mean / max |
|---|---:|---:|
| 1 | 197,050 | 1,204,904 / 1,535,886 / 3,277,544 |
| 8 | 1,575,973 | 3,945,512 / 3,952,260 / 3,967,976 |
| 64 | 12,607,357 | 26,051,560 / 26,051,560 / 26,051,560 |

This is allocation traffic, not retained heap or a throughput claim; early small-case variance includes JVM compilation/class initialization. The 64-frame result and source establish the unnecessary approximately twice-input payload copying. Probe `/workspace/scratch/upstream/ApngAllocationProbe.java`; fixtures valid-N-frames.png. No nanosecond or cross-platform latency claim is inferred from these measurements.

Remediation: thread frame limit/cancellation through container parsing, stop after the requested frame's data, and retain slices rather than copies for skipped/later fdAT. Update public contract only if full validation is intentionally retained. Regression: allocation slope and container read/copy accounting for maxFrames=1 on valid large animations. Apply upstream, not a KitePDF format-specific workaround.

<a id="kjs-01"></a>

### KJS-01 [P2] Reclaiming a batch of Kotlin handles allocates quadratic garbage in the next QuickJS call

Owner: **KiteJS QuickJS 0.7.0**, published source `commonMain/.../facade/QuickJsKiteJs.kt:100-108` (cleaner callback), `:281-285` (sweep); `jniMain/.../bridge/JniBridge.kt:135-139`. Confirmed by direct published-binary allocation probe.

Every handle delivered by the cleaner appends with `now + h` to an immutable List inside an AtomicReference. Appending N dead handles copies lists of size 1,2,...,N, so reclamation costs O(N^2) allocation. The JVM queue itself is already drained on the owner thread, but still goes through this copying queue. `sweep()` is run synchronously at the start of the next outermost engine call, so ordinary GC batches cause a latency/GC spike in that call.

Repro: hold N `js.newObject()` wrappers, clear the host list, force host GC, then measure allocation during `js.evaluate("0")`. Instrumenting the cleaner's live set by reflection verified the handles were actually removed; this does not rely on elapsed time. Published binary, JDK21, `-Xmx512m`, 4 runs per N:

| N | Allocated during next outermost call |
|---:|---:|
| 1,000 | 4,104,888 after first run (first 4,143,128) |
| 2,000 | 16,212,888 in all four runs |
| 4,000 | 64,428,888 in 3/4 runs, 64,364,688 when two startup handles had been swept earlier |
| 8,000 | 256,860,888 in all four runs |

Probe `/workspace/scratch/upstream/KiteJsCleanupProbe.kt`. These are allocations, not timings or retained leakage; the roughly 4x growth for 2x N corroborates the copying algorithm. Input objects are ordinary valid API-created values.

Remediation: upstream O(1) enqueue/batched publication with a linear owner-thread drain. On the JVM the queue may release directly on the owner's sweep, while a thread-safe linked/chunked queue handles asynchronous native finalizers. Preserve cross-thread cleanup safety. Do not weaken Kotlin/Native correctness to optimize only JVM.

<a id="kjs-02"></a>

### KJS-02 [P2] Unreachable compiled scripts can exhaust the engine's native memory budget before the host GC reclaims their tiny wrappers

Owner: **KiteJS QuickJS/API 0.7.0** for deterministic resource ownership; KitePDF integration can avoid repeated loads with a bounded per-engine compiled-script cache. Published source `QuickJsKiteJs.kt:132-148`, `QuickJsHandles.kt:197-205`, `kitejs-api .../JsRefs.kt:182-196`. Confirmed with the published native JVM engine.

Script handles are finalized automatically, so this is **not a permanent native leak**. However `JsScript` has no close/release API; its only release path is host GC followed by an engine sweep. Repeated `loadBytecode(bytecode).run()` creates large native scripts and tiny Kotlin wrappers. A bounded engine can run out of native memory while the host heap remains far below its own GC trigger. Closing the whole engine is currently the only deterministic public release mechanism.

Executed on JDK21, `-Xms256m -Xmx512m`, published 0.7.0: open QuickJS with memoryLimit=16MiB; source `"(()=>{let x=0;" + "x+=1;".repeat(8000) + "return x;})();"` is 40,028 chars, compiled bytecode 136,118 bytes. Repeatedly load/run that same bytecode without retaining scripts. It fails after 121 successful runs with `JsEngineError: the engine ran out of memory`. After `System.gc()`, a 200ms allowance for reference processing, and the next sweep, the same engine successfully loads/runs it again. Probe `/workspace/scratch/upstream/KiteJsScriptLifetimeProbe.kt`. A positive control then retained one loaded script and ran it 3,000 further times in the same engine without forced host GC, successfully. This directly distinguishes delayed reclamation from script-side live data and permanent leakage. A large repeated string literal did not reproduce because QuickJS interns/shares that string, so that weaker probe was rejected.

KitePDF's `KiteJsScriptEngine.kt:72-83` calls `script(source, name).run()` for sources of at least 32 KiB; `:90-104` loads a new compiled wrapper from the global bytecode cache on each hit. That byte cache does not retain the per-engine compiled script. Public callers should be able to dispose a transient compiled resource without forcing host-wide GC. API changes belong upstream even if a retained per-engine cache also improves KitePDF.

Remediation: add deterministic script handle lifetime/release with idempotent semantics, or an engine-owned bounded compiled-code cache/reclamation policy that works under its native budget. Preserve the ability to run a deliberately retained JsScript repeatedly. Regression: many transient loads under a small native budget with host GC deliberately delayed, plus retained-script reuse and close/double-close behavior.

## Optional quality/documentation defect: integer-bin scaling is not true area-average box filtering

Owner: **ImageKodec**, `Scaling.kt:62-97`. Executed against published 0.3.0. This is a deliberate implemented approximation, as ExactScalingTest.kt:22-61 codifies the same one-bin-per-source algorithm. It should not be mislabeled as an untested indexing bug.

The documented area-average filter assigns whole source pixels to one destination bin instead of weighting their fractional overlap. For the opaque 3x1 source `[white,black,black]` reduced to 2x1, output channels are `[128,0]`; true source-area boxes `[0,1.5)` and `[1.5,3)` give `[170,0]`. Mirroring the input gives `[0,255]`, rather than mirroring the previous result `[0,128]`. Thus non-integral scaling introduces visible spatial/brightness bias and does not commute with horizontal reflection. If true area filtering is part of the fidelity promise, treat as P2; otherwise document the approximation and list area resampling as an improvement opportunity. Regression needs an independent mathematical fractional-area oracle, not the same bin formula as the implementation.

## Coverage and rejected hypotheses

- Decoding/probing/transforms/color conversion/animation/bindings and the PDF codec integration were sampled deeply. The complete approximately hundred-file AV1/JXL/JPEG/JPX internal algorithms were not exhaustively proven against all specifications.
- No malformed-input hardening or fuzz defects are included.
- JVM execution and allocation measurements were performed. Apple, Android devices, JS/Wasm runtimes were not executed in this lane; no claims of device timings or platform parity are made.
- A detached/out-of-range typed-array byte extraction probe returned an empty array. The API's phrase "gives no bytes" is ambiguous, so no defect was asserted.
- QuickJS script wrappers have a cleaner and engine close frees all handles. A claim of unconditional/permanent compiled-script leakage would be false; KJS-02 precisely describes transient resource pressure.
- QuickJS.close rejects calls while depth>0 before freeing active native state. Therefore a reentrant KitePDF close should be analyzed as a rejected/deferred close and potential retained-resource lifecycle bug, not an asserted native use-after-free.
- No wall-clock or nanosecond performance superiority claims were made. Allocation measurements are bounded observations on the stated release artifacts and JDK, with input sizes/sample counts recorded.


## Distribution, documentation, interop and validation

Audited KitePDF `c4e7574b143689a8d115aba6dcd605cc4cc909f3`. No implementation files changed. Malformed-document hardening excluded. The findings below distinguish source proofs, isolated executed probes and full integration execution. The isolated Python probe is `/workspace/scratch/distribution_repro.py`.

<a id="dist-01"></a>

### DIST-01 [P1] Normal failures from the Apple public API terminate Swift callers

**Owner:** KitePDF. **Evidence:** source-verified Kotlin/Native interop defect, not Apple-executed in this Linux audit.

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:1031` and `:1095` export the two `open` overloads without `@Throws`. A valid encrypted PDF opened with an omitted or incorrect password throws `KiteWrongPasswordException` at `:1045`.
- `kitepdf/src/io/github/yuroyami/kitepdf/document/KiteDoc.kt:88` forwards that call, also without `@Throws`, despite documenting the exception at `:85`.
- Apple-specific entry points do the same: `kitepdf/src@apple/io/github/yuroyami/kitepdf/document/Sources.apple.kt:23`, `:43`, `:55`. A normal file-read failure throws at `:29` or `:49`.
- A repository-wide source scan found zero `@Throws` declarations. KDoc `@throws` does not change the generated Objective-C/Swift error convention.

**Valid trigger:** An ordinary Swift app embeds the generated framework, calls the `NSData`/`NSURL` or byte-array `open` API for a password-protected, valid PDF, and the reader supplies a wrong password. The exception crosses a non-throwing Objective-C boundary. Standard Kotlin/Native export semantics terminate the process for an exception from such a function, so Swift cannot catch it as an `NSError` and ask for another password. The same issue affects recoverable file permission/file disappearance failures and other documented throwing APIs. `openOrNull` is an available workaround for byte arrays, but it collapses failure classes and does not repair the documented throwing APIs or their Foundation overloads.

**Expected:** recoverable error propagation for the documented failure modes from a native consumer. **Remediation:** give the exported throwing entry points explicit `@Throws` declarations for their supported exception hierarchy, including wrappers and relevant suspend APIs; validate a wrong-password retry and a disappearing-file error from an actual Swift test. Kotlin/Native behavior reference: <https://kotlinlang.org/docs/native-objc-interop.html#errors-and-exceptions>.

<a id="dist-02"></a>

### DIST-02 [P2] Web test npm resolution depends on unrelated cached artifacts and can miss newly downloaded dependencies

**Owner:** KitePDF tooling. **Evidence:** executed isolated Python probe against the actual `scripts/web-tests/run.py` functions, plus published Maven metadata inspection. Full web suites were not run for this finding.

`scripts/web-tests/run.py:75-90` memoizes one scan of every JS/Wasm klib in the global toolchain Maven cache and chooses the largest npm version it encounters. `:101-109` installs that result for the currently linked output. This is not the npm dependency graph of the output under test.

**Executed probe:** import the script without running `main`, point `CACHE` and `ROOT` at temporary directories, write a selected output importing `@js-joda/core`, and construct zipped klibs with legitimate `package.json` dependency metadata. With only the selected dependency cached, the script schedules `@js-joda/core@5.0.0`. Add a klib for an unrelated project requiring `6.0.0`, clear the memoization only to simulate a new script run, and the identical selected output now installs `@js-joda/core@6.0.0`. Thus unrelated cache contents change the tested dependency version.

There is also a fresh-cache defect: let the first module scan an empty npm map, then download a later module's klib requiring `@js-joda/core`. `install_npm_packages` emits **zero** npm install calls because the first map is cached forever and `imported & versions.keys()` silently drops the missing package. The default module order is core before JavaScript (`:29-30`), and linking happens immediately before each module's package installation (`:112-115`). The published KiteJS 0.7.0 JS metadata depends on kotlinx-datetime 0.8.0, whose published klib package.json requires `@js-joda/core` 3.2.0. A clean local run which has not prebuilt that later dependency can therefore miss its npm package. CI prebuilds all web modules before this script, which can hide this branch.

**Impact:** a clean documented local web test run can fail with a missing npm module, while a warm cache can test an unrelated npm version. Passing results are not reproducible evidence for the declared graph. **Remediation:** derive npm dependencies from the resolved klibs for the linked module, resolve conflicts explicitly and persist a lockfile; do not cache a global snapshot across module linking, and fail if an emitted bare import has no resolved npm dependency. Repro output:

```text
only actual dependency cached: @js-joda/core@5.0.0
with unrelated dependency cached: @js-joda/core@6.0.0
first module scans an empty npm cache: {}
after later module downloads datetime: npm install calls = 0
```

Published metadata inspected (HTTP 200 on 2026-11-04):

- <https://repo.maven.apache.org/maven2/io/github/yuroyami/kitejs-api-js/0.7.0/kitejs-api-js-0.7.0.module>
- <https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime-js/0.8.0/kotlinx-datetime-js-0.8.0.klib>

<a id="dist-03"></a>

### DIST-03 [P2] The publication consumer gate can fall back to the existing release instead of detecting missing local artifacts

**Owner:** KitePDF tooling. **Evidence:** source-verified repository resolution path and live Maven metadata, not an executed Gradle fault injection.

`consumer-test/settings.gradle.kts:24-26` puts unrestricted `mavenLocal()` before unrestricted Maven Central. `consumer-test/build.gradle.kts:8-10` takes the existing source version, currently 0.13.0, and `:13-16` uses it for the tested artifacts. CI publishes and tests the same release version (`.github/workflows/ci.yml:323`, `:333`). Maven Central's `io.github.yuroyami:kitepdf` metadata, read during this audit, confirms **0.13.0 already exists** and is its latest release.

**Trigger:** a publishing regression omits a local KitePDF module/coordinate, while the publisher succeeds for the other requested artifacts. When the consumer cannot find the omitted module in Maven Local, Gradle is allowed to resolve its already-published 0.13.0 from Central. The tests can then pass against the previous release or a mixture of old and locally built modules. This undermines the check's stated purpose of making missing publications fail.

**Expected:** default local publication verification fails when any requested current-build KitePDF artifact is absent. **Remediation:** publish the consumer test graph with a unique audit/CI version or restrict the local mode's KitePDF group to the build's isolated local repository with `exclusiveContent`; allow external dependencies such as KiteJS from Central as usual. Keep the explicit central-only release check as a separate mode. Metadata: <https://repo.maven.apache.org/maven2/io/github/yuroyami/kitepdf/maven-metadata.xml>.

<a id="dist-04"></a>

### DIST-04 [P2] The README's plain-JVM Skia setup omits its required native runtime

**Owner:** KitePDF documentation. **Evidence:** source and published metadata inspected, failure not JVM-executed in this audit lane.

`README.md:103-109` says a server/CLI without Compose needs just two dependencies and explicitly offers `kitepdf-skia-renderer` as the alternative second dependency. The subsequent extras table (`:117-120`) lists extra Skia setup only for Android. That recipe is insufficient on the stated plain JVM path.

`kitepdf-skia-renderer/module.yaml:34-37` includes Skiko, while native JVM runtimes occur only in `test-dependencies@jvm` (`:41-48`), so tests supply the missing piece themselves. The published 0.13.0 `kitepdf-skia-renderer-jvm` runtime metadata likewise contains Skiko, stdlib, PDF and EPUB, but no `skiko-awt-runtime-*`. `PdfPageRasterizer.renderToImage` reaches native Skia at `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/PdfPageRasterizer.kt:48`.

The docs site already describes the requirement correctly at `docs/rendering.md:126-132` and `docs/getting-started.md:35`: add an appropriate native Skiko runtime when Compose does not supply one. **Impact:** copying the README's headless Skia alternative into a plain JVM app leads to a missing-native-library failure at first rasterization. **Remediation:** add the matching JVM runtime to the README extras table and show it in the plain-JVM Skia recipe, linking the existing platform runtime guidance.

<a id="dist-05"></a>

### DIST-05 [P3] The benchmark report labels a minimum as a median

**Owner:** KitePDF tests/documentation. **Evidence:** direct source proof, no performance measurements claimed.

`kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/BenchmarkTest.kt:40` writes "Median of timed iterations after warmup" to every generated report. The shared timing helper at `:186-199` initializes `best`, retains only the smallest duration and returns that minimum. This affects read, write and codec numbers in the report. A slow distribution is presented as a central value, so a reviewer can mistake its fastest sample for typical latency. **Remediation:** calculate the stated median or label these results accurately as minima and include sample counts; retain the distribution if the report is used for performance decisions. This is separate from whether choosing a minimum is appropriate for an anti-flake CI budget.

<a id="dist-06"></a>

### DIST-06 [P3] Platform documentation claims a JS network test suite which CI does not run

**Owner:** KitePDF documentation/test coverage. **Evidence:** static inventory of all net test files and CI runner selection.

`docs/platforms.md:59` says the JS row runs the format modules, `-net` and `-javascript` on Node. `scripts/web-tests/run.py:29-30` excludes `kitepdf-net`, and the module contains only `test@jvm/RemoteSourcesTest.kt` and `test@jvm/DocsKtorVersionTest.kt`; there is no common or JS network test suite for the runner to execute. CI's web job builds JS and then invokes this selection. **Impact:** browser/Node Ktor transport, cancellation and byte-stream behavior have no JS runtime evidence despite the published test-coverage claim. **Remediation:** correct the coverage table, and add a JS/wasm runnable suite if JS network parity is a release requirement. The absence is not itself proof of a JS transport bug.

## Coverage and capability observations, not additional product defects

- The source format matrix supports PDF, EPUB, CBZ, SVG, XPS/OpenXPS reading/rendering. Only PDF editing/creation ships, as the README matrix already states. "Every ebook ever" is a goal, not current shipped capability. No direct Markdown, MOBI/AZW, DjVu, FB2 or RTF reader is registered in `KiteDoc`.
- Headless output is divided by renderer: native helper APIs are PDF-specific; Skia helper APIs cover PDF and EPUB; the generic Compose rasterizer handles `KitePage` including the other formats. This distinction is documented in module descriptions and should stay explicit in comparisons.
- The ABI gate compares built declarations with checked-in dumps. It does not by itself prove compatibility with the prior release, behavioral compatibility, Swift header usability or a Kotlin consumer compiled against a previous binary. No previous-release binary client or Swift consumer is executed by the inspected CI workflow.
- The publication consumer fixture is JVM-only and depends on umbrella, native renderer and JavaScript. It does not exercise published Compose, Skia, net or media artifacts, Android variant selection, Apple framework linking, JS/Wasm package consumption or direct Java/Swift/JavaScript clients. Compilation of source targets is different evidence from published consumer integration.
- `scripts/apple_frameworks.py` exists but is not invoked by CI. The inspected source CI workflow has no Swift compilation/run gate. Apple-native interop claims therefore need separate evidence, particularly for DIST-01.
- Existing performance tests provide budgets and opt-in JVM timings, not nanosecond-level cross-platform measurements, distribution uncertainty, sustained memory measurements or competitive throughput on pinned matching workloads. The inspected benchmark compares Flate with the JDK, but does not time PDFium, MuPDF or Acrobat against KitePDF. No competitive performance claim can be certified from these tests.
- The parity harness checks normalized character multiplicities, not ordering (`ParityHarness.kt:397-407`), and marks a page as no-consensus when every engine is a tile outlier (`:343-346`). Its pass verdict is narrower than text order/spec parity. These are test interpretation limits, not proof that the product currently extracts a particular page wrongly.
- The README has an unconditional "behaves identically" statement (`README.md:43`), while the documentation describes backend differences and the CI platform table has many compile-only targets. Treat absolute platform parity as unestablished; do not infer it from shared source alone.
- I checked published Maven metadata read-only, current build declarations, API dumps, documentation, CI, web test scripts and the benchmark/three-engine harness. I did not execute platform packaging/Swift/browser/Android consumption or comparative benchmarks in this lane. No public issues were created.



## Cross-cutting design conclusions

These are recurring causes of the numbered defects, not extra counted findings.

1. **Editor operations do not consistently read the effective staged graph.** Metadata and signer code copy base objects while other editor paths use staged-or-base resolution (PDF-04/09). Shared-object mutation also lacks document-wide ownership accounting (PDF-02). A central effective resolver and a deliberate copy-on-write policy would prevent entire classes of operation-order bugs. Failure atomicity must include editor bookkeeping, not only serialized bytes (PDF-12).
2. **Cache identity is split across unrelated layers.** Whole-page raster keys, detailed tiles, EPUB outline summaries, derived text/link cells and theme bitmap variants encode different subsets of rendering state (COMPOSE-03, FMT-05/06, PLAT-05). A pixel/layout revision needs to capture all inputs and must be checked again when an asynchronous result is published. Hash values alone cannot establish equality.
3. **A dispatcher is being used as an operation queue.** Single-thread execution and limitedParallelism(1) do not serialize suspending operation lifetimes (COMPOSE-01). The public serialization/completion contract needs an explicit owner and completion chain. Callback scopes must also survive the operation they start (COMPOSE-04), and recomposition must migrate registrations without closing a still-owned player (MED-02).
4. **Format adapters discard semantics already available upstream.** ImageKodec already supplies sRGB conversion and orientation, but KitePDF defaults discard that metadata; conversely PDF JPX semantics require preserving the containing PDF's explicit color space (IMG-01/02/03). Applying one blanket image policy would fix one path and break another. Document-format interpretation belongs in KitePDF; codec precision and deterministic engine resources belong upstream.
5. **Native resources and Kotlin heap pressure have different lifetimes.** Tiny JsScript wrappers can hold large native code objects, and owner-thread cleanup can itself be quadratic (KJS-01/02). A high Kotlin heap limit does not make a smaller engine budget safe. Reentrant runner close also needs deferred owner-thread cleanup (PLAT-06).
6. **Self-oracles and aggregate scores permit correlated bugs.** Round-tripping through two implementations of the same mistaken state machine can pass (PDF-06/07), and a low average pixel error cannot establish correct signature wiring, confidentiality, page ownership or callback completion. Existing tests should assert independently observable output and completion, not merely reproduce the same formula or cache key as production.

## Explicit parameter-interaction coverage

This is a record of reviewed/reproduced intersections, not a claim that millions of combinations were enumerated.

| Interacting inputs or phases | Evidence and consequence |
|---|---|
| Rasterized + capped bitmap + existing detail tiles + scripts null-to-handler + hidden widget | Current scene, COMPOSE-03; Vectorized control correct |
| Paged/Spread + zoom/pan-edge + userScrollEnabled=false | Actual pointer scenes, COMPOSE-02 |
| Focused field + suspending handler + await commitFocusedField + save/export | Actual state/scene, COMPOSE-01 |
| Outline target + animated page navigation + callback removes drawer | Real viewer and controlled cancellation scenes, COMPOSE-04 |
| Autoplay or initial tap + allowRemote=false-to-true + retained composition | Actual media scene, MED-01 |
| Open media player + session/place change + second player in new session | Actual media scene, MED-02 |
| Complete EPUB + :target/script repagination + previously read outline | Public EPUB repro, FMT-05 |
| Background derived text/links + simultaneous chapter replacement + cache publication | Controlled real-method interleaving, FMT-06 |
| Saved graphics state + standard/embedded font switch + restore + showText | Writer and independent-reader results, PDF-06/07 |
| Earlier editor operations + signature preparation + save | PDF-08/09; staged metadata combinations PDF-04 |
| Shared Form across pages + one-page redaction + rewrite | Independent-reader result, PDF-02 |
| Field export/display pair or repeated widgets + fill + saved appearances | PDF-10/11 |
| Host-font fallback + affine shear/reflection + backend choice | PLAT-01; Compose and CoreGraphics preserve full matrix |
| Isolation/knockout + blending + pure raster fallback | PLAT-02 |
| Soft-mask/raster-step callback + cancellation + reuse | PLAT-03 |
| Line-art recoloring + custom function hash collision + shared bitmap cache | PLAT-05 |
| Small filter ROI + large output surface | Measured allocation/latency growth, PLAT-07 |
| PDF explicit color space + JPX + eager/lazy decode | IMG-01; eager/lazy coverage bounds stated there |
| Standalone image + color metadata/orientation + intrinsic page dimensions | IMG-02/03; CBZ header and rendering need one geometry policy |
| Static 16-bit PNG + animation facade + sRGB conversion | IK-01 |
| Valid long APNG + maxFrames=1 | Published-binary allocation probe, IK-02 |
| Document budget + disabled event/instruction budgets + first running event | SCRIPT-01 |
| Per-process bytecode cache + repeated engine loads + host/native memory limits | KJS-02 |

Untested products include every layout orientation/reverse-layout/RTL combination, all text-layer/font-environment combinations under tiling, all save/close/cancel interleavings on every target, every codec acceleration path, and every legal format-field combination. The numbered triggers and source paths give concrete regression seeds for a systematic interaction suite.

## Shipped capabilities and comparison boundaries

KitePDF is currently a multi-format reader/rendering engine and a PDF writer/editor. The registered formats are PDF, EPUB, CBZ, SVG and XPS/OpenXPS. EPUB editing/creation and SVG scripting are not shipped and are already marked as such in the README. A generic document API does not currently add MOBI/AZW, DjVu, FB2 or RTF support. ImageKodec having a codec does not automatically mean the CBZ file-extension selector or a document adapter exposes it.

| Comparison | Evidence available here | Conclusion supported |
|---|---|---|
| KitePDF vs MuPDF 1.27.2 | PDF/Skia sampled corpus sweeps, informational EPUB comparison, valid per-finding writer/rendering oracles | Good aggregate rendering scores coexist with the documented defects; no full PDF/EPUB/XPS spec-parity certificate |
| KitePDF vs PDFium 153.0.7999.0 | Explicit pinned parity harness; test checks consensus pixels/text under its own rules | Its pass has the limits of the harness, including known gaps and text-order normalization; no claim of every PDFium feature or performance parity |
| KitePDF vs Acrobat | Acrobat-compatible API source contract and PDF specification reasoning | Acrobat was not run; no quantitative correctness, editing or performance ranking |
| Compose integration | Direct common Compose canvas plus optional raster backends; current actual JVM scenes | A distinctive shipped integration with real parameter interactions still needing correction; no Android/iOS/browser runtime parity certification |
| Format breadth | Source registration, adapters and capability matrix above | Broader than a PDF-only API; supported file formats still contain feature subsets and wiring gaps |

No benchmark here compares equivalent release-build document workloads against MuPDF/PDFium/Acrobat, so matching or exceeding their performance remains unestablished. For a valid comparison, pin binaries, fonts, DPI, color management, thread policy, target hardware and document set; compare open/first-page/sustained page latency and peak/retained memory, with warmup, distributions, sample counts and uncertainty. Include cold and warm caches, text/vector/scanned/transparency-heavy PDFs and long reflowable books. Native Swift/Android/JS/Wasm runs need their own results; JVM nanosecond estimates cannot substitute.

## Opportunities, separate from defects

These priorities are engineering judgments, not measured user-demand data.

| Proposal | User need and reach | Relative cost | Why it follows from this audit |
|---|---|---|---|
| Share a complete staged-object resolver and copy-on-write resource model | Reliable PDF editing/signing/redaction across every consumer | Medium/high | Removes operation-order and resource-aliasing failure classes rather than patching one caller at a time |
| One full-suspend script queue and authoritative render/layout revisions | All interactive Compose readers, especially web scripting | Medium | Supports reliable save, parameter changes, cache invalidation and cancellation ownership |
| Color/orientation policy at the standalone image boundary, preserving separate PDF raw-component semantics | EPUB covers/photos, CBZ comics and embedded SVG assets on every backend | Medium | Most upstream capabilities already exist; adapter/header consistency is the missing part |
| Published consumer matrix for Java, Swift/ObjC, JS/Wasm and Android | Library adoption on the platforms named in the goal | Medium/high | Source compilation and current API dumps do not establish framework error delivery, packaging or previous-release binary compatibility |
| Extend CBZ image selection to additional ImageKodec formats, with agreed archive conventions | Modern comic/image collections | Low/medium | Codec availability alone is not end-to-end format support; dimensions, orientation and memory policy must accompany it |
| True fractional-area resampling, if required by the fidelity contract | High-quality thumbnails and image reduction | Medium | Current integer-bin algorithm is deliberate/tested but is not true area averaging; optional example is documented in the upstream section |
| Additional ebook readers, after specifying formats and licensing | New reader categories such as DjVu/FB2/MOBI | High/variable | Entire parse/layout/navigation pipelines are required; listing ImageKodec/KiteJS capabilities does not supply them |

## Remaining validation boundaries

The audit did not execute Apple/Swift runtime, Android devices including 16 KiB page-size packaging, Kotlin/Native GC/ARC/pinning stress, browser JS/Wasm rendering/scheduling, WASI, Windows binaries, or previous-release binary consumers. It did not compile every documentation sample exactly as written, exhaustively verify licenses/link reachability, prove every font/color/codec algorithm, or perform interrupted-save crash-consistency and long-duration retained-heap experiments. These are unexecuted lanes, not silently passed checks and not extra defects.

The source review examined platform actuals, locks/caches, ownership and packaging alongside current common code. Specific native hypotheses which lacked execution or a complete proof remain labeled unverified in their lane sections. No theoretical race, cleaner reliance, hash-derived thread ID, or Apple mask concern is promoted into the verified count. Exact specification fidelity and extreme performance remain goals that require the remaining measurements and conformance work.

## Tracker duplicate cross-check

Bounded read-only searches and full reads of plausible historical matches found no exact duplicate for the checked current triggers. This is not proof that no differently worded issue exists. Related historical issues and distinct triggers are recorded below. No GitHub issues or comments were posted. The explicitly documented standard-font limitation is marked in PDF-05.

### Read-only historical matches

Searched GitHub issues in `yuroyami/KitePDF`, including closed issues, through the GitHub connector. Also searched `yuroyami/ImageKodec` for gamma/gAMA/EXIF/orientation. Fetched the full bodies and current states of plausible matches. These are bounded searches, not proof that no differently worded issue exists. No issue, comment or state was mutated.

No exact duplicate was found for the requested current findings. The following historical matches are related but describe different triggers and defects. Every specifically fetched historical issue below is currently **closed / completed**.

| Current finding | Closest historical issue | Why it is not an exact duplicate |
|---|---|---|
| Rewritten redaction preserves BDC `/ActualText` | [KitePDF #468](https://github.com/yuroyami/KitePDF/issues/468), Page text ignores ActualText | #468 adds extraction of replacement text; it does not redact or scrub it. The new leak arises when the visible show operation is removed while replacement text is retained. |
| Redacting a form used by two pages modifies the untouched page | [#278](https://github.com/yuroyami/KitePDF/issues/278), Redaction text-state drift; [#631](https://github.com/yuroyami/KitePDF/issues/631), soft-mask redaction | #278 concerns text positions, rise, inherited font and widths, not form ownership. #631 concerns an unvisited SMask group; its body assumes shared forms are copied, and does not report the reproduced across-page in-place mutation. #631's state was not needed to establish this distinction. |
| Reorder/graft drops an inherited CropBox | [#273](https://github.com/yuroyami/KitePDF/issues/273), indirect boxes read as missing | #273 concerns resolving an indirect value while reading; current trigger has an ordinary inherited array that was read correctly, then is omitted while flattening/reparenting. No result for graft or CropBox+order. |
| Builder encoder and plain extraction ignore q/Q font restoration | [#459](https://github.com/yuroyami/KitePDF/issues/459), plain extraction omits Form XObjects | #459 adds Do traversal and scoped resources. It does not describe graphics save/restore of font encoding in the page stream or the builder's `currentEmbedded`. Searches for currentEmbedded and graphics-state extraction found no exact issue. |
| Saved text field regenerates only first widget AP | [#249](https://github.com/yuroyami/KitePDF/issues/249), field model keeps first widget; [#358](https://github.com/yuroyami/KitePDF/issues/358), live form values missing in layouts | #249 corrected the model to expose all widgets. The new writer still uses the legacy first-widget accessor despite the corrected model. #358 concerns live viewer layers, not exported AP streams. |
| SVG raster image ignores preserveAspectRatio and slice clip | [#97](https://github.com/yuroyami/KitePDF/issues/97), outermost SVG viewBox stretches; [#566](https://github.com/yuroyami/KitePDF/issues/566), one missing outer SVG dimension | Both historical issues concern root `<svg>` intrinsic sizing/viewBox mapping. New repro concerns `<image>` mapping a raster into its own declared viewport. |
| SVG use of symbol omits symbol viewport and style | None found | Queries for SVG+symbol, symbol+use and preserveAspectRatio returned no matching issue. |
| SVG shape's own opacity is applied separately to fill and stroke | [#91](https://github.com/yuroyami/KitePDF/issues/91), container group overlap seams | #91 is about `<g opacity>` and was closed after groups were added. The new repro is one painted shape's fill/stroke overlap outside that container path. |
| SVG solid rgba/transparent fill and stroke lose alpha | [#180](https://github.com/yuroyami/KitePDF/issues/180), gradient stop opacity; [#253](https://github.com/yuroyami/KitePDF/issues/253), EPUB inline background alpha | #180 concerns gradient stops. #253 concerns EPUB background consumers and the shared CSS color model. New direct SVG paint consumer still discards the separately parsed alpha. |
| Valid CP437 ZIP names make an archive disappear | None found | CP437, ZIP+encoding and archive+name found no matching defect; ZIP and filename results concern comic metadata, image loading and other EPUB wiring. |
| KitePDF ignores image gamma/orientation metadata | [ImageKodec #108](https://github.com/yuroyami/ImageKodec/issues/108), apply reported color data; [ImageKodec #16](https://github.com/yuroyami/ImageKodec/issues/16), report PNG color chunks | These are closed upstream codec/API work. A caller failing to request/use that completed upstream behavior is a KitePDF integration defect. EXIF/orientation searches found metadata bounds, TIFF pages and bitmap helpers, not this integration defect. |
| Existing tiles survive changing scripts/form-layer state | [#358](https://github.com/yuroyami/KitePDF/issues/358), live forms missing in layouts; [#375](https://github.com/yuroyami/KitePDF/issues/375), whole-page zoom cache | #358 concerns initial form-layer wiring and stale base widgets; new trigger changes scripts/form-layer configuration after tiles exist and retains the old bitmap entries. #375 requested tiles and buckets, not invalidation of the implemented tiles. |
| `userScrollEnabled=false` still allows zoomed edge page turn | [#594](https://github.com/yuroyami/KitePDF/issues/594), selected text disables wheel | #594 concerns re-enabling mouse wheel with a selection. New trigger explicitly disables paging but zoomed pan's PageTurn path ignores that setting. |
| `commitFocusedField` returns before a suspended queued commit | [#365](https://github.com/yuroyami/KitePDF/issues/365), script effects/lifecycle and absent commit API | #365 asked for this public API and completion waiting. The new implementation has an empty dispatcher hop instead of awaiting completion of a prior suspended task. This is an incomplete implementation of a historical requirement, not the old missing-API symptom. |
| Outline onNavigate callback disposes the scope before animated navigation | None found | No onNavigate issue found. COMPOSE-04 was subsequently reproduced with the current real paged viewer and a panel-disposal callback. |

Additional PDF searches for setInfo, Latin-1/WinAnsi standard-font creation, signature+Fields, setChoiceValue and export-value labels found no exact matches. The embedded-CFF StandardEncoding issue #469 is a different font-reader path. The standard-font Latin text limitation is already documented in docs/writing.md even though no matching tracker issue was found.

Suggested report wording is "related historical issue: #N, distinct trigger" or "the API was introduced after #365, but its completion contract still fails." Do not label any of the new findings as an existing open issue solely because these historical keywords match.
