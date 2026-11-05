# SOL audit of KitePDF, ImageKodec and KiteJS

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... SOL <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

## Not yet filed

### PDF-01 [P1] True redaction retains the removed text in `/ActualText`

Status: proven, and held back with the other security findings until they are reported.

**Evidence:** `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:307` preserves every `BDC` operation unchanged; `:531` replaces the corresponding visible `Tj` only. `text/TextExtractor.kt:109` then extracts `/ActualText` whether the visible show string survived or not.

**Trigger:** A normal accessibility sequence `/Span << /ActualText (SECRET-ACTUALTEXT) >> BDC BT /F1 24 Tf 72 700 Td (SECRET-VISIBLE) Tj ET EMC`, then `redactRegion(page, KiteRectangle(60.0, 680.0, 450.0, 735.0))` followed by **`saveRewritten()`**.

**Expected/actual:** The secret should be absent from the rewritten content. The visible string is removed, but the rewritten page content still contains `/ActualText (SECRET-ACTUALTEXT)` and KitePDF's own `extractText()` returns `SECRET-ACTUALTEXT`. This is recoverable confidential content despite the documented true-redaction guarantee. The output displays the black box; visual checks alone will miss it. MuPDF reports the replacement has no position when extracting, which does not remove the retained bytes.

**Fix direction:** Associate marked-content replacement properties with the content they describe and scrub/remove the affected `/ActualText`; handle both inline dictionaries and named `/Properties` without damaging unrelated uses. Add an extraction and decoded-stream check after a full rewrite, including nested and partially touched sequences. Also assess structure-tree replacement text separately; this repro establishes the inline `BDC` leak only.

**Artifact:** `results/actualtext-before.pdf`, `results/actualtext-after.pdf`; `repro.log` records retained text and the decoded stream.

<a id="pdf-02"></a>

### PDF-02 [P2] Redacting a shared Form XObject modifies other pages

Status: proven, and held back with the other security findings until they are reported.

**Evidence:** `writer/PdfEditorRedaction.kt:156` determines whether a form must remain pristine from only the current parent's invocation list; `:170` considers the original claimable; `:238` chooses the original form reference for the rewrite.

**Trigger:** Two pages each draw the same Form XObject once; its text is `SHARED-STAMP` at `(72,700)`. Redact the text region on **page 0 only** and call `saveRewritten()`.

**Expected/actual:** Page 1 should retain its stamp. Before editing its extracted text is `SHARED-STAMP`; after editing it is empty. MuPDF independently shows both pages blank except for the redaction box on page 0. There is no untouched invocation in page 0's local hit list, so the global form object is rewritten in place even though page 1 still uses it.

**Fix direction:** Use copy-on-write for page-local form redactions unless exclusive ownership across the document is established, and prune only the target page's unused resource entries. Extend shared-form tests across pages, resource aliases and different parent forms, not only repeated `Do` operations within one stream.

**Artifact:** `results/shared-form-before.pdf`, `results/shared-form-after.pdf`; `repro.log` and `oracle.log`.

<a id="pdf-03"></a>

### SCRIPT-01 [P2] The document-wide script budget never accounts for the event currently running

Status: proven, and held back with the other security findings until they are reported.

**Owner:** KitePDF. **Evidence:** executed current runner plus actual pinned QuickJS.

**Source:** `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:693-707` increments `documentSpent` only after evaluation returns. The interrupt callback at `:718-728` compares only this completed-event total, without adding `now - eventStartedAt`. `PdfScriptPolicy.kt:22-31` permits a document-wide budget with per-event and instruction limits disabled. In addition, `onStillRunning` approval resets `eventStartedAt`, causing previously approved time slices to disappear from the final cumulative accounting.

**Valid trigger:** Create `PdfScriptPolicy(budgetMillis = 0, documentBudgetMillis = 10, instructionBudget = 0)` and run a long, finite PDF JavaScript action. The host can legitimately want a single shared allowance across multiple events, instead of a separate cap for each event. A first event must be stopped once it has consumed the document allowance.

**Executed reproduction:** Use an `AtomicLong` clock returning `getAndIncrement()` and run `var sum=0; for(var i=0;i<10000000;i++) sum+=i; 'completed';`. The engine's real interrupt callbacks advanced that deterministic clock to **78,130 ms**, yet the result was `completed` and `failures=[]`, despite the declared 10 ms document budget. These are synthetic budget-clock readings, not measured wall-clock latency. With completed time initially zero, a single non-returning event has no document-budget stop at all. No malformed PDF or script syntax is involved.

**Expected:** interrupt when completed script time plus current event time exceeds the cumulative allowance. **Fix direction:** separate the current event's absolute start, the next still-running prompt deadline and the accumulated document time. Prompt approval can extend an event without erasing its accumulated cost. Test first-event exhaustion, many short events, approved extensions and the combinations of zero/nonzero instruction/event/document budgets.

<a id="script-02"></a>

### KJS-01 [P2] Reclaiming a batch of Kotlin handles allocates quadratic garbage in the next QuickJS call

Status: proven, and held back with the other security findings until they are reported.

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

## Refuted

Claims that the code, a probe or a reference reader contradicted. They were not filed; a "(part)" finding was filed for the rest of its claims.

| Id | Why it was not filed |
| --- | --- |
| line 699, item 2 | Per-intent ICC/image memo arrays and line-art memo fields are unsynchronized: Same claim as FABLE PDF-RENDER-25, refuted by P07: a lost race builds an equal immutable variant twice; OPUS also lists these as documented benign races (CharText, IccProfile.renderings, KiteImageData.renderings, KiteImageSamples.failed). |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 1039, item 1 | A shared staged-object resolver and copy-on-write resource model | No deliverable beyond the filed defects. |
| 1039, item 2 | One full-suspend script queue and authoritative render/layout revisions | No deliverable beyond the filed defects. |
| 1039, item 7 | DjVu and CHM handlers (and a generic 'more ebook readers') | GPT and SOL ask for demand to decide; no audit names a DjVu or CHM scope. |
