# FABLE audit of KitePDF, KiteJS and ImageKodec

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... FABLE <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

## Not yet filed

### VIEWER-RUNTIME-03: A document script that throws an Error ends the host app, because the script guard catches Exception and the lanes have no handler

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-compose-viewer (ScriptCall, KiteDocViewState script lanes)
- Kind: concurrency
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ScriptCall.kt:13-19, :25-27; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:660-667, :713-720, :780, :862, :894, :924-938; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:345-364; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteFormLayer.kt:230-245; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageRasterizer.kt:280-284
- What happens: `scriptCall` is the one guard around every call into a `PdfScriptHandler` or `EpubScriptHandler`, and it catches `Exception` (ScriptCall.kt:15). The raster guard next to it catches `Throwable` on purpose, with the note that an `OutOfMemoryError` "once let through to kill the app it was written to protect (#219)" (KitePageRasterizer.kt:280-284). A document's script runs in the document's hands: a page open action that grows an array or a string until the heap is gone throws `OutOfMemoryError` out of KiteJS, through `scriptCall`, and into whatever runs the call. Several of those calls run in scopes with no parent and no handler: `CoroutineScope(lane).launch { work() }` for a blur, a choice commit or a page close (KiteDocViewState.kt:661, 714, 780, 862, 894) and `CoroutineScope(Dispatchers.Unconfined).launch` in `runInPlace` (ScriptCall.kt:26). There the error reaches the thread's uncaught handler, which on Android kills the process; in `LaunchedEffect`s and `tapScope.launch` (KiteDocView.kt:345, 419; KiteFormLayer.kt:230) it reaches the composition's scope, which ends the composition the same way. KiteJS bounds the call depth itself ("Maximum call stack size exceeded"), so a `StackOverflowError` is unlikely, but it bounds no allocation.
- What should happen: a document's script, like a torn page, never ends the host app: the guard catches `Throwable`, lets `CancellationException` through as it does now, and the parentless launches carry a `CoroutineExceptionHandler` or use `SupervisorJob` scopes so a failed call is logged and the next call still runs (#365 asked for that).
- How to reproduce or evidence: a PDF whose `/OpenAction` is `/S /JavaScript /JS (var a = []; while (true) a.push(a.length))`, opened in `KiteDocView(scripts = PdfScriptRunner(...))` on Android: `documentOpened` runs on the script lane inside `withContext(scriptLane)` (KiteDocView.kt:352-358); the `OutOfMemoryError` passes `scriptCall` at ScriptCall.kt:15 (it is not an `Exception`) and the `LaunchedEffect` fails the composition.
- Cross-effects: `PdfScriptRunner` runs calls on a thread of its own (kitepdf-javascript PdfScriptRunner.kt:84, :107) and marshals the result back, so where the error surfaces depends on that marshalling; the `timerWake` pump (KiteFormLayer.kt:128-157) runs the same guard on every frame while a timer waits.
- Fix sketch: change `catch (failure: Exception)` to `catch (failure: Throwable)` in `scriptCall`, keep the `CancellationException` rethrow, and give the four parentless launch sites one shared `CoroutineScope(SupervisorJob() + lane + CoroutineExceptionHandler { ... kiteWarn ... })` held by the state.

### PDF-PARSE-08: `saveRewritten` on an encrypted document silently drops `/Encrypt` and writes plaintext

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/writer/PdfEditor (adjacent to the crypto lane)
- Kind: security
- Severity: low
- Confidence: likely (read both rewrite paths; did not confirm intent against an issue)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:1122-1148 (`writeWithClassicXref`) and :1156-1211 (`writeWithObjectStreams`): neither applies `encryptor`, and neither emits `/Encrypt` in the new trailer; contrast :1014/:1025 (`saveIncremental` applies `encryptor?.encryptIndirect`)
- What happens: `saveRewritten(useObjectStreams = …)` rebuilds the file from resolved (already-decrypted) objects and writes them verbatim with a fresh trailer that carries only `/Root`, `/Info`, `/ID` (no `/Encrypt`), and never calls the `encryptor`. So a full rewrite of an encrypted-and-authenticated document produces a fully decrypted PDF with no warning. `saveIncremental` by contrast re-encrypts staged objects and keeps `/Encrypt`.
- What should happen: Either re-encrypt on full rewrite (apply `encryptor` and re-emit `/Encrypt`), or document explicitly that `saveRewritten` decrypts, and ideally require an opt-in so a caller does not leak a protected document unintentionally.
- How to reproduce or evidence: `doc.edit().saveRewritten()` on an AES document opened with its password yields bytes with no `/Encrypt` that any reader opens without a password.
- Cross-effects: With PDF-PARSE-02, `saveRewritten(useObjectStreams = true)` of an encrypted base is doubly wrong; here the simpler issue is the confidentiality downgrade.
- Fix sketch: Apply `encryptor?.encryptIndirect` in both rewrite paths and re-emit the `/Encrypt` reference, matching `saveIncremental`; or gate decryption behind an explicit flag.

### PDF-RENDER-01: An Indexed colour space allocates a palette table of hival plus one entries with no upper bound

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-core, `core.render.KiteColorSpace`
- Kind: memory
- Severity: high
- Confidence: verified (the orchestrator reproduced it: NegativeArraySizeException out of renderTo)
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:146-155`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:451-457`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/DefaultColorSpaces.kt:85`
- What happens: `resolveIndexed` reads `hival` as `it.value.toInt()` with no clamp, and the lazy `lut` builds `Array(hival.coerceAtLeast(0) + 1) { ... }`. A file with `[/Indexed /DeviceRGB 100000000 <...>]` allocates an array of 100 million references (400 MB with compressed oops, 800 MB without) the first time the space is used, for a palette whose entries past the lookup string are all `RgbColor.BLACK`. With `hival` of `2147483647`, `hival + 1` overflows to `Int.MIN_VALUE` and `Array(negative)` throws `NegativeArraySizeException` out of `cs`, `sc`, `scn`, `defaultColor()` or an image decode, which `dispatch` does not catch, so the page render fails instead of salvaging. `DefaultColorSpaces.imageSpace` copies the same `hival` into a new `Indexed`. The ByteArray palette itself is bounded by the file, but the table is not.
- What should happen: ISO 32000-1, 8.6.6.3 limits `hival` to the range 0 to 255 (the lookup table holds `hival + 1` entries of `base.componentCount` bytes). Clamp `hival` to 255, or to `palette.size / comp - 1`, before sizing the table, and keep the lenient rule that an out-of-range index clamps.
- How to reproduce or evidence: a page with `/Resources << /ColorSpace << /CS0 [/Indexed /DeviceRGB 2147483647 <FF0000>] >> >>` and content `/CS0 cs 0 sc 0 0 100 100 re f` throws from `KiteColorSpace.Indexed.defaultColor()` at the `cs` operator. With `100000000` instead, the same page allocates 400 MB. `KiteColorSpace.kt:150`: `Array(hival.coerceAtLeast(0) + 1)`.
- Cross-effects: `ImageRaster.unpackIndexed` and `unpackFromTable` go through `colorAt`, so an Indexed image triggers the same allocation; on Android (192 MB heap) a far smaller `hival` is enough to OOM the viewer. Breaks the "never break lenient salvage" rule of CONTRIBUTING.md.
- Fix sketch: `val hival = ...coerceIn(0, 255)` in `resolveIndexed` and in `DefaultColorSpaces.imageSpace`; size the LUT as `minOf(hival, palette.size / comp - 1) + 1`.

### PDF-RENDER-06: A page's whole content stream is parsed into a list of operations before the first one is drawn, at about 100 bytes per operation

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf `content.ContentStreamParser`, `render.PageRenderer`, `PdfDocument` operation cache
- Kind: memory
- Severity: medium
- Confidence: verified
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/content/ContentStream.kt:68`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/content/ContentStream.kt:117-181`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:332-339`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPage.kt:235-239`
- What happens: `ContentStreamParser.parse` builds `List<Operation>` for the whole stream (`Operation` plus `operandStack.toList()` plus boxed `PdfReal`/`PdfInt` per operand), capped at 5 million operations, and `render` iterates it afterwards. The parser's own estimate is 104 bytes per operation (`BYTES_PER_OPERATION`). A 50 MB content stream of short path operators (`x y m x y l S`, about 20 bytes per operator) is about 2.5 million operations, so about 260 MB of objects before a pixel is drawn; the 5 million cap allows about 520 MB. Such streams are what large CAD plots, GIS maps and scanned-vector drawings contain. On Android this is an OOM; on the JVM it is a GC storm. The document cache then keeps the list (`PdfPage.operations` through `document.operations`), subject to its own budget.
- What should happen: a content stream interpreter runs one operator at a time with O(1) state, as `pdf_process_stream` in MuPDF does, so memory does not grow with the stream length; the cache can still keep parsed operations for small streams that are replayed (forms, glyphs).
- How to reproduce or evidence: generate a page whose content is `i 0 m i 1 l S\n` for i in 0 until 2_500_000 (about 40 MB) and render it with `-Xmx256m`: the parse fails with OutOfMemoryError before `beginPage`. `ContentStream.kt:154`: `ops.add(Operation(tok.value, operandStack.toList()))`.
- Cross-effects: `PdfDocument.operations` retains the list for the next render (budgeted by `retainedBytes`); `MAX_DISPATCHED_OPS` (20 million) bounds time but not memory; the EPUB and SVG engines do not go through this parser.
- Fix sketch: give `ContentStreamParser` a streaming form (`parse(bytes) { op -> dispatch(op) }`) that `PageRenderer.render` uses for page content above a size threshold, and keep the list form for the cache of small nested streams.

### FONTS-TEXT-08: Table counts read from the font file size allocations before any bound: a cmap format 12 with a huge `numGroups`, a CFF2 INDEX count, or a GPOS class matrix can demand gigabytes from a few bytes

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-core/font (TtfCMap, CffFont, OpenTypeKern, OpenTypeMarks)
- Kind: memory
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TtfCMap.kt:195-211` and `219-234`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:465-470`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/OpenTypeKern.kt:144-151` (orchestrator corrected the line numbers), `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/OpenTypeMarks.kt:179-193`
- What happens: `parseFormat12` and `parseFormat13` read `numGroups` as a signed 32-bit value and allocate three `IntArray(numGroups)` (12 bytes per group) before reading a single group, and skip the table's own `length` field. A 20-byte subtable claiming `numGroups = 100_000_000` asks for 1.2 GB; on a 192 MB Android heap this is an `OutOfMemoryError` caught by the font-level `runCatching`, but the allocation attempt alone evicts caches and on Kotlin/Native a failed allocation of this size can end the process. `readIndex(cff2 = true)` does the same with a 32-bit INDEX count (`IntArray(count + 1)`), `parsePairPos` format 2 allocates `IntArray(class1Count * class2Count)` (up to 65535 squared, or a negative size after overflow), and both `readCoverage`/`parseClassDef` of `OpenTypeMarks` and `OpenTypeKern` expand every coverage range into a `HashMap` entry per glyph: a format 2 coverage with 65535 ranges of `0..65535` is four billion `put` calls, a hang rather than an allocation. These bytes come straight from the embedded font (PDF) or the book's font file (EPUB).
- What should happen: every count is bounded by the bytes that remain in its table before allocation (`numGroups * 12 <= length - 16`; INDEX `count * offSize <= remaining`; `class1Count * class2Count * recSize <= remaining`), and coverage ranges are kept as sorted arrays as `OpenTypeGsub.Coverage` already does.
- How to reproduce or evidence: a TrueType font whose cmap has one `(3,10)` record pointing at a format 12 header with `numGroups = 0x7FFFFFFF`; `TrueTypeFont.parse` tries to allocate 24 GB inside `TtfCMap.parse` before the `EOF` check can run.
- Cross-effects: `TrueTypeFont.parse` is the entry for PDF `/FontFile2`, EPUB `@font-face`, XPS and the writer, so one guard covers four modules.
- Fix sketch: clamp each count by the remaining table bytes and treat an excess as a malformed subtable (fall through to the next cmap record, drop the lookup); replace the HashMap expansion with the range arrays of `OpenTypeGsub.Coverage`.

### FONTS-TEXT-09: GSUB nested lookups have a depth limit but no operation budget, so a self-referencing context lookup fans out exponentially

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-core/font (OpenTypeGsub)
- Kind: security
- Severity: medium
- Confidence: verified (read the whole path)
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/OpenTypeGsub.kt:450-496`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/OpenTypeGsub.kt:637`
- What happens: `applyRecords` guards nesting with `MAX_NESTING = 64`, HarfBuzz's `HB_MAX_NESTING_LEVEL`, but HarfBuzz also charges every lookup application against `max_ops` (`HB_MAX_OPS_FACTOR * len`, floor 16384) and stops shaping when it runs out. Here a context subtable (type 5 or 6) whose `records` name its own lookup index matches again at the same position (no glyph changed, so `delta == 0 -> continue` does not stop the recursion), and each of its k records recurses, giving k^64 applications before the depth limit returns. With k = 2 that is 2^64 steps: a font in an EPUB hangs the layout thread for good. The outer loop's `end` arithmetic also lets a multiple substitution grow the glyph list without a cap (HarfBuzz caps the buffer at `HB_BUFFER_MAX_LEN_FACTOR * len`).
- What should happen: an operation counter per `substitute` call, scaled by the run length, after which lookups stop applying, plus a cap on glyph growth; both are what HarfBuzz does and what `GsubOracleTest` compares against.
- How to reproduce or evidence: build a GSUB with one chained-context lookup (format 3, one input coverage holding glyph 5, two `SequenceLookupRecord`s with `sequenceIndex 0, lookupIndex 0`) under `calt` of `DFLT`; shape a run of ten glyphs 5 through `OpenTypeGsub.substitute`.
- Cross-effects: the Indic, Khmer, Myanmar and USE shapers all run through `Applier`, and EPUB layout runs on the main or layout thread of the viewer.
- Fix sketch: add `var ops` to `Applier` initialised to `max(glyphs.size * 64, 16384)`, decrement in `applyAt`/`applyNested` and return -1 when exhausted; stop `applyMultiple` when `glyphs.size` exceeds `64 * initialSize`.

### PDF-WRITE-01: Redaction keeps the `/ActualText` of a marked-content sequence whose text it removed, and KitePDF's own extractor reads it back

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/writer (RedactionEngine, PdfEditor.redactRegions)
- Kind: security
- Severity: high
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:303-307, kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:843-860, docs/editing.md:240-270
- What happens: The engine replays only the operators it knows (text, paths, `Do`, `BI`, `sh`) and passes every other operator through with its operands intact (`else -> out.add(op)`). `BDC` is one of them. A page that wraps a text run in `/Span << /ActualText (TOPSECRETACTUAL) >> BDC ... EMC` (the way OCR layers, Acrobat and many tagged-PDF producers write replacement text, ISO 32000-1, 14.9.4) has its `Tj` replaced by a `TJ` advance, while the property list with the clear text stays in the rewritten content stream. The same holds for `/Alt` and `/E` property lists and for a `/Span /MC0 BDC` whose dictionary sits in `/Resources /Properties`, which the prune never touches. KitePDF's own `extractText` honours `/ActualText`, so after `redactRegions` plus `saveRewritten` the document still extracts the redacted words. The docs' list of what redaction does not remove (editing.md, "Redaction does not remove the content below") does not mention it.
- What should happen: Redaction removes or empties the `/ActualText`, `/Alt` and `/E` entries of every marked-content property list whose sequence lost a run, both inline and in `/Resources /Properties`, or drops the `BDC`/`EMC` pair when the whole sequence is gone (ISO 32000-1, 14.9.4 says the replacement text stands in for the content it wraps, so it is that content).
- How to reproduce or evidence: `base_actualtext.pdf` has the content above with "Visible words" at 72,700 and a footer at 72,100. `editor.redactRegions(page0, [KiteRectangle(60, 680, 400, 740)])` then `saveRewritten()`. The rewritten content stream reads `q /Span << /ActualText (TOPSECRETACTUAL) >> BDC BT /F1 24 Tf 72 700 Td [-5890] TJ ET EMC ... Q q 0 g 60 680 340 60 re f Q`. `PdfDocument.open(out).pages[0].extractText()` returns `TOPSECRETACTUAL|Footer stays`. pdf.js, pypdf and PDFium return only `Footer stays` because they do not read `/ActualText` for plain text extraction, so a test that uses one of them as the oracle passes.
- Cross-effects: `editPageContent`, `stampPage` and the form recursion all use the same engine output. `RedrawnTextTest` and `TextExtractorScopesTest` show the extractor reads `/ActualText`, so the two halves of the library disagree about what the page says.
- Fix sketch: In `RedactionEngine.run`, track the open `BDC` nesting; when a run inside a sequence is dropped, rewrite that `BDC`'s operands without `/ActualText`, `/Alt` and `/E` (resolving a named property through the resources and staging a copy), and add the case to docs/editing.md until it is done.

### PDF-WRITE-02: Redacting a region on one page rewrites a form XObject in place, so every other page that draws the same form loses that content too

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditorRedaction.recurseIntoForms, redactFormXObject)
- Kind: bug
- Severity: high
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditorRedaction.kt:156-160, 170, 238-239; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:811-817, 919-923; docs/editing.md:245
- What happens: `recurseIntoForms` decides whether a form may be rewritten in place ("claimed") from the hits of ONE parent stream: a form is `pristine` only when some `Do` in that same stream paints into no region. A form that one page draws once, inside the region, is claimed and `updateObject(formRef, ...)` replaces the original object. Every other page whose `/Resources /XObject` names the same object now draws the redacted copy. A letterhead, a stamp or a scanned page stored as a form and reused across pages loses its content on pages the caller never redacted, with no warning. The KDoc and docs/editing.md promise the opposite ("A place that no region meets keeps the original form"). The same per-parent logic also lets a nested descent (page draws F directly outside the region, and G, which draws F inside it) claim F for G and change the page's own direct draw.
- What should happen: A rewrite in place is only safe when no other invocation anywhere in the document still needs the original. Either always clone (the original stays, the GC drops it when nothing names it after all pages were redacted) or count invocations across the whole document before claiming.
- How to reproduce or evidence: `base_sharedform.pdf`: two pages, both `/Fx Do` (form draws "SHAREDFORMTEXT" at 72,700) plus their own footer. `redactRegions(pages[0], [KiteRectangle(60, 680, 400, 740)])` then `saveRewritten()`. Reopened: page 1 extracts `PageOneFooter` (right), page 2 extracts `PageTwoFooter` only; "SHAREDFORMTEXT" is gone from page 2. `RedactionFormCloneTest` only ever builds one-page fixtures (`RawPdf.page`), so no test sees it.
- Cross-effects: `claimedForms` and `redactedFormCache` are cleared per call (`beginRedactionCall`), so redacting page 2 afterwards composes on the already-redacted stream and cannot restore anything.
- Fix sketch: Drop the in-place claim: always allocate a fresh object for a rewritten form and repoint the `Do`; the reachability GC of `saveRewritten` removes the original when no page uses it any more. If the optimisation matters, build the pristine set from every page's content before the first rewrite.

### PDF-WRITE-03: Signature trust accepts a chain through an end-entity certificate, because the chain builder never checks BasicConstraints or KeyUsage

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/crypto/sig (SignatureValidator.chainOf, PdfSignature.validate)
- Kind: security
- Severity: high
- Confidence: verified (read the whole path; `isCa` is parsed and never read)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/SignatureValidator.kt:202-213, 215-219; kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/X509.kt:32, 67; kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfSignature.kt:152-153
- What happens: `chainOf` walks upwards by name match plus a valid signature and `result` sets `isTrusted` when any certificate of that chain equals a trust anchor byte for byte. Nothing checks that an issuer is a CA (`BasicConstraints cA`, RFC 5280, 4.2.1.9), that it may sign certificates (`KeyUsage keyCertSign`, 4.2.1.3), the path length, or the leaf's `digitalSignature` or `nonRepudiation` usage. `X509.parse` reads `isCa` (line 67) but no caller uses it (grep over `crypto/sig`). So anyone who holds an ordinary end-entity certificate issued under a trusted root can mint a second certificate with their key, sign a PDF with it, embed both, and `validate(trustAnchors = [root])` reports `Valid` and `isTrusted = true`. This is the classic path-validation hole and it is separate from the missing date check of #634.
- What should happen: RFC 5280, 6.1 basic path validation: every intermediate must assert `cA = TRUE` (a v3 certificate without the extension is not a CA), must carry `keyCertSign` when KeyUsage is present, path length constraints must hold, and the signer's certificate should allow digital signatures. `isTrusted` should be false otherwise, with `detail` saying why.
- How to reproduce or evidence: Read `chainOf`: the only predicates are `candidate.subjectDer == last.issuerDer` and `last.signedBy(candidate)`. Build with openssl: root CA, leaf L1 (no CA bit), then `openssl x509 -req -CA L1.pem` to issue L2, sign a PDF with L2, include L1 and the root in the CMS, validate with the root as anchor.
- Cross-effects: `PdfCertificate.revocation` is computed per link with the same chain, so a revocation check against a bogus "issuer" also means nothing. `docs/` describes `isTrusted` as trust in the signer.
- Fix sketch: In `chainOf`, require `candidate.isCa` (and `keyCertSign` when a KeyUsage extension is present; parse it next to `extendedKeyUsages`) for every issuer except when the candidate is the leaf itself; reject a chain that violates `pathLenConstraint`; surface the failure in `detail`.

### PDF-WRITE-04: `graftPage`, `appendPage` and `mergeDocument` copy every object reachable from the page, so a link or a widget drags unrelated pages of the source into the target

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor.graftPage)
- Kind: bug
- Severity: high
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:681-711, 714-722, 1217-1225; docs/editing.md:198
- What happens: `graftPage` removes `/Parent` from the page dictionary and then breadth-first copies everything it references. A page's `/Annots` is copied whole: a Link with `/Dest [5 0 R /Fit]` reaches page 5, page 5's `/Parent` reaches the source's `/Pages` root, whose `/Kids` reach every page, their contents, fonts and images. A widget's `/Parent` reaches the field tree and, through `/Kids` and `/P`, every page again; `/B` article beads and `/Popup` do the same. The result is a target file that silently carries the whole source document (every page's content stream) as objects reachable from the grafted page, so `saveRewritten`'s GC keeps them. A one-page excerpt of a confidential document ships the other pages' text in the file. docs/editing.md says "A copied page brings everything that it uses (fonts, images and content) with it", which is what the caller expects, not this.
- What should happen: Mirror MuPDF's `pdf_graft_mapped_page`: copy the page's own entries, and for annotations drop `/P`, `/Parent` of widgets (or re-home them), `/Popup` and `/Dest`/`/A` targets that point into the source page tree (or rewrite them to the copied page when that page is grafted too). Never copy a `/Pages` node.
- How to reproduce or evidence: `base_graft_source.pdf`: three pages, page 1 has a Link whose `/Dest` is page 3. `target.edit().appendPage(source, 0)` then `saveRewritten()`. `pendingObjectCount` is 11 for one page; the output has 14 objects for its 2 pages, and the streams of objects 12 and 14 contain `SOURCEPAGETHREESECRET` and `SOURCEPAGETWOSECRET`, which were never grafted. `PageOpsTest` fixtures have no annotations, so the suite cannot see it.
- Cross-effects: The same reachability rule is why a page removed with `removePage` is NOT dropped by `saveRewritten` when an outline item, a link on another page or `/OpenAction` still points at it (docs/editing.md:198 claims it is). `mergeDocument` of an N-page form-bearing document copies the field tree N times.
- Fix sketch: In `graftPage`, build the page copy from a filtered dictionary: keep `/Annots` but copy each annotation with `/P` repointed, `/Parent`, `/Popup`, `/IRT` dropped (or re-homed) and a `/Dest`/`/A /D` that names a source page replaced by a named destination or dropped; stop the BFS at any dictionary with `/Type /Pages` or `/Type /Page` other than the grafted one.

### PDF-WRITE-11: Redaction leaves five more copies of the content that docs/editing.md does not list: structure-tree text, the page thumbnail, tiling-pattern cells, Type 3 glyph procedures and private data

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor.redactRegions, RedactionEngine), docs/editing.md
- Kind: docs
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:884-897 (the page dict keeps every other key), kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:221-312 (no `scn`/`SCN` or pattern handling), kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditorRedaction.kt:515-523 (scrub reaches annotations and fields only); docs/editing.md:262-270
- What happens: After `redactRegions` plus `saveRewritten`, these stay in the file: (a) `/StructTreeRoot` elements with `/ActualText`, `/Alt` and `/E` for the removed marked content (the KDoc of `scrub` names the structure tree only as a path that keeps annotations alive); (b) the page's `/Thumb` image, a rendering of the whole page before redaction; (c) a tiling pattern's content stream when the filled path's edges lie outside the region (a page-sized rectangle filled with a text or image pattern keeps its pattern cells, the black box only covers them); (d) the glyph procedures of a Type 3 font, which for a scanned-to-Type3 OCR document are pictures of the words (the show operator goes, the glyph pictures stay); (e) `/PieceInfo` private data, `/Outlines` titles, `/Info` and XMP. The "Redaction does not remove the content below" warning in editing.md lists four cases (soft mask, `/LW`, broken `/Rect`, `/EmbeddedFiles`) and none of these, so a reader of the docs who checks exactly that list ships the file.
- What should happen: Either remove them (drop `/Thumb` from a redacted page; scrub `/ActualText`, `/Alt`, `/E` of structure elements whose `/K` marked content lost a run; recurse into tiling patterns used by a path that survives; keep the list honest) or list them in the warning so the owner's promise matches the code.
- How to reproduce or evidence: `/Thumb`: `redactRegions` builds `newPage` from `pageDict` by `withEntry` and prunes only `/Contents`, `/Resources /XObject` and `/Annots`; a `/Thumb` key is copied verbatim (PdfEditor.kt:884-897). Patterns: the engine's `when` has no case for `scn`, `SCN`, `cs` or `/Pattern`; `loadFormXObjectNames` lists `/XObject` only, never `/Pattern`. Structure tree: `scrub` is called with `droppedAnnots` and `detached` only.
- Cross-effects: #631 (soft mask) and #632 (`/EmbeddedFiles`) are the same class and are already open; this adds the rest of the class in one place.
- Fix sketch: Drop `/Thumb` in `redactRegions`; treat `/Pattern` resources like forms (recurse, clone per invocation with the pattern matrix); walk `/StructTreeRoot` for elements whose `/Pg` is the page and strip the three text keys; extend the editing.md list with whatever is left.

### PDF-WRITE-13: The text-field appearance ignores the `/DA` font and the form's `/DR`, draws password fields in clear, drops the widget's border and background, and draws a list box as one line

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor.setTextFieldValue, setChoiceValue, FieldAppearance.build)
- Kind: design
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:302-312, 422-428; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/FieldAppearance.kt:29-51, 59-94; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/FieldText.kt:110
- What happens: (a) `setTextFieldValue` always allocates a Helvetica font object and binds it to whatever resource name `/DA` names, so a form whose `/DA` says `/TiRo 10 Tf` or names a `/DR` CJK Type0 font gets Helvetica, and a non-Latin value becomes `?` (PdfText.kt:471). The form's `/DR` is never consulted (`acroFormDefaultAppearance` reads only `/DA`). (b) `/Ff` bit 14 (Password, 12.7.4.3) is not checked, so the value is drawn in the appearance in clear. (c) The old `/AP`, which drew the `/MK` border and background, is replaced by a stream that draws neither (`synthesize` does draw them, `build` does not), so a filled field loses its box. (d) `setChoiceValue` builds the same single-line appearance for a list box, which should list its options with the selection highlighted (12.7.4.4); `/I` handles one index only and multi-select lists are not supported. (e) `/MaxLen` and comb fields (bit 25) are ignored. (f) `FieldText.write` writes the `/DA` font name raw (`"/${nameOf(run.font)}"`), so a name with a `#`-escaped character breaks the stream, which `AnnotationAppearance` guards with `SAFE_NAME` and this path does not.
- What should happen: Look the `/DA` font up in `/DR /Font` and reference that object (fall back to Helvetica only when absent); draw asterisks for a password field; keep `/MK` border and background as `synthesize` does; draw list boxes as lists; honour `/MaxLen`; escape the resource name.
- How to reproduce or evidence: `out_field.pdf`: the appearance resources are `/Helv → << /BaseFont /Helvetica >>` although the base form's `/DR` has its own `/Helv`; the content has no border though the test fixture of `FormFillTest` has none either, so nothing asserts it.
- Cross-effects: Rendering in KitePDF uses the written `/AP` when present, so the viewer shows the degraded appearance too, and `clearNeedAppearances` stops viewers from regenerating it.
- Fix sketch: Resolve `/DR /Font /<daName>` from the effective AcroForm and reuse it; pass the `/Ff` flags and `/MK` into `FieldAppearance.build`; share the border code with `synthesize`.

### PDF-WRITE-14: The editor never enforces `/P`: a document opened with the user password and `/P` that forbids changes is edited and re-encrypted without a word

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor init), kitepdf-pdf/crypto (StandardSecurityHandler)
- Kind: design
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:84-104; kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/StandardSecurityHandler.kt:59-71; kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPermissions.kt:54-60
- What happens: The editor checks `isAuthenticated` and `supportsWrite` only. ISO 32000-1, 7.6.3.2 says the user access permissions in `/P` shall be enforced by a conforming reader when the document was opened with the user password; MuPDF, the architectural reference, exposes `pdf_has_permission` for exactly this and its editing paths consult it (to confirm in `mupdf-master`, which is not checked out here). KitePDF edits, fills, stamps, rotates and re-encrypts a document whose `/P` clears bits 4, 6, 9 and 11, and the handler does not record which password authenticated (`fileKey` is the same either way), so neither the editor nor `PdfPermissions.from` can tell the owner apart from the user; `doc.permissions` reports the restrictions to the owner as well.
- What should happen: Record `authenticatedAsOwner` in the handler; the editor refuses (or a documented `force` flag allows) an operation that `/P` forbids for a user-authenticated document, in the way MuPDF does; `PdfPermissions` reports `allowAll` for the owner.
- How to reproduce or evidence: `PdfBuilder().encrypt("user", "owner", permissions = allowAll.copy(canModifyContents = false, canFillForms = false), ...)`, open with "user", `edit(random).stampPage(...)`, `saveIncremental()`: succeeds. `EncryptedWriteTest.permissions_round_trip_through_the_p_flags` only reads the flags back.
- Cross-effects: `PdfSignature`/DocMDP already reasons about permitted changes after signing; the same "is this change allowed" question has two different answers in the library.
- Fix sketch: Add `val isOwner` to `StandardSecurityHandler` (set in `deriveV5Key` and `tryOwnerPassword`), consult `base.permissions` in the editor's mutating methods unless the owner authenticated, and document it.

### EPUB-LAYOUT-02: `colspan`, `rowspan` and `<col span>` are read without a ceiling, so one attribute hangs or kills the layout

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-epub BoxBuilder and BoxLayout (tables)
- Kind: security
- Severity: high
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:763`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:764`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:712`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:617`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:648`
- What happens: `rowCells` takes `colspan` and `rowspan` as `toIntOrNull()?.coerceAtLeast(1)` with no upper bound (lines 763 and 764). `placeCells` then runs `for (dr in 0 until cell.rowspan) for (dc in 0 until cell.colspan) occupied.add(key(...))` (line 712 on), so `<td colspan="2000000000">` spins two billion iterations and grows a `HashSet<Long>` until the heap is gone; `rowspan` does the same. `collapseBorders` repeats the loop. In `layoutTable`, `cols = maxOf(gridCol + colspan)` sizes `DoubleArray(cols)` and the `colX` array, so a colspan of a hundred million allocates 800 MB before any loop runs. `scanColWidths` has the same hole for `<col span="...">` (`repeat(span)` at line 617 and 624). A smaller value also breaks the grid key: `key(r, c) = r * 100_000L + c` (line 648 and 714) collides with the next row once the spanned columns pass 100,000, so two hundred cells of `colspan="1000"` put cells of row 0 into row 1's slots and the collapsed borders go to the wrong cells.
- What should happen: HTML, 4.9.11 (the `td` and `th` elements), clamps `colspan` to 1000 and `rowspan` to 65534, and 4.9.4 (the `col` element) clamps `span` to 1000. A crafted book must not take the reader down ("Never break lenient salvage").
- How to reproduce or evidence: `EpubFixtures.epub("<table><tr><td colspan=\"2000000000\">x</td></tr></table>")` and `doc.pageCountIn(0)`: `placeCells` loops `Int` times; with `colspan="100000000"` `layoutTable` throws `OutOfMemoryError` on a small heap.
- Cross-effects: a script that sets `colspan` through the DOM (the other lane) reaches the same loop. `TableTest.colspan_spans_columns` covers only small spans.
- Fix sketch: `coerceIn(1, 1000)` for `colspan` and `span`, `coerceIn(1, 65534)` for `rowspan`, as HTML does, and key the grid with `(r shl 32) or c` (or a `Pair`) instead of `r * 100_000 + c`.

### EPUB-LAYOUT-17: The WOFF2 and WOFF decoders use the 512 MiB PDF stream cap, so a tiny font file can demand a gigabyte

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-epub Woff2, Woff and brotli
- Kind: memory
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff2.kt:82`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff.kt:60`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/filters/Filters.kt:65`
- What happens: `Woff2.convert` decodes the Brotli stream with `maxOutputBytes = FilterChain.MAX_DECODED_STREAM`, which is `512 shl 20` (line 82 and `Filters.kt:65`), then `assembleSfnt` copies every table into a second array of the same size. A 300 KB WOFF2 whose stream is a run of zeros expands to 512 MiB raw plus 512 MiB SFNT before `TrueTypeFont.parse` refuses it; `Woff.toSfnt` allows the same per table (line 60) and sums `origLen` over up to 65535 tables. On a 192 MB Android heap this is an `OutOfMemoryError` at font load, which `runCatching` in `Woff2.toSfnt` turns into a null face only after the allocation has starved every other thread. `totalSfntSize` of the WOFF2 header is not trusted, which is right, but no ceiling takes its place.
- What should happen: a font program has a natural ceiling: the largest real fonts (Noto Serif CJK, Source Han) unpack to about 30 to 40 MB. A cap of 64 MiB on the raw stream, the sum of `origLen`, and the assembled SFNT refuses a bomb and loses no real font.
- How to reproduce or evidence: `Brotli.decode` of a stream that encodes 512 MiB of zeros is about 30 KB (RFC 7932 copy commands); wrap it in a WOFF2 header with one table of `origLength` 512 MiB.
- Cross-effects: `RemoteResources` caps fetched bytes at 32 MiB, so a remote WOFF2 is capped on the wire but not after unpacking.
- Fix sketch: `private const val MAX_FONT_BYTES = 64 shl 20` in `Woff` and `Woff2`, passed to `Brotli.decode` and `Zlib.decode` and checked against the summed `origLen` before allocation.

### EPUB-LAYOUT-18: Selector matching tests every rule against every element, and the sibling pseudo-classes rescan the sibling list, so a long table or a big stylesheet costs seconds

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-epub/css (StyleResolver.compute, Selector)
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:55`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:129`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:211`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:512`
- What happens: `compute` and `computePseudo` loop over every rule for every element (lines 55 and 129), with no index by the rightmost compound's tag, class or id as Blink's `RuleSet` keeps; `computePseudo` runs twice more per element (before and after). An InDesign export with 3000 rules and a chapter of 20,000 elements makes 180 million `matches` calls per layout, and a chapter is laid out again after the budget drops it. `NthCondition.matches` finds the element with `siblings.indexOfFirst` and walks the siblings before it (line 211), and `previousElement` (line 512) scans from the first sibling, so `tr:nth-child(odd)`, `:first-child`, `+` and `~` on a parent of n children cost O(n) each and O(n^2) for the parent: a 10,000-row table (a dictionary or an index) takes about 10^8 steps per such rule. `:has()` walks the subtree of every candidate.
- What should happen: a rule index keyed by the subject compound's id, class and tag, as every browser does (and as the KDoc of `Selector` cites Blink's checker for the right-to-left walk), with the untyped rules in a small universal bucket; a cached child index on the element for the nth pseudo-classes.
- How to reproduce or evidence: `HtmlParserScalingTest` measures the parser alone; a timing of `StyleResolver.compute` over a 20,000-element chapter with 3,000 rules is not in the suite.
- Cross-effects: the script DOM's `querySelectorAll` (other lane) shares `Selector.matchFrom`.
- Fix sketch: at `StyleResolver` construction, bucket rules by the subject's `IdCondition`, first `ClassCondition`, else tag, else universal; in `compute`, test only the buckets the element can hit; memoize `previousElement` through a per-parent position map built lazily on the parent.

### EPUB-SCRIPT-04: A script can build a tree deeper than the parser's nesting limit, and the layout's recursion then overflows the stack

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-epub (ScriptDom, EpubScriptSession, HtmlParser, layout)
- Kind: security
- Severity: high
- Confidence: likely
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:537-542`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:563-592`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:1178-1234`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:590-596`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:318-323`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/HtmlParser.kt:47-52`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:83-92`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:895`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:306-307`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:919`, `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ScriptCall.kt:13-19`
- What happens: `HtmlParser.MAX_DEPTH = 32` exists because "layout recurses once for each level, and in a debug build 51 nested grid containers overflow the 512 KB stack" (#450). The parser moves deeper elements up, but a script's `appendChild` goes through `ScriptDom.insert`, whose `hierarchyError` checks the DOM's hierarchy rules and nothing about depth. `commit()` then snapshots the tree (`copy` recurses per level) and hands it to `replaceChapterTree`, which builds boxes and lays them out on the calling thread. `BoxBuilder.buildBlock` calls itself for every nested block child (line 895), so every nested `div` costs a frame there whatever its display; `BoxLayout` frames normal-flow blocks explicitly (#389) but `layoutGrid` and `layoutFlex` call `layoutBlock` for each item (306-307, 919), so nested grid or flex containers cost a frame per level in layout as well. Nothing in `ScriptDom` guards depth (no `depth` or limit anywhere in the file). The 16 MB script thread holds about 5000 levels at the 3 KB per level of a release build and about 1600 in a debug build on Apple targets; the JVM's frames are smaller but a loop making 20,000 nested `div`s with `style.display = 'grid'` costs about 2 × 10^8 steps of `isAncestor` and finishes inside the 5 s budget. The pool threads that lay the chapter out later for another document over the book have the platform default stack, smaller still. On Kotlin/Native a stack overflow is a segfault with no exception; on the JVM it is a `StackOverflowError`, which `scriptCall` does not catch (it catches `Exception`) and which ends the viewer's `LaunchedEffect` coroutine, an uncaught exception.
- What should happen: a tree a script makes obeys the same depth limit as a parsed one, or the layout's recursion is bounded. A corrupt or hostile book never stops the reader (CONTRIBUTING, "never break lenient salvage").
- How to reproduce or evidence: a chapter with `<script>var e = document.body; for (var i = 0; i < 20000; i++) { var d = document.createElement('div'); e.appendChild(d); e = d; }</script>`; open it with `EpubScriptRunner` and call `chapterOpened(0)`. On macOS the test process dies in the layout; on the JVM `chapterOpened` throws `StackOverflowError` out of `thread.call` (rethrown by `runCatching(block).get().getOrThrow()` in `ScriptThread.jvm.kt:21`).
- Cross-effects: `textContent`, `innerHTML`, `querySelectorAll` and `XMLSerializer` on such a tree recurse too (`appendText`, `serialize`, `query.walk`, `XmlWriter.element`). `ScriptDom.insert` is also O(depth) per call through `isAncestor`, so building the tree is quadratic.
- Fix sketch: in `hierarchyError`, refuse (or move up, as the parser does) an insertion whose resulting depth passes `HtmlParser.MAX_DEPTH`, and catch `Throwable` around the layout in `commit()` so a layout that fails leaves the previous tree in place.

### EPUB-SCRIPT-06: localStorage and sessionStorage have no quota, so a script can grow the host's memory without limit

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-epub (EpubScriptSession storage, DomPrelude Storage)
- Kind: memory
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:142-143`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:1033-1044`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomPrelude.kt:2084`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomPrelude.kt:2095-2099`, `docs/javascript.md:391`
- What happens: `storage(kind, "set", key, value)` puts the pair in a `LinkedHashMap<String, String>` and nothing checks a size. The strings live on the Kotlin heap, outside the engine's 512 MB `memoryLimit`, so a loop such as `for (var i = 0; ; i++) localStorage.setItem('k' + i, 'x'.repeat(1 << 20))` takes a megabyte of host memory per iteration until the budget stops it (5 s is thousands of iterations), and the next pump starts again. A browser caps a storage area (a few megabytes per origin, 5 MB in most) and throws `QuotaExceededError`, whose constructor the prelude defines (DomPrelude.kt:453-481) but never throws. The stores also outlive every chapter: `stores` belongs to the session, not to a chapter, so an evicted chapter frees nothing here.
- What should happen: HTML, 12.2.1: "User agents should limit the total amount of space allowed for storage areas", and `setItem` throws `QuotaExceededError` past the limit.
- How to reproduce or evidence: the loop above in a chapter script; observe the JVM heap after `chapterOpened(0)`.
- Cross-effects: `EpubScriptRunner` has no policy knob for memory at all; see EPUB-SCRIPT-10.
- Fix sketch: count the code units stored per kind, cap at about 5 MB as Chromium does, and have the `set` branch answer an error marker that the prelude turns into `new QuotaExceededError(...)`.

### EPUB-SCRIPT-07: PdfScriptRunner.failures is unbounded, and each failure copies the whole list

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner)
- Kind: memory
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:165-177`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:659-665`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:156-161`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:377-381`
- What happens: `recordFailure` appends to `failureList` and then sets `failureCopy = failureList.toList()`, a full copy, on every failure. `EpubScriptSession` caps its list at `MAX_FAILURES = 100` with the reason "a timer that throws each time it runs would otherwise grow it for as long as the book is open"; the PDF runner has no cap. A PDF with `app.setInterval("undefinedFn()", 0)` fails once per frame: `pumpTimers` runs it every pump, so after an hour at 60 frames a second the list holds 216,000 `KiteScriptException`s, each with its `JsException` cause and message, and the copying has cost 216,000 × average length, a quadratic 2 × 10^10 element copies. The `documentOpened` path also returns `failureList.drop(before)`, another copy.
- What should happen: the same cap as the EPUB session, documented the same way.
- How to reproduce or evidence: `PdfScriptRunner(doc).run(PdfAction.JavaScript("app.setInterval('nope()', 0)"))` then `pumpTimers` in a loop; `runner.failures.size` grows by one per call and never stops.
- Cross-effects: `failures` is read by hosts on another thread through `failureCopy`, which is why the copy exists; a bounded ring would keep that property.
- Fix sketch: drop the oldest entry past 100 as `EpubScriptSession.recordFailure` does, and replace the copy with an immutable snapshot that is rebuilt only when read.

### EPUB-SCRIPT-10: Every engine may allocate 512 MB and nothing in either policy can lower it, so eight live chapters plus their frames may take gigabytes

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-javascript (KiteJsScriptEngine, EpubScriptRunner, PdfScriptPolicy, EpubScriptPolicy)
- Kind: memory
- Severity: medium
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/KiteJsScriptEngine.kt:60-61`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/KiteJsScriptEngine.kt:182-183`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/EpubScriptRunner.kt:186-204`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/EpubScriptRunner.kt:291-294`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/EpubScriptRunner.kt:23-41`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptPolicy.kt:14-35`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:264-282`
- What happens: `MEMORY_LIMIT = 512L shl 20` is a `const val` chosen for "a compiled program's heap" (DoomPDF) and set on every `KiteJsScriptEngine`, PDF and EPUB alike. `EpubScriptRunner` opens one engine per live chapter, up to `LIVE_CHAPTERS = 8`, and one more per scripted frame of each (`open()` starts `ChapterScripts` for every scripted frame; `openAddedFrames` adds the ones scripts insert, bounded only by `MAX_FRAME_DEPTH`). A book whose chapters each run `var a = []; for (;;) a.push('x'.repeat(1e6))` hits 512 MB per engine inside the 5 s budget (the instruction budget is 0 by default and QuickJS counts allocation as nothing), so eight chapters the reader has visited hold up to 4 GB of native memory on a phone whose process dies far below that. Neither `PdfScriptPolicy` nor `EpubScriptPolicy` has a memory field, and `KiteJsScriptEngine`'s constructor does not take one either, so a host cannot lower the cap without implementing `KiteScriptEngine` itself. The CLAUDE.md gotcha about the 192 MB Android heap is about Java memory; this is malloc memory that the Android limit does not count but the OOM killer does.
- What should happen: a memory limit per engine in both policies, with a default sized for a chapter's scripts (tens of MB) and the 512 MB reserved for `LONG_RUNNING` documents, plus a limit on the total across live chapters.
- How to reproduce or evidence: the loop above in a chapter script of a book with 8 chapters; open each in turn with `chapterOpened(i)` and watch the process RSS.
- Cross-effects: EPUB-SCRIPT-05 and EPUB-SCRIPT-06 grow host memory outside this cap as well. `KiteJsScriptEngine.MEMORY_LIMIT` is `public const`, so changing it is an API change.
- Fix sketch: add `memoryLimitBytes` to `PdfScriptPolicy` and `EpubScriptPolicy` (default 64 MB for EPUB, 512 MB for `LONG_RUNNING`) and pass it through `KiteJsScriptEngine(memoryLimit = ...)`.

### EPUB-SCRIPT-16: calculateNow inside a calculate script recurses to the stack limit, and app.calculate is ignored

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner, PdfScriptHost, AcrobatApi)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:534-552`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:318-321`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:328`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:441`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:524`
- What happens: `this.calculateNow()` from inside a calculate script calls `host.action("calculateNow")` → `runCalculations()` → `onScriptThread` (which runs in place on the script thread) → `calculateAll()`, which runs the same calculate script again, with no guard. The recursion ends only when QuickJS's 8 MB stack check throws `RangeError` in the innermost evaluate; each level records a failure and, by EPUB-SCRIPT-01, hands a stale result to its field. PDFium's `CPDFSDK_InteractiveForm::OnCalculate` returns at once when `busy_` is set and sets it for the round, so the nested call does nothing, and returns when `IsCalculateEnabled()` is false (`fpdfsdk/cpdfsdk_interactiveform.cpp`, `OnCalculate`, fetched copy). `app.calculate` is a plain data property here that the runner never reads, so the common pattern `app.calculate = false; ...set many fields...; app.calculate = true; this.calculateNow()` runs a full calculation round after every assignment anyway.
- What should happen: a calculation round in progress ignores `calculateNow`, and `app.calculate === false` skips `calculateAll` (Acrobat JavaScript Reference, `app.calculate`).
- How to reproduce or evidence: a field whose calculate script is `this.calculateNow(); event.value = 1`; `runner.setFieldValue("other", "1")` fills `runner.failures` with hundreds of "Maximum call stack size exceeded" entries.
- Cross-effects: EPUB-SCRIPT-01, EPUB-SCRIPT-07.
- Fix sketch: a `calculating` flag around `calculateAll`, and read `app.calculate` through a host getter before each round.

### KITEJS-02: The instruction budget, the interrupt hook and a wall-clock deadline do not reach a long loop inside a built-in, on either engine

Status: proven, and held back with the other security findings until they are reported.
- Repo: KiteJS
- Area: kitejs-quickjs (interrupt handler), kitejs-rhino (NativeArray and friends), docs/limits.md
- Kind: design
- Severity: medium
- Confidence: likely (read the loops and the poll sites, did not run them)
- Where: kitejs-quickjs/native/kitejs_quickjs.c:163-190, kitejs-quickjs/native/quickjs/quickjs.c:8702-8708 and 44139-44190 (`js_array_join`) and 43848-43877 (`js_array_fill`), kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeArray.kt:1432-1482 (`js_lastIndexOf`) and 1528-1546 (`js_fill`), kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/regexp/NativeRegExp.kt:2859-2861
- What happens: QuickJS only runs the interrupt handler from `js_poll_interrupts`, which the interpreter calls at function entry and backward jumps, and from a handful of built-ins (`every`, `reduce`, `includes`, `indexOf`, `lastIndexOf`, `find`, the for-in builder, and the regexp engine through `lre_check_timeout` every 10,000 steps). `Array.prototype.join`, `Array.prototype.fill`, `String.prototype.padStart`, `Array.from` and the other array built-ins loop in C without a poll. `new Array(2147483647).join('')` runs `JS_GetPropertyUint32` two billion times on a sparse array, appends nothing (empty separator, undefined elements), so neither the budget, nor `interruptWhen`, nor `memoryLimit` ends it; it takes a minute or more of CPU. On Rhino the budget is charged only by the interpreter, `AsmRunner` and the regexp matcher (the only `addInstructionCount` call sites outside Interpreter.kt and Context.kt are NativeRegExp.kt:2861 and AsmRunner.kt:119); `new Array(2147483647).lastIndexOf(1)` walks the sparse branch at NativeArray.kt:1474-1481 two billion times with `getRawElem`, again with no budget, no hook and no memory growth.
- What should happen: docs/limits.md opens with "Stop a script that will not return" and presents the budget and the hook as the way to do it; `KiteJsConfig.interruptWhen` says it is "how an outside signal reaches a running script". A built-in loop over a length the script controls should poll the same handler (QuickJS upstream polls in several of its array methods already; the patch in native/kitejs-quickjs.patch could add `js_poll_interrupts` to `js_array_join`, `js_array_fill`, `js_array_from`, `js_string_pad`), and Rhino's length-driven loops in NativeArray, NativeString and the typed-array views should charge `cx.addInstructionCount` per iteration or per block, as the regexp matcher does.
- How to reproduce or evidence: `KiteJs(QuickJs) { instructionBudget = 1000; interruptWhen = { true } }.use { it.evaluate("new Array(2147483647).join('')") }` does not return within the time the budget implies. Rhino: `KiteJs(Rhino) { instructionBudget = 1000 }.use { it.evaluate("new Array(2147483647).lastIndexOf(1)") }`.
- Cross-effects: KitePDF's `PdfScriptRunner.deadlinePassed` and `EpubScriptRunner.deadlinePassed` (kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:718-730, EpubScriptRunner.kt) are `interruptWhen` hooks, and `PdfScriptPolicy.budgetMillis` (default 5 s) is enforced only through them, so a document can hold the script thread for a minute with one `join` and the viewer's stop button cannot reach it. The 512 MB `memoryLimit` does not help for loops that allocate nothing.
- Fix sketch: add `js_poll_interrupts(ctx)` every N iterations to the unpolled QuickJS array and string loops in the vendored patch; add `cx.addInstructionCount(...)` to Rhino's length-driven loops; add a contract test that runs `new Array(2**31-1).join('')` under a small budget and asserts it stops.

### KITEJS-03: The default `maxStackSize` of 512 KiB is the whole stack of an Apple secondary thread, so deep recursion there is a crash, not the promised RangeError

Status: proven, and held back with the other security findings until they are reported.
- Repo: KiteJS
- Area: kitejs-quickjs (`QuickJsConfig`, native stack check), kitejs-coroutines (`EngineThread` on native)
- Kind: design
- Severity: medium
- Confidence: likely (read the check and the thread creation; not run on a device)
- Where: kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/QuickJs.kt:53-61, kitejs-quickjs/native/kitejs_quickjs.c:325-331 and 395-400, kitejs-quickjs/native/quickjs/quickjs.c:2310-2315 and 3150-3170, kitejs-coroutines/src@native/io/github/yuroyami/kitejs/coroutines/EngineThread.native.kt (the file is 32 lines; the lane's line number could not be placed)-119, docs/engines.md:84-96
- What happens: QuickJS detects recursion by comparing the C stack pointer with `stack_top - stack_size`, where `stack_top` is sampled by `kite_enter` at each outermost call. With the default `stack_size` of 512 KiB on a thread whose whole stack is 512 KiB (every secondary pthread on iOS and macOS, which is what `newSingleThreadContext` and therefore `asyncKiteJs`'s `EngineThread` create on Kotlin/Native), the limit lies below the end of the stack, so the check never fires and the guard page ends the process with EXC_BAD_ACCESS. The KDoc admits that such a thread "needs a smaller value", but the default is the unsafe one, `asyncKiteJs(QuickJs)` picks that thread for the user, and the recursion contract (`ErrorsContract.deepRecursionIsARangeErrorTheScriptCanCatch`) runs only on the test process's main thread in CI, never on a 512 KiB thread. Rhino's native reentry limit (`maxHostCallDepth = 64`) is likewise untested on a 512 KiB thread; only Windows has a native stack probe (src@mingwX64/NativeStackProbe.mingw.kt), every other target answers null.
- What should happen: docs/engines.md:84-96 says "A script that recurses past `maxStackSize` gets a `RangeError`, which it can catch like any other." The default should be safe on every thread the library itself creates: either measure the thread's stack where the platform allows it (pthread_get_stacksize_np on Apple, pthread_getattr_np on Linux, as the Windows probe already does for Rhino) and cap the limit below it, or make `EngineThread` on native create its thread with a larger stack, and give the default a margin (for example 256 KiB, which KitePDF already chose for the same reason, kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/KiteJsScriptEngine.kt:186).
- How to reproduce or evidence: on macOS, `runBlocking { val js = asyncKiteJs(QuickJs); js.evaluate("function f() { f() } try { f() } catch (e) { e.name }") }` on the default `EngineThread`. The same inside a `Thread`/`pthread` with a 512 KiB stack on iOS.
- Cross-effects: `kitejs-coroutines` on Apple; any Apple app that opens the engine on `Dispatchers.Default`. KitePDF avoids it by making its own 1 MiB script threads and passing `maxStackBytes` (kitepdf-javascript/src@native/.../ScriptThread.native.kt, src@apple/NativeThread.apple.kt:23-26).
- Fix sketch: in `kite_new` or `kite_enter`, clamp `stack_size` to the current thread's stack size minus a reserve where the platform can say (Apple, Linux, Windows); give `EngineThread.native.kt` an explicit stack size; add an Apple test that opens the engine on a `Worker` thread and recurses.

### KITEJS-04: Releasing collected QuickJS handles is quadratic in the number collected between two calls

Status: proven, and held back with the other security findings until they are reported.
- Repo: KiteJS
- Area: kitejs-quickjs facade (`QuickJsKiteJs.collected`, `HandleCleaner`)
- Kind: performance
- Severity: medium
- Confidence: verified (read the whole path; the cost is an estimate)
- Where: kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:102-109 and 281-290, kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/JniBridge.kt:121-142, kitejs-quickjs/src@native/io/github/yuroyami/kitejs/quickjs/bridge/NativeBridge.kt:212-220
- What happens: every handle whose wrapper the collector took is queued through `collected.compareAndSet(now, now + h)`, where `collected` holds an immutable `List<Int>`; `List.plus` copies the list, so queueing n handles costs n²/2 element copies. On the JVM the queueing happens inside `sweep()` itself (`cleaner.poll()` drains the `ReferenceQueue` and calls the lambda once per handle), on the engine thread, at the start of the next outermost call; on Kotlin/Native it happens on the cleaner thread. A host that reads a 100,000-element array of objects (`js.evaluate("rows").asArray().toList()`) creates 100,000 wrappers; after the next GC the following `evaluate` first spends about 5 billion element copies in `sweep()`.
- What should happen: queueing a collected handle should be O(1): on the JVM `poll()` already runs on the engine thread and could call `bridge.release` directly; elsewhere a lock-free queue or an `ArrayList` under a lock.
- How to reproduce or evidence: on the JVM, `val a = js.evaluate("Array.from({length: 200000}, (_, i) => ({i}))").asArray(); a.toList(); System.gc(); Thread.sleep(100); time { js.evaluate("1") }`.
- Cross-effects: KitePDF's EPUB DOM bindings hand many objects to the host per chapter, so the first `evaluate` after a GC pays this on the script thread.
- Fix sketch: replace `AtomicReference<List<Int>>` with an intrusive lock-free stack or a mutex-guarded `IntArray` buffer, and on the JVM release inside `poll()`.

### KITEJS-09: `DecimalParser.exactPath` builds a big integer from every significant digit, so a very long numeric string parses in quadratic time

Status: proven, and held back with the other security findings until they are reported.
- Repo: KiteJS
- Area: kitejs-api dtoa (`DecimalParser`, `KBigInt.parse`)
- Kind: performance
- Severity: low
- Confidence: verified (read the path; the cost is an estimate)
- Where: kitejs-api/src/io/github/yuroyami/kitejs/api/dtoa/DecimalParser.kt:146-197, kitejs-api/src/io/github/yuroyami/kitejs/api/KBigInt.kt:328-357 and 638-650
- What happens: any decimal with more than 15 significant digits goes to `exactPath`, which calls `KBigInt.parse(significant, 10)` on the full digit string. `parse` multiplies the growing magnitude by 10^9 per chunk (`magMulAddSmall`), so a string of n digits costs about (n/9)²/2 limb operations. `Number('1'.repeat(1_000_000))` or `parseFloat` of the same string costs several billion limb operations (tens of seconds) and, being a built-in loop, is not reached by the budget (KITEJS-02). V8 and QuickJS's dtoa truncate to the 768 or so significant digits that can affect correct rounding and keep one sticky bit for the rest.
- What should happen: the same answer in linear time: keep at most 768 significant digits plus a flag that says whether any dropped digit was non-zero, and round from those.
- How to reproduce or evidence: `KiteJs(Rhino).use { it.evaluate("Number('9'.repeat(400000))") }` and time it; QuickJS has its own parser and is not affected.
- Cross-effects: `JsValue.asDouble()` on a string goes through `JsNumbers.stringToNumber` and the same parser, on both engines.
- Fix sketch: in `parse`, cut `digits` at 768 characters, set `sticky` from the rest, and treat `sticky` as a non-zero remainder in the rounding comparison.

### IMAGEKODEC-01: Inflate loops forever once its output passes 1 GiB with no size hint, which a 16-bit RGBA PNG at the pixel ceiling asks for

Status: proven, and held back with the other security findings until they are reported.
- Repo: ImageKodec
- Area: imagekodec/internal/flate, codec/PngDecoder
- Kind: security
- Severity: high
- Confidence: verified (read the whole path; the growth arithmetic reproduced with a Java probe)
- Where: imagekodec/src/io/github/yuroyami/imagekodec/internal/flate/Inflate.kt:207-217, imagekodec/src/io/github/yuroyami/imagekodec/internal/flate/Zlib.kt:555-576, imagekodec/src/io/github/yuroyami/imagekodec/codec/PngDecoder.kt:486-505
- What happens: `State.ensure` grows the output buffer with `var cap = ...out.size; while (cap < needed) cap = cap shl 1` on an `Int`. When `out.size` reaches 2^30 and one more byte is needed, `cap shl 1` becomes `Int.MIN_VALUE` (negative, so still `< needed`), the next shift gives 0, and `0 shl 1` stays 0 for ever: the loop never exits. The buffer only grows this way when the caller passes no `sizeHint`, and `Zlib.decompress` drops its hint whenever `maximumSize > Int.MAX_VALUE` (its default `sizeHint = if (maximumSize <= Int.MAX_VALUE) maximumSize.toInt() else 0`). `PngDecoder.unpack` passes `expected = height.toLong() * (1 + rowBytesFor(width))` as `maximumSize`; for a 16384 by 16384 16-bit RGBA PNG that is 2147500032, just past `Int.MAX_VALUE`, so the hint becomes 0. The header passes every guard: 16384 is below `MAX_DIMENSION` (2^24), 16384*16384 is exactly `MAX_PIXELS` (2^28) and the ceiling test is `>` not `>=`, and `Budget.fits` at 64 bits per pixel allows 2^28 pixels from any input over 64 KiB. The IDAT then inflates by doubling from 256 bytes up to 2^30, and hangs. A stream of filtered zero rows compresses about 1000:1, so roughly 1 MiB of deflate data reaches 2^30 output bytes and spins the decode thread forever. This is exactly the "decode that does not terminate" SECURITY.md names as a vulnerability. Even without the wrap, an `expected` past `Int.MAX_VALUE` can never be materialised as a `ByteArray` on any target, so such a PNG can never decode anyway and should be refused up front.
- What should happen: refuse at the IHDR a PNG whose computed inflated size exceeds `Int.MAX_VALUE`, before any inflate (its pixels can never fit a `ByteArray`); and grow `cap` in `Long` arithmetic clamped to `maxOutput` so the shift cannot wrap, mirroring the `needed.toLong() > maxOutput` guard already above it.
- How to reproduce or evidence: the growth loop was simulated in Java at `out.size = 2^30`, `needed = 2^30 + 1`: step 1 cap = -2147483648, step 2 cap = 0, and 0 thereafter (never exits). `expected` for 16384x16384 RGBA16 = 2147500032 > Int.MAX_VALUE (2147483647), so `Zlib.decompress` passes `sizeHint = 0`. The same wrap reaches `TiffDecoder` deflate strips when a single strip's `expect` exceeds 2^30 with no size hint, though TIFF usually passes an exact Int expect.
- Cross-effects: `Zlib.decompress` is also used by `ColorChunks.iccp` (capped at 16 MiB, safe) and TIFF Deflate strips (bounded by strip geometry). Only PNG reaches a maximumSize over Int.MAX_VALUE with the hint dropped. The JVM test heap is 3 GB, so a test could allocate the 1 GiB and still hang; on a 192 MB Android heap it would instead OOM mid-grow, which `KiteImageSamples.decode` swallows but only after the allocation.
- Fix sketch: in `PngDecoder.unpack`, throw `ImageDecodeException` when `expected > Int.MAX_VALUE`; and in `Inflate.State.ensure`, compute the new capacity as a `Long` (`var cap = out.size.toLong(); while (cap < needed) cap = cap shl 1; out = out.copyOf(minOf(cap, maxOutput).toInt())`).

### PLATFORM-01: An SVG `<use>` tree that fans out renders in k^16 steps, with no work budget to stop it

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-svg (SvgImage.walk, drawUse)
- Kind: security
- Severity: high
- Confidence: verified (ran it)
- Where: kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:224, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:292-293, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:363-381, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1704, kitepdf-svg/test/io/github/yuroyami/kitepdf/svg/SvgFeaturesTest.kt:58-66
- What happens: The only guard on `<use>` recursion is `depth > MAX_DEPTH` (32). A `<g id="a">` that holds k `<use href="#a"/>` elements is walked k times at each level, and each `g`/`use` pair costs two levels, so the walk visits k^16 copies of the group before the depth limit ends each branch. The existing test `a_use_cycle_stops_instead_of_hanging` covers a cycle one element wide, which the depth limit does stop; it never covers fan-out. XPS has a page-wide work budget for exactly this (`XpsRenderBudget`, 100,000 elements, kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsRenderer.kt:20-28); SVG has none. `SvgImage.links()` and `textContent()` (SvgImage.kt:145, 152) run the same walk with no cancellation at all, so a hyperlink or text-extraction pass hangs for good.
- What should happen: A bounded amount of work per render, as the XPS renderer and the PDF tiling-pattern limit do: a count of elements walked (or of `<use>` instantiations), past which the render stops and warns. SVG 2, 5.6.1 makes a `use` that references itself an error, but a non-cyclic tree (`a` uses `b` twice, `b` uses `c` twice, ...) blows up the same way and is valid, so a depth check is not enough.
- How to reproduce or evidence: `<svg width="10" height="10"><g id="a"><use href="#a"/><use href="#a"/><use href="#a"/><rect width="1" height="1"/></g></svg>` rendered to `NoopCanvas` through `SvgDocument.open(...).pages[0].renderTo(...)`: k=2 painted 65,535 fills in 294 ms; k=3 took 34,181 ms (43 million walks); k=4 is 100 times that. An EPUB can carry such an SVG in any chapter, and the viewer renders pages on the raster pool.
- Cross-effects: EPUB page render, SvgDocument, the viewer's prefetch pool (one hung worker per such page), `textContent()` and `hyperlinks` of `SvgPage` which take no `KiteCancellation`.
- Fix sketch: Add a per-render budget (a counter in the `walk` entry, decremented per element and per `<use>` instantiation, as `XpsRenderBudget.take()`), and refuse a `<use>` whose target is already on the active instantiation stack (a set of elements being drawn), which is what browsers do for self-reference.

### PLATFORM-03: An SVG filter converts the whole raster step to floats before it shrinks to its pixel budget, so a full-page filter allocates up to 640 MB on Skia and AWT and 256 MB on the Compose viewer

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-svg (SvgImage.runFilter, FilterImage)
- Kind: memory
- Severity: high
- Confidence: verified (sizes read in code); the out-of-memory outcome on a phone is likely, not run
- Where: kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1396-1406, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1418-1419, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1722, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/FilterImage.kt:16-21, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/FilterImage.kt:116-130, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:1015, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:1448, kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageRaster.kt:9, kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/SoftMaskFilter.kt:25, kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:896
- What happens: `runFilter` first calls `FilterImage.of(scope.render { ... })`, which allocates a `FloatArray(width * height * 4)` for the full raster step (16 bytes a pixel), and only then computes `shrink` from `FILTER_MAX_PIXELS` (4 million) and calls `resized(w, h)`. At the end it resizes back to the full step and calls `toRaster()`, another full-size `IntArray`. The step budgets are 40 million pixels on `SkiaCanvas`, `AwtCanvas` and `CoreGraphicsCanvas`, 16.8 million on `ComposeCanvas` and 4.2 million on `AndroidNativeCanvas`. So a filtered element whose box covers the page at those sizes needs 640 MB (Skia, AWT, CoreGraphics), 256 MB (Compose viewer) or 64 MB (Android native) of floats in one array, on top of the step's own raster, the `KiteRaster` copy and the primitives' working images. A zoomed page of the Compose viewer on Android, where a 1080 by 2400 screen at 2x is 10 million pixels, asks for a 160 MB float array plus 40 MB of ints on a 192 MB (or 512 MB large) heap, and `OutOfMemoryError` is not an `Exception`, so no lenient catch sees it.
- What should happen: The budget applies before the float conversion: either ask the canvas for the step at the reduced scale (every `rasterStep` already supports a scale, as the mask code uses it), or shrink the `KiteRaster` as ints before `FilterImage.of`, and draw back the reduced raster scaled up through `KiteRasterScope.draw`, which samples bilinearly on every canvas.
- How to reproduce or evidence: `FilterImage(width, height)` is `FloatArray(width * height * 4)`; `FilterImage.of(raster)` passes the raster's full size; the shrink test at SvgImage.kt:1400 runs after that allocation. An EPUB page holding `<svg ... style="filter: drop-shadow(2px 2px 2px black)">` the size of the page, viewed at 2x zoom on Android, exercises it.
- Cross-effects: every canvas with raster steps; the Android viewer's whole-process crash; `KITE_DEFAULT_MAX_RASTER_PIXELS` which sizes three of the five budgets.
- Fix sketch: Compute `shrink` from `scope.width * scope.height` before rendering, pass `scale` into the step through a reduced `KiteRasterScope` (or downsample the int raster), and keep `FilterImage` at or under `FILTER_MAX_PIXELS` from the start; resize the final result as ints.

### PLATFORM-04: A `<filter>` keeps every named result for the run and caps neither the primitive count nor the per-primitive work, so a crafted filter holds gigabytes or runs for minutes

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF
- Area: kitepdf-svg (FilterContext.run, SvgImage.filterStage)
- Kind: security
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgFilter.kt:131, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgFilter.kt:150-166, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1108, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1725, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/FilterImage.kt:513-523
- What happens: `FilterContext.run` keeps each primitive's output under its `result` name in `named` for the whole stage, with no eviction, and `filterStage` maps every child of the `<filter>` to a primitive with no count limit. Every image is 16 bytes a pixel, 64 MB at the 4 million pixel budget. A filter of 200 `<feFlood result="rN"/>` elements over a page-sized element asks for 12.8 GB. Separately, `feConvolveMatrix` allows a kernel of 256 values (`MAX_KERNEL`, "as Skia allows") and runs it per pixel and channel: 4 million pixels times 256 taps times 4 channels is 4 billion multiply-adds for one primitive, and the number of primitives is unbounded, so a filter of a hundred 16 by 16 convolutions runs for minutes with the cancellation flag checked only between stages (SvgImage.kt:1415). The same stage loop allocates two or three working images per blur and per morphology.
- What should happen: A cap on primitives per filter (browsers set none, but they run on the GPU and drop frames; a reader embedding untrusted books needs one), a cap on the number or total bytes of named results (drop the least recently named when the sum passes a budget, since a later reference to it then reads as a missing reference, which 15.7.2 of Filter Effects 1 already defines as "no reference"), a cancellation check inside the per-pixel loops of convolve, lighting and turbulence, and a work bound for convolve (kernel taps times pixels).
- How to reproduce or evidence: `<filter id="f">` + 200 `<feFlood result="r1".."r200"/>` on a `<rect>` the size of the page; `named[it] = result` at SvgFilter.kt:164 keeps all 200.
- Cross-effects: PLATFORM-03 multiplies the per-image size when the step is larger than the budget; EPUB books are untrusted input.
- Fix sketch: Keep named results in a bounded LRU by bytes (say 8 images), count primitives (say 64 per filter, as the W3C test suite needs at most a dozen), and poll `stop` inside the pixel loops of the expensive primitives.

### BUILD-DOCS-TESTS-19: KitePDF and KiteJS have no SECURITY.md, though both parse untrusted input

Status: proven, and held back with the other security findings until they are reported.
- Repo: KitePDF, KiteJS
- Area: repository root, .github/
- Kind: security
- Severity: low
- Confidence: verified
- Where: /home/user/KitePDF (no SECURITY.md, no .github/SECURITY.md), /home/user/KiteJS (same), /home/user/ImageKodec/SECURITY.md (exists)
- What happens: a PDF parser with a JavaScript engine behind it is the kind of library that receives vulnerability reports; ImageKodec has a disclosure policy, KitePDF and KiteJS do not, so GitHub shows no "Report a vulnerability" path and a reporter's only option is a public issue. docs/about.md:46-48 asks for rendering problems as public issues and says nothing about security.
- What should happen: a SECURITY.md in each repository (the ImageKodec one can be copied) naming the private reporting channel and the supported versions.
- How to reproduce or evidence: `ls SECURITY.md .github/SECURITY.md` in each repository.
- Cross-effects: none seen.
- Fix sketch: copy ImageKodec/SECURITY.md and enable private vulnerability reporting on the two repositories.

### BUILD-DOCS-TESTS-20: The KiteJS and ImageKodec workflows run with the default token permissions and keep the checkout credentials

Status: proven, and held back with the other security findings until they are reported.
- Repo: KiteJS, ImageKodec
- Area: .github/workflows/ci.yml, quickjs.yml, test262.yml, publish.yml
- Kind: security
- Severity: low
- Confidence: verified
- Where: /home/user/KiteJS/.github/workflows/ci.yml:1-30 (no `permissions:` block, `actions/checkout@v7` without `persist-credentials: false`), /home/user/ImageKodec/.github/workflows/ci.yml:1-15 and publish.yml:19-30 (same)
- What happens: KitePDF's workflows set `permissions: contents: read` and `persist-credentials: false` on every checkout; the two sibling repositories do not. Their jobs run third-party tools on untrusted pull-request content (`npm install`, `pip`, `zig`, `cmake` of cloned oracle sources in ImageKodec's `test-jvm`), with the `GITHUB_TOKEN` of the repository's default scope available to them and the token stored in `.git/config` of the checkout. `publish.yml` additionally exposes the Central credentials and the signing key to a job that runs `./kotlin`, whose distribution is pinned by checksum but whose Kotlin/Native, JDK and Node downloads are not.
- What should happen: `permissions: contents: read` at the top of each workflow, `persist-credentials: false` on each checkout, and `pull_request` jobs that never see secrets (they do not today, which is right).
- How to reproduce or evidence: `grep -c "persist-credentials\|^permissions" KiteJS/.github/workflows/ci.yml ImageKodec/.github/workflows/ci.yml ImageKodec/.github/workflows/publish.yml` prints 0 for each.
- Cross-effects: none seen.
- Fix sketch: four lines per workflow, copied from KitePDF's ci.yml:30-31 and 41-43.

## Could not be settled here

Claims that need a device, a Mac, Windows or a release build to prove or refute. Each says what would settle it.

| Where | Claim | What would settle it |
| --- | --- | --- |
| XCUT-02 | Shrinkers keep the EPUB engine in a PDF-only Android, iOS or web app that uses the viewer | Settle by building a release PDF-only viewer app per target with and without kitepdf-epub. The dependency itself and the JVM cost are filed as #693. |

## Refuted

Claims that the code, a probe or a reference reader contradicted. They were not filed; a "(part)" finding was filed for the rest of its claims.

| Id | Why it was not filed |
| --- | --- |
| XCUT-07 | Not a defect: no concrete consequence beyond api/ dump churn and docs size; the claimed mutation of a tree the layout holds does not hold. KP-123's concrete opt-in bypass is filed as #712. |
| XCUT-08 | Not a defect: documented design; per-document diagnostics would be a feature request. |
| VIEWER-API-01 (part) | Only the SinglePage part is refuted: the slot keeps its rasterizer across page changes, except when it passes through a chapter placeholder. The Paged and Spread parts are filed as #660. |
| VIEWER-API-07 (part) | Two sub-claims of VIEWER-API-07 refuted; the rest filed as #667. |
| VIEWER-API-08 (part) | Design kept by #398, not a defect; the stale resetZoom KDoc/docs part is filed in #669. |
| VIEWER-API-09 (part) | Design choice, not a defect: the page bitmap is an image named 'Page N of M', and its text nodes sit beside it. |
| VIEWER-API-10 (part) | Part (c) holds only for pagePlaceholder and prefetchPages, which #669 covers. |
| VIEWER-RUNTIME-03 (part) | Only the audit's example trigger is refuted; the guard defect itself is drafted with the deep-tree trigger. |
| VIEWER-RUNTIME-07 | The claimed trigger (a host calling focusField between two views) is impossible: focusField is internal. A dropped choice answer after leaving is a no-op, since the leave dismisses the session (choiceSession === session fails) and clears choiceCommitPending. |
| VIEWER-RUNTIME-09 | Documented design, not a defect: the cache belongs to the state so a return to the view is a cache hit. A public trim for onTrimMemory would be a feature request. |
| VIEWER-RUNTIME-12 | A coroutine's cancellation is thrown by withContext when the block returns, outside runCatching, so none of these can swallow it; the blocking calls themselves never throw CancellationException. |
| PDF-RENDER-12 | The audit says mutool fills red; MuPDF fills black, as KitePDF does and as #52 measured ('Same-page behaviour is not a defect'). d1 outside a glyph description is invalid (ISO 32000-1, 9.6.5) and its effect undefined; per CONTRIBUTING, MuPDF is the reference and a difference only PDFium shows is not a failure. |
| PDF-RENDER-16 (part) | The cut-off itself is filed as #791, asking for a memory bound and a warning instead of a fixed count. |
| PDF-RENDER-18 (part) | The slow path is real for 2, 3 and 5+ colorants and is filed as #798. |
| PDF-RENDER-21 | No library path reaches the branch, so Compose paints nothing for an unparsable shading, as the other canvases do, and the renderer's pattern /Background is painted once. Only a third-party caller of ComposeCanvas.fillShading with a hand-made Unsupported shading and a clip path sees the difference. |
| PDF-RENDER-22 (part) | Refuted parts: (1) a dark glyph, icon or table rule vanishing passes: it fails on largest channel error; (2) no canvas but AWT is gated: GroupFixtures and the other oracle fixtures run on Skia and Compose (SkiaGroupOracleTest, ComposeGroupOracleTest), and Canvas2D and CoreGraphics have group, soft mask, stroke and image tests; (3) the missing-fixture list is not a defect on its own: Lab/CalRGB/16-bit are in ColorFixtures, JPX in ParityFixtures, overprint in OverprintTest, and the rest belong to the feature defects in other packages; the grey luminosity fixture belongs to P07 (luminosity weights). |
| PDF-RENDER-25 | No observable defect: a lost race only builds an equal variant twice. Every memoised value is immutable, a variant identity compares by namespace and path (KiteImageIdentity.equals), so duplicates hit the same bitmap cache entry, and KiteImageSamples documents that its race only costs one more decode. The KiteCanvas KDoc rule (KiteCanvas.kt:23-26) is about canvas state, not about these shared caches. The audit itself rates it info and benign. |
| FONTS-TEXT-12 | The audit says MuPDF falls back via a synthesized Unicode charmap; MuPDF 1.28.6 draws a blank like KitePDF. No spec rule asks for the fallback, and per CONTRIBUTING a difference only PDFium shows is not a failure. gNN/index names not tested; same category. |
| FONTS-TEXT-14 | ISO 32000-1 9.4.4 sets ty = 0 and takes tx from the horizontal component in horizontal writing, so a zero advance for a quarter-turn matrix is what the spec and MuPDF give. The audit's claim that MuPDF uses the full vector is contradicted by MuPDF 1.28.6. |
| FONTS-TEXT-19 (part) | Partial refutation of the reachability claim; the footprint defect itself is filed as #687. |
| FONTS-TEXT-20 (part) | Only the ligature part of FONTS-TEXT-20 holds against the oracles; it is filed as #807 with KP-44. The rest matches MuPDF and is a feature request at most. |
| PDF-WRITE-11 (part) | Part (e) except /PieceInfo. Outline titles, the document information dictionary and XMP are document-level text, not content of the page region; a region redaction cannot tell which of them restates the region, and the docs do not claim to touch them (the editor offers setInfo for the metadata). A document sanitizer is a feature, not a redaction defect. |
| PDF-WRITE-14 (part) | A documented design choice, not a defect; KP-18 asked to document the choice either way, and it is documented. The owner/user distinction an app needs to enforce it is drafted separately. |
| PDF-WRITE-15 (part) | Only the /Size part is refuted: a lower /Size needs a base whose trailer /Size exceeds its own cross-reference table, which is itself non-conforming (ISO 32000-1, 7.5.5), and no new object can be numbered below the base /Size. The /ID part is filed separately. |
| PDF-WRITE-17 (part) | Partly refuted: object-stream rewrites are checked by mutool, the AES-256 R6 builder output is rendered by MuPDF and PDFium in the parity sweep (which runs Algorithm 2.B, and with it SHA-256/384/512), and SHA-384 is cross-checked against the JDK. The surviving part (no oracle on an incremental save over an unrepaired xref-stream base, non-ASCII show strings) is filed. |
| PDF-WRITE-19 | The extra copy is real but transient: it is unreachable once MD5 returns, before the final toByteArray copy is made, so the peak live set stays the builder buffer plus one copy either way. The claimed three-times peak does not hold; what remains is one avoidable allocation of the file size, not a defect worth an issue. |
| PDF-WRITE-20 | The Acrobat-normalises interop claim was not verifiable here (no Acrobat or PDFBox). PdfBuilder.encrypt's KDoc not naming the encoding is a minor omission, not a defect. |
| EPUB-LAYOUT-06 (part) | Partial: the height sub-claim is not filed. In a paginated reader the root commonly takes the page height on purpose (a cover with height:100% filling the page), so resolving against the page is a defensible choice, not a proven defect. The width, margin and padding part is filed as #838. |
| EPUB-LAYOUT-10 | The claimed evidence (InlineRun and BlockBox survive only with a page background) does not hold: runs and text blocks are retained in both cases through the lines, and BYTES_PER_GLYPH is calibrated over the corpus 'with its share of lines, runs and DOM'. The extra retention from deco and hit boxes is about 100 bytes per block, too small to make the budget materially wrong. |
| EPUB-LAYOUT-11 (part) | Partial: wrapping pre text so that it stays on the page is a reader choice the audit itself calls defensible; not filed. nowrap, pre-line and tab are filed as #845, #846, #847. |
| EPUB-LAYOUT-14 (part) | Partial: this consequence is not specific to KitePDF. The fixed 0.8 em baseline without half-leading and the 1.4 em normal are filed as #852. |
| EPUB-SCRIPT-12 (part) | Not a defect: the references disagree and only PDFium differs (CONTRIBUTING: a difference that only PDFium shows is not a failure). Also the AFPercent_Format variant of the same claim. |
| EPUB-SCRIPT-16 (part) | These two sub-claims are wrong; the proven parts are the security draft (two or more fields) and #781 (flag ignored on commit). |
| EPUB-SCRIPT-17 | Not a defect: the fallback is deliberate and documented in the public KDoc of PdfScriptRunner.runCalculations ('A form with no order runs them in the order its fields appear') and in calculationOrder(). ISO 32000-1 Table 218 makes /CO required when a field has a C action, so a file without it is damaged, and calculating anyway is lenient salvage. A non-empty /CO is followed exactly. |
| EPUB-SCRIPT-22 (part) | The audit's own repro is refused; only paste and commit admit an exponent (filed as #785). |
| KITEJS-09 (part) | DecimalParser.parse returns infinity when the leading exponent passes 309, before exactPath. The quadratic cost is real only for long values inside the double range; filed as a security draft with a corrected repro. |
| IMAGEKODEC-03 (part) | Detail only; the defect (the /ColorSpace is ignored on both branches) is filed as #808. Its example of a 3-component codestream under /DeviceGray is a mismatched file and was not used. |
| PLATFORM-09 (part) | Only the soft-mask part of the CoreGraphics claim is refuted; its groups (CGContextBeginTransparencyLayer(ctx, null)) are filed in #824. |
| PLATFORM-19 | Two opens of the same file get different keys, as the comment intends. An identity-hash collision within the session's 256 places is theoretical. |
| PLATFORM-22 (part) | The repro 'KiteLock().unlock() then lock() never returns' is false; the real risk (an unmatched unlock frees another thread's hold) is in #773. |
| BUILD-DOCS-TESTS-12 (part) | They do not run by default, which is drafted; with --include-classes they can be selected (not run here). |
| BUILD-DOCS-TESTS-15 | Not a defect: no concrete consequence beyond api/ dump churn and docs size; the claimed mutation of a tree the layout holds does not hold. KP-123's concrete opt-in bypass is filed as #712. |
| BUILD-DOCS-TESTS-16 | Not a defect: a pre-1.0 design note with no current consequence; the audit itself proposes a 1.0 planning item. |
| BUILD-DOCS-TESTS-17 (part) | Not a defect: the same source set is tested; no iOS-only code path exists to cover. |
| BUILD-DOCS-TESTS-22 (part) | Partial refutation: only the claim that the iOS sample is undocumented is wrong. |
| BUILD-DOCS-TESTS-23 | Nothing to assert absent, so the checks are complete for these books. |
| BUILD-DOCS-TESTS-24 | The facts hold (mkdocs-material>=9.5 is unpinned), but no failure: the strict build passes with the newest release. A floating tool is a hazard, not a defect. Worth knowing: mkdocs-material 9.8.0 prints that Material for MkDocs reaches end of life on 2027-05-05 (Zensical is the successor); that banner does not fail --strict. |
| line 1252, item 8 | Document which canvas gives the backdrop for non-isolated and knockout groups: Already documented (in rendering.md, not compose-viewer.md). |
| line 1252, item 9 | Text render mode 7 on a Type 3 font adds nothing to the clip: The current behaviour is what the spec asks for. |
| line 576, item 4 | KitePageVector allocates a KiteBitmapCache on every draw when imageCacheBudgetBytes = 0: The Vectorized KDoc says 0 disables retention across draws, which holds. No extra retained memory; not a defect. |
| line 576, item 5 | A state used after its view left posts script work to a cancelled scope, where it is dropped: Same claim as FABLE VIEWER-RUNTIME-07, refuted by P02: focusField is internal, and a dropped choice answer after leaving is a no-op because the leave dismisses the session. |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 426, item 1 | A platform.zlib actual for Apple and Linux | A measurement to make, not a deliverable. Related: #835. |
| 567, item 6 | A KiteSelectionMenuItem preset for keyboard copy | Left to the host by a documented design choice; a preset would make the viewer use the clipboard. Owner's call. |
| 746, item 4 | A shared 'after the view' scope for launches that leave composition | Design principle with no user-visible deliverable. Related: #365, #887. |
| 903, item 2 | A per-document decode budget across all streams | No memory accumulation shown: a sum budget would bound decode time, not memory. If decode time per document is a concern it belongs to the security batch. |
| 1834, item 5 | AES-128 (V4) on the create path | Documented design choice; R6 opens in Acrobat 9 and later. The isOwner half is the P16 draft (separate record). |
| 2466, item 4 | A docs line on Field.value reading as a number | Optional doc polish, no gap. |
| 2663, item 4 | JsObject.keys returns strings only | Not drafted: convenience with a documented workaround in the audit itself. |
| 3042, item 7 | Bundled speech for KiteReadAloud clips without audio | Documented design; the npt= half is #708. |
| 746, item 2 | Platform defaults for cacheBudgetBytes and maxBitmapLongSide | Needs device measurement to pick numbers, with no defect behind it; the trim hook #903 lets a host respond to pressure meanwhile. |
| 2663, item 6 | A memory budget or allocation caps for Rhino | Documented limitation with a documented alternative. |
