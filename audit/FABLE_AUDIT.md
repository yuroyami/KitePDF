# FABLE audit of KitePDF, KiteJS and ImageKodec

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... FABLE <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

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
| PDF-RENDER-22 (part) | Refuted parts: (1) a dark glyph, icon or table rule vanishing passes: it fails on largest channel error; (2) no canvas but AWT is gated: GroupFixtures and the other oracle fixtures run on Skia and Compose (SkiaGroupOracleTest, ComposeGroupOracleTest), and Canvas2D and CoreGraphics have group, soft mask, stroke and image tests; (3) the missing-fixture list is not a defect on its own: Lab/CalRGB/16-bit are in ColorFixtures, JPX in ParityFixtures, overprint in OverprintTest, and the rest belong to their own feature issues; the grey luminosity fixture belongs to #799 (luminosity weights). |
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
| EPUB-SCRIPT-16 (part) | These two sub-claims are wrong; the proven parts are #962 (two or more fields) and #781 (flag ignored on commit). |
| EPUB-SCRIPT-17 | Not a defect: the fallback is deliberate and documented in the public KDoc of PdfScriptRunner.runCalculations ('A form with no order runs them in the order its fields appear') and in calculationOrder(). ISO 32000-1 Table 218 makes /CO required when a field has a C action, so a file without it is damaged, and calculating anyway is lenient salvage. A non-empty /CO is followed exactly. |
| EPUB-SCRIPT-22 (part) | The audit's own repro is refused; only paste and commit admit an exponent (filed as #785). |
| KITEJS-09 (part) | DecimalParser.parse returns infinity when the leading exponent passes 309, before exactPath. The quadratic cost is real only for long values inside the double range; kept with a corrected repro in KITEJS_DRAFTS.md. |
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
| line 576, item 5 | A state used after its view left posts script work to a cancelled scope, where it is dropped: Same claim as FABLE VIEWER-RUNTIME-07, refuted: focusField is internal, and a dropped choice answer after leaving is a no-op because the leave dismisses the session. |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 426, item 1 | A platform.zlib actual for Apple and Linux | A measurement to make, not a deliverable. Related: #835. |
| 567, item 6 | A KiteSelectionMenuItem preset for keyboard copy | Left to the host by a documented design choice; a preset would make the viewer use the clipboard. Owner's call. |
| 746, item 4 | A shared 'after the view' scope for launches that leave composition | Design principle with no user-visible deliverable. Related: #365, #887. |
| 903, item 2 | A per-document decode budget across all streams | No memory accumulation shown: a sum budget would bound decode time, not memory. If decode time per document is a concern it belongs to the security batch. |
| 1834, item 5 | AES-128 (V4) on the create path | Documented design choice; R6 opens in Acrobat 9 and later. The isOwner half is #998. |
| 2466, item 4 | A docs line on Field.value reading as a number | Optional doc polish, no gap. |
| 2663, item 4 | JsObject.keys returns strings only | Not drafted: convenience with a documented workaround in the audit itself. |
| 3042, item 7 | Bundled speech for KiteReadAloud clips without audio | Documented design; the npt= half is #708. |
| 746, item 2 | Platform defaults for cacheBudgetBytes and maxBitmapLongSide | Needs device measurement to pick numbers, with no defect behind it; the trim hook #903 lets a host respond to pressure meanwhile. |
| 2663, item 6 | A memory budget or allocation caps for Rhino | Documented limitation with a documented alternative. |
