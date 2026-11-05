# GPT audit of KitePDF

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... GPT <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

## Not yet filed

### C-01 [P1] Password form values are published as plaintext accessibility descriptions

Status: proven, and held back with the other security findings until they are reported.

**Locations:** `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageSemantics.kt:101-120,228-231`; compare `KiteFormInput.kt:83-105,239` in the same directory.

**Trigger:** A PDF text widget has the Password field flag (`/Ff 8192`), `KiteDocView` has a `scripts` handler, and its `formState` contains an entered value. Either rendering mode and any page layout are affected once the page builds its accessibility nodes.

**Evidence and impact:** `accessNodes` reads `formState.value(fieldName)` for every non-choice field and stores it unmodified in `PageAccessNode.state`. `describe(TEXT_FIELD)` copies that string to Compose `stateDescription`. There is no password check or password semantics on this separate page node. Thus a masked password is available as plaintext in the semantics tree, and can be exposed by accessibility readout/inspection even when the actual editing field is unfocused. The input path explicitly recognizes the same flag and applies `PasswordVisualTransformation`, so masking that input does not fix the second node.

**Verification:** Source-proven. Minimal regression: extend `PageSemanticsSceneTest` with a `/FT /Tx /Ff 8192 /T (password)` widget, set its form value to a unique secret, render each mode, and assert no node's text, state description or content description contains that secret, with and without keyboard focus. Also check that the node exposes appropriate password semantics.

**Remediation:** Carry a password attribute into `PageAccessNode`, omit/mask its value, and apply password semantics. Audit reading-order/appearance-derived nodes for duplicate disclosure too.

<a id="C-02"></a>

### JS-03 [P1] The document-wide script budget does not count the script currently running

Status: proven, and held back with the other security findings until they are reported.

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

### JS-08 [P2] An erroring interval causes unbounded diagnostic retention and quadratic copying

Status: proven, and held back with the other security findings until they are reported.

**Owner:** KitePDF, `kitepdf-javascript`. **Status:** source-proven algorithmic behavior; no long-duration RSS benchmark performed.

**Locations:** `PdfScriptRunner.kt:165-176`, `:659-663`, `:701-705`; `PdfScriptPolicy.kt:23-28`.

**Trigger:** A document starts `app.setInterval("throw new Error('tick')", 0)`. The viewer pumps the timer repeatedly. Each failure is caught, so the interval stays active and the document continues. The default document budget is unlimited, and its per-event time limit does not stop a short throw.

**Cause:** Every failure is permanently appended to `failureList`; `failureCopy = failureList.toList()` copies all prior entries after every failure. N failures retain N throwable/cause/stack chains and copy N(N+1)/2 references over time. JavaScript's 512 MiB engine allocation limit does not cover these Kotlin allocations.

**Impact:** An accidentally broken recurring callback can make a long reader session consume increasing memory and GC time. At 60 failures per second for one hour, the code would copy approximately 23.3 billion references in aggregate; this is an arithmetic extrapolation, not a measured memory result. Retained state also grows without bound.

**Remediation:** Bound retained diagnostics, keep a total/dropped count, optionally deduplicate repeated failures, and publish snapshots without copying the entire history on every event. Expose a callback or bounded stream for applications that need durable logging. Verify after many failure cycles that retained count and per-event allocation stay bounded.

<a id="MEDIA-01"></a>

### PDF-04 [P1] Redaction removes glyphs but retains their `/ActualText` replacement

Status: proven, and held back with the other security findings until they are reported.

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

Status: proven, and held back with the other security findings until they are reported.

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:883-892`, `:1084-1094`; `writer/PdfEditorRedaction.kt:353-374`.

Redaction copies inherited resources onto the leaf page and removes the image's name there. The ancestor `/Pages` dictionary is not changed. That ancestor is reachable from the catalog/page tree and still references the original image; the rewrite's reference-based collection therefore retains it. Merely shadowing the inherited name on the leaf does not remove the ancestor's reference.

**Reproduction:** One-page PDF whose `/Resources << /XObject << /Im0 6 0 R >> >>` is on the `/Pages` node, with no leaf `/Resources`. Draw `/Im0` into `[100,100,200,200]`, redact `[90,90,210,210]`, then `saveRewritten()`. The image uses 15 grayscale samples `SECRET_IMAGE_123`, which makes survival easy to check without a decoder. Actual: the `Do` is removed from the rewritten page, but a reachable image stream still contains all 15 original samples. No other page draws the image.

**Impact:** Visually removed scanned/image content remains recoverable despite the rewrite and advertised resource pruning. This also wastes space in documents where all uses of an inherited image were removed.

**Remediation:** Before collecting rewritten objects, normalize page-resource ownership or prune ancestor resources against actual descendant needs. Keep shared images only when a surviving use requires them. Test inherited resources on multiple ancestor levels and combinations of redacted/unredacted children.

**Status:** Reproduced with current PDF sources; reopened output and inspected all reachable decoded streams. Fixture: `redacted-inherited-image.pdf`.

<a id="PDF-06"></a>

### PDF-07 [P1] Xref-stream negative widths bypass decoded-data limits

Status: proven, and held back with the other security findings until they are reported.

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/XrefParser.kt:133-148`, `:153-175`, `:181-186`.

`/W` values are narrowed to `Int` without validating nonnegative bounded widths. Only a zero sum is rejected. With `/W [0 0 -1]`, `rowSize=-1`; `readField(...,-1)` runs no iterations and returns zero, the cursor becomes progressively negative, and `cursor+rowSize > decoded.size` never rejects an empty stream. A declared `/Index [0 N]` therefore creates N `InUse` entries from no data. Work and memory are controlled by a count, not by available rows. Validate widths, arithmetic and `/Index` pairs before walking rows, and bound counts by the decoded length. **Status: source-established; no exhaustion run performed.**

<a id="PDF-08"></a>

### PDF-08 [P1] Object-stream counts allocate before being checked against available headers

Status: proven, and held back with the other security findings until they are reported.

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:913-924`; recovery duplicates the pattern in `parser/PdfRepair.kt:142-145`.

`/N` is narrowed to `Int` and used as `IntArray(n)` before a single header pair is validated; repair constructs `ArrayList(n)` at the same stage. A tiny otherwise parseable object stream can request a huge array independent of decoded stream length. Read `/N` as a checked value and require a feasible number of header pairs within `/First`/decoded bytes before allocating. Avoid treating catch-and-repair as allocation validation, especially on Wasm/Native. **Status: source-established; no exhaustion run performed.**

<a id="PDF-09"></a>

### PDF-09 [P1] Signature trust accepts a non-CA certificate as an issuer

Status: proven, and held back with the other security findings until they are reported.

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/SignatureValidator.kt:202-219`; `crypto/sig/X509.kt:32`, `:72-74` parses `isCa` but the chain walk does not consult it.

`chainOf` accepts an issuer whenever its subject matches and its key verifies the next certificate; `result` reports `isTrusted=true` when any certificate in that chain is a supplied anchor. Thus a CA-issued end-entity certificate (`basicConstraints CA:FALSE`) can issue an arbitrary further signer certificate, and a mathematically valid PDF signature from that signer gets a chain to the trusted CA. RFC 5280 path validation requires CA authorization for intermediate issuers; signature verification alone does not supply it. Key usage, path-length constraints and unrecognized critical extensions are also not validated here. This is separate from the explicitly documented omission of certificate-date checks and the separately reported revocation status; those documented omissions are not findings in this audit.

**Remediation:** Enforce certificate path constraints before exposing trust, or expose the result as signature-linkage information until actual trust-path validation is available. Test a genuine CA chain against the same chain with a CA:FALSE intermediate. **Status: source-established; certificate experiment not run.**

### RENDER-06 [P1] Tiling work-limit arithmetic overflows before conversion to Long

Status: proven, and held back with the other security findings until they are reported.

**Owner:** KitePDF PDF renderer. **Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1856-1864,1896-1898`. Each index span is computed as `Int` before conversion to `Long`. A bounded arithmetic check with `i0 = -2147483648` and `i1 = 2147483646` gives `span=-1`; two such spans multiply to `tiles=1`, although each mathematical span is 4,294,967,295. The maximum-tile guard therefore accepts a count that is not the size of the subsequent nested ranges. Finite extreme bounds and small steps can reach saturated endpoints through `Double.toInt()`.

**Impact:** The intended page-work cap is unreliable, and this loop checks no cancellation at its iteration boundary. The operation budget inside `dispatch` can suppress drawing without terminating enumeration. No oversized render or exhaustion experiment was run; the finding is established by integer arithmetic and the loop's source, not a measured hang.

**Remediation:** Widen before subtraction, reject invalid/saturated ranges, and compare multiplication against the budget without overflow. Break enumeration directly on cancellation or work exhaustion. Add bounded tests of the count calculation at signed-Int limits and ensure both positive and negative pattern-step fixes preserve the guard.

### FMT-03 [P1] SVG reference depth does not bound exponentially repeated drawing work

Status: proven, and held back with the other security findings until they are reported.

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

Status: proven, and held back with the other security findings until they are reported.

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

## Could not be settled here

Claims that need a device, a Mac, Windows or a release build to prove or refute. Each says what would settle it.

| Where | Claim | What would settle it |
| --- | --- | --- |
| 597, item 3 | Read-aloud teardown clears the narration mark on an unowned scope and a per-instance mutex, which may race a new reading | Not reproduced: needs a scene test with a narrated EPUB that recreates KiteReadAloud while a clear is pending. Impact is bounded: show() reapplies the mark for every clip (applyMark per clip), so a lost mark lasts at most one clip. |
| 597, item 4 | A failure inside read() leaves a completed reading job, so play() never starts again | No library call on that path was found to throw: locate() catches, clipsOf/mediaOverlayOf and bookItem are lenient, clip playback catches Exception. Only a host callback (onClip, onFinished) or an unexpected layout exception in markNarration would reach it, which also crashes the composition scope. Needs a reproduction with a throwing chapter before filing. |
| 281, item 2 | Page hit geometry during pager motion is the centred letterbox, not where the moving page is | Needs a scene test (SceneTestDriver with QueuedEffects) that taps, long-presses or starts a selection while the pager animates, to see whether a gesture reaches the hit test mid-motion and lands on the wrong spot. GPT also left it unpromoted. |

## Refuted

Claims that the code, a probe or a reference reader contradicted. They were not filed; a "(part)" finding was filed for the rest of its claims.

| Id | Why it was not filed |
| --- | --- |
| line 902, item 1 | Canvas2D gradient created before or after the drawing CTM: GPT itself rejected it: Chromium 151 gave identical pixels either way. Nothing to file. |
| line 1112, item 3 | SVG and CBZ do not balance beginPage and endPage when a render throws: No observable consequence: SvgImage pops its own clips in finally before the exception leaves, and endPage on every canvas only cleans leftovers. The exception itself escaping renderTo is #737; a lost layer inside a raster step on KiteRasterCanvas is #765. |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 1367, item 1 | One canonical render identity for page, tile, thumbnail and semantic data | No deliverable beyond the filed defects. |
| 1367, item 2 | A shared graphics/text-state model for renderer, extractor, writer and redaction | No deliverable beyond the filed defects; the audit itself warns against a wholesale rewrite. |
| 1367, item 3 | End-to-end resource ownership and bounded diagnostics | Covered by concrete issues. |
| 1367, item 6 | DjVu and CHM handlers (and a generic 'more ebook readers') | GPT and SOL ask for demand to decide; no audit names a DjVu or CHM scope. |
