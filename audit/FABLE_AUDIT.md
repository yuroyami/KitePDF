# FABLE audit of KitePDF, KiteJS and ImageKodec

Date: 2026-11-04. Revisions audited: KitePDF `c4e7574` (main), KiteJS `5882331` (main), ImageKodec `aba04d0` (main). Audited by Claude (Fable 5.1) at the owner's request, as one file for the owner to check before any finding becomes a GitHub issue.

## How to read this file

- Each finding has a lane id (for example `VIEWER-API-02`), a severity, a confidence grade and a `Where` line with file paths and the line numbers the auditor read at the revision above. The text is written so that it can be pasted into an issue after the owner's check, in the house style of the repository's own issues (what happens, what should happen, how to reproduce, where).
- Severity: `critical` (data loss, crash in common use, silent wrong output in common use, a security hole), `high` (a feature does not work for a documented input, or a defect every user of a module meets), `medium` (wrong under a plausible combination of inputs, or a cost paid by every user that could be avoided), `low` (edge cases, hygiene with a concrete consequence), `info` (a design note with no defect yet).
- Confidence: `verified` means the auditor read the whole code path, or ran it in this container; `likely` means most of the path was read; `suspected` means the claim needs one more check before it is posted. The owner should start with the verified ones.
- `Orchestrator check`: findings the orchestrator re-read against the code after the lane reported them are marked so. A finding without that mark was read by one auditor only.
- Findings that belong to KiteJS or ImageKodec say so in their `Repo` line; they are to be fixed upstream, as the owner asked, even when the symptom shows in KitePDF.
- No em dashes are used anywhere in this file, as the three repositories require.

## Method and coverage

Twelve lanes and one cross-cutting pass, each reading the code end to end rather than sampling it:

| Lane | Scope |
| --- | --- |
| VIEWER-API | the public surface and parameter semantics of `kitepdf-compose-viewer`, every combination of layout, zoom, render spec, colours, callbacks and document type |
| VIEWER-RUNTIME | concurrency, coroutines, lifecycle, memory and per-frame cost of the viewer and of `kitepdf-media` |
| PDF-PARSE | bytes to objects: lexer, xref, repair, filters, inflate, zip, XML, crypto, the document caches, the open overloads, `kitepdf-net` |
| PDF-RENDER | content operations to pixels, spec fidelity, parity across the seven canvases, the differential oracle's blind spots |
| FONTS-TEXT | CFF, TrueType, Type 1, CMaps, encodings, GSUB and GPOS parsing, text extraction, search, bidi, hyphenation |
| PDF-WRITE | builder, editor, incremental and rewritten saves, redaction, forms, encryption on write, signatures |
| EPUB-LAYOUT | container, OPF, HTML parsing, CSS cascade and layout, pagination, fonts (WOFF2, Brotli, obfuscation), the layout budget, the conformance report |
| EPUB-SCRIPT | the DOM prelude and script session, Canvas 2D, URL and encoding, the Acrobat API, the script threads and the KiteJS contract as used |
| KITEJS | the KiteJS repository: the API, the QuickJS bindings (JNI, cinterop, Wasm), the Rhino port's platform behaviour, coroutines, packaging |
| IMAGEKODEC | the ImageKodec repository: every decoder for input trust and semantic correctness, colour management, the Compose and Coil bindings, and how KitePDF uses the library |
| PLATFORM | the native renderer canvases (AWT, Android, CoreGraphics, Canvas2D), the Skia renderer, SVG, XPS, CBZ, media, the core `expect`/`actual` code |
| BUILD-DOCS-TESTS | build and publishing metadata, API dumps, CI coverage, documentation truthfulness, test reliability across the three repositories |
| XCUT | the orchestrator's own pass over footprint, module boundaries, locks, caches, lifecycle and the public surface |

What ran in this container: the Kotlin Toolchain downloaded and built `kitepdf-pdf` and `kitepdf-core` for the JVM with their tests; a temporary JVM test measured the pure-Kotlin inflater against `java.util.zip` and the cold start of six hyphenation languages; the lanes ran small Python or JVM probes where a claim needed one, and say so in their evidence lines. The whole JVM suite, the differential sweep against MuPDF and PDFium, the Android host tests and the Apple, web and Linux native tests did not run here: `mutool`, PDFium, `hb-shape` and the Skiko runtime are not installed, and there is no device or simulator. Every claim about those platforms is from reading the platform source sets.

Known open issues (#427, #629 to #644) were excluded from the findings; where a lane found a deeper cause for one of them it says so.

Two companions sit next to this file: `FABLE_AUDIT_coverage.md` lists, lane by lane, what was read closely and found sound, so the owner can tell coverage from silence; `fixtures/` holds the small PDFs and scripts the probes used (the encrypted object-stream files that reproduce PDF-PARSE-02, and the writer lane's input and output files for PDF-WRITE-01 to 09), so each executed claim can be re-run. These fixtures are audit material, not corpus documents, and are not listed in corpus/manifest.json on purpose.

## Executive summary

KitePDF is in better shape than a 130 000-line engine written in a few months has any right to be: the lanes read the content-stream interpreter, the colour and function code, the font parsers, the EPUB cascade, the Brotli and WOFF2 decoders, the zip reader, the XML parser and the caches end to end and found the spec arithmetic right far more often than wrong (the coverage file lists what was read and found sound). The defects that remain cluster in a few places, and the owner should read them in this order.

**Three things to fix before anything else.**

1. Encrypted PDFs that keep their objects in object streams do not open (PDF-PARSE-02, critical, reproduced). `decodeObjectStream` inflates the object stream without decrypting it first, so every file saved by qpdf, pikepdf or Acrobat with encryption and compression fails with "Repair found no catalog that leads to a page tree". The classic-xref form of the same file opens. No fixture in the repository has both encryption and object streams, which is why nothing caught it.
2. The write side leaks what it promised to remove or protect (PDF-WRITE-01 to 05, all high, all executed by the lane): redaction keeps `/ActualText` and rewrites a shared form in place so other pages lose content; `graftPage` carries the whole source document into the target through a link's `/Dest`; signature trust accepts a chain through an end-entity certificate; and every standard-14 font the writer emits lacks `/Encoding`, so "café" renders "cafØ" in every reader including KitePDF.
3. The Compose viewer has two defects a reader meets in the first minute (VIEWER-API-01 and 02, high): the pager layouts give each page slot its own rasterizer, so the 96 MB page cache never hits on a swipe back; and with `panEnabled = false` (which `KiteZoomSpec.Disabled` sets) a page that overflows the viewport can neither be moved nor turned by touch, which `KiteDocLayout.forDocument` produces for every scrolled-doc EPUB.

**Where the engine deviates from the specs it cites.** The renderer passes its own oracle sweep because the sweep cannot see what is missing: tiling patterns with more than 20 000 cells paint nothing (PDF-RENDER-02, reproduced), a stray `d1` turns colour off for the rest of the page (PDF-RENDER-12, reproduced), an Indexed space with a large `/hival` throws out of the render (PDF-RENDER-01, reproduced), JPX images ignore the PDF colour space (PDF-RENDER-07), indirect scalars in ExtGState, pattern and shading dictionaries are read as absent (PDF-RENDER-11), the Skia canvas uses other luminosity weights than the six other canvases (PDF-RENDER-08), CFF glyphs composed with `seac` draw nothing (FONTS-TEXT-01), `/MissingWidth` is read from the wrong dictionary (FONTS-TEXT-04), a unitless `line-height` is inherited as a length so headings overlap (EPUB-LAYOUT-01), and the pure-Kotlin inflater loses a stored block after a dynamic block whose end code is short (PDF-PARSE-01, reproduced with a crafted stream), which only the JVM's zlib fast path hides.

**Lenient salvage has holes.** The project's first rule is that a corrupt object becomes a skip, never a page failure. The audit found a dozen inputs that break it: a bad `/CIDToGIDMap` stream, `/Encrypt /Length 0`, an unbounded `/hival`, a negative WOFF length, a `colspan` of a billion, an `OutOfMemoryError` from a document script (the guard catches `Exception`, not `Throwable`, the same mistake #219 fixed in the rasterizer), and a script-built DOM deeper than the parser's limit.

**Design and footprint.** Three structural findings cost every consumer: 3.1 MB of hyphenation tables live in `kitepdf-core` though only the EPUB layout reads them (XCUT-01); the Compose viewer hard-depends on `kitepdf-epub`, so a PDF-only app ships the whole EPUB stack (XCUT-02); and every artifact is Java 21 bytecode while KiteJS and ImageKodec target Java 11, with no document saying so (XCUT-03). Documents have no `close()`, so a book's remote fetches run on after the reader is gone (XCUT-06). The published jars carry none of the licence texts the bundled fonts, patterns and ports require (BUILD-DOCS-TESTS-01).

**Upstream.** KiteJS: with an instruction budget set, Rhino never consults `interruptWhen` until the budget is spent, so cancellation and deadlines are ignored (KITEJS-01); the QuickJS handle sweep is quadratic (KITEJS-04); `JsValue` is a value class and so is invisible from Swift (KITEJS-05). ImageKodec: the inflater's growth loop never ends past 1 GiB of output, which a 16-bit PNG at the pixel ceiling asks for (IMAGEKODEC-01).

**Tests and CI.** The gate is strong where it runs and silent where it does not: the shaping, signature and embedded-font oracles never run in CI and nothing counts the skips (BUILD-DOCS-TESTS-06); the differential baseline's margins let a 40 by 40 pixel element vanish unseen and only the AWT canvas is swept (PDF-RENDER-22); three published modules are tested on the JVM alone (BUILD-DOCS-TESTS-17); the writer's only oracle for encrypted and object-stream output is KitePDF itself (PDF-WRITE-17).

**How to use this file.** Start with the critical and high entries in the index; each has a `How to reproduce` line and most were re-checked by the orchestrator (Appendix A says how, and names the five that were run rather than read). The medium entries are the bulk of the engineering work; the low ones are hygiene with a concrete consequence. The duplicates section says which of the overlapping findings to post. Everything in the KiteJS and ImageKodec lanes, and the two ImageKodec-side halves of PDF-RENDER-07 and IMAGEKODEC-03, are to be fixed upstream.

## Counts

| Severity | Findings |
| --- | ---: |
| critical | 1 |
| high | 19 |
| medium | 84 |
| low | 106 |
| info | 8 |
| total | 218 |

By repository: ImageKodec 3, KiteJS 15, KiteJS, 1, KitePDF 198, KitePDF, 1. Orchestrator re-checked 52 findings line by line (Appendix A).

## Index of findings by severity

### Critical

| Id | Title | Repo | Kind | Confidence | Orchestrator check |
| --- | --- | --- | --- | --- | --- |
| [PDF-PARSE-02](#pdf-parse-02-object-streams-are-inflated-without-decryption-so-an-encrypted-pdf-that-stores-objects-in-object-streams-cannot-be-opened) | Object streams are inflated without decryption, so an encrypted PDF that stores objects in object streams cannot be opened | KitePDF | bug | verified | no |

### High

| Id | Title | Repo | Kind | Confidence | Orchestrator check |
| --- | --- | --- | --- | --- | --- |
| [VIEWER-API-01](#viewer-api-01-the-pager-layouts-give-every-page-slot-its-own-rasterizer-so-the-page-bitmap-cache-never-hits-for-a-page-that-left-the-window) | The pager layouts give every page slot its own rasterizer, so the page-bitmap cache never hits for a page that left the window | KitePDF | performance | memory | likely | yes: confirmed |
| [VIEWER-API-02](#viewer-api-02-with-panenabled--false-a-page-that-overflows-the-viewport-cannot-be-moved-or-turned-by-touch-kitezoomspecdisabled-with-a-scrolled-doc-epub-strands-the-reader) | With `panEnabled = false` a page that overflows the viewport cannot be moved or turned by touch; `KiteZoomSpec.Disabled` with a scrolled-doc EPUB strands the reader | KitePDF | bug | design | verified | yes: confirmed |
| [PDF-PARSE-01](#pdf-parse-01-inflates-aligntobyte-discards-buffered-whole-bytes-so-a-stored-block-after-a-huffman-block-loses-data-on-jswasmnative) | Inflate's `alignToByte` discards buffered whole bytes, so a stored block after a Huffman block loses data on JS/Wasm/Native | KitePDF | bug | verified | no |
| [PDF-RENDER-01](#pdf-render-01-an-indexed-colour-space-allocates-a-palette-table-of-hival-plus-one-entries-with-no-upper-bound) | An Indexed colour space allocates a palette table of hival plus one entries with no upper bound | KitePDF | memory | verified | yes: confirmed |
| [FONTS-TEXT-01](#fonts-text-01-a-type-2-charstring-that-composes-an-accented-glyph-with-endchar-draws-nothing-because-the-cff-font-never-hands-the-interpreter-a-seac-renderer) | A Type 2 charstring that composes an accented glyph with `endchar` draws nothing, because the CFF font never hands the interpreter a seac renderer | KitePDF | bug | verified | yes: confirmed |
| [PDF-WRITE-01](#pdf-write-01-redaction-keeps-the-actualtext-of-a-marked-content-sequence-whose-text-it-removed-and-kitepdfs-own-extractor-reads-it-back) | Redaction keeps the `/ActualText` of a marked-content sequence whose text it removed, and KitePDF's own extractor reads it back | KitePDF | security | verified | yes: confirmed by inflating the probe output |
| [PDF-WRITE-02](#pdf-write-02-redacting-a-region-on-one-page-rewrites-a-form-xobject-in-place-so-every-other-page-that-draws-the-same-form-loses-that-content-too) | Redacting a region on one page rewrites a form XObject in place, so every other page that draws the same form loses that content too | KitePDF | bug | verified | yes: confirmed by the probe output |
| [PDF-WRITE-03](#pdf-write-03-signature-trust-accepts-a-chain-through-an-end-entity-certificate-because-the-chain-builder-never-checks-basicconstraints-or-keyusage) | Signature trust accepts a chain through an end-entity certificate, because the chain builder never checks BasicConstraints or KeyUsage | KitePDF | security | verified | yes: confirmed |
| [PDF-WRITE-04](#pdf-write-04-graftpage-appendpage-and-mergedocument-copy-every-object-reachable-from-the-page-so-a-link-or-a-widget-drags-unrelated-pages-of-the-source-into-the-target) | `graftPage`, `appendPage` and `mergeDocument` copy every object reachable from the page, so a link or a widget drags unrelated pages of the source into the target | KitePDF | bug | verified | yes: confirmed by the probe output |
| [PDF-WRITE-05](#pdf-write-05-every-standard-14-font-dictionary-the-writer-emits-has-no-encoding-while-show-strings-are-latin-1-bytes-so-café-renders-cafø-in-every-reader-including-kitepdf) | Every standard-14 font dictionary the writer emits has no `/Encoding`, while show strings are Latin-1 bytes, so "café" renders "cafØ" in every reader including KitePDF | KitePDF | bug | verified | yes: confirmed |
| [EPUB-LAYOUT-01](#epub-layout-01-a-unitless-line-height-is-inherited-as-a-length-so-a-heading-under-body--line-height-15--gets-lines-shorter-than-its-own-glyphs) | A unitless `line-height` is inherited as a length, so a heading under `body { line-height: 1.5 }` gets lines shorter than its own glyphs | KitePDF | bug | verified | yes: confirmed |
| [EPUB-LAYOUT-02](#epub-layout-02-colspan-rowspan-and-col-span-are-read-without-a-ceiling-so-one-attribute-hangs-or-kills-the-layout) | `colspan`, `rowspan` and `<col span>` are read without a ceiling, so one attribute hangs or kills the layout | KitePDF | security | verified | yes: confirmed |
| [EPUB-LAYOUT-03](#epub-layout-03-a-woff-10-font-with-a-negative-table-length-throws-out-of-preparechapter-and-the-throw-repeats-on-every-chapter) | A WOFF 1.0 font with a negative table length throws out of `prepareChapter`, and the throw repeats on every chapter | KitePDF | bug | verified | yes: confirmed |
| [EPUB-SCRIPT-04](#epub-script-04-a-script-can-build-a-tree-deeper-than-the-parsers-nesting-limit-and-the-layouts-recursion-then-overflows-the-stack) | A script can build a tree deeper than the parser's nesting limit, and the layout's recursion then overflows the stack | KitePDF | security | likely | no |
| [KITEJS-01](#kitejs-01-rhino-never-asks-interruptwhen-once-an-instructionbudget-is-set-so-cancellation-and-deadlines-are-silently-ignored-until-the-budget-runs-out) | Rhino never asks `interruptWhen` once an `instructionBudget` is set, so cancellation and deadlines are silently ignored until the budget runs out | KiteJS | bug | verified | yes: confirmed |
| [IMAGEKODEC-01](#imagekodec-01-inflate-loops-forever-once-its-output-passes-1-gib-with-no-size-hint-which-a-16-bit-rgba-png-at-the-pixel-ceiling-asks-for) | Inflate loops forever once its output passes 1 GiB with no size hint, which a 16-bit RGBA PNG at the pixel ceiling asks for | ImageKodec | security | verified | yes: confirmed by reading the arithmetic |
| [PLATFORM-01](#platform-01-an-svg-use-tree-that-fans-out-renders-in-k16-steps-with-no-work-budget-to-stop-it) | An SVG `<use>` tree that fans out renders in k^16 steps, with no work budget to stop it | KitePDF | security | verified | yes: confirmed |
| [PLATFORM-02](#platform-02-the-svg-path-parser-misreads-the-compact-syntax-svgo-writes-arc-flags-without-separators-give-a-nan-curve-and-55-loses-both-numbers) | The SVG path parser misreads the compact syntax SVGO writes: arc flags without separators give a NaN curve, and `.5.5` loses both numbers | KitePDF | bug | verified | yes: confirmed |
| [PLATFORM-03](#platform-03-an-svg-filter-converts-the-whole-raster-step-to-floats-before-it-shrinks-to-its-pixel-budget-so-a-full-page-filter-allocates-up-to-640-mb-on-skia-and-awt-and-256-mb-on-the-compose-viewer) | An SVG filter converts the whole raster step to floats before it shrinks to its pixel budget, so a full-page filter allocates up to 640 MB on Skia and AWT and 256 MB on the Compose viewer | KitePDF | memory | verified | yes: confirmed |

### Medium

| Id | Title | Repo | Kind | Confidence | Orchestrator check |
| --- | --- | --- | --- | --- | --- |
| [XCUT-01](#xcut-01-every-consumer-of-kitepdf-core-carries-31-mb-of-hyphenation-tables-that-only-the-epub-layout-uses) | Every consumer of kitepdf-core carries 3.1 MB of hyphenation tables that only the EPUB layout uses | KitePDF | design | verified | no |
| [XCUT-02](#xcut-02-the-compose-viewer-hard-depends-on-the-epub-engine-so-a-pdf-only-compose-app-ships-the-whole-epub-stack) | The Compose viewer hard-depends on the EPUB engine, so a PDF-only Compose app ships the whole EPUB stack | KitePDF | design | verified | no |
| [XCUT-03](#xcut-03-kitepdf-publishes-java-21-bytecode-and-no-document-says-so) | KitePDF publishes Java 21 bytecode and no document says so | KitePDF | api | verified | no |
| [XCUT-06](#xcut-06-a-document-cannot-be-closed-so-a-books-remote-fetches-and-their-scope-outlive-the-readers-interest) | A document cannot be closed, so a book's remote fetches and their scope outlive the reader's interest | KitePDF | design | verified | no |
| [VIEWER-API-03](#viewer-api-03-reverselayout-is-relative-to-locallayoutdirection-for-the-swipe-but-absolute-for-the-spread-order-and-the-document-direction) | `reverseLayout` is relative to `LocalLayoutDirection` for the swipe but absolute for the spread order and the document direction | KitePDF | bug | api | verified | yes: confirmed |
| [VIEWER-API-04](#viewer-api-04-userscrollenabled--false-does-not-stop-the-drag-past-the-edge-of-a-zoomed-page-from-turning-it) | `userScrollEnabled = false` does not stop the drag past the edge of a zoomed page from turning it | KitePDF | bug | verified | yes: confirmed |
| [VIEWER-API-05](#viewer-api-05-onpagerendered-never-fires-for-a-page-the-strip-drew-ahead-so-with-the-default-prefetchpages--1-most-pages-of-a-slowly-read-document-are-never-reported) | `onPageRendered` never fires for a page the strip drew ahead, so with the default `prefetchPages = 1` most pages of a slowly read document are never reported | KitePDF | docs | design | verified | yes: confirmed by the module's own test (ContinuousPrefetchSceneTest.kt |
| [VIEWER-API-06](#viewer-api-06-two-kitedocview-composables-on-one-state-fight-over-the-adapter-the-zoom-range-the-pan-axes-and-the-kept-chapters-and-nothing-documents-or-refuses-it) | Two `KiteDocView` composables on one state fight over the adapter, the zoom range, the pan axes and the kept chapters, and nothing documents or refuses it | KitePDF | design | api | verified | yes: confirmed |
| [VIEWER-API-09](#viewer-api-09-the-pages-accessibility-nodes-are-rebuilt-on-every-form-revision-with-an-on²-widget-scan-and-the-page-container-carries-the-image-role-over-text-links-and-fields) | The page's accessibility nodes are rebuilt on every form revision, with an O(n²) widget scan, and the page container carries the Image role over text, links and fields | KitePDF | performance | design | likely | no |
| [VIEWER-RUNTIME-01](#viewer-runtime-01-an-exact-group-composite-allocates-about-twenty-bytes-a-pixel-of-java-arrays-so-a-zoomed-page-with-a-non-isolated-blending-group-runs-out-of-memory-on-a-phone) | An exact group composite allocates about twenty bytes a pixel of Java arrays, so a zoomed page with a non-isolated blending group runs out of memory on a phone | KitePDF | memory | likely | no |
| [VIEWER-RUNTIME-02](#viewer-runtime-02-a-vectorized-page-of-a-chapter-whose-layout-throws-lays-the-chapter-out-again-on-every-frame) | A Vectorized page of a chapter whose layout throws lays the chapter out again on every frame | KitePDF | bug | likely | yes: confirmed |
| [VIEWER-RUNTIME-03](#viewer-runtime-03-a-document-script-that-throws-an-error-ends-the-host-app-because-the-script-guard-catches-exception-and-the-lanes-have-no-handler) | A document script that throws an Error ends the host app, because the script guard catches Exception and the lanes have no handler | KitePDF | concurrency | verified | yes: confirmed |
| [VIEWER-RUNTIME-04](#viewer-runtime-04-kitemediaoverlay-lays-a-dropped-chapter-out-on-the-ui-thread-by-reading-the-pages-media-in-composition) | KiteMediaOverlay lays a dropped chapter out on the UI thread by reading the page's media in composition | KitePDF | performance | likely | yes: confirmed |
| [VIEWER-RUNTIME-05](#viewer-runtime-05-on-the-web-a-bookmark-a-table-of-contents-entry-or-a-page-point-lays-a-chapter-out-in-one-piece-on-the-ui-thread) | On the web, a bookmark, a table of contents entry or a page point lays a chapter out in one piece on the UI thread | KitePDF | performance | likely | no |
| [VIEWER-RUNTIME-06](#viewer-runtime-06-kitethumbnailstrip-keeps-the-previous-document-alive-after-a-state-swap-through-its-bitmap-cache-keys) | KiteThumbnailStrip keeps the previous document alive after a state swap through its bitmap cache keys | KitePDF | memory | verified | yes: confirmed |
| [PDF-PARSE-03](#pdf-parse-03-v5v6-decryption-ignores-an-identity-crypt-filter-on-stmf-or-strf) | V5/V6 decryption ignores an `/Identity` crypt filter on `/StmF` or `/StrF` | KitePDF | bug | verified | yes: confirmed |
| [PDF-PARSE-04](#pdf-parse-04-a-crafted-encrypt-with-length-0-or-negative-throws-a-non-format-exception-out-of-open-on-the-repair-path) | A crafted `/Encrypt` with `/Length 0` (or negative) throws a non-format exception out of `open()` on the repair path | KitePDF | bug | verified | yes: confirmed in structure |
| [PDF-PARSE-05](#pdf-parse-05-xmpmetadataxml-and-pdfattachmentbytesprovider-call-filterchaindecode-unguarded-so-a-corrupt-stream-throws-from-apis-documented-to-be-lenient) | `xmpMetadataXml` and `PdfAttachment.bytesProvider` call `FilterChain.decode` unguarded, so a corrupt stream throws from APIs documented to be lenient | KitePDF | bug | verified | no |
| [PDF-PARSE-06](#pdf-parse-06-a-type0-font-with-an-undecodable-cidtogidmap-stream-throws-out-of-loadfonts-aborting-the-whole-page) | A Type0 font with an undecodable `/CIDToGIDMap` stream throws out of `loadFonts`, aborting the whole page | KitePDF | bug | verified | no |
| [PDF-PARSE-07](#pdf-parse-07-an-object-stream-with-one-malformed-member-drops-every-object-it-holds) | An object stream with one malformed member drops every object it holds | KitePDF | bug | verified | no |
| [PDF-RENDER-02](#pdf-render-02-a-tiling-pattern-with-more-than-20000-cells-on-the-page-paints-nothing-at-all) | A tiling pattern with more than 20000 cells on the page paints nothing at all | KitePDF | bug | verified | yes: confirmed |
| [PDF-RENDER-04](#pdf-render-04-every-tiling-fill-re-parses-its-cell-and-replays-the-content-once-per-tile-pushing-a-clip-per-tile) | Every tiling fill re-parses its cell and replays the content once per tile, pushing a clip per tile | KitePDF | performance | verified | no |
| [PDF-RENDER-05](#pdf-render-05-an-image-of-more-than-40-million-pixels-draws-as-a-grey-placeholder-whenever-it-is-shown-at-half-its-resolution-or-more) | An image of more than 40 million pixels draws as a grey placeholder whenever it is shown at half its resolution or more | KitePDF | memory | verified | no |
| [PDF-RENDER-06](#pdf-render-06-a-pages-whole-content-stream-is-parsed-into-a-list-of-operations-before-the-first-one-is-drawn-at-about-100-bytes-per-operation) | A page's whole content stream is parsed into a list of operations before the first one is drawn, at about 100 bytes per operation | KitePDF | memory | verified | no |
| [PDF-RENDER-07](#pdf-render-07-a-jpxdecode-image-ignores-the-pdfs-colorspace-and-converts-cmyk-with-a-formula-no-other-cmyk-path-uses) | A JPXDecode image ignores the PDF's /ColorSpace and converts CMYK with a formula no other CMYK path uses | KitePDF | bug | verified | yes: confirmed |
| [PDF-RENDER-08](#pdf-render-08-skiacanvas-turns-a-luminosity-soft-mask-into-alpha-with-rec-709-weights-while-the-other-five-canvases-the-core-raster-canvas-and-mupdf-use-030-059-011) | SkiaCanvas turns a luminosity soft mask into alpha with Rec. 709 weights while the other five canvases, the core raster canvas and MuPDF use 0.30, 0.59, 0.11 | KitePDF | bug | likely | yes: confirmed on the KitePDF side |
| [PDF-RENDER-09](#pdf-render-09-skiacanvas-and-androidnativecanvas-open-a-full-surface-layer-for-every-transparency-group-and-every-soft-masked-paint-ignoring-the-bbox-they-are-given) | SkiaCanvas and AndroidNativeCanvas open a full-surface layer for every transparency group and every soft-masked paint, ignoring the bbox they are given | KitePDF | performance | verified | no |
| [PDF-RENDER-11](#pdf-render-11-shading-pattern-function-and-extgstate-parsers-read-their-entries-with-the-resolver-less-accessors-so-an-indirect-value-is-treated-as-absent) | Shading, pattern, function and ExtGState parsers read their entries with the resolver-less accessors, so an indirect value is treated as absent | KitePDF | bug | verified | yes: confirmed in substance, citation corrected |
| [PDF-RENDER-13](#pdf-render-13-an-image-mask-painted-with-a-pattern-as-the-nonstroking-colour-paints-a-flat-colour-instead-of-the-pattern) | An image mask painted with a pattern as the nonstroking colour paints a flat colour instead of the pattern | KitePDF | bug | verified | no |
| [PDF-RENDER-22](#pdf-render-22-the-per-page-baseline-cannot-see-a-small-element-vanish-and-the-sweep-compares-one-canvas-with-grey-only-fixtures-for-the-features-that-differ-between-canvases) | The per-page baseline cannot see a small element vanish, and the sweep compares one canvas with grey-only fixtures for the features that differ between canvases | KitePDF | test-gap | verified | no |
| [FONTS-TEXT-02](#fonts-text-02-a-composite-fonts-tounicode-text-is-re-segmented-by-the-tounicodes-own-codespace-so-a-one-byte-or-missing-codespace-turns-every-two-byte-code-into--plus-its-text) | A composite font's ToUnicode text is re-segmented by the ToUnicode's own codespace, so a one-byte or missing codespace turns every two-byte code into "�" plus its text | KitePDF | bug | verified | yes: confirmed in structure |
| [FONTS-TEXT-03](#fonts-text-03-an-embedded-encoding-cmap-streams-usecmap-and-usecmap-are-ignored-so-a-cmap-that-only-overrides-a-predefined-one-maps-every-other-code-to-cid--code) | An embedded Encoding CMap stream's `/UseCMap` and `usecmap` are ignored, so a CMap that only overrides a predefined one maps every other code to CID = code | KitePDF | bug | verified | no |
| [FONTS-TEXT-04](#fonts-text-04-missingwidth-is-read-from-the-font-dictionary-instead-of-the-font-descriptor-so-it-never-applies) | `/MissingWidth` is read from the font dictionary instead of the font descriptor, so it never applies | KitePDF | bug | verified | yes: confirmed |
| [FONTS-TEXT-05](#fonts-text-05-an-embedded-type-1-program-is-sliced-by-length1-and-length2-as-the-file-states-them-so-a-wrong-length1-or-a-missing-length2-silently-loses-every-glyph) | An embedded Type 1 program is sliced by `/Length1` and `/Length2` as the file states them, so a wrong `/Length1` (or a missing `/Length2`) silently loses every glyph | KitePDF | bug | verified | no |
| [FONTS-TEXT-06](#fonts-text-06-a-nonsymbolic-truetype-font-that-has-only-a-10-cmap-maps-winansi-codes-above-0x7f-to-the-wrong-glyph-because-the-name-is-looked-up-as-a-unicode-code-point-instead-of-a-mac-roman-code) | A nonsymbolic TrueType font that has only a (1,0) cmap maps WinAnsi codes above 0x7F to the wrong glyph, because the name is looked up as a Unicode code point instead of a Mac Roman code | KitePDF | bug | likely | no |
| [FONTS-TEXT-07](#fonts-text-07-a-broken-cidtogidmap-stream-throws-out-of-pdffontfrom-and-takes-the-whole-page-render-and-text-extraction-with-it) | A broken `/CIDToGIDMap` stream throws out of `PdfFont.from` and takes the whole page render and text extraction with it | KitePDF | bug | verified | yes: confirmed |
| [FONTS-TEXT-08](#fonts-text-08-table-counts-read-from-the-font-file-size-allocations-before-any-bound-a-cmap-format-12-with-a-huge-numgroups-a-cff2-index-count-or-a-gpos-class-matrix-can-demand-gigabytes-from-a-few-bytes) | Table counts read from the font file size allocations before any bound: a cmap format 12 with a huge `numGroups`, a CFF2 INDEX count, or a GPOS class matrix can demand gigabytes from a few bytes | KitePDF | memory | verified | yes: confirmed for the GPOS class matrix (OpenTypeKern.kt |
| [FONTS-TEXT-09](#fonts-text-09-gsub-nested-lookups-have-a-depth-limit-but-no-operation-budget-so-a-self-referencing-context-lookup-fans-out-exponentially) | GSUB nested lookups have a depth limit but no operation budget, so a self-referencing context lookup fans out exponentially | KitePDF | security | verified | no |
| [FONTS-TEXT-10](#fonts-text-10-text-strings-without-a-bom-are-decoded-as-latin-1-rather-than-pdfdocencoding-and-the-pdf-20-utf-8-bom-is-not-recognised) | Text strings without a BOM are decoded as Latin-1 rather than PDFDocEncoding, and the PDF 2.0 UTF-8 BOM is not recognised | KitePDF | bug | verified | no |
| [FONTS-TEXT-11](#fonts-text-11-cfffont-keeps-the-whole-font-program-alive-in-an-unused-reader-field-and-retainedbytes-counts-neither-it-nor-the-cmap-and-cidtogid-tables) | `CffFont` keeps the whole font program alive in an unused `reader` field, and `retainedBytes` counts neither it nor the CMap and CIDToGID tables | KitePDF | memory | verified | no |
| [FONTS-TEXT-22](#fonts-text-22-no-test-reaches-the-charstring-interpreters-hard-parts-and-no-fixture-carries-a-seac-a-missingwidth-a-10-only-nonsymbolic-cmap-or-a-codespace-less-tounicode) | No test reaches the charstring interpreters' hard parts, and no fixture carries a seac, a `MissingWidth`, a (1,0)-only nonsymbolic cmap or a codespace-less ToUnicode | KitePDF | test-gap | verified | no |
| [PDF-WRITE-06](#pdf-write-06-the-content-string-encoder-turns-every-winansi-only-character-the-euro-sign-the-typographic-quotes-the-en-and-em-dashes-the-ellipsis-the-bullet-the-trademark-sign-into--and-the-width-function-measures-them-as-500-units) | The content-string encoder turns every WinAnsi-only character (the euro sign, the typographic quotes, the en and em dashes, the ellipsis, the bullet, the trademark sign) into `?`, and the width function measures them as 500 units | KitePDF | bug | verified | yes: confirmed |
| [PDF-WRITE-07](#pdf-write-07-pdfsignerpreparesignature-builds-its-page-and-catalog-updates-from-the-base-document-so-an-edit-staged-earlier-in-the-same-editor-is-thrown-away) | `PdfSigner.prepareSignature` builds its page and catalog updates from the base document, so an edit staged earlier in the same editor is thrown away | KitePDF | bug | verified | no |
| [PDF-WRITE-08](#pdf-write-08-pdfsigner-replaces-a-direct-acroform-dictionary-with-a-new-one-dropping-the-existing-fields-from-fields-together-with-da-dr-and-needappearances) | `PdfSigner` replaces a direct `/AcroForm` dictionary with a new one, dropping the existing fields from `/Fields` together with `/DA`, `/DR` and `/NeedAppearances` | KitePDF | bug | verified | no |
| [PDF-WRITE-09](#pdf-write-09-page-operations-flatten-the-page-tree-and-bake-only-mediabox-resources-and-rotate-so-a-page-that-inherited-cropbox-loses-its-crop) | Page operations flatten the page tree and bake only `/MediaBox`, `/Resources` and `/Rotate`, so a page that inherited `/CropBox` loses its crop | KitePDF | bug | verified | no |
| [PDF-WRITE-10](#pdf-write-10-setinfo-changes-info-and-leaves-the-xmp-metadata-stream-and-moddate-untouched-so-readers-that-prefer-xmp-keep-showing-the-old-title) | `setInfo` changes `/Info` and leaves the XMP `/Metadata` stream and `/ModDate` untouched, so readers that prefer XMP keep showing the old title | KitePDF | bug | verified | no |
| [PDF-WRITE-11](#pdf-write-11-redaction-leaves-five-more-copies-of-the-content-that-docseditingmd-does-not-list-structure-tree-text-the-page-thumbnail-tiling-pattern-cells-type-3-glyph-procedures-and-private-data) | Redaction leaves five more copies of the content that docs/editing.md does not list: structure-tree text, the page thumbnail, tiling-pattern cells, Type 3 glyph procedures and private data | KitePDF | docs | verified | no |
| [PDF-WRITE-12](#pdf-write-12-setbuttonvalue-treats-the-export-value-as-the-appearance-state-name-so-a-radio-group-that-uses-opt-states-named-0-1-cannot-be-set-by-its-export-value) | `setButtonValue` treats the export value as the appearance-state name, so a radio group that uses `/Opt` (states named `/0`, `/1`) cannot be set by its export value | KitePDF | bug | likely | no |
| [PDF-WRITE-13](#pdf-write-13-the-text-field-appearance-ignores-the-da-font-and-the-forms-dr-draws-password-fields-in-clear-drops-the-widgets-border-and-background-and-draws-a-list-box-as-one-line) | The text-field appearance ignores the `/DA` font and the form's `/DR`, draws password fields in clear, drops the widget's border and background, and draws a list box as one line | KitePDF | design | verified | no |
| [PDF-WRITE-17](#pdf-write-17-the-writers-only-oracles-for-encrypted-output-object-stream-output-and-non-ascii-field-values-are-kitepdf-itself) | The writer's only oracles for encrypted output, object-stream output and non-ASCII field values are KitePDF itself | KitePDF | test-gap | verified | no |
| [EPUB-LAYOUT-04](#epub-layout-04-a-source-line-break-between-two-cjk-characters-becomes-a-space-and-justification-stretches-it) | A source line break between two CJK characters becomes a space, and justification stretches it | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-05](#epub-layout-05-vertical-margins-never-collapse-between-a-box-and-its-first-or-last-child-nor-through-an-empty-block-nor-with-negative-values) | Vertical margins never collapse between a box and its first or last child, nor through an empty block, nor with negative values | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-06](#epub-layout-06-percentages-resolve-against-the-page-content-box-not-the-containing-block-so-table--width-100--inside-an-indented-box-runs-past-its-container) | Percentages resolve against the page content box, not the containing block, so `table { width: 100% }` inside an indented box runs past its container | KitePDF | design | verified | no |
| [EPUB-LAYOUT-07](#epub-layout-07-display-inline-block-is-laid-out-as-plain-inline-content-no-box-no-width-padding-or-border-and-it-breaks-across-lines) | `display: inline-block` is laid out as plain inline content: no box, no width, padding or border, and it breaks across lines | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-08](#epub-layout-08-every-media-block-applies-whatever-its-query-says-and-link-media-is-ignored-so-print-and-dark-mode-rules-restyle-the-page) | Every `@media` block applies whatever its query says, and `<link media>` is ignored, so print and dark-mode rules restyle the page | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-09](#epub-layout-09-htmls-presentational-attributes-are-not-mapped-align-center-font-bgcolor-td-width-img-align) | HTML's presentational attributes are not mapped: `align`, `<center>`, `font`, `bgcolor`, `td width`, `img align` | KitePDF | capability | verified | no |
| [EPUB-LAYOUT-10](#epub-layout-10-pages-keep-the-whole-box-tree-through-decoboxes-hitboxes-and-linkboxes-which-the-layout-budget-does-not-count) | Pages keep the whole box tree through `decoBoxes`, `hitBoxes` and `linkBoxes`, which the layout budget does not count | KitePDF | memory | likely | no |
| [EPUB-SCRIPT-01](#epub-script-01-a-field-script-that-fails-to-parse-or-is-stopped-hands-the-previous-events-result-to-the-field) | A field script that fails to parse or is stopped hands the previous event's result to the field | KitePDF | bug | verified | yes: confirmed in structure |
| [EPUB-SCRIPT-02](#epub-script-02-afsimple_calculate-over-a-field-that-has-kids-sums-nothing-because-getfield-finds-terminal-fields-only-and-getarray-answers-the-field-itself) | AFSimple_Calculate over a field that has kids sums nothing, because getField finds terminal fields only and getArray answers the field itself | KitePDF | bug | verified | yes: confirmed |
| [EPUB-SCRIPT-03](#epub-script-03-the-viewer-never-shows-a-format-scripts-result-and-formatall-runs-every-format-script-for-nothing-on-each-commit) | The viewer never shows a format script's result, and formatAll runs every format script for nothing on each commit | KitePDF | design | verified | yes: confirmed in structure |
| [EPUB-SCRIPT-05](#epub-script-05-a-node-a-script-makes-is-never-freed-while-its-chapter-is-live) | A node a script makes is never freed while its chapter is live | KitePDF | memory | verified | no |
| [EPUB-SCRIPT-06](#epub-script-06-localstorage-and-sessionstorage-have-no-quota-so-a-script-can-grow-the-hosts-memory-without-limit) | localStorage and sessionStorage have no quota, so a script can grow the host's memory without limit | KitePDF | memory | verified | no |
| [EPUB-SCRIPT-08](#epub-script-08-closing-a-pdfscriptrunner-from-one-of-its-own-callbacks-throws-out-of-close-and-leaks-the-engine) | Closing a PdfScriptRunner from one of its own callbacks throws out of close() and leaks the engine | KitePDF | concurrency | verified | no |
| [EPUB-SCRIPT-09](#epub-script-09-close-blocks-its-caller-until-the-running-script-stops-which-deadlocks-a-ui-thread-that-the-script-is-waiting-on) | close() blocks its caller until the running script stops, which deadlocks a UI thread that the script is waiting on | KitePDF | design | likely | no |
| [EPUB-SCRIPT-10](#epub-script-10-every-engine-may-allocate-512-mb-and-nothing-in-either-policy-can-lower-it-so-eight-live-chapters-plus-their-frames-may-take-gigabytes) | Every engine may allocate 512 MB and nothing in either policy can lower it, so eight live chapters plus their frames may take gigabytes | KitePDF | memory | verified | no |
| [KITEJS-02](#kitejs-02-the-instruction-budget-the-interrupt-hook-and-a-wall-clock-deadline-do-not-reach-a-long-loop-inside-a-built-in-on-either-engine) | The instruction budget, the interrupt hook and a wall-clock deadline do not reach a long loop inside a built-in, on either engine | KiteJS | design | likely | no |
| [KITEJS-03](#kitejs-03-the-default-maxstacksize-of-512-kib-is-the-whole-stack-of-an-apple-secondary-thread-so-deep-recursion-there-is-a-crash-not-the-promised-rangeerror) | The default `maxStackSize` of 512 KiB is the whole stack of an Apple secondary thread, so deep recursion there is a crash, not the promised RangeError | KiteJS | design | likely | no |
| [KITEJS-04](#kitejs-04-releasing-collected-quickjs-handles-is-quadratic-in-the-number-collected-between-two-calls) | Releasing collected QuickJS handles is quadratic in the number collected between two calls | KiteJS | performance | verified | yes: confirmed |
| [KITEJS-05](#kitejs-05-jsvalue-is-a-value-class-so-the-whole-value-reading-api-vanishes-from-swift-and-objective-c) | `JsValue` is a value class, so the whole value-reading API vanishes from Swift and Objective-C | KiteJS | api | verified | yes: confirmed |
| [KITEJS-06](#kitejs-06-kitejs-coroutines-is-tested-with-rhino-only-so-asynckitejsquickjs-never-runs-in-ci-on-any-platform) | `kitejs-coroutines` is tested with Rhino only, so `asyncKiteJs(QuickJs)` never runs in CI on any platform | KiteJS | test-gap | verified | no |
| [IMAGEKODEC-02](#imagekodec-02-epub-cbz-svg-and-xps-images-decode-with-neither-exif-orientation-nor-srgb-conversion-so-phone-photos-show-sideways-and-wide-gamut-images-draw-oversaturated) | EPUB, CBZ, SVG and XPS images decode with neither EXIF orientation nor sRGB conversion, so phone photos show sideways and wide-gamut images draw oversaturated | KitePDF | bug | verified | no |
| [IMAGEKODEC-03](#imagekodec-03-a-jpxdecode-image-in-a-pdf-ignores-the-image-dictionarys-colorspace-and-always-uses-the-jp2s-own-colour-space) | A JPXDecode image in a PDF ignores the image dictionary's /ColorSpace and always uses the JP2's own colour space | KitePDF | bug | likely | no |
| [IMAGEKODEC-04](#imagekodec-04-the-jpeg-2000-tier-1-coder-allocates-a-triple-and-pair-on-the-heap-for-every-coefficient-of-every-pass) | The JPEG 2000 tier-1 coder allocates a Triple (and Pair) on the heap for every coefficient of every pass | ImageKodec | performance | verified | no |
| [PLATFORM-04](#platform-04-a-filter-keeps-every-named-result-for-the-run-and-caps-neither-the-primitive-count-nor-the-per-primitive-work-so-a-crafted-filter-holds-gigabytes-or-runs-for-minutes) | A `<filter>` keeps every named result for the run and caps neither the primitive count nor the per-primitive work, so a crafted filter holds gigabytes or runs for minutes | KitePDF | security | verified | no |
| [PLATFORM-05](#platform-05-a-non-finite-number-in-fecolormatrix-or-fecomponenttransfer-makes-kitepagerenderto-throw-illegalargumentexception-cannot-round-nan-value) | A non-finite number in `feColorMatrix` or `feComponentTransfer` makes `KitePage.renderTo` throw `IllegalArgumentException: Cannot round NaN value` | KitePDF | bug | verified | no |
| [PLATFORM-06](#platform-06-a-soft-masked-paint-loses-its-blend-mode-on-skia-android-and-canvas2d-while-awt-and-coregraphics-keep-it) | A soft-masked paint loses its blend mode on Skia, Android and Canvas2D, while AWT and CoreGraphics keep it | KitePDF | bug | verified | no |
| [PLATFORM-07](#platform-07-on-the-jvm-and-apple-compose-viewers-the-fill-and-the-outline-of-host-font-text-come-from-different-faces-so-a-stroked-or-clipped-standard-14-run-is-drawn-in-one-font-and-outlined-in-another) | On the JVM and Apple Compose viewers the fill and the outline of host-font text come from different faces, so a stroked or clipped Standard-14 run is drawn in one font and outlined in another | KitePDF | bug | verified | no |
| [PLATFORM-08](#platform-08-the-skia-canvas-leaves-paints-fonts-textblobs-shaders-gradients-colorfilters-and-evicted-images-to-skikos-cleaner) | The Skia canvas leaves Paints, Fonts, TextBlobs, Shaders, Gradients, ColorFilters and evicted Images to Skiko's cleaner | KitePDF | memory | verified | no |
| [PLATFORM-09](#platform-09-android-skia-and-coregraphics-open-page-sized-layers-for-every-transparency-group-and-soft-mask-ignoring-the-box-the-renderer-gives-them) | Android, Skia and CoreGraphics open page-sized layers for every transparency group and soft mask, ignoring the box the renderer gives them | KitePDF | performance | verified | no |
| [PLATFORM-11](#platform-11-an-xps-brush-written-as-a-contextcolor-draws-nothing) | An XPS brush written as a `ContextColor` draws nothing | KitePDF | bug | verified | no |
| [PLATFORM-17](#platform-17-the-android-native-canvas-has-two-host-tests-where-the-js-and-macos-canvases-have-parity-suites-for-masks-groups-images-shadings-pens-and-raster-steps) | The Android native canvas has two host tests, where the JS and macOS canvases have parity suites for masks, groups, images, shadings, pens and raster steps | KitePDF | test-gap | verified | no |
| [BUILD-DOCS-TESTS-01](#build-docs-tests-01-the-published-jars-aars-and-klibs-carry-none-of-the-licence-texts-that-the-bundled-fonts-hyphenation-patterns-and-the-pdfium-port-require) | The published jars, AARs and klibs carry none of the licence texts that the bundled fonts, hyphenation patterns and the PDFium port require | KitePDF | security | verified | yes: confirmed on the lane's downloaded 0.13.0 artifacts |
| [BUILD-DOCS-TESTS-04](#build-docs-tests-04-every-kitepdf-artifact-is-java-21-bytecode-kitejs-and-imagekodec-are-java-11-and-no-document-says-so) | Every KitePDF artifact is Java 21 bytecode, KiteJS and ImageKodec are Java 11, and no document says so | KitePDF | docs | verified | no |
| [BUILD-DOCS-TESTS-06](#build-docs-tests-06-the-shaping-signature-and-embedded-font-oracles-never-run-in-ci-and-nothing-counts-the-skips) | The shaping, signature and embedded-font oracles never run in CI, and nothing counts the skips | KitePDF | test-gap | verified | yes: confirmed from ci.yml |

### Low

| Id | Title | Repo | Kind | Confidence | Orchestrator check |
| --- | --- | --- | --- | --- | --- |
| [XCUT-04](#xcut-04-the-native-kitelock-decides-reentrancy-from-a-32-bit-hash-of-pthread_self-and-its-comment-misstates-the-consequence-of-a-collision) | The native KiteLock decides reentrancy from a 32-bit hash of pthread_self, and its comment misstates the consequence of a collision | KitePDF | concurrency | verified | no |
| [XCUT-05](#xcut-05-the-platformflate-kdoc-promises-a-9x-fast-path-that-only-the-jvm-and-android-have-and-the-measured-gap-is-12x) | The PlatformFlate KDoc promises a 9x fast path that only the JVM and Android have, and the measured gap is 1.2x | KitePDF | docs | verified | no |
| [XCUT-07](#xcut-07-the-core-modules-public-api-is-257-classes-most-of-them-engine-plumbing-with-no-opt-in-and-the-abi-dumps-freeze-them) | The core module's public API is 257 classes, most of them engine plumbing with no opt-in, and the ABI dumps freeze them | KitePDF | api | verified | no |
| [VIEWER-API-07](#viewer-api-07-the-spec-require-checks-stop-at-nan-and-sign-infinite-maxzoom-minzoom-quality-and-hairlinewidthpx-a-negative-cachebudgetbytes-and-an-unspecified-pagespacing-are-accepted-and-a-continuous-strip-empties-a-zoom-range-below-1-in-silence) | The spec `require` checks stop at NaN and sign: infinite `maxZoom`, `minZoom`, `quality` and `hairlineWidthPx`, a negative `cacheBudgetBytes` and an unspecified `pageSpacing` are accepted, and a `Continuous` strip empties a zoom range below 1 in silence | KitePDF | api | bug | verified | no |
| [VIEWER-API-08](#viewer-api-08-with-minzoom--1-resetzoomonpagechange-goes-to-fit-not-to-minzoom-as-its-kdoc-says-and-a-double-tap-at-fit-zooms-out) | With `minZoom < 1`, `resetZoomOnPageChange` goes to fit, not to `minZoom` as its KDoc says, and a double tap at fit zooms out | KitePDF | docs | design | verified | no |
| [VIEWER-API-10](#viewer-api-10-kdoc-and-docs-claims-the-code-does-not-keep-layout-list-placeholders-in-vectorized-mode-no-settings-that-silently-do-nothing-cache-budget-pagespacing-in-a-spread) | KDoc and docs claims the code does not keep (layout list, placeholders in Vectorized mode, "no settings that silently do nothing", cache budget, pageSpacing in a spread) | KitePDF | docs | verified | no |
| [VIEWER-RUNTIME-07](#viewer-runtime-07-a-state-used-after-its-view-left-posts-script-work-to-a-cancelled-scope-where-it-is-dropped-without-a-word) | A state used after its view left posts script work to a cancelled scope, where it is dropped without a word | KitePDF | bug | verified | yes: confirmed |
| [VIEWER-RUNTIME-08](#viewer-runtime-08-every-change-of-the-form-rebuilds-the-accessibility-nodes-of-every-page-on-screen) | Every change of the form rebuilds the accessibility nodes of every page on screen | KitePDF | performance | verified | no |
| [VIEWER-RUNTIME-09](#viewer-runtime-09-the-page-bitmap-cache-survives-the-views-disposal-while-the-vector-image-cache-does-not) | The page bitmap cache survives the view's disposal while the vector image cache does not | KitePDF | design | verified | no |
| [VIEWER-RUNTIME-10](#viewer-runtime-10-onpagerendered-hands-the-host-the-very-bitmap-the-cache-draws-from-and-nothing-says-so) | onPageRendered hands the host the very bitmap the cache draws from, and nothing says so | KitePDF | docs | verified | no |
| [VIEWER-RUNTIME-11](#viewer-runtime-11-every-run-of-glyph-masks-allocates-a-platform-bitmap-hundreds-per-page-raster) | Every run of glyph masks allocates a platform bitmap, hundreds per page raster | KitePDF | performance | suspected | no |
| [VIEWER-RUNTIME-12](#viewer-runtime-12-runcatching-around-three-off-thread-reads-swallows-the-cancellation-too) | runCatching around three off-thread reads swallows the cancellation too | KitePDF | bug | verified | no |
| [PDF-PARSE-08](#pdf-parse-08-saverewritten-on-an-encrypted-document-silently-drops-encrypt-and-writes-plaintext) | `saveRewritten` on an encrypted document silently drops `/Encrypt` and writes plaintext | KitePDF | security | likely | no |
| [PDF-PARSE-09](#pdf-parse-09-lexerparselongfrombuf-overflow-test-is-unreliable-so-a-huge-integer-can-wrap-silently-instead-of-throwing) | `Lexer.parseLongFromBuf` overflow test is unreliable, so a huge integer can wrap silently instead of throwing | KitePDF | bug | verified | no |
| [PDF-PARSE-10](#pdf-parse-10-predictor-parameters-are-not-validated-before-sizing-so-columns-0-divides-by-zero-and-large-values-overflow-int) | Predictor parameters are not validated before sizing, so `/Columns 0` divides by zero and large values overflow Int | KitePDF | bug | verified | no |
| [PDF-RENDER-03](#pdf-render-03-a-tiling-pattern-with-a-negative-xstep-or-ystep-paints-its-cells-in-the-wrong-place) | A tiling pattern with a negative XStep or YStep paints its cells in the wrong place | KitePDF | bug | verified | yes: confirmed |
| [PDF-RENDER-10](#pdf-render-10-skiacanvas-allocates-a-native-paint-for-every-fill-stroke-glyph-run-and-image-and-never-closes-it) | SkiaCanvas allocates a native Paint for every fill, stroke, glyph run and image and never closes it | KitePDF | performance | verified | no |
| [PDF-RENDER-12](#pdf-render-12-a-stray-d1-in-page-or-form-content-switches-every-colour-operator-off-for-the-rest-of-the-page) | A stray d1 in page or form content switches every colour operator off for the rest of the page | KitePDF | bug | verified | yes: confirmed |
| [PDF-RENDER-14](#pdf-render-14-a-bm-array-whose-first-name-is-unknown-paints-normal-instead-of-the-first-recognised-mode) | A /BM array whose first name is unknown paints Normal instead of the first recognised mode | KitePDF | bug | verified | yes: confirmed, citation corrected |
| [PDF-RENDER-15](#pdf-render-15-a-calcmyk-colour-space-resolves-to-devicegray-so-its-four-operands-are-read-as-one-grey-value) | A CalCMYK colour space resolves to DeviceGray, so its four operands are read as one grey value | KitePDF | bug | verified | yes: confirmed |
| [PDF-RENDER-16](#pdf-render-16-a-type-4-or-type-67-mesh-with-more-than-65536-triangles-or-patches-is-cut-off-silently) | A type 4 or type 6/7 mesh with more than 65536 triangles or patches is cut off silently | KitePDF | bug | verified | no |
| [PDF-RENDER-17](#pdf-render-17-a-mesh-shading-is-rasterised-to-one-image-of-at-most-two-megapixels-and-scaled-up-with-smoothing) | A mesh shading is rasterised to one image of at most two megapixels and scaled up with smoothing | KitePDF | design | verified | no |
| [PDF-RENDER-18](#pdf-render-18-images-in-a-devicen-or-multi-component-separation-space-evaluate-the-tint-transform-per-pixel-with-a-fresh-allocation-per-sample-corner) | Images in a DeviceN or multi-component Separation space evaluate the tint transform per pixel, with a fresh allocation per sample corner | KitePDF | performance | verified | no |
| [PDF-RENDER-19](#pdf-render-19-an-image-xobject-under-a-resource-dictionary-with-a-default-colour-space-bypasses-both-caches-on-every-draw) | An image XObject under a resource dictionary with a Default colour space bypasses both caches on every draw | KitePDF | performance | verified | no |
| [PDF-RENDER-20](#pdf-render-20-a-negative-dash-phase-is-clamped-to-zero-on-awt-and-wrapped-ignored-or-passed-through-on-the-other-canvases) | A negative dash phase is clamped to zero on AWT and wrapped, ignored or passed through on the other canvases | KitePDF | bug | verified | no |
| [PDF-RENDER-21](#pdf-render-21-composecanvas-paints-the-background-of-a-shading-it-cannot-parse-every-other-canvas-paints-nothing) | ComposeCanvas paints the /Background of a shading it cannot parse; every other canvas paints nothing | KitePDF | bug | verified | yes: confirmed |
| [PDF-RENDER-23](#pdf-render-23-kiterastercanvas-allocates-a-canvas-sized-float-mask-for-every-clip-push-and-canvas-sized-layers-for-every-group-and-mask) | KiteRasterCanvas allocates a canvas-sized float mask for every clip push and canvas-sized layers for every group and mask | KitePDF | performance | verified | no |
| [PDF-RENDER-24](#pdf-render-24-coregraphicscanvas-copies-the-whole-rgba-buffer-and-rebuilds-a-cgimage-on-every-draw-of-a-cached-image) | CoreGraphicsCanvas copies the whole RGBA buffer and rebuilds a CGImage on every draw of a cached image | KitePDF | performance | verified | no |
| [PDF-RENDER-26](#pdf-render-26-a-pagerenderer-reused-for-a-second-page-serves-a-resource-less-form-xobject-the-first-pages-resources) | A PageRenderer reused for a second page serves a resource-less form XObject the first page's resources | KitePDF | api | verified | no |
| [FONTS-TEXT-12](#fonts-text-12-cfffontglyphidforcodepoint-knows-ascii-names-cidnnn-and-unixxxx-only-so-a-differences-name-the-program-lacks-and-any-accented-code-without-a-matching-name-resolve-to-glyph-0-and-draw-nothing) | `CffFont.glyphIdForCodePoint` knows ASCII names, `cidNNN` and `uniXXXX` only, so a Differences name the program lacks and any accented code without a matching name resolve to glyph 0 and draw nothing | KitePDF | bug | verified | no |
| [FONTS-TEXT-13](#fonts-text-13-a-simple-font-with-widths-but-no-firstchar-loses-its-widths-and-every-glyph-advances-500) | A simple font with `/Widths` but no `/FirstChar` loses its widths and every glyph advances 500 | KitePDF | bug | verified | no |
| [FONTS-TEXT-14](#fonts-text-14-a-type-3-glyphs-advance-uses-only-fontmatrixa-so-a-rotated-or-skewed-type-3-font-advances-the-pen-by-zero) | A Type 3 glyph's advance uses only `FontMatrix.a`, so a rotated or skewed Type 3 font advances the pen by zero | KitePDF | bug | verified | no |
| [FONTS-TEXT-15](#fonts-text-15-type-2-charstrings-never-close-a-subpath-on-rmoveto-so-stroked-and-clipped-text-tr-1-2-5-6-shows-an-open-edge-on-every-contour-but-the-last) | Type 2 charstrings never close a subpath on `rmoveto`, so stroked and clipped text (Tr 1, 2, 5, 6) shows an open edge on every contour but the last | KitePDF | bug | verified | no |
| [FONTS-TEXT-16](#fonts-text-16-opentypekern-skips-extension-lookups-and-lookup-flags-so-gpos-kerning-stored-under-type-9-is-silently-absent) | `OpenTypeKern` skips Extension lookups and lookup flags, so GPOS kerning stored under type 9 is silently absent | KitePDF | bug | verified | yes: confirmed |
| [FONTS-TEXT-17](#fonts-text-17-truetypefontparse-rejects-a-font-whose-loca-or-hmtx-is-shorter-than-maxpnumglyphs-implies-dropping-the-whole-program-where-freetype-truncates) | `TrueTypeFont.parse` rejects a font whose `loca` or `hmtx` is shorter than `maxp.numGlyphs` implies, dropping the whole program where FreeType truncates | KitePDF | bug | verified | no |
| [FONTS-TEXT-18](#fonts-text-18-cmap-looks-ranges-up-linearly-so-a-large-embedded-cmap-or-tounicode-costs-oranges-per-glyph) | `CMap` looks ranges up linearly, so a large embedded CMap or ToUnicode costs O(ranges) per glyph | KitePDF | performance | verified | no |
| [FONTS-TEXT-19](#fonts-text-19-hyphenation-patterns-ship-in-kitepdf-core-for-every-consumer-31-mb-of-class-constants-that-a-pdf-only-app-never-reads) | Hyphenation patterns ship in `kitepdf-core` for every consumer, 3.1 MB of class constants that a PDF-only app never reads | KitePDF | design | verified | no |
| [FONTS-TEXT-20](#fonts-text-20-search-compares-code-units-only-no-normalisation-no-ligature-expansion-no-soft-hyphen-and-a-real-hyphen-at-a-line-end-is-dropped) | Search compares code units only: no normalisation, no ligature expansion, no soft hyphen, and a real hyphen at a line end is dropped | KitePDF | bug | verified | no |
| [FONTS-TEXT-21](#fonts-text-21-a-composite-glyphs-scaled_component_offset-flag-is-ignored-and-the-cff-predefined-expert-charsets-read-as-isoadobe) | A composite glyph's `SCALED_COMPONENT_OFFSET` flag is ignored, and the CFF predefined Expert charsets read as ISOAdobe | KitePDF | bug | verified | no |
| [PDF-WRITE-14](#pdf-write-14-the-editor-never-enforces-p-a-document-opened-with-the-user-password-and-p-that-forbids-changes-is-edited-and-re-encrypted-without-a-word) | The editor never enforces `/P`: a document opened with the user password and `/P` that forbids changes is edited and re-encrypted without a word | KitePDF | design | verified | no |
| [PDF-WRITE-15](#pdf-write-15-neither-save-mode-changes-the-second-id-string-and-the-incremental-trailers-size-can-be-smaller-than-the-bases) | Neither save mode changes the second `/ID` string, and the incremental trailer's `/Size` can be smaller than the base's | KitePDF | bug | verified | no |
| [PDF-WRITE-16](#pdf-write-16-pdfimagejpeg-and-pdfimagejpx-trust-the-caller-for-the-colour-space-and-the-size-so-a-cmyk-or-ycck-jpeg-is-declared-devicergb-and-the-test-for-the-path-uses-a-fake-jpeg) | `PdfImage.jpeg` and `PdfImage.jpx` trust the caller for the colour space and the size, so a CMYK or YCCK JPEG is declared `DeviceRGB` and the test for the path uses a fake JPEG | KitePDF | design | verified | no |
| [PDF-WRITE-18](#pdf-write-18-pdfobjectwriterwritename-keeps-only-the-low-byte-of-each-character-so-a-name-built-from-a-kotlin-string-with-a-character-above-u00ff-is-silently-changed) | `PdfObjectWriter.writeName` keeps only the low byte of each character, so a name built from a Kotlin string with a character above U+00FF is silently changed | KitePDF | api | verified | no |
| [PDF-WRITE-20](#pdf-write-20-r6-passwords-skip-saslprep-on-both-sides-and-the-public-kdoc-does-not-say-so-the-new-file-header-says-pdf-17-while-using-a-pdf-20-handler) | R6 passwords skip SASLprep on both sides and the public KDoc does not say so; the new-file header says PDF 1.7 while using a PDF 2.0 handler | KitePDF | docs | verified | no |
| [EPUB-LAYOUT-11](#epub-layout-11-white-space-nowrap-and-pre-still-wrap-pre-line-keeps-every-space-and-a-tab-is-one-space-wide) | `white-space: nowrap` and `pre` still wrap, `pre-line` keeps every space, and a tab is one space wide | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-12](#epub-layout-12-vw-and-vh-take-whatever-reference-the-property-uses-a-font-size-in-vw-is-a-fraction-of-the-parent-font-size) | `vw` and `vh` take whatever reference the property uses: a `font-size` in `vw` is a fraction of the parent font size | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-13](#epub-layout-13-a-float-without-a-width-takes-half-the-content-width-instead-of-its-shrink-to-fit-width) | A float without a width takes half the content width instead of its shrink-to-fit width | KitePDF | design | verified | no |
| [EPUB-LAYOUT-14](#epub-layout-14-line-height-normal-is-14-em-and-the-baseline-sits-at-08-em-whatever-the-font-says) | `line-height: normal` is 1.4 em and the baseline sits at 0.8 em whatever the font says | KitePDF | design | verified | no |
| [EPUB-LAYOUT-15](#epub-layout-15-a-chapter-laid-out-from-a-withsettings-copy-loses-the-canvases-its-scripts-drew-since-canvasimages-lives-on-the-document-and-the-scripted-tree-on-the-parse) | A chapter laid out from a `withSettings` copy loses the canvases its scripts drew, since `canvasImages` lives on the document and the scripted tree on the parse | KitePDF | bug | suspected | no |
| [EPUB-LAYOUT-16](#epub-layout-16-the-reader-origin-overrides-use--so-a-forced-justification-flattens-centred-headings-and-a-forced-family-removes-monospace-from-code) | The reader-origin overrides use `*`, so a forced justification flattens centred headings and a forced family removes monospace from code | KitePDF | design | verified | no |
| [EPUB-LAYOUT-17](#epub-layout-17-the-woff2-and-woff-decoders-use-the-512-mib-pdf-stream-cap-so-a-tiny-font-file-can-demand-a-gigabyte) | The WOFF2 and WOFF decoders use the 512 MiB PDF stream cap, so a tiny font file can demand a gigabyte | KitePDF | memory | verified | no |
| [EPUB-LAYOUT-18](#epub-layout-18-selector-matching-tests-every-rule-against-every-element-and-the-sibling-pseudo-classes-rescan-the-sibling-list-so-a-long-table-or-a-big-stylesheet-costs-seconds) | Selector matching tests every rule against every element, and the sibling pseudo-classes rescan the sibling list, so a long table or a big stylesheet costs seconds | KitePDF | performance | verified | no |
| [EPUB-LAYOUT-19](#epub-layout-19-a-table-lays-every-cell-out-twice-and-a-nested-table-doubles-that-at-each-level) | A table lays every cell out twice and a nested table doubles that at each level | KitePDF | performance | verified | no |
| [EPUB-LAYOUT-20](#epub-layout-20-docsepubmd-says-the-writing-mode-is-one-decision-per-book-read-from-the-first-reflowable-chapter-which-the-code-no-longer-does) | `docs/epub.md` says the writing mode is one decision per book read from the first reflowable chapter, which the code no longer does | KitePDF | docs | verified | no |
| [EPUB-LAYOUT-21](#epub-layout-21-hyphenation-takes-one-language-per-chapter-so-a-lang-on-an-element-inside-it-hyphenates-with-the-wrong-patterns) | Hyphenation takes one language per chapter, so a `lang` on an element inside it hyphenates with the wrong patterns | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-22](#epub-layout-22-full-path-of-containerxml-is-used-as-a-zip-name-without-percent-decoding) | `full-path` of `container.xml` is used as a zip name without percent-decoding | KitePDF | bug | likely | no |
| [EPUB-LAYOUT-23](#epub-layout-23-a-numeric-character-reference-in-a-texthtml-chapter-does-not-get-htmls-c1-remap-or-the-ufffd-replacements) | A numeric character reference in a `text/html` chapter does not get HTML's C1 remap or the U+FFFD replacements | KitePDF | bug | verified | no |
| [EPUB-LAYOUT-24](#epub-layout-24-the-viewers-pager-takes-reverselayout-straight-from-the-book-so-under-an-rtl-app-locale-every-book-pages-the-wrong-way) | The viewer's pager takes `reverseLayout` straight from the book, so under an RTL app locale every book pages the wrong way | KitePDF | bug | suspected | no |
| [EPUB-SCRIPT-07](#epub-script-07-pdfscriptrunnerfailures-is-unbounded-and-each-failure-copies-the-whole-list) | PdfScriptRunner.failures is unbounded, and each failure copies the whole list | KitePDF | memory | verified | no |
| [EPUB-SCRIPT-11](#epub-script-11-utilprintf-writes-the-wrong-separators-for-styles-2-3-and-4) | util.printf writes the wrong separators for styles 2, 3 and 4 | KitePDF | bug | verified | no |
| [EPUB-SCRIPT-12](#epub-script-12-afnumber_format-prepends-a-minus-sign-for-negstyle-1-and-rounds-half-cents-differently-from-chrome) | AFNumber_Format prepends a minus sign for negStyle 1 and rounds half-cents differently from Chrome | KitePDF | bug | verified | no |
| [EPUB-SCRIPT-13](#epub-script-13-afspecial_format-leaves-partial-phone-and-ssn-input-unformatted-and-afsimple_calculate-does-not-round-to-six-decimals) | AFSpecial_Format leaves partial phone and SSN input unformatted, and AFSimple_Calculate does not round to six decimals | KitePDF | bug | verified | no |
| [EPUB-SCRIPT-14](#epub-script-14-pdfscriptrunnerkeystroke-takes-a-commit-parameter-that-does-nothing) | PdfScriptRunner.keystroke takes a `commit` parameter that does nothing | KitePDF | api | verified | no |
| [EPUB-SCRIPT-15](#epub-script-15-a-call-admitted-just-before-close-can-reopen-an-engine-that-nothing-ever-closes) | A call admitted just before close() can reopen an engine that nothing ever closes | KitePDF | concurrency | suspected | no |
| [EPUB-SCRIPT-16](#epub-script-16-calculatenow-inside-a-calculate-script-recurses-to-the-stack-limit-and-appcalculate-is-ignored) | calculateNow inside a calculate script recurses to the stack limit, and app.calculate is ignored | KitePDF | bug | verified | no |
| [EPUB-SCRIPT-17](#epub-script-17-a-form-without-co-runs-every-calculate-script-in-field-order-which-no-reference-viewer-does) | A form without /CO runs every calculate script in field order, which no reference viewer does | KitePDF | design | verified | no |
| [EPUB-SCRIPT-18](#epub-script-18-leaving-a-text-field-commits-it-even-when-nothing-was-typed-so-validate-every-calculation-and-every-format-run-on-each-focus-change) | Leaving a text field commits it even when nothing was typed, so validate, every calculation and every format run on each focus change | KitePDF | performance | likely | no |
| [EPUB-SCRIPT-19](#epub-script-19-each-measurement-after-a-change-lays-the-chapter-out-again-and-announces-the-change-to-every-viewer-inside-the-scripts-turn) | Each measurement after a change lays the chapter out again and announces the change to every viewer, inside the script's turn | KitePDF | performance | verified | no |
| [EPUB-SCRIPT-21](#epub-script-21-on-the-web-a-blocking-run-or-setfieldvalue-during-a-paused-script-fails-silently-which-the-docs-do-not-say) | On the web, a blocking run() or setFieldValue() during a paused script fails silently, which the docs do not say | KitePDF | docs | verified | no |
| [EPUB-SCRIPT-22](#epub-script-22-afnumber_keystroke-accepts-exponent-and-sign-characters-and-either-decimal-mark-where-chrome-accepts-digits-one-sign-and-the-styles-mark) | AFNumber_Keystroke accepts exponent and sign characters and either decimal mark, where Chrome accepts digits, one sign and the style's mark | KitePDF | bug | verified | no |
| [KITEJS-07](#kitejs-07-a-script-that-throws-leaves-its-queued-promise-jobs-in-place-and-they-run-inside-the-next-unrelated-call) | A script that throws leaves its queued Promise jobs in place, and they run inside the next unrelated call | KiteJS | design | verified | no |
| [KITEJS-08](#kitejs-08-the-jvm-library-loader-has-no-fallback-when-systemload-refuses-the-shared-copy-so-a-second-class-loader-or-a-noexec-temp-folder-cannot-use-quickjs) | The JVM library loader has no fallback when `System.load` refuses the shared copy, so a second class loader or a `noexec` temp folder cannot use QuickJS | KiteJS | design | verified | no |
| [KITEJS-09](#kitejs-09-decimalparserexactpath-builds-a-big-integer-from-every-significant-digit-so-a-very-long-numeric-string-parses-in-quadratic-time) | `DecimalParser.exactPath` builds a big integer from every significant digit, so a very long numeric string parses in quadratic time | KiteJS | performance | verified | no |
| [KITEJS-10](#kitejs-10-dateparse-accepts-the-expanded-year--000000-which-ecmascript-forbids) | `Date.parse` accepts the expanded year `-000000`, which ECMAScript forbids | KiteJS | bug | likely | no |
| [KITEJS-11](#kitejs-11-kite_news-failure-path-frees-a-table-whose-next-array-is-null-or-uninitialised) | `kite_new`'s failure path frees a table whose `next` array is NULL or uninitialised | KiteJS | bug | verified | no |
| [KITEJS-12](#kitejs-12-kitejsnewpromise-compiles-its-helper-script-on-every-call-so-each-suspendfunction-invocation-pays-a-parse) | `KiteJs.newPromise()` compiles its helper script on every call, so each `suspendFunction` invocation pays a parse | KiteJS | performance | verified | no |
| [KITEJS-13](#kitejs-13-no-contract-test-crosses-a-lone-surrogate-or-a-nul-through-the-engines) | No contract test crosses a lone surrogate or a NUL through the engines | KiteJS | test-gap | verified | no |
| [KITEJS-14](#kitejs-14-two-concurrent-quickjsload-calls-on-the-web-both-decompress-and-instantiate-the-module) | Two concurrent `QuickJs.load()` calls on the web both decompress and instantiate the module | KiteJS | performance | verified | no |
| [PLATFORM-10](#platform-10-the-coregraphics-canvas-builds-a-new-cgimage-and-copies-the-pixel-bytes-on-every-draw-of-a-cached-image-and-creates-a-ctfont-for-every-host-text-run) | The CoreGraphics canvas builds a new CGImage and copies the pixel bytes on every draw of a cached image, and creates a CTFont for every host-text run | KitePDF | performance | verified | no |
| [PLATFORM-12](#platform-12-xps-gradient-stops-that-share-an-offset-are-dropped-so-a-hard-edge-becomes-a-ramp) | XPS gradient stops that share an offset are dropped, so a hard edge becomes a ramp | KitePDF | bug | verified | no |
| [PLATFORM-13](#platform-13-the-awt-sh-fill-without-a-clip-covers-at-most-10000-by-10000-user-units-from-the-origin) | The AWT `sh` fill without a clip covers at most 10,000 by 10,000 user units from the origin | KitePDF | bug | verified | no |
| [PLATFORM-14](#platform-14-awtcanvas-changes-the-hosts-graphics2d-hints-color-stroke-font-and-paint-and-never-restores-them-and-the-kdoc-does-not-say-so) | `AwtCanvas` changes the host's `Graphics2D` hints, color, stroke, font and paint and never restores them, and the KDoc does not say so | KitePDF | api | verified | no |
| [PLATFORM-15](#platform-15-the-awt-image-bitmap-is-filled-through-bufferedimagesetrgb-one-pixel-at-a-time) | The AWT image bitmap is filled through `BufferedImage.setRGB` one pixel at a time | KitePDF | performance | likely | no |
| [PLATFORM-16](#platform-16-three-luminosity-formulas-gate-soft-masks-rec-709-on-the-skia-renderer-029905870114-on-the-compose-viewer-7715029-of-256-on-the-four-native-canvases) | Three luminosity formulas gate soft masks: Rec. 709 on the Skia renderer, 0.299/0.587/0.114 on the Compose viewer, 77/150/29 of 256 on the four native canvases | KitePDF | bug | verified | no |
| [PLATFORM-18](#platform-18-a-smil-clip-whose-clipend-the-clock-parser-cannot-read-plays-the-audio-file-to-its-end) | A SMIL clip whose `clipEnd` the clock parser cannot read plays the audio file to its end | KitePDF | bug | verified | no |
| [PLATFORM-19](#platform-19-kitemediasession-keys-an-elements-kept-place-by-the-hash-code-of-the-books-metadata-so-two-books-with-equal-metadata-share-positions) | `KiteMediaSession` keys an element's kept place by the hash code of the book's metadata, so two books with equal metadata share positions | KitePDF | bug | verified | no |
| [PLATFORM-20](#platform-20-svgimageissvg-looks-for-svg-in-the-first-512-bytes-only-so-kitedoc-does-not-recognise-an-svg-with-a-long-prolog) | `SvgImage.isSvg` looks for `<svg` in the first 512 bytes only, so `KiteDoc` does not recognise an SVG with a long prolog | KitePDF | bug | verified | no |
| [PLATFORM-21](#platform-21-nsdatatobytearray-in-the-apple-sources-truncates-a-length-above-2-gb-to-a-negative-int) | `NSData.toByteArray` in the Apple sources truncates a length above 2 GB to a negative Int | KitePDF | bug | verified | no |
| [BUILD-DOCS-TESTS-02](#build-docs-tests-02-readme-notice-and-the-about-page-say-kitepdf-contains-no-third-party-source-code-but-adobecmykkt-is-a-port-of-a-pdfium-routine) | README, NOTICE and the About page say KitePDF contains no third-party source code, but AdobeCmyk.kt is a port of a PDFium routine | KitePDF | docs | verified | no |
| [BUILD-DOCS-TESTS-03](#build-docs-tests-03-the-kitepdf-skia-renderer-aar-declares-minsdk-21-although-skiko-needs-24-and-the-docs-say-24) | The kitepdf-skia-renderer AAR declares minSdk 21 although Skiko needs 24 and the docs say 24 | KitePDF | api | verified | yes: confirmed |
| [BUILD-DOCS-TESTS-05](#build-docs-tests-05-a-push-to-main-cancels-the-ci-run-of-the-commit-before-it-so-intermediate-commits-on-main-get-no-verdict) | A push to main cancels the CI run of the commit before it, so intermediate commits on main get no verdict | KitePDF | test-gap | verified | no |
| [BUILD-DOCS-TESTS-07](#build-docs-tests-07-the-what-ci-tests-table-of-docsplatformsmd-does-not-match-the-workflow) | The "What CI tests" table of docs/platforms.md does not match the workflow | KitePDF | docs | verified | no |
| [BUILD-DOCS-TESTS-08](#build-docs-tests-08-behaves-identically-across-platforms-is-contradicted-by-the-renderer-host-font-and-viewer-differences-the-other-pages-document) | "behaves identically across platforms" is contradicted by the renderer, host-font and viewer differences the other pages document | KitePDF | docs | verified | no |
| [BUILD-DOCS-TESTS-09](#build-docs-tests-09-kitepdf-has-no-release-workflow-a-release-is-a-signed-publish-from-a-developer-machine-with-automatic-central-release-and-no-ci-gate) | KitePDF has no release workflow; a release is a signed publish from a developer machine with automatic Central release and no CI gate | KitePDF | design | verified | no |
| [BUILD-DOCS-TESTS-10](#build-docs-tests-10-stale-references-in-kdoc-and-difftestmd-a-planning-file-that-no-longer-exists-no-embedding-on-a-builder-that-embeds-and-gradle-flags-on-a-toolchain-build) | Stale references in KDoc and DIFFTEST.md: a planning file that no longer exists, "no embedding" on a builder that embeds, and Gradle flags on a toolchain build | KitePDF | docs | verified | no |
| [BUILD-DOCS-TESTS-11](#build-docs-tests-11-the-readmes-what-else-you-need-table-and-the-install-section-leave-out-two-things-a-first-build-hits) | The README's "What else you need" table and the Install section leave out two things a first build hits | KitePDF | docs | verified | no |
| [BUILD-DOCS-TESTS-12](#build-docs-tests-12-three-kitejs-probe-classes-in-testjvm-can-never-run-under-the-toolchain-and-no-document-says-how-to-run-them) | Three KiteJS probe classes in test@jvm can never run under the toolchain, and no document says how to run them | KiteJS | test-gap | likely | no |
| [BUILD-DOCS-TESTS-14](#build-docs-tests-14-imagekodecs-fuzztest-runs-the-same-mutants-forever-three-fixed-seeds-fixed-counts-no-override) | ImageKodec's FuzzTest runs the same mutants forever: three fixed seeds, fixed counts, no override | ImageKodec | test-gap | verified | no |
| [BUILD-DOCS-TESTS-15](#build-docs-tests-15-engine-plumbing-is-public-api-kitelock-currentthreadid-a-mutablelist-dom-and-the-gsub-substitute-call-ship-in-every-abi-dump) | Engine plumbing is public API: KiteLock, currentThreadId, a MutableList DOM and the GSUB substitute call ship in every ABI dump | KitePDF | api | verified | no |
| [BUILD-DOCS-TESTS-17](#build-docs-tests-17-three-published-modules-are-tested-on-the-jvm-only-and-the-coregraphics-canvas-only-on-macos-although-they-ship-for-up-to-17-targets) | Three published modules are tested on the JVM only, and the CoreGraphics canvas only on macOS, although they ship for up to 17 targets | KitePDF | test-gap | verified | no |
| [BUILD-DOCS-TESTS-18](#build-docs-tests-18-the-consumer-test-resolves-the-same-version-from-maven-local-and-maven-central-so-a-module-missing-from-the-local-publish-is-taken-from-the-last-release-without-notice) | The consumer test resolves the same version from Maven Local and Maven Central, so a module missing from the local publish is taken from the last release without notice | KitePDF | test-gap | likely | no |
| [BUILD-DOCS-TESTS-19](#build-docs-tests-19-kitepdf-and-kitejs-have-no-securitymd-though-both-parse-untrusted-input) | KitePDF and KiteJS have no SECURITY.md, though both parse untrusted input | KitePDF, KiteJS | security | verified | no |
| [BUILD-DOCS-TESTS-20](#build-docs-tests-20-the-kitejs-and-imagekodec-workflows-run-with-the-default-token-permissions-and-keep-the-checkout-credentials) | The KiteJS and ImageKodec workflows run with the default token permissions and keep the checkout credentials | KiteJS, ImageKodec | security | verified | no |
| [BUILD-DOCS-TESTS-21](#build-docs-tests-21-31-mb-of-hyphenation-classes-that-only-kitepdf-epub-uses-ship-in-kitepdf-core-so-every-pdf-only-consumer-pays-for-them) | 3.1 MB of hyphenation classes that only kitepdf-epub uses ship in kitepdf-core, so every PDF-only consumer pays for them | KitePDF | design | verified | no |
| [BUILD-DOCS-TESTS-22](#build-docs-tests-22-no-page-says-how-an-ios-app-without-kotlin-consumes-kitepdf-and-the-framework-script-that-makes-it-possible-is-undocumented) | No page says how an iOS app without Kotlin consumes KitePDF, and the framework script that makes it possible is undocumented | KitePDF | docs | verified | no |
| [BUILD-DOCS-TESTS-23](#build-docs-tests-23-some-automated-epub-conformance-checks-assert-only-that-the-pass-sentence-shows-not-that-the-fail-sentence-does-not) | Some automated EPUB conformance checks assert only that the pass sentence shows, not that the fail sentence does not | KitePDF | test-gap | suspected | no |

### Info

| Id | Title | Repo | Kind | Confidence | Orchestrator check |
| --- | --- | --- | --- | --- | --- |
| [XCUT-08](#xcut-08-the-warning-sink-is-one-process-global-mutable-field) | The warning sink is one process-global mutable field | KitePDF | design | verified | no |
| [PDF-RENDER-25](#pdf-render-25-shared-colour-spaces-images-and-icc-profiles-memoise-their-per-intent-variants-in-plain-arrays-without-synchronisation) | Shared colour spaces, images and ICC profiles memoise their per-intent variants in plain arrays without synchronisation | KitePDF | concurrency | verified | no |
| [PDF-WRITE-19](#pdf-write-19-pdfbuilderserialize-copies-the-whole-output-once-more-to-hash-it-for-id) | `PdfBuilder.serialize` copies the whole output once more to hash it for `/ID` | KitePDF | performance | verified | no |
| [EPUB-SCRIPT-20](#epub-script-20-event-objects-expose-their-state-as-writable-own-properties-and-can-be-dispatched-while-dispatching) | Event objects expose their state as writable own properties and can be dispatched while dispatching | KitePDF | bug | verified | no |
| [PLATFORM-22](#platform-22-the-native-kitelockunlock-without-a-matching-lock-drives-depth-negative-and-never-releases-the-lock) | The native `KiteLock.unlock()` without a matching `lock()` drives `depth` negative and never releases the lock | KitePDF | design | verified | yes: confirmed from the orchestrator's own earlier read of KiteLock.native.kt |
| [BUILD-DOCS-TESTS-13](#build-docs-tests-13-the-opt-in-doompdf-benchmarks-on-apple-and-web-targets-report-pass-when-nobody-opted-in) | The opt-in DoomPDF benchmarks on Apple and web targets report PASS when nobody opted in | KitePDF | test-gap | verified | no |
| [BUILD-DOCS-TESTS-16](#build-docs-tests-16-150-public-data-classes-freeze-their-constructor-order-into-copy-and-componentn) | 150 public data classes freeze their constructor order into copy and componentN | KitePDF | api | verified | no |
| [BUILD-DOCS-TESTS-24](#build-docs-tests-24-the-docs-site-build-floats-mkdocs-material-and-runs-with---strict) | The docs site build floats mkdocs-material and runs with --strict | KitePDF | design | verified | no |

## Lane XCUT: cross-cutting design review (orchestrator's own)

### XCUT-01: Every consumer of kitepdf-core carries 3.1 MB of hyphenation tables that only the EPUB layout uses
- Repo: KitePDF
- Area: kitepdf-core, text/hyphen; kitepdf-epub BoxLayout
- Kind: design
- Severity: medium
- Confidence: verified (measured on the built JVM classes in this container)
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen/HyphenPatternSets.kt:38 (the `when` that names all 70 pattern objects), kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/Hyphenator.kt:71 (public `forLanguage`), kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt (the only caller outside core), kitepdf-core/module.yaml (the module every handler depends on)
- What happens: The 70 `Hyph*.kt` files are 3.1 MB of source and compile to 3.1 MB of the 7.6 MB of class files of kitepdf-core (41 percent, `du` on build/artifacts/CompiledJvmArtifact/kitepdf-corejvm/kotlin-output). `HyphenPatternSets.byId` references every pattern object from one `when`, and `Hyphenator.forLanguage` is public, so the data is reachable from the public API of the core module. Only `BoxLayout` in kitepdf-epub calls `Hyphenator`; the PDF, CBZ, SVG and XPS handlers never hyphenate. A desktop app that depends on `kitepdf-pdf` alone ships the tables in its jar. A Kotlin/Native debug build and any framework that exports kitepdf-core links the string constants of all 70 languages. Release DCE on Kotlin/Native, R8 on Android and the JS or Wasm DCE can drop them in an app that never reaches `forLanguage`, so the cost there depends on the app's shrinker and build type, which the docs do not say. Cold start of one language is also paid on the layout thread: measured on this JVM, `forLanguage("hu")` takes 347 ms and retains 1.8 MB, `"de"` 57 ms and 1.3 MB, `"nb"` 33 ms and 0.9 MB (a phone without a JIT will be slower). The per-process cache in `Hyphenator.Companion.cache` never evicts, so a multilingual reader keeps every language it ever hyphenated.
- What should happen: The pattern data belongs next to its one user (kitepdf-epub), or in an artifact of its own (`kitepdf-hyphenation`) that kitepdf-epub depends on, so that a PDF-only consumer carries none of it. docs/platforms.md or getting-started should state the size each artifact adds per target.
- How to reproduce or evidence: `du -sh build/artifacts/CompiledJvmArtifact/kitepdf-corejvm/kotlin-output/io/github/yuroyami/kitepdf/core/text/hyphen` gives 3.1M against 7.6M for the whole module. A temporary JVM test calling `Hyphenator.forLanguage` for hu, de, en-US, nb, la, cu printed the cold times and `trieBytes` above.
- Cross-effects: README says kitepdf is "pure Kotlin from the parser up" and compares favourably on footprint with native engines; the web bundle size of a PDF-only viewer and the iOS framework size are where this shows. The CONTRIBUTING rule "no new dependencies in core, pdf, epub" is not an obstacle: a KitePDF module is allowed.
- Fix sketch: Move `core/text/hyphen` and `Hyphenator` to kitepdf-epub (or a new module), keep `Hyphenator`'s public constructor in core if third parties use it, and add a one-line size table to docs/platforms.md.

### XCUT-02: The Compose viewer hard-depends on the EPUB engine, so a PDF-only Compose app ships the whole EPUB stack
- Repo: KitePDF
- Area: kitepdf-compose-viewer
- Kind: design
- Severity: medium
- Confidence: verified
- Where: kitepdf-compose-viewer/module.yaml (dependencies: `//kitepdf-pdf`, `//kitepdf-epub`), kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:158 (`forDocument` casts to `EpubDocument`), kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:249 (`epubScripts: io.github.yuroyami.kitepdf.epub.EpubScriptHandler?`), kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt and kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ChapterLoader.kt in the same folder (chapter logic typed on the EPUB classes where it is)
- What happens: The viewer's own module metadata requires kitepdf-epub, and its public signatures name EPUB types. A reader that shows PDFs only therefore resolves and ships kitepdf-epub (43 000 lines: HTML parser, CSS engine, box layout, Brotli, WOFF2, the 2 500-line DOM prelude, Nfc data) plus the hyphenation tables of XCUT-01. The README sells `kitepdf-pdf` plus `kitepdf-compose-viewer` as the two-artifact PDF setup, and the format modules are split exactly so that a consumer can take one format; the viewer undoes that split. On wasmJs and JS this is a bundle-size cost on every page load, and on iOS a framework-size cost.
- What should happen: The viewer should depend on kitepdf-core only and reach format-specific behaviour through `KiteDocument` and `KitePage` (the chapter and bookmark API already exists on the core interface for this) and through small optional "viewer adapter" artifacts or service hooks, so that `kitepdf-compose-viewer` works with any handler on the classpath. At minimum the docs should say what the viewer pulls in.
- How to reproduce or evidence: `grep -n "kitepdf-epub\|kitepdf-pdf" kitepdf-compose-viewer/module.yaml`; `grep -n "epub\." kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt KiteDocViewSpecs.kt`.
- Cross-effects: XCUT-01 (hyphenation rides along), kitepdf-media (EPUB only, correctly separate), the `scripts` and `epubScripts` parameters (two handler types on one composable, where one `KiteScriptHandler` on the core interface would do).
- Fix sketch: Introduce `KiteDocument.scriptHandlerType`-free hooks: a `KiteLayoutHints` value on the core document (flow, spread, roll, rightToLeft, scrolled) that `forDocument` reads, and move the EPUB-specific branches behind it. Keep source compatibility with deprecated typealiases for one release.

### XCUT-03: KitePDF publishes Java 21 bytecode and no document says so
- Repo: KitePDF
- Area: build, distribution, docs
- Kind: api
- Severity: medium
- Confidence: verified
- Where: common.module-template.yaml:12 (`release: 21`, "The JVM and Android bytecode target"), the built class under build/artifacts (PdfDocument.class, major version 65), README.md and docs/*.md (no JDK, AGP or D8 requirement anywhere: `grep -rn "JDK\|Java 21\|Java 17" README.md docs/` finds only AWT remarks)
- What happens: Every KitePDF JVM and Android artifact is class file version 65 (Java 21). KiteJS targets Java 11 (`release: 11` in kitejs-quickjs-android-natives/module.yaml and the KiteJS README says "JVM (bytecode 11)"), and ImageKodec targets Java 11 too. A desktop or server app on JDK 17, which is still the documented baseline of Compose Multiplatform desktop and of many CI images, fails at class load with `UnsupportedClassVersionError`. An Android app whose Android Gradle plugin's D8 does not accept class file 65 fails to dex. The consumer test (consumer-test/build.gradle.kts, `jvmToolchain(21)`) would not notice either. Nothing in the README, getting-started or platforms page states the requirement, and the CHANGELOG's toolchain-migration entry does not mention a change of bytecode level, so a 0.13.0 user cannot tell whether this is new.
- What should happen: Either target Java 11 like the sibling libraries (the code uses no JDK-21 API that a `release` of 11 would reject, or it would have failed to compile for Android minSdk 21 desugaring; check with `./kotlin build` after lowering the setting), or state "JDK 21 and AGP x.y or newer" in README, getting-started and platforms, and in the CHANGELOG if 0.13.0 was lower.
- How to reproduce or evidence: `od -A d -t u1 -j 6 -N 2 <PdfDocument.class>` prints `0 65`.
- Cross-effects: Compose Multiplatform's own minimum JDK; Gradle's toolchain auto-provisioning; any consumer with `kotlin.jvmTarget` below 21 gets "inconsistent JVM target" warnings when inlining KitePDF inline functions (the API has `inline fun KiteLock.withLock`).
- Fix sketch: Set `release: 11` (or 17) in common.module-template.yaml, rebuild all targets, and add a "Requirements" paragraph to getting-started.

### XCUT-04: The native KiteLock decides reentrancy from a 32-bit hash of pthread_self, and its comment misstates the consequence of a collision
- Repo: KitePDF
- Area: kitepdf-core, src@native
- Kind: concurrency
- Severity: low
- Confidence: verified (code read; the probability is argued, not measured)
- Where: kitepdf-core/src@native/io/github/yuroyami/kitepdf/core/KiteLock.native.kt:27 (`if (owner.value == me) { depth++; return }`), :64 to :69 (`pthread_self().hashCode().toLong()`, "A collision merely risks one spurious cyclic-reference null-cache")
- What happens: `currentThreadId()` folds the 64-bit `pthread_t` into a 32-bit `hashCode` and then widens it to `Long`, so the "stable identifier" has 32 bits of entropy and `0` is remapped to `1`, which a real thread can also hash to. The same value is the lock's owner word. The comment says a collision only risks a spurious cyclic-reference null-cache, but the lock uses the value first: a thread whose hash equals the holder's walks straight into `depth++` and runs inside the critical section with the holder. Every cache of PdfDocument and EpubDocument and the RasterGate sit behind this lock. The probability per thread pair is about 2^-32 for random addresses, and lower in practice because `pthread_t` values on one process share their high bits, so this is a design wart rather than an observed bug; the cheap fix is to stop throwing away half the bits.
- What should happen: A thread identity with the full 64 bits (`pthread_self().rawValue.toLong()` on Apple where `pthread_t` is a pointer, `pthread_self().toLong()` on Linux and Android Native where it is an integer, `GetCurrentThreadId()` on MinGW), or a per-thread counter in a `@ThreadLocal` for the id. The comment should also say which structures the id protects.
- How to reproduce or evidence: Code reading of the two functions above.
- Cross-effects: `PdfDocument.activelyResolving` (the cycle guard keyed on this id), `RasterGate`, `KiteBitmapCache`, `SkiaSystemFonts`, `Hyphenator.Companion.cache`: every `KiteLock` user on iOS, macOS, Linux, Windows and Android Native.
- Fix sketch: Three-line `expect`/`actual` split per native family for `currentThreadId`, or use `kotlin.native.concurrent.Worker.current.id` where available and fall back to the pointer value.

### XCUT-05: The PlatformFlate KDoc promises a 9x fast path that only the JVM and Android have, and the measured gap is 1.2x
- Repo: KitePDF
- Area: kitepdf-core, compression
- Kind: docs
- Severity: low
- Confidence: verified (measured on this JVM)
- Where: kitepdf-core/src@jvm/io/github/yuroyami/kitepdf/core/compression/PlatformFlate.jvm.kt:8 ("Measured ~9x faster than the pure-Kotlin inflate on large streams"), kitepdf-core/src@native/io/github/yuroyami/kitepdf/core/compression/PlatformFlate.kt:5 ("the Compression framework ... is the noted follow-up"), kitepdf-core/src@js, src@wasmJs, src@wasmWasi (all three return null)
- What happens: On a 16 MB content-stream-like payload compressed to 2.0 MB, the pure-Kotlin `Inflate.decode` took 45.8 ms and `java.util.zip.Inflater` 37.4 ms on this container's JDK 21 (best of five after warm-up), a ratio of 1.2, not 9. The pure inflater is in good shape; the KDoc number is stale and a reader planning platform work would be misled. Apple, Linux and Windows targets run the pure path for every FlateDecode stream, every zip entry (EPUB, CBZ, XPS) and every WOFF2 or font stream, which is fine at 1.2x on a JIT but has not been measured on Kotlin/Native, where a C zlib (`platform.zlib` is in the Kotlin/Native distribution for Apple and Linux) could matter more because the pure path has no JIT. The doc claims "the fast path never changes WHAT is decoded", which holds: the JVM path returns null on any error and the pure path re-decodes, at the cost of decoding a decompression bomb twice up to the cap.
- What should happen: Replace the 9x claim with a measured number and the input it was measured on, and either measure the pure path on an iOS device or macOS and add a `platform.zlib` actual under src@apple and src@linux if the gap justifies it, or state in the doc that no platform fast path exists outside the JVM.
- How to reproduce or evidence: Temporary JVM test (removed) that deflated 16 MiB of operator-like text with `Deflater(6)` and timed `Inflate.decode(z, 2, z.size - 6)` against `java.util.zip.Inflater`; output `pure=45.78 ms zlib=37.45 ms ratio=1.2`.
- Cross-effects: ImageKodec carries a second pure inflater (imagekodec/internal/flate/Inflate.kt) for PNG, so a PDF with Flate images inflates through two different implementations in one process; neither is wrong, but a fix to one does not reach the other.
- Fix sketch: Update the KDoc, add a benchmark to the existing BenchmarkTest that records the ratio, and open a measured follow-up for `platform.zlib` on Apple and Linux.

### XCUT-06: A document cannot be closed, so a book's remote fetches and their scope outlive the reader's interest
- Repo: KitePDF
- Area: kitepdf-core KiteDocument, kitepdf-epub EpubRemote
- Kind: design
- Severity: medium
- Confidence: verified
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/KitePage.kt:149 (`interface KiteDocument`, no `close`, not `AutoCloseable`), kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubRemote.kt:90 (`private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`, never cancelled), :77 ("Fetches run in the store's own scope, so a caller that stops waiting ... does not cancel them"), kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt (caches with `drop*Cache` but no close)
- What happens: `KiteDocument` has no lifecycle end. PdfDocument relies on garbage collection, which is acceptable for byte arrays. EpubDocument's `RemoteResources` starts fetches in a per-book scope that nothing cancels: when the app discards the book, every in-flight download runs to completion or to the fetcher's own timeout, holding the store, the `EpubResourceFetcher` (and whatever the app captured in it, often an `HttpClient` and a view model) and up to 32 MiB of landed bytes. On a hung server with a client that has no timeout the coroutine never ends. A viewer that switches books quickly stacks these. `EpubScriptSession` and `PdfScriptRunner` are `AutoCloseable`, so the project already has the pattern for the pieces that own threads, but the document that owns the scope has none, and an app has no single call that frees a document's background work.
- What should happen: `KiteDocument` should extend `AutoCloseable` (default no-op, so third-party implementors do not break), `EpubDocument.close()` should cancel the remote scope and release layout caches, `PdfDocument.close()` should drop its caches, and the viewer's `DisposableEffect` should call it when it owns the document (the deprecated `KiteDocView(document)` overload) or document that it does not. Idempotent and safe after close: a page render after close should throw a clear `IllegalStateException` or degrade to a placeholder, not corrupt a cache.
- How to reproduce or evidence: `grep -rn "fun close\|AutoCloseable" kitepdf-pdf/src kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt` finds none on the documents; EpubRemote.kt:90 and :110 show the uncancelled scope and the lazily started `async`.
- Cross-effects: kitepdf-net's fetcher has no timeout of its own (it defers to the caller's Ktor client, documented), so the lifetime of a stuck fetch is the client's; `KiteDocViewState` keeps a reference to the document for its life; `withSettings` makes a new EpubDocument sharing the parse and, presumably, the store, so one scope serves several documents (check which one would own close).
- Fix sketch: Add `close()` with a `closed` flag, cancel `scope` in RemoteResources, and document ownership: "the app that opened the document closes it".

### XCUT-07: The core module's public API is 257 classes, most of them engine plumbing with no opt-in, and the ABI dumps freeze them
- Repo: KitePDF
- Area: kitepdf-core public API
- Kind: api
- Severity: low
- Confidence: verified
- Where: kitepdf-core/api/jvm/kitepdf-core.api (257 public classes and objects: `ByteArrayBuilder`, `ByteReader`, `KiteLock`, `Inflate`, `Deflate`, `Zlib`, `Lexer`, `Token`, `PdfObject` and its subclasses, `FilterChain`, `CffFont`, `TrueTypeFont`, `TtfReader`, `CffSubsetter`, `GraphicsStack`, `KiteRasterCanvas`, `ZipReader`, `Crc32`, ...), kitepdf-core/src/io/github/yuroyami/kitepdf/core/KiteLock.kt:8 ("Engine-internal plumbing that must cross module boundaries ... not a public concurrency API"), `KiteRawApi` used on 9 declarations only
- What happens: Because Kotlin has no friend-module visibility, everything the handler modules need from core is `public`, and the project's own ABI check then treats each of those classes as a compatibility promise (`./kotlin check abi` fails on any change). The KDoc of `KiteLock` says it is not public API, but a consumer can call it, Dokka documents it, and the Swift framework header exports it. The one opt-in annotation that exists, `KiteRawApi`, marks nine members. So an internal refactor of the lexer, the inflater or the font parser is an "API change by accident" by the project's own rule, and the real public surface (what an app should use) is not distinguishable from plumbing in the API reference.
- What should happen: A `@KiteInternalApi` `RequiresOptIn` annotation on the plumbing (the ABI tool can then exclude it, and the opt-in compiler flag in the handler modules' module.yaml already exists for `KiteRawApi`), or a `kitepdf-internal` module name convention plus a Dokka `suppress` filter, so that the API dumps and the docs show the supported surface only.
- How to reproduce or evidence: `grep -cE '^public (final |abstract |open )?(class|interface|object) ' kitepdf-core/api/jvm/kitepdf-core.api` gives 257; `grep -rn "KiteRawApi" kitepdf-core/src kitepdf-pdf/src | grep -c "@"` gives 9.
- Cross-effects: XCUT-03 (every public inline function pins the bytecode level into consumers), the Swift and ObjC header size, Dokka site size, the `-Xexport-kdoc` flag exporting plumbing docs into frameworks.
- Fix sketch: Add the annotation, apply it to the compression, parser, zip, xml and font packages, add the opt-in to the module.yaml of every KitePDF module, and regenerate the dumps once.

### XCUT-08: The warning sink is one process-global mutable field
- Repo: KitePDF
- Area: kitepdf-core KiteWarnings
- Kind: design
- Severity: info
- Confidence: verified
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/KiteWarnings.kt:17 (`public object KiteWarnings { @Volatile public var sink }`), :29 (`kiteWarn`)
- What happens: Every lenient-salvage diagnostic of every document in the process goes to one sink with a plain `String` and no document, page or severity. Two libraries in one app that both set the sink overwrite each other, and a server that renders many documents on many threads cannot attribute a warning to a request. The design is a deliberate `fz_warn` equivalent and is documented as a debugging aid, so this is a note, not a bug.
- What should happen: Keep the global as a default, and let `PdfDocument.open` and `KiteDoc.open` take an optional per-document sink (or a `KiteDiagnostics` object with the document identity and page index), so that salvage events are attributable.
- How to reproduce or evidence: Code reading.
- Cross-effects: Any future structured-diagnostics or "repair report" feature; the W3C conformance runner and the differential sweep, which could use attributable warnings.
- Fix sketch: `KiteWarningSink` gains a default-method overload with context; documents hold an optional sink and fall back to the global.

### Capabilities and opportunities (not defects)
- A `platform.zlib` actual for Apple and Linux is a measured follow-up, not a given (XCUT-05): measure the pure path on a device first.
- A document lifecycle (`close`, `AutoCloseable`) is a prerequisite for a bounded memory story on mobile (XCUT-06), and a prerequisite for the viewer to own documents it opened for the caller.
- A per-document diagnostics channel (XCUT-08) would make "why does this page look wrong" answerable from an app without a debugger.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane VIEWER-API: public API and parameter semantics of kitepdf-compose-viewer

Scope read end to end: `KiteDocView.kt`, `KiteDocViewSpecs.kt`, `KiteDocViewState.kt`, `Deprecated.kt`, `KiteGestures.kt`, `KiteWidgets.kt`, `KitePageOverlay.kt`, `KiteFormLayer.kt`, `KiteFormInput.kt`, `KiteChoiceInput.kt`, `KiteSelectionMenu.kt`, `KiteExternalLinks.kt`, `KiteLinkAction.kt`, `KitePageSemantics.kt`, `KiteScrollPosition.kt`, `KiteViewerStrings.kt`, `KiteMarkupErrorNotice.kt`, `SpreadPlan.kt`, `ChapterLoader.kt`, `KitePageRasterizer.kt` (the cache key and the `remember` wiring only), `PageBitmapCache.kt`, both API dumps, `docs/compose-viewer.md`, the viewer section of `README.md`, and the JVM scene tests named below. Nothing was run: the toolchain built `kitepdf-pdf` only, and a Compose scene test of this module needs the Skiko runtime, so every finding is from reading.

### VIEWER-API-01: The pager layouts give every page slot its own rasterizer, so the page-bitmap cache never hits for a page that left the window
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocView.kt` (Paged, Spread, SinglePage), `KitePageRasterizer.kt`, `PageBitmapCache.kt`
- Kind: performance | memory
- Severity: high
- Confidence: likely (the mechanism is stated in the module's own comment for the strip, and the pager layouts do not use that fix; not run)
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1369-1374` (per-slot `rememberKitePageRasterizer`), `:1330-1333` (`PageBox` calls `PageSlotContent` without `sharedRasterizer`), `:2318-2321` (`SpreadBox` the same), `:795-801` (the strip's comment on why one rasterizer must be shared), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageRasterizer.kt:241-255` (the cache key holds `fontEnvironment = textMeasurer`), `:497-505` (`rememberKitePageRasterizer` calls `rememberTextMeasurer()` at its own call site), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/PageBitmapCache.kt:38`
- What happens: `KitePageRaster` takes `sharedRasterizer ?: rememberKitePageRasterizer(...)`. Only `ContinuousLayout` passes a shared rasterizer; `PagedLayout`, `SpreadLayout` and `SinglePageLayout` go through `PageBox` or `SpreadBox`, which call `PageSlotContent` with the default `sharedRasterizer = null`. So each page slot composed by a pager gets a `KitePageRasterizer` of its own, with a `TextMeasurer` of its own from `rememberTextMeasurer()`. `PageBitmapCache.Key` includes that measurer as `fontEnvironment` (#421), compared by identity. When a page leaves the pager's window (`offscreenPages` on each side) its slot composition is released, and when the reader swipes back the slot is composed again with a new measurer, so the lookup misses: the 96 MB `cacheBudgetBytes` LRU (default) fills with bitmaps under keys that no live slot can ever produce again, and every swipe back is a fresh raster. With `offscreenPages = 0` every single swipe re-rasterizes. The strip had exactly this defect and fixed it in #437 by sharing one rasterizer per layout ("the bitmap cache key holds the rasterizer's text measurer, so a slot finds what another slot or the strip drew"). Each per-slot rasterizer also carries its own `GlyphMaskCache`, so glyph coverage is rebuilt per slot as well.
- What should happen: `cacheBudgetBytes` should do in a pager what `docs/compose-viewer.md:329` says it does: "a page that comes back on screen needs no new render". One rasterizer per layout (as the strip and the thumbnail strip already do) makes the key stable across slot lifetimes.
- How to reproduce or evidence: `KiteDocView(state, layout = KiteDocLayout.Paged(offscreenPages = 0), onPageRendered = { i, _ -> renders += i })`, open a 3-page PDF, `scrollToPage(1)`, then `scrollToPage(0)`: `renders` holds 0 twice (fresh raster both times, since `fresh` is true on a miss), and `state.bitmapCacheFor(budget).size` grows by one entry per visit. No test covers a swipe back in a pager: the only cache-hit scene test is `ContinuousPrefetchSceneTest` (strip).
- Cross-effects: `offscreenPages` (the smaller, the worse), `cacheBudgetBytes` (held but useless), `rerasterizeOnZoom` (each zoom bucket is cached per slot lifetime only), Android's 192 MB heap (the LRU evicts, but holds up to the budget of unreachable bitmaps), `KiteThumbnailStrip` is unaffected (one rasterizer per strip).
- Fix sketch: Create the rasterizer once in `PagedLayout`, `SpreadLayout` and `SinglePageLayout` (keyed on `maxBitmapLongSide` as the strip does) and pass it down as `sharedRasterizer`; add a pager scene test that swipes away and back and asserts one raster per page.

### VIEWER-API-02: With `panEnabled = false` a page that overflows the viewport cannot be moved or turned by touch; `KiteZoomSpec.Disabled` with a scrolled-doc EPUB strands the reader
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocView.kt` (PagedLayout, SpreadLayout), `KiteGestures.kt`, `KiteDocViewState.overflows`
- Kind: bug | design
- Severity: high
- Confidence: verified (read the whole gesture path)
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1165-1168` (`pagerScrollEnabled = userScrollEnabled && !overflows && ...`), `:2188-2191` (Spread), `:1312-1326` (`fitWidth` makes a tall page's geometry taller than the viewport), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:445-450` (`overflows` reads only zoom and geometry, never `panEnabled`), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:295-296` (`if (!spec.panEnabled) return@pointerInput`), `:266` (two-finger pan gated on `panEnabled` too), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:216-224` (`Disabled` sets `panEnabled = false`)
- What happens: A pager turns its own swipe off whenever the content overflows the viewport, so that the one-finger drag can pan instead. The drag handler is attached only when `spec.panEnabled` is true. `overflows` does not know about `panEnabled`. So with `panEnabled = false` and an overflowing page, a one-finger drag reaches neither the pan nor the pager: the reader cannot move the page and cannot turn it. Two cases reach this with documented inputs. (a) `layout = KiteDocLayout.forDocument(book)` for a `rendition:flow` `scrolled-doc` book gives `Paged(fit = KitePageFit.WIDTH)`; a chapter taller than the viewport overflows at zoom 1 (`tall` at line 1317 reports a page geometry taller than the viewport, and `panRoom` gives `slack.y > 0`). Add `zoomSpec = KiteZoomSpec.Disabled` ("No zoom at all", `KiteDocViewSpecs.kt:217`) and a touch reader can neither scroll down the chapter nor swipe to the next one. The wheel still works on a desktop (`kiteWheelPaging` pans on `overflows` regardless of `panEnabled`, `KiteGestures.kt:464-471`), the keyboard turns pages, and a tap on a link still navigates, so the dead end is touch-only. (b) `KiteZoomSpec(pinchEnabled = true, panEnabled = false)`: a pinch zooms in, the page overflows, the pager swipe goes off, and the reader is stuck on the zoomed corner until they pinch out.
- What should happen: Either `overflows` should count only axes the reader can pan (`panAxes && panEnabled`), so a pager with pan off keeps its swipe, or `KiteZoomSpec` should refuse `panEnabled = false` together with a layout or zoom that can overflow, or `KiteZoomSpec.Disabled` should keep `panEnabled = true` (pan without zoom is still meaningful for `KitePageFit.WIDTH`).
- How to reproduce or evidence: `ScrolledFlowSceneTest.a_chapter_fills_the_width_starts_at_its_top_and_scrolls_down` with `zoomSpec = KiteZoomSpec.Disabled` added: the drag at line 102 moves `panOffset.y` by 0, and a sideways drag (test at line 110) no longer reaches page 1. `MinZoomSceneTest.a_page_that_fits_turns_at_any_zoom_below_fit` uses `panEnabled = false` only with `maxZoom = 0.8f`, where nothing overflows, so the suite never meets the dead end.
- Cross-effects: `KiteZoomSpec.Disabled`, `KitePageFit.WIDTH`, `KiteDocLayout.forDocument` (scrolled-doc), `Spread`, `userScrollEnabled` (true here and still useless), `minZoom > 1` (always overflowing), mouse and keyboard (unaffected, which hides the bug on the desktop test host).
- Fix sketch: In `PagedLayout` and `SpreadLayout` compute `pagerScrollEnabled = userScrollEnabled && !(overflows && zoomSpec.panEnabled) && ...`, and document that `Disabled` keeps the pan of an overflowing page.

### VIEWER-API-03: `reverseLayout` is relative to `LocalLayoutDirection` for the swipe but absolute for the spread order and the document direction
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocViewSpecs.kt`, `KiteDocView.kt` (PagedLayout, SpreadLayout, SpreadBox), `SpreadPlan.kt`
- Kind: bug | api
- Severity: medium
- Confidence: verified by reading (Compose's `HorizontalPager` mirrors in an RTL layout direction, which the module's own `PageTurn.reversed` and `keyAction.forwardIsLeft` XOR terms and two RTL tests rely on)
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:68-74` (KDoc: "page N+1 sits visually LEFT of page N"), `:132-138` (`pagedFor` sets `reverseLayout` from `document.metadata.rightToLeft`), `:96-98` (Spread KDoc), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1125` and `:2185` (`reversed = (horizontal && direction == Rtl) != layout.reverseLayout`), `:1171-1181` and `:2220-2227` (the pagers take `reverseLayout` raw), `:2203` and `:2332-2335` (`SpreadBox` places the first page from the physical left by `reverseOrder = layout.reverseLayout`), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/SpreadPlan.kt:106` (`pairSpreads(rightToLeft = layout.reverseLayout)`), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:558`
- What happens: Compose's `HorizontalPager(reverseLayout = x)` lays pages out relative to the layout direction: in an RTL app (Arabic, Hebrew, Persian locale) `reverseLayout = false` already puts page N+1 to the left, and `reverseLayout = true` puts it to the right. The viewer passes `layout.reverseLayout` straight through, and its own edge-drag and keyboard code XOR the layout direction in to keep up (`PageTurn.reversed`, `forwardIsLeft`). Three things do not: the KDoc promises an absolute direction; `pagedFor(document)` derives `reverseLayout` from the document's page progression, which is a property of the book and not of the UI locale, so in an RTL app an Arabic book pages left to right and an English PDF pages right to left; and `SpreadBox` places the two pages of a spread from the physical left by `reverseOrder = layout.reverseLayout`, with `pairSpreads` choosing the opening side the same way. So in an RTL app `Spread()` shows page 0 on the left and page 1 on the right (reading order LTR inside the spread) while the next spread arrives from the left (RTL progression), and `Spread(reverseLayout = true)` does the reverse. The in-spread order and the progression disagree in every RTL app.
- What should happen: A document's page progression is absolute (EPUB 3.3 `page-progression-direction`, ISO 32000-1 `/ViewerPreferences /Direction`). Either the pager runs under `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` so `reverseLayout` means what the KDoc says and `pagedFor` is right in every locale, or the spread order and the pairing side must follow the same effective direction as the pager.
- How to reproduce or evidence: `SpreadRtlHitSceneTest` wraps `Spread(reverseLayout = reverse)` in `LocalLayoutDirection provides Rtl` and asserts only that the hit test agrees with the drawn page; it never checks that the next spread comes from the side the in-spread order implies. `ZoomedPanSceneTest.in_a_right_to_left_pager_a_drag_past_the_left_edge_turns_forward` and `KeyboardAndWheelSceneTest.the_left_arrow_goes_forward_where_pages_advance_to_the_left` both run `Paged()` (reverseLayout false) under `Rtl` and assert that forward is to the left, which is the pager mirroring. With `Paged(reverseLayout = true)` under `Rtl` forward is to the right, which `pagedFor` gives an RTL book.
- Cross-effects: `LocalLayoutDirection` (host locale), `KiteDocLayout.pagedFor`, `KiteDocLayout.forDocument`, `Spread.reverseLayout`, `Spread.firstPageAlone`, `KitePageFit.WIDTH` + reversed pager (same confusion), `KiteScrollPosition` KDoc ("including right-to-left layout direction").
- Fix sketch: Provide `LayoutDirection.Ltr` to the pagers and lists inside `KiteDocView` so `reverseLayout` is absolute, drop the two XOR terms, and add a scene test that runs an RTL document in an RTL app and asserts page 1 is to the left of page 0 in both `Paged` and `Spread`.

### VIEWER-API-04: `userScrollEnabled = false` does not stop the drag past the edge of a zoomed page from turning it
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocView.kt` (PagedLayout, SpreadLayout), `KiteGestures.kt`
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1123-1126` and `:1146-1148` (`pageTurn` built and passed whatever `userScrollEnabled` says), `:2183-2186` and `:2206-2208` (Spread), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:327-332` (`if (pageTurn != null && abs(pastEdge) >= PAGE_TURN_DRAG.toPx()) scope.launch { state.turn(forward, pageTurn.spread) }`), KDoc at `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:165-168`
- What happens: The KDoc of `userScrollEnabled` says it governs "gesture scrolling/swiping of the layout itself, the wheel ... and the page keys", and "Disable to drive paging exclusively through KiteDocViewState". The pager's own swipe, the wheel (`kiteWheelPaging(enabled = userScrollEnabled)`, line 455) and the keys (`keyAction(paging = userScrollEnabled)`, line 449) honour it. The edge turn of a zoomed page does not: `PagedLayout` and `SpreadLayout` always hand `pageTurn` to `kiteTransformGestures`, and a one-finger drag that reaches the edge of a zoomed page and goes on for 64 dp calls `state.turn(...)`. A kiosk or a lesson app that locked paging to its own buttons still turns pages under a finger once the reader has zoomed.
- What should happen: No gesture turns the page while `userScrollEnabled` is false (README line 163: "false leaves paging to the state").
- How to reproduce or evidence: `ZoomedPanSceneTest.a_drag_past_the_edge_of_a_zoomed_page_turns_it` with `userScrollEnabled = false` added to the `KiteDocView` call at line 58 still ends at `currentPage == 1`. No test combines `userScrollEnabled = false` with a zoomed page.
- Cross-effects: `KiteZoomSpec.panEnabled` (the turn lives inside the pan handler), `Spread`, `KitePageFit.WIDTH` (a tall page overflows at zoom 1, so the sideways edge turn is reachable without any zoom).
- Fix sketch: Pass `pageTurn = if (userScrollEnabled) pageTurn else null` in both pagers, and add the case to `GestureFlagsSceneTest`.

### VIEWER-API-05: `onPageRendered` never fires for a page the strip drew ahead, so with the default `prefetchPages = 1` most pages of a slowly read document are never reported
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocView.kt` (ContinuousLayout prefetch, KitePageRaster)
- Kind: docs | design
- Severity: medium
- Confidence: verified (a test asserts the behaviour)
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:822-853` (the prefetch loop calls `rasterizeCachedOrNull` and reports nothing), `:1454` (`ReportFreshRaster` runs only in `KitePageRaster`), `:169-175` (KDoc), `docs/compose-viewer.md:94`, `:975-988` ("The viewer calls it once for each page and bitmap size"), `kitepdf-compose-viewer/test@jvm/io/github/yuroyami/kitepdf/compose/ContinuousPrefetchSceneTest.kt:36-57`
- What happens: The strip's prefetch rasterizes the next `prefetchPages` slots into the LRU once the strip rests. That raster is fresh, but the loop discards `PageRaster.fresh`. When the reader scrolls on, the slot finds the bitmap in the cache, `fresh` is false, and `ReportFreshRaster` stays silent. The test `the_strip_draws_the_next_page_ahead_once_it_rests` asserts exactly this: after `scrollToPage(1)` the callback list is still `[0]`. So in the default layout (`Continuous(prefetchPages = 1)`) a reader who scrolls at reading speed has every page but the first ones prefetched, and the callback the docs recommend for thumbnails (`thumbnails[pageIndex] = bitmap`, `docs/compose-viewer.md:982-984`) fills in only the pages reached by a fast fling. The KDoc "fires whenever a page finishes a FRESH screen raster: once per page and bitmap size" is not kept.
- What should happen: Either the prefetch reports its fresh raster through `onPageRendered` (it is the same bitmap that will show), or the KDoc and the docs say that a page drawn ahead is never reported and `prefetchPages = 0` is needed for a complete sequence.
- How to reproduce or evidence: `ContinuousPrefetchSceneTest.kt:51` and `:55` lock the gap in as intended behaviour; `KiteDocViewSceneTest` sees both pages only because both are on screen in the first frame.
- Cross-effects: `Continuous.prefetchPages`, `cacheBudgetBytes` (a cache hit is what hides the raster), `Paged.offscreenPages` (a pager's offscreen slots do report, so the two layouts disagree), `rerasterizeOnZoom` (zoomed rasters are per slot and do report).
- Fix sketch: Thread `onPageRendered` into the prefetch loop and call it when `drawn.fresh`, or state the exception in both KDoc and docs and in the thumbnails example.

### VIEWER-API-06: Two `KiteDocView` composables on one state fight over the adapter, the zoom range, the pan axes and the kept chapters, and nothing documents or refuses it
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocView.kt`, `KiteDocViewState.kt`
- Kind: design | api
- Severity: medium
- Confidence: verified
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:266-296` (the `SideEffect` writes `scripts`, `scriptScope`, `scriptLane`, `externalLinks`, `zoomRange`, `panAxes`, `selectionEnabled` into the state on every composition), `:318` (`attachViewerThread`), `:334-336` and `:367` (dispose of one view clears the kept chapters, the vector image cache and the focused field of the other), `:1082-1088`, `:1228-1235`, `:2153-2161` (each layout sets `state.adapter`), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1099` (`canNavigate = adapter !is FixedPageAdapter`), `:1824-1836`
- What happens: The state is documented as the hoisted control surface, and the widgets are documented to share it, but nothing says a state may be shown by one `KiteDocView` only. A host that adds a preview `KiteDocView(state, layout = KiteDocLayout.SinglePage(n))` beside the main view gets: `state.adapter` set by whichever layout composed last, so `currentPage`, `scrollToPage` and `nextPage` drive one view only; if the preview wins, `canNavigate` is false for the whole state, `KiteNavigationControls` greys out and every internal link of the main view falls through to `onTap` (KDoc `:206-207`); `zoomRange` and `panAxes` flip between the two views' specs on every recomposition (a `Continuous` and a `Paged` view give `XOnly` against `Both`); disposing either view calls `keepChapters(emptySet())` on the shared document while the other still shows it, so a budgeted EPUB may drop the chapter on screen (#377), and `blurFocusedField` commits the other view's field.
- What should happen: One of: the KDoc of `KiteDocViewState` and `KiteDocView` states that a state belongs to one view at a time, and `attachViewerThread` warns through `kiteWarn` when a second view attaches while one is attached; or the per-view inputs (`zoomRange`, `panAxes`, `adapter`, scripts) move into a per-view object the state only points at.
- How to reproduce or evidence: Compose `Column { KiteDocView(state, layout = Paged()); KiteDocView(state, layout = SinglePage(0)) }` on a 3-page PDF: `state.canNavigate` is false, `KiteNavigationControls(state)` disables both chevrons, and `state.scrollToPage(2)` moves nothing visible in the pager (the `FixedPageAdapter` ignores it).
- Cross-effects: every layout parameter, `KiteZoomSpec`, `selectionEnabled` (one view's false clears the other's selection every composition), `scripts` (the script lane and scope are the last view's), `KiteThumbnailStrip` (fine: it sets no adapter).
- Fix sketch: Add a `kiteWarn` in `attachViewerThread` when `viewerThread != null`, and say in the KDoc of `rememberKiteDocViewState` that widgets may share a state but only one `KiteDocView` shows it.

### VIEWER-API-07: The spec `require` checks stop at NaN and sign: infinite `maxZoom`, `minZoom`, `quality` and `hairlineWidthPx`, a negative `cacheBudgetBytes` and an unspecified `pageSpacing` are accepted, and a `Continuous` strip empties a zoom range below 1 in silence
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocViewSpecs.kt`, `KiteDocView.kt`
- Kind: api | bug
- Severity: low
- Confidence: verified for the accepted values; the downstream effects are read, not run
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:210-214` (`KiteZoomSpec.init`), `:284-287` (`Rasterized.init`), `:341-344` (`Vectorized.init`), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:285-287` (`floor..maxOf(floor, zoomSpec.maxZoom)`), `:1474-1496` (`rasterPlanFor`), `:1447-1448` and `:1537-1594` (`full` and `PageTiles`), `:908` and `:1174` (`pageSpacing` reaches `Arrangement.spacedBy` and `HorizontalPager`)
- What happens: `KiteZoomSpec` refuses `doubleTapZoom` that is NaN or infinite (#338) but only checks `minZoom > 0` and `maxZoom >= minZoom`, so `KiteZoomSpec(maxZoom = Float.POSITIVE_INFINITY)`, the natural spelling of "no limit", is accepted, and so is `minZoom = maxZoom = Infinity`. With an unbounded range a pinch can drive `zoom` to 1e30; `zoomBucket` then overflows to infinity, `rasterPlanFor` gives `scale = Infinity`, `full` rounds to `Int.MAX_VALUE` on both sides, and `PageTiles` asks the rasterizer for 48 tiles of a page drawn at `Int.MAX_VALUE` pixels (each attempt is guarded and retried once, so the cost is 96 failed or absurd rasters per settle, not a crash). `Rasterized(quality = Float.POSITIVE_INFINITY)` and `Vectorized(hairlineWidthPx = Float.POSITIVE_INFINITY)` pass too, although `KitePageRasterizer.rasterizeInternal` later requires a finite hairline (`KitePageRasterizer.kt:424-426`), so the Vectorized form layer is the only path that draws an infinite stroke. `Rasterized.cacheBudgetBytes` has no check where `Vectorized.imageCacheBudgetBytes` has one; a negative budget is read as "off". `pageSpacing` reaches `Arrangement.spacedBy` and the pagers unchecked: `Dp.Unspecified` is NaN and `roundToPx` throws inside layout. Separately, the `SideEffect` raises the floor of a `Continuous` strip to 1 and then clamps the ceiling to the floor, so `KiteZoomSpec(minZoom = 0.5f, maxZoom = 0.8f)` with the default layout becomes the range `1..1` with the pinch handler still attached and doing nothing; the KDoc of `KiteZoomSpec` says the bounds apply to "all zoom changes" and does not mention the strip's floor.
- What should happen: The specs are the one place a bad value can be refused with a message, which is what #338 started: `require(minZoom.isFinite() && maxZoom.isFinite())`, `require(quality.isFinite())`, `require(hairlineWidthPx.isFinite())`, `require(cacheBudgetBytes >= 0)` (or document that negative means off), and a finite non-negative `pageSpacing` (or a documented meaning for a negative one). The `Continuous` floor belongs in the `KiteZoomSpec` KDoc next to `minZoom`.
- How to reproduce or evidence: `KiteZoomSpec(maxZoom = Float.POSITIVE_INFINITY)` constructs; `ViewerInputLimitsSceneTest.specs_refuse_values_the_viewer_cannot_use` (lines 97-105) lists what is refused and shows the gap.
- Cross-effects: `maxBitmapLongSide` (caps the whole bitmap but not the tile scale), `rerasterizeOnZoom`, `KitePageFit`, `Continuous` + `minZoom < 1`.
- Fix sketch: Extend the three `init` blocks with the finite checks above, add `require(pageSpacing.isSpecified && pageSpacing >= 0.dp)` or a documented clamp in `KiteDocView`, and extend `specs_refuse_values_the_viewer_cannot_use`.

### VIEWER-API-08: With `minZoom < 1`, `resetZoomOnPageChange` goes to fit, not to `minZoom` as its KDoc says, and a double tap at fit zooms out
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocViewSpecs.kt`, `KiteDocViewState.kt`, `KiteGestures.kt`
- Kind: docs | design
- Severity: low
- Confidence: verified (`MinZoomSceneTest.a_minimum_below_fit_still_opens_and_turns_at_fit` asserts the reset to 1)
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:195-198` (KDoc: "snap zoom back to [minZoom]"), `:189` ("double-tap toggles between [minZoom] and [doubleTapZoom]"), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1341-1345` (`resetZoom` goes to `1f` clamped), `:439` (`isZoomed = zoom > zoomRange.start + EPSILON`), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:239-244` (`target = if (state.isZoomed) spec.minZoom else spec.doubleTapZoom`), `docs/compose-viewer.md:140-141` and `:258-259`
- What happens: #398 let a range start below 1 and kept a page turn at fit. The KDoc of `resetZoomOnPageChange` and two lines of the docs still say `minZoom`. Worse for the reader: at fit (zoom 1) with `minZoom = 0.5f`, `isZoomed` is true, so the first double tap after every page turn shrinks the page to 0.5 instead of zooming to `doubleTapZoom`; the second one zooms in. A reader who turns pages and double-taps to read sees the page jump small every time.
- What should happen: The KDoc and docs should say "back to fit (zoom 1), clamped into the range". The double tap should treat fit as the rest state when the range starts below it: `if (zoom > 1 + eps) 1 else doubleTapZoom`, or toggle between fit and `doubleTapZoom` and leave zooming out to the pinch.
- How to reproduce or evidence: `MinZoomSceneTest.kt:110-133` shows the reset to 1; `KiteZoomSpec(minZoom = 0.5f)` with `doubleTapEnabled = true` and one double tap on a freshly turned page animates to 0.5 (`KiteGestures.kt:241`).
- Cross-effects: `minZoom < 1` is only reachable in `Paged`, `Spread` and `SinglePage` (the strip floors at 1), `doubleTapZoom`, `resetZoomOnPageChange`.
- Fix sketch: Fix the two KDoc sentences and the docs, and base the double-tap toggle on `zoom > 1f` when `zoomRange.start < 1f`.

### VIEWER-API-09: The page's accessibility nodes are rebuilt on every form revision, with an O(n²) widget scan, and the page container carries the Image role over text, links and fields
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KitePageSemantics.kt`, `KiteDocView.kt`
- Kind: performance | design
- Severity: medium
- Confidence: likely (read; the cost is not measured)
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageSemantics.kt:157` (`LaunchedEffect(page, formState, strings, state.formRevision)`), `:93-122` (one `page.widgetAt` per Widget annotation), `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPage.kt:322-334` (`widgetAt` scans every annotation), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1027-1030` (`role = Role.Image` on the page slot), `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1023-1028` (`formRevision` KDoc: a script that writes a field twenty times a second costs twenty overlay draws)
- What happens: `PageSemantics` keys its effect on `state.formRevision`, so every form change restarts it, waits for the view to rest, then runs `accessNodes` on the raster dispatcher: `readingOrder()` and `textContent()` are cached by the page, but the field loop calls `page.widgetAt(centre, formState)` for each Widget annotation, and `widgetAt` walks every annotation of the page, so a page with n widgets costs n² rectangle tests per revision. A script that writes fields continuously (a clock, a game such as DoomPDF with its hundreds of widgets) keeps a pool thread busy rebuilding semantics nobody asked for, in step with the form, on every page on screen. The `formRevision` KDoc counts the overlay draws as the cost of such a script and does not count this. Separately, the page slot is a node named "Page 3 of 12" with `role = Role.Image`; its children are text, links as buttons and fields. A screen reader announces a page of text and form fields as an image.
- What should happen: Semantics should follow the form at a screen reader's pace, not the script's: debounce the rebuild (the view already waits for rest) or rebuild only the field nodes from the live `formState` without re-reading the page, and compute the widget list in one pass over `annotationEntries` instead of n `widgetAt` calls. The container should be a traversal group with a name (`isTraversalGroup = true`, as `PageSemantics` itself sets on its layout) rather than an image.
- How to reproduce or evidence: `PageSemanticsSceneTest` builds the nodes once; no test counts rebuilds under a running script. `AccessibilityLabelsSceneTest.kt:71` asserts the Image role on the page node, so the role is intended but questionable.
- Cross-effects: `scripts` (the only path that moves `formRevision`), `Paged.offscreenPages` (nodes are gated on `shown`, so offscreen slots do not rebuild), the raster dispatcher (VIEWER-RUNTIME lane: this work competes with page rasters for the pool).
- Fix sketch: Key the effect on a debounced revision (`snapshotFlow { formRevision }.debounce(...)`) and build the widget list from `annotationEntries` with the form's hidden overrides in one pass; replace `Role.Image` on the slot with `isTraversalGroup = true`.

### VIEWER-API-10: KDoc and docs claims the code does not keep (layout list, placeholders in Vectorized mode, "no settings that silently do nothing", cache budget, pageSpacing in a spread)
- Repo: KitePDF
- Area: kitepdf-compose-viewer, `KiteDocView.kt`, `KiteDocViewSpecs.kt`, `docs/compose-viewer.md`, `README.md`
- Kind: docs
- Severity: low
- Confidence: verified
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:147-148`, `:176-177`, `:1035-1051`, `:156-157`, `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:228-232`, `:54-56`, `docs/compose-viewer.md:92`, `:329`, `:1042`, `README.md:163`
- What happens: (a) The `layout` KDoc lists "continuous strip (any orientation), snap pager (any orientation) or a single fixed page" and leaves `Spread` out. (b) `pagePlaceholder` is documented as "shown in a page's slot until its raster is ready" with no mode qualifier, but `PageSlotContent` passes it only to `KitePageRaster`; in `Vectorized` mode it is never composed, including for a page whose chapter the layout budget dropped, which shows bare paper while its content comes back (#377). `pageRenderState` and `retryPage` say "Rasterized mode only" and `onPageRendered` says it never fires in Vectorized, so the placeholder is the one that forgot to say so. (c) `KiteRenderSpec`'s KDoc promises "each carries only the knobs that actually apply to it, so there are no settings that silently do nothing", while four view-level inputs do nothing in Vectorized mode (`onPageRendered`, `pagePlaceholder`, `pageRenderState`, `retryPage`) and `resetZoomOnPageChange` does nothing in `Continuous` and `SinglePage`. (d) `docs/compose-viewer.md:329` says the page-bitmap cache means "a page that comes back on screen needs no new render", false in the pager layouts (VIEWER-API-01). (e) `docs/compose-viewer.md:92` says `userScrollEnabled` governs "a swipe", false for the edge turn (VIEWER-API-04). (f) `pageSpacing` is "the gap between two pages" and "pager spacing"; in `Spread` it is the gap between two spreads, and the two pages of a spread touch (`SpreadBox` places them at `0..fullW/2` and `fullW/2..fullW`). (g) The `Paged` KDoc says "Each page is letterboxed to fit the viewport", which `fit = KitePageFit.WIDTH` contradicts in the same class.
- What should happen: Each sentence should match the code, or the code the sentence; the house rule is that every public API change documents its behaviour.
- How to reproduce or evidence: The cited lines.
- Cross-effects: none beyond the findings above.
- Fix sketch: One documentation pass over `KiteDocView`'s parameter list and `KiteDocViewSpecs`, stating the render mode each callback and placeholder applies to, adding `Spread` to the layout list, and describing `pageSpacing` for spreads.

### Capabilities and opportunities (not defects)

- A `KiteDocViewState.zoomRange` read-only property. The effective bounds are internal, so a host building a zoom slider against `KiteZoomSpec` cannot learn that a `Continuous` strip raised the floor to 1 (VIEWER-API-07). Small change, every host with a slider benefits.
- An explicit page progression on `Paged` and `Spread` (`KitePageProgression.LTR`, `RTL`, `FromDocument`) in place of the Compose-relative `reverseLayout` boolean, with `LocalLayoutDirection` made irrelevant inside the viewer. It would make `pagedFor` correct in RTL apps and let `Spread` pair and place from one source of truth (VIEWER-API-03). Medium cost (a deprecation cycle for `reverseLayout`).
- `pagePlaceholder` in `Vectorized` mode for a page whose content is not in memory (dropped chapter, remote picture pending), where today the paper shows. Small cost.
- A `onPageShown(slot)` or "page settled" callback independent of rasters, for hosts that today misuse `onPageRendered` to learn which pages were visited (VIEWER-API-05 shows why the raster callback cannot serve that).
- A documented guard for a second view on one state, or a per-view object (VIEWER-API-06). The first part is a one-line `kiteWarn`.
- Keyboard copy (Ctrl or Cmd with C) is left to the host by design and documented; a `KiteSelectionMenuItem` preset for it would remove a copy of the same six lines from every app.

### Hand-offs to VIEWER-RUNTIME

- `PageTiles` holds up to `MAX_TILES = 48` tiles of 1024 by 1024 ARGB (192 MB at the limit) in a `mutableStateMapOf` per page slot (`KiteDocView.kt:1537-1594`); the practical bound is the screen size, but a 4K desktop at deep zoom reaches 20 tiles per visible page.
- Each per-slot rasterizer in the pager layouts also means a `GlyphMaskCache` per slot (VIEWER-API-01); the runtime cost of filling glyph masks per slot is for that lane.
- `accessNodes` runs on `kitepdfRasterDispatcher()` per form revision (VIEWER-API-09) and competes with page rasters for the pool, or with the UI thread in a browser.
- `KitePageVector` allocates `KiteBitmapCache()` with its default budget on every draw when `imageCacheBudgetBytes = 0` (`KiteDocView.kt:1718`): the cache is garbage after the frame, but each frame converts and holds every image of the page until collected.
- `KiteDocViewState.scriptScope` keeps the last view's `rememberCoroutineScope()`; after the view leaves, `post()` launches on a cancelled scope and the script call is dropped in silence (`KiteDocViewState.kt:924-938`). `blurTextField` and `dismissChoice` use their own scope and are fine.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane VIEWER-RUNTIME: concurrency, lifecycle, memory and performance of kitepdf-compose-viewer and kitepdf-media

Scope read end to end: KiteDocViewState.kt, KiteDocView.kt, KitePageRasterizer.kt, PageBitmapCache.kt, RasterGate.kt, RasterDispatcher.kt and its actuals, ChapterLoader.kt, ScriptDispatcher.kt and actuals, ScriptCall.kt, ThreadMarker.kt and actuals, HostTextThread.kt, HostTextLine.kt, HostTextPath.kt and the JVM, Android, Apple and web actuals, GlyphMaskCache.kt, ComposeCanvas.kt, SoftMaskFilter, BackdropLayer, TwoCircleGradient, TwoCircleRaster, ImageDecoder actuals, ImageExport, KiteFormLayer.kt, KitePageSemantics.kt, KiteWidgets.kt, KitePageOverlay.kt, KiteMarkupErrorNotice.kt, the coroutine use of KiteGestures.kt and KiteFormInput.kt, all of kitepdf-media, kitepdf-core KiteLock and its actuals, KiteBitmapCache, the cache parts of PdfDocument.kt, and the chapter state, locks and budget of EpubDocument.kt. Nothing was run; the toolchain was available but every claim below rests on reading the path, so each one carries its confidence.

### VIEWER-RUNTIME-01: An exact group composite allocates about twenty bytes a pixel of Java arrays, so a zoomed page with a non-isolated blending group runs out of memory on a phone
- Repo: KitePDF
- Area: kitepdf-compose-viewer (ComposeCanvas raster step), kitepdf-pdf (PageRenderer.compositeExactly)
- Kind: memory
- Severity: medium
- Confidence: likely (read the whole path, not run)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ComposeCanvas.kt:966-982, :996-1002, :1004-1044, :1046-1054, :1058-1068; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/SoftMaskFilter.kt:24-25; kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1156-1163, :1194-1216; kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/GroupRasters.kt:20-30
- What happens: `STEP_MAX_PIXELS` is documented as "64 MB of ARGB", as if a raster step held one buffer. The exact path of a non-isolated transparency group whose paints blend (`compositeExactly`) takes `scope.backdrop()` (an `IntArray` of the step, ComposeCanvas.kt:999), then `scope.render(backdrop, content)` and `scope.render(null, content)` (each an `ImageBitmap` plus an `IntArray` read back from it, :1005 and :1041, and the first also a 4-byte-a-pixel `ByteArray` for the initial raster at :1009), then `GroupRasters.withoutBackdrop` (another `IntArray`, GroupRasters.kt:21), then `scope.draw`, which converts the result to a `ByteArray` of four bytes a pixel (:1048, :1058-1068) and a platform bitmap. At the moment of `draw` the backdrop, the two renders, the result and the RGBA bytes are all live: 20 bytes a pixel of Java arrays, before the bitmaps. For a Rasterized page the step runs at scale 1 only (`backdrop()` returns null otherwise, :998), so the step is as large as the page bitmap. On a 1080 px wide phone a page at zoom bucket 2 is about 2160 by 3054 px (6.6 million px, 132 MB of arrays); at bucket 2.83 the long side cap of 4096 gives about 13 million px (264 MB). A 192 MB Android heap cannot hold that, the raster throws `OutOfMemoryError`, `rasterizeCachedOrNull` retries once with the same sizes, fails again, and the page goes to `KitePageRenderState.Failed`: the page that showed fine at zoom 1 vanishes when the reader zooms in. The knockout path has `KNOCKOUT_PIXEL_BUDGET` (PageRenderer.kt:2825); the non-knockout exact path has no budget but `STEP_MAX_PIXELS`.
- What should happen: the step budget counts every buffer the step holds at once, and is small enough for the smallest heap the module runs on (a few million pixels on Android), or the exact composite works in bands of rows so the peak is bounded whatever the page size. Past the budget the group paints as a layer, as it already does when the canvas cannot read the backdrop (ISO 32000-1, 11.4.8: the layer is the approximation the code already accepts there).
- How to reproduce or evidence: a one-page PDF with a page-sized `/Group << /S /Transparency /I false >>` form XObject whose content sets `/BM /Multiply` and is drawn with a `gs` of `/ca 0.5`, in `KiteDocView` with the default `KiteRenderSpec.Rasterized` on an Android device, pinch to about 2.8 times. Count the arrays in `ComposeRasterScope.backdrop`, `render` (twice) and `draw` plus `withoutBackdrop`: five `Int`/`Byte` arrays of the step's pixel count are reachable at `drawScope.drawImage` in `draw`.
- Cross-effects: `KiteRenderSpec.Rasterized.maxBitmapLongSide` (default 4096) sets the largest step a page bitmap allows; `KiteRenderSpec.Vectorized` at a zoom uses `magnification` as the step scale (ComposeCanvas.kt:978-980), and an SVG `<filter>` (kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1380-1400) renders the whole step before it shrinks to `FILTER_MAX_PIXELS`, so it reaches `STEP_MAX_PIXELS` without needing a backdrop; `PdfDocument.imageCacheBudgetBytes` (32 MB) and the 96 MB page bitmap cache sit in the same heap on Android below API 26, and the decoded image bytes do on every API.
- Fix sketch: make the step pixel budget platform-aware (an `expect val` like `MASK_MAX_PIXELS` for Android), count all five buffers against it, and reuse the `render` bitmap's `IntArray` as the output of `withoutBackdrop` instead of allocating a sixth array.

### VIEWER-RUNTIME-02: A Vectorized page of a chapter whose layout throws lays the chapter out again on every frame
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KitePageVector), kitepdf-epub (EpubPage.loadContent)
- Kind: bug
- Severity: medium
- Confidence: likely (read the whole path, not run)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1686-1695, :1703; kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:2059-2065, :1241-1244, :1056-1082; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:2078-2101
- What happens: `KitePageVector` keeps a `loads` counter and, while `!page.isContentLoaded`, runs `LaunchedEffect(page, loads) { withContext(raster) { runCatching { page.loadContent() } }; backOnComposeThread(); loads++ }`. `loadContent` lays the dropped chapter out again through `livePages` (EpubDocument.kt:1056-1082), and a layout that throws leaves `live[chapter]` null, so `isContentLoaded` stays false. The `runCatching` swallows the failure, `loads++` recomposes the slot, the key changed, and the effect starts the same layout again: one chapter layout per frame, for as long as the slot is composed, with the page showing its paper. The loader has `failedChapters` for exactly this ("Chapters whose layout threw. They stay placeholders, and nothing lays them out again", KiteDocViewState.kt:2078-2079), and the raster path stops after two attempts (`rasterizeCachedOrNull`), but this path has no limit. Layouts have thrown before (#331), and an `OutOfMemoryError` from a heavy chapter is the likeliest cause, so the loop also allocates to the heap limit every frame.
- What should happen: a load that fails stops, as a raster does: the slot stays on its paper and the failure is logged once, or the chapter joins `failedChapters` and the Vectorized slot reads that set before it tries.
- How to reproduce or evidence: an `EpubDocument` with `EpubSettings(layoutCacheBytes = 1)` (so every chapter but one is dropped) and a chapter whose layout throws (a test `KiteDocument` wrapper whose `loadContent()` throws works too), shown in `KiteDocLayout.Continuous` with `KiteRenderSpec.Vectorized`; count the calls to `loadContent()` over a second of frames. One call per frame is the loop.
- Cross-effects: `EpubSettings.layoutCacheBytes` (default 48 MB) decides how often a chapter is dropped; the loader's `declined` and `failedChapters` sets do not reach this effect; on the web the layout runs on the UI thread, so the loop also stalls every frame.
- Fix sketch: keep the failure in the slot (`remember(page) { DrawFailure() }` already exists at KiteDocView.kt:1683) and stop after a failed load, or ask the state's `failedChapters` and add to it.

### VIEWER-RUNTIME-03: A document script that throws an Error ends the host app, because the script guard catches Exception and the lanes have no handler
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

### VIEWER-RUNTIME-04: KiteMediaOverlay lays a dropped chapter out on the UI thread by reading the page's media in composition
- Repo: KitePDF
- Area: kitepdf-media (KiteMediaOverlay), kitepdf-epub (EpubPage.media)
- Kind: performance
- Severity: medium
- Confidence: likely (read the whole path, not run)
- Where: kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaOverlay.kt:115-116; kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:3146, :1925-1927, :1247-1249, :1056-1064; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:334, :526-527, :553-555, :1689-1695
- What happens: the viewer guards every read of a page's content on the UI thread with `isContentLoaded`, because a page whose chapter the layout budget dropped lays the chapter out again on the calling thread (#377: KiteDocView.kt:527, 555, 1689; KiteDocViewState.kt:1592). `KiteMediaOverlay` is composed as the page overlay of every page slot and reads `remember(epubPage) { epubPage.media }` in composition (KiteMediaOverlay.kt:116). `EpubPage.media` is `buildMedia(laidOut())` (EpubDocument.kt:3146), `laidOut()` is `doc.render(chapter, index)` (:1926-1927), and `render` calls `livePages(chapter)` (:1248), which lays the chapter out under `chapterLocks[chapter]` when it is not live (:1061-1064). A slot is composed before its geometry is reported, and `keepChapters(chaptersOnScreen())` follows the geometry a frame later (KiteDocView.kt:334), so the first composition of a page in a dropped chapter, as when the reader scrolls back into one, runs the whole chapter layout on the UI thread, inside composition. A book with the media overlay set up therefore stalls on every return to a dropped chapter, which is the stall #377 removed from the viewer's own reads.
- What should happen: the overlay reads `media` only when `epubPage.isContentLoaded`, and otherwise draws nothing until the page's raster or draw has brought the chapter back (both do that off the UI thread), as the viewer's own overlays do.
- How to reproduce or evidence: an EPUB with a video on chapter 3 and `EpubSettings(layoutCacheBytes = 1)`, `KiteDocView(state, pageOverlay = { KiteMediaOverlay() })`; scroll to chapter 6 and back to chapter 3. On the JVM, a `DroppedChapterSceneTest`-style watched page counts a `render` on the composition thread; `DroppedChapterSceneTest.a_long_press_and_a_tap_on_a_dropped_chapter_read_nothing_on_the_ui_thread` is the model, and nothing in kitepdf-media tests a dropped chapter.
- Cross-effects: `EpubSettings.layoutCacheBytes`; `KiteDocLayout.Paged.offscreenPages` composes pages that are off screen, which `chaptersOnScreen()` never keeps, so with a pager the layout runs for pages the reader has not reached; the same `laidOut()` call sits behind `EpubPage.drawsHostFontText`, which the rasterizer already answers off the UI thread for that reason (KitePageRasterizer.kt:206-210).
- Fix sketch: in `KiteMediaOverlay`, `if (!epubPage.isContentLoaded) return` before the `remember`, and read the content version so the overlay composes again when the chapter is back; or make `EpubPage.media` answer from the chapter summary, which survives the budget.

### VIEWER-RUNTIME-05: On the web, a bookmark, a table of contents entry or a page point lays a chapter out in one piece on the UI thread
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KiteDocViewState navigation), kitepdf-epub (locate, topOf)
- Kind: performance
- Severity: medium
- Confidence: likely (read the whole path, not run on JS)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:2049-2052, :2068-2069, :2086-2093, :2110-2114, :2141-2149; kitepdf-compose-viewer/src@js/io/github/yuroyami/kitepdf/compose/RasterDispatcher.kt:7; kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1497-1501, :1524-1527, :1143-1146, :1004-1015
- What happens: #389 made a chapter lay out in slices on the web, with a frame between two, because `kitepdfRasterDispatcher()` is `Dispatchers.Main` there (RasterDispatcher.kt:7) and a chapter laid out in one call stalled the page. `prepareChapterGuarded` honours that through `layoutPausesForFrames` (KiteDocViewState.kt:2091). The navigation paths around it do not: `locateGuarded` runs `document.locate(bookmark)` under `withContext(kitepdfRasterDispatcher())` (:2069), `scrollTo(bookmark)` runs `document.topOf(bookmark)` the same way (:2051), and `awaitRemoteLayout` too (:2113). `locate` of a `KiteBookmark.Flow` calls `summaryOf(chapter)` (EpubDocument.kt:1501), which calls the synchronous `prepareChapter` (:1145), which lays the whole chapter out under the chapter lock with no checkpoint (:1004-1015). On the web that `withContext` runs on the one thread, so opening a book at a saved bookmark, tapping an outline entry, following a link with a fragment, or `rememberKiteDocViewState(book, bookmark)` lays the target chapter out in one piece on the UI thread, which is the stall #389 was written to remove, on the slowest platform.
- What should happen: on the web the navigation paths prepare the chapter through `prepareChapterGuarded` first (which slices), and only then call `locate` and `topOf`, which then find the summary ready and lay nothing out.
- How to reproduce or evidence: a reflowable EPUB with a long chapter in a wasmJs build; `state.scrollTo(KiteBookmark.Flow(chapter = 5, charOffset = 0))` from a button: the frame that runs `locateGuarded` lasts the whole layout of chapter 5. Compare with scrolling into chapter 5, which goes through `loadChapters` and `prepareInSlices`.
- Cross-effects: `openSavedPosition` (KiteDocViewState.kt:2301-2328) takes the same `locateGuarded` path on every open with a saved position; `KiteOutlinePanel` and `linkTap` for EPUB links call `scrollTo(bookmark)`; `KiteReadAloud` calls `locateFragment` on `Dispatchers.Default`, which is also the UI thread on the web if the module ever reaches it (it does not build for the web today).
- Fix sketch: in `scrollTo(bookmark)` and `openSavedPosition`, call `prepareChapterGuarded(flowTarget(bookmark).chapter)` before `locateGuarded` when `layoutPausesForFrames` is true; `locate` then returns without laying out.

### VIEWER-RUNTIME-06: KiteThumbnailStrip keeps the previous document alive after a state swap through its bitmap cache keys
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KiteWidgets.KiteThumbnailStrip, PageBitmapCache)
- Kind: memory
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteWidgets.kt:182, :188, :196-225; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/PageBitmapCache.kt:20-23, :101-118; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageRasterizer.kt:241-255; kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPage.kt:36-37; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:467-470
- What happens: the strip's cache is `remember(state.viewerDecorator) { PageBitmapCache(THUMBNAIL_CACHE_BYTES) }` (KiteWidgets.kt:188), keyed on the decorator and not on the state. Its keys hold `pageIdentity = page` (KitePageRasterizer.kt:243), a `PdfPage`, which holds its `PdfDocument` (PdfPage.kt:37). An app that keeps `KiteThumbnailStrip(state)` composed while the reader opens another document (a new `rememberKiteDocViewState(document)`, a new state) keeps the old document's pages in the cache until the 8 MB budget evicts them (:101-118 evict only when something is added, so about sixty new thumbnails later). Through the pages the old `PdfDocument` stays reachable with its decoded image cache (32 MB), font cache (32 MB), operation cache (16 MB) and the file bytes, or for an EPUB its live chapters (48 MB). `KiteDocView` avoids this with `key(state)` around its layouts (KiteDocView.kt:470); the strip has no such key. The strip's `rememberKitePageRasterizer()` (:182) is not keyed on the state either, and on the web its `hostFontPage` keeps the last thumbnailed page (KitePageRasterizer.kt:82-83, :214).
- What should happen: a new state gives the strip a new cache and a new rasterizer, so nothing of the old document stays reachable from the strip once the new one shows, as `key(state)` does for the viewer itself.
- How to reproduce or evidence: compose `KiteThumbnailStrip(state)` with `state` from a `mutableStateOf<KiteDocViewState>`; open document A, take a heap histogram, swap to document B and let the strip render its thumbnails, take another: one `PdfDocument` of A is still reachable through `PageBitmapCache$Entry` of the strip's `thumbnails`. `ThumbnailCacheSceneTest.a_thumbnail_that_comes_back_is_not_rendered_again` uses one state and cannot see it.
- Cross-effects: `KiteDocViewState.viewerDecorator` (the only key) rarely changes; `KiteRenderSpec.Rasterized.canvasDecorator` set per composition would recreate the cache on every frame instead; on Android every retained `PdfDocument` is counted against the 192 MB heap.
- Fix sketch: `remember(state, state.viewerDecorator) { PageBitmapCache(...) }` and `key(state)` around the `LazyRow`, or key the cache on `state.document`.

### VIEWER-RUNTIME-07: A state used after its view left posts script work to a cancelled scope, where it is dropped without a word
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KiteDocView wiring, KiteDocViewState.post)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:263-273; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:538-542, :924-938, :658-667, :711-720
- What happens: `KiteDocView` sets `state.scriptScope = rememberCoroutineScope()` and `state.scriptLane` in a `SideEffect` (KiteDocView.kt:269-270) and never clears them on dispose. `post` (KiteDocViewState.kt:924-938) documents that it "runs work on the script lane, or here when there is no view to give one", and tests `scope == null || lane == null` for that. After the view leaves, both are still set, and `scope.launch(lane)` on the cancelled `rememberCoroutineScope` creates a coroutine that never runs: a host's `focusField` (which posts `handler.focus`) or `commitFocusedField`, called between two views of the same state, does nothing, where the documented behaviour is to run in place. `focusChoice` and `chooseChoice` answer through the same scope (:663-666, :716-719), so their answers are dropped too, leaving `choiceCommitPending` true.
- What should happen: a `DisposableEffect(state) { onDispose { if (state.scriptScope === scriptScope) state.scriptScope = null } }`, so the "no view" branch runs as documented; the lane can stay, it holds no threads.
- How to reproduce or evidence: compose `KiteDocView(state, scripts = handler)`, remove it from the composition, call `state.focusField("Text1")`: `handler.focus` is never called, while the same call before the view was ever composed calls it through `runInPlace`.
- Cross-effects: the `DisposableEffect(state) { onDispose { state.blurFocusedField() } }` at KiteDocView.kt:367 runs while the scope is still active, so the blur on leave is unaffected; `KiteFormInput` and `KiteChoiceInput` live inside the view and leave with it.
- Fix sketch: clear `scriptScope` in a disposable effect of `KiteDocView`, keyed on the state.

### VIEWER-RUNTIME-08: Every change of the form rebuilds the accessibility nodes of every page on screen
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KitePageSemantics)
- Kind: performance
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageSemantics.kt:151-171, :69-124; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1023-1028
- What happens: `PageSemantics` keys its `LaunchedEffect` on `state.formRevision` (KitePageSemantics.kt:157), which "a script that writes a field twenty times a second" moves twenty times a second (KiteDocViewState.kt:1024-1026). Each change restarts the effect for every shown page, hops to the raster dispatcher, rebuilds every node from the reading order, the text, the links and the widgets (`accessNodes`, :69-124), hops back, and replaces `nodes`, so the `Layout` with one `Box` and one semantics modifier per text item recomposes and remeasures. The reading items and links never change with the form; only the values and states of the widget nodes do. The form layer was built to avoid this very cost for pixels (KiteFormLayer.kt:34-37) and the semantics undo it for the semantics tree.
- What should happen: the text and link nodes are built once per page and the widget nodes alone follow `formRevision`, or the widget values are read in the semantics lambda, which already reads snapshot state, so a change updates the node without rebuilding the list.
- How to reproduce or evidence: a PDF with a timer script that writes a field every 50 ms, shown with `scripts`; count calls to `accessNodes` per second with a shown page: about twenty, one per revision, each running `page.readingOrder()` and `page.widgetAt` for every widget on the page.
- Cross-effects: on the web `kitepdfRasterDispatcher()` is the UI thread, so the rebuild runs there too; `KiteFormRevision` publishes the revision on the composition thread (KiteFormLayer.kt:168-184), so the rate is bounded by frames.
- Fix sketch: split `accessNodes` into a `remember(page)`-ed content part and a form part keyed on `formRevision`, and merge the two lists.

### VIEWER-RUNTIME-09: The page bitmap cache survives the view's disposal while the vector image cache does not
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KiteDocView, KiteDocViewState caches)
- Kind: design
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:335-336, :282-284; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1236-1268; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:274-280
- What happens: on dispose the view clears the Vectorized image cache (`state.vectorImageCacheFor(0L)`, KiteDocView.kt:336) and the chapters it kept (:335), but the Rasterized page bitmap cache stays with the state (KiteDocViewState.kt:1236-1255), up to its 96 MB default (KiteDocViewSpecs.kt:280). A state hoisted above a navigation destination, as the KDoc recommends ("Hoist it to drive a KiteDocView from anywhere"), keeps 96 MB of bitmaps while the reader is on another screen. #395 freed the bitmaps on a switch to Vectorized but not on leaving the view. The asymmetry is the finding; a return to the view that finds the bitmaps is a feature, but a phone with 192 MB cannot afford to keep them for a screen that is gone.
- What should happen: one rule for both caches, documented: either both survive the view with the state, or both go on dispose; and a `KiteDocViewState.trimCaches()` the host can call from `onTrimMemory`, as `PdfDocument.dropDecodedImageCache` exists for the document.
- How to reproduce or evidence: `KiteDocViewState.bitmapCacheFor` is only set to null by `bitmapCacheFor(0L)`, which `KiteDocView` calls for Vectorized specs only (KiteDocView.kt:283); no `onDispose` reaches it.
- Cross-effects: `KiteRenderSpec.Rasterized.cacheBudgetBytes` is the same on a desktop with gigabytes and a phone with 192 MB; the bitmaps are native memory on Android 8 and later, so the Java heap does not show them.
- Fix sketch: `DisposableEffect(state) { onDispose { state.bitmapCacheFor(0L) } }` next to the vector cache line, or a documented keep with a public trim.

### VIEWER-RUNTIME-10: onPageRendered hands the host the very bitmap the cache draws from, and nothing says so
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KiteDocView.onPageRendered, PageBitmapCache)
- Kind: docs
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:169-175, :1454, :1626-1630; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/PageBitmapCache.kt:7-17, :49, :109
- What happens: the brief asks whether the cache can still recycle a bitmap handed out. It never recycles: no `recycle()` or `close()` exists in the viewer, bitmaps are left to the collector, so a bitmap the host received stays valid. The other direction is the gap: `ReportFreshRaster` gives `onPageRendered` the same `ImageBitmap` instance the `PageBitmapCache.Entry` holds and the slot draws (KiteDocView.kt:1628, PageBitmapCache.kt:109). A host that writes into it (`asAndroidBitmap()` and a `Canvas` over it to stamp a watermark, or `recycle()` after saving a PNG) changes or breaks the page on screen and every later cache hit of that key. The KDoc of `onPageRendered` (KiteDocView.kt:169-175) says what the bitmap leaves out but not that it is shared and must be treated as read-only.
- What should happen: the KDoc says the bitmap is shared with the viewer's cache and must not be changed or recycled, and that `encodeToPng` is the way to take it away; or the callback hands out a copy.
- How to reproduce or evidence: on Android, `onPageRendered = { _, bmp -> bmp.asAndroidBitmap().recycle() }` makes the next frame throw "Canvas: trying to use a recycled bitmap" from the slot's `Image`.
- Cross-effects: the thumbnail strip and `PageTiles` share bitmaps with the same cache; `KitePageRasterizer.rasterizeOffMain` returns a fresh bitmap each call and is the right export path, as the KDoc already says.
- Fix sketch: one sentence in the `onPageRendered` KDoc, and the same in `KitePageRasterizer.rasterizeCachedOffMain`.

### VIEWER-RUNTIME-11: Every run of glyph masks allocates a platform bitmap, hundreds per page raster
- Repo: KitePDF
- Area: kitepdf-compose-viewer (ComposeCanvas.drawRun, ImageDecoder actuals)
- Kind: performance
- Severity: low
- Confidence: suspected (read the path; the cost needs a measurement)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ComposeCanvas.kt:361-426; kitepdf-compose-viewer/src@android/io/github/yuroyami/kitepdf/compose/ImageDecoder.android.kt:17-41; kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/ImageDecoder.jvm.kt:28-44
- What happens: #382 draws a run of opaque text as one bitmap of its masks. Each `drawGlyphs` call with masks makes a coverage `ByteArray`, an RGBA `ByteArray` four times as large, and a platform bitmap through `ImageDecoder.decodeRaw` (ComposeCanvas.kt:392, :413, :423): on Android `Bitmap.createBitmap` plus `setPixels` through a band (ImageDecoder.android.kt:20-37), on Skiko a native `Bitmap` with a copy of the bytes (ImageDecoder.jvm.kt:35-40). A text page has a few hundred `Tj` runs, so a raster makes a few hundred small bitmaps that are drawn once and dropped. On Skiko the native copy is freed only when the collector reaches the small Kotlin wrapper, so a fast scroll through text pages builds native memory the Java heap does not see until a collection runs. The masks save the glyph fills; the per-run bitmap may give part of that back.
- What should happen: one scratch coverage buffer and one bitmap per canvas, reused across runs (the raster is single-threaded per canvas), or the run drawn through `drawImage` of a bitmap the canvas keeps and `installPixels` again.
- How to reproduce or evidence: count `ImageDecoder.decodeRaw` calls for one raster of a dense text page (a test can wrap the decoder as `CanvasDecoratorTest` wraps the canvas); JFR with native allocation sampling on a scroll through a text-heavy PDF shows the `installPixels` copies (see the CLAUDE.md note on JFR and native frames).
- Cross-effects: `GlyphMaskCache.DEFAULT_BUDGET_BYTES` only bounds the masks, not the per-run bitmaps; `MAX_RUN_PIXELS` (4 million) bounds one run at 20 MB of arrays.
- Fix sketch: keep a growable `ByteArray` pair and, on Skiko, one mutable `Bitmap` per `ComposeCanvas`, sized to the largest run so far.

### VIEWER-RUNTIME-12: runCatching around three off-thread reads swallows the cancellation too
- Repo: KitePDF
- Area: kitepdf-compose-viewer (navigation, vector slot, markup notice)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:2051; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:641, :1691; kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteMarkupErrorNotice.kt:33-35
- What happens: `withContext(kitepdfRasterDispatcher()) { runCatching { document.topOf(bookmark) }.getOrNull() }` and its two siblings catch `CancellationException` with everything else. A navigation cancelled while `topOf` runs continues as if the bookmark had no fragment and calls `scrollTo(location, animate)`, which then throws at its next suspension instead of here; the vector slot's `loads++` writes state after its effect was cancelled. The rest of the file uses the `catch (cancelled: CancellationException) { throw cancelled }` shape (KiteDocViewState.kt:2070-2071, :2094-2095) for this reason.
- What should happen: the same shape here: rethrow `CancellationException`, swallow the rest.
- How to reproduce or evidence: read the three lines; `runCatching` catches `Throwable`.
- Cross-effects: none seen.
- Fix sketch: a small `internal inline fun <T> quietly(block: () -> T): T?` that rethrows cancellation, used at the four sites.

### Capabilities and opportunities (not defects)
- A memory pressure hook on the viewer: `PdfDocument` has `dropDecodedImageCache`, `dropOperationCache` and `dropFontCache`, `EpubDocument` has its budget, but `KiteDocViewState` has nothing an Android `onTrimMemory` can call to drop the 96 MB page bitmap cache, the glyph masks and the tiles. Reach: every Android host; cost: one public method and a scene test.
- Platform defaults for the budgets: `KiteRenderSpec.Rasterized.cacheBudgetBytes` (96 MB), `STEP_MAX_PIXELS` (16.7 million) and `maxBitmapLongSide` (4096) are the same on a 4 GB desktop JVM and a 192 MB Android heap; an `expect` default per platform, as `MASK_MAX_PIXELS` already has for the mask path, would let the Android numbers be smaller without the host knowing. Cost: small.
- Cancellation inside a long operator: a raster stops between operators (#188), so a 20 megapixel scanned image decode cannot be stopped once begun; passing the `KiteCancellation` into ImageKodec's row loop would let a fling through a scanned book drop the pages it passed. Reach: scanned PDFs on phones; cost: a flag check per band in the decoders.
- The media module leaves the composition's scope for `markNarration(null, false)` on close (KiteReadAloud.kt:292) and the viewer leaves it for blur and page close (#365); a small shared "after the view" scope with a handler would give those launches one place to log and one place to cancel on app exit.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane PDF-PARSE lane findings

Scope read end to end: core Lexer/PdfObject/ByteReader/ByteArrayBuilder, filters
(FilterChain, Flate/LZW/RunLength/ASCIIHex/85, Predictors, CCITT hand-off),
compression (Inflate, Zlib, PlatformFlate JVM/native actuals) compared with
ImageKodec's Inflate, zip (ZipReader), xml (KiteXml, entity expansion),
KiteDataUrl, kitepdf-pdf parser (Parser, XrefParser, PdfRepair, object/xref
streams, /Prev + /XRefStm), crypto (StandardSecurityHandler, RC4, AES, R2-R6,
Decryptor, Encryptor), PdfDocument (open, resolve, cycle guard, object-stream
decode, page tree walk, caches), content (ContentStreamParser, inline images),
KiteDoc (formatOf + open overloads), kitepdf-net, cbz, xps parsing.

Toolchain: JVM build succeeds; I did not run Kotlin (would need a new test
class + full suite). Probes were by reading and byte-level reasoning.

---

### PDF-PARSE-01: Inflate's `alignToByte` discards buffered whole bytes, so a stored block after a Huffman block loses data on JS/Wasm/Native
- Repo: KitePDF
- Area: kitepdf-core/core/compression (pure Kotlin inflater)
- Kind: bug
- Severity: high
- Confidence: verified (the orchestrator reproduced it with a crafted stream that java.util.zip accepts; natural zlib output did not trigger it, see Appendix A)
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/compression/Inflate.kt:131 (`alignToByte`), :98 (`readBits`), :112 (`ensureBits`), :72 (`consumedBytes`); contrast ImageKodec imagekodec/src/io/github/yuroyami/imagekodec/internal/flate/Inflate.kt:188 (`alignToByte`)
- What happens: The Huffman decoder prefetches up to `fastBits` bits (up to 15 for a dynamic table) into `bitBuf` by reading whole bytes and advancing `bytePos`. When an end-of-block symbol returns and the next block is a stored block, `inflateStored` calls `alignToByte`, which does `bitBuf = 0; bitCount = 0` without rewinding `bytePos`. If `bitCount >= 8` at that point (routine after a dynamic block with a short end-of-block code: leftover can reach ~15 bits, minus the 3 BFINAL/BTYPE header bits still leaves one or two whole bytes), those whole already-read bytes are thrown away. `readByteRaw` then reads the stored block's LEN/NLEN/data from a position past the dropped bytes, so the output is corrupted or the LEN/NLEN check throws. The class's own `consumedBytes` getter (`bytePos - (bitCount / 8) - origin`) shows the code elsewhere knows `bitCount / 8` whole bytes are buffered-but-unconsumed; `alignToByte` fails to return them.
- What should happen: `alignToByte` must return the buffered whole bytes to the input cursor before dropping the partial-byte padding, exactly as ImageKodec's port does: `incnt -= bitcnt ushr 3; bitbuf = 0; bitcnt = 0` (RFC 1951 3.2.4: a stored block begins on a byte boundary, and only the partial padding bits are skipped).
- How to reproduce or evidence: A DEFLATE stream of one dynamic Huffman block (frequent EOB, short EOB code) immediately followed by a stored block. On JVM/Android the java.util.zip fast path (`PlatformFlate.inflateOrNull`) decodes it correctly and masks the bug; on JS, Wasm and all Kotlin/Native targets `PlatformFlate` returns null and this pure path runs, so FlateDecode of such a stream (xref streams, object streams, fonts, images, zip entries via `Inflater`) decodes wrong. `Inflate.decode` used directly (not behind the platform path) is wrong on every target.
- Cross-effects: Every FlateDecode consumer on JS/Wasm/Native (object streams, xref streams, fonts, images, EPUB/CBZ/XPS zip entries all flow through this `Inflater`). The JVM-only fast path is why the JVM differential sweep never catches it.
- Fix sketch: In `alignToByte`, do `bytePos -= bitCount ushr 3` before zeroing `bitBuf`/`bitCount`, matching ImageKodec.

### PDF-PARSE-02: Object streams are inflated without decryption, so an encrypted PDF that stores objects in object streams cannot be opened
- Repo: KitePDF
- Area: kitepdf-pdf/PdfDocument (object-stream decode) + kitepdf-pdf/parser/PdfRepair
- Kind: bug
- Severity: critical (raised from high by the orchestrator: reproduced by execution, see Appendix A)
- Confidence: verified (the orchestrator reproduced it: pikepdf-made R4 and R6 files with object streams fail with "Repair found no catalog that leads to a page tree" while the same file with a classic xref opens)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:903 (`decodeObjectStream`), specifically :918 `val decoded = FilterChain.decode(stream)` with no `maybeDecrypt`; compare :870 `resolveInPlace` which does call `maybeDecrypt`. Also kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/PdfRepair.kt:138-143 (orchestrator corrected the line numbers).
- What happens: `decodeObjectStream` reads the container `/Type /ObjStm` object straight from its byte offset with `Parser.readIndirectObject()` (deliberately bypassing `resolve()` to avoid cache recursion), then calls `FilterChain.decode(stream)` on the raw stream bytes. For an encrypted document (V2/V4/V5) the object stream body is ciphertext and is never run through `Decryptor`/`StandardSecurityHandler.decryptStream`, so `FilterChain.decode` tries to inflate ciphertext, throws, and `resolve()`'s `runCatching` caches null. Every compressed object therefore resolves to null. If the catalog or page tree lives inside an object stream (the normal case for a modern Acrobat-encrypted PDF with a compressed xref), `isStructurallyUsable` fails, `open` falls to repair, and `PdfRepair.objStreamMembers` (line 379) decodes the same encrypted stream the same unprotected way and also fails, so the document fails to open or opens with its objects missing. Members parsed out of the decoded stream are correctly NOT individually decrypted (ISO 32000-1 7.6.2), so there is no double-decrypt; the defect is the missing single decrypt of the container.
- What should happen: Decrypt the object-stream body with the security handler before running the filter chain, the way `resolveInPlace` does for in-file streams (ISO 32000-1 7.6.2: the object stream itself is encrypted; its member strings/streams are not).
- How to reproduce or evidence: Open any AES-128 or AES-256 encrypted PDF whose xref is a cross-reference stream with compressed objects (Acrobat default). `EncryptionIntegrationTest` and `EncryptedWriteTest` both build fixtures with a classic `xref` table and no `/ObjStm` (EncryptionIntegrationTest.kt:133 `write("xref\n0 7\n...")`), and the differential corpus holds no encrypted PDF at all, so this path is untested.
- Cross-effects: The write side mirrors the gap: `PdfEditor.writeWithObjectStreams` (PdfEditor.kt:1156) packs members and emits the `/ObjStm` with no `encryptor` call, so even KitePDF's own `saveRewritten(useObjectStreams = true)` output of an encrypted base would be inconsistent (see PDF-PARSE-08). `/Metadata` with `/EncryptMetadata false` and `/XRef` streams are handled correctly elsewhere; only the object-stream container is missed.
- Fix sketch: In `decodeObjectStream` (and `PdfRepair.objStreamMembers`), call `maybeDecrypt(indirect.number, indirect.generation, stream)` (or `security?.decryptStream(...)` on the raw bytes) before `FilterChain.decode`.

### PDF-PARSE-03: V5/V6 decryption ignores an `/Identity` crypt filter on `/StmF` or `/StrF`
- Repo: KitePDF
- Area: kitepdf-pdf/crypto/StandardSecurityHandler
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/StandardSecurityHandler.kt:83-98 (`decrypt`), :298 (`detectV4Algorithm` short-circuits for v != 4)
- What happens: `decrypt` returns early for `v >= 5` with `return Aes.decryptCbc(key, ciphertext)` and never consults `stmAlgorithm`/`strAlgorithm`. `detectV4Algorithm` only distinguishes algorithms for `v == 4`; for V5 it returns `NONE`, but the V5 branch never looks at it. A V5 document that names `/StmF /Identity` (or `/StrF /Identity`, i.e. selective encryption leaving streams or strings in clear, ISO 32000-2 7.6.5) has its clear streams/strings AES-256-decrypted anyway, producing garbage. The spec Identity crypt filter means "pass through unchanged".
- What should happen: For V5/V6, resolve `/StmF` and `/StrF` against `/CF` just like V4 and skip decryption when the selected crypt filter is `/Identity` (or has no `/CFM`/`CFM None`). ISO 32000-2 7.6.5.1, Table 26 (`Identity`).
- How to reproduce or evidence: A V5 `/Encrypt` with `/StmF /Identity /StrF StdCF`: stream bodies stored as plaintext are corrupted on read. No test exercises a non-StdCF V5 filter; `Encryptor.createV5` always writes `/StmF /StdCF /StrF /StdCF` (Encryptor.kt:152-153).
- Cross-effects: Collides with PDF/A and hybrid signing workflows that leave streams clear; also with the signature `/Contents` handling (PDF-PARSE commentary below).
- Fix sketch: Compute `stmAlgorithm`/`strAlgorithm` for `v >= 5` too (map `AESV3`/`Identity`), and honour `NONE` in the `v >= 5` branch of `decrypt`/`encrypt`.

### PDF-PARSE-04: A crafted `/Encrypt` with `/Length 0` (or negative) throws a non-format exception out of `open()` on the repair path
- Repo: KitePDF
- Area: kitepdf-pdf/crypto/StandardSecurityHandler + kitepdf-pdf/PdfDocument.open
- Kind: bug
- Severity: medium
- Confidence: verified (the orchestrator reproduced it: ArithmeticException escapes open())
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/StandardSecurityHandler.kt:30 (`keyLengthBits = getInt("Length")?.toInt() ?: ...`), :162 (`rc4Key = digest.copyOf(n)` with `n = keyLengthBits / 8`), :197-203 (`digest.copyOf(n)`); kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/Rc4.kt:23 (`key[i % key.size]`); kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:1071-1072 (repair-path `buildSecurityHandler` not wrapped)
- What happens: `keyLengthBits` is read straight from `/Length` with no lower bound. `/Length 0` makes `n = 0`, so the file key is `digest.copyOf(0)` (empty) and validation calls `Rc4.process(emptyKey, ...)`, whose `ksa` does `key[i % key.size]` = `i % 0` -> ArithmeticException (divide by zero). `/Length` negative makes `digest.copyOf(negative)` -> NegativeArraySizeException. In `open()` the normal path wraps `buildSecurityHandler` in `runCatching`, so it just yields null and falls through to repair, but the repair path (PdfDocument.kt:1072) calls `buildSecurityHandler` with no guard, so the exception escapes `open()` as a raw ArithmeticException/NegativeArraySizeException rather than a `PdfFormatException`/`KiteWrongPasswordException`.
- What should happen: Validate `/Length` (multiple of 8, in 40..256) and treat an out-of-range value leniently (fall back to the V-implied default, or fail with `PdfFormatException`). `open()` must only throw the documented `KiteFormatException`/`KiteWrongPasswordException` family. ISO 32000-1 7.6.4.4, Table 20 (`Length` is a multiple of 8 between 40 and 128).
- How to reproduce or evidence: Any PDF whose `/Encrypt` dict has `/V 2 /R 2 /Length 0` (R2 reaches `Rc4.process` during `/U` validation). `openOrNull` masks it (catches `Exception`), but `open` throws the wrong type and crashes a caller that only guards for format/password exceptions.
- Cross-effects: `KiteDoc.open` -> `PdfDocument.openOrNull` null branch is fine, but `PdfDocument.openFile`, `KiteDoc.openFile`, and direct `PdfDocument.open` callers see an undocumented exception type; fuzzers hit it immediately.
- Fix sketch: Clamp/validate `keyLengthBits` in the constructor; wrap the repair-path `buildSecurityHandler` call (PdfDocument.kt:1072) so a handler-construction failure degrades like the normal path.

### PDF-PARSE-05: `xmpMetadataXml` and `PdfAttachment.bytesProvider` call `FilterChain.decode` unguarded, so a corrupt stream throws from APIs documented to be lenient
- Repo: KitePDF
- Area: kitepdf-pdf/PdfDocument, kitepdf-pdf/PdfAttachment
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:148-161 (`xmpMetadataXml` lazy, :155 unguarded `FilterChain.decode`), :168 (`xmp`), :536-543 (`metadata` reads `xmp?.title`); kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfAttachment.kt:34 (KDoc "Empty array on failure"), :90-92 (`bytesProvider` lambda unguarded)
- What happens: `xmpMetadataXml` is a public property documented to return null when there is no `/Metadata` stream, but it decodes the stream with a bare `FilterChain.decode`. A `/Metadata` stream with an unsupported filter (`UnsupportedFilterException`), broken Flate (`InflateException`), or one that trips the 512 MiB cap throws out of the lazy. `xmp` propagates it, and `metadata` (the `KiteDocument` override the Compose viewer reads) evaluates `info.title ?: xmp?.title`, so a document with no `/Info /Title` and a corrupt `/Metadata` stream throws from `metadata` and blocks the viewer. Separately, `PdfAttachment.bytesProvider` is documented "Empty array on failure" but throws on a decode failure instead of returning an empty array; `bytes` (a `by lazy`) then throws.
- What should happen: Both must honour the lenient-salvage rule (CONTRIBUTING: "A corrupt object, image, font or annotation becomes a skip or a placeholder. It never stops the page or the document."): wrap the decode in `runCatching` and return null / empty.
- How to reproduce or evidence: A document whose catalog `/Metadata` points at a stream with `/Filter /JBIG2Decode` or truncated Flate, no `/Info /Title`: reading `doc.metadata` throws. An attachment whose embedded-file stream has a bad filter: `attachment.bytes` throws, contradicting the KDoc.
- Cross-effects: `KiteDocument.metadata` feeds the viewer's title/author/RTL; this makes a malformed-metadata file fail to display despite otherwise-fine pages.
- Fix sketch: `xmpMetadataXml`: `runCatching { FilterChain.decode(stream) }.getOrNull() ?: return@lazy null`. `PdfAttachment`: wrap the provider in `runCatching { ... }.getOrDefault(ByteArray(0))`.

### PDF-PARSE-06: A Type0 font with an undecodable `/CIDToGIDMap` stream throws out of `loadFonts`, aborting the whole page
- Repo: KitePDF
- Area: kitepdf-core/core/font (CompositeFont) + kitepdf-pdf/render/PageRenderer
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:243-244 (`parseStream` -> bare `FilterChain.decode`), :173 (`CidToGidMap.from(...)` called directly in `from`, not wrapped); kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:466 (`font()` runs `build()` unwrapped); kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:303 (`loadFonts` before the render `try` at :338)
- What happens: `CidToGidMap.parseStream` decodes the `/CIDToGIDMap` stream with an unguarded `FilterChain.decode`. `CompositeFont.from` calls it inline (line 173) with no `runCatching`, unlike the embedded-outline loaders (`loadTtf`/`loadFontFile3`/CMap all use `runCatching`). `PdfDocument.font(..) { build() }` calls `build()` (i.e. `PdfFont.from`) outside any catch, and `PageRenderer.render` calls `loadFonts` at line 303, before the page's `try` block at line 338. So a Type0 font whose `/CIDToGIDMap` stream fails to inflate (corruption, unsupported filter, or the bomb cap) throws all the way out and aborts the entire page render, instead of substituting that one font.
- What should happen: A font that cannot be built is replaced by a fallback/placeholder and the page still draws (CONTRIBUTING lenient-salvage rule; PageRenderer already does this for annotations and images via `runCatching`).
- How to reproduce or evidence: A page using a `/Subtype /Type0` font whose descendant `/CIDToGIDMap` is a stream with a truncated FlateDecode body. The `runCatching` wrappers on the sibling loaders confirm the intent was to guard every embedded stream; `CidToGidMap.parseStream` is the one left bare.
- Cross-effects: Same unguarded shape would bite any future stream read added to `CompositeFont.from`; also affects text extraction, which builds the same fonts.
- Fix sketch: Wrap `CidToGidMap.parseStream`'s decode in `runCatching` (return an empty/identity map on failure), or wrap the `build()` call in `PdfDocument.font` so any font-construction throw yields a fallback.

### PDF-PARSE-07: An object stream with one malformed member drops every object it holds
- Repo: KitePDF
- Area: kitepdf-pdf/PdfDocument (object-stream decode)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:936-942 (member loop with no per-member salvage)
- What happens: `decodeObjectStream` parses each member with `out[i] = Parser(Lexer(objReader)).readObject()` inside a single loop with no per-member try. One member whose offset is out of range (`objReader.seek(first + offsets[i])` throws a `PdfFormatException` from `ByteReader.seek`) or whose body is malformed throws, so the whole `decodeObjectStream` fails and `resolve()` caches null for every member of that object stream. In-file objects get per-object salvage (one bad object -> one null), but compressed objects are all-or-nothing per container, so a single bad member can blank many pages at once. MuPDF recovers object-stream members best-effort.
- What should happen: Salvage per member: a member that will not parse becomes null, the rest of the object stream still resolves (mirror of the in-file lenient path and CONTRIBUTING's rule).
- How to reproduce or evidence: A `/Type /ObjStm` whose first-pair offset table lists one offset past `first + stream length`: `seek` throws, and all members (including the catalog if it lives there) vanish.
- Cross-effects: Amplifies PDF-PARSE-02 for encrypted files and any file with a slightly-off object stream; interacts with the page-tree walk, which then sees many null kids.
- Fix sketch: Wrap each member parse in `runCatching`, store null on failure, and keep going.

### PDF-PARSE-08: `saveRewritten` on an encrypted document silently drops `/Encrypt` and writes plaintext
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

### PDF-PARSE-09: `Lexer.parseLongFromBuf` overflow test is unreliable, so a huge integer can wrap silently instead of throwing
- Repo: KitePDF
- Area: kitepdf-core/core/parser/Lexer
- Kind: bug
- Severity: low
- Confidence: verified
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/Lexer.kt:246-262 (`parseLongFromBuf`, :257 `if (next < v) throw ...`)
- What happens: The integer accumulator uses `val next = v * 10 + d; if (next < v) throw` as its only overflow guard. `v * 10` can overflow `Long` to a value that is still `>= v` (the wrap does not always land below the previous value), so a crafted integer literal longer than 19 digits can produce a wrong wrapped `Long` with no exception. The result flows into object numbers, `/Length`, xref offsets, `/Size`, etc.
- What should happen: Detect overflow correctly (e.g. compare against `Long.MAX_VALUE / 10` before multiplying) or cap digit count, throwing `PdfFormatException` on overflow.
- How to reproduce or evidence: `99999999999999999999 0 R` or an absurd `/Length`: the lexer may return a wrapped value. Downstream bounds checks (`ByteReader.seek`) catch most misuse, so impact is low, but the lexer's own overflow promise is not kept.
- Cross-effects: Mostly masked by later range checks; worst case is a wrong-but-in-range offset landing on an unintended object (already defended by the `indirect.number != objectNumber` check in `resolveInPlace`).
- Fix sketch: `if (v > (Long.MAX_VALUE - d) / 10) throw PdfFormatException(...)` before `v = v*10 + d`.

### PDF-PARSE-10: Predictor parameters are not validated before sizing, so `/Columns 0` divides by zero and large values overflow Int
- Repo: KitePDF
- Area: kitepdf-core/core/filters/Predictors
- Kind: bug
- Severity: low
- Confidence: verified
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/filters/Predictors.kt:163-164 (`bpp`, `bytesPerRow` as Int), :225-232 (`applyTiff`: `rowBytes = columns * colors`, `rows = input.size / rowBytes`)
- What happens: `Columns`, `Colors`, `BitsPerComponent` come straight from `/DecodeParms` with no bounds. For the TIFF predictor (predictor 2), `/Columns 0` makes `rowBytes = 0` and `input.size / rowBytes` throws ArithmeticException (divide by zero). Large `/Columns` (e.g. 2^30) overflows the Int product `columns * colors * bitsPerComponent`, giving a wrong `bytesPerRow` and then a wrong/negative `ByteArray` size (NegativeArraySizeException) or out-of-bounds writes. These throw rather than corrupt, and most call sites wrap `FilterChain.decode` in `runCatching`, but the xref-stream path (`XrefParser.parseXrefStreamAt`) and object-stream path rely on the open-level catch, and the divide-by-zero is an undocumented exception type.
- What should happen: Validate `columns >= 1`, `colors >= 1`, `bitsPerComponent in {1,2,4,8,16}`, and compute row sizes in `Long` with range checks before allocating (ISO 32000-1 7.4.4.4, Table 10).
- How to reproduce or evidence: A Flate/LZW stream with `/DecodeParms << /Predictor 2 /Columns 0 /Colors 1 /BitsPerComponent 8 >>` -> ArithmeticException inside `applyTiff`.
- Cross-effects: Same unvalidated params feed the PNG predictor path (Int overflow of `bytesPerRow * rows`); bounded in practice by the 512 MiB decode cap but not by validation.
- Fix sketch: Guard the params and use `Long` arithmetic with explicit range checks, returning the input unchanged or throwing a `PdfFormatException` on invalid geometry.

---

### Capabilities and opportunities (not defects)
- No streaming/memory-mapped reader: every source (`PdfDocument.open`, all `KiteDoc` open overloads, `kitepdf-net`) loads the whole file into one `ByteArray`, documented but hard-capped at ~2 GB (the native `readPosixFile` rejects > `Int.MAX_VALUE`; `NSData.toByteArray` does `length.toLong().toInt()`, which would truncate a > 2 GB file rather than reject it). A random-access `ByteSource` abstraction behind `ByteReader` would lift the 2 GB ceiling, reduce peak memory on 32-bit Android ABIs and Wasm (bounded `ByteArray`), and let the xref be read without buffering the body. Large reach (every open path), medium-large cost.
- The 512 MiB `FilterChain.MAX_DECODED_STREAM` cap bounds each filter stage but not the sum across a document; a crafted file with thousands of near-cap object/xref/font streams can still exhaust memory even though each stays under the cap. A per-document decode budget (a shared counter) would close that, small cost.
- Encrypted-document coverage is absent from the corpus and the differential sweep. Adding a few encrypted fixtures (V2/RC4, V4/AES-128, V5/AES-256, each with a cross-reference stream and object streams, plus `/StmF /Identity`, `/EncryptMetadata false`, owner-password-only) would have caught PDF-PARSE-02/03 and belongs in the gate.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane PDF-RENDER: the rendering pipeline and canvas parity

Scope read end to end: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/*` (PageRenderer, DefaultColorSpaces, Overprint, KnockoutCanvas, GroupRasters, Type3Font), `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/*` (KiteCanvas, GraphicsState, ExtGState, KiteColorSpace, KiteFunction, KiteShading, MeshShading, GradientSpread, KitePattern, KiteImageData, ImageRaster, ImageSampling, ImageResample, KiteImageSamples, JpegInk, IccProfile, IccSource, IccLut, AdobeCmyk, RasterBlend, RasterScan, KiteRasterCanvas, StrokeOutline, StrokePen, ReaderTheme, KiteMaskTransfer, KiteBitmapCache, ImageGridFit, TwoCircleParameter), the content stream parser (`kitepdf-pdf/src/io/github/yuroyami/kitepdf/content/ContentStream.kt`), the six canvases (AwtCanvas, SkiaCanvas, ComposeCanvas, AndroidNativeCanvas, CoreGraphicsCanvas, Canvas2dCanvas), the text collector canvas in `text/StructuredText.kt`, and the differential harness (`difftest/DifferentialTest.kt`, `DiffHarness.kt`, `DiffBaseline.kt`, `kitepdf-difftest/.../ImageDiff.kt`, `CorpusSelection.kt`). Fonts and glyph outlines were left to the FONTS-TEXT lane. Nothing was run; every claim is from reading the code path.

### PDF-RENDER-01: An Indexed colour space allocates a palette table of hival plus one entries with no upper bound
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

### PDF-RENDER-02: A tiling pattern with more than 20000 cells on the page paints nothing at all
- Repo: KitePDF
- Area: kitepdf-pdf, `render.PageRenderer.renderTilingPattern`
- Kind: bug
- Severity: medium
- Confidence: verified (the orchestrator reproduced it: 2 pt cells record no fill, 40 pt cells record 396)
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1856-1861`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2816`
- What happens: `renderTilingPattern` counts the tiles that can touch the fill region and returns before painting anything when `tiles > MAX_TILES` (20000). A fine hatch, a 2 pt dot screen or a 1 pt line raster over a Letter page (612 x 792 pt) needs 306 x 396 = 121176 cells at a 2 pt step, 153 x 198 = 30294 at a 4 pt step. Such fills are the normal output of CAD exports, GIS maps, Excel pattern fills and Illustrator pattern swatches. The fill area is left blank with no warning, and the differential sweep has no tiling fixture to see it.
- What should happen: ISO 32000-1, 8.7.3.1 puts no limit on the number of cells; a viewer renders the cell once into a tile and repeats it, as MuPDF does (`pdf_show_pattern` draws the cell into a tile pixmap when the cell is small and the fill large), so the cost is one cell render plus a blit per tile.
- How to reproduce or evidence: a page with `/Pattern << /P0 << /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 2 2] /XStep 2 /YStep 2 /Resources << >> /Length ... >> >>` whose cell is `0 0 1 1 re f`, and content `/Pattern cs /P0 scn 0 0 612 792 re f`: KitePDF paints nothing; `mutool draw` paints a dot screen. `PageRenderer.kt:1861`: `if (tiles <= 0 || tiles > MAX_TILES) return`.
- Cross-effects: pattern-stroked paths and pattern-filled text (`strokeWithPattern`, `glyphShapes`) go through the same function, so they vanish too. `KitePattern.Tiling.baseColor` exists as "the flat fallback for consumers that don't replay cells" but is never used here, so there is not even a flat fallback.
- Fix sketch: when `tiles` is large, render one cell into a `KiteImageData` (through `rasterStep` or a `KiteRasterCanvas` of the cell's device size) and draw it per tile, or at least fill the region with `baseColor` instead of returning; also cap the per-tile replay path at a count derived from the pixel budget rather than a fixed 20000.

### PDF-RENDER-03: A tiling pattern with a negative XStep or YStep paints its cells in the wrong place
- Repo: KitePDF
- Area: kitepdf-pdf, `render.PageRenderer.renderTilingPattern`
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1855-1859`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1897-1898`
- What happens: the index range `i0..i1` is computed from `axs = abs(xs)`, so it is the range of offsets `k * axs` that cover the region. The tile matrix then uses the signed step: `KiteMatrix.translation(i * xs, j * ys)`. With `xs < 0` the tiles land at `-i * axs` for `i` in `i0..i1`, which is the mirror image of the needed range. For a region at pattern-space x in 100..200 with `/BBox [0 0 10 10] /XStep -10`, `i0 = 9` and `i1 = 20`, and the cells are drawn at x from -200 to -90: nothing shows in the fill.
- What should happen: ISO 32000-1, Table 75 says `XStep` and `YStep` "may be either positive or negative, but shall not be zero". The cell at index `i` sits at `i * XStep`, so the index range must be computed from the signed step (or the translation must use `i * axs`).
- How to reproduce or evidence: the example above; `PageRenderer.kt:1856`: `floor((pMinX - pat.bbox.right) / axs)` with `axs = abs(xs)`, then `PageRenderer.kt:1898`: `translation(i * xs, j * ys)`.
- Cross-effects: none seen.
- Fix sketch: use `translation(i * axs, j * ays)`; the cell content is the same whichever sign the step carries.

### PDF-RENDER-04: Every tiling fill re-parses its cell and replays the content once per tile, pushing a clip per tile
- Repo: KitePDF
- Area: kitepdf-pdf, `render.PageRenderer.renderTilingPattern`
- Kind: performance
- Severity: medium
- Confidence: verified
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1863-1871`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1897-1916`
- What happens: for each fill with a tiling pattern, `renderTilingPattern` calls `loadFonts`, `loadXObjects`, `loadColorSpaces`, `loadShadings`, `loadPatterns` and `ContentStreamParser.parse(pat.contentBytes, ...)` again (no cache, unlike forms and Type 3 glyphs which go through `cachedOperations`), then for every tile allocates a `GraphicsStack`, a `KitePath.Builder`, pushes `cellBox` as a clip on the canvas, dispatches every operation of the cell, and pops the clip. The cost is `fills x (parse + tiles x (clip push + ops))`. On AWT a non-rectangular cell clip opens a soft-clip layer per tile (`AwtCanvas.pushClip`, `beginSoftClip`). A page with 2000 small pattern-filled cells (a table with hatched cells, a map legend) parses the same cell 2000 times and runs up to 2000 x tiles clip pushes.
- What should happen: parse the cell once per pattern object (the pattern already has an object number through the resources) and render the cell once to a tile when the fill needs more than a handful of cells (see PDF-RENDER-02); clip once to the fill region, not once per cell, when the cell content stays inside its box.
- How to reproduce or evidence: `PageRenderer.kt:1871`: `val ops = ContentStreamParser.parse(pat.contentBytes, colorSpaces)` inside the per-fill function; `PageRenderer.kt:1904-1905`: `canvas.pushClip(cellBox, tileCtm, false); activeClipCount++` inside the `for (j) for (i)` loop.
- Cross-effects: `MAX_DISPATCHED_OPS` (20 million) is the only bound on the total work; a page can stay under `MAX_TILES` per fill and still spend minutes. `KitePattern.Tiling.hashCode` hashes `contentBytes` fully, so using it as a cache key would be O(n) per lookup; key by object number instead.
- Fix sketch: memoize parsed cell operations and loaded resources per pattern object number in `PageRenderer` (like `formResourceCache`), and render the cell to a bitmap tile when `tiles` exceeds a small number.

### PDF-RENDER-05: An image of more than 40 million pixels draws as a grey placeholder whenever it is shown at half its resolution or more
- Repo: KitePDF
- Area: kitepdf-core `ImageRaster`, every canvas
- Kind: memory
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageRaster.kt:34-40`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageRaster.kt:112-115`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageSampling.kt:76-80`, `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:551-552`, `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:584-598`
- What happens: a canvas asks `toSampledRgbaBytes(sampling)`; with shrink factors of 1 that is `toRgbaBytes()`, which returns null when `width * height > KITE_DEFAULT_MAX_RASTER_PIXELS` (40 MP), and the canvas then draws the placeholder box. `shrinkFactor` only shrinks when the image has at least twice the pixels it covers, so any zoom at which the image is drawn at more than half its own resolution hits the full-size path. A 600 dpi A3 scan (7016 x 9933 = 70 MP) or a 1200 dpi A4 scan (139 MP) renders fine when the page is small on screen and turns into a grey box as soon as the reader zooms in. Below the limit the whole image still expands to RGBA8888, 4 bytes per pixel whatever its depth: a 35 MP bilevel scan becomes a 140 MB array plus the platform bitmap, on a 192 MB Android heap.
- What should happen: a viewer renders the visible part of a large image at the resolution it is shown, as MuPDF does by decoding into a 1 byte per pixel gray pixmap and scaling to the device area, so a scan never disappears because the reader zoomed in.
- How to reproduce or evidence: a PDF with one 7000 x 10000 1-bit CCITT or Flate image drawn over the page; render with `AwtPdfRasterizer.renderToImage(page, scale = 4.0)` (image at 0.69 of its resolution, `shrinkFactor` returns 1): the page shows the placeholder from `drawPlaceholder`. `ImageRaster.kt:39`: `if (pixelCountLong > KITE_DEFAULT_MAX_RASTER_PIXELS) return null`; `ImageRaster.kt:115`: `if (fx == 1 && fy == 1) return toRgbaBytes()`.
- Cross-effects: all six canvases share the path (SkiaCanvas.kt:712, ComposeCanvas.kt:619, AndroidNativeCanvas.kt:536, CoreGraphicsCanvas.kt:651, Canvas2dCanvas.kt:385, KiteRasterCanvas.kt:172). The `ScannedPdfs` benchmark measures thread CPU and memory only at page-fit scale, so it cannot see the zoomed case. The `KiteBitmapCache` default budget (16 MB) means any image over 4 MP is rebuilt on every draw as well.
- Fix sketch: crop the decode to the device clip (convert only the rows and columns under the visible area) or tile the conversion; keep gray and bilevel images as one byte per pixel through to the platform bitmap where the platform allows it (AWT `TYPE_BYTE_GRAY`, Skia `GRAY_8`, Android `ALPHA_8` for stencils).

### PDF-RENDER-06: A page's whole content stream is parsed into a list of operations before the first one is drawn, at about 100 bytes per operation
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

### PDF-RENDER-07: A JPXDecode image ignores the PDF's /ColorSpace and converts CMYK with a formula no other CMYK path uses
- Repo: KitePDF (with ImageKodec)
- Area: kitepdf-core `KiteImageData.from` (JPEG2000 branch), `KiteImageSamples.jpx`; ImageKodec `JpxDecoder`
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:417-451`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageSamples.kt:84-90`, `/home/user/ImageKodec/imagekodec/src/io/github/yuroyami/imagekodec/codec/JpxDecoder.kt:1596`, `/home/user/ImageKodec/imagekodec/src/io/github/yuroyami/imagekodec/codec/JpxDecoder.kt:1689-1699`
- What happens: both JPX branches set `resolvedColorSpace = if (colorSpace == "DeviceRGB") DeviceRGB else DeviceGray` from what the decoder says, and drop `resolvedCs` (the dictionary's `/ColorSpace`) and `decodeArr`. The decoder converts a four-component (enumerated 12, CMYK) codestream to RGB itself with OpenJPEG's `(max - c) * (max - k) / max^2` formula and reports "DeviceRGB". So a `/ColorSpace /DeviceCMYK` or ICC CMYK JPX image is converted with a naive formula while every other CMYK image in the same document goes through `AdobeCmyk` (PDFium's table) or the ICC profile, and the two renderings of the same photo differ visibly (the naive formula gives lighter, more saturated colours). A `/ColorSpace [/Indexed ...]`, `[/Separation ...]`, `[/DeviceN ...]`, `[/Lab ...]` or ICC-tagged RGB JPX image is read as plain gray or sRGB samples.
- What should happen: ISO 32000-1, 8.9.5, Table 89 (`ColorSpace` for JPXDecode): "if present, any colour space specifications in the JPEG2000 data shall be ignored" and the samples are interpreted in the PDF colour space; 7.4.9 says the same and adds that `Decode` is ignored unless `ImageMask` is true. The decoder should hand back the raw component samples (4 for CMYK) and `KiteImageData` should apply the PDF colour space when there is one.
- How to reproduce or evidence: a PDF with the same CMYK photo once as `/Filter /DCTDecode /ColorSpace /DeviceCMYK` and once as `/Filter /JPXDecode /ColorSpace /DeviceCMYK` side by side: the two differ by tens of levels in saturated regions; mutool draws them alike. `KiteImageData.kt:426`: `resolvedColorSpace = if (lazy.colorSpace == "DeviceRGB") KiteColorSpace.DeviceRGB else KiteColorSpace.DeviceGray`.
- Cross-effects: `Overprint.image` and the colour-key mask compare source samples, so for JPX they compare already converted RGB; the rendering intent plumbing (`withIntent`) has nothing to act on for a JPX image in an ICC space. ImageKodec's `Jp2Color.Space.CMYK` means the decoder already knows the component layout.
- Fix sketch: add a raw-components mode to `JpxDecoder` (as `decodeJpegComponents` exists for JPEG) returning N samples per pixel and the component count; in `KiteImageData.from`, when `resolvedCs != null && resolvedCs.componentCount == N` keep `resolvedCs`, else fall back to the decoder's space; keep `/Decode` ignored.

### PDF-RENDER-08: SkiaCanvas turns a luminosity soft mask into alpha with Rec. 709 weights while the other five canvases, the core raster canvas and MuPDF use 0.30, 0.59, 0.11
- Repo: KitePDF
- Area: kitepdf-skia-renderer `SkiaCanvas.applySoftMask`
- Kind: bug
- Severity: medium
- Confidence: likely (the Skia filter's coefficients are from Skia's `SkLumaColorFilter`, which uses BT.709; the KitePDF side is verified)
- Where: `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:826-833`, compared with `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:1092-1094`, `kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:912`, `kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:1068`, `kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt:731`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:221`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteMaskTransfer.kt:55-59`, `kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/SoftMaskFilter.jvm.kt:14 and kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/SoftMaskFilter.kt:27`
- What happens: `SkiaCanvas` composites the mask layer with `ColorFilter.luma` (Skia's `SkLumaColorFilter`, luma = 0.2126 R + 0.7152 G + 0.0722 B). AWT, Android, CoreGraphics, Canvas2D, `KiteRasterCanvas`, `KiteMaskTransfer.toMaskAlpha` and Compose's `LUMINOSITY_TO_ALPHA` (0.299, 0.587, 0.114) all use the 0.30, 0.59, 0.11 weights of ISO 32000-1, 11.3.5.3 (`Lum(C)`), which is also what MuPDF's gray conversion uses (77, 150, 29 over 256). A mask group that paints pure red gives 30 percent opacity on every other canvas and 21 percent on Skia; pure blue gives 11 percent against 7 percent; pure green 59 against 72. A luminosity mask drawn as a colour gradient (common from Illustrator and InDesign opacity masks) therefore fades differently on the Skia renderer.
- What should happen: one luminosity formula on every canvas. ISO 32000-1, 11.6.5.2 derives the mask from "the luminosity of the group's colour", and the only luminosity the standard defines is 11.3.5.3's `0.3 r + 0.59 g + 0.11 b`.
- How to reproduce or evidence: the `syn-smask-luminosity` fixture paints its mask group in `1 g` (white), so no sweep can see the coefficients. Change the mask content to `1 0 0 rg 40 90 80 60 re f` and render with `SkiaCanvas` and `AwtCanvas`: the red rectangle under the mask comes out at alpha 54 (Skia) against 77 (AWT) of 255.
- Cross-effects: `SkiaDifferentialTest` compares Skia with MuPDF, but only through the fixtures above, which are grey. Compose on desktop uses its own matrix (`SoftMaskFilter.jvm.kt`) and so disagrees with `SkiaCanvas` on the same machine.
- Fix sketch: replace `ColorFilter.luma` with a `ColorFilter.makeMatrix` carrying the 0.30, 0.59, 0.11 row (as `SoftMaskFilter.jvm.kt` does), composed with the `/TR` table as now.

### PDF-RENDER-09: SkiaCanvas and AndroidNativeCanvas open a full-surface layer for every transparency group and every soft-masked paint, ignoring the bbox they are given
- Repo: KitePDF
- Area: kitepdf-skia-renderer `SkiaCanvas`, kitepdf-native-renderer `AndroidNativeCanvas`
- Kind: performance
- Severity: medium
- Confidence: verified
- Where: `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:761-763`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:810`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:835`, `kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:583`, `kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:629`, `kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:649`
- What happens: `beginTransparencyGroup` and `applySoftMask` call `saveLayer(null, paint)` (`SaveLayerRec(null, ...)` on Skia), so the layer is the size of the current clip, usually the whole page surface. `PageRenderer.withSoftMask` wraps every single painting operator under a soft mask in its own `applySoftMask`, and `applySoftMask` opens two layers (content and mask). A page of 10000 small masked paints at 300 dpi Letter (8.4 MP, 34 MB per layer) allocates, clears and composites some 680 GB of layer memory on Skia and on Android. The same renderer on AWT sizes the layer from `maskBBox` and the painted bounds (`AwtCanvas.applySoftMask`, `paintedBounds`), Compose from `maskBounds(maskBBox, maskCtm)` (#384), Canvas2D from `deviceArea`, so the slow case is specific to these two canvases.
- What should happen: the layer covers the group's `bbox` under `ctm` cut to the clip, as the other canvases do; the renderer already hands the box over.
- How to reproduce or evidence: `SkiaCanvas.kt:810`: `canvas.saveLayer(null, Paint())`; `AndroidNativeCanvas.kt:629`: `canvas.saveLayer(null, Paint())`. Time a page with 5000 `q /GS0 gs ... re f Q` paints under one `/SMask` on `SkiaCanvas` against `AwtCanvas`.
- Cross-effects: the Vectorized Compose page (`ComposeCanvas`) is not affected; `PdfPageRasterizer` (Skia) and `AndroidPdfBitmapRenderer` are. Also relevant to PDF-RENDER-05: the per-paint layer plus a large image doubles the peak memory.
- Fix sketch: compute the device bounds of `bbox` under the canvas matrix, intersect with the clip bounds (Skia: `canvas.localClipBounds`; Android: `canvas.getClipBounds`), and pass them to `saveLayer`; for a soft mask, pass `maskBBox` under `maskCtm` as Compose does.

### PDF-RENDER-10: SkiaCanvas allocates a native Paint for every fill, stroke, glyph run and image and never closes it
- Repo: KitePDF
- Area: kitepdf-skia-renderer `SkiaCanvas`
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:156`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:174`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:245`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:617`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:687`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:756`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:827`
- What happens: each draw does `Paint().apply { ... }`; the `Path` built for the same draw is closed explicitly (#130) but the `Paint`, a `Managed` native object in Skiko, is left to the Cleaner. A page of 100000 tiny paths creates 100000 native paints and 100000 cleaner registrations; the native memory is freed only when the JVM collects the wrappers. `PathEffect.makeDash` and `Shader` objects are also left to the Cleaner.
- What should happen: one `Paint` per canvas, reset per draw (Skia paints are cheap to mutate), or an explicit `close()` after each draw as for the path.
- How to reproduce or evidence: `SkiaCanvas.kt:156-164`: `val paint = Paint().apply {...}; canvas.drawPath(sk, paint); sk.close()`, no `paint.close()`.
- Cross-effects: `AndroidNativeCanvas` does the same with `android.graphics.Paint` (Java-managed, cheaper), and `ComposeCanvas` goes through DrawScope which pools its paint.
- Fix sketch: keep a `fillPaint`, `strokePaint` and `imagePaint` per `SkiaCanvas`, call `reset()` and set the fields per draw; close path effects and shaders after the draw.

### PDF-RENDER-11: Shading, pattern, function and ExtGState parsers read their entries with the resolver-less accessors, so an indirect value is treated as absent
- Repo: KitePDF
- Area: kitepdf-core `ExtGState.parse`, `KiteFunction.parseTyped`, `KitePattern.parse`, `KiteShading.parseDict`
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/PdfObject.kt:95-111` (the accessors), `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ExtGState.kt:64-72 and :88-90 (orchestrator corrected the line numbers)`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:283-310`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KitePattern.kt:92`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KitePattern.kt:101-106`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:237-245`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:264-289`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:297-308`
- What happens: `PdfDictionary.getInt`, `getReal`, `getName` and `getArray(key)` without `refs` return null for a `PdfReference`. So `/ca 12 0 R` leaves the alpha unchanged, `/BM 12 0 R` leaves the blend mode, `/LW`, `/LC`, `/LJ`, `/ML` the same; a tiling pattern with `/BBox 12 0 R` is dropped (`toRectangle() ?: return null`), so the fill vanishes; `/XStep 12 0 R` becomes 1.0, so a 100 pt cell is tiled every point and the fill hits `MAX_TILES` and vanishes (PDF-RENDER-02); a shading with `/Coords 12 0 R`, `/ShadingType 12 0 R` or `/Function` entries whose `/Domain`, `/C0`, `/C1`, `/Bounds`, `/Encode`, `/Size`, `/BitsPerSample` are indirect becomes `Unsupported` or evaluates with the defaults, so a gradient paints nothing or the wrong colours. No warning is logged in any of these cases.
- What should happen: ISO 32000-1, 7.3.10: "any object ... may be an indirect object", and the content stream exceptions do not cover resource dictionaries. MuPDF's `pdf_dict_get` resolves transparently, so these files render in the oracle and not in KitePDF.
- How to reproduce or evidence: `ExtGState.kt:64`: `val fillAlpha = dict.getReal("ca")` with `PdfObject.kt:97-101` returning null for a reference; `KitePattern.kt:101-103`: `(dict.getArray("BBox"))?.toRectangle() ?: return null`, `dict.getReal("XStep") ?: 1.0`. A fixture `<< /PatternType 1 ... /BBox 20 0 R /XStep 21 0 R ... >>` with `20 0 obj [0 0 10 10]` and `21 0 obj 10` paints nothing.
- Cross-effects: `DefaultColorSpaces.shading` builds a replaced dictionary and re-parses, so it inherits the same gaps; `Type3Data.parse` and `PdfAnnotation.parse` already resolve (`getArray("FontMatrix", refs)`), so the renderer is inconsistent with itself.
- Fix sketch: give the four parsers a `refs` and route every scalar through a small `number(dict[key], refs)` helper, as `ExtGState.deref` already does for `/OP`, `/RI` and `/SMask`.

### PDF-RENDER-12: A stray d1 in page or form content switches every colour operator off for the rest of the page
- Repo: KitePDF
- Area: kitepdf-pdf `render.PageRenderer.dispatch`
- Kind: bug
- Severity: low
- Confidence: verified (the orchestrator reproduced it: the fill after a stray d1 is black)
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1307-1318`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1300`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2853`
- What happens: the `d1` case sets `type3IgnoreColor = true` unconditionally; only the clip part is guarded by `type3Depth > 0`. Outside a Type 3 glyph procedure nothing resets the flag until the next `render()` (the comment at line 329 shows the cross-page case was fixed, not the in-page one). A `d1` that leaks into page content (a char proc pasted as a form, a producer bug) makes every later `g`, `rg`, `k`, `cs`, `sc`, `scn` and their stroking forms a no-op, so the rest of the page paints in the colour that was current at the `d1`. MuPDF's `pdf_run_d1` only sets device flags; page colours stay live.
- What should happen: `d1` and `d0` have meaning only inside a glyph description (ISO 32000-1, 9.6.5, Table 113). Outside one they should be ignored, as other out-of-context operators are.
- How to reproduce or evidence: content `0 0 0 0 10 10 d1 1 0 0 rg 0 0 100 100 re f`: KitePDF fills black, mutool fills red. `PageRenderer.kt:1308`: `type3IgnoreColor = true` with no depth check.
- Cross-effects: inside a real d1 glyph `TYPE3_COLOR_OPS` leaves `sh`, `BI` (coloured inline images) and `Do` (coloured images) alone, so a glyph that breaks 9.6.5 paints its own colours where MuPDF, rendering the glyph as a mask, paints the fill colour; same class of mistake, low impact.
- Fix sketch: `"d1" -> if (type3Depth > 0) { type3IgnoreColor = true; ... }`; optionally skip `sh` and non-mask images while `type3IgnoreColor` is set.

### PDF-RENDER-13: An image mask painted with a pattern as the nonstroking colour paints a flat colour instead of the pattern
- Repo: KitePDF
- Area: kitepdf-pdf `render.PageRenderer` (`Do` image, `BI`, `decodeImageCached`, `paintImage`)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1504-1509`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1541-1547`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:902-930`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2073-2083`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1376`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2143-2149`
- What happens: a stencil mask (`/ImageMask true`) is tinted with `state.current.fillColor`, an `RgbColor`, and `paintImage` never looks at `state.current.fillPattern`. After `/Pattern cs /P0 scn`, `fillColor` is `csp.defaultColor()` (black, `KiteColorSpace.kt:42`) for a coloured pattern, or the base-space colour given with the name for an uncoloured one, and `fillPattern` holds the pattern. The mask is therefore painted solid black (or in the uncoloured pattern's flat colour) where the file asks for a gradient or a tiling through the mask. Cairo's PDF backend emits exactly this construct for `cairo_mask()` with a gradient source, and so do some chart libraries for gradient text rendered as a bitmap mask.
- What should happen: ISO 32000-1, 8.9.6.2: "unmasked areas shall be painted using the current nonstroking colour", and 8.7.3.1 lists stencil masks among the objects a pattern colour may paint. MuPDF's `pdf_show_image` clips to the image mask and runs `pdf_show_pattern` when the fill is a pattern.
- How to reproduce or evidence: `/Pattern cs /P0 scn /Im0 Do` with `/Im0` an `/ImageMask true` XObject and `/P0` an axial shading pattern: KitePDF paints the mask's shape black; mutool paints the gradient through it.
- Cross-effects: `paintFill` and `showText` handle `fillPattern` for paths and glyphs (`paintShadingPattern`, `glyphShapes`), so only images are missing; `paintsNothing` already reads `maskFill`. The decoded-image cache keys stencils by `fillKey(fillColor)`, so a pattern-filled stencil would need a key of its own.
- Fix sketch: in the `Do` and `BI` image paths, when `image.isImageMask && (fillPattern is Shading || fillPattern is Tiling)`, push the stencil as a clip (a canvas call that clips to an image mask, or `pushClip` with the mask's outline from a run-length trace) and paint the pattern over the unit square, as `paintShadingPattern`/`renderTilingPattern` paint a path.

### PDF-RENDER-14: A /BM array whose first name is unknown paints Normal instead of the first recognised mode
- Repo: KitePDF
- Area: kitepdf-core `ExtGState.parse`, `KiteBlendMode.parse`
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ExtGState.kt:66-69 (orchestrator corrected the line numbers)`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteBlendMode.kt:22`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2058-2066`
- What happens: `ExtGState.parse` takes `bm.firstOrNull()` of a `/BM` array and `KiteBlendMode.parse` maps any unknown name to `Normal`. For `/BM [/Foo /Multiply]` the result is Normal. The renderer's own `blendsOtherThanNormal` (used to decide isolation) scans all names of the array, so the two disagree on the same dictionary.
- What should happen: ISO 32000-1, Table 58, `/BM`: "an array of blend mode names ... the application shall use the first blend mode in the array that it recognizes".
- How to reproduce or evidence: `ExtGState.kt:68`: `is PdfArray -> KiteBlendMode.parse((bm.firstOrNull() as? PdfName)?.value)`.
- Cross-effects: none seen beyond the isolation heuristic mismatch.
- Fix sketch: iterate the array and return the first name `KiteBlendMode.parse` recognises other than by its fallback (give `parse` a nullable variant).

### PDF-RENDER-15: A CalCMYK colour space resolves to DeviceGray, so its four operands are read as one grey value
- Repo: KitePDF
- Area: kitepdf-core `KiteColorSpace.resolve`
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:334-340`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:342-398`
- What happens: `resolveByName` and `resolveArray` fall to `DeviceGray` for any name they do not know, which includes `CalCMYK` (`/CS0 /CalCMYK` or `[/CalCMYK <<...>>]`). A `0 1 1 0 sc` in that space then reads as gray 0, black, instead of red; an image in that space is read with one component per pixel instead of four, so it comes out as a stretched grey stripe.
- What should happen: ISO 32000-1, 8.6.5.1 (note at the end of the CIE-based families): the PDF 1.1 `CalCMYK` family is deprecated and a conforming reader shall treat it as `DeviceCMYK`. MuPDF and pdf.js both map it to DeviceCMYK.
- How to reproduce or evidence: `KiteColorSpace.kt:339`: `else -> DeviceGray` with no `CalCMYK` arm in either `when`.
- Cross-effects: `DefaultColorSpaces.isUsable` would accept a resolved `CalCMYK` as a `DefaultCMYK` if it resolved to a four-component space; today it never can.
- Fix sketch: add `"CalCMYK" -> DeviceCMYK` to `resolveByName` and `resolveArray`.

### PDF-RENDER-16: A type 4 or type 6/7 mesh with more than 65536 triangles or patches is cut off silently
- Repo: KitePDF
- Area: kitepdf-core `MeshShadingParser`
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:150`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:199`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:302-303`
- What happens: `parseTriangles` (type 4) stops at `MAX_TRIANGLES = 65536` and `parsePatches` at `MAX_PATCHES = 65536`; the rest of the stream is ignored and the mesh paints only its first part, with no warning. Type 5 has no such cap. GIS and CAD exporters write type 4 meshes with hundreds of thousands of triangles (terrain shading, contour fills); Illustrator gradient meshes stay far below the limit, so the cap is only met by the files where it hurts. Separately, a type 4 stream truncated mid-record throws `ArrayIndexOutOfBoundsException` from `BitStream.read` (no bounds check at `MeshShading.kt:38`), which `parseDict` turns into `Unsupported`, so a damaged mesh paints nothing instead of the triangles read so far (MuPDF paints them).
- What should happen: ISO 32000-1, 8.7.4.5.5 to 8.7.4.5.8 set no limit; the memory of a parsed mesh is bounded by the stream length already (a triangle costs more bytes in the stream than in the parsed list is not true, but the ratio is a small constant), so a cap on triangle count is not a DoS defence that the stream size does not already give.
- How to reproduce or evidence: a type 4 stream with 70000 flag-0 triangles at `/BitsPerCoordinate 16 /BitsPerComponent 8 /BitsPerFlag 8`: KitePDF draws 65536 of them. `MeshShading.kt:150`: `&& triangles.size < MAX_TRIANGLES`.
- Cross-effects: `paintMesh` rasterises whatever was parsed, so a cut mesh looks like a cut image.
- Fix sketch: drop the fixed caps or raise them to a figure derived from the stream length; return the triangles parsed so far when the stream ends early.

### PDF-RENDER-17: A mesh shading is rasterised to one image of at most two megapixels and scaled up with smoothing
- Repo: KitePDF
- Area: kitepdf-core `MeshShading.paintMesh`
- Kind: design
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:457-473`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:696-709`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:738`
- What happens: `paintMesh` draws the whole mesh into one `KiteImageData` whose size is capped at `MESH_MAX_PIXELS` (about 1448 x 1448) and then draws that image over the device box with `interpolate = true`. A mesh that covers a Letter page at 300 dpi (8.4 MP) is drawn at half resolution in each direction and bilinearly enlarged; a type 4 mesh used to draw sharp-edged shapes (Illustrator and Inkscape export flat shapes as meshes when a gradient mesh is on the layer) gets soft edges, and the alpha plane of unpainted pixels is binary so the edges show as blurred steps. The budget is per mesh, not per page, so a page with 500 small meshes allocates 500 rasters.
- What should happen: a mesh drawn through the canvas's own path fill (one `fillPath` per triangle, as MuPDF's draw device does for meshes) keeps device resolution and anti-aliased edges; or the raster should follow the clip area and device resolution without the fixed cap.
- How to reproduce or evidence: `MeshShading.kt:462`: `val scale = if (w * h <= MESH_MAX_PIXELS) 1.0 else sqrt(MESH_MAX_PIXELS / (w * h))`; `MeshShading.kt:707`: `interpolate = true`.
- Cross-effects: the differential sweep's mesh fixtures are 200 pt pages at 96 dpi, far under the cap, so the baseline cannot see the resolution loss.
- Fix sketch: cut the raster to the device clip box (the canvases know their clip bounds) and raise the cap to the raster budget, or emit the triangles as device-space paths when their count is small.

### PDF-RENDER-18: Images in a DeviceN or multi-component Separation space evaluate the tint transform per pixel, with a fresh allocation per sample corner
- Repo: KitePDF
- Area: kitepdf-core `ImageRaster.unpackGeneral`, `KiteFunction.Type0`
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageRaster.kt:85-93`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageRaster.kt:503-530`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:54-76`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:100-137`
- What happens: an 8-bit image with 2 or more components in a space other than DeviceRGB, a curve-matrix space or a 4-component space (so DeviceN with 2 to 32 colorants, Lab, 16-bit anything) goes through `unpackGeneral`, which calls `cs.toRgb(compBuf)` per pixel. `DeviceN.toRgb` evaluates the tint transform: `KiteFunction.evaluate` allocates `clamped` and `raw`; `Type0.evaluateInternal` allocates `e`, `lo`, `frac`, `out` and an `IntArray(m)` per corner, with `2^m` corners and `bitsPerSample` single-bit reads per sample. A 4-colorant DeviceN image of 10 MP costs 10 million x 16 corners x (4 reads of up to 32 bits) and 170 million small allocations. `unpackCmyk8` dedupes runs of equal pixels and `unpackGrayTable` builds a 256-entry table, so the common cases are fine; the DeviceN case has neither.
- What should happen: a per-image cache of converted sample tuples (a hash map from packed sample bytes to RGB, as MuPDF's cached colour converter keeps for separations), and allocation-free function evaluation (scratch arrays on the function instance per thread, or on the stack of the raster loop).
- How to reproduce or evidence: time `toRgbaBytes()` on a 3000 x 3000 image with `/ColorSpace [/DeviceN [/Cyan /Magenta /Yellow /Spot] /DeviceCMYK <Type 0 function with /Size [9 9 9 9]>]`.
- Cross-effects: the same `evaluate` runs for every pixel of a `FunctionBased` shading cell and for every vertex of a mesh; those counts are small. Kotlin/JS pays more per allocation.
- Fix sketch: in `unpackGeneral`, memoise `toRgb` by the packed sample tuple (an `IntArray`-keyed open hash of bounded size, cleared when full); let `Type0` keep its scratch arrays.

### PDF-RENDER-19: An image XObject under a resource dictionary with a Default colour space bypasses both caches on every draw
- Repo: KitePDF
- Area: kitepdf-pdf `render.PageRenderer.decodeImageCached`
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:902-930`
- What happens: when `DefaultColorSpaces.imageSpace` returns a space (the resources hold `/DefaultRGB` or `/DefaultGray`, or `/DefaultCMYK` other than the output intent), the image is decoded with `KiteImageData.from(...)` and returned without an identity (`PageRenderer.kt:914-917`), so the document cache is skipped by design and the canvas's `KiteBitmapCache` never hits either (each `KiteImageData` starts with a fresh `KiteImageIdentity()`). A logo drawn 200 times on a page whose resources declare `/DefaultRGB` (every PDF/A-1 file from some producers does) is Flate-decoded and converted 200 times.
- What should happen: the cache key can include the identity of the default space (the resource object reference of `/DefaultRGB`), so the decode is shared by every draw under the same resources; the KDoc says the skip exists because "the same image can meet other defaults on another page", which a key on (image object, default space object) solves.
- How to reproduce or evidence: `PageRenderer.kt:914-917`: `if (defaultSpace != null) { doc?.countImageDecode(); return KiteImageData.from(...) }` with no `withIdentity`.
- Cross-effects: `KiteBitmapCache` eviction is by budget, so the uncached bitmaps also churn the other images out.
- Fix sketch: `image.withIdentity(doc.imageIdentity(key).child("default:" + System.identityHashCode(defaultSpace)))` is not stable; instead carry the `/ColorSpace` resource reference of the default into the key, or cache per `(objectNumber, defaultSpace)` in the renderer instance.

### PDF-RENDER-20: A negative dash phase is clamped to zero on AWT and wrapped, ignored or passed through on the other canvases
- Repo: KitePDF
- Area: the canvases' `strokePath`, `StrokeOutline.dash`
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:129`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:198`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/StrokeOutline.kt:181-182`, `kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:232`, `kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt:124`, `kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:124`
- What happens: `PageRenderer` passes `dashPhase` through unchecked. AWT does `.coerceAtLeast(0f)` (phase 0), the core stroker wraps modulo the pattern length (`offset += total`), Skia wraps the other way inside `SkDashPathEffect`, Core Graphics and Canvas2D accept a negative offset as a shift backwards, Android hands it to Skia. So `[6 3] -2 d` starts its first dash at different places on each canvas. The same applies to a negative dash length: AWT, Skia and Compose clamp it to 0 (a dot or a solid), the core stroker draws solid, Core Graphics logs an error and draws solid, Android's `scaledDashIntervals` passes it to a `DashPathEffect` whose native effect is null (solid).
- What should happen: one rule in the renderer, before the canvases: ISO 32000-1, 8.4.3.6 defines the phase as a distance into the pattern, so a negative phase wraps (that is also what a PostScript interpreter does with `setdash` after range checking), and a negative length is an error that should fall back to solid.
- How to reproduce or evidence: `AwtCanvas.kt:129`: `(dashPhase * pen.dashScale).toFloat().coerceAtLeast(0f)`; `StrokeOutline.kt:181-182`: `var offset = phase % total; if (offset < 0) offset += total`.
- Cross-effects: `applyExtGState` and the `d` operator both keep negatives; `awtDash`, `composeDashIntervals` and `scaledDashIntervals` differ on negative lengths too.
- Fix sketch: normalise in `PageRenderer` (`d` and `/D`): drop arrays with a negative entry, wrap a negative phase modulo the pattern sum, and remove the per-canvas special cases.

### PDF-RENDER-21: ComposeCanvas paints the /Background of a shading it cannot parse; every other canvas paints nothing
- Repo: KitePDF
- Area: kitepdf-compose-viewer `ComposeCanvas.fillShading`
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ComposeCanvas.kt:692-699`, compared with `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:477`, `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:551`, `kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:319-346`
- What happens: for `KiteShading.Unsupported` (a shading whose parse failed, see PDF-RENDER-11), Compose fills `clipPath` with `shading.background` while AWT, Skia, Android, CoreGraphics, Canvas2D and `KiteRasterCanvas` return. The renderer already paints `/Background` itself for pattern fills (`paintShadingPattern`, `PageRenderer.kt:1814-1816`), so on Compose the background is painted twice and, for `sh` (where Table 78 forbids `/Background`), once too many.
- What should happen: the same output on every canvas; the renderer owns `/Background`, so the canvas should not paint it.
- How to reproduce or evidence: `ComposeCanvas.kt:694-697`: `val bg = shading.background ?: return; if (clipPath != null) fillPath(clipPath, ctm, bg, ...)`.
- Cross-effects: none seen.
- Fix sketch: `is KiteShading.Unsupported -> return` as on the other canvases.

### PDF-RENDER-22: The per-page baseline cannot see a small element vanish, and the sweep compares one canvas with grey-only fixtures for the features that differ between canvases
- Repo: KitePDF
- Area: kitepdf-native-renderer `difftest` (`DiffBaseline`, `DiffHarness`, `SyntheticPdfs`), kitepdf-difftest `ImageDiff`, `CorpusSelection`
- Kind: test-gap
- Severity: medium
- Confidence: verified
- Where: `kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/DiffBaseline.kt:83-101`, `kitepdf-difftest/src/io/github/yuroyami/kitepdf/difftest/ImageDiff.kt:26`, `kitepdf-difftest/src/io/github/yuroyami/kitepdf/difftest/ImageDiff.kt:96-97`, `kitepdf-difftest/src/io/github/yuroyami/kitepdf/difftest/CorpusSelection.kt:17-22`, `kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/SyntheticPdfs.kt:17-36`, `kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/SyntheticPdfs.kt:39-55`, `kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/DiffHarness.kt:199`
- What happens: `DiffBaseline.regression` passes a page whose MAE grows by up to 25 percent plus 0.0005 absolute, whose changed-pixel fraction grows by 25 percent plus 0.002, and whose largest channel error grows by up to 48 levels. At 96 dpi a Letter page is 816 x 1056 = 861696 pixels: 0.002 of them is 1723 pixels, so a 40 x 40 pixel glyph, icon or table rule may disappear (or appear) with no failure; the 0.0005 MAE margin lets a page recorded at 0.00001 (`gen-multipage-mixed p4`, `syn-shading4-freeform`) regress fifty-fold. The MAE averages over the white background, so a feature covering 1 percent of the page can change completely for an MAE move under 0.003. The sweep renders with `AwtPdfRasterizer` only (`DiffHarness.kt:199`); the Skia sweep is separate and no sweep covers Compose, Android, CoreGraphics or Canvas2D, so a canvas disagreement (PDF-RENDER-08, PDF-RENDER-20, PDF-RENDER-21) is invisible by construction. The soft-mask fixture paints its mask group in `1 g` (grey), so the luminosity coefficients never matter. There is no fixture for a tiling pattern, overprint (`/OPM 1`), a 16-bit image, a JPX image, a Lab or CalRGB colour, a stencil mask under a pattern, text render modes 1, 2 or 4 to 7 inside the sweep (`TextClipOracleTest` covers 7 separately), or an annotation with a non-identity appearance `/Matrix`, so PDF-RENDER-02, 03, 07 and 13 pass the gate today.
- What should happen: a baseline margin that is tight on the absolute scale for near-zero pages (for example a fixed 0.0002 MAE and 0.0005 fraction floor, with the relative margin only above that), a tiled or per-feature comparison for the fixtures (as `ThreeWayDiff` already does with 24 x 24 tiles for the parity check), one coloured luminosity mask fixture, and fixtures for the features above; and the other canvases drawn at least through the oracle fixtures (`RecordingCanvas` parity or a per-platform smoke sweep) so a canvas-only regression has a gate.
- How to reproduce or evidence: `DiffBaseline.kt:98-101`: `FACTOR = 1.25; MAE_MARGIN = 0.0005; FRACTION_MARGIN = 0.002; DELTA_MARGIN = 48`. Remove the 8-glyph word from `syn-text` and compare the scores: well within margin.
- Cross-effects: `PdfiumParityTest` tiles the page and would see a vanished element, but only when PDFium and MuPDF agree and only for the corpus pages selected (6 per document, first and last included, `CorpusSelection.indices`).
- Fix sketch: add the absolute floors and a tile-max metric to `DiffBaseline.Score`; add the missing fixtures to `SyntheticPdfs`; render the oracle fixtures through `KiteRasterCanvas` as a seventh engine in `ThreeWayDiff` so a portable baseline exists for the platform canvases to compare with in their own module tests.

### PDF-RENDER-23: KiteRasterCanvas allocates a canvas-sized float mask for every clip push and canvas-sized layers for every group and mask
- Repo: KitePDF
- Area: kitepdf-core `KiteRasterCanvas`
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:145-160`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:188-191`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:205-228`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/CanvasHost.kt`
- What happens: `pushClip` allocates `FloatArray(width * height)` (4 bytes per pixel of the whole canvas) per clip, intersecting with the clip under it; `beginTransparencyGroup` and `applySoftMask` allocate `IntArray(width * height)` per layer and the mask another `FloatArray`. The canvas is the backing of the EPUB script `<canvas>` host (`CanvasHost.kt`), where a script that clips per draw (a common pattern in canvas games and charts) costs a full-canvas allocation per `clip()`; at 2048 x 1536 that is 12.6 MB per call. `composite` and `endTransparencyGroup` also walk the whole clip box rather than the painted bounds.
- What should happen: clips as spans or as a mask over their own device bounds (`RasterScan.fill` already reports the row span), layers sized to the group's bounds, as the other canvases do.
- How to reproduce or evidence: `KiteRasterCanvas.kt:147`: `val mask = FloatArray(width * height)` in `pushClip`.
- Cross-effects: `Overprint` and the knockout path run through `rasterStep` on this canvas in tests (`OverprintTest`), where the cost does not show.
- Fix sketch: store the clip mask over its bounding box only (left, top, width, height), and size layers from `bbox` under `ctm`.

### PDF-RENDER-24: CoreGraphicsCanvas copies the whole RGBA buffer and rebuilds a CGImage on every draw of a cached image
- Repo: KitePDF
- Area: kitepdf-native-renderer `CoreGraphicsCanvas.rawCgImage`
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:648-670`, `kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:1169-1171`
- What happens: the `KiteBitmapCache` holds Kotlin `RgbaImage` arrays, and each draw calls `toCFData()`, which `CFDataCreate` copies, then `CGDataProviderCreateWithCFData` and `CGImageCreate`, and releases all three after the draw. A page that draws one 4 MP image 50 times (a tiled background, a repeated stamp) copies 50 x 16 MB and decodes the image into a CGImage 50 times; the cache saves only the colour conversion.
- What should happen: cache the `CGImageRef` (retained, released on eviction or `endPage`) or at least the `CGDataProviderRef`, so a draw is one `CGContextDrawImage`.
- How to reproduce or evidence: `CoreGraphicsCanvas.kt:656-669`: `val cfData = pixels.rgba.toCFData() ...; CGImageCreate(...)` inside `rawCgImage`, which runs per draw after the cache lookup.
- Cross-effects: `KiteBitmapCache` has no release hook, so caching native refs needs an eviction callback (the KDoc notes the cache "does not recycle native resources").
- Fix sketch: wrap the `CGImageRef` in a class with a `close()`, give `KiteBitmapCache` an optional `onEvict`, and keep the image across draws.

### PDF-RENDER-25: Shared colour spaces, images and ICC profiles memoise their per-intent variants in plain arrays without synchronisation
- Repo: KitePDF
- Area: kitepdf-core `KiteColorSpace.rendering`, `KiteImageData.withIntent`, `KiteImageData.lineArt`, `IccProfile.forRendering`
- Kind: concurrency
- Severity: info
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:77-84`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:208-225`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:228`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/IccProfile.kt:97-113`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageSamples.kt:37`
- What happens: colour spaces, decoded images and ICC profiles are shared between pages and threads through the document caches and the global profile cache (`PdfDocument.cachedImage`, `IccProfile.cache`). Their `renderings` arrays, `lineArt` pair and `failed` flag are written without a lock or atomics from whichever render thread first asks. The result is benign on the JVM and under Kotlin/Native's current memory model (a lost write means a second build of an equal object, never a torn one), but the `KiteCanvas` KDoc promises that "a canvas must not share mutable state with another canvas without a lock", and these shared objects are the mutable state canvases read through `KiteBitmapCache` keys (`bitmapIdentity.rendering(...)`).
- What should happen: either `@Volatile`/atomic publication with a documented "may build twice" rule, or a lock as `IccSource.tables` already has.
- How to reproduce or evidence: `KiteColorSpace.kt:81-83`: `val all = renderings ?: arrayOfNulls<KiteColorSpace>(8).also { renderings = it }` with `private var renderings` and no lock.
- Cross-effects: none beyond duplicate work; noted so the owner can decide whether the KDoc or the code should move.
- Fix sketch: a `KiteLock` per holder, or `kotlin.concurrent.AtomicReference` for the array and `compareAndSet` on each slot.

### PDF-RENDER-26: A PageRenderer reused for a second page serves a resource-less form XObject the first page's resources
- Repo: KitePDF
- Area: kitepdf-pdf `render.PageRenderer` (public API)
- Kind: api
- Severity: low
- Confidence: verified
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:57-58`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:144`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1060-1078`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:297-358`
- What happens: `PageRenderer` is public and its KDoc says every call to `render` "starts with a fresh state stack". `render()` resets the stacks but not `formResourceCache`, `type3Data` or `maskTransfers`. `formResourceCache` is keyed by the form's object number, and for a form without its own `/Resources` the cached `FormResources` were built from `pageResources` of the page that first drew it. A second page rendered with the same instance that uses the same form under different page resources (the 7.8.3 fallback) draws it with the first page's fonts and colour spaces. The library's own callers (`PdfPage.renderTo`) create a renderer per render, so only API users hit it.
- What should happen: either clear the per-page caches in `render()` and `renderAnnotations()`, or key the cache by (object number, has own resources) and skip it for the fallback case, or document that a `PageRenderer` serves one page.
- How to reproduce or evidence: `PageRenderer.kt:1062`: `val resources = formStream.dict.getDict("Resources", resolver) ?: pageResources` inside `buildResources()`, memoised at `PageRenderer.kt:1077` by `objectNumber` only.
- Cross-effects: `type3Data` keyed by `PdfFont` identity is safe because fonts are cached per document; `maskTransfers` keyed by `KiteFunction` identity is safe for the same reason.
- Fix sketch: `if (!ownResources) buildResources() else cache.getOrPut(...)`, and `formResourceCache.clear()` at the top of `render()`.

### Capabilities and opportunities (not defects)

- Transfer functions in the graphics state (`/TR`, `/TR2`, ISO 32000-1, 10.4) are parsed nowhere and never applied to paints; `ExtGState` KDoc lists only `/SMask /TR`. PDFium and pdf.js apply them; MuPDF does not. Reach: inverted or gamma-adjusted scans from some producers; cost: a 256-entry table per channel applied in the raster conversion and as a colour filter on the canvases.
- `/NeedAppearances true` (12.7.2) is read by `PdfAcroForm` but the renderer still draws a stored `/AP` for a widget unless `PdfFormState` marks the field changed (`renderAnnotation`, `PageRenderer.kt:556-566`). Regenerating from `/DA` and `/V` when the flag is set would show the values Acrobat shows. Reach: forms filled by servers that do not write appearances; cost: small, `FieldAppearance.synthesize` exists.
- The glyph raster cache exists on AWT (#306) and the glyph mask cache on Compose (#382); Skia, Android, CoreGraphics and Canvas2D fill a path per glyph per draw. A shared `KiteCanvas`-side cache keyed by (outline identity, matrix a and d, subpixel position) as in `AwtCanvas.GlyphKey` would bring text-heavy pages on those canvases to parity.
- A tile-once path for tiling patterns (render the cell to a bitmap, repeat it) would fix PDF-RENDER-02 and -04 together and match MuPDF's `pdf_show_pattern`.
- A soft mask is re-rendered for every painting operator under it (`withSoftMask` per paint, as MuPDF also does). Caching the rendered mask layer across consecutive paints with the same `softMask` and `softMaskCtm` would make pages with thousands of masked paints (Illustrator opacity masks) an order of magnitude faster on every canvas.
- Region-limited image decoding (decode and convert only the rows and columns under the device clip) would remove the 40 MP placeholder (PDF-RENDER-05) and the full-page RGBA expansion for zoomed scans.
- A streaming content-stream interpreter (PDF-RENDER-06) would bound memory for CAD and map PDFs.
- The exact non-isolated and knockout compositing of 11.4.8 works only where `rasterStep` can read the backdrop: AWT anywhere, Compose and Android only on a rasterizer canvas outside layers, Skia only outside layers, CoreGraphics only on a bitmap context. Documented in `KiteCanvas.rasterStep`; worth a line in docs/compose-viewer.md so a viewer author knows which canvas to pick for print-faithful output.
- Text render mode 7 on a Type 3 font adds nothing to the clip (`showTextType3` returns before `accumulateTextClip`); ISO 32000-2, 9.3.6 makes modes 4 to 7 apply to Type 3 glyphs as the glyph's painted shape. Low reach.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane FONTS-TEXT lane: fonts, glyphs, encodings, shaping tables, text extraction and search

Repo: KitePDF unless stated. Line numbers are from the checkout read on 2026-11-04. Scratch probes live in
`/tmp/claude-0/-home-user/8fa91e2b-1e52-50dc-8511-48f93ec9d76e/scratchpad/FONTS-TEXT/` (`seac_probe.py` reads the
bundled standard 14 programs, `corpus_probe.py` scans `corpus/pdf`).

### FONTS-TEXT-01: A Type 2 charstring that composes an accented glyph with `endchar` draws nothing, because the CFF font never hands the interpreter a seac renderer
- Repo: KitePDF
- Area: kitepdf-core/font (CffFont, CharstringInterpreter)
- Kind: bug
- Severity: high
- Confidence: verified (read the whole path; grep shows no caller passes `seacRenderer`)
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:108-115`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:48`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:550-567`
- What happens: `CharstringInterpreter.endchar()` recognises the deprecated accent composition form, `adx ady bchar achar endchar` (Type 2 charstring spec, Appendix C), pops the four operands and calls `seacRenderer?.invoke(...)`. `CffFont.outline()` constructs the interpreter with `cs, locals, globalSubrs, defaultWidthX, nominalWidthX, cff2, regionCount, vsindex` and never passes a `seacRenderer`, so the hook is always null. The glyph then ends with `done = true` and an empty path: `outline()` caches `null`-equivalent emptiness, `glyphSpaceOutline` returns an empty path, the canvas paints nothing, and no warning is logged. Every accented letter of a Type1C font that keeps the Type 1 `seac` construction (Acrobat Distiller and other Type 1 to CFF converters keep it for é, ü, ñ and so on) vanishes from the page. This is exactly the pattern CLAUDE.md records for #303 (a lookup that misses draws nothing silently). The Type 1 interpreter does wire its resolver (`Type1Font.kt:57`), so only the CFF path is affected.
- What should happen: the accent composite draws the base glyph at the origin and the accent translated by `(adx, ady)` (Type 2 charstring spec, Appendix C: bchar and achar are StandardEncoding codes resolved through the font's charset names).
- How to reproduce or evidence: `grep -rn "seacRenderer" kitepdf-core/src` finds only the parameter declaration and its two uses inside `CharstringInterpreter.kt`. The corpus probe found 183 Type1C programs in `corpus/pdf`, none using seac, and the five bundled URW programs do not use it either (`seac_probe.py`), which is why no sweep caught it. A fixture: a Type1C font whose `eacute` charstring is `<adx> <ady> 101 194 endchar` (101 = `e`, 194 = `acute` in StandardEncoding) renders a blank where é should be.
- Cross-effects: `CffSubsetter` documents that it does not follow seac either (`CffSubsetter.kt:19-21`), so a writer that subsets such a font also loses the components; both symptoms share the fix in `CffFont`.
- Fix sketch: in `CffFont.outline()` pass a renderer that maps `bchar`/`achar` through `Encodings.standardEncoding` to a name, `nameToGid`, then `outline(gid)` (guard recursion with the existing `done`/depth state), appending the accent translated by `(adx, ady)`. Add a test with a synthetic seac glyph and a real one from a Distiller font.

### FONTS-TEXT-02: A composite font's ToUnicode text is re-segmented by the ToUnicode's own codespace, so a one-byte or missing codespace turns every two-byte code into "�" plus its text
- Repo: KitePDF
- Area: kitepdf-core/font (CompositeFont, CMap)
- Kind: bug
- Severity: medium
- Confidence: verified (read the whole path; not present in the corpus)
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:108`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:122`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CMap.kt:90-110`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CMap.kt:133-150`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CMap.kt:242`
- What happens: `CompositeFont.textOf` already knows the code unit (the Encoding CMap or Identity-H segmented it) but calls `toUnicode.decodeAll(bytes.copyOfRange(offset, offset + count))`, and `CompositeFont.decode` calls `decodeAll(bytes)` over the whole string. `decodeAll` segments by the ToUnicode's `begincodespacerange`; without one, `codeWidth` stays 1 (`CMap.kt:242`). A ToUnicode that declares `<00> <FF>` or no codespace at all while its `bfchar` keys are two bytes (`<0041> <0041>`, written by several producers) makes `segment()` read one byte: `00` has no mapping, so `decodeAll` appends U+FFFD and advances one byte, then `41` matches the key 0x41 and gives "A". Every glyph extracts as "�A", and search for "A" matches but the plain text is polluted. MuPDF looks the ToUnicode up by the code the Encoding CMap produced and never re-segments (`pdf_show_char`, `pdf_lookup_cmap(fontdesc->to_unicode, cpt)`); the simple-font path here does the same (`PdfFont.decodeSingle` calls `textFor(code)`), so only Type 0 fonts suffer.
- What should happen: ISO 32000-1, 9.10.3: the ToUnicode CMap maps the character codes of the font's encoding; the code is already known, so the lookup should be by code value, as `textFor(code)` does.
- How to reproduce or evidence: a Type 0 Identity-H font with `/ToUnicode` holding `1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <0041> <0041> endbfchar` and the string `<0041>`: `PdfFont.decode` and `layoutBytes(...).text` return "�A". `corpus_probe.py` checked all 248 ToUnicode CMaps in `corpus/pdf`: every one declares a codespace that agrees with its keys, so no gate sees this.
- Cross-effects: `TextExtractor.decode` (`kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextExtractor.kt:296`) uses `PdfFont.decode`, `StructuredText` uses `layoutBytes`; both paths carry the replacement characters into search and copy.
- Fix sketch: in `textOf`, call `toUnicode.textFor(code)` with the integer code of the unit (add `code` to `CidUnit`); in `decode`, iterate `codeUnits` and look each code up the same way, falling back to `textOf` semantics. Keep `decodeAll` for the rare caller without a code.

### FONTS-TEXT-03: An embedded Encoding CMap stream's `/UseCMap` and `usecmap` are ignored, so a CMap that only overrides a predefined one maps every other code to CID = code
- Repo: KitePDF
- Area: kitepdf-core/font (CMap, CompositeFont)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CMap.kt:235-266`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:151-156`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:72-75`
- What happens: `CMap.parse` walks the keywords `begincodespacerange`, `beginbfchar`, `beginbfrange`, `begincidchar`, `begincidrange` and `/WMode` only. The `usecmap` operator in the program and the `/UseCMap` entry of the stream dictionary are never read (`grep -rn usecmap kitepdf-core/src` matches only the bundled predefined tables). A CMap stream such as `/CIDSystemInfo ... /90ms-RKSJ-H usecmap 1 begincidrange <8140> <8140> 633 endcidrange` therefore has one range and, worse, no codespace of its own, so `codeUnits` segments every byte singly and `lookupCid(code) ?: code` returns the byte as the CID. The text renders as a column of wrong glyphs. When the stream holds a codespace but no mappings beyond its overrides, every unlisted code still falls to CID = code.
- What should happen: ISO 32000-1, 9.7.5.3, Table 120: `/UseCMap` names a predefined CMap or another stream whose codespaces and mappings this CMap extends; the `usecmap` operator in the program means the same (Adobe TN 5014, 7.4). The lookup should fall through the chain, as `TableCMapReader` already does for bundled names.
- How to reproduce or evidence: a Type 0 font whose `/Encoding` is a stream with `/UseCMap /90ms-RKSJ-H` and one `cidchar`; show the bytes of any kanji not in the override. `PdfFont.layoutBytes` gives two one-byte units with CIDs equal to the bytes.
- Cross-effects: `vertical` detection reads only the stream's own `/WMode`, so `/UseCMap /Identity-V` with no `/WMode` also loses the writing mode.
- Fix sketch: in `CompositeFont.from`, read `/UseCMap` (name or stream) and the `usecmap` name token in `CMap.parse`; give `CMap` a `parent: CodeUnitReader?`/`CMap?` that `segment` and `lookupCid` fall through to, and inherit `writingMode` from it.

### FONTS-TEXT-04: `/MissingWidth` is read from the font dictionary instead of the font descriptor, so it never applies
- Repo: KitePDF
- Area: kitepdf-core/font (PdfFont)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:621-662`, in particular `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:659`
- What happens: `resolveWidths(dict, ...)` receives the font dictionary and evaluates `dict.getInt("MissingWidth")`. `/MissingWidth` is an entry of the font descriptor (ISO 32000-1, 9.8.1, Table 122), never of the font dictionary, so the lookup always misses and the fallback becomes 0 whenever a `/Widths` array exists. A simple font whose `/Widths` is shorter than `LastChar - FirstChar + 1`, or whose text uses codes outside `[FirstChar, LastChar]`, with a descriptor `/MissingWidth 500` (common for TrueType subsets from Word and for Type 3 wrappers), advances those glyphs by zero and stacks them on one another. No test mentions `MissingWidth` (`grep -rn MissingWidth kitepdf-core/test kitepdf-pdf/test` is empty).
- What should happen: codes with no `/Widths` entry advance by the descriptor's `/MissingWidth`, default 0 (Table 122).
- How to reproduce or evidence: `/FirstChar 65 /LastChar 66 /Widths [600 600]` with `/FontDescriptor << /MissingWidth 500 /Flags 32 ... >>`, text `(ABC)`: C advances 0 here, 500 in MuPDF and Acrobat.
- Cross-effects: `defaultWidth` also feeds `forEachGlyphAdvance` (redaction layout) and `widthOf`.
- Fix sketch: pass `descriptor` into `resolveWidths` and read `descriptor?.getInt("MissingWidth")`, keeping the 500 fallback for a standard 14 font with no `/Widths`.

### FONTS-TEXT-05: An embedded Type 1 program is sliced by `/Length1` and `/Length2` as the file states them, so a wrong `/Length1` (or a missing `/Length2`) silently loses every glyph
- Repo: KitePDF
- Area: kitepdf-core/font (Type1Font, PdfFont)
- Kind: bug
- Severity: medium
- Confidence: verified (read the whole path; the 11 FontFile programs in the corpus all state the right lengths)
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:446-454`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/Type1Font.kt:99-111`
- What happens: `loadEmbeddedType1` returns null when `/Length1` or `/Length2` is absent or zero, and `Type1Font.parse` cuts the clear text at exactly `l1` bytes and decrypts `flat[l1, l1 + l2)` as the eexec section. The string `eexec` is never searched for (`grep -n eexec Type1Font.kt` shows only variable names). Producers often write `/Length1` a few bytes off (a CR LF after `eexec`, or the length of a different copy of the font); when the eexec section starts even one byte late the whole decryption is noise, `parseCharStrings` finds nothing, `charStrings` is empty, and every glyph of the font returns a null outline. Because `hasEmbeddedOutlines` is true, the canvas does not fall back to a host face either: the text is invisible, with no warning. FreeType (`T1_Get_Private_Dict` in t1parse.c) and pdf.js both locate `eexec` in the stream and ignore the declared lengths.
- What should happen: find the `eexec` token, skip the white space after it, and treat the rest as the encrypted section; if `/Length2` is missing, run to the trailer (512 zeros / `cleartomark`) or the end. Lenient salvage: a font that still yields no charstrings should report `hasEmbeddedOutlines = false` so the host face stands in.
- How to reproduce or evidence: take any pdfTeX page, add 2 to `/Length1` of a `/FontFile` stream and save with `mutool clean`; the text in that font disappears while mutool still renders it.
- Cross-effects: `PdfFont.hasOutlines` drives `PageRenderer.showText`'s decision to stroke, clip and fill through the font program (`PageRenderer.kt:2252-2255`), so the invisible-text failure also hides mode 1 and 2 text.
- Fix sketch: in `Type1Font.parse`, `val ee = indexOfBytes(flat, "eexec")`; use it when found, else fall back to `l1`; make `/Length2` optional; in `PdfFont.from`, drop the `embeddedType1` when it parsed no charstrings.

### FONTS-TEXT-06: A nonsymbolic TrueType font that has only a (1,0) cmap maps WinAnsi codes above 0x7F to the wrong glyph, because the name is looked up as a Unicode code point instead of a Mac Roman code
- Repo: KitePDF
- Area: kitepdf-core/font (PdfFont, TtfCMap)
- Kind: bug
- Severity: medium
- Confidence: likely (read the whole path; not run; the three (1,0)-only fonts in the corpus are symbolic and unaffected)
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:264-289`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TtfCMap.kt:23-80`
- What happens: `TtfCMap.parse` picks one subtable by rank (`(3,10)`, `(0,4)`, ... `(1,0)`, then everything else) and exposes only `glyphIdFor(codePoint)`. `PdfFont.simpleGid` first tries `ttf.glyphIdForCodePoint(resolveByteToUnicode(code))`. For a font whose only subtable is `(1,0)` (format 0 or 6, Mac Roman codes), the Unicode value of the glyph name is indexed straight into the Mac Roman table: `/eacute` (WinAnsi 0xE9, U+00E9) reads entry 0xE9 of the Mac table, which is `Edieresis`, a non-zero glyph, so no fallback runs and the wrong letter is drawn. Every WinAnsi code from 0x80 up suffers in such a font. ISO 32000-1, 9.6.6.4 says: for a nonsymbolic font with a `(1,0)` subtable, map the glyph name to a code through the standard Roman character set (Mac Roman) and look that code up. MuPDF does this (`pdf_load_simple_font`, the `mrt` reverse table); KitePDF has `Encodings.macRomanEncoding` but no reverse lookup. The same picker also ranks a `(3,0)` symbol subtable below `(1,0)` (`rank` returns 9 for `(3,0)`), so a font with both uses the Mac table for the F0xx probing and `SymbolSubtable` wrapping is dead for it.
- What should happen: 9.6.6.4: with a `(3,1)` subtable, name to Unicode to glyph; with only `(1,0)`, name to Mac Roman code to glyph; for a symbolic font, `(3,0)` with the code and `F0xx`/`F1xx`/`F2xx`, else `(1,0)` with the code.
- How to reproduce or evidence: embed a Mac TrueType subset that carries only a `(1,0)` format 0 cmap, `/Encoding /WinAnsiEncoding`, flags 32, text `(caf\351)`: the é renders as Ë.
- Cross-effects: EPUB `EmbeddedFace.gidFor` uses the same single-subtable picker; browsers and HarfBuzz prefer Unicode subtables and ignore `(1,0)` for Unicode text, which the ranking already does when one exists.
- Fix sketch: let `TtfCMap` expose which platform/encoding it chose (or keep the `(3,0)`, `(3,1)` and `(1,0)` subtables separately); in `simpleGid`, when the chosen table is `(1,0)`, reverse `Encodings.macRomanEncoding` for the glyph name before the lookup, and prefer `(3,0)` over `(1,0)` for symbolic fonts.

### FONTS-TEXT-07: A broken `/CIDToGIDMap` stream throws out of `PdfFont.from` and takes the whole page render and text extraction with it
- Repo: KitePDF
- Area: kitepdf-core/font (CompositeFont), kitepdf-pdf/render, kitepdf-pdf/text
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:173`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:243-249`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:303`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1244-1256`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextExtractor.kt:238-244`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/StructuredText.kt:194`
- What happens: every other embedded stream a font reads is wrapped in `runCatching` (FontFile2, FontFile3, FontFile, ToUnicode, Encoding CMap), but `CidToGidMap.parseStream` calls `FilterChain.decode(stream)` bare. `FilterChain.decode` throws `PdfFormatException` when `/Filter` is not a name or array of names (an indirect `/Filter 12 0 R` is legal but rejected at `Filters.kt:124`), `UnsupportedFilterException` for an unknown filter, and `InflateException` on a corrupt Flate block (`Inflate.kt:89`). The exception leaves `CompositeFont.from`, `PdfFont.from` and `PageRenderer.loadFonts`, which `render()` calls before the content loop with no try (`PageRenderer.kt:303`), so one bad font fails the whole page instead of one glyph run. `PdfPage.extractText()` (`TextExtractor.run` line 86) and `StructuredTextExtractor.extract` (`StructuredText.kt:194`) have the same exposure; only `PdfReadingOrder` wraps the render (`PdfReadingOrder.kt:64`). This breaks the CONTRIBUTING rule that a corrupt font becomes a skip, never a page failure.
- What should happen: a `/CIDToGIDMap` that cannot be read degrades to Identity (ISO 32000-1, 9.7.4.2 default) with a warning, and a font that cannot be built falls back to the Helvetica substitute (`PdfFont.fallback()`), as `PdfFont.from` already does for a non-dictionary.
- How to reproduce or evidence: a Type 0 font whose CIDFontType2 descendant has `/CIDToGIDMap 20 0 R` where object 20 is `<< /Filter /FlateDecode /Length 4 >> stream \xff\xff\xff\xff endstream`; `page.renderTo(canvas, ctm)` throws, `page.extractText()` throws.
- Cross-effects: a `PdfFont.from` failure in `PdfEditorRedaction.kt:621` or `RedactionEngine.kt:180` stops a redaction the same way.
- Fix sketch: wrap `parseStream` in `runCatching { ... }.getOrNull()` returning Identity on failure, and wrap `PdfFont.from` in the three `loadFonts` with a fallback to `PdfFont.fallback()` plus `kiteWarn`.

### FONTS-TEXT-08: Table counts read from the font file size allocations before any bound: a cmap format 12 with a huge `numGroups`, a CFF2 INDEX count, or a GPOS class matrix can demand gigabytes from a few bytes
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

### FONTS-TEXT-10: Text strings without a BOM are decoded as Latin-1 rather than PDFDocEncoding, and the PDF 2.0 UTF-8 BOM is not recognised
- Repo: KitePDF
- Area: kitepdf-core/parser (PdfString), kitepdf-core/font (Encodings)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/PdfObject.kt:59-74`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/Encodings.kt:39-72`
- What happens: `PdfString.asText()` handles the `FE FF` BOM and otherwise maps each byte to the char of the same value. ISO 32000-1, 7.9.2.2 says a text string without a BOM is PDFDocEncoding (Annex D.3), which differs from Latin-1 in 0x18 to 0x1F (accents), 0x7F to 0x9F (bullet, dagger, ellipsis, em dash, en dash, fi, fl, quotes, trademark, Scaron, OE, ...) and 0xA0 (Euro). A bookmark titled `Caf\x8e Society` (right double quote) or an info `/Title` with an em dash (0x84) comes back with C1 control characters; the Euro at 0xA0 reads as a no-break space; `fi`/`fl` (0x93, 0x94) become control characters. ISO 32000-2, 7.9.2.2.1 adds the UTF-8 BOM `EF BB BF`, which `asText` leaves in the text as "ï»¿". The correct table already exists as `Encodings.pdfDocToUnicode`, and `grep -rn pdfDocToUnicode` shows nothing uses it. `asText` feeds document info, outlines, annotation contents, form field values, `/ActualText` (`TextExtractor.kt:234`) and the `s.asText()` fallback of text extraction.
- What should happen: no BOM: PDFDocEncoding through `Encodings.pdfDocToUnicode` (undefined codes as U+FFFD or dropped); `FE FF`: UTF-16BE; `EF BB BF`: UTF-8 (ISO 32000-2).
- How to reproduce or evidence: `/Title (A\204B)` extracts as "A\u0084B" instead of "A\u2014B"; `/Title <EFBBBF41>` extracts as "ï»¿A".
- Cross-effects: `PdfDate.parse` is fed by `asAsciiOrNull()` (`PdfDocumentInfo.kt:44`), so a date stored as UTF-16 with BOM, which 7.9.4 allows for a text string, is dropped.
- Fix sketch: in `asText`, test the UTF-8 BOM first, then UTF-16BE, then map bytes through `Encodings.pdfDocToUnicode`, keeping the byte for a 0 entry; decode dates through `asText` before `PdfDate.parse`.

### FONTS-TEXT-11: `CffFont` keeps the whole font program alive in an unused `reader` field, and `retainedBytes` counts neither it nor the CMap and CIDToGID tables
- Repo: KitePDF
- Area: kitepdf-core/font (CffFont, PdfFont), kitepdf-pdf (PdfDocument font cache)
- Kind: memory
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:29`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:78-80`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:305-306`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:77-82`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:455-489`
- What happens: `CffFont` stores `private val reader: TtfReader`, which holds the full program byte array, and no instance method reads it after construction (the only non-companion reference is the declaration). `charStrings`, `globalSubrs` and `localSubrsPerFd` are copies (`reader.slice` allocates), so a CID-keyed CJK CFF of 8 MB holds 8 MB of dead bytes plus the copies. `programBytes` counts the copies only, and `PdfFont.retainedBytes` is `16 KB + 2 * programBytes`, so the document font cache (`fontCacheBudgetBytes`, default 32 MB) under-counts every CFF font by its program size. The same estimate charges 16 KB for every table that is not a program: a `CidToGidMap` with 65536 entries is 256 KB, a ToUnicode `bfChars` map with 20 000 entries is a few MB of boxed `Int` keys and `String` values, `CidWidthTable` and `PredefinedCMap` chains are not counted at all. A document with many non-embedded CJK fonts carrying large ToUnicode maps keeps far more than 32 MB under the budget, the #224 pattern (the budget only counts what it lays out).
- What should happen: the font keeps only what it uses, and `retainedBytes` includes the tables it keeps.
- How to reproduce or evidence: open a PDF with a 10 MB CIDFontType0C font, render one page, take a heap histogram: `byte[]` of the raw program is reachable from `CffFont.reader` with no reader call sites.
- Cross-effects: EPUB `FontProgram` keeps the `TrueTypeFont` (whose `reader` is used for `rawTable`) and a `CffFont` parsed from the `CFF ` table, so an `.otf` book holds the CFF bytes twice too.
- Fix sketch: drop the `reader` constructor parameter (the parse is complete before construction); add the sizes of `cidToGid`, the width tables and both CMaps' maps to `retainedBytes`.

### FONTS-TEXT-12: `CffFont.glyphIdForCodePoint` knows ASCII names, `cidNNN` and `uniXXXX` only, so a Differences name the program lacks and any accented code without a matching name resolve to glyph 0 and draw nothing
- Repo: KitePDF
- Area: kitepdf-core/font (CffFont, PdfFont)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:166-193`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:281-285`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/GlyphList.kt:772`
- What happens: for a simple CFF font `simpleGid` tries the glyph name, then `cff.glyphIdForCodePoint(unicode)`. That fallback tries a hand-written table for 0x20 to 0x7E, `"cid$codePoint"` (a CID named after a Unicode value, which is not a thing) and `uniXXXX`. `GlyphList` offers no reverse map (name for a code point). So a PDF whose `/Differences` says `/uni00E9` while the program names the glyph `eacute`, or says `/Euro` while the program has `euro`, lands on gid 0 with `coerceAtLeast(0)` and the glyph is blank, silently. MuPDF falls back from the name to the AGL code point and then asks FreeType's synthesised Unicode charmap, which knows `eacute`.
- What should happen: a reverse AGL (code point to the names that map to it) tried before giving up, plus the `uXXXX[XX]` form and the `gNN`/`glyphNN`/`GNN` index names (`PdfFont.parseUniName` handles `uni`/`u` only; `g123` falls to the code).
- How to reproduce or evidence: Type1C font with glyph `eacute`, `/Encoding << /Differences [233 /uni00E9] >>`, text `(\351)`: blank.
- Cross-effects: the standard 14 bundled programs are CFF and go through the same path when a PDF applies `/Differences` with `uniXXXX` names.
- Fix sketch: build a lazy reverse map in `GlyphList` (first name wins, plus the `uniXXXX` spelling), and in `glyphIdForCodePoint` try every name of the code point before `-1`.

### FONTS-TEXT-13: A simple font with `/Widths` but no `/FirstChar` loses its widths and every glyph advances 500
- Repo: KitePDF
- Area: kitepdf-core/font (PdfFont)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:629`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:642`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:658-660`
- What happens: `firstChar` defaults to -1 when absent, so `firstChar in 0..255 && arr != null` is false, the `/Widths` array is skipped, `hasWidthsArray` is false and the fallback becomes 500 for every code. MuPDF reads `/FirstChar` as 0 when missing and applies the widths from code 0; a producer that forgets `/FirstChar` (the spec requires it, 9.6.2.1, Table 111, but buggy writers exist) reflows correctly there and spreads out here.
- What should happen: a missing `/FirstChar` reads as 0, as MuPDF does; the width fallback for a font that has a `/Widths` array stays the descriptor's `/MissingWidth`.
- How to reproduce or evidence: `<< /Type /Font /Subtype /TrueType /BaseFont /Foo /LastChar 90 /Widths [ ... 91 numbers ... ] >>` with no `/FirstChar`.
- Cross-effects: none seen.
- Fix sketch: `val firstChar = dict.getInt("FirstChar")?.toInt() ?: 0` when `arr != null`.

### FONTS-TEXT-14: A Type 3 glyph's advance uses only `FontMatrix.a`, so a rotated or skewed Type 3 font advances the pen by zero
- Repo: KitePDF
- Area: kitepdf-pdf/render (PageRenderer, Type3)
- Kind: bug
- Severity: low
- Confidence: verified (read the path; no rotated Type 3 fixture exists)
- Where: `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2637`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2662`
- What happens: `adv = data.widthFor(code) * data.fontMatrix.a * t.fontSize` and the text-reading path's `advanceWidth = widthFor(code) * fontMatrix.a * 1000`. ISO 32000-1, 9.6.5 and 9.2.4: a Type 3 width is a glyph-space displacement `(wx, 0)` that `/FontMatrix` maps into text space, so its x component is `wx * a` and its y component `wx * b`. A `/FontMatrix [0 0.001 -0.001 0 0 0]` (glyphs drawn rotated a quarter turn, which some dvips and OCR producers write) has `a = 0`, so every glyph advances zero and the run collapses onto one spot, while the glyph procedures themselves are placed through the full matrix (`glyphToUser.concat(data.fontMatrix)`), so the shapes are right and only the pen is wrong.
- What should happen: transform `(wx, 0)` by the matrix and advance by the resulting vector (MuPDF: `fz_transform_vector`).
- How to reproduce or evidence: a Type 3 font with the matrix above and two glyphs of width 1000: the second glyph draws over the first.
- Cross-effects: `reportType3Text` gives the text canvas the same zero advance, so structured text sees a run of zero width and search quads degenerate.
- Fix sketch: `val v = data.fontMatrix.transformVector(width, 0.0)`; advance by `v.x * fontSize` (and carry `v.y` into the pen when it is not zero).

### FONTS-TEXT-15: Type 2 charstrings never close a subpath on `rmoveto`, so stroked and clipped text (Tr 1, 2, 5, 6) shows an open edge on every contour but the last
- Repo: KitePDF
- Area: kitepdf-core/font (CharstringInterpreter)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:278-301`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:574`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KitePath.kt:68-72`
- What happens: the Type 2 charstring format has no `closepath`: "a subpath is closed by the next moveto or by endchar" (Type 2 charstring spec, 4.1). `rmoveto`, `hmoveto` and `vmoveto` call `pathBuilder.moveTo` without `close()`, and `KitePath.Builder.moveTo` does not close either; only `endchar` closes, so an `o` or `B` has its inner contour closed and its outer one open. Filling is unaffected (rasterisers close implicitly), but `PageRenderer.strokeTextGlyphs` strokes the path as given, so mode 1 and 2 text misses the closing segment of each contour, and the clip of modes 4 to 7 depends on the canvas. The TrueType path closes every contour (`GlyphOutline.renderContour`, `b.close()`), so the two formats draw stroked text differently.
- What should happen: a moveto closes the open subpath, as in CFF (Type 2 spec, 4.1) and FreeType's `cff_builder_close_contour`.
- How to reproduce or evidence: `BT 1 Tr /F1 48 Tf (O) Tj ET` with a Type1C font: the outer ring has a gap at its start point.
- Cross-effects: `glyphShapes` for pattern fills and `accumulateTextClip` use the same outline; even-odd or nonzero fills of an open contour are implementation-defined on some canvases.
- Fix sketch: in the three moveto handlers, `if (!pathBuilder.isEmpty()) pathBuilder.close()` before `moveTo` (guard against a close after a bare moveto).

### FONTS-TEXT-16: `OpenTypeKern` skips Extension lookups and lookup flags, so GPOS kerning stored under type 9 is silently absent
- Repo: KitePDF
- Area: kitepdf-core/font (OpenTypeKern)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/OpenTypeKern.kt:97-104` (orchestrator corrected the line numbers: `r.u16() // lookupFlag` and `if (type != 2) continue`)
- What happens: `parseGposKern` reads each `kern` lookup's type and `if (type != 2) continue`. Large fonts (Noto, Source Han, any font whose GPOS exceeds 64 KB of offsets) wrap their PairPos subtables in Extension lookups (type 9, OpenType 1.9, "Lookup Type 9: Extension Positioning"), which `OpenTypeMarks` does unwrap (`OpenTypeMarks.kt:79-84`) but `OpenTypeKern` does not. Such a font gets no kerning in EPUB layout, with no warning; the legacy `kern` table, when present, hides the loss. Lookup flags (ignore marks) are also not read, so a mark between two letters breaks the pair.
- What should happen: unwrap type 9 to its `extensionLookupType` and offset, as in `OpenTypeMarks`; skip glyphs the flag ignores, as `OpenTypeGsub.skipped` does.
- How to reproduce or evidence: `OpenTypeKern.from(null, gpos)` on a GPOS whose `kern` feature points at a type 9 lookup wrapping a type 2 subtable: `between(left, right)` is 0 for every pair.
- Cross-effects: `GsubOracleTest` compares glyph ids, not positions, so the oracle cannot see it.
- Fix sketch: share the extension unwrap with `OpenTypeMarks` and pass the flag to a skip test.

### FONTS-TEXT-17: `TrueTypeFont.parse` rejects a font whose `loca` or `hmtx` is shorter than `maxp.numGlyphs` implies, dropping the whole program where FreeType truncates
- Repo: KitePDF
- Area: kitepdf-core/font (TrueTypeFont)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:557-586`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TtfReader.kt:33-35`
- What happens: `parseLoca` reads `numGlyphs + 1` entries and `parseHmtx` reads `numHMetrics` records with `reader.u16()`, which throws `PdfFormatException("TTF EOF")` at the end of the data. A subset whose `maxp.numGlyphs` says 65535 while `loca` holds 300 entries (a common subsetter bug, the "maxp numGlyphs of 65535 with a tiny file" shape) fails `TrueTypeFont.parse` entirely, `loadEmbeddedTtf` returns null and the text draws in a host face with the wrong shapes. FreeType clamps `numGlyphs` to what `loca` can hold (`tt_face_load_loca`: "loca table too short, truncating numGlyphs") and renders the glyphs the file has.
- What should happen: read as many `loca`/`hmtx` entries as the tables hold, clamp `numGlyphs` to that, and keep the font.
- How to reproduce or evidence: patch `maxp.numGlyphs` of any embedded subset to 0xFFFF; the page falls back to a system font.
- Cross-effects: the same `TtfReader` throw ends `parseHead`/`parseHhea` for truncated tables, which is right for those; only the glyph-count-sized tables need the clamp.
- Fix sketch: in `parseLoca`, `n = min(numGlyphs + 1, table.length / entrySize)`; in `parseHmtx`, `min(numHMetrics, table.length / 4)`; expose the clamped count as `numGlyphs`.

### FONTS-TEXT-18: `CMap` looks ranges up linearly, so a large embedded CMap or ToUnicode costs O(ranges) per glyph
- Repo: KitePDF
- Area: kitepdf-core/font (CMap)
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CMap.kt:187-209`
- What happens: `lookupCid` and `lookup` scan `cidRanges` and `bfRanges` with a for loop for every code that `cidChars`/`bfChars` miss. A ToUnicode written as one `bfrange` per character (common for CJK subsets, thousands of entries) or an embedded copy of a predefined CJK CMap (UniGB-UCS2-H has about 15 000 ranges) makes every glyph of every run walk the list, in `layoutBytes`, `forEachGlyphAdvance` and `textOf`. `TableCMapReader` already binary-searches sorted arrays for the bundled CMaps; the embedded path does not.
- What should happen: sort the ranges at parse time and binary-search, as `TableCMapReader.cidFor` and `CidWidthTable.widthOf` do.
- How to reproduce or evidence: profile `extractText()` on a page whose font's ToUnicode has 10 000 `bfrange` lines: `CMap.lookup` dominates.
- Cross-effects: none seen.
- Fix sketch: store `lo/hi/base` in parallel `IntArray`s sorted by `lo`, keep the first-match semantics by stable sort, and binary-search.

### FONTS-TEXT-19: Hyphenation patterns ship in `kitepdf-core` for every consumer, 3.1 MB of class constants that a PDF-only app never reads
- Repo: KitePDF
- Area: kitepdf-core/text/hyphen
- Kind: design
- Severity: low
- Confidence: verified (measured on the built jar)
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen/HyphenPatternSets.kt:24-110`, `kitepdf-core/module.yaml:1-3`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/Hyphenator.kt:160-172`
- What happens: the 70 `Hyph*.kt` objects hold their patterns as `const val` strings. In the JVM jar they are 3 098 036 bytes of 13 482 334 uncompressed (74 class files, 23 % of the module; the compressed jar is 3.3 MB in all). `HyphenPatternSets.byId` references every object in one `when`, and `Hyphenator.forLanguage` is public API, so no linker can drop them: a Kotlin/Native framework or a Wasm module that uses only `kitepdf-pdf` carries them too, and there a string literal is UTF-16, about 6 MB of binary for data only EPUB `BoxLayout` asks for. Class initialisation is cheap (the constants are in the constant pool and `patterns` concatenates on demand), so the cost is footprint, not time. The other big tables are smaller: `Standard14FontData` 663 KB, `PredefinedCMapData` 222 KB, `CidUnicodeData` 151 KB.
- What should happen: a PDF-only consumer should not pay for hyphenation data. Either the pattern sets move to a module that `kitepdf-epub` depends on, or they become a resource the app opts into, with `Hyphenator.forLanguage` returning null without it.
- How to reproduce or evidence: `unzip -l build/tasks/_kitepdf-core_jarJvm/kitepdf-core-jvm.jar | grep text/hyphen` (74 entries, 3 098 036 bytes).
- Cross-effects: the module description in `module.yaml` already lists "hyphenation data" as part of core, so this is a conscious placement; the README does not state the size.
- Fix sketch: a `kitepdf-hyphenation` module (or `kitepdf-epub` itself) registering sets into `HyphenPatternSets` at start-up, so the `when` table and the data leave core.

### FONTS-TEXT-20: Search compares code units only: no normalisation, no ligature expansion, no soft hyphen, and a real hyphen at a line end is dropped
- Repo: KitePDF
- Area: kitepdf-core (KiteStructuredText)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/KiteStructuredText.kt:41-77`
- What happens: `search` builds the block text and runs `regionMatches(..., ignoreCase)` per position. A page whose text has the `fi` ligature (U+FB01, which a ToUnicode often emits) does not match the query "fine"; "café" typed as `e` + U+0301 does not match a page with U+00E9 and the reverse; a soft hyphen U+00AD left in the text by a producer breaks a word; and the line join treats any line that ends in `-` as a hyphenation, so "well-\nknown" becomes "wellknown" and a search for "well-known" misses it, as does a line ending in a minus sign. `TextSearch.kt:20-23` documents the case caveat but not these.
- What should happen: fold both sides with NFKC-style compatibility decomposition for the ligature and presentation forms, drop U+00AD, and keep a hyphen that is not followed by a lowercase continuation (or try both joins), as MuPDF's `fz_search_stext_page` and pdf.js's `normalize` do.
- How to reproduce or evidence: `KiteStructuredText` with one line "efﬁcient"; `search("efficient")` returns no hit.
- Cross-effects: EPUB search shares this walker, so the layout's own soft hyphens (`KiteLineEnd.HYPHEN`) are handled by `copyText` but not by `search`.
- Fix sketch: a small compatibility-fold table (FB00 to FB06, 2010 to 2015 to `-`, strip 00AD, NFC of Latin accents) applied to both the needle and the block text with an index map back to chars.

### FONTS-TEXT-21: A composite glyph's `SCALED_COMPONENT_OFFSET` flag is ignored, and the CFF predefined Expert charsets read as ISOAdobe
- Repo: KitePDF
- Area: kitepdf-core/font (TrueTypeFont, CffFont)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:348-371`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:270-279`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:605`
- What happens: the composite offset `(arg1, arg2)` is always added after the 2x2 transform (the Microsoft `UNSCALED_COMPONENT_OFFSET` reading). A glyph setting `SCALED_COMPONENT_OFFSET` (0x0800, OpenType `glyf` spec, "Composite Glyph Flags") wants the offset transformed by the component matrix first; Apple fonts set it, and the accent lands in the wrong place when the component is scaled. Separately, `predefinedCharset(which)` returns SIDs 1..228 for charset ids 0, 1 and 2 alike; ids 1 and 2 are the Expert and ExpertSubset charsets (CFF spec, Appendix C), whose glyph order is different, so an expert font without a custom charset gets wrong glyph names and wrong `glyphIdForName` hits.
- What should happen: honour 0x0800 by mapping `(dx, dy)` through `(sx, sy01, sy10, sy)`; carry the two expert tables (165 and 87 SIDs).
- How to reproduce or evidence: a composite with `WE_HAVE_A_SCALE 0.5` and `SCALED_COMPONENT_OFFSET` set, offset (100, 0): the component sits at x = 100 here, 50 in FreeType.
- Cross-effects: none seen.
- Fix sketch: branch on `flags and 0x0800`; add the Appendix C tables.

### FONTS-TEXT-22: No test reaches the charstring interpreters' hard parts, and no fixture carries a seac, a `MissingWidth`, a (1,0)-only nonsymbolic cmap or a codespace-less ToUnicode
- Repo: KitePDF
- Area: kitepdf-core/test, kitepdf-pdf/test, corpus
- Kind: test-gap
- Severity: medium
- Confidence: verified
- Where: `kitepdf-core/test/io/github/yuroyami/kitepdf/core/font/` (CffCidCharsetTest, CffEncodingTest, CffStandardStringsTest, FontFormatTest, FontMatrixTest), `kitepdf-core/test/io/github/yuroyami/kitepdf/core/Type1Test.kt`, `kitepdf-core/test/io/github/yuroyami/kitepdf/core/FontWidthResolutionTest.kt`
- What happens: `grep -rn "CharstringInterpreter\|seac\|hintmask\|FDSelect\|MissingWidth\|usecmap" kitepdf-core/test kitepdf-pdf/test` finds nothing but one comment in `PredefinedCMapTest`. The Type 2 interpreter (hintmask before the first stem, implicit vstems, the width on the first stack-clearing operator, flex1's dominant axis, seac, subr bias at 1240/33900) is exercised only through whole-font fixtures and the differential sweep; the corpus holds 183 Type1C programs, none with seac (`corpus_probe.py`), and every ToUnicode in it declares a matching codespace. So findings 01, 02, 04 and 06 are all green today. CLAUDE.md's #303 and #596 record this exact class ("a synthetic font tests none of what it leaves empty").
- What should happen: unit tests on `CharstringInterpreter` and `Type1CharstringInterpreter` with hand-assembled charstrings (fontTools `cffLib`/`psCharStrings` can emit them), a fixture per spec rule, and a `fontTools`-compared glyph for a seac font and a hintmask-first font.
- How to reproduce or evidence: the two probe scripts in the scratchpad show the corpus shape; the grep shows the test shape.
- Cross-effects: `GsubOracleTest` and the differential sweep skip without `mupdf-master`, so CI has no font oracle on a clean checkout.
- Fix sketch: add `CharstringInterpreterTest` (seac, hintmask-first, flex1, width forms, bias boundaries), `Type1CharstringInterpreterTest` (flex, hint replacement, seac), and regression fixtures for 02, 04 and 06.

### Capabilities and opportunities (not defects)

- Right-to-left text extraction: `PdfTextLine.text` and `KiteTextAdapter` order spans by `bounds.left`, so Arabic and Hebrew runs come out in visual order; `Bidi.reorderVisually` exists but no inverse is applied to structured text. Users copying Arabic from a PDF get reversed words. Reach: every RTL document; cost: run `Bidi.resolveLevels` per line and invert the L2 permutation (one or two days).
- cmap format 14 (Unicode variation sequences) is deferred (`TtfCMap.kt:89-92`); CJK fonts with IVS and emoji with VS16 fall back to the base glyph. Reach: EPUB CJK; cost: a day.
- `OpenTypeMarks` applies every mark lookup regardless of script, language, feature or lookup flag; a font whose `mark` feature is language-specific or whose mark lookups use `MarkAttachmentType` filtering positions marks from the wrong subtable. Reach: Vietnamese and Arabic fonts with layered marks; cost: reuse `OpenTypeGsub`'s feature plan and `skipped`.
- CFF charstring widths are parsed (`advanceWidth`) but never used; a Type1C simple font with no `/Widths` falls to 500 (`PdfFont.simpleWidth` consults only `hmtx`). MuPDF uses the program's advance. Reach: rare, non-conforming files; cost: small.
- CFF2 cannot be subset (`EmbeddedFont.kt:72-74` throws); the writer could embed the full program as `/OpenType` instead of refusing.
- `Hyphenator.forLanguage` falls back to `en-US` when the book has no language (`BoxLayout.kt:96`); a `lang`-less German book is hyphenated with English patterns. A language detector over the first chapter, or no hyphenation without a language, would be safer.
- Search ignores `KiteLineEnd` metadata that `copyText` already honours; using it would make EPUB search immune to the layout's own hyphenation.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane PDF-WRITE: the PDF writer, editor, redaction, encryption on write, signing

Method: read every file of `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/`, `crypto/` and `crypto/sig/`, `PdfSignature.kt`, the editor entry points in `PdfDocument.kt`, and the writer tests. Then compiled a scratch Java program against the built `kitepdf-pdf-jvm.jar` (no repository file touched) and ran the editor on hand-built fixtures; the outputs were read back with pypdf 6.17, pdf.js 4.10.38 and PDFium (pypdfium2 5.14). `mutool` is not installed here, so MuPDF claims come from reading, not from running it. Probe scripts and outputs are under `/tmp/claude-0/-home-user/8fa91e2b-1e52-50dc-8511-48f93ec9d76e/scratchpad/PDF-WRITE/` (`Probe.java`, `make_bases.py`, `inspect_outputs.py`, `out_*.pdf`).

### PDF-WRITE-01: Redaction keeps the `/ActualText` of a marked-content sequence whose text it removed, and KitePDF's own extractor reads it back
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

### PDF-WRITE-05: Every standard-14 font dictionary the writer emits has no `/Encoding`, while show strings are Latin-1 bytes, so "café" renders "cafØ" in every reader including KitePDF
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfBuilder, ContentStreamBuilder, PdfEditor.stampPage, setTextFieldValue, setChoiceValue, PdfText)
- Kind: bug
- Severity: high
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:184-196; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:263-271, 302-310, 423-425; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfText.kt:41-42 (orchestrator corrected the line numbers); kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/StandardFontMetrics.kt:11-23 (orchestrator corrected the line numbers); kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:504-512
- What happens: `PdfText.encodeContentString` writes each character below U+0100 as its code point byte, i.e. ISO Latin-1, and `StandardFont.stringWidth` measures through WinAnsi. But the font dictionaries written by the builder, by `stampPage` and by the two field setters are `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>` with no `/Encoding`, and ISO 32000-1, 9.6.6.2 says such a nonsymbolic font uses its built-in encoding, which for the standard 14 is StandardEncoding (Annex D). In StandardEncoding 0xE9 is `Oslash`, 0xE8 is `Lslash`, 0xA9 is `quotesingle`, and most of 0xA0..0xFF is unassigned. KitePDF's own reader applies the same rule (`PdfFont.defaultEncodingFor` returns `standardEncoding`), so the library disagrees with itself: the writer measures WinAnsi, the reader draws StandardEncoding. The two other appearance generators (`FieldAppearance.synthesize` line 311 and `AnnotationAppearance.fontDict` line 250) do write `/Encoding /WinAnsiEncoding`, so this is an inconsistency inside the writer. The field case is worse because `/V` carries the right UTF-16BE text while the visible appearance shows the wrong glyphs.
- What should happen: Every Type1 standard-14 font dictionary the writer emits for text carries `/Encoding /WinAnsiEncoding` (Symbol and ZapfDingbats excepted), and the content-string encoder maps through WinAnsi (see PDF-WRITE-06).
- How to reproduce or evidence: `PdfBuilder().page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "café") }.build()`. pypdf, pdf.js 4.10 and PDFium all extract `cafØ`; KitePDF's `extractText` gives the same (printed `caf?` on an ASCII console). The same for `setTextFieldValue(field, "John" + U+2019 + "s café" + U+20AC)`: the appearance stream holds `<4A6F686E3F7320636166E9203F> Tj` under `/Helv` → `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`. The hand-built probe `probe_encoding.py` shows that adding `/Encoding /WinAnsiEncoding` makes pypdf read `café`, the euro sign and an en dash. No writer test writes a non-ASCII show string: `WriterTest`, `PdfBuilderTest`, `WriterRoundTripPropertyTest`, `WriterOracleTest` use ASCII only, and `FormFillTest:52-56` sets "Ådá", an em dash and "名前" but asserts only `/V`.
- Cross-effects: `RedactionEngine.missingFont` and the renderer's substitute also assume StandardEncoding, so a stamp's measured width and its drawn width differ for accented text. Rich text (`RichText`) draws through `synthesize`, which has WinAnsi, so a rich value and its plain value render differently.
- Fix sketch: Add `"Encoding" to PdfName("WinAnsiEncoding")` to the four font dictionaries (PdfBuilder.kt:188-194, PdfEditor.kt:263-271, 302-310, 423-425), and add one writer test that writes "café" and checks the glyph names of the recorded draw calls (or mutool text extraction).

### PDF-WRITE-06: The content-string encoder turns every WinAnsi-only character (the euro sign, the typographic quotes, the en and em dashes, the ellipsis, the bullet, the trademark sign) into `?`, and the width function measures them as 500 units
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfText.encodeContentString, StandardFont.stringWidth)
- Kind: bug
- Severity: medium
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfText.kt:41-42 (orchestrator corrected the line numbers); kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/StandardFontMetrics.kt:11-23 (orchestrator corrected the line numbers); kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/FieldText.kt:111; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/AnnotationAppearance.kt:105, 182
- What happens: `encodeContentString` keeps a character only when its code point is at or below 0xFF and writes `?` otherwise. WinAnsiEncoding (ISO 32000-1, Annex D.2) places the euro sign at 0x80, the typographic quotes at 0x91..0x94, the dashes at 0x96..0x97, the ellipsis at 0x85, the bullet at 0x95 and the trademark at 0x99, all of which have code points above 0xFF. Mobile keyboards insert U+2019 and U+2013 by default, so a form value such as "John" plus U+2019 plus "s" is drawn as "John?s", and a euro price as "? 10". The reverse problem exists for U+0080..U+009F, which pass through as bytes that WinAnsi maps to those symbols. `stringWidth` looks the character up in `winAnsiEncoding[code]`, finds nothing for code > 255 and charges the 500-unit fallback, so centring and wrapping of such text are off too.
- What should happen: Encode through the inverse of the WinAnsi table (Unicode → byte), fall back to `?` only for characters outside it, and measure the same way. Non-Latin text needs an embedded font (documented); typographic punctuation does not.
- How to reproduce or evidence: `out_field.pdf` from the probe: `/V` is the UTF-16BE text with U+2019, U+00E9 and U+20AC (correct) and the appearance content is `<4A6F686E3F7320636166E9203F> Tj` (the `?` at 0x3F twice). `out_cafe.pdf` extracts `cafØ ? John?s ? end` in pypdf, pdf.js and PDFium.
- Cross-effects: `AnnotationAppearance.freeText` and `stamp` go through the same encoder, so a FreeText annotation's synthesized appearance drops the same characters at render time.
- Fix sketch: Build a `HashMap<Int, Int>` from `Encodings.winAnsiEncoding` glyph names to bytes via the AGL once, use it in `encodeContentString` and `stringWidth`, and keep `?` for the rest.

### PDF-WRITE-07: `PdfSigner.prepareSignature` builds its page and catalog updates from the base document, so an edit staged earlier in the same editor is thrown away
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfSigner)
- Kind: bug
- Severity: medium
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfSigner.kt:87-90, 93-114; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:1214-1215 (`effectiveObject` exists for this)
- What happens: The signer reads `page.dictionary` and `doc.catalog` straight from the base document and stages them back with the widget added. If the caller stamped the page, filled a field, rotated the page or touched `/AcroForm` through the same `PdfEditor` first (the documented order is "edit, then sign"), the staged page or catalog object is overwritten by a copy of the original: the stamp's `/Contents` repoint and the merged `/Resources` are gone, `clearNeedAppearances` is undone, and a field value set on a merged widget survives only because it lives in another object.
- What should happen: Read the staged-or-base object (`editor.effectiveObject`) for the page, the catalog and the AcroForm, as every other editor operation does.
- How to reproduce or evidence: `stampPage(pages[0]) { text(CourierBold, 36, 150, 400, "STAMPTEXT") }` then `PdfSigner(doc, editor).prepareSignature("Sig1")` and `saveForSigning()`. The signed file extracts `cafØ ? John?s ? end` only; pypdf and pdf.js agree. `PdfSignerTest` signs fresh documents without a prior edit.
- Cross-effects: An editor that already staged a redaction still has `saveIncremental` refused, so that case fails loudly; the silent one is any non-redaction edit.
- Fix sketch: Give `PdfSigner` an internal accessor on `PdfEditor` for the effective page and catalog dictionaries and build from those.

### PDF-WRITE-08: `PdfSigner` replaces a direct `/AcroForm` dictionary with a new one, dropping the existing fields from `/Fields` together with `/DA`, `/DR` and `/NeedAppearances`
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfSigner)
- Kind: bug
- Severity: medium
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfSigner.kt:96-114; compare kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:567-569 which handles the direct case
- What happens: Only an `/AcroForm` that is an indirect reference is merged. When the catalog carries the form dictionary directly (legal, and the editor's own `clearNeedAppearances` and `detachRedactedFields` handle it), the `else` branch allocates `<< /Fields [sig] /SigFlags 3 >>` and points the catalog at it. Every existing field leaves `/Fields`, the form-wide `/DA`, `/DR`, `/Q`, `/XFA` and `/NeedAppearances` vanish. Acrobat and pypdf then list only the signature field; KitePDF's lenient `collectStrayWidgets` still finds the widgets on the page, which is why its own API hides the loss.
- What should happen: Merge into the direct dictionary the same way as into an indirect one, and keep every other entry.
- How to reproduce or evidence: `base_direct_acroform.pdf` has `/AcroForm << /Fields [4 0 R] /DA (...) /NeedAppearances true >>` in the catalog. After `prepareSignature("Sig1")` and `saveForSigning()`, pypdf reads `/AcroForm: {'/Fields': [7 0 R], '/SigFlags': 3}` and `get_fields()` is `['Sig1']`; before, the field "Existing" was listed.
- Cross-effects: `/NeedAppearances true` lost means a viewer stops regenerating the appearances of fields that have none.
- Fix sketch: Resolve `catalog["AcroForm"]` to a dictionary whether direct or indirect, copy its map, append the field and set `/SigFlags`, then either update the indirect object or write the merged dictionary back into the catalog.

### PDF-WRITE-09: Page operations flatten the page tree and bake only `/MediaBox`, `/Resources` and `/Rotate`, so a page that inherited `/CropBox` loses its crop
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor.applyPageOrder, graftablePageDict)
- Kind: bug
- Severity: medium
- Confidence: verified (ran it)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:656-670, 714-722; kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPage.kt:103-104
- What happens: `applyPageOrder` rebuilds one flat `/Pages` node, re-parents every page and copies down the inherited attributes it knows about. ISO 32000-1, Table 30 marks four entries inheritable: `/Resources`, `/MediaBox`, `/CropBox` and `/Rotate`. `/CropBox` is skipped in both places, so any `removePage`, `setPageOrder`, `insertPageAt`, `appendPage` or `mergeDocument` on a document whose `/Pages` root carries the crop (common for books and print PDFs, whose crop is the trimmed page) silently reverts every page to its MediaBox. `PdfPage.cropBox` reads the inherited value, so the editor has it at hand.
- What should happen: Bake `/CropBox` like the other three, from `bp.cropBox` / `page.cropBox`, when the leaf has none of its own.
- How to reproduce or evidence: `base_cropbox.pdf`: `/Pages` root with `/MediaBox [0 0 612 792] /CropBox [50 50 400 500]`, two leaf pages without boxes. `removePage(pages[1])` then `saveIncremental()`: `pages[0].cropBox` is `[50 50 400 500]` before and `[0 0 612 792]` after. No test in `PageOpsTest` or `CumulativeEditTest` mentions `/CropBox`.
- Cross-effects: The viewer crops to `cropBox`, so the visible page size changes after a page delete. `/Group`, `/Dur`, `/Trans`, `/AA` are not inheritable, so nothing else is lost.
- Fix sketch: Add `if ("CropBox" !in m) m["CropBox"] = rectToArray(bp.cropBox)` (only when it differs from the media box) at PdfEditor.kt:665 and the same in `graftablePageDict`.

### PDF-WRITE-10: `setInfo` changes `/Info` and leaves the XMP `/Metadata` stream and `/ModDate` untouched, so readers that prefer XMP keep showing the old title
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor.setInfo)
- Kind: bug
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:140-183; kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:144-152
- What happens: `setInfo` merges the new strings into the `/Info` dictionary and stages it. The catalog's `/Metadata` stream (XMP) is not read, not rewritten and not removed, and `/ModDate` is not set. ISO 32000-1, 14.3.3 asks that `/Info` and the XMP packet be kept in sync and that a writer which changes one updates the other; ISO 32000-2 deprecates `/Info` in favour of XMP. pdf.js (`PDFViewerApplication._initializeMetadata`) and Acrobat take `dc:title` from XMP when it is present, so a title set with `setInfo` on any document that carries XMP (every Office, LaTeX and Acrobat export) is not what Firefox or Acrobat displays. KitePDF's own `metadata.title` reads `/Info`, which is why `WriterTest.unicode_title_round_trips_via_utf16be` passes.
- What should happen: When the catalog has `/Metadata`, either rewrite the matching XMP properties (`dc:title`, `dc:creator`, `dc:description`, `pdf:Keywords`, `xmp:CreatorTool`, `pdf:Producer`, `xmp:ModifyDate`) or at least drop the stale packet and say so in the KDoc; set `/ModDate` (Table 317 makes it required when `/PieceInfo` is present).
- How to reproduce or evidence: Open any PDF with an XMP packet, `edit().setInfo(title = "New")`, `saveIncremental()`, open in Firefox: the tab still shows the old title. Code: nothing in `PdfEditor` references `Metadata`, `ModDate` or `xmp` (grep).
- Cross-effects: `saveRewritten` keeps the `/Metadata` stream because the catalog references it, so the stale packet survives the rewrite too. The README's "doc.xmp" reader then disagrees with `doc.info` after an edit.
- Fix sketch: Add a small XMP patcher (the EPUB module already has an XML parser) that replaces the known properties in the packet, or remove `/Metadata` from the catalog when `setInfo` changes a mirrored field, and stamp `/ModDate`.

### PDF-WRITE-11: Redaction leaves five more copies of the content that docs/editing.md does not list: structure-tree text, the page thumbnail, tiling-pattern cells, Type 3 glyph procedures and private data
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

### PDF-WRITE-12: `setButtonValue` treats the export value as the appearance-state name, so a radio group that uses `/Opt` (states named `/0`, `/1`) cannot be set by its export value
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor.setButtonValue, toggleAppearance)
- Kind: bug
- Severity: medium
- Confidence: likely (read most of it; the `/Opt` rule needs the spec text checked)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:355-396 (378-392), 455-489
- What happens: The KDoc says "Set a button field to a named export value". For a field with `/Opt [(Red) (Blue)]` (ISO 32000-1, Table 227, the way Acrobat writes radio groups whose export values are not valid name characters), the widgets' `/AP /N` states are the indices `/0`, `/1` and `/V` is the index as a name. `setButtonValue(field, "Red")` computes `states = {"0", "Off"}`, finds `"Red" !in states`, writes `/AS /Off` on every widget and `/V /Red`: nothing is selected and the value names no state. The only way through is to pass `widgets[i].onStateName` ("0"), which contradicts the KDoc and the `/Opt` reading at line 378 (which then builds a synthesized appearance keyed by the export string instead of the index when a widget has no `/AP`).
- What should happen: When `/Opt` is present, map the export value to its index, use the index name for `/V` and `/AS`, and name a synthesized state by that index (Table 227 and 12.7.4.2.3). Expose the export values on `PdfFormField` for buttons so callers can choose.
- How to reproduce or evidence: A radio field `<< /FT /Btn /Ff 32768 /Opt [(Red) (Blue)] /Kids [k0 k1] >>` with `k0 /AP << /N << /0 ... /Off ... >> >>`, `k1 /AP << /N << /1 ... /Off ... >> >>`; call `setButtonValue(field, "Red")`, reopen: `field.value == "Red"`, both widgets `/AS /Off`. `FormButtonChoiceTest` has no `/Opt` button fixture.
- Cross-effects: `FieldAppearance.synthesize` (render side) decides "on" by `state == onState`, so a `/V /Red` against states `/0` draws unticked there too.
- Fix sketch: In `setButtonValue`, when `optNames != null`, translate `exportValue` to `optNames.indexOf(exportValue).toString()` for `/V` and `/AS`, and key `toggleAppearance` by the index.

### PDF-WRITE-13: The text-field appearance ignores the `/DA` font and the form's `/DR`, draws password fields in clear, drops the widget's border and background, and draws a list box as one line
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

### PDF-WRITE-15: Neither save mode changes the second `/ID` string, and the incremental trailer's `/Size` can be smaller than the base's
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfEditor.writeTrailer, writeWithClassicXref, writeWithObjectStreams, DocumentId)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:1047-1053, 1058, 1143, 1204; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:1058, :1143, :1204 (orchestrator corrected the citation)-386
- What happens: ISO 32000-1, 14.4: the first `/ID` string is permanent and the second "shall be changed each time the file is updated". `writeTrailer` copies `base.trailer["ID"]` unchanged, `saveRewritten` does the same, and `DocumentId.generate` makes both strings identical for a new file. Tools that use the pair to detect revisions (Acrobat's "compare", some DMS systems) see two different files with the same identity. Separately, `/Size` is `max(highest xref key, highest staged key) + 1`, which can be lower than the base trailer's `/Size` when that was larger than the highest listed object (common); 7.5.5 says `/Size` is one greater than the highest object number used in the file, and readers that honour "objects beyond Size are missing" take the newest trailer's value.
- What should happen: Keep the first string, replace the second with a fresh digest (or random bytes from the editor's `Random` when it has one), and take `/Size` as `max(base Size, ...)`.
- How to reproduce or evidence: Code lines above; `WriterTest.two_successive_incremental_updates_chain_via_prev` does not look at `/ID`.
- Cross-effects: A signed file must keep the first string; the fix must only touch the second. For an AES-128 (V4) document the file key depends on the first string only (Algorithm 2), so changing the second is safe.
- Fix sketch: `dict["ID"] = PdfArray(listOf(first, PdfString(Md5.hash(staged bytes or base.rawBytes + size))))` in both writers; `PdfInt(maxOf(base.trailer.getInt("Size") ?: 0, maxNum + 1))`.

### PDF-WRITE-16: `PdfImage.jpeg` and `PdfImage.jpx` trust the caller for the colour space and the size, so a CMYK or YCCK JPEG is declared `DeviceRGB` and the test for the path uses a fake JPEG
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfImage)
- Kind: design
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfImage.kt:84-98; kitepdf-pdf/test/io/github/yuroyami/kitepdf/writer/WriterImageClipTest.kt:109-121
- What happens: The JPEG path stores the bytes under `/DCTDecode` with `/ColorSpace` chosen by a `grayscale` boolean and `/Width`/`/Height` from the arguments. A 4-component JPEG (Adobe APP14 transform 2, the usual CMYK export) is written as `DeviceRGB`, and an Adobe-inverted CMYK needs `/Decode [1 0 1 0 1 0 1 0]`, which the API cannot express; a size that does not match the SOF makes MuPDF warn and PDFium stretch. The SOF0/SOF2 segment and the APP14 marker carry all of this and are cheap to read. `WriterImageClipTest.jpeg_passthrough_preserves_encoded_bytes` embeds `FF D8 01 02 03 FF D9`, so the only test of the path cannot see a mismatch. `jpx` always writes `DeviceRGB`, although for JPX the entry is optional and, when present, overrides the codestream's colour space (8.9.5), so a greyscale JP2 is mis-declared.
- What should happen: Read the SOF (components, dimensions) and APP14 (transform) from the bytes, derive `/ColorSpace`, `/Decode` for inverted CMYK, and `/Width`/`/Height`, and reject a mismatch; omit `/ColorSpace` for JPX unless the caller asks for an override.
- How to reproduce or evidence: `PdfImage.jpeg(cmykBytes, w, h)` declares `/ColorSpace /DeviceRGB` over a 4-component codestream; MuPDF and PDFium decode by the JPEG's own component count and then find the declared colour space disagrees (MuPDF warns and falls back, PDFium rejects the image). The kitepdf-pdf JVM tests already have `CmykJpegTest` on the read side to borrow a fixture from.
- Cross-effects: `drawImage` has no `/Interpolate` and no `/Decode` either, which the brief lists as missing capabilities.
- Fix sketch: Add a 40-line SOF/APP14 scanner to `PdfImage.jpeg` (ImageKodec may already expose one), make `width`/`height`/`grayscale` optional and validated.

### PDF-WRITE-17: The writer's only oracles for encrypted output, object-stream output and non-ASCII field values are KitePDF itself
- Repo: KitePDF
- Area: kitepdf-pdf/test, kitepdf-native-renderer/test@jvm (WriterOracleTest, MutoolAcceptanceTest)
- Kind: test-gap
- Severity: medium
- Confidence: verified (read the tests)
- Where: kitepdf-pdf/test/io/github/yuroyami/kitepdf/EncryptedWriteTest.kt:36-42, 95-114; kitepdf-pdf/test/io/github/yuroyami/kitepdf/ObjectStreamWriteTest.kt:22-33; kitepdf-pdf/test/io/github/yuroyami/kitepdf/FormFillTest.kt:52-56; kitepdf-pdf/test/io/github/yuroyami/kitepdf/CryptoTest.kt (no SHA-384/512 vector); kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/WriterOracleTest.kt (no encrypted or object-stream case); kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/difftest/MutoolAcceptanceTest.kt:57-78 (object streams only through the repair path)
- What happens: `EncryptedWriteTest` and `EncryptionIntegrationTest` build AES-256 R6 files with `PdfBuilder` and open them with `PdfDocument`; `hash2B` (Algorithm 2.B) and `Sha512.hash384` are shared by both sides and have no published test vector, so a wrong round count or a wrong SHA-384 would pass every test while Acrobat, MuPDF and pdf.js refuse the password. `ObjectStreamWriteTest` reopens `saveRewritten(useObjectStreams = true)` output with KitePDF only; the mutool check of that shape exists only for a repaired base. `FormFillTest` sets "Ådá", an em dash and "名前" and asserts `/V` only, which is how PDF-WRITE-05 and -06 stayed invisible. No mutool check runs on an incremental save over a base that uses cross-reference streams. I ran the R6 output through pypdf, pdf.js and PDFium with both passwords (all open, text and title decrypt), so today the R6 writer is interoperable; the gap is that nothing in CI would notice when it stops being.
- What should happen: One oracle run per writer feature: `mutool draw -p <pw>` (or pdf.js in the existing `scripts/web-tests`) on an encrypted build and on an encrypted incremental edit; `mutool show ... grep` on an object-stream rewrite; a non-ASCII show string checked by glyph name on a recording canvas; SHA-384/512 vectors from FIPS 180-4 in `CryptoTest`; an incremental edit of a PDF 1.5 xref-stream base through `MutoolAcceptance`.
- How to reproduce or evidence: grep: `Sha512` appears in no test file; `WriterOracleTest` has no `encrypt`; `FormFillTest:56` asserts only `formField("FullName")?.value`.
- Cross-effects: The same KitePDF-only loop hides PDF-WRITE-05 for the builder and the stamp.
- Fix sketch: Add the five checks above; the `MutoolAcceptance` helper and the Node runner already exist.

### PDF-WRITE-18: `PdfObjectWriter.writeName` keeps only the low byte of each character, so a name built from a Kotlin string with a character above U+00FF is silently changed
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfObjectWriter)
- Kind: api
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfObjectWriter.kt:116-127; kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/Lexer.kt:162-181
- What happens: The lexer decodes a name byte by byte into chars 0..255, so round-tripping parsed names is lossless. `PdfObjectWriter` and `PdfName` are public, and a caller who writes `PdfName("Größe")` or a name from user text with "€" gets `ch.code and 0xFF`: U+20AC becomes 0xAC (`#AC`). ISO 32000-1, 7.3.5 says name bytes that stand for text are UTF-8, so the right output for a non-Latin1 char is its UTF-8 bytes escaped with `#`.
- What should happen: Encode chars above 0xFF as UTF-8 `#xx` sequences (and document that parsed names are byte strings), or reject them.
- How to reproduce or evidence: `PdfObjectWriter.toBytes(PdfName("€"))` gives `/#AC`.
- Cross-effects: `EmbeddedFont.sanitizeName` already strips to ASCII, so font names are safe; resource names from the DSL are ASCII.
- Fix sketch: For `ch.code > 0xFF`, append the UTF-8 encoding of the char with every byte escaped.

### PDF-WRITE-19: `PdfBuilder.serialize` copies the whole output once more to hash it for `/ID`
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfBuilder, DocumentId)
- Kind: performance
- Severity: info
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:381; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:381 (orchestrator corrected the citation)
- What happens: `DocumentId.generate(out.toByteArray())` allocates a full copy of the document (images included) just to feed MD5, then `out.toByteArray()` copies it again on return: peak memory is three times the file for a builder that is documented as careful about image memory (PdfImage.kt:20-26).
- What should happen: Hash incrementally (`Md5` over the builder's internal buffer, or over the object bodies as they are written) and return one copy.
- How to reproduce or evidence: A 60 MB scanned-page build allocates 180 MB at the end.
- Cross-effects: none seen
- Fix sketch: Expose a `ByteArrayBuilder.forEachChunk` or hash the per-object byte arrays as they go.

### PDF-WRITE-20: R6 passwords skip SASLprep on both sides and the public KDoc does not say so; the new-file header says PDF 1.7 while using a PDF 2.0 handler
- Repo: KitePDF
- Area: kitepdf-pdf/writer (PdfBuilder.encrypt), kitepdf-pdf/crypto (Encryptor.createV5, StandardSecurityHandler.deriveV5Key)
- Kind: docs
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:51-76, 349; kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/Encryptor.kt:99-100; kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/StandardSecurityHandler.kt:249-252
- What happens: ISO 32000-2, 7.6.4.3.3 Algorithm 2.A step (a) says the password is SASLprep'd (RFC 4013) before UTF-8 encoding and truncation to 127 bytes. Neither side does it; the reader notes it in a comment and the `open(String)` KDoc; `PdfBuilder.encrypt` does not. A password typed as a decomposed "é" (e + U+0301) or with a non-ASCII space encrypts a file that Acrobat (which normalises) opens with the composed form and KitePDF does not, and the reverse. The truncation at 127 bytes can also cut a multi-byte sequence. Separately, the builder writes `%PDF-1.7` with `/V 5 /R 6`, which ISO 32000-1 readers do not define (Acrobat 9's Extension Level 3 and PDF 2.0 do); a `/Version /2.0` in the catalog or a `%PDF-2.0` header would say what the file is.
- What should happen: Apply NFKC plus the RFC 4013 mapping tables (a small table for the mapped-to-nothing and mapped-to-space code points; `kotlin.text` has no normaliser, so an `expect` on the JVM/Apple and a table elsewhere), or state the deviation in `encrypt`'s KDoc; write the header that matches the handler.
- How to reproduce or evidence: Code lines above; EncryptionIntegrationTest:48-66 uses a precomposed "é" only.
- Cross-effects: `PdfDocument.open(String)` tries Latin-1 as a fallback, which the builder never writes, so the asymmetry is reader-only.
- Fix sketch: Document now; normalise later behind a shared `PasswordPrep` used by both `createV5` and `deriveV5Key`.

### Capabilities and opportunities (not defects)
- Form-field appearance: look up the `/DA` font in `/DR` and embed it when it is a Type0 font, so non-Latin values draw (need: every CJK or Arabic form; reach: all `setTextFieldValue` users; cost: a day with the existing `PdfFont` loader). List-box appearances with highlighted rows and multi-select `/I` (12.7.4.4). Comb fields and `/MaxLen`. Password masking.
- `PdfImage`: `/Interpolate`, `/Decode`, CMYK and 16-bit samples, an indexed palette, and a JPX path that omits `/ColorSpace` (half a day each).
- Editor: write `/EmbeddedFiles` (name tree plus `/AF`), `/Outlines`, `/PageLabels` and XMP; create annotations (#638 covers the last). Each is a small object graph the writer can already serialize.
- Signing: a `/M`, `/Reason`, `/Location`, `/Name` on the `/Sig` dict and a visible appearance for the widget; a DocMDP transform for certification; a second-signature path that tolerates an earlier unfilled placeholder; PAdES-T (`signature-time-stamp` unsigned attribute) and LTV (`/DSS` writing) on the write side, and verification of the signature timestamp token on the read side (the validator checks document timestamps only).
- Encryption: AES-128 (V4) on the create path for readers older than Acrobat 9, and an `isOwner` on the security handler so permissions can mean something.
- Redaction: an option to crop or blank the covered pixels of an image instead of dropping the whole XObject (MuPDF's `PDF_REDACT_IMAGE_PIXELS`), and recursion into tiling patterns.
- `saveRewritten` could keep encryption (re-encrypt with the same key) instead of writing a plaintext file; the pieces (`Encryptor`, `Decryptor`) exist.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane EPUB-LAYOUT findings

Lane: the EPUB engine in `kitepdf-epub`, without the script and DOM layer. All paths are relative to `/home/user/KitePDF`.

### EPUB-LAYOUT-01: A unitless `line-height` is inherited as a length, so a heading under `body { line-height: 1.5 }` gets lines shorter than its own glyphs
- Repo: KitePDF
- Area: kitepdf-epub/css (StyleResolver) and BoxLayout
- Kind: bug
- Severity: high
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:942`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:1176`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1844`
- What happens: `resolveLineHeight` turns `line-height: 1.5` into points at the element that declares it (`b.lineHeightPt = 1.5 * b.fontSizePt`, line 942). The `Builder` then seeds every child with that point value (`var lineHeightPt = parent.lineHeightPt`, line 1176), so a child with a larger `font-size` keeps the parent's line height in points. `layoutInline` reads only the block's `lineHeightPt` (line 1844) while the ascent is `maxFs * 0.8` (line 1845). With the UA sheet's `h1 { font-size: 2em }` under a 12 pt body with `line-height: 1.5`, an `h1` line box is 18 pt tall while its glyphs reach 19.2 pt above the baseline and 4.8 pt below it: consecutive heading lines overlap by about 6 pt, and a two-line heading paints into the paragraph after it. `body { line-height: 1.5 }` or `1.4` is in the stylesheet of most commercial EPUBs, and every heading is bigger than the body, so this is visible in ordinary books.
- What should happen: CSS 2.1, 10.8.1: for a `<number>` value "the computed value is the specified number; the used value is this number multiplied by the element's own font size", and it is the number that inherits. A percentage or a length computes to a length and inherits as such, which is what the code does for every value, so only the unitless case is wrong.
- How to reproduce or evidence: a chapter with `<style>body{line-height:1.5}</style><h1>A heading long enough to wrap onto a second line of text</h1>`, laid out at 12 pt and 200 pt wide: `h1`'s `TextBlockBox.lines[0].height` is 18.0 and `lines[0].ascent` is 19.2; browsers give a 36 pt line. The unitless case is the one `resolveLineHeight` multiplies at line 942.
- Cross-effects: `EpubSettings.lineHeightScale` multiplies the wrong value too. The EPUB sweep's page counts in `kitepdf-native-renderer/testResources@jvm/epub-sweep-pages.txt` encode the current line heights, so the fix moves counts.
- Fix sketch: keep a `lineHeightFactor: Double?` beside `lineHeightPt` in `Builder` and `ComputedStyle`; a unitless value sets the factor and clears the length, both inherit, and the used value at layout is `lineHeightPt ?: factor?.times(fontSizePt)`.

### EPUB-LAYOUT-02: `colspan`, `rowspan` and `<col span>` are read without a ceiling, so one attribute hangs or kills the layout
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

### EPUB-LAYOUT-03: A WOFF 1.0 font with a negative table length throws out of `prepareChapter`, and the throw repeats on every chapter
- Repo: KitePDF
- Area: kitepdf-epub Woff and FontRegistry
- Kind: bug
- Severity: high
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff.kt:46`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff.kt:47`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff.kt:74`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/FontRegistry.kt:42`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:463`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:615`
- What happens: `Woff.toSfnt` reads `compLength` and `origLength` with `u32(...).toInt()`, so a value at or above 2^31 comes back negative. The guard on line 46 tests `woffOffset + compLen > b.size`, which a negative `compLen` passes, and `b.copyOfRange(woffOffset, woffOffset + compLen)` on line 47 throws `IllegalArgumentException`. The sum of `align4(origLen)` over 65535 tables also overflows `pos`, and `ByteArray(pos)` on line 74 throws `NegativeArraySizeException`. `FontProgram.parse` wraps only `TrueTypeFont.parse` and `CffFont.parse` in `runCatching` (`FontRegistry.kt:46-48`); the `Woff.toSfnt` call on line 42 is bare, unlike `Woff2.toSfnt`, which wraps its whole conversion (`Woff2.kt:49`). Nothing above catches either: `ParsedEpub.programAt` (line 615), `loadFace`, the `fonts by lazy` of line 463, `EpubDocument.fontsFor`, `ChapterLayout.<init>` (`EpubDocument.kt:891`) and `prepareChapter`. A Kotlin `lazy` does not cache a throw, so the book's `fonts` throws again for every chapter that is laid out: one bad font file (a few bytes patched in a font the book already has) makes the whole book unreadable.
- What should happen: CONTRIBUTING.md, "Never break lenient salvage": a corrupt font becomes a skipped face. `Woff.toSfnt` should return null for any length that is negative or past the file, and `FontProgram.parse` should wrap the unwrapping as it wraps the SFNT parse.
- How to reproduce or evidence: take any WOFF 1.0 the tests use (`test@jvm/.../WoffTest.kt` builds one), set bytes 8 to 11 of the first directory entry (offset 44 + 8) to `FF FF FF F0`, name it in an `@font-face` of a chapter, and call `prepareChapter(0)`: an `IllegalArgumentException` from `copyOfRange` (fromIndex > toIndex) escapes `prepareChapter`.
- Cross-effects: `EpubDocument.pages`, `search`, `locate` and the viewer's background loader all go through `prepareChapter`, so the exception reaches the UI. The obfuscation path (`Deobfuscate`) runs first and does not change the outcome.
- Fix sketch: in `Woff.toSfnt`, validate `compLen >= 0 && origLen >= 0 && woffOffset <= b.size - compLen` with Long arithmetic and cap the total `pos` (for example at 64 MiB); in `FontProgram.parse`, `runCatching { Woff.toSfnt(bytes) }.getOrNull()`.

### EPUB-LAYOUT-04: A source line break between two CJK characters becomes a space, and justification stretches it
- Repo: KitePDF
- Area: kitepdf-epub BoxBuilder (white space collapsing)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:1098`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:1140`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:2465`
- What happens: `Inline.appendText` treats every collapsible character alike: `collapsible(cp)` (line 1140) is true for U+000A, and the next non-space character appends a `' '` (line 1098 on). A Chinese or Japanese chapter whose XHTML is wrapped at 80 columns, which most tools produce, gets a visible quarter-em space wherever the source wrapped. `tokenize` makes it a `Token.Space` (line 2465), `measure` counts it as a word separator, and a justified paragraph stretches those spurious spaces across the line, so the gaps land at random places in the text and move as the page width changes.
- What should happen: CSS Text 3, 4.1.2 (segment break transformation rules): when `white-space` collapses, a segment break is removed when the East Asian Width of the characters on both sides is F, W or H (not A) and neither is Hangul; otherwise it becomes a space. Blink and Gecko implement this, and `FontMetrics.isWide` already gives the width class the rule needs for the common case.
- How to reproduce or evidence: `EpubFixtures.epub("<p>春夏秋冬\n山川草木</p>")`, then `doc.page(KiteLocation(0, 0)).textContent().plainText` reads `春夏秋冬 山川草木` with a space; a browser shows `春夏秋冬山川草木`. `CjkJustifyTest` never puts a line break in its source text, so it cannot see this.
- Cross-effects: `EpubPage.textContent` and `search` carry the space, so a search for a word that straddles a source line break misses. The Hangul exception (a break between Hangul becomes a space) matters for Korean books.
- Fix sketch: in `appendText`, remember whether the pending space came only from segment breaks and what the last appended code point was; when the next character and the last one are both wide (`FontMetrics.isWide`, minus Hangul 0xAC00..0xD7A3 and 0x1100..0x11FF) and the pending white space held only line breaks, append nothing.

### EPUB-LAYOUT-05: Vertical margins never collapse between a box and its first or last child, nor through an empty block, nor with negative values
- Repo: KitePDF
- Area: kitepdf-epub BoxLayout (block formatting)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:294`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:345`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:366`
- What happens: `BlockFrame` sets `contentTop = box.y + border + padding` (line 294) and places the first child at `cursorY + topMargin` (line 345, `if (first) topMargin`), so a child's top margin stacks inside the parent's. `close()` adds the last child's bottom margin inside the parent (`cursorY += prevBottom`, line 366) and the grandparent then adds the parent's own. With the UA sheet's `body { margin: 1em }` and `p { margin: 1em 0 }`, the first paragraph of every chapter starts 24 pt below the top of the content box where a browser puts it at 12 pt; a `div > p` nesting repeats the doubling at every level, and `section`-per-chapter markup (every modern EPUB) has two or three levels. An empty block with `margin: 20px 0` between two paragraphs yields `max(prev, 20)` above it and `max(20, next)` below it, two gaps instead of one. Sibling margins take `maxOf(prevBottom, topMargin)` (line 345), so a negative margin, which authors use to pull a caption up, is ignored.
- What should happen: CSS 2.1, 8.3.1: adjoining margins of a parent and its first in-flow child collapse when nothing separates them (no border, padding, inline content or clearance), the same at the bottom when the parent's height is auto, the margins of an empty block collapse through it, and a negative margin combines as "the maximum of the positive margins plus the minimum of the negative ones".
- How to reproduce or evidence: `BoxLayoutTest.layout("<div><p>a</p></div>")`: `root.child(0).child(0).y - root.child(0).y` is 12.0 where CSS gives 0 and moves the `div` down by 12 instead. `BoxLayoutTest.adjacent_margins_collapse` covers siblings only.
- Cross-effects: every page count in `epub-sweep-pages.txt` depends on it; the differential sweep against mutool (which collapses parent and child margins) carries the difference at the top of each chapter. `Paginator` drops a margin at a page break by starting the page at the unit (#442), which hides the parent-child doubling on the first page of a chapter but not after a `div` boundary inside a page.
- Fix sketch: in `BlockFrame`, when the box has no top border or padding and is not a BFC root, do not add the first child's top margin at `contentTop` but hand the collapsed value (the max with the box's own) up to the parent's gap; mirror it for the last child's bottom margin when `heightPt` is null; keep separate positive and negative accumulators for the sibling gap.

### EPUB-LAYOUT-06: Percentages resolve against the page content box, not the containing block, so `table { width: 100% }` inside an indented box runs past its container
- Repo: KitePDF
- Area: kitepdf-epub/css (StyleResolver) and BoxLayout
- Kind: design
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:576`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:580`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:617`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:920`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1547`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1617`
- What happens: the cascade runs before layout and resolves every `%` of `width`, `min-width`, `max-width`, `margin-*`, `padding-*`, `text-indent`, `left` and `right` against `refWidthPt`, the page content width (lines 576 to 617), and `height`, `min-height`, `max-height` against the page content height (line 920). A block's `widthPt` is then used as is (`BlockFrame.<init>`: `s.widthPt ?: (availWidth - extra)`), and `layoutTable` takes `target = widthPt - gutters` and distributes the columns to it (lines 1547 and 1617) without looking at `avail`. So `blockquote table { width: 100% }` (the UA sheet gives `blockquote` 40 px side margins) is 60 pt (80 px) wider than its container and paints off the right edge of the page; `div.box { width: 50% } div.box div.inner { width: 50% }` makes the inner box as wide as the outer one; `p { height: 50% }` or `div.cover { height: 100% }` in an auto-height flow reserves half or all of a page of blank space ("a declared height may grow a box but never clip", `BoxLayout.kt` `close()`).
- What should happen: CSS 2.1, 10.2: a percentage `width` is calculated with respect to the width of the containing block; 8.3 says the same for margins and padding; 10.5: a percentage `height` whose containing block has `height: auto` computes to `auto`.
- How to reproduce or evidence: `BoxLayoutTest.layout("<blockquote><table style=\"width:100%\"><tr><td>x</td></tr></table></blockquote>", width = 300.0)`: the `TableBox.borderBoxWidth` is 300 while the blockquote's content width is 240, and `table.x + table.borderBoxWidth` is 330.
- Cross-effects: images are saved by `layoutImage`'s cap to `physicalRoomW`, so the symptom shows on blocks and tables. The script layer's `getComputedStyle` reads the same resolved values.
- Fix sketch: keep percentages as a `CssSize` (points or fraction) in `ComputedStyle` for the width-ish properties and resolve them in `BlockFrame`, `layoutTable` and `layoutImage` against the containing block's content width; treat a percentage `height` as `auto` unless the parent's height is definite.

### EPUB-LAYOUT-07: `display: inline-block` is laid out as plain inline content: no box, no width, padding or border, and it breaks across lines
- Repo: KitePDF
- Area: kitepdf-epub BoxBuilder and BoxLayout (inline formatting)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:321`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:887`
- What happens: both `place` (line 321) and `processInline` (line 887) send `Display.INLINE_BLOCK` down the same path as `Display.INLINE`, so an inline-block's children become runs of the surrounding paragraph. Its `width`, `height`, `min-width`, padding, border and margins are never used (the runs carry only text properties), its background is painted per run, and its text wraps mid-element like any inline. `vertical-align` on it applies as a per-run shift of its text. Books use inline-block for drop caps (`span.dropcap { display: inline-block; float: left }` works, since the float path takes it, but `display: inline-block` alone does not), for side-by-side figures, for labels with a fixed width (`span.num { display: inline-block; width: 2em }`), and for `<span class="pagenum">`, all of which lay out wrong.
- What should happen: CSS 2.1, 9.2.2 and 10.3.9: an inline-block is an atomic inline-level box with its own block formatting context, sized shrink-to-fit (10.3.5) and placed on the line as one unit, with `vertical-align` applied to the whole box against its baseline.
- How to reproduce or evidence: `BoxLayoutTest.layout("<p>a <span style=\"display:inline-block;width:100px;border:1px solid\">b</span> c</p>")`: the paragraph has one `TextBlockBox` whose runs are `a `, `b`, ` c` at natural widths; no box of 75 pt exists. `InlineDecorationTest` mentions inline-block only for decoration propagation.
- Cross-effects: `measureContent` and the flex `maxContentWidth` see an inline-block as text, so flex items and table cells that wrap an inline-block measure wrong too. The script DOM's `getBoundingClientRect` for such an element gives the text's rectangle.
- Fix sketch: in `processInline`, build an inline-block as a `BlockBox` (as a float is built), lay it out with the shrink-to-fit width from `measureContent`, and place it on the line as an image cell is placed (`Token.Word` of one cell with the box's width and height), aligned by its first baseline.

### EPUB-LAYOUT-08: Every `@media` block applies whatever its query says, and `<link media>` is ignored, so print and dark-mode rules restyle the page
- Repo: KitePDF
- Area: kitepdf-epub/css (CssParser) and EpubParse
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/CssParser.kt:83`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:836`
- What happens: `handleAtRule` flattens `@media` and `@supports` without reading the prelude (line 83, "always-matching"). `walkStyleSources` takes every `<link rel="stylesheet">` and ignores its `media` attribute (line 836). A sheet with `@media print { .screen-only { display: none } }` hides content; `@media (prefers-color-scheme: dark) { body { background: #000; color: #eee } }` (shipped by Standard Ebooks and by Apple Books templates) paints white text on black in a light reader, since it comes after the light rules; `@media (max-width: 480px)` phone overrides apply on a tablet; `@media amzn-kf8 { ... }` Kindle-only margins apply here. The KDoc of `CssParser` states the flattening as a design choice ("a reflow reader honours all media"), which is right for `screen` and `all` and wrong for the rest.
- What should happen: CSS Conditional 3, 6 (`@media`) and Media Queries 4: a block applies when its media query list matches. A paginated reader is `screen` (EPUB Reading Systems 3.3, 7.2 says reflowable content is rendered as `screen`), and `width`, `height`, `orientation`, `prefers-color-scheme` and `min-`/`max-` ranges can be evaluated against the page size and the reader's settings; `print`, `speech`, and unknown types such as `amzn-kf8` must not match. `@supports` can keep its always-match.
- How to reproduce or evidence: `EpubFixtures.epub("<style>p{color:black}@media print{p{color:red}}</style><p>x</p>")`: the glyph run's colour is red.
- Cross-effects: `EpubSettings.textColor` papers over the colour case but not `display: none`. The script DOM's `matchMedia` (other lane) should answer with the same evaluator.
- Fix sketch: parse the prelude after `@media` into a media query list (type, `and`, `not`, `only`, the width, height, orientation, prefers-color-scheme and range features) and evaluate it against `refWidthPt`, `refHeightPt` and a `EpubSettings.darkMode` flag; skip the block when it does not match; pass `media` from `<link>` and `<style>` through the same evaluator.

### EPUB-LAYOUT-09: HTML's presentational attributes are not mapped: `align`, `<center>`, `font`, `bgcolor`, `td width`, `img align`
- Repo: KitePDF
- Area: kitepdf-epub/css (StyleResolver.presentationalHints, UaStylesheet)
- Kind: capability
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:83`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/UaStylesheet.kt:49`
- What happens: `presentationalHints` maps `table[border]`, `table[cellspacing]`, `table[cellpadding]` and `td/tr[valign]` and nothing else (line 83 on). `<p align="center">`, `<div align="right">`, `<h1 align="center">`, `<td align="right">`, `<table align="center">`, `<center>`, `<font color="..." size="..." face="...">`, `<body bgcolor>`, `<td bgcolor>`, `<td width="30%">`, `<table width="100%">`, `<hr width>` and `<img align="left">` are silently ignored; `center` is not in the UA sheet at all, so it is an inline element and the block it wraps is hoisted out of it. These are the idiom of EPUB 2 books converted from HTML, of Project Gutenberg EPUBs and of Calibre output from old HTML, where centred chapter titles, right-aligned signatures and left-floated images all come from attributes.
- What should happen: HTML, 15.3.3 (align on `div`, `p`, headings, `table`, `td` and so on maps to `text-align` or auto margins), 15.3.4 (`img[align]` maps to `float` or `vertical-align`), 15.3.5 (`font` element: `color`, `face`, `size`), 15.3.9 (`td[width]`, `table[width]`, `bgcolor`), and the rendering section's `center { display: block; text-align: center }`.
- How to reproduce or evidence: `EpubFixtures.epub("<p align=\"center\">Title</p>")`: the run's `x` is the content left edge, not centred. `grep -n '"align"' kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt` finds nothing.
- Cross-effects: `EpubSettings.justify = true` would override the mapped `text-align` as it overrides author CSS today (see EPUB-LAYOUT-16).
- Fix sketch: add `center{display:block;text-align:center}` and `big{font-size:larger}`, `nobr{white-space:nowrap}` to the UA sheet, and extend `presentationalHints` with the HTML 15.3 table: `align` on block elements, `td`, `tr` and `table` (`center` as auto margins on `table`), `width`/`height` on `td`, `th`, `table`, `hr`, `bgcolor`, `font[color,face,size]` (size 1 to 7 as the spec's keyword table) and `img[align]`.

### EPUB-LAYOUT-10: Pages keep the whole box tree through `decoBoxes`, `hitBoxes` and `linkBoxes`, which the layout budget does not count
- Repo: KitePDF
- Area: kitepdf-epub EpubDocument (layout budget) and Paginator
- Kind: memory
- Severity: medium
- Confidence: likely
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Paginator.kt:283`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Paginator.kt:288`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1175`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1717`
- What happens: `Paginator.collect` puts every `BlockBox` with a background or border into `deco` (line 288), every box with a `source` into `hits` (line 283, every box of a scripted chapter), and every linked block into `links`; each `PageRender` keeps a filtered view of those lists, and a `BlockBox` holds its `children`, which hold `TextBlockBox.runs` (one `InlineRun` with its `String` per style change) and `lines`. So a chapter whose `body` or outer `div` has `background-color` (a white page background is a common author rule) retains its whole box tree, with every `InlineRun` text, through every one of its pages, and a scripted chapter retains all of it always. The comment above `buildDocRoot` ("The tree is most of what a laid-out chapter weighs, so it is never kept past pagination") describes the other case. `estimateBytes` (lines 1175 to 1203) counts glyphs at `BYTES_PER_GLYPH` (a corpus average, line 1717), images and SVG file sizes, and nothing for boxes, runs, the `structured` and `links` caches of `LiveChapter`, or the hit boxes, so two books of equal text can hold different multiples of what the budget believes, and the one with a page background drops later than it should on a 192 MB Android heap (CLAUDE.md, #218, #224).
- What should happen: the budget should count what the pages retain, or the pages should retain less: a page needs only the box's geometry and style to paint a background, not its children.
- How to reproduce or evidence: compare a heap class histogram after `prepareChapter` of one chapter with and without `body { background: #fff }`: `InlineRun` and `BlockBox` instances survive only in the second case, while `estimateBytes` answers the same number for both.
- Cross-effects: `keepChapters` and `trimToBudget` decide on the estimate; `EpubSettings.layoutCacheBytes` documents "about 165 bytes per character".
- Fix sketch: give `PageRender` a light `DecoBox(style, x, y, w, h, radii, decoRank, lastRank)` snapshot for deco, link and effect boxes, and for hit boxes keep `(source, rect, rank)` only; count runs and boxes in `estimateBytes` where a tree is kept, or use `KiteImageData.retainedBytes()` and an estimate for `KiteStructuredText` when `derived` stores it.

### EPUB-LAYOUT-11: `white-space: nowrap` and `pre` still wrap, `pre-line` keeps every space, and a tab is one space wide
- Repo: KitePDF
- Area: kitepdf-epub BoxBuilder and BoxLayout (white space)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1800`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:2707`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:1089`
- What happens: `WhiteSpaceMode.NOWRAP` is read only to compute `preserve` (line 1800); `wrap` never consults it, so `<span style="white-space:nowrap">10 km</span>` and `td { white-space: nowrap }` break like any text. `pre` and `pre-wrap` go through the same `wrap`, so a `pre` block wraps (CSS 2.1, 16.6.1: `pre` does not wrap; wrapping it is a defensible reader choice, but then `nowrap` is in the same class and should at least hold a short span). `pre-line` goes through `transformPre` (line 1089), which keeps every space, and `preserve` is true for it, so runs of spaces are drawn (CSS Text 3, 4.1.1: `pre-line` collapses spaces and tabs and preserves segment breaks only). A tab in a `pre` block is a `Token.Space` the width of a space (line 2465 of `tokenize`), so code indented with tabs loses its indentation (CSS Text 3, 4.1.1 and `tab-size`, default 8).
- What should happen: CSS Text 3, 4.1.1 and 5.1 (the `white-space` table): `nowrap` and `pre` suppress soft wrap opportunities; `pre-line` collapses spaces; a tab in preserved text advances to the next multiple of `tab-size` spaces.
- How to reproduce or evidence: `BoxLayoutTest.layout("<p>aaaa <span style=\"white-space:nowrap\">b c d e f</span></p>", width = 60.0)`: the span's words land on several lines.
- Cross-effects: `EpubPage.textContent` copies the extra spaces of `pre-line`.
- Fix sketch: give `Token.Word` a `noWrap` flag from the run and merge adjacent words and spaces of a nowrap run into one token in `tokenize`; for `pre-line`, collapse in `appendText` but keep `\n`; expand tabs to the next tab stop when measuring preserved text.

### EPUB-LAYOUT-12: `vw` and `vh` take whatever reference the property uses: a `font-size` in `vw` is a fraction of the parent font size
- Repo: KitePDF
- Area: kitepdf-core css (CssValues), used by kitepdf-epub StyleResolver
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/css/CssValues.kt:35`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/css/CssValues.kt:36`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:930`
- What happens: `CssValues.length` resolves both `vw` and `vh` as `n / 100 * refPt` (lines 35 and 36), and `refPt` is whatever the caller passes for percentages: `resolveFontSize` passes the parent font size (line 930), so `font-size: 3vw` on a 12 pt parent is 0.36 pt; `margin-top: 5vh` passes `refWidthPt`, a share of the page width; `height: 50vw` passes `refHeightPt`. Fixed-layout books size text and boxes in viewport units.
- What should happen: CSS Values 4, 6.1.2: `vw` is 1% of the viewport width and `vh` 1% of its height, whatever the property.
- How to reproduce or evidence: `EpubFixtures.epub("<p style=\"font-size:10vw\">x</p>")` at 400 pt: the run's `fontSize` is 1.2, not 40 (the page is the viewport of a reflowable chapter) or the declared viewport of a fixed one.
- Cross-effects: `svgSizePt` in `BoxBuilder` calls `CssValues.length` with `refPt = 0`, so `vw` there is 0; the SVG module shares the parser.
- Fix sketch: give `CssValues.length` separate `viewportWidthPt` and `viewportHeightPt` parameters (with defaults for the SVG caller) and pass `refWidthPt` and `refHeightPt` from every `StyleResolver` site.

### EPUB-LAYOUT-13: A float without a width takes half the content width instead of its shrink-to-fit width
- Repo: KitePDF
- Area: kitepdf-epub BoxLayout (floats)
- Kind: design
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:465`
- What happens: `placeFloat` gives a floated block `widthPt + extra` when the author set a width, else `contentW / 2` (line 465, "a shrink-to-fit approximation"). A floated `<div class="sidenote">` with two words, a floated `<figure>` around a small image, or a floated `<span class="dropcap">` of one letter takes half the column, and the text beside it wraps in the other half with a wide gap. Same-side floats always stack (the comment says so), and floats never escape their block, so a tall float at the end of a short paragraph stretches the paragraph.
- What should happen: CSS 2.1, 10.3.5: a float's `auto` width is `min(max(preferred minimum width, available width), preferred width)`; `measureContent` already computes both widths for table cells and could serve here.
- How to reproduce or evidence: `BoxLayoutTest.layout("<div style=\"float:left;border:1px solid\">ab</div><p>text</p>", width = 300.0)`: the `div.borderBoxWidth` is 150 where the text measures about 14.
- Cross-effects: `floatInsets` shortens every overlapping line by that width, so a drop cap that floats costs half the column for three lines.
- Fix sketch: `val (pref, min) = measureContent(child)`; budget = `min(max(min, contentW), pref) + extra`, as `layoutTable` sizes columns.

### EPUB-LAYOUT-14: `line-height: normal` is 1.4 em and the baseline sits at 0.8 em whatever the font says
- Repo: KitePDF
- Area: kitepdf-epub BoxLayout (line boxes)
- Kind: design
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1844`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1845`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1813`
- What happens: a line's height is `lineHeightPt ?: maxFs * 1.4` and its ascent `maxFs * 0.8` (lines 1844 and 1845; the float estimate at line 1813 repeats the 1.4). An embedded face's `hhea` ascender and descender and `OS/2` metrics are never read, although `FontProgram` parses the tables. A face with an ascender of 1.0 em (many Noto and CJK fonts, and most fonts with tall accents) draws its capitals and accents into the line above when the author sets `line-height: 1` or `1.1`, and a face with a short descender gets more leading below than above. Browsers and MuPDF (`html-layout.c`, 1.2 for `normal`) use 1.2 or the font's own line gap, so the same book runs to about 15% more pages here, and the differential sweep baseline has absorbed that.
- What should happen: CSS 2.1, 10.8.1: `normal` is based on the font's metrics, with 1.0 to 1.2 recommended; CSS Inline 3, 4.1: the ascent and descent of an inline box come from the font's ascent and descent metrics.
- How to reproduce or evidence: `FontMetricsTest` and `BoxLayoutTest` assert the 1.4 and 0.8 constants; no test reads a face's ascender.
- Cross-effects: every page count in the EPUB sweep; `EpubSettings.lineHeightScale` KDoc describes the extra as leading.
- Fix sketch: expose `ascender1000` and `descender1000` from `EmbeddedFace` (hhea, or OS/2 typo metrics when `USE_TYPO_METRICS` is set) and base-14 values for the generic path; use them for the ascent and for `normal`, with a factor near 1.2 for faces without a line gap.

### EPUB-LAYOUT-15: A chapter laid out from a `withSettings` copy loses the canvases its scripts drew, since `canvasImages` lives on the document and the scripted tree on the parse
- Repo: KitePDF
- Area: kitepdf-epub EpubDocument (withSettings) with the script session
- Kind: bug
- Severity: low
- Confidence: suspected
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:639`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1371`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:336`
- What happens: `replaceTree` stores a script's tree in `ParsedEpub.scriptedSpines` (`EpubParse.kt:336`), shared by every document over the book, and that tree names the pixels a canvas holds by `kite-canvas:` hrefs that `CanvasImages` resolves. `canvasImages` is a field of `EpubDocument` (line 639), not of `ParsedEpub`, so a document made by `withSettings` (line 1371) lays the scripted chapter out from the shared tree and looks the hrefs up in an empty store: `SvgImage.fromElement(drawing, null, canvasImages)` gets null for each image, and the canvas paints blank until a new script session over the new document draws again, which the brief's other lane should confirm happens on a font-size change.
- What should happen: what the layout reads for a chapter should come from one place: the pixels belong with the tree that names them.
- How to reproduce or evidence: a scripted chapter that draws into a canvas with `putImageData`; after `doc.withFontSize(16.0)`, `page.renderTo(RecordingCanvas())` on the new document records no image call for the canvas while the old document records one.
- Cross-effects: `BlobUrlStore` was moved to `ParsedEpub` for the same reason (#533); `RemoteResources` too (#38).
- Fix sketch: move `CanvasImages` into `ParsedEpub` next to `blobs`, and pass `parsed.canvasImages::get` from both `docRootBuild` and `layFrame`.

### EPUB-LAYOUT-16: The reader-origin overrides use `*`, so a forced justification flattens centred headings and a forced family removes monospace from code
- Repo: KitePDF
- Area: kitepdf-epub EpubDocument (readerRules)
- Kind: design
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:608`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:610`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:621`
- What happens: `readerRules` (line 608 on) emits `*{text-align:justify;text-align-last:auto}` for `justify = true` (line 621) and `*{font-family:serif}` for a font family (line 610), at `Origin.READER`, which outranks author `!important`. A justified last line aligns at the start, so every one-line element, chapter titles, poem lines, epigraphs, dedications and `th` cells (UA `text-align:center`) become left-aligned; `pre`, `code`, `kbd` and `samp` lose their monospace face and code listings lose their columns. A reader setting meant for body text thus restyles everything.
- What should happen: a reader override should reach body text and leave what the author or the UA sheet align or set for a reason: Readium CSS, the reference most readers follow, applies the user's `text-align` to `body` and its text blocks and excludes headings and tables, and applies the font family to body text with `pre, code, kbd, samp` kept monospace.
- How to reproduce or evidence: `EpubDocument.open(EpubFixtures.epub("<h1 style=\"text-align:center\">T</h1><pre>a    b</pre>"), EpubSettings(justify = true, fontFamily = ReaderFontFamily.SERIF))`: the `h1` run starts at the content left edge and the `pre` runs use the serif widths.
- Cross-effects: EPUB-LAYOUT-09's mapped `align` attributes would be overridden the same way.
- Fix sketch: emit the justify rule for `p, li, dd, blockquote, div` with `:not(h1, h2, h3, h4, h5, h6, th, figcaption, caption)` (the selector parser supports `:not()` and `:is()`), and the family rule as `*:not(pre, code, kbd, samp, tt, var)`.

### EPUB-LAYOUT-17: The WOFF2 and WOFF decoders use the 512 MiB PDF stream cap, so a tiny font file can demand a gigabyte
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

### EPUB-LAYOUT-19: A table lays every cell out twice and a nested table doubles that at each level
- Repo: KitePDF
- Area: kitepdf-epub BoxLayout (tables)
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1670`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1690`
- What happens: `layoutTableGrid` lays out every cell in pass A to measure heights (line 1670) and again in pass B at the final y (line 1690), each pass a full `layoutBlock` of the cell's content including any table inside it, which repeats its own two passes. A table nested d levels deep lays its innermost cells out 2^d times; `HtmlParser.MAX_DEPTH` of 32 allows about ten levels, 1024 layouts of the leaf text. Layout tables of converted HTML nest three to five deep, 8 to 32 times the work, and `measureCell` adds one `measureContent` per cell on top.
- What should happen: one layout per cell: lay the cell out once at its column width, then shift it (`shiftSubtree`) to its row's y and stretch its height.
- How to reproduce or evidence: count `layoutInline` calls for `<table><tr><td><table><tr><td><table><tr><td>x</td></tr></table>...` three deep: eight for one text block.
- Cross-effects: `Paginator` and `columnRun` call nothing twice; `textMaxContent` in flex measures text once more per nesting level, a related cost.
- Fix sketch: after pass A, compute row heights, then `shiftSubtree(cell, 0, rowY[cell.gridRow] - (cell.y - tContentTop))` instead of a second `layoutBlock`.

### EPUB-LAYOUT-20: `docs/epub.md` says the writing mode is one decision per book read from the first reflowable chapter, which the code no longer does
- Repo: KitePDF
- Area: docs and kitepdf-epub EpubDocument
- Kind: docs
- Severity: low
- Confidence: verified
- Where: `docs/epub.md:119`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:750`
- What happens: the page says "The layout of any chapter also reads the first reflowable chapter. The writing mode (horizontal or vertical) is one decision for each book, and KitePDF takes it from that chapter." Since #507 each chapter lays out in its own mode (`ChapterLayout.step`, stage 3, calls `verticalModeOf(built)` on the chapter's own tree). The `verticalMode` lazy of line 750 still builds the first reflowable chapter's box tree, but nothing in `src` reads `isVertical` or `isVerticalLr` any more (only `VerticalWritingTest` does), so the paragraph describes dead code and tells a reader to expect a cost that is not paid.
- What should happen: the docs should say that each chapter's writing mode is its own, from its root or body (CSS Writing Modes 3, 8), and the dead members should go, or the docs should say what they are for.
- How to reproduce or evidence: `grep -rn "isVertical\b\|verticalMode" kitepdf-epub/src kitepdf-compose-viewer/src` lists only the definitions.
- Cross-effects: none seen.
- Fix sketch: delete the paragraph's first three sentences and the `verticalMode`, `isVertical`, `isVerticalLr` members, moving the test's assertions onto `PageRender.vertical`.

### EPUB-LAYOUT-21: Hyphenation takes one language per chapter, so a `lang` on an element inside it hyphenates with the wrong patterns
- Repo: KitePDF
- Area: kitepdf-epub EpubDocument and BoxLayout (hyphenation)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:818`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:96`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:2217`
- What happens: `languageFor(chapter)` reads `lang` from the chapter's `html` or `body` only (line 818), `BoxLayout` builds one `hyphenator` from it (line 96), and every word of the chapter goes through it (line 2217) when `hyphens: auto` holds. A French quotation marked `<span lang="fr">` in an English chapter, or the German glossary entries of a bilingual reader, break by English patterns, which CSS forbids ("hyphenation is language-dependent": CSS Text 3, 5.4, and "the UA must not hyphenate text in a language it has no rules for"). The language is also folded into `lang` by the XML reader, so `xml:lang` on an inner element is available for free.
- What should happen: CSS Text 3, 5.4: the hyphenation rules follow the content language of the element the text is in, which `:lang()` already resolves per element in `LangCondition`.
- How to reproduce or evidence: a chapter `<html lang="en"><body><p style="hyphens:auto">... <span lang="de">Donaudampfschifffahrt</span></p>` at a narrow width: the German word breaks at English points (`Donaud-ampfschifffahrt`), which `Hyphenator.forLanguage("de")` would not give.
- Cross-effects: `EpubSettings.hyphenate = true` turns `hyphens: auto` on for the whole book, so the mismatch reaches every book with inline language changes.
- Fix sketch: carry `lang` in `ComputedStyle` as an inherited value (from the element's `lang` attribute in `compute`), copy it into `InlineRun`, and pick the hyphenator per word from the run's language through `Hyphenator.forLanguage`'s cache.

### EPUB-LAYOUT-22: `full-path` of `container.xml` is used as a zip name without percent-decoding
- Repo: KitePDF
- Area: kitepdf-epub EpubParse and Opf
- Kind: bug
- Severity: low
- Confidence: likely
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:816`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Opf.kt:128`
- What happens: `containerOpfPath` returns the `full-path` attribute as written (line 816 on) and `Opf.parse` reads `zip.readText(opfPath)` with it (line 128). A package at `OEBPS/my book.opf` written as `full-path="OEBPS/my%20book.opf"` fails with "OPF not found", while every other href in the book goes through `EpubDocument.resolvePath`, which percent-decodes. OCF 3.3, 3.5.2.1, types the attribute as a path-relative-scheme-less-URL string, whose segments may be percent-encoded; a path with a literal space works today and a percent-encoded one does not, and the behaviour differs from the manifest hrefs of the same book.
- What should happen: resolve `full-path` as the other URLs of the book are resolved, with `resolvePath("", fullPath)`, which also drops a stray fragment or query.
- How to reproduce or evidence: rename the OPF of any fixture to `content file.opf` and write `full-path="OEBPS/content%20file.opf"`: `EpubDocument.open` throws `EpubFormatException("OPF not found at OEBPS/content%20file.opf")`.
- Cross-effects: `TocParser` and `fontFaceRules` resolve against `opf.baseDir`, which would follow the decoded path.
- Fix sketch: `val opfPath = containerOpfPath(zip)?.let { EpubDocument.resolvePath("", it) }`.

### EPUB-LAYOUT-23: A numeric character reference in a `text/html` chapter does not get HTML's C1 remap or the U+FFFD replacements
- Repo: KitePDF
- Area: kitepdf-core xml (KiteXml.decodeEntities), used by kitepdf-epub for `text/html` chapters
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/xml/KiteXml.kt:251`, `kitepdf-core/src/io/github/yuroyami/kitepdf/core/xml/KiteXml.kt:371`
- What happens: `decodeEntities` turns `&#150;` into U+0096 and `&#0;` into U+0000 (`charsFor`, line 371), and leaves `&#x110000;` or a number past `Int` as literal text (line 251, `toIntOrNull` null). For an XHTML chapter that is right: XML 1.0, 4.1, makes `&#150;` a reference to U+0096. For a chapter the manifest serves as `text/html` (#547, parsed with `html = true`) HTML, 13.2.5.80 (numeric character reference end state) remaps 0x80 to 0x9F through the Windows-1252 table (`&#150;` is an en dash, `&#151;` an em dash, `&#146;` a right quote), and turns 0, surrogates and numbers above 0x10FFFF into U+FFFD. Word-exported HTML writes those references everywhere, so an HTML chapter shows control characters where its dashes and quotes are.
- What should happen: the HTML remap and replacements when the document is parsed as HTML, and XML's rules otherwise.
- How to reproduce or evidence: `HtmlParser.parse("<p>a&#150;b</p>", html = true)` gives the text `a\u0096b`; browsers give `a\u2013b`, an en dash.
- Cross-effects: `EpubPage.textContent` and search carry the control character; `TextEncoding.CP1252_HIGH` already holds the table.
- Fix sketch: pass an `html` flag to `tokenize` and `decodeEntities`; when set, map 0x80..0x9F through `CP1252_HIGH`, and 0, 0xD800..0xDFFF, > 0x10FFFF and an overflow to U+FFFD.

### EPUB-LAYOUT-24: The viewer's pager takes `reverseLayout` straight from the book, so under an RTL app locale every book pages the wrong way
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KiteDocLayout.pagedFor and the pager), from the EPUB `rightToLeft` metadata
- Kind: bug
- Severity: low
- Confidence: suspected
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:137`, `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1125`, `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1177`
- What happens: `pagedFor` sets `reverseLayout = document.metadata.rightToLeft` (line 137), and `EpubMetadata.rightToLeft` follows the spine's `page-progression-direction`, else `vertical-rl` or a right-to-left language (`EpubParse.buildMetadata`), which matches EPUB 3.3, 5.5. The pager then passes `reverseLayout = layout.reverseLayout` to `HorizontalPager` (line 1177) with no `LocalLayoutDirection` override. Compose's pager reads `reverseLayout` relative to the layout direction: under an RTL locale (Arabic, Hebrew, Persian app), `reverseLayout = false` already puts page 0 at the right, so an English book pages right to left, and an Arabic book (`reverseLayout = true`) pages left to right. `PageTurn` at line 1125 XORs the locale in (`reversed = (horizontal && direction == Rtl) != layout.reverseLayout`), so the drag direction agrees with the pager but both are reversed against the book; `spreadPlan` passes `rightToLeft = layout.reverseLayout` without the XOR, so the spread pairing follows the book while the paging follows the locale.
- What should happen: the page progression of a book is the book's, not the UI locale's (EPUB 3.3, 5.5; EPUB Reading Systems 3.3, 5.1): the pager should get `reverseLayout = rightToLeft != (direction == Rtl)`, or run under `CompositionLocalProvider(LocalLayoutDirection provides Ltr)`.
- How to reproduce or evidence: a `SceneTestDriver` scene with `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)` around `KiteDocView` of an English fixture: the first page sits at the right edge and a swipe from right to left goes backward.
- Cross-effects: this is the viewer lane's code; the EPUB side's `rightToLeft` is right. Listed here because the brief asks whether the rendition handling matches `pagedFor`.
- Fix sketch: resolve the locale once in `KiteDocView` and pass `reverseLayout = layout.reverseLayout != (direction == LayoutDirection.Rtl)` to both pagers and to `spreadPlan`, or pin the pager's layout direction to LTR.

### Capabilities and opportunities (not defects)

- `linear="no"` spine items: `Opf.parse` reads `idref` and `properties` of each `itemref` (`Opf.kt:195`) and drops `linear`, so a viewer cannot skip auxiliary documents from the reading order or offer them on demand (EPUB 3.3, 5.5, `linear`). A `isLinear(chapter)` on `EpubDocument` is a small addition; reach: every book with notes, image galleries or a cover kept out of the flow.
- `@font-face` descriptors: `unicode-range`, `font-weight` ranges (`400 700`), `font-stretch` and `format()` are not read (`CssParser.parseFontFace`), and `FontRegistry.match` keeps one boolean for weight. Families split by Unicode range (every CJK or Latin plus Cyrillic web font) work by the per-glyph fallback across all families, which can pick the wrong family when two overlap. Implementing CSS Fonts 4, 5.2 (the matching algorithm) with numeric weights and ranges is a medium change in `FontRegistry` and `FontFaceRule`.
- `calc()`, `min()`, `max()`, `clamp()` values are dropped as invalid by `CssValues.length`; `width: calc(100% - 2em)` is in many modern stylesheets. A small expression evaluator over the tokenizer would do.
- Text encodings beyond UTF-8, UTF-16 and Windows-1252: `TextEncoding.canonical` maps nothing else, so an EPUB 2 chapter declaring `windows-1251`, `iso-8859-2`, `shift_jis` or `gb2312` decodes as 1252 mojibake; EPUB forbids these but old converters shipped them.
- HTML's hidden elements: the UA sheet lacks `template`, `datalist`, `noembed`, `noframes { display: none }`, so their content renders.
- `dir="auto"` and `<bdi>` are treated as inherited direction (`StyleResolver.compute` reads only `rtl` and `ltr`), and `unicode-bidi` is not read, so `<bdo dir="rtl">` only changes the base direction. HTML, 3.2.6.4 (the directionality) and CSS Writing Modes 3, 2.2.
- Counters and `counter()`/`counters()` in generated content make the rule inert (`parseContentValue`), so numbered notes and figure captions built with CSS counters lose their numbers.
- The conformance report (`docs/epub-conformance.md`) is honest where I checked it: `lay-reflow-align-x-center` is marked not applicable rather than passed, `pss-support` is marked as failing, `lay-fxl-orientation-landscape` fails, and the "Judged" rows say what was read. I found no "Passes" that the code does not back, with the note that `css-epub-hyphens` is judged from the code and narrower pages, as the row says.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane EPUB-SCRIPT: how document JavaScript is run

Lane: `kitepdf-javascript` (PDF and EPUB runners, Acrobat and AForm APIs, thread actuals), `kitepdf-epub/.../script/` (the DOM prelude and its Kotlin hosts), `kitepdf-epub/EpubScriptSession.kt`, the viewer's script lane, `docs/javascript.md`, and the KiteJS API contract the glue builds on.

The PDFium sources cited below are `fxjs/cjs_publicmethods.cpp` and `fxjs/cjs_util.cpp` of the `main` branch of pdfium.googlesource.com, fetched on 2026-11-04 into `/tmp/claude-0/-home-user/8fa91e2b-1e52-50dc-8511-48f93ec9d76e/scratchpad/EPUB-SCRIPT/pdfium/`. The docs name Chrome's PDF viewer as the target, so PDFium is the oracle for the AF library.

### EPUB-SCRIPT-01: A field script that fails to parse or is stopped hands the previous event's result to the field
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner, PdfScriptHost, AcrobatApi)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:678-692`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:694-707`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:63-64`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:90-98`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:635-667`
- What happens: `dispatch` wraps a field script in `__kiteEvent(__kiteEventInfo, function () {\n$script\n})` and, whatever `evaluate` did, returns `host.lastEventResult` (line 691). `lastEventResult` is only written by `__kite.eventResult`, which `__kiteEvent` calls in its `finally` (AcrobatApi.kt:656-665). Two paths never reach that `finally`: a script with a syntax error fails inside `engine.evaluate` before `__kiteEvent` is called, and a script stopped by the deadline or the instruction budget is ended by QuickJS with an uncatchable error that skips `finally` (`quickjs.c:8683-8686` sets the interrupt error uncatchable and the exception path at `quickjs.c:20965` runs no handler for an uncatchable error). In both cases `evaluate` records the failure and returns null (lines 701-703), and `dispatch` hands back the `rc`, `value` and `change` that the *previous* event left, which may belong to another field. `keystrokeHere` then merges the stale `change` (line 420), `commitOnThread` stores the stale `value` as the field's value after a broken validate script (line 461), and `choiceRewrite` applies a stale value to a choice (lines 485, 496). The stale `rc` can also refuse a keystroke on one field because an earlier event on another field set `rc = false`.
- What should happen: an event whose script did not run to `__kiteEvent` must answer as `unchanged` does (line 680): rc true, the event's own value and change. ISO 32000-1 §12.6.3 says nothing about a failed trigger changing the value, and Chrome's engine leaves the field alone when a script throws.
- How to reproduce or evidence: a form with fields `total` (format script `AFNumber_Format(2, 0, 0, 0, "$", true)`) and `name` (validate script `if (event.value.length > ) {}`, a syntax error). `runner.setFieldValue("total", "1234.5")` runs the format script, so `lastEventResult.value` becomes `"$1,234.50"`. Then `runner.setFieldValue("name", "Bob")` fails to parse the validate script, `dispatch` returns the stale result, and `formState.value("name")` is `"$1,234.50"`. With a fresh runner the first stale result is `EventResult(true, "", "")`, so a keystroke into a field whose keystroke script does not parse inserts `""`: the typed character is dropped silently.
- Cross-effects: the budget path makes this timing dependent: a keystroke script that occasionally runs past `budgetMillis` (for example one that calls `calculateNow`) corrupts the field with the last event's value. `RedrawnText` and the viewer's `KeystrokePipeline` then show the stale text.
- Fix sketch: have `__kite.eventResult` carry an event sequence number, or reset `lastEventResult` to `unchanged` in `dispatch` before `evaluate` and treat a null answer from `evaluate` as "unchanged"; the `finally` in `__kiteEvent` cannot be relied on for a budget stop.

### EPUB-SCRIPT-02: AFSimple_Calculate over a field that has kids sums nothing, because getField finds terminal fields only and getArray answers the field itself
- Repo: KitePDF
- Area: kitepdf-javascript (AformApi, AcrobatApi, PdfScriptHost), kitepdf-pdf (PdfDocument.formFields)
- Kind: bug
- Severity: medium
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:254-281`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:408`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:514-519`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:69`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:690-697`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:722-724`
- What happens: the invoice pattern Acrobat and LiveCycle write is `AFSimple_Calculate("SUM", "amount")` where `amount` is a parent field with kids `amount.0`, `amount.1`, ... `AFSimple_Calculate` calls `g.getField(name)` for each name and `field.getArray()` for its parts. `getField` asks `__kite.hasField`, which is `document.formField(name) != null`, and `formFields` holds "the AcroForm field tree's terminal fields" only (PdfDocument.kt:691-692), so a parent name answers null and the loop `continue`s. Even when the name is terminal, `Field.prototype.getArray` answers `[this]` (AcrobatApi.kt:408). The total is 0. PDFium loops `for j < pForm->CountFields(wsFieldName)` and `GetField(j, wsFieldName)` (`cjs_publicmethods.cpp`, `AFSimple_Calculate`); `CPDF_InteractiveForm::CountFields(name)` is `FindNode(name)->CountFields()`, which `CountFieldsInternal` sums over every child of the node (`core/fpdfdoc/cpdf_interactiveform.cpp:665-672` and `:450-457` of the fetched copy), so a parent name sums every terminal field under it. Acrobat's `getArray` is documented to answer the terminal children of a parent.
- What should happen: `getField("amount")` answers a Field for a non-terminal node and `getArray()` answers its terminal descendants (Acrobat JavaScript Reference, `Field.getArray`), so `AFSimple_Calculate("SUM", "amount")` sums `amount.0`, `amount.1`, ... as Chrome does.
- How to reproduce or evidence: a form whose `/Fields` holds `<< /T (amount) /Kids [a 0 R b 0 R] >>` with kids `/T (0) /FT /Tx /V (10)` and `/T (1) /FT /Tx /V (20)`, and a field `total` with `/AA /C` of `AFSimple_Calculate("SUM", "amount")`. `runner.setFieldValue("amount.0", "10")` then `runner.formState.value("total")` is `"0"`; Chrome shows 30. `FormScriptTest` only sums terminal names (`"price.1, price.2"` style), so nothing covers a parent name.
- Cross-effects: `this.getField("amount").value = ...` on a parent is a no-op too, and `getNthFieldName` and `numFields` count terminal fields only, which differs from Acrobat's counting of every node.
- Fix sketch: let `__kite.hasField` and a new `__kite.fieldKids(name)` answer for a prefix that terminal names start with (`name + "."`), and make `getArray` answer `kids.map(getField)`; `AFSimple_Calculate` then works unchanged.

### EPUB-SCRIPT-03: The viewer never shows a format script's result, and formatAll runs every format script for nothing on each commit
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner), kitepdf-pdf (PdfScriptHandler), kitepdf-compose-viewer
- Kind: design
- Severity: medium
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:754-759`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:558-573`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:463-464`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfScriptHandler.kt:22-232`, `docs/javascript.md:149`, `docs/javascript.md:160`
- What happens: `commitOnThread` calls `calculateAll()` then `formatAll()`. `formatAll` calls `formatOnThread` for every field with a format script and throws the string away (lines 754-759): a format script changes only `event.value`, which `__kiteEvent` reports and nobody stores. The only reader of a formatted value is the blocking `PdfScriptRunner.formattedValue`, which `PdfScriptHandler` does not expose and which no file in `kitepdf-compose-viewer`, `kitepdf-pdf` or `kitepdf-native-renderer` calls (grep for `formattedValue`, `formatted`, `displayValue` finds only the runner and its tests). So `KiteDocView` draws `formState.value`, and a field with `AFNumber_Format(2, 0, 0, 0, "$", true)` shows `1234.5` where Chrome shows `$1,234.50`. Meanwhile each commit (every blur, every check box tap, every choice commit) evaluates N format scripts, each costing a `defineValue` plus an `evaluate` on the script thread and a budget window.
- What should happen: the docs say the viewer "runs validate, then calculate, then format" and "draws the form from its live values" (javascript.md:149, 160), which a reader takes to mean the formatted text is what shows. Either the handler exposes the formatted text per field and the viewer draws it (and `formatAll` becomes the thing that fills that cache), or `formatAll` goes and the docs say format scripts are not shown.
- How to reproduce or evidence: open a PDF with an `AFNumber_Format` field in `KiteDocView`, type `1234.5`, tap elsewhere: the page shows `1234.5`. `FormScriptTest.the_format_script_decides_what_the_field_shows` passes only through `runner.formattedValue`.
- Cross-effects: a form with a few hundred formatted fields (tax forms) runs a few hundred scripts on every check box tap; with `budgetMillis` each one may spend seconds on the lane while the reader waits for the next keystroke answer.
- Fix sketch: keep a `formatted: Map<String, String>` in the runner that `formatAll` fills and expose it on `PdfScriptHandler` (`displayText(fieldName)`), then have the form renderer prefer it over `formState.value`.

### EPUB-SCRIPT-04: A script can build a tree deeper than the parser's nesting limit, and the layout's recursion then overflows the stack
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

### EPUB-SCRIPT-05: A node a script makes is never freed while its chapter is live
- Repo: KitePDF
- Area: kitepdf-epub (ScriptDom, DomPrelude)
- Kind: memory
- Severity: medium
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:38-39`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:460`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:511-515`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomPrelude.kt:210`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomPrelude.kt:497-520`
- What happens: every node the script side names goes through `ScriptDom.idOf`, which appends it to `nodes` (an `ArrayList` that only grows) and `ids`; `createElement` also puts it in `fragments` and `names`, and attributes go into `attributeLists`. Nothing removes an entry: a node detached with `remove()` or never inserted stays in all of them for the life of the chapter. On the script side `wrap` keeps every wrapper in `wrappers`, a strong `Map`, not a `WeakMap`, so the engine cannot collect them either. A chapter that animates by creating elements each frame (a common pattern: `container.innerHTML = ...` or `createElement` per tick) grows without bound for as long as the chapter is one of the eight live ones, which for the chapter the reader is on is the whole session. The prelude uses a `FinalizationRegistry` for `Path2D` handles (DomCanvas.kt:14), so the machinery to free host handles exists, and nodes do not use it.
- What should happen: a detached node that no script holds is collectable, as in a browser. The host keeps a node only while the script holds a wrapper or the tree holds the node.
- How to reproduce or evidence: a chapter with `setInterval(function () { for (var i = 0; i < 1000; i++) document.createElement('div'); }, 0)`; pump timers for a minute and watch `ScriptDom.nodes.size` (and the JVM heap) climb by 60,000 nodes a second, each a `KiteXmlNode.Element` plus a map entry plus a QuickJS object.
- Cross-effects: `EpubScriptSession.unloadChapters` and the LRU eviction are the only release, and the current chapter is never evicted; `holdTree` keeps the layout's copy too.
- Fix sketch: register wrappers in a `FinalizationRegistry` that calls a `__kite.release(id)` host function, and have `ScriptDom` drop a released node that has no parent and is not `root`; keep `wrappers` as a `WeakMap` keyed by id object or an id-to-`WeakRef` map.

### EPUB-SCRIPT-06: localStorage and sessionStorage have no quota, so a script can grow the host's memory without limit
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

### EPUB-SCRIPT-08: Closing a PdfScriptRunner from one of its own callbacks throws out of close() and leaks the engine
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner, ScriptThread actuals), KiteJS (QuickJsKiteJs.close)
- Kind: concurrency
- Severity: medium
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:779-810`, `kitepdf-javascript/src@jvm/io/github/yuroyami/kitepdf/javascript/ScriptThread.jvm.kt:19-26`, `kitepdf-javascript/src@native/io/github/yuroyami/kitepdf/javascript/ScriptThread.native.kt:30-35`, `/home/user/KiteJS/kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:197-205`, `docs/javascript.md:224`
- What happens: the docs say the callbacks run on the script thread, and `onRequest` receives `PdfScriptRequest.Document("closeDoc")`, the natural place for a host to call `runner.close()`. `close()` then runs `closeHere()` on the same thread: `thread.call` runs the block in place (`Thread.currentThread() === thread`, or `runningOn === this` on native), the block calls `openEngine?.close()`, and KiteJS refuses with `JsEngineError("this engine is running a script; close it once that call has returned")` because its `depth > 0`. The `finally` sets `openEngine = null` and the outer `finally` shuts the executor down, so the exception leaves `close()` into the host's callback (an `AutoCloseable.close` that throws from a normal call), the running script fails with it, and the `KiteJsScriptEngine` is never closed: its QuickJS runtime, up to 512 MB, and its thread slot stay allocated for the life of the process. `closed = true` makes every later call throw `IllegalStateException`, so nothing can close it afterwards. The EPUB docs warn that an `onNavigate` listener "must not call the runner" (javascript.md:401); the PDF docs give no such warning, and the `closeWaits` logic (lines 783-786) covers only the web and a caller-provided engine.
- What should happen: `close()` from a callback defers the close to the end of the running call, as the web path does with `closeWaits`, and never throws.
- How to reproduce or evidence: `PdfScriptRunner(doc, onRequest = { if (it is PdfScriptRequest.Document) runner.close() })` and a button whose script is `this.closeDoc()`; `runner.mouseUp("b")` on the JVM throws `JsEngineError` wrapped in `KiteScriptException` out of the call, and `runner.failures` records "this engine is running a script"; a heap dump still holds the `QuickJsKiteJs`.
- Cross-effects: two `close()` calls from two threads both pass `if (closed) return` (not atomic) and the second `thread.call` after `executor.shutdown()` throws `RejectedExecutionException` out of `close()`.
- Fix sketch: in `close()`, when the current thread is the script thread (or `busy > 0` on any platform), set `closeWaits = true` and return; `counted`'s `finally` already calls `closeHere()` when `busy` drops to 0. Make `closed` an atomic compare-and-set.

### EPUB-SCRIPT-09: close() blocks its caller until the running script stops, which deadlocks a UI thread that the script is waiting on
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner, EpubScriptRunner, ScriptThread actuals), docs
- Kind: design
- Severity: medium
- Confidence: likely
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:790-810`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:718-730`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/EpubScriptRunner.kt:235-249`, `kitepdf-javascript/src@jvm/io/github/yuroyami/kitepdf/javascript/ScriptThread.jvm.kt:21`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:284-291`, `docs/javascript.md:136`, `docs/javascript.md:141`
- What happens: `closeHere()` submits the engine close to the script thread and waits with `executor.submit(...).get()` (or `runBlocking { result.await() }` on native) behind whatever the thread is doing. A running script stops at its next deadline check, within about 10,000 interpreter checks, but a script inside a host function does not: `__kite.alert` calls `hooks.onAlert` and waits for its answer, which is "which button the reader pressed", so a real host posts a dialog to the UI thread and blocks the script thread until the reader answers. The docs show `DisposableEffect(runner) { onDispose { runner.close() } }`, which Compose runs on the UI thread when the view leaves, for example on an Android configuration change. Then the UI thread blocks in `close()` on the script thread, which blocks on the UI thread's dialog: a deadlock and an ANR. The same holds for any slow host function (`pageWords` extracting text from a large page, a `rect` re-layout in the EPUB session) and for the suspend event methods, which also block their caller (`pausingCall` → `onScriptThread` → `thread.call`), so a host that calls `runner.keystroke` from `Dispatchers.Main` blocks the UI for up to `budgetMillis`.
- What should happen: `close()` returns at once and the engine closes when the script thread next runs, or the docs say `close()` may block for the length of a host callback and must not be called from the thread that answers one. A suspend method should suspend rather than block, or the docs' thread section should say it blocks.
- How to reproduce or evidence: `onAlert = { alert -> runBlocking(Dispatchers.Main) { showDialogAndAwait(alert) } }`, a document open script with `app.alert("hi")`, then `runner.close()` from the main thread while the dialog is up: `close()` never returns. `RunnerLifecycleTest.closing_the_runner_stops_a_script_that_never_ends` covers only a script in a loop, where the deadline check reaches it.
- Cross-effects: `policy.onStillRunning` is documented as "A viewer can ask the reader, as a browser does" (javascript.md:118) and runs on the script thread with the same constraint.
- Fix sketch: make `close()` set `closed` and hand the engine close to the script thread without waiting (the thread is private to the runner, so nothing else needs the engine gone synchronously), and let `onAlert` and `onResponse` be `suspend` or document that they must not depend on the thread that called the runner.

### EPUB-SCRIPT-10: Every engine may allocate 512 MB and nothing in either policy can lower it, so eight live chapters plus their frames may take gigabytes
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

### EPUB-SCRIPT-11: util.printf writes the wrong separators for styles 2, 3 and 4
- Repo: KitePDF
- Area: kitepdf-javascript (AcrobatApi util.printf)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:142-168`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:223-242`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:24-32`
- What happens: `groupThousands(text, separator)` maps the `,n` flag to a group separator with `sep = separator === '1' ? '' : separator === '2' ? '.' : separator === '3' ? '' : ','`, so style `4` groups with a comma, and two dead lines follow (`if (separator === '2') sep = '.'` and the no-op at line 226). The decimal mark is never changed: `rest` keeps the `.` that `toFixed` wrote. So `util.printf("%,2.2f", 1234.5)` gives `1.234.50` and `util.printf("%,3.2f", 1234.5)` gives `1234.50`. `AformApi.separators` in the same module maps the same styles correctly (2 → group `.` and decimal `,`; 3 → decimal `,`; 4 → group `'`), so the two halves of one engine disagree.
- What should happen: Acrobat JavaScript Reference, `util.printf`, the `,` flag: 0 → `1,234.56`, 1 → `1234.56`, 2 → `1.234,56`, 3 → `1234,56`, and the apostrophe style of `AFNumber_Format` where it is accepted.
- How to reproduce or evidence: `runner.run(PdfAction.JavaScript("getField('a').value = util.printf('%,2.2f', 1234.5)"))` and read `formState.value("a")`: `1.234.50`.
- Cross-effects: a document script that formats totals itself with `util.printf` for a European locale shows two periods.
- Fix sketch: reuse `separators(sepStyle)` from AformApi (expose it on `util` or move it into AcrobatApi) and replace the decimal point with its `decimal` before grouping.

### EPUB-SCRIPT-12: AFNumber_Format prepends a minus sign for negStyle 1 and rounds half-cents differently from Chrome
- Repo: KitePDF
- Area: kitepdf-javascript (AformApi)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:83-103`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:106-118`; PDFium `cjs_publicmethods.cpp`, `AFNumber_Format` (the `bNegative` block: `if (iNegStyle == 0) Value.InsertAtFront('-'); else if (iNegStyle == 2 || iNegStyle == 3) parens`), `kDoubleCorrect` and `CalculateString`
- What happens: `formatNumber` writes `-` for `negStyle` 0 and 1 (`case 0: case 1: text = '-' + text`). PDFium writes the sign for style 0 only; style 1 is "red, no sign", which is also what Acrobat's number format dialog shows for its second negative style. KitePDF also ignores `textColor` (PdfScriptHost.kt:156-158), so a style-1 field in KitePDF shows `-1,234.56` where Chrome shows `1,234.56` in red. The code comment at AformApi.kt:112 shows the author knows which styles ask for red, so the extra sign may be deliberate compensation; if so it should be documented. Rounding: `toFixed` rounds the exact binary value, PDFium adds `kDoubleCorrect = 1e-15` before `%.{n}f`, so `1.005` with two decimals is `1.00` here and `1.01` in Chrome, and `2.675` is `2.67` here and `2.68` there; the half-cent prices of unit-price forms hit this.
- What should happen: match the oracle: no sign for style 1, and the same nudge before rounding (or a decimal rounding that rounds the printed decimal, not the binary double).
- How to reproduce or evidence: field with format `AFNumber_Format(2, 0, 1, 0, "", true)` and value `-1234.56`: `formattedValue` is `-1,234.56`; with value `1.005` and style 0: `1.00`.
- Cross-effects: `AFPercent_Format` shares `formatNumber`, so `AFPercent_Format(2, 0)` of `0.01005` gives `1.00%` here and `1.01%` in Chrome.
- Fix sketch: `case 0: text = '-' + text; case 1: break;` and `Math.abs(n) + 1e-15` before `toFixed` when `nDec > 0`, as PDFium does.

### EPUB-SCRIPT-13: AFSpecial_Format leaves partial phone and SSN input unformatted, and AFSimple_Calculate does not round to six decimals
- Repo: KitePDF
- Area: kitepdf-javascript (AformApi)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:193-214`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:254-281`; PDFium `cjs_publicmethods.cpp`, `AFSpecial_Format` (masks `99999`, `99999-9999`, `(999) 999-9999` or `999-9999`, `999-99-9999` through `StringPrintx`) and `AFSimple_Calculate` (`dValue = floor(dValue * powf(10, 6) + 0.49) / powf(10, 6)`)
- What happens: PDFium formats every special style through `printx` with a mask, so `12345678` as a phone becomes `123-4567` (the eighth digit dropped) and `12345` as an SSN becomes `123-45`; KitePDF formats a phone only when it has exactly 10 or 7 digits and an SSN only at 9, and leaves anything else as typed. The zip styles match. `AFSimple_Calculate` in PDFium rounds its result to six decimals; KitePDF stores the raw double, so `SUM` of `0.1` and `0.2` stores `0.30000000000000004`, which a field without a format script shows as is and which `exportAsFDF`-style consumers would write.
- What should happen: the same `util.printx` masks (the module already has `printx`) and the same six-decimal rounding.
- How to reproduce or evidence: `AFSpecial_Format(2)` with `event.value = "12345678"`: KitePDF `12345678`, Chrome `123-4567`. `AFSimple_Calculate("SUM", "a, b")` with `0.1` and `0.2`: KitePDF `0.30000000000000004`, Chrome `0.3`.
- Cross-effects: none seen.
- Fix sketch: `e.value = g.util.printx(mask, e.value)` with PDFium's mask choice, and `result = Math.floor(result * 1e6 + 0.49) / 1e6`.

### EPUB-SCRIPT-14: PdfScriptRunner.keystroke takes a `commit` parameter that does nothing
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner public API)
- Kind: api
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:397-403`
- What happens: the public blocking `keystroke(fieldName, change, selectionStart, selectionEnd, commit: Boolean = false)` forwards four arguments to `keystrokeHere` and drops `commit`. A caller passing `commit = true` expects the willCommit keystroke (and, by the KDoc of the method above, perhaps the validate round) and gets a plain keystroke with `willCommit = false`. The parameter is part of the published ABI (`api/`), so it cannot be removed without an API change.
- What should happen: either run the commit path (`commitOnThread`) when `commit` is true, or deprecate the parameter and say it is ignored.
- How to reproduce or evidence: `runner.keystroke("a", "x", commit = true)` with a keystroke script `event.rc = event.willCommit`: the result is refused, because `willCommit` was false.
- Cross-effects: none seen.
- Fix sketch: `if (commit) KeystrokeResult(setFieldValue(fieldName, mergedValue(...)), ...)` or `@Deprecated` with a note.

### EPUB-SCRIPT-15: A call admitted just before close() can reopen an engine that nothing ever closes
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner, ScriptThread.jvm/native)
- Kind: concurrency
- Severity: low
- Confidence: suspected
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:107-111`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:209-236`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:797-806`, `kitepdf-javascript/src@jvm/io/github/yuroyami/kitepdf/javascript/ScriptThread.jvm.kt:19-26`
- What happens: `onScriptThread` checks `closed` and then submits to the executor; the two steps are not atomic. A call that passes the check while another thread runs `close()` can be queued after the close block (whose `finally` sets `openEngine = null` and calls `thread.leave`) but before `executor.shutdown()` takes effect for it (a task submitted before `shutdown()` still runs). That call then runs `ensureEngine()`, finds `openEngine == null`, opens a fresh `KiteJsScriptEngine`, re-runs the document scripts because `started && documentScriptsRan`, runs the event, and returns. The runner is closed, so nothing can ever close that engine: its QuickJS runtime stays allocated in a dead daemon thread's slot. On native the same window exists between `check(!closed)` and `tasks.trySend`.
- What should happen: a call that loses the race fails with the closed error, as `ScriptThreadTest.a_closed_runner_refuses_calls_instead_of_hanging` expects, without opening anything.
- How to reproduce or evidence: a stress test with one thread calling `runner.mouseUp("b")` in a loop and another calling `close()`; look for an engine open after `close()` returns (a `KiteJs` instance reachable from the executor thread's slot). Not run here.
- Cross-effects: EPUB-SCRIPT-08.
- Fix sketch: check `closed` again inside the submitted block (`counted` could `check(!closed)` on the script thread before `ensureEngine`), and make `closeHere`'s block set a `closedForGood` flag that `ensureEngine` honours.

### EPUB-SCRIPT-16: calculateNow inside a calculate script recurses to the stack limit, and app.calculate is ignored
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

### EPUB-SCRIPT-17: A form without /CO runs every calculate script in field order, which no reference viewer does
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner.calculationOrder)
- Kind: design
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:733-751`
- What happens: when `/AcroForm /CO` is absent or empty, `calculationOrder()` answers every field with a calculate script in the order of `formFields`. PDFium's `CPDFSDK_InteractiveForm::OnCalculate` iterates `CountFieldsInCalculationOrder()`, which answers the size of `form_dict_->GetArrayFor("CO")` or 0 (`core/fpdfdoc/cpdf_interactiveform.cpp:738-744` of the fetched copy), so it runs nothing (and it runs calculate scripts of text and combo fields only); pdf.js's `calculateNow` returns when `_calculationOrder` is null (from memory, not re-read here); Acrobat writes `/CO` whenever it saves a calculate script and the "Set Field Calculation Order" dialog is the only order it uses. ISO 32000-1 §12.7.2 defines `/CO` as the order of "fields with calculation actions that shall be recalculated", with no fallback. The leniency is defensible for hand-made files, but it is undocumented and a file whose author removed a field from `/CO` to switch its calculation off calculates in KitePDF and nowhere else.
- What should happen: match the references, or document the fallback in `docs/javascript.md` next to the `/CO` claim and keep it.
- How to reproduce or evidence: a form with a calculate script and no `/CO`: KitePDF computes the total, Chrome leaves it empty.
- Cross-effects: `FormScriptTest.a_calculate_script_that_adds_two_fields_by_hand` may rely on the fallback.
- Fix sketch: a documented policy flag, default off, or a line in the docs.

### EPUB-SCRIPT-18: Leaving a text field commits it even when nothing was typed, so validate, every calculation and every format run on each focus change
- Repo: KitePDF
- Area: kitepdf-compose-viewer (KiteDocViewState.blurTextField), kitepdf-javascript (commitOnThread)
- Kind: performance
- Severity: low
- Confidence: likely (KitePDF's behaviour is verified; the reference behaviour of Acrobat and PDFium's `IsDataChanged` gate is from memory)
- Where: `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:840-862`, `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteFormLayer.kt:221`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:434-466`
- What happens: `blurTextField` calls `handler.commit(name, typed ?: handler.formState.value(name) ?: "")` whenever the field loses the caret, including when `editingText` is null because nothing was typed. `commitOnThread` then runs the willCommit keystroke, the validate script, `calculateAll` and `formatAll`. `handleWidgetTap` blurs the focused field before any tap on another widget (KiteFormLayer.kt:221), so tapping into a field and tapping a check box runs the field's validate script (which may `app.alert` on an invalid stored value) and every calculation of the form. Acrobat and PDFium run validate and calculate only when the value changed ("the user has changed the value", Acrobat JavaScript Reference, Validate event); format runs on blur regardless.
- What should happen: commit only when `typed != null && typed != formState.value(name)`, and run `blur` alone otherwise.
- How to reproduce or evidence: a field with validate script `app.alert("validated")` and a stored invalid value; tap into it and tap elsewhere without typing: the alert shows.
- Cross-effects: EPUB-SCRIPT-03 multiplies the cost by the number of format scripts.
- Fix sketch: `if (commit && typed != null && typed != handler.formState.value(name))`.

### EPUB-SCRIPT-19: Each measurement after a change lays the chapter out again and announces the change to every viewer, inside the script's turn
- Repo: KitePDF
- Area: kitepdf-epub (EpubScriptSession rect, EpubDocument.replaceChapterTree, EpubParse.announceTreeChange)
- Kind: performance
- Severity: low
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:806-813`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:590-596`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:318-323`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:335-342`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:390-392`
- What happens: `__kite.rect` calls `commit()` when the tree is dirty, which snapshots the whole tree, rebuilds the spine's styles, paginates the whole chapter and bumps `treeChanges`, so the viewer re-counts pages and redraws. A script that measures after each of N changes (the usual `el.style.width = ...; el.offsetWidth` layout loop, or a library that measures every node it inserts) pays N full chapter layouts and N announcements inside one call, with the reader's page re-rendered up to N times. A browser's layout is incremental and its paint is one per frame. The budget bounds the time (the deadline is checked between host calls), but a 300-page chapter costs hundreds of milliseconds per layout, so a handful of measurements eat the whole 5 s and the script is stopped with a half-made DOM.
- What should happen: the layout for a measurement should not announce to viewers (the turn's final `commit()` does), and ideally the measurement should lay out only the chapter's flow once per dirty tree.
- How to reproduce or evidence: a chapter script `for (var i = 0; i < 50; i++) { p.style.marginTop = i + 'px'; p.getBoundingClientRect(); }` on a long chapter; time `chapterOpened` and count `chapterChanges` emissions.
- Cross-effects: `transformValue` reads `dom.toLayout[el]` without a commit, so `getComputedStyle(el).transform` with a percentage is measured on the box before the change while `getBoundingClientRect` is measured after it.
- Fix sketch: a `commit(announce = false)` for `rect`, with `announceTreeChange()` once at the end of the host call.

### EPUB-SCRIPT-20: Event objects expose their state as writable own properties and can be dispatched while dispatching
- Repo: KitePDF
- Area: kitepdf-epub (DomPrelude events)
- Kind: bug
- Severity: info
- Confidence: verified
- Where: `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomPrelude.kt:629-645`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomPrelude.kt:620-624`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomPrelude.kt:722-736`
- What happens: `Event` sets `type`, `target`, `currentTarget`, `eventPhase`, `defaultPrevented`, `isTrusted`, `timeStamp` as plain own data properties, so `Object.keys(new Event('x'))` lists them (a browser lists only `isTrusted`), `JSON.stringify(event)` serializes them, a script can assign `event.target = other` or `event.defaultPrevented = false`, and `'type' in Event.prototype` is false. `dispatchEvent` does not check the dispatch flag, so a listener that calls `target.dispatchEvent(event)` with the event being dispatched re-enters `dispatchSteps`, overwrites `event.target` and `eventPhase`, and shares `__stop`; the DOM Standard (2.9, dispatch) throws `InvalidStateError`. A script that passes the event into a `structuredClone` or a `postMessage` also clones the extra fields.
- What should happen: DOM Standard 2.2: the attributes are readonly accessors on `Event.prototype`; `isTrusted` is a `[LegacyUnforgeable]` own accessor; `dispatchEvent` throws `InvalidStateError` while the event's dispatch flag is set.
- How to reproduce or evidence: `JSON.stringify(new Event('a'))` is `{"type":"a","bubbles":false,...}` here and `{"isTrusted":false}` in Chrome.
- Cross-effects: none seen.
- Fix sketch: keep the state in a `WeakMap` as the prelude does for `DOMException`, with getters on the prototype, and a `dispatching` flag.

### EPUB-SCRIPT-21: On the web, a blocking run() or setFieldValue() during a paused script fails silently, which the docs do not say
- Repo: KitePDF
- Area: kitepdf-javascript (PdfScriptRunner), docs
- Kind: docs
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:107-111`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:694-707`, `/home/user/KiteJS/kitejs-api/src/io/github/yuroyami/kitejs/api/KiteJs.kt:66-68`, `docs/javascript.md:233-235`
- What happens: the docs say "The other methods, such as `run` and `setFieldValue`, never pause", which is true, but on the web with stack switching a suspend method's script may be paused when a host calls `run()`. `onScriptThread` does not take the `turns` mutex, so the call reaches `engine.evaluate` while the engine is paused; KiteJS throws `JsEngineError("this engine is paused in a script...")`, `evaluate` records it in `failures` and answers null. The host sees `null` and a failure entry, not a refusal it can retry. Nothing in the docs or the KDoc of `run` says a blocking call can fail this way.
- What should happen: the docs' Threads section says a blocking method fails (and how) while a suspend method's script is paused, or the blocking methods throw a distinct exception instead of recording a failure.
- How to reproduce or evidence: Chrome 137 or Node 25, a long open script started with `documentOpened()`, then `runner.run(action)` from a timer: `failures.last().message` holds "paused in a script".
- Cross-effects: none seen.
- Fix sketch: one sentence in the docs, or `check(!pausing)` in `onScriptThread` on the web with an `IllegalStateException`.

### EPUB-SCRIPT-22: AFNumber_Keystroke accepts exponent and sign characters and either decimal mark, where Chrome accepts digits, one sign and the style's mark
- Repo: KitePDF
- Area: kitepdf-javascript (AformApi)
- Kind: bug
- Severity: low
- Confidence: verified
- Where: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:77-81`, `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:120-130`; PDFium `cjs_publicmethods.cpp`, `AFNumber_Keystroke` (the loop over `wstrChange` rejects anything but a digit, one `cSep` of `DecimalMarkForStyle(iSepStyle)` and a leading `-`)
- What happens: for a keystroke that does not commit, `isNumberText(merged)` accepts `^[+-]?(\d+([.,]\d*)?|[.,]\d+)([eE][+-]?\d+)?$`, so a number field takes `1e5`, `+5`, and both `.` and `,` whatever `sepStyle` says, and a `-` typed in the middle of a selection is accepted when the merged text happens to parse. PDFium rejects `e`, `+`, a second separator, a separator of the other style, and a `-` anywhere but position 0. The field then holds `1e5`, which `AFNumber_Format` formats as `100000.00` here while Chrome never let it in.
- What should happen: the per-character rules of the oracle, with the separator from `separators(sepStyle).decimal`.
- How to reproduce or evidence: a field with keystroke `AFNumber_Keystroke(2, 0, 0, 0, "", true)`; `runner.keystroke("a", "e")` after `1` is accepted.
- Cross-effects: `AFPercent_Keystroke` shares it.
- Fix sketch: port the PDFium loop: digits, one decimal mark of the style, `-` only at the start.

### Capabilities and opportunities (not defects)

- A `<script>` element a script inserts (`createElement('script')` with `src`, or `document.write('<script src=...>')`) never runs and never gets `load` or `error` (`DomPrelude.kt:1734-1740` only records `forceAsync`; no code path evaluates an inserted script). Loaders that wait on `script.onload` (MathJax, polyfill loaders, many "reader hacks") wait for ever with no failure recorded. `defer` and `async` are also ignored: every classic script runs in document order (`EpubScriptSession.kt:634-642`), so a deferred script runs before a later inline one that a browser would run first. Reach: any book with a loader; cost: moderate (run inserted scripts from the zip through `runScript`, fire the events as tasks, and order `defer` after the parse).
- `imageSmoothingEnabled` and `imageSmoothingQuality` are stored (`CanvasContext.kt:73-74`) and never applied to the SVG the bitmap keeps (no `image-rendering`), so pixel-art canvases blur. Cheap: `image-rendering: pixelated` on the `<image>` when smoothing is off.
- `getPageBox(which, page)` always answers the crop box (`PdfScriptHost.kt:81`, `:276-280`); `getPageNthWordQuads`, `getAnnots`, `getLinks`, `addField` answer empty. A viewer that shows scripted annotations would need them; cost moderate.
- `Field.value` of a text field reads as a number when it looks like one (`AcrobatApi.kt:304-316`), which matches Acrobat (and loses leading zeros as Acrobat does); `valueAsString` is there. Worth a line in the docs' object table, since the pdf.js and PDFium behaviours differ.
- `EpubScriptRunner` and `PdfScriptRunner` have no memory or allocation policy (EPUB-SCRIPT-10) and no way to cap the number of live chapter engines from the policy (`LIVE_CHAPTERS` is a `const val`).
- The PDF runner's timers do not honour a clear from an earlier timer in the same pump (`PdfScriptHost.dueTimers` snapshots the due list at `:364-371`); the EPUB prelude does (`timerTask` checks the live list, `DomPrelude.kt:2174-2188`). Cheap to align.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane KITEJS lane: the KiteJS repository

Every finding below is a KiteJS defect and must be fixed in /home/user/KiteJS. Paths are relative to that repository unless they start with `kitepdf-`, which are KitePDF paths named only under Cross-effects. Line numbers are from the checkout at commit 5882331 (2026-10-22).

### KITEJS-01: Rhino never asks `interruptWhen` once an `instructionBudget` is set, so cancellation and deadlines are silently ignored until the budget runs out
- Repo: KiteJS
- Area: kitejs-rhino facade (`RhinoKiteJs`, `EngineFactory`), kitejs-coroutines
- Kind: bug
- Severity: high
- Confidence: verified (read the whole path)
- Where: kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/RhinoKiteJs.kt:366-373, kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/RhinoKiteJs.kt:417-435, kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/Context.kt:377-389, kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt:284-307, docs/limits.md:43, docs/engines.md:39
- What happens: `RhinoKiteJs.open` sets the instruction observer threshold to the budget itself when one is set (`setInstructionObserverThreshold(if (config.instructionBudget > 0) config.instructionBudget else INTERRUPT_SLICE)`). `Context.addInstructionCount` calls the observer only once the pending count reaches that threshold, and hands it the whole pending count. `EngineFactory.observeInstructionCount` adds that count to `instructionsUsed`, finds it `>= budget` on the very first observer call of every outermost call, and throws the budget `JsEngineError` before the `interruptWhen` hook is read (the hook is consulted only after the budget test, line 424). So with `instructionBudget > 0` the hook is never asked on Rhino. `asyncKiteJs` installs its cancellation check through this hook (AsyncKiteJs.kt:302-307), so `asyncKiteJs(Rhino) { instructionBudget = 50_000_000 }` followed by `job.cancel()` keeps the script running until 50 million instructions have passed, and a `KiteJs(Rhino) { instructionBudget = N; interruptWhen = deadline }` ignores the deadline. QuickJS behaves differently: `interrupt_handler` in native/kitejs_quickjs.c:163-190 asks the host at every poll regardless of the budget. No test sets both settings together (the grep over kitejs-testkit, kitejs-rhino/test and kitejs-coroutines/test finds `interruptWhen` only without a budget), so this is green.
- What should happen: the hook is asked at its own cadence whatever the budget is. docs/limits.md:43 promises "The hook is asked from inside the running script every hundred thousand instructions or so", docs/engines.md:39 says `instructionBudget` and `interruptWhen` "mean the same on both" engines, and the KDoc of `asyncKiteJs` (AsyncKiteJs.kt:284-286) says a budget "is set for you when you do not name one", which the code never does.
- How to reproduce or evidence: `var asked = 0; KiteJs(Rhino) { instructionBudget = 5_000_000; interruptWhen = { asked++; true } }.use { it.evaluate("for (;;) {}") }`; the call ends with "script used more than 5000000 instructions", and `asked` is 0. On QuickJS the same program ends with "script was interrupted" after a few hundred checks. For the coroutine path: `asyncKiteJs(Rhino) { instructionBudget = Int.MAX_VALUE }`, launch `evaluate("for (;;) {}")`, cancel the job: the script runs for minutes.
- Cross-effects: `kitejs-coroutines` cancellation on Rhino; any Rhino embedder that combines a budget with a stop button or a deadline. KitePDF is not affected (kitepdf-javascript uses QuickJs only, kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/KiteJsScriptEngine.kt:161).
- Fix sketch: keep the observer threshold at `min(budget, INTERRUPT_SLICE)` (or at `INTERRUPT_SLICE` whenever a hook is set) and let `observeInstructionCount` accumulate toward the budget across several observer calls; add a `ConfigContract` test that sets both and asserts the hook was asked before the budget fires; fix the three doc sentences.

### KITEJS-02: The instruction budget, the interrupt hook and a wall-clock deadline do not reach a long loop inside a built-in, on either engine
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

### KITEJS-05: `JsValue` is a value class, so the whole value-reading API vanishes from Swift and Objective-C
- Repo: KiteJS
- Area: kitejs-api (`JsValue`), Apple frameworks (tools/apple_frameworks.py)
- Kind: api
- Severity: medium
- Confidence: verified (read the declaration and the klib API dump; Kotlin/Native maps inline classes to their underlying type in ObjC export)
- Where: kitejs-api/src/io/github/yuroyami/kitejs/api/JsValue.kt:47-54, kitejs-api/api/kitejs-api.klib.api:364-373, tools/apple_frameworks.py:19-27, README.md "Targets"
- What happens: `JsValue` is `@JvmInline value class JsValue(val raw: Any?)`. Kotlin/Native's Objective-C export does not export inline classes: every function that returns or takes one is exported with the underlying type, so `KiteJs.evaluate` returns `id _Nullable` in Swift, and `type`, `asString()`, `asDouble()`, `asObject()`, `toKotlin()`, `isThenable` and the rest do not exist there. A Swift caller gets back a raw `Any?` holding `JsUndefined`, a `KotlinDouble`, a `String`, a `KBigInt` or a `QuickJsObject`, and has to reproduce the engine's coercion rules by hand. The repository ships `tools/apple_frameworks.py` to build XCFrameworks for exactly this audience, and the README lists iOS and macOS as targets "for both engines".
- What should happen: the Apple frameworks should expose the value API. Either make `JsValue` a regular class on Apple (expect/actual cannot change inline-ness, so this means a regular class everywhere, at the cost of one allocation per value), or ship a Swift-facing facade (`KiteJsSwift` object with `typeOf(_:)`, `asString(_:)`, and so on, or SKIE), and document what Swift sees.
- How to reproduce or evidence: build `KiteJSApi.framework` with `python3 tools/apple_frameworks.py --platforms macosArm64` and read the generated header: `- (id _Nullable)evaluateSource:(NSString *)source fileName:(NSString *)fileName` and no `JsValue` class.
- Cross-effects: `JsError.value`, `JsObject.get`, `JsArray.values()`, `onSettled`'s callback arguments and `AsyncKiteJs.evaluate` all carry `JsValue`; `AsyncKiteJs.await` is exported with a mangled name (`await-gF0PohQ`) for the same reason (kitejs-coroutines/api/jvm/kitejs-coroutines.api).
- Fix sketch: add a Swift-friendly reader object with the `JsValue` members as functions over `Any?`, export it from the frameworks, and say so in docs/getting-started.md.

### KITEJS-06: `kitejs-coroutines` is tested with Rhino only, so `asyncKiteJs(QuickJs)` never runs in CI on any platform
- Repo: KiteJS
- Area: kitejs-coroutines tests, .github/workflows/quickjs.yml
- Kind: test-gap
- Severity: medium
- Confidence: verified
- Where: kitejs-coroutines/module.yaml:33-36, kitejs-coroutines/test/io/github/yuroyami/kitejs/coroutines/AsyncKiteJsTest.kt:12-27, kitejs-coroutines/test@jvm/io/github/yuroyami/kitejs/coroutines/CancellationTest.kt:8-30, .github/workflows/quickjs.yml:9-33
- What happens: the module's only test dependency on an engine is `../kitejs-rhino` ("Any engine will do for the tests"), every test file imports `Rhino`, and the QuickJS workflow does not even trigger on a change under `kitejs-coroutines/`. The two engines differ in exactly what the coroutine layer relies on: how the interrupt hook is scheduled (KITEJS-01), that QuickJS host functions throw an uncatchable error and surface `hostFailure` only at the outermost call, that QuickJS drains with its own job queue, that `evaluatePausing` suspends inside the engine on the web, and that `close()` frees a C runtime. None of that is exercised through `AsyncKiteJs`, `suspendFunction`, `deferredToPromise` or `await`.
- What should happen: the coroutine suite runs against both engines, the way `kitejs-testkit` contracts are instantiated twice.
- How to reproduce or evidence: `grep -rn QuickJs kitejs-coroutines/test*` finds nothing.
- Cross-effects: a QuickJS-specific regression in cancellation or in host-promise settling would not be seen before a release.
- Fix sketch: turn the coroutine tests into an abstract suite parameterised by `JsEngine`, instantiate it for Rhino and QuickJS, add `../kitejs-quickjs` as a test dependency, and add `kitejs-coroutines/**` to the paths of quickjs.yml.

### KITEJS-07: A script that throws leaves its queued Promise jobs in place, and they run inside the next unrelated call
- Repo: KiteJS
- Area: kitejs-quickjs facade (`guarded`), kitejs-rhino facade (`metered`)
- Kind: design
- Severity: low
- Confidence: verified
- Where: kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:254-271, kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/RhinoKiteJs.kt:162-217
- What happens: the outermost call drains the job queue only when `body()` returned normally (`if (outermost && drain && drainJobs() < 0) raise()` after `body()`), and discards it only when the engine stopped the script. When the script itself throws, the jobs it queued before throwing are neither run nor dropped: they sit in the queue and run during the next call into the engine, whatever that call is. In a browser the microtask checkpoint after a script that threw still runs them. Both engines agree with each other, so the contract suites pass, but the observable order differs from a browser: `evaluate("Promise.resolve().then(() => log.push('reaction')); throw 1")` then `evaluate("log.push('next'); log.join()")` answers `reaction,next` only because the second call ran the first call's job before its own body ran.
- What should happen: the jobs queued by a call run (or are dropped, if the owner prefers) before that call returns, success or failure, as the KDoc on `KiteJs` promises: "Every outermost call ... runs the Promise reactions it queued before it returns".
- How to reproduce or evidence: the two `evaluate` calls above, on either engine; a `log` of `['reaction', 'next']` with the reaction executed during the second call (observe with a host function that records the call depth).
- Cross-effects: KitePDF's EPUB runner evaluates many scripts per chapter; a throwing script's reactions run at the start of the next timer or event dispatch.
- Fix sketch: in `guarded`/`call`, drain in the `catch` path too when the throwable is a `JsError` (a script exception) and the engine did not stop; keep discarding on a stop.

### KITEJS-08: The JVM library loader has no fallback when `System.load` refuses the shared copy, so a second class loader or a `noexec` temp folder cannot use QuickJS
- Repo: KiteJS
- Area: kitejs-quickjs (`NativeLibrary.kt`)
- Kind: design
- Severity: low
- Confidence: verified
- Where: kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/NativeLibrary.kt:30-38 and :64 (orchestrator corrected the line numbers)-207, 214-247, 266-273
- What happens: the loader extracts the library once per build under `java.io.tmpdir/kitejs-quickjs-<user>/<sha256 prefix>/` and calls `System.load` on that one path. The JVM refuses to load the same file into two class loaders ("Native Library ... already loaded in another classloader"), which is what happens in an application server, an IDE plugin host, or a test harness that isolates class loaders; and a temp folder mounted `noexec` makes `System.load` fail outright. `freshCopy` (a private folder per process) exists but is used only when the shared folder cannot be made private, and there is no property to choose the extraction folder (`kitejs.quickjs.library` requires a library the user built).
- What should happen: on `UnsatisfiedLinkError` from the shared path, fall back to `freshCopy` (a distinct file loads into a distinct class loader), and let a system property such as `kitejs.quickjs.tmpdir` choose the folder.
- How to reproduce or evidence: load `kitejs-quickjs` through two `URLClassLoader`s in one JVM and open an engine from each; the second throws `UnsatisfiedLinkError`.
- Cross-effects: none seen.
- Fix sketch: wrap `System.load(target)` in a try that retries with `freshCopy`; read an optional property for the base folder; mention both in docs/engines.md.

### KITEJS-09: `DecimalParser.exactPath` builds a big integer from every significant digit, so a very long numeric string parses in quadratic time
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

### KITEJS-10: `Date.parse` accepts the expanded year `-000000`, which ECMAScript forbids
- Repo: KiteJS
- Area: kitejs-rhino (`NativeDate.parseISOString`)
- Kind: bug
- Severity: low
- Confidence: likely (read the parser; the oracle run may already list it as an accepted difference)
- Where: kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeDate.kt:627-631 and 734-750
- What happens: a leading `-` sets `yearlen = 6` and `yearmod = -1`; six zero digits give `year = 0`, and the validity check only tests `year > 275943`, so `-000000-01-01T00:00:00Z` parses as year 0. ECMA-262 21.4.1.32 (Date Time String Format, Expanded Years): "−000000 is invalid". V8 and QuickJS answer NaN.
- What should happen: NaN for a six-digit zero year with a minus sign.
- How to reproduce or evidence: `Date.parse('-000000-01-01T00:00:00Z')` is `-62167219200000` here, `NaN` in Chrome.
- Cross-effects: none seen.
- Fix sketch: after reading the year, `if (yearlen == 6 && yearmod < 0 && year == 0) return NaN`.

### KITEJS-11: `kite_new`'s failure path frees a table whose `next` array is NULL or uninitialised
- Repo: KiteJS
- Area: kitejs-quickjs native glue
- Kind: bug
- Severity: low
- Confidence: verified
- Where: kitejs-quickjs/native/kitejs_quickjs.c:346-350, 365-367, 376-382
- What happens: `kite_new` sets `e->capacity = 64` before the two `malloc` calls and jumps to `fail` when either fails. `kite_free` then runs `for (i = FIXED_HANDLES; i < e->capacity; i++) if (e->next[i] == -2) JS_FreeValue(e->ctx, e->slots[i])` with `e->ctx` set: with `next == NULL` it dereferences NULL, and with `next` allocated but the loop at 352-356 not yet run it reads uninitialised memory and may free garbage. Only a failed 256-byte allocation reaches it, so this is a correctness note rather than a live crash.
- What should happen: `capacity` is set only once both arrays exist and are initialised, or `kite_free` checks `e->next`.
- How to reproduce or evidence: code reading only.
- Cross-effects: none seen.
- Fix sketch: move `e->capacity = 64` after the initialisation loop, and guard the loop in `kite_free` with `if (e->next)`.

### KITEJS-12: `KiteJs.newPromise()` compiles its helper script on every call, so each `suspendFunction` invocation pays a parse
- Repo: KiteJS
- Area: kitejs-api (`JsPromiseSupport`), kitejs-coroutines (`suspendFunction`)
- Kind: performance
- Severity: low
- Confidence: verified
- Where: kitejs-api/src/io/github/yuroyami/kitejs/api/JsPromiseSupport.kt:30 (orchestrator corrected the line number)-298, kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt:163-175
- What happens: `newPromise()` is `evaluate(PROMISE_MAKER, "<promise>")`, a compile plus a run. `AsyncKiteJs.suspendFunction` calls it inside the bound host function, so a script that calls a suspending host function in a loop compiles the same 100-character program every time (on Rhino a parse and IR build, on QuickJS a `JS_Eval`).
- What should happen: compile once per engine and run the `JsScript`, or build the promise through the engine's own API (QuickJS has `JS_NewPromiseCapability`; Rhino has `NativePromise`).
- How to reproduce or evidence: profile `for (let i = 0; i < 1e5; i++) await fetch('/a')` with a `suspendFunction` named `fetch`.
- Cross-effects: none seen.
- Fix sketch: add an abstract `newPromiseCapability()` to `KiteJs` implemented natively by each engine, and have `newPromise()` call it.

### KITEJS-13: No contract test crosses a lone surrogate or a NUL through the engines
- Repo: KiteJS
- Area: kitejs-testkit (`ValuesContract`, `HandlesContract`), kitejs-quickjs string path
- Kind: test-gap
- Severity: low
- Confidence: verified (grep over kitejs-testkit and kitejs-quickjs/test finds no `\uD800`, `\uDC00` or `\u0000` case)
- Where: kitejs-testkit/src/io/github/yuroyami/kitejs/testkit/ValuesContract.kt, kitejs-quickjs/native/kitejs_quickjs.c:471-478, 512-518, 618-645
- What happens: QuickJS strings cross as UTF-16 in both directions (`JS_NewStringUTF16`, `JS_ToCStringLenUTF16`), but source text is re-encoded as CESU-8 for `JS_Eval` (`JS_ToCStringLen2(..., true)` at line 631) and function names and file names as plain UTF-8 C strings (`JS_ToCString`, lines 632 and 789), where a NUL truncates. Nothing in the shared suites asserts that `evaluate("'\\uD800'")`, a host string holding a lone surrogate or an embedded NUL, or a function named with one, comes back unchanged on both engines and every platform (the JS and Wasm glue copies code units by hand, src@web/WasmGlue.kt:235-248). The CLAUDE.md of KitePDF already records that a lone surrogate in a Kotlin/JS literal becomes `?`, which is the kind of platform difference this would catch.
- What should happen: a `ValuesContract` test that round-trips a lone high surrogate, a lone low surrogate, a NUL and a non-BMP pair through `evaluate`, `global[...]`, a host function argument and a host function name.
- How to reproduce or evidence: absence of the test.
- Cross-effects: KitePDF's EPUB text reaches scripts through these strings.
- Fix sketch: add the test to the shared contract, built with `charArrayOf(...).concatToString()` as the KitePDF gotcha advises.

### KITEJS-14: Two concurrent `QuickJs.load()` calls on the web both decompress and instantiate the module
- Repo: KiteJS
- Area: kitejs-quickjs web glue
- Kind: performance
- Severity: low
- Confidence: verified
- Where: kitejs-quickjs/src@web/io/github/yuroyami/kitejs/quickjs/bridge/WasmGlue.kt:121 (orchestrator corrected the line number)-331, kitejs-quickjs/src@web/io/github/yuroyami/kitejs/quickjs/bridge/WebBridge.kt:161-182
- What happens: `glue.load` returns early only when `exports !== null`; while the first instantiation is in flight a second call starts another gunzip and `WebAssembly.instantiate` of the 1 MB module and discards the result in its `then`. Two components calling `load()` at startup (KitePDF's `KiteJsScriptEngine.load()` from two runners, for example) double the start-up cost.
- What should happen: the pending promise is kept and returned to later callers.
- How to reproduce or evidence: call `QuickJs.load()` twice without awaiting the first; both promises resolve after two instantiations (observe with a `console.time` around `WebAssembly.instantiate`).
- Cross-effects: KitePDF calls `KiteJsScriptEngine.load()` from `PdfScriptRunner.prepare()` and the EPUB runner.
- Fix sketch: store the in-flight promise in a `loading` variable and return it when set.

### Capabilities and opportunities (not defects)
- A host function cannot throw an error a script can catch. On both engines a Kotlin exception becomes an uncatchable engine failure, so a host that implements a specified API (a DOM method that must throw `TypeError`, a `DOMException`, a `RangeError` from a bound library) has to return a marker and throw from a JavaScript wrapper, which is exactly what the QuickJS prelude does for `toLocaleString` (kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/Prelude.kt:512-525: `[0, value]`, `[1, message]`, `[2, message]` decoded by a Proxy trap). A `JsScriptError` (or a flag on `JsError`) that the facades turn into a real thrown value (`JS_Throw` on QuickJS, `JavaScriptException` on Rhino) would remove that workaround for every embedder; KitePDF's EPUB DOM bindings need it for spec-conformant `DOMException`s. Reach: every host-binding user. Cost: one new API type, a branch in `hostCall`/`LambdaFunction`, a contract test.
- Unhandled Promise rejections are invisible through the API. Rhino carries `UnhandledRejectionTracker` (off by default and reachable only through the Rhino `Context`), and the QuickJS glue never calls `JS_SetHostPromiseRejectionTracker`, so a rejected promise with no handler is silently dropped on QuickJS. A `KiteJsConfig.onUnhandledRejection: ((JsValue) -> Unit)?` would give a viewer the browser's console behaviour. Cost: small on both engines.
- The Kotlin/Native frameworks have no Swift-facing value API (KITEJS-05). A thin Swift reader object or SKIE integration would make the iOS target usable from Swift.
- `JsObject.keys` returns strings only; there is no way to read symbol-keyed or non-enumerable properties, or a property descriptor, from the host. `Object.getOwnPropertyNames`/`Reflect.ownKeys` through `call` work as a workaround.
- `AsyncKiteJs` has no `evaluatePausing`; a web host that wants a suspending engine and a pausing script has to drop to `onEngine`.
- Rhino has no memory budget (documented); a heap sampling hook, or a cap on `NativeArray`/`ArrayBuffer` allocation sizes, would give a first line of defence on platforms where a process cannot be killed (the web).

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane IMAGEKODEC: ImageKodec decoders and KitePDF's use of them

Scope read end to end: the ImageKodec facade and public types, JpegDecoder, ImageProbe, Exif, ColorChunks, PngDecoder, GifDecoder/GifLooping, BmpDecoder, Inflate and Zlib, TiffDecoder and TiffOldJpeg, CcittFax, Jbig2Decoder and MqDecoder, JpxDecoder with Jp2Headers/Jp2Color/Jp2Limits/Jp2TileGrid, WebpDecoder, Vp8Decoder and Vp8lDecoder, the colour engine (ColorManagement, SrgbConverter, IccProfile, IccLut, Cicp, ColorMath, Ink), the AVIF container and decoder front (IsoBmff, AvifContainer, AvifDecoder, Av1SequenceHeader, Av1FrameDecoder sizing, Av1Decoder saves, AvifDerived sizes), the JPEG XL front (JxlContainer, JxlDecoder, JxlEntropy, JxlModular, JxlModularFrame sizing), the Compose and Coil modules, FuzzTest, Tools and the CI env. KitePDF side: KiteImageData, KiteImageSamples, KiteImageAdapter, JpegInk, the CCITT filter, ImageRaster, ImageSampling, the EPUB/CBZ/SVG/XPS call sites, the viewer ImageDecoder actuals and the version pin. The AV1 tile decode, the JXL VarDCT/filters and the ICC curve maths were read for sizing and allocation, not sample-level correctness.

### IMAGEKODEC-01: Inflate loops forever once its output passes 1 GiB with no size hint, which a 16-bit RGBA PNG at the pixel ceiling asks for
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

### IMAGEKODEC-02: EPUB, CBZ, SVG and XPS images decode with neither EXIF orientation nor sRGB conversion, so phone photos show sideways and wide-gamut images draw oversaturated
- Repo: KitePDF
- Area: kitepdf-core/render (KiteImageData.fromEncodedImage), kitepdf-epub, kitepdf-cbz, kitepdf-svg, kitepdf-xps
- Kind: bug
- Severity: medium
- Confidence: verified (read the call sites and the facade defaults)
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:482-487, kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1603, kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/CbzPage.kt:60, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:395, kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:81
- What happens: every non-PDF container reaches `KiteImageData.fromEncodedImage(bytes)`, which calls `ImageKodec.decode(bytes)` with both defaults: `applyOrientation = false` and `colorTarget = ColorTarget.Source`. So a JPEG with an EXIF `Orientation` tag (6 = rotate 90, the normal phone-portrait case) is drawn with its stored landscape pixels, sideways; and a Display P3 or Adobe RGB image is drawn with its raw samples tagged DeviceRGB, with no conversion to sRGB. ImageKodec's own Compose and Coil bindings apply orientation and convert to sRGB by default precisely because, as their docs say, "a phone photo whose EXIF tag says rotate me is simply sideways without it" and browsers colour-manage. KitePDF's own viewer path never calls those bindings for page images; it decodes through the core and draws the RAW DeviceRGB pixels, so the viewer under-converts rather than double-converts. Browsers honour `image-orientation: from-image` by default for content images (the CSS initial value since 2020) and colour-manage tagged images, so an EPUB or SVG that renders correctly in a browser renders rotated and off-colour here. For PDF this is correct (PDF image XObjects carry no EXIF and set their own colour space), so only the four self-contained-file containers are affected.
- What should happen: a reflowable EPUB, an SVG `<image>`, an XPS image brush and a CBZ scan should honour the file's EXIF orientation and convert to sRGB through the file's embedded profile, as a browser does and as ImageKodec's Compose binding already does.
- How to reproduce or evidence: a CBZ whose pages are phone-shot JPEGs with EXIF orientation 6, or an EPUB chapter with `<img src="p3-photo.jpg">`. `fromEncodedImage` offers no orientation or colorTarget parameter, so there is no way for a caller to opt in either.
- Cross-effects: `KiteImageData.fromEncodedImage` has no `applyOrientation`/`colorTarget` overload, so fixing it means threading those through; the CBZ `ImageDims` header sniffer reads stored (pre-orientation) dimensions, so applying orientation also changes `displayWidth`/`displayHeight` and must be reflected in CbzPage's size. The CMYK and 16-bit PDF paths are unaffected (PDF has its own colour management).
- Fix sketch: add `applyOrientation` and `colorTarget` to `fromEncodedImage` (default the sRGB + orientation pair the browsers use), pass `applyOrientation = true, colorTarget = ColorTarget.Srgb()` from the EPUB/CBZ/SVG/XPS call sites, and read `ImageInfo.displayWidth/displayHeight` for CBZ page sizing.

### IMAGEKODEC-03: A JPXDecode image in a PDF ignores the image dictionary's /ColorSpace and always uses the JP2's own colour space
- Repo: KitePDF
- Area: kitepdf-core/render (KiteImageData JPEG2000 path, KiteImageSamples.jpx)
- Kind: bug
- Severity: medium
- Confidence: likely (read the path; spec direction stated below)
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:422-444, kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageSamples.kt:84-90
- What happens: for a `JPXDecode` image the lazy path builds the image from `KiteImageSamples.jpx`, which records `check.colorSpace` (the colour space the JP2 boxes declare, "DeviceRGB" or "DeviceGray") and sets `resolvedColorSpace` from it, discarding the PDF image dictionary's `/ColorSpace` entry (`resolvedCs`) entirely; the non-lazy `raw != null` branch does the same from `raw.colorSpace`. ISO 32000-1 8.9.5.4 says that for a JPXDecode image, when the image dictionary specifies a `ColorSpace` it is used and the colour space information inside the JPEG 2000 stream is ignored (pdf.js and pikepdf follow this). KitePDF does the opposite: it always trusts the JP2's own colour space. An image with a greyscale JP2 codestream but a PDF `/ColorSpace /DeviceRGB`, an Indexed palette over a JPX stream, or an ICCBased override would be drawn in the stream's space, not the PDF's. (The Indexed and masked cases are already forced down the full-decode branch, but a plain DeviceRGB-vs-DeviceGray or ICCBased override still reaches the lazy path.) The `/Decode` array is likewise dropped on the lazy JPX path (`decode = null` is never set, but it is simply not passed through `KiteImageSamples`).
- What should happen: when the PDF image dictionary carries a `/ColorSpace` for a JPXDecode image, honour it (ISO 32000-1, 8.9.5.4), falling back to the JP2's declared space only when the dictionary has none.
- How to reproduce or evidence: a PDF image XObject with `/Filter /JPXDecode` and `/ColorSpace /DeviceGray` wrapping a 3-component sRGB JP2 codestream renders in colour here rather than as grey. The existing JPX test (`KiteImageDataTest.asciihex_prefix_before_jpx_decodes_to_same_pixels_as_bare_jpx`) only checks the matching case (`colorSpace = DeviceRGB` over an RGB JP2), so the override is untested.
- Cross-effects: interacts with `/SMaskInData` (the non-lazy branch reads it correctly) and `/Decode`; a fix must decide precedence when both the PDF space and a JP2 palette are present.
- Fix sketch: in the JPEG2000 branch, when `resolvedCs != null`, carry it (and `decodeArr`) onto the resulting `KiteImageData` instead of re-deriving the space from `raw.colorSpace`, matching the DCTDecode path's `keepsSpace` logic.

### IMAGEKODEC-04: The JPEG 2000 tier-1 coder allocates a Triple (and Pair) on the heap for every coefficient of every pass
- Repo: ImageKodec
- Area: imagekodec/codec/JpxDecoder (EBCOT tier-1)
- Kind: performance
- Severity: medium
- Confidence: verified (read the loops; Kotlin Triple/Pair are heap objects for a non-inline function)
- Where: imagekodec/src/io/github/yuroyami/imagekodec/codec/JpxDecoder.kt:1088-1095 (`neighborSums` returns `Triple<Int,Int,Int>`), 1098-1117 (`signContext` returns `Pair<Int,Int>`), called from sigPropPass (1129-1135), magRefPass (1164), cleanupPass (1194, 1204, 1215-1218)
- What happens: `neighborSums` returns `Triple<Int, Int, Int>` and `signContext` returns `Pair<Int, Int>`, and both are plain (non-inline) functions, so each call allocates a heap object. They run inside the significance-propagation, magnitude-refinement and cleanup passes, which visit every coefficient of every code block for every bit plane: EBCOT tier-1 is the bulk of a JPEG 2000 decode. A modest 1-megapixel image with a dozen bit planes drives tens of millions of `Triple`/`Pair` allocations, all immediately discarded by the destructuring `val (hh, vv, dd) = ...`. On Kotlin/Native and JS, where escape analysis does not remove these, this is a large, avoidable GC load in the hottest loop, exactly the "boxing in hot loops" the project warns against.
- What should happen: the three neighbour sums and the two sign values should be produced without a per-coefficient allocation.
- How to reproduce or evidence: the signatures `private fun T1.neighborSums(x: Int, y: Int): Triple<Int, Int, Int>` and `private fun T1.signContext(x: Int, y: Int): Pair<Int, Int>`, with call sites in all three passes. Kotlin boxes the destructured return of a non-inline function.
- Cross-effects: none seen; this is a pure hot-loop allocation, correctness unchanged.
- Fix sketch: write the three sums into a reused `IntArray(3)` field on `T1` (and the sign context into two `Int` fields or an `IntArray(2)`), or inline the computation at each call site; the AV1 and JXL tier-1 equivalents already avoid tuples.

### Capabilities and opportunities (not defects)
- EPUB and CBZ never play animated images: both reach `fromEncodedImage` -> `ImageKodec.decode`, which returns only the first composited frame (grep shows no `decodeAnimation` or `KiteAnimation` use in kitepdf-epub/src or kitepdf-cbz/src). A reflowable EPUB with an animated GIF or WebP, which browsers animate, shows a still first frame here. Reach is modest (animated content in e-books), cost is moderate (a frame-playing path in the EPUB/CBZ viewer, which the viewer's own animation machinery for other formats could feed).
- The AVIF still path bounds a declared `ispe` only per side (`1..65536`, `AvifDecoder.itemSize`), not against the 2^28 pixel ceiling, so `probe` reports a 65536x65536 AVIF as decodable with a 4.3-billion-pixel declared size; the actual decode is still bounded by the `Av1FrameDecoder` guard (`miCount*16 > 2^28`), so this is only a probe-reports-too-large issue a layout caller could trust. Adding a pixel-count check in `itemSize` would make `probe` honest. Low reach.
- `ImageKodec.decodeJpegComponents` plus KitePDF's `JpegInk` give the PDF layer a clean way to read Separation/DeviceN CMYK JPEGs; the same mechanism would let KitePDF support a Separation/DeviceN JPX or a 4-component JPX, which the current lazy JPX path cannot.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane PLATFORM lane: platform canvases, Skia renderer, SVG, XPS, CBZ, media, platform actuals

Probes that back the "verified (ran it)" entries live under `/tmp/claude-0/-home-user/8fa91e2b-1e52-50dc-8511-48f93ec9d76e/scratchpad/PLATFORM/probe/` (Probe.kt, Probe2.kt, Probe4.kt), compiled against `build/tasks/_kitepdf-svg_jarJvm/kitepdf-svg-jvm.jar` and `build/tasks/_kitepdf-core_jarJvm/kitepdf-core-jvm.jar` with the toolchain's `kotlin-compiler-embeddable-2.4.21`. The native renderer and Skia findings are from reading the whole code path; nothing there was run.

### PLATFORM-01: An SVG `<use>` tree that fans out renders in k^16 steps, with no work budget to stop it
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

### PLATFORM-02: The SVG path parser misreads the compact syntax SVGO writes: arc flags without separators give a NaN curve, and `.5.5` loses both numbers
- Repo: KitePDF
- Area: kitepdf-svg (SvgImage.PathScanner, numbers)
- Kind: bug
- Severity: high
- Confidence: verified (ran it)
- Where: kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:2099-2106, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:2018-2023, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1886-1901, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1958
- What happens: `PathScanner.num()` takes every digit and dot that follows as one number. SVG's grammar (SVG 2, 9.3.9) makes an arc flag a single `0` or `1` and a number carry one fraction, so `a5 5 0 0110 10` is flags 0 and 1 followed by `10 10`, and `M.5.5` is `.5 .5`. SVGO's `convertPathData` writes both forms by default, so minified icons and Illustrator exports passed through SVGO use them throughout. Here the flags token `0110` reads as the large-arc number, `10` as the sweep flag, the endpoint runs out of numbers and reads as 0.0, and the arc maths divides by zero: the parser emits a cubic to (NaN, NaN). `1.5.5` reads as one token, `toDoubleOrNull()` gives null, and `num()` answers 0.0 for it, so the second number is lost as well. `numbers()` (used for `points`, `viewBox`, transforms and filter values) has the same greedy scan. XPS copies the scanner (kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsPaths.kt:228-237) but throws on the malformed token, so an XPS path is dropped whole instead; XPS producers write separators, so the reach there is small.
- What should happen: SVG 2, 9.3.9: a flag is exactly one character, and a number ends at the second dot (or at a sign that is not part of an exponent), so `0110 10` parses as 0, 1, 10, 10 and `.5.5` as 0.5, 0.5.
- How to reproduce or evidence: `SvgImage.parsePathData("M10 10 a5 5 0 0110 10")` gives `M(10,10) C(..NaN,NaN)` where `"M10 10 a5 5 0 0 1 10 10"` gives two cubics ending at (20,20). `parsePathData("M.5.5 l10.5.5")` gives `M(0,0) L(0,0)` where the spaced form gives `M(0.5,0.5) L(11,1)`. Run: Probe.kt in the scratchpad folder above.
- Cross-effects: `KitePath` with NaN coordinates reaches every canvas (AWT `Path2D` with NaN draws nothing or an undefined shape; CoreGraphics logs); `boundsOf` (SvgImage.kt:1495) with NaN gives a null box, so gradients and patterns on such a shape fall back silently.
- Fix sketch: In `num()`, read the flag arguments of `A` with a dedicated `flag()` that consumes one character; stop a number at a second `.`; in `numbers()` do the same. Add the two SVGO forms to `SvgImageTest`.

### PLATFORM-03: An SVG filter converts the whole raster step to floats before it shrinks to its pixel budget, so a full-page filter allocates up to 640 MB on Skia and AWT and 256 MB on the Compose viewer
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

### PLATFORM-05: A non-finite number in `feColorMatrix` or `feComponentTransfer` makes `KitePage.renderTo` throw `IllegalArgumentException: Cannot round NaN value`
- Repo: KitePDF
- Area: kitepdf-svg (SvgImage.primitiveOf, FilterImage.toRaster)
- Kind: bug
- Severity: medium
- Confidence: verified (ran it)
- Where: kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1162-1171, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1180, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1133, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/FilterImage.kt:104-110, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/FilterImage.kt:26-43, kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/FilterImage.kt:406-408, kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:2602-2613
- What happens: `number()` checks `isFinite`, but the list attributes (`values`, `tableValues`, `kernelMatrix`, `baseFrequency`) go through `numbers()`, which keeps `Infinity` from a literal such as `1e400`. `Infinity * 0` is NaN, `coerceIn(0f, 1f)` passes NaN through, and `FilterImage.toRaster` calls `roundToInt()` on the alpha, which throws for NaN. In the default linearRGB mode the final `inSpace` conversion happens to drop pixels whose alpha is NaN (`if (a > 0f)`), which hides it, but with `color-interpolation-filters="sRGB"` the image goes to `toRaster` as it is. Nothing between `SvgImage.walk` and `KitePage.renderTo` catches (EpubDocument.kt:2602 is try/finally only), so the whole page fails: a break of the lenient salvage rule for one malformed attribute.
- What should happen: A non-finite value in a filter attribute is an error that disables the primitive (Filter Effects 1, 15.7.1: an element in error is drawn as transparent black), and `toRaster` never throws on bad pixels.
- How to reproduce or evidence: `<svg width="40" height="40"><filter id="f" color-interpolation-filters="sRGB" filterUnits="userSpaceOnUse" x="0" y="0" width="40" height="40"><feColorMatrix values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  1e400 0 0 1 0"/></filter><rect x="10" y="10" width="20" height="20" fill="#f00" filter="url(#f)"/></svg>` rendered into `KiteRasterCanvas(40, 40)` throws `java.lang.IllegalArgumentException: Cannot round NaN value.` The same with `<feComponentTransfer><feFuncA type="table" tableValues="1e400 -1e400"/></feComponentTransfer>`. Probe4.kt.
- Cross-effects: SvgDocument pages, EPUB pages, the viewer's rasterizer (KitePageRasterizer.kt:462-469 has no catch either).
- Fix sketch: Filter `numbers()` results with `isFinite` in `primitiveOf` (or make `numbers()` drop non-finite values), and in `toRaster` treat a NaN channel as 0 (`if (!(a > 0f)) continue`).

### PLATFORM-06: A soft-masked paint loses its blend mode on Skia, Android and Canvas2D, while AWT and CoreGraphics keep it
- Repo: KitePDF
- Area: kitepdf-skia-renderer, kitepdf-native-renderer (Android, JS)
- Kind: bug
- Severity: medium
- Confidence: verified (read the whole path on all five canvases)
- Where: kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:810-816, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:827-835, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:854, kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:629-663, kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt:542-563, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:1061-1066, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:1104-1106, kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:984-997, kitepdf-native-renderer/test@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtSoftMaskTest.kt:186-205
- What happens: The three layer-based canvases open a transparent layer, run the content in it, gate it with the mask (DstIn) and composite the layer with a default Normal paint. A paint inside with `/BM /Multiply` multiplies against the transparent layer, which Skia defines as the source colour itself (the backdrop term is zero), so the mask layer lands on the page in Normal mode: a masked Multiply shadow becomes a dark box over the artwork instead of darkening it. AWT records the modes of the paints in the layer (`LayerBlends`) and composites the layer with that mode, rendering again over the backdrop when the modes mix, and CoreGraphics clips through `CGContextClipToMask` so each paint keeps its own mode; `AwtSoftMaskTest.a_single_blend_mode_survives_the_mask_layer` guards only AWT. InDesign and Illustrator drop shadows export as a luminosity `/SMask` on a form painted with `/BM /Multiply`, so this is a common page.
- What should happen: ISO 32000-1, 11.6.4.3 and 11.3.8: the soft mask scales the source alpha of each paint, and the paint's blend function then composites it with the backdrop; the mask does not change the mode. The result of the AWT test, (128, 0, 0) from red Multiply over (128, 128, 255) through a full mask, on every canvas.
- How to reproduce or evidence: Port `a_single_blend_mode_survives_the_mask_layer` to `SkiaCanvas` (a 40 by 40 N32 surface cleared to (128, 128, 255)), to `AndroidNativeCanvas` under Robolectric native graphics and to `Canvas2dCanvas`: the centre pixel reads (255, 0, 0) on the three.
- Cross-effects: `ComposeCanvas` uses the same layer model (kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ComposeCanvas.kt:779), which the orchestrator's viewer lane may want to check with the same test; the PDF renderer's choice between `applySoftMask` and the backdrop raster path (PageRenderer.kt:1954-2017) does not look at blend modes.
- Fix sketch: Do what AWT does: record the modes of the paints made inside `render()` (one `LayerBlends` per canvas), composite the gated layer with that mode when all paints agree, and fall back to the raster-step backdrop path (every canvas has `rasterStep`) when they mix. On Skia the outer `saveLayer` paint cannot change after the save, so render once to learn the mode, or always go through `rasterStep` when a blend is possible.

### PLATFORM-07: On the JVM and Apple Compose viewers the fill and the outline of host-font text come from different faces, so a stroked or clipped Standard-14 run is drawn in one font and outlined in another
- Repo: KitePDF
- Area: kitepdf-compose-viewer platform actuals (HostTextPath.jvm.kt, HostTextPath.apple.kt)
- Kind: bug
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/HostTextPath.jvm.kt:38-50, kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/HostTextPath.jvm.kt:54-82, kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/HostTextPath.jvm.kt:133-137, kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/HostTextPath.jvm.kt:145-169, kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/HostTextPath.jvm.kt:222-237, kitepdf-compose-viewer/src@apple/io/github/yuroyami/kitepdf/compose/HostTextPath.apple.kt:147-151, kitepdf-compose-viewer/src@apple/io/github/yuroyami/kitepdf/compose/HostTextPath.apple.kt:204-219, kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ComposeCanvas.kt:532, kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ComposeCanvas.kt:556-582, kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2525-2531
- What happens: `hostTextLine` (the fill) picks its face through `composeFamilyNames`: on macOS and iOS `.AppleSystemUIFontSerif` (New York) for Serif, `.AppleSystemUIFont` (San Francisco) for SansSerif, `.AppleSystemUIFontMonospaced` for Monospace; on Linux `Noto Sans`/`Noto Serif` first. `hostTextPath` (the outline that `hostGlyphOutline` hands the PDF renderer for text render modes 1, 2 and 4 to 7, and the SVG stroke) picks through `hostTypeface`, whose list starts with `Times New Roman`, `Times`, `Helvetica`, `Arial`, `Courier New`. The two lists share no first entry on macOS, iOS or a Linux with Noto and Liberation fonts installed, so a `Tr 2` (fill and stroke) run in Helvetica fills in San Francisco and strokes the outline of Helvetica, and a text clip through Times cuts New York shapes against a Times outline. Only Windows gets the same face both ways. The contract in HostTextPath.kt:8-9 says the outline is "in the host face that stands in for fontSpec", meaning the one the text draws in.
- What should happen: One face choice for both: the outline comes from the typeface the paragraph resolves (`FontCollection.findTypefaces` on the same family names), or `hostTextLine` draws with the typeface `hostTypeface` found.
- How to reproduce or evidence: On macOS, a PDF with `BT /Helv 36 Tf 2 Tr 1 0 0 RG 0 0 1 rg 20 100 Td (Og) Tj ET` through `KitePageRasterizer`: the blue fill is San Francisco and the red stroke is Helvetica, visibly offset at the O's bowl and the g's ear.
- Cross-effects: `hostHasFace` is a constant `true` on these targets (HostTextPath.jvm.kt:239, HostTextPath.apple.kt:221), so the bundled standard faces of #593 are never a fallback here even on a host with none of the named faces; `SkiaCanvas` keeps one list (`SkiaSystemFonts`) for both and has no such split.
- Fix sketch: Give `HostShaping.families` and `hostTypeface` the same candidate list (resolve once through `FontMgr`, register the found typeface in the `TypefaceFontProviderWithFallback` under a private name, and use that name for the paragraph), so fill and outline cannot diverge.

### PLATFORM-08: The Skia canvas leaves Paints, Fonts, TextBlobs, Shaders, Gradients, ColorFilters and evicted Images to Skiko's cleaner
- Repo: KitePDF
- Area: kitepdf-skia-renderer (SkiaCanvas)
- Kind: memory
- Severity: medium
- Confidence: verified (read the whole file); the native memory growth is likely, not measured
- Where: kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:156-163, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:174-215, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:245-250, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:285-294, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:335, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:365, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:434-442, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:473-481, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:580-622, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:703, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:826-834, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:1020-1024
- What happens: #130 made `toSkPath` detach from one `PathBuilder` and every caller close its path "so no native object waits for its cleaner". Everything else still does: a new `Paint` per `fillPath`, `strokePath`, `drawGlyphs` run, `drawImage`, group and mask; a `PathEffect` per dashed stroke; a `Font` per host-text run and per fallback character in `fontRuns`; a `TextBlob` and `TextBlobBuilder` per placed run; a `Gradient` and a `Shader` per shading; a `ColorFilter` per transfer; a `Font` and one `Path` per glyph in `hostGlyphOutline`; and the `Image`s evicted from the 16 MB `KiteBitmapCache` are not closed. Skiko frees these from a cleaner when the JVM collects the small wrapper, and the JVM collects on Java-heap pressure, which these 48-byte wrappers do not create, so native memory from a batch rasterization grows until an unrelated GC. A page of text is a few thousand Paints and Paths of native memory held past the page.
- What should happen: Close native objects when the draw returns, as the paths are closed, or keep one `Paint` per canvas and reset it per paint (Skia Paints are cheap to reuse).
- How to reproduce or evidence: Rasterize a 200-page text PDF through `PdfPageRasterizer.encodeToPng` in a loop with `-Xmx256m` and watch RSS against heap use: RSS climbs between GCs. Every `Paint().apply { ... }` in the file is a native allocation with no `close()`.
- Cross-effects: Android and iOS consumers of `kitepdf-skia-renderer`, where native memory is counted against the process; `KiteBitmapCache` eviction (core) returns nothing to close.
- Fix sketch: Reuse one `Paint` field with `reset()` for fills, strokes and glyphs; `use {}` the per-call Font, TextBlob, Shader, Gradient and ColorFilter; close an evicted `Image` by giving the cache an `onEvict` callback.

### PLATFORM-09: Android, Skia and CoreGraphics open page-sized layers for every transparency group and soft mask, ignoring the box the renderer gives them
- Repo: KitePDF
- Area: kitepdf-native-renderer (Android, Apple), kitepdf-skia-renderer
- Kind: performance
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:583, kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:629, kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:649, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:761-763, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:810, kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:835, kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:716, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:883-902, kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt:451
- What happens: `beginTransparencyGroup` and `applySoftMask` receive a `bbox` and its matrix. AWT, Canvas2D and the Compose viewer (#384) intersect it with the clip and size their layer to it. Android calls `saveLayer(null, paint)`, Skia `saveLayer(null, paint)` and CoreGraphics `CGContextBeginTransparencyLayer(ctx, null)`, which all allocate a layer of the current clip, that is the page. A page of one hundred small groups with a constant alpha (a typical chart, each series in its own group with `/ca`) allocates, clears and composites one hundred page-sized layers: 24 MB each for a 2000 by 3000 render on the Android bitmap renderer, and the hardware canvas of a `View` turns each into an offscreen render target.
- What should happen: A layer the size of `bbox` under `ctm`, cut to the clip, as the other three canvases do: `saveLayer(RectF, paint)` on Android, `SaveLayerRec(rect, ...)` on Skia (the InitWithPrevious variant already takes a rect in the Compose actual), `CGContextBeginTransparencyLayerWithRect` on CoreGraphics.
- How to reproduce or evidence: Render `SyntheticPdfs` with many `/ca 0.5` groups through `AndroidPdfBitmapRenderer` under a method tracer: every `saveLayer` reports the full canvas bounds.
- Cross-effects: PLATFORM-03's raster step budgets are separate, but a soft mask inside a group multiplies layer memory; the Compose `BackdropLayer` actuals already pass bounds.
- Fix sketch: Compute the device box as AWT's `beginTransparencyGroup` does (bbox under parent transform and ctm, intersected with the clip bounds), and pass it to the platform layer call; keep `null` only when the box is not finite.

### PLATFORM-10: The CoreGraphics canvas builds a new CGImage and copies the pixel bytes on every draw of a cached image, and creates a CTFont for every host-text run
- Repo: KitePDF
- Area: kitepdf-native-renderer (Apple)
- Kind: performance
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:648-679, kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:1169-1171, kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:309-311, kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:340
- What happens: `rgbaImages` caches only the Kotlin RGBA bytes (#117 says an image drawn many times "converts once"), but each `drawImage` calls `toCFData()`, which `CFDataCreate` copies, then `CGImageCreate`, draws, and releases both. A tiled background drawn 400 times copies its pixels 400 times and makes 400 CGImages. AWT, Android, Skia and Canvas2D cache the platform bitmap. Likewise `drawTextViaSystemFont` calls `CTFontCreateWithName` and `CFRelease` per run (hundreds per page of Standard-14 text); CTFont is immutable and thread-safe, so one per `FontSpec` per canvas is enough.
- What should happen: Cache the `CGImageRef` (retained; CGImage is immutable and thread-safe) under the same key, releasing on eviction, and keep a small `FontSpec` to `CTFontRef` map per canvas.
- How to reproduce or evidence: The KDoc at CoreGraphicsCanvas.kt:675-678 states it: "each draw makes its own CGImage and releases it".
- Cross-effects: none seen
- Fix sketch: Make `KiteBitmapCache<CGImageRef>` hold the image with a release on eviction (the cache needs an eviction hook; see PLATFORM-08), and memoize `CTFontCreateWithName` per spec.

### PLATFORM-11: An XPS brush written as a `ContextColor` draws nothing
- Repo: KitePDF
- Area: kitepdf-xps (XpsBrushes.xpsColor)
- Kind: bug
- Severity: medium
- Confidence: verified (read the whole path)
- Where: kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:221-237, kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsRenderer.kt:311-317, kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:35-39, kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:52-70, kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsRenderer.kt:189
- What happens: `xpsColor` reads `sc#` scRGB, `#ARGB`/`#AARRGGBB` and CSS names. ECMA-388, 15.1 also defines `ContextColor /Resources/profile.icc 1.0,0.2,0.4,0.0,1.0` (alpha then the profile's components), which Windows writes for CMYK and ICC colours. For it `xpsColor` answers null: `solid()` gives no colour, `fill()` falls into the `when` with no branch for `solidcolorbrush`, and `drawPath` returns before the stroke. The path, glyph or gradient stop vanishes with no warning. MuPDF maps it by component count (gray, RGB, CMYK) when it cannot load the profile.
- What should happen: ECMA-388, 15.1: parse the alpha and the components; without colour management, map 1, 3 and 4 components to gray, RGB and CMYK (the core's `KiteColorSpace.DeviceCMYK.toRgb` exists), as MuPDF does.
- How to reproduce or evidence: A `<Path Fill="ContextColor /Resources/cmyk.icc 1,0,0,1,0" Data="M0,0 L100,0 100,100 Z"/>` on a page renders blank; `render(...)` in `XpsDocumentTest` with `RecordingCanvas` records no `Fill`.
- Cross-effects: `representative()` (XpsBrushes.kt:42-50) returns black for such a gradient stop, so a gradient of ContextColor stops is black.
- Fix sketch: Extend `xpsColor` with a `ContextColor` branch: split off the profile URI, read `alpha,c1..cn`, convert by count through `KiteColorSpace`.

### PLATFORM-12: XPS gradient stops that share an offset are dropped, so a hard edge becomes a ramp
- Repo: KitePDF
- Area: kitepdf-xps (XpsBrushes.gradient)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:149-152
- What happens: `stops.sortedBy { it.offset }.distinctBy { it.offset }` keeps the first stop of each offset. A stripe gradient written as stops `0 red, 0.5 red, 0.5 blue, 1 blue` (ECMA-388, 13.5, two stops at one offset make a sharp transition) loses `0.5 blue` and ramps from red to blue over the upper half. SVG's `SvgGradient` keeps equal offsets (`sortedBy` only, SvgGradient.kt:129) and the PDF `Type3` stitching accepts equal bounds, so the removal is not needed.
- What should happen: Keep both stops; a `Type3` function with a zero-width segment gives the hard edge, as the SVG path already relies on.
- How to reproduce or evidence: `<LinearGradientBrush StartPoint="0,0" EndPoint="100,0"><LinearGradientBrush.GradientStops><GradientStop Color="#FF0000" Offset="0"/><GradientStop Color="#FF0000" Offset="0.5"/><GradientStop Color="#0000FF" Offset="0.5"/><GradientStop Color="#0000FF" Offset="1"/></LinearGradientBrush.GradientStops></LinearGradientBrush>`: the pixel at x=70 is purple, not blue.
- Cross-effects: none seen
- Fix sketch: Drop `distinctBy`; if the stitching function cannot take a zero-width piece, nudge the second offset by 1e-6.

### PLATFORM-13: The AWT `sh` fill without a clip covers at most 10,000 by 10,000 user units from the origin
- Repo: KitePDF
- Area: kitepdf-native-renderer (AwtCanvas.fillShading)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:443-447
- What happens: With `clipPath == null` the region is `g.clipBounds ?: Rectangle(0, 0, 10_000, 10_000)`. `AwtPdfRasterizer` sets a clip, but a caller who wraps its own `Graphics2D` (a Swing `paintComponent`, a `BufferedImage` of its own) usually has none, and `getClipBounds()` is then null. A `sh` on such a page larger than 10,000 pixels (A3 at 600 dpi is 7,000 by 9,900, A0 at 300 dpi is 9,900 by 14,000) paints only the top-left square, and a Graphics whose transform puts the page at negative coordinates gets no shading at all. The KiteCanvas default uses a 2e6-wide square.
- What should happen: The device bounds of the surface (`g.deviceConfiguration.bounds` is unreliable per the gotcha about Java2D, but `g.clipBounds` null means "unclipped", so use the KiteCanvas default's huge rectangle mapped through the inverse transform, or the destination raster size when `g` draws a `BufferedImage`).
- How to reproduce or evidence: `BufferedImage(12_000, 12_000)` with its `createGraphics()` and no clip, `AwtCanvas(g)`, `fillShading(axial, identity, clipPath = null)`: pixels past 10,000 stay white.
- Cross-effects: none seen
- Fix sketch: Fall back to a rectangle of ±1e6 in user space (as the interface default), which the clip or the surface edge trims.

### PLATFORM-14: `AwtCanvas` changes the host's `Graphics2D` hints, color, stroke, font and paint and never restores them, and the KDoc does not say so
- Repo: KitePDF
- Area: kitepdf-native-renderer (AwtCanvas)
- Kind: api
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:81-85, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:108, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:128-132, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:349, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:483
- What happens: The constructor sets three rendering hints on the Graphics it is given; `fillPath` sets `color`, `strokePath` sets `stroke`, `drawTextViaSystemFont` sets `font`, `fillShading` sets `paint`, and none is restored (the transform and composite are). The KDoc says only "The host owns the Graphics; we don't clear". A Swing `paintComponent` that renders a page then draws its own text draws it in the page's last font, colour and stroke, with anti-aliasing turned on.
- What should happen: Either restore (`g.create()` at construction and `dispose()` on `endPage`, so the host's Graphics is untouched), or state in the KDoc that the Graphics' state is changed.
- How to reproduce or evidence: After `page.renderTo(AwtCanvas(g), ctm)`, `g.font` is a 1-point derived Serif/Sans font and `g.stroke` a `BasicStroke` of the last line.
- Cross-effects: `AwtPdfRasterizer` and the tests create and dispose their own Graphics, so they never see it.
- Fix sketch: Take `g.create() as Graphics2D` in the constructor and dispose it in `endPage` (the clip and transform are copied), or document the leak.

### PLATFORM-15: The AWT image bitmap is filled through `BufferedImage.setRGB` one pixel at a time
- Repo: KitePDF
- Area: kitepdf-native-renderer (AwtCanvas.rgbaToBufferedImage)
- Kind: performance
- Severity: low
- Confidence: likely (read the path; the cost is from the JDK's implementation, not measured here)
- Where: kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:607-624, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:1231-1238
- What happens: `rgbaToBufferedImage` packs the bytes into an `IntArray` and then calls `img.setRGB(0, 0, width, height, argb, 0, width)`, which in the JDK loops over every pixel through `ColorModel.getDataElements` and `Raster.setDataElements` (a second copy, with a method call and a bounds check per pixel). The same file already wraps an `IntArray` in a `BufferedImage` without copying for raster steps (`Raster.createPackedRaster(DataBufferInt(...))` at line 1233). For a scanned page sampled to a 5,000 by 7,000 render that is 35 million per-pixel calls on top of the decode.
- What should happen: Build the image over the packed `IntArray` as `AwtRasterScope.draw` does, which is one allocation and no per-pixel calls.
- How to reproduce or evidence: Time `decodeImage` on a 20-megapixel RAW image before and after replacing `setRGB` with the packed-raster constructor.
- Cross-effects: the glyph cache and the soft mask layers use `TYPE_INT_ARGB` images whose backing `DataBufferInt` the code reads directly, so the packed-raster image is drawn the same way.
- Fix sketch: `BufferedImage(ColorModel.getRGBdefault(), Raster.createPackedRaster(DataBufferInt(argb, argb.size), width, height, width, intArrayOf(0xFF0000, 0xFF00, 0xFF, -0x1000000), null), false, null)`.

### PLATFORM-16: Three luminosity formulas gate soft masks: Rec. 709 on the Skia renderer, 0.299/0.587/0.114 on the Compose viewer, 77/150/29 of 256 on the four native canvases
- Repo: KitePDF
- Area: kitepdf-skia-renderer, kitepdf-compose-viewer actuals, kitepdf-native-renderer
- Kind: bug
- Severity: low
- Confidence: verified (read all six); Skia's `ColorFilter.luma` coefficients are from Skia's documentation (`SkLumaColorFilter`, Rec. 709), not read here
- Where: kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:831-832, kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/SoftMaskFilter.kt:27-33, kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:1093, kitepdf-native-renderer/src@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt:912, kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt:1068, kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt:731, kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteMaskTransfer.kt:210
- What happens: A luminosity mask painted in a saturated colour (a red mask group, which some generators use) gates the content at alpha 54 of 255 on `SkiaCanvas` (0.2126), 76 on `ComposeCanvas` (0.299) and 77 on AWT, Android, CoreGraphics and Canvas2D (77/256). Grey masks, the common case, agree. The SVG `mask` element goes through the same calls, where CSS Masking 1, 7.1 asks for 0.2125/0.7154/0.0721 (in linearRGB unless `color-interpolation` says sRGB), a fourth formula.
- What should happen: ISO 32000-1, 11.6.5.2 leaves "luminosity" to 11.3.5.3's Lum, 0.3 R + 0.59 G + 0.11 B, which MuPDF uses; one formula on every canvas so the differential baselines of the Skia sweep and the AWT sweep measure the same thing.
- How to reproduce or evidence: Mask group `1 0 0 rg 0 0 100 100 re f` over a black square, rendered through `PdfPageRasterizer` and `AwtPdfRasterizer`: the square reads 54 and 77 grey.
- Cross-effects: `SkiaDifferentialTest` and the AWT `DifferentialTest` keep separate baselines, so the gap never fails a gate.
- Fix sketch: Replace `ColorFilter.luma` on Skia with a colour matrix filter of 0.3/0.59/0.11 (the Compose actual already builds one), and change `LUMINOSITY_TO_ALPHA` to the same three numbers.

### PLATFORM-17: The Android native canvas has two host tests, where the JS and macOS canvases have parity suites for masks, groups, images, shadings, pens and raster steps
- Repo: KitePDF
- Area: kitepdf-native-renderer test@android
- Kind: test-gap
- Severity: medium
- Confidence: verified (listed the test sets)
- Where: kitepdf-native-renderer/test@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidHostShapingTest.kt, kitepdf-native-renderer/test@android/io/github/yuroyami/kitepdf/nativerenderer/AndroidTwoCircleShadingTest.kt, kitepdf-native-renderer/test@js/, kitepdf-native-renderer/test@macosArm64/
- What happens: `test@js` has nine files (soft masks with transfer functions, groups, image blends, image sampling, shadings with extend, stroke pens, raster steps, host shaping) and `test@macosArm64` ten, all run in CI (`.github/workflows/ci.yml:196`, `:230`). `test@android` has two, both Robolectric native-graphics tests, which proves the harness can read pixels from `AndroidNativeCanvas`. So `applySoftMask` (both overloads, the pixel-gated transfer path at AndroidNativeCanvas.kt:776-802), `beginTransparencyGroup` (knockout, non-isolated), `drawImage` with blend modes and sampling, `rasterStep` with its backdrop, the concentric fallback below API 31, and `hostGlyphOutline` on API 34 have no test on Android. PLATFORM-06 would have been caught by porting one JS test.
- What should happen: The JS and macOS suites ported to Robolectric (`@GraphicsMode(NATIVE)`, `@Config(sdk = [29, 35])`, as the two existing tests do).
- How to reproduce or evidence: Compare the file lists above.
- Cross-effects: #641 (no Android device run) covers devices; host tests under Robolectric already run in CI, so this is cheap.
- Fix sketch: Port `Canvas2dSoftMaskTest`, `Canvas2dGroupTest`, `Canvas2dImageBlendTest`, `Canvas2dRasterStepTest` and `Canvas2dStrokePenTest` to `test@android`.

### PLATFORM-18: A SMIL clip whose `clipEnd` the clock parser cannot read plays the audio file to its end
- Repo: KitePDF
- Area: kitepdf-epub (EpubMediaOverlay.SmilParser), kitepdf-media (ReadAloud)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubMediaOverlay.kt:78-92, kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubMediaOverlay.kt:133-134, kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteReadAloud.kt:318-326, kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteReadAloud.kt:462-477
- What happens: `SmilClock.seconds` reads full and partial clocks and `h`, `min`, `s`, `ms` counts, and answers null for anything else. `clip()` turns a null `clipBegin` into 0.0 and keeps `clipEnd` null, and `ReadAloud` reads null as "to the end of the file" (`awaitPosition(null)`). So a clip written `clipBegin="npt=12.5s" clipEnd="npt=15s"` (the DAISY-era prefix that some converted books still carry, and that Readium's clock parser accepts) plays the whole chapter file from its start instead of 2.5 seconds, with the highlight stuck on one sentence, and every later clip of the same file does the same. An unreadable `clipEnd` with a readable `clipBegin` has the same effect.
- What should happen: EPUB 3.3 Media Overlays, 2.4, the `audio` element: `clipBegin` and `clipEnd` are SMIL clock values; a value that is not one is an error in the book, and a reader should skip the clip's audio (treat it as a clip without audio) or accept the `npt=` prefix leniently, never play to the end of the file.
- How to reproduce or evidence: `<par><text src="c1.xhtml#p1"/><audio src="c1.mp3" clipBegin="npt=12.5s" clipEnd="npt=15s"/></par>`: `SmilParser.clips` gives `EpubOverlayClip(c1.xhtml#p1, c1.mp3, 0.0..null)`.
- Cross-effects: `MediaOverlayTest` in kitepdf-epub covers the valid forms only.
- Fix sketch: In `clip()`, when `clipBegin` or `clipEnd` is present but unparseable, drop the audio (`audioHref = null`) and warn; optionally strip a leading `npt=` in `SmilClock.seconds`.

### PLATFORM-19: `KiteMediaSession` keys an element's kept place by the hash code of the book's metadata, so two books with equal metadata share positions
- Repo: KitePDF
- Area: kitepdf-media (KiteMediaOverlay)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaOverlay.kt:116-134, kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaSession.kt:33-46
- What happens: `place` is `"$book|chapter|name|twin"` with `book = epubPage.document.epubMetadata.hashCode()`. Two editions with the same title, creators and identifier (a hash of the metadata object, not of the file), or two different books whose hash codes collide, resolve to one key in the process-wide `KiteMediaSession.Default`, so an element of one book opens at the position, mute and speed kept for the other. The comment says the intent is "one opened book".
- What should happen: A key tied to the opened document instance (an identity token the `EpubDocument` carries, or `System.identityHashCode` of the document's metadata object plus the metadata's `dc:identifier`).
- How to reproduce or evidence: Open the same EPUB twice as two `EpubDocument`s (same bytes), play an element in the first to 00:30 and leave the page, open the element's page in the second: it shows 00:30 paused.
- Cross-effects: none seen
- Fix sketch: Key by the document's own identity (`identityHashCode(epubPage.document.epubMetadata)` is what the comment describes) rather than `hashCode()`.

### PLATFORM-20: `SvgImage.isSvg` looks for `<svg` in the first 512 bytes only, so `KiteDoc` does not recognise an SVG with a long prolog
- Repo: KitePDF
- Area: kitepdf-svg, kitepdf (KiteDoc)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1760-1763, kitepdf/src/io/github/yuroyami/kitepdf/document/KiteDoc.kt:70
- What happens: Inkscape and Illustrator write an XML declaration, a licence comment and a DOCTYPE with an internal subset before `<svg`, often past 512 bytes (an Illustrator header with `<!-- Generator: Adobe Illustrator ... -->` plus a DOCTYPE with entity declarations runs to about 600). `KiteDoc.open` then falls through to its other sniffers and reports the file as unreadable, while `SvgDocument.open` on the same bytes works.
- What should happen: Skip the prolog (declaration, comments, DOCTYPE with its bracketed subset, as `KiteXml.readDoctype` already does) and look at the first element name, or widen the window to a few kilobytes.
- How to reproduce or evidence: 600 bytes of `<!-- ... -->` then `<svg width="1" height="1"/>`: `SvgImage.isSvg` false, `SvgDocument.open` succeeds.
- Cross-effects: none seen
- Fix sketch: `KiteXml.tokenize(head)` on the first 4 KB and test the first `Open` token's name.

### PLATFORM-21: `NSData.toByteArray` in the Apple sources truncates a length above 2 GB to a negative Int
- Repo: KitePDF
- Area: kitepdf (Sources.apple.kt)
- Kind: bug
- Severity: low
- Confidence: verified (read the whole path)
- Where: kitepdf/src@apple/io/github/yuroyami/kitepdf/document/Sources.apple.kt:62-69, kitepdf/src@posixLp64/io/github/yuroyami/kitepdf/document/PosixFileIo.kt:32-34
- What happens: `val n = length.toLong().toInt()` wraps for a file over 2 GB, and `ByteArray(n)` throws an `IllegalArgumentException` from Kotlin/Native (negative size) instead of the `KiteFormatException` with a clear message that the POSIX reader throws for the same case. `n == 0` is the only check.
- What should happen: The same guard as `PosixFileIo.kt:32`: a `KiteFormatException("file is larger than 2 GB ...")`.
- How to reproduce or evidence: `KiteDoc.open(NSData of 2,147,483,648 bytes)`.
- Cross-effects: none seen
- Fix sketch: Check `length > Int.MAX_VALUE.toULong()` first.

### PLATFORM-22: The native `KiteLock.unlock()` without a matching `lock()` drives `depth` negative and never releases the lock
- Repo: KitePDF
- Area: kitepdf-core src@native (KiteLock)
- Kind: design
- Severity: info
- Confidence: verified (read the whole file)
- Where: kitepdf-core/src@native/io/github/yuroyami/kitepdf/core/KiteLock.native.kt:42-44
- What happens: The JVM `ReentrantLock.unlock()` throws `IllegalMonitorStateException` for a thread that does not own the lock; the native lock decrements `depth` and only clears `owner` when it hits 0, so an unmatched `unlock()` (a `withLock` whose block threw before `lock()` completed is not possible, but a hand-written pair is) leaves the lock held for good by a thread that may be gone, and every later `lock()` spins and sleeps forever. The hash-based owner identity is already known to the orchestrator; this is the other way the lock can wedge.
- What should happen: `unlock()` checks `owner.value == currentThreadId()` and ignores or fails an unmatched call, as the JVM does.
- How to reproduce or evidence: `KiteLock().unlock()` then `lock()` on native: the second call never returns.
- Cross-effects: none seen
- Fix sketch: Guard `unlock()` with an owner check and an `error()` for a foreign thread.

### KiteCanvas parity matrix (design, for the owner)

Methods of `KiteCanvas` that each shipped canvas overrides. A blank cell means the interface default: `hostGlyphOutline` returns null (a stroked or clipped host-font run paints filled, and a PDF text clip through such a font clips to each glyph's advance box, PageRenderer.kt:2509-2514; an SVG stroked text fills, SvgImage.kt:558-565).

| Method | AwtCanvas | AndroidNativeCanvas | CoreGraphicsCanvas | Canvas2dCanvas | SkiaCanvas |
| --- | --- | --- | --- | --- | --- |
| fillShading | yes | yes | yes | yes | yes |
| hostGlyphOutline | yes, cached (4096) | API 34 and later only (AndroidNativeCanvas.kt:266-267), no cache | no (the machinery exists: `CTFontCreatePathForGlyph` at CoreGraphicsCanvas.kt:421) | no (no browser API) | yes, no cache |
| drawImage (both overloads) | yes | yes | yes | yes | yes |
| beginTransparencyGroup / end | yes, bbox-sized layer | yes, page-sized layer | yes, page-sized layer | yes, bbox-sized | yes, page-sized |
| applySoftMask (both overloads) | yes, keeps blend modes | yes, loses blend modes | yes, keeps blend modes (ClipToMask) | yes, loses blend modes | yes, loses blend modes |
| rasterStep | yes, backdrop always | yes, backdrop only in AndroidPdfBitmapRenderer outside layers | yes, bitmap contexts outside layers | yes, backdrop always | yes, outside layers |
| text clip / stroke of host fonts | glyph outline | box below API 34 | box | box | glyph outline |

Other per-platform differences a developer sees: Android below API 31 draws an off-centre radial shading by pixels (AndroidNativeCanvas.kt:404-447); CoreGraphics draws CJK host text in Hiragino/Songti/PingFang by PostScript name and Korean serif in `AppleMyungjo`, which iOS does not ship, so `CTFontCreateWithName` silently falls back there (CoreGraphicsCanvas.kt:491); the luminosity formula (PLATFORM-16); every canvas truncates `RgbColor` to 8 bits with `toInt()` except `EpubPageRasterizer.skiaColor` and `RadialShadingPaint.ramp`, which round, so a 0.5 grey is 127 on paths and 128 in those two.

### Capabilities and opportunities (not defects)

- `CoreGraphicsCanvas.hostGlyphOutline`: the canvas already gets glyph paths from `CTFontCreatePathForGlyph` (CoreGraphicsCanvas.kt:420-431); exposing them (flipped to y up, at `HOST_EM`) gives Apple users real text clips and strokes for Standard-14 fonts at small cost. Same for `AndroidNativeCanvas` below API 34 through `Paint.getTextPath` plus `android.graphics.Path` approximation (`PathMeasure` or `Path.approximate`, API 26).
- SVG `<textPath>`: `layoutText` keeps only `tspan` children (SvgImage.kt:457-461), so text on a path is dropped entirely, not even drawn straight. Drawing it straight along the path's start would keep the words visible; placing glyphs along the path needs arc-length sampling of `KitePath`, which `strokeOutline` already flattens. Reach: logos and diagrams in EPUBs.
- SVG `<marker>` (arrowheads on lines in diagrams) is not drawn; `clip-path` on a `<clipPath>` or on its children (SVG 1.1, 14.3.5) is not applied.
- SVG luminance masks use the canvas's PDF luminosity, where CSS Masking 1, 7.1 specifies 0.2125/0.7154/0.0721 in linearRGB; a `KiteMaskTransfer`-like coefficient choice on `applySoftMask` would let SVG ask for its own.
- XPS: `ContextColor` (PLATFORM-11 as a defect), and HD Photo (JPEG XR) image brushes, which ImageKodec does not decode, draw as the grey placeholder; Windows "Microsoft XPS Document Writer" output with photos can carry them.
- CBZ: `CBZ_IMAGE_EXTENSIONS` (CbzDocument.kt:13-14) stops at jpg, png, gif, bmp, webp, while ImageKodec also decodes TIFF, AVIF, JPEG XL and JPEG 2000; an archive of `.avif` pages opens as "ZIP archive holds no image entries". A `.cbr` (RAR, magic `Rar!\x1A\x07`) gives "not a readable ZIP archive (N bytes)"; a sniff of the RAR signature would give the user "RAR archives are not supported".
- Media: lenient `npt=` clock values (PLATFORM-18); `KiteReadAloud` has no bundled speech for clips without audio (documented, `speak` is the app's).
- Skia renderer: an `onEvict` hook on `KiteBitmapCache` would let every canvas release native bitmaps (PLATFORM-08, PLATFORM-10) instead of waiting for a collector.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Lane BUILD-DOCS-TESTS findings

Lane: how KitePDF (and, more lightly, KiteJS and ImageKodec) are built, published, documented and tested.
Verification notes: the released 0.13.0 artifacts were downloaded from Maven Central and inspected (jar listings, bytecode level, POMs, klib manifests, the Android AAR manifests), the Skiko 0.153.0 Android AAR was downloaded from the Compose dev repository, a JVM build of kitepdf-core and kitepdf-pdf and a full JVM test run of kitepdf-cbz were made in this container, and every README code sample was checked symbol by symbol against the committed `api/` dumps. Line numbers are from the checked-out tree.

### BUILD-DOCS-TESTS-01: The published jars, AARs and klibs carry none of the licence texts that the bundled fonts, hyphenation patterns and the PDFium port require
- Repo: KitePDF (the same gap in KiteJS and ImageKodec, see cross-effects)
- Area: publishing (library.module-template.yaml, NOTICE, kitepdf-core)
- Kind: security
- Severity: medium
- Confidence: verified
- Where: NOTICE:58-93, kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/Standard14FontData.kt:1-30, kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen/HyphAf.kt (and the other 70 sets), kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/AdobeCmyk.kt:7-14, library.module-template.yaml:5-40
- What happens: `kitepdf-core-jvm-0.13.0.jar` on Maven Central holds only `META-INF/MANIFEST.MF` (25 bytes, `Manifest-Version: 1.0`) and `META-INF/io.github.yuroyami_kitepdf-core.kotlin_module` beside the classes; the jar this container built from the toolchain (`build/tasks/_kitepdf-core_jarJvm/kitepdf-core-jvm.jar`, 545 files) is the same shape. Those classes contain the URW base 35 font programs (`Standard14FontData`, SIL OFL 1.1), 71 hyphenation pattern sets (MIT, BSD-3-Clause, LPPL and a custom notice each) and the PDFium CMYK table and interpolation routine (BSD-3-Clause). The licence texts and copyright lines exist only as comments in the `.kt` sources, which the compiler drops, so every binary copy that a consumer ships (jar, AAR, klib, the frameworks `scripts/apple_frameworks.py` links) carries no notice at all. The OFL (condition 2) asks that "each copy contains the above copyright notice and this license" as text, a header or metadata; MIT asks that "the above copyright notice and this permission notice shall be included in all copies or substantial portions"; BSD-3 asks the same for binary redistribution; Apache-2.0 section 4(d) asks that a redistribution of a Work with a NOTICE file includes that NOTICE. `publishSources: true` puts the sources jar on Central, but a consumer's app does not ship a sources jar.
- What should happen: the published artifacts carry `META-INF/LICENSE`, `META-INF/NOTICE` and a third-party notices file (OFL text with the URW copyright, the hyph-utf8 notices, the PDFium licence, the Adobe and WHATWG notices), as the Kotlin and AndroidX artifacts do; the klibs can carry them as resources, and the frameworks a `LICENSE` in the bundle.
- How to reproduce or evidence: `curl -sSLO https://repo1.maven.org/maven2/io/github/yuroyami/kitepdf-core-jvm/0.13.0/kitepdf-core-jvm-0.13.0.jar && unzip -l kitepdf-core-jvm-0.13.0.jar | grep -i 'license\|notice'` prints nothing. `unzip -l build/tasks/_kitepdf-core_jarJvm/kitepdf-core-jvm.jar | awk '/text\/hyphen\//{s+=$1} END{print s}'` gives 3,098,036 bytes of hyphenation classes in the jar with no notice next to them.
- Cross-effects: `kitejs-quickjs-jvm-0.7.0.jar` bundles the compiled QuickJS-ng engine (`jni/*/libkitejs_quickjs.*`, five native libraries) with no copy of its MIT licence, although KiteJS's NOTICE and README name that licence; `imagekodec-jvm-0.3.0.jar` has no LICENSE or NOTICE either. KiteJS's POM names only MPL-2.0 for the kitejs-quickjs artifact, which is mostly MIT code.
- Fix sketch: make the toolchain's jar/klib/AAR packaging add `META-INF/LICENSE`, `META-INF/NOTICE` and `META-INF/THIRD-PARTY-NOTICES` (a small plugin task that copies the files into the module's resources before the compile is enough), and have the consumer test assert that the resolved jar contains them.

### BUILD-DOCS-TESTS-02: README, NOTICE and the About page say KitePDF contains no third-party source code, but AdobeCmyk.kt is a port of a PDFium routine
- Repo: KitePDF
- Area: docs (README.md, NOTICE, docs/about.md), kitepdf-core
- Kind: docs
- Severity: low
- Confidence: verified
- Where: README.md:511-513, NOTICE:11-13, NOTICE:86-93, docs/about.md:58, kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/AdobeCmyk.kt:7-14 and 55-61
- What happens: README.md:511 says "KitePDF contains no third-party source code, but it bundles some third-party data"; NOTICE:11-13 says "It contains no third-party source code. It does contain reference data"; docs/about.md:58 repeats it. NOTICE item 8 itself then describes "the way PDFium steps between grid points, from core/fxge/dib/cfx_cmyk_to_srgb.cpp", and the file's KDoc says "This is PDFium's `AdobeCmykToStandardRgb`, fixed-point arithmetic included" (AdobeCmyk.kt:14). A fixed-point interpolation routine transcribed from a C++ function is source code, not data, and it is under PDFium's BSD-3-Clause licence. The three documents contradict the file they describe.
- What should happen: the three statements say that KitePDF carries one small routine ported from PDFium (BSD-3-Clause) beside the data, or the routine is rewritten from the table alone (it is a 4-D linear step, which needs no PDFium code).
- How to reproduce or evidence: `grep -n "no third-party source" README.md NOTICE docs/about.md` and `sed -n 7,14p kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/AdobeCmyk.kt`.
- Cross-effects: the BSD-3 binary redistribution clause makes finding 01 apply to this code as well.
- Fix sketch: change the three sentences to name the PDFium routine, and add the PDFium licence to the third-party notices file of finding 01.

### BUILD-DOCS-TESTS-03: The kitepdf-skia-renderer AAR declares minSdk 21 although Skiko needs 24 and the docs say 24
- Repo: KitePDF
- Area: kitepdf-skia-renderer/module.yaml, docs/platforms.md, README.md
- Kind: api
- Severity: low
- Confidence: verified
- Where: kitepdf-skia-renderer/module.yaml:53-54 (an `android:` block with only `namespace`), library.module-template.yaml:17 (`minSdk: 21` for every library), docs/platforms.md:43, README.md:478
- What happens: README.md:478 and docs/platforms.md:43 say the Skia renderer needs Android API 24 "Skiko needs API 24". `kitepdf-compose-viewer/module.yaml:58`, `kitepdf-media/module.yaml` and `kitepdf-native-renderer/module.yaml` each override `minSdk`, but `kitepdf-skia-renderer/module.yaml` does not, so it inherits 21 from the template. The released `kitepdf-skia-renderer-android-0.13.0.aar` has `<uses-sdk android:minSdkVersion="21" />` in its manifest, and `skiko-android-0.153.0.aar` (the Compose dev repository) has `<uses-sdk android:minSdkVersion="24" />`. An app with minSdk 21 to 23 that adds kitepdf-skia-renderer fails in the manifest merger with a message that names `org.jetbrains.skiko:skiko-android`, a library the app never added, instead of the KitePDF artifact the docs describe.
- What should happen: the artifact declares the minimum it needs, as the other three do, so the merger names kitepdf-skia-renderer and the docs and the manifest agree.
- How to reproduce or evidence: `unzip -p kitepdf-skia-renderer-android-0.13.0.aar AndroidManifest.xml` (Maven Central) prints minSdkVersion 21; `unzip -p skiko-android-0.153.0.aar AndroidManifest.xml` prints 24.
- Cross-effects: the toolchain-built AARs have no `aar-metadata.properties` (CHANGELOG.md:21-22), so the consumer's AGP also cannot learn that the viewer and the Skia renderer were compiled against compileSdk 37.2; the Gradle-built 0.13.0 AAR still carried `minCompileSdk=37`, `minCompileMinorSdk=2`.
- Fix sketch: add `minSdk: 24` under `settings.android` of kitepdf-skia-renderer/module.yaml, and add a consumer-side check (an `aapt dump badging` or a manifest grep in CI) that the AAR minSdk of each module equals the table in docs/platforms.md.

### BUILD-DOCS-TESTS-04: Every KitePDF artifact is Java 21 bytecode, KiteJS and ImageKodec are Java 11, and no document says so
- Repo: KitePDF
- Area: common.module-template.yaml, README.md, docs/getting-started.md, consumer-test
- Kind: docs
- Severity: medium
- Confidence: verified
- Where: common.module-template.yaml:10-12 (`release: 21`), README.md:55-110 (Install), docs/getting-started.md:5-43, consumer-test/build.gradle.kts:20-22 (`jvmToolchain(21)`)
- What happens: the classes in `kitepdf-core-jvm-0.13.0.jar` on Maven Central have class-file major version 65 (Java 21), and the toolchain build in this container produces the same (`release: 21`). `imagekodec-jvm-0.3.0.jar` is major 55 (Java 11) and KiteJS targets 11 (`library.module-template.yaml` of KiteJS, `release: 11`; its README says "JVM (bytecode 11)"). A JVM or desktop Compose application on Java 17, still the default of many Gradle and IDE setups, loads ImageKodec and KiteJS and then fails with `UnsupportedClassVersionError` on the first KitePDF class. Nothing in README.md, docs/getting-started.md, docs/platforms.md or the CHANGELOG states a JDK requirement (`grep -in "jdk\|java 21\|java 17" README.md docs/*.md` finds only two sentences about AWT needing "the JDK alone"). The Android side is the same: an AAR of Java 21 classes needs an AGP whose D8 reads class-file 65, and no document names an AGP version. The consumer test cannot catch any of it because consumer-test/build.gradle.kts pins `jvmToolchain(21)`.
- What should happen: the README's Install section and docs/getting-started.md state "JDK 21 or newer on the JVM, AGP x.y or newer on Android" next to the coordinates, or the libraries target Java 11 as the two sibling libraries do (the common code uses no Java 21 API).
- How to reproduce or evidence: `unzip -p kitepdf-core-jvm-0.13.0.jar io/github/yuroyami/kitepdf/core/KiteLock.class | od -An -tu1 -j6 -N2` gives `0 65`; the same on `imagekodec-jvm-0.3.0.jar` gives `0 55`.
- Cross-effects: kitepdf-javascript mixes the two levels in one dependency tree; a Java 17 server that only runs scripts through KiteJS is pulled to Java 21 by the kitepdf-pdf dependency.
- Fix sketch: either set `release: 11` (or 17) in common.module-template.yaml and keep `jdk.version: 21` for the build, or document the requirement in README and getting-started and add a consumer-test variant that runs on the oldest supported JDK, as ImageKodec's `tools/java11_tests.py` does.

### BUILD-DOCS-TESTS-05: A push to main cancels the CI run of the commit before it, so intermediate commits on main get no verdict
- Repo: KitePDF (also KiteJS and ImageKodec)
- Area: .github/workflows/ci.yml
- Kind: test-gap
- Severity: low
- Confidence: verified
- Where: .github/workflows/ci.yml:20-28; /home/user/KiteJS/.github/workflows/ci.yml:11-13; /home/user/ImageKodec/.github/workflows/ci.yml:9-11
- What happens: the workflow runs on `push: branches: [main]` with `concurrency: group: ci-${{ github.ref }}` and `cancel-in-progress: true`. On a push to main the ref is `refs/heads/main` for every run, so a second push during the hour-long `jvm` job cancels the first commit's run. CONTRIBUTING.md asks for work on `main` with no branches, so this is the normal flow: when two commits land within an hour (the repository's history shows several such days), the first one keeps a "cancelled" status forever, `git bisect` later finds no verdict for it, and the differential and parity gates ran on neither the first commit alone nor the second commit alone (the second run tests the combination).
- What should happen: cancel in progress only for pull requests, where a superseded push really is obsolete, and let every push to main finish.
- How to reproduce or evidence: push two commits to main five minutes apart and look at the Actions page: the first run is "cancelled".
- Cross-effects: the README badge (README.md:25) reads the last completed run, so a cancelled run leaves the badge on the older result.
- Fix sketch: `cancel-in-progress: ${{ github.event_name == 'pull_request' }}` in the three workflows.

### BUILD-DOCS-TESTS-06: The shaping, signature and embedded-font oracles never run in CI, and nothing counts the skips
- Repo: KitePDF
- Area: .github/workflows/ci.yml, kitepdf-epub/test@jvm, kitepdf-pdf/test@jvm, kitepdf-native-renderer/test@jvm
- Kind: test-gap
- Severity: medium
- Confidence: verified
- Where: .github/workflows/ci.yml:56-61 (the only `apt-get install`, `fonts-noto-cjk`) and 123-130 (pypdfium2); kitepdf-epub/test@jvm/io/github/yuroyami/kitepdf/epub/HarfBuzzOracle.kt:21 (`hb-shape` from three fixed paths); kitepdf-pdf/test@jvm/io/github/yuroyami/kitepdf/crypto/sig/SignatureOracleTest.kt:72 (`pdfsig`); .gitignore:13 (`mupdf-master/`)
- What happens: CONTRIBUTING.md names HarfBuzz "the shaping oracle for EPUB text" and poppler's `pdfsig` the signature oracle, and the CLAUDE.md gotchas record bugs that only those oracles found (#622, #203, #596). The `jvm` job installs `fonts-noto-cjk`, builds `mutool` and installs `pypdfium2`, and nothing else: no `harfbuzz` (`hb-shape`), no `poppler-utils` (`pdfsig`), no `openssl`, no `woff2`, no `libopenjp2-tools`, and the Noto, DroidSansFallback and reference fonts live in `mupdf-master/resources`, which is git-ignored and never checked out on the runner. Every test that needs one of them calls `assumeTrue` or `orSkip` and reports as passed-with-skip. Counting the skip sites in `*/test@jvm`: 11 tests skip without `hb-shape`, 10 without the Noto fonts of mupdf-master, 22 without `DroidSansFallback.ttf`, 4 without `NotoNaskhArabic-Regular.otf`, 3 without `NotoSans-Regular.otf`, and one each without `pdfsig`, `openssl cms`, `woff2_compress`, `opj_compress`, the Arabic corpus book and the GoldenHour corpus PDF. So the GSUB, Indic, Arabic and mark-positioning oracle tests, the CFF and Type 1 glyph comparisons, the WOFF2 round trip and the signature cross-check all pass on CI without running, and no step reports how many tests skipped. The gate is only the author's Mac. ImageKodec solved the same problem with `IMAGEKODEC_REQUIRE_ORACLES=1` (its CI installs every tool and fails on a missing one); EpubConformanceTest is the one KitePDF test that fails on CI when its input is missing (EpubConformanceTest.kt:45).
- What should happen: CI installs `harfbuzz`, `poppler-utils`, `openssl`, `woff2`, `libopenjp2-tools`, checks out the pinned MuPDF resources (the `.oracle-source` checkout at ci.yml:98-106 already has `resources/fonts` when the cache misses, so the fonts can be kept beside `.oracle-bin`), and a `KITEPDF_REQUIRE_ORACLES=1` variable turns each `orSkip` into a failure, as `Tools.kt:34-36` does in ImageKodec. The upload of `build/reports/` could also fail the job when the skipped count of a module grows.
- How to reproduce or evidence: `grep -n "apt-get install" .github/workflows/ci.yml` prints one line; `grep -rhn 'orSkip("' */test@jvm | sort | uniq -c` lists the tools above; `grep -n mupdf-master .gitignore`.
- Cross-effects: `DifferentialTest.kt:80-85` degrades to a "KitePDF-only smoke pass" without `mutool` and still passes, so a contributor who runs the gate of CONTRIBUTING.md on a machine without `mutool` gets a green run that scored nothing; the same `assume` pattern hides it.
- Fix sketch: a shared `OracleTools` helper that reads `KITEPDF_REQUIRE_ORACLES`, the apt line and the font checkout in the `jvm` job, and a one-line summary of skipped tests in the job log.

### BUILD-DOCS-TESTS-07: The "What CI tests" table of docs/platforms.md does not match the workflow
- Repo: KitePDF
- Area: docs/platforms.md, .github/workflows/ci.yml, scripts/web-tests/run.py
- Kind: docs
- Severity: low
- Confidence: verified
- Where: docs/platforms.md:49-64; .github/workflows/ci.yml:192-196, 219-230; scripts/web-tests/run.py:29-35; kitepdf-net (only `test@jvm`), kitepdf/test (common tests), kitepdf-compose-viewer/test and test@macos
- What happens: the table says "JS: The format modules, `-net` and `-javascript` on Node", but `kitepdf-net` has no common `test` folder (only `test@jvm`) and run.py's `JS_MODULES` (run.py:29-30) does not list it, so nothing of kitepdf-net runs on JS. It says "wasmJs: The format modules and `-javascript` on Node", but the `web` job also runs the viewer's tests on wasmJs in headless Chrome (ci.yml:224-225). It says "iOS simulator arm64: The format modules except `kitepdf`, and `-javascript`", but `./kotlin test -p iosSimulatorArm64` (ci.yml:195-196) runs every module with a common `test` folder on that platform: `kitepdf` (kitepdf/test/.../KiteDocTest.kt), `kitepdf-compose-viewer` (its common `test`) and `kitepdf-native-renderer` (its common `test`). The macOS row leaves out the viewer, which has `test` and `test@macos`.
- What should happen: the table lists, per platform, the modules whose `test` or `test@<platform>` folders exist and whose module.yaml names the platform, which is what the toolchain runs.
- How to reproduce or evidence: `for m in kitepdf*; do ls -d $m/test* ; done` against the rows of the table.
- Cross-effects: none seen.
- Fix sketch: regenerate the table from the folders (a ten-line script in `tools/`) or fix the five rows by hand.

### BUILD-DOCS-TESTS-08: "behaves identically across platforms" is contradicted by the renderer, host-font and viewer differences the other pages document
- Repo: KitePDF
- Area: README.md, docs/index.md, docs/rendering.md, docs/compose-viewer.md
- Kind: docs
- Severity: low
- Confidence: verified
- Where: README.md:43 and docs/index.md:32 (the claim); docs/rendering.md:286-333 ("How the renderers draw"); docs/compose-viewer.md:301 and README.md:200; docs/epub.md:455 and :356; kitepdf-native-renderer/Module.md:5; CLAUDE.md gotcha on `public-pdfa-vertical-text`
- What happens: README.md:43 says the engine "behaves identically across Android, iOS, the desktop JVM, macOS, Linux, Windows and the web". The project's own pages say otherwise: rendering.md lists clip edges and glyph snapping that only the AWT backend does and a table of which canvases can read a backdrop (a non-isolated blending group paints differently where it cannot); compose-viewer.md:301 says a pinch is "Sharp on Android. Soft on iOS, the desktop and the web"; epub.md:455 says MathML "glyphs draw in the host font", epub.md:356 says fallback text uses Standard 14 metrics while the host draws it; Module.md of the native renderer says "The back ends differ in a few details"; and the gotcha list records a corpus page that scores 0.0046 on a Mac and 0.017 on Linux because a non-embedded font "scores by the host's fonts". The parsing and layout are identical; the pixels are not, and a reader who takes the sentence at face value will file platform-difference bugs the docs already own.
- What should happen: the sentence says what is true: one parser, layout and text engine on every platform, with pixels that differ only where a page needs a host font or a platform canvas (and a link to "How the renderers draw"); the Skia renderer is the one that gives "the same output on every target", as rendering.md:17 already says.
- How to reproduce or evidence: compare README.md:43 with docs/rendering.md:286-333.
- Cross-effects: none seen.
- Fix sketch: one sentence in README.md and docs/index.md.

### BUILD-DOCS-TESTS-09: KitePDF has no release workflow; a release is a signed publish from a developer machine with automatic Central release and no CI gate
- Repo: KitePDF
- Area: .github/workflows, library.module-template.yaml, CONTRIBUTING.md
- Kind: design
- Severity: low
- Confidence: verified
- Where: .github/workflows/ (ci.yml and docs.yml only); library.module-template.yaml:36-40 (`signArtifacts: true`, `mavenCentral: publishingMode: auto`); CONTRIBUTING.md "Consumer check" (manual steps); /home/user/ImageKodec/.github/workflows/publish.yml:1-47
- What happens: ImageKodec publishes from a `release: published` event on a macOS runner, after `./kotlin check abi klibAbi`, with the signing key and the Central credentials as repository secrets and a dry-run mode. KitePDF has nothing of the kind: CONTRIBUTING.md asks the developer to run `./kotlin publish mavenLocal` and the consumer test by hand "before a release", and the real publish (`./kotlin publish mavenCentral`) is run on a Mac with `publishingMode: auto`, which releases the deployment on Central without a manual review step. There is no record of which toolchain, JDK, Kotlin/Native and Node produced a release, no check that the tag's CI run was green, and the release's ABI dumps are whatever the developer's checkout had. The consumer test on CI only ever sees Maven Local builds of the current version, never the bytes that reached Central (the `-PkitepdfRepo=central` run is manual).
- What should happen: a `publish.yml` like ImageKodec's, triggered by a release, that builds every target on macOS, runs `./kotlin check abi androidAbi klibAbi version corpus`, publishes, and then runs `consumer-test` with `-PkitepdfRepo=central` after the artifacts are visible.
- How to reproduce or evidence: `ls .github/workflows` in the two repositories.
- Cross-effects: finding 01 (notices) and 04 (bytecode level) would both be caught by a release job that inspects the artifacts.
- Fix sketch: copy ImageKodec's publish.yml, add the consumer test against Central as a second job that waits for sync.

### BUILD-DOCS-TESTS-10: Stale references in KDoc and DIFFTEST.md: a planning file that no longer exists, "no embedding" on a builder that embeds, and Gradle flags on a toolchain build
- Repo: KitePDF
- Area: kitepdf-epub, kitepdf-pdf, kitepdf-native-renderer/DIFFTEST.md
- Kind: docs
- Severity: low
- Confidence: verified
- Where: kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:68; kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:25-26 against :36-37 and :104; kitepdf-native-renderer/DIFFTEST.md:74 and :87
- What happens: (1) The class KDoc of `EpubDocument` ends with "See EPUB_ROAD_TO_PERFECTION.md"; no such file exists anywhere in the tree (`find . -name 'EPUB_ROAD*'` is empty) and CLAUDE.md says "There is no private planning file, and there never will be one again", so the generated API reference sends readers to a document that was deleted. (2) The class KDoc of `PdfBuilder` says "text uses the standard-14 fonts (no embedding)", while the same file keeps `embeddedFontNames` and `resolveEmbedded(font: EmbeddedFont)` and docs/writing.md:144-170 documents `EmbeddedFont.load` with subsetting. (3) DIFFTEST.md:74 tells the reader to run the EPUB sweep with `--tests '*EpubDifferentialTest*'`, the Gradle flag that the toolchain does not have (CONTRIBUTING.md uses `--include-classes`), and DIFFTEST.md:87 points at "the `build` job of `.github/workflows/ci.yml`", which is named `jvm` since the migration.
- What should happen: KDoc and DIFFTEST.md describe the current code and commands; the docs job could grep the generated site for file names that do not exist in the repository.
- How to reproduce or evidence: the line references above.
- Cross-effects: none seen.
- Fix sketch: delete the sentence in EpubDocument.kt:68, rewrite PdfBuilder.kt:25-26, replace the two DIFFTEST.md references.

### BUILD-DOCS-TESTS-11: The README's "What else you need" table and the Install section leave out two things a first build hits
- Repo: KitePDF
- Area: README.md
- Kind: docs
- Severity: low
- Confidence: verified
- Where: README.md:112-123 (the table says "Two artifacts need something extra"), README.md:103-110 (the server example "or kitepdf-skia-renderer"), docs/getting-started.md:35 (the fuller table), CHANGELOG.md:1034-1035, kitepdf-compose-viewer/module.yaml:27-29 and compose.module-template.yaml:5 (Compose 1.13.0-alpha02 exported)
- What happens: (1) README.md:108 proposes `kitepdf-skia-renderer` for a server with no Compose UI, and the table at :117-120 lists only the Ktor engine and the Android repository as extras. docs/getting-started.md:35 knows the third one: on the JVM without Compose the Skia renderer also needs `org.jetbrains.skiko:skiko-awt-runtime-<os>:0.153.0`, because `$libs.skiko` resolves to `skiko-awt` without a native runtime (kitepdf-skia-renderer/module.yaml:41-48 lists all six runtimes for its own tests for that reason). A reader who follows the README's server example gets an `UnsatisfiedLinkError` at the first render. (2) `kitepdf-compose-viewer` exports `compose.runtime`, `compose.foundation` and `compose.ui` at `1.13.0-alpha02` (module.yaml:27-29, and the released POM shows the three at that version), so adding the viewer raises any app's Compose to an alpha. CHANGELOG.md:1034 says so in the 0.13.0 notes and ImageKodec's README (lines 55-57) warns its users in the Install section; KitePDF's README and getting-started say nothing.
- What should happen: the README table has three rows (Ktor engine, the Android repository, the Skiko runtime on a JVM without Compose), and the Install section states the Compose version the viewer brings.
- How to reproduce or evidence: compare README.md:117-120 with docs/getting-started.md:35; `curl -sSL https://repo1.maven.org/maven2/io/github/yuroyami/kitepdf-compose-viewer/0.13.0/kitepdf-compose-viewer-0.13.0.pom | grep -A2 '<artifactId>ui</artifactId>'`.
- Cross-effects: finding 04 (JDK) belongs in the same table.
- Fix sketch: one row and one sentence in README.md.

### BUILD-DOCS-TESTS-12: Three KiteJS probe classes in test@jvm can never run under the toolchain, and no document says how to run them
- Repo: KiteJS
- Area: kitejs-rhino/test@jvm
- Kind: test-gap
- Severity: low
- Confidence: likely
- Where: kitejs-rhino/test@jvm/io/github/yuroyami/kitejs/api/AsmRealModuleProbe.kt:20-27, kitejs-rhino/test@jvm/io/github/yuroyami/kitejs/api/AsmProfileProbe.kt:14-44, kitejs-rhino/test@jvm/io/github/yuroyami/kitejs/rhino/asm/AsmCodeHistogram.kt:23-33
- What happens: the three classes carry `@Test` functions gated by `assumeTrue` on `KITEJS_PROFILE` and `KITEJS_ASM_FILE`, so they are opt-in developer probes, not regression tests. The toolchain's JVM runner only runs classes named `Test*`, `*Test` or `*Tests` (KitePDF's CLAUDE.md records it, and KitePDF renamed four classes for it in commit 74d665f), so `KITEJS_PROFILE=true ./kotlin test -m kitejs-rhino -p jvm` never reaches them, and KiteJS's README, CONTRIBUTING.md and docs/ do not mention them (`grep -rn "Probe\|KITEJS_PROFILE" README.md CONTRIBUTING.md docs/` is empty). They are not lost product tests, but they are dead code for anyone who builds with the toolchain, and the KDoc of each ("Opt in with `KITEJS_PROFILE=true`") promises a path that does not work. Whether `--include-classes='*Probe'` bypasses the name filter was not checked (no JVM build of KiteJS was made here).
- What should happen: either the classes get a `Test` suffix (they already skip cleanly) and docs/asm-js.md or CONTRIBUTING.md names the two variables, or they move to `tools/` as programs.
- How to reproduce or evidence: `KITEJS_PROFILE=true ./kotlin test -m kitejs-rhino -p jvm` and look for `AsmProfileProbe` in the report.
- Cross-effects: none seen.
- Fix sketch: rename to `AsmProfileProbeTest` and friends, add two lines to CONTRIBUTING.md.

### BUILD-DOCS-TESTS-13: The opt-in DoomPDF benchmarks on Apple and web targets report PASS when nobody opted in
- Repo: KitePDF
- Area: kitepdf-javascript/test@apple, kitepdf-javascript/test@web
- Kind: test-gap
- Severity: info
- Confidence: verified
- Where: kitepdf-javascript/test@apple/io/github/yuroyami/kitepdf/javascript/DoomPdfNativeBenchmark.kt:36-37; kitepdf-javascript/test@web/io/github/yuroyami/kitepdf/javascript/DoomPdfWebBenchmark.kt:42
- What happens: both tests do `if (!enabled) return println("Run with KITEPDF_DOOM=true ...")`. kotlin-test on Kotlin/Native and JS has no assumption API, so the only alternative to this early return is a failure, and the class names do not matter on those runners (they run every `@Test`). The result is the shape that CLAUDE.md gotcha #44 warns about: the `apple` and `web` CI jobs show one more passing test each, and a reader of the report cannot tell that the benchmark did not run. The JVM twin (`DoomPdfBenchmarkTest`) uses `assumeTrue`, which the report shows as skipped.
- What should happen: the report distinguishes "did not run" from "passed". On the web the TeamCity protocol that run.py parses has `##teamcity[testIgnored ...]`; on native, a dedicated `@Ignore`d twin or a `kotlin.test` `assertTrue(enabled, ...)` behind an `-Dkitepdf.doom` flag that CI never sets both work.
- How to reproduce or evidence: run `./kotlin test -p macosArm64 -m kitepdf-javascript` without `KITEPDF_DOOM` and count the passes.
- Cross-effects: none seen.
- Fix sketch: print `##teamcity[testIgnored name='doom' message='opt-in']` from the web test and use `@Ignore` plus a `--include-classes` opt-in on native.

### BUILD-DOCS-TESTS-14: ImageKodec's FuzzTest runs the same mutants forever: three fixed seeds, fixed counts, no override
- Repo: ImageKodec
- Area: imagekodec/test/io/github/yuroyami/imagekodec/FuzzTest.kt
- Kind: test-gap
- Severity: low
- Confidence: verified
- Where: imagekodec/test/io/github/yuroyami/imagekodec/FuzzTest.kt:40 (the `Rng` class), :358 (`Rng(0x7B16_2002)`), :382-383 (`repeat(600)` and `repeat(300)` per seed), :407 (`Rng(0x5EED_1234)`), :409 (`repeat(400)`), :420 (`Rng(0x1BADB002)`), :422 (`repeat(300)`)
- What happens: every run of the suite, on every target, in every CI shard, mutates the same bytes of the same seed files in the same order. That makes a failure reproducible, which the KDoc wants (FuzzTest.kt:21), but it also means the suite stopped finding new bugs the day it first passed: 600 one-byte, bit-flip and multi-byte mutants per seed is a fixed set, and the CI matrix (ci.yml, `test-js` four shards of eight, `tools/run-shards.sh`) spends its parallelism on repeating it. `grep -n "getenv\|getProperty" FuzzTest.kt` finds no way to change the seed or the count, so the nightly or the Linux release link cannot explore further without a code change.
- What should happen: the seed comes from an environment variable with the fixed value as the default (`IMAGEKODEC_FUZZ_SEED`, printed at the start of the run so a failure names it), and one scheduled job runs with a time-based seed and a larger count; the ci.yml comment block already documents the sharding that would make such a job cheap.
- How to reproduce or evidence: the line references above.
- Cross-effects: KitePDF has no fuzz test at all in its suites (`MalformedGlyfTest` is one reproducer from an offline run); the decompression-bomb tests are the only input-mutation coverage.
- Fix sketch: read `IMAGEKODEC_FUZZ_SEED` and `IMAGEKODEC_FUZZ_ROUNDS` in `FuzzTest`, add a weekly workflow that sets them.

### BUILD-DOCS-TESTS-15: Engine plumbing is public API: KiteLock, currentThreadId, a MutableList DOM and the GSUB substitute call ship in every ABI dump
- Repo: KitePDF
- Area: kitepdf-core public API
- Kind: api
- Severity: low
- Confidence: verified
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/KiteLock.kt:8-9 and :26-30; kitepdf-core/api/jvm/kitepdf-core.api:202-204 (`KiteLock_jvmKt.currentThreadId`) and the `KiteLock_androidKt` twin in api/android; kitepdf-core/api/kitepdf-core.klib.api:593 (`substitute(MutableList<GsubGlyph>, ...)`), :1559 (`RecordingCanvas.calls: MutableList`), :2852-2855 (`KiteXmlNode.Element` constructor and `children: MutableList`)
- What happens: the KDoc of `KiteLock` says "Engine-internal plumbing that must cross module boundaries ... not a public concurrency API", yet `KiteLock`, `withLock` and `currentThreadId()` are `public` on every target, appear in the JVM, Android and klib dumps, and the JVM facade classes are named after their platform source files (`KiteLock_jvmKt`, `KiteLock_androidKt`), a name that changes when the file is renamed. The XML DOM that the SVG, EPUB and XPS parsers build exposes `children` as `MutableList`, so a consumer can mutate a tree the layout holds (EpubDocument.kt:251 warns about exactly that for another object). `RecordingCanvas.calls` is a `MutableList` too, and `substitute(MutableList<GsubGlyph>, ...)` on the font object is a shaping step that only `kitepdf-epub` calls. With `explicitApi: strict` each of these was written `public` by hand because the toolchain has no friend-module mechanism, and the ABI check now freezes them.
- What should happen: cross-module internals carry `@KiteInternalApi` (an opt-in marker like `KiteRawApi`, `RequiresOptIn` level ERROR) so they stay out of the documented surface and a consumer cannot call them by accident; `children` and `calls` return `List`.
- How to reproduce or evidence: `grep -n "currentThreadId\|KiteLock_" kitepdf-core/api/jvm/kitepdf-core.api`; `grep -n "kotlin.collections/Mutable" kitepdf-core/api/kitepdf-core.klib.api`.
- Cross-effects: the Dokka site (`reportUndocumented`) lists them as API; finding 16 compounds the freeze.
- Fix sketch: add `@RequiresOptIn(level = ERROR) annotation class KiteInternalApi` to kitepdf-core, annotate `KiteLock`, `withLock`, `currentThreadId`, `substitute`, `RecordingCanvas`, and `@JvmName("KiteLockKt")` the platform files.

### BUILD-DOCS-TESTS-16: 150 public data classes freeze their constructor order into copy and componentN
- Repo: KitePDF
- Area: public API of kitepdf-core and kitepdf-pdf
- Kind: api
- Severity: info
- Confidence: verified
- Where: kitepdf-core/api/jvm/kitepdf-core.api (80 `component1` classes, 61 `copy`), kitepdf-pdf/api/jvm/kitepdf-pdf.api (73 and 46), for example `PdfPermissions` at :970-1007 (`copy (ZZZZZZZZI)` with eight booleans in a row), `KiteRenderSpec.Rasterized` in kitepdf-compose-viewer/api/jvm:419-441
- What happens: every public data class publishes `copy$default`, `componentN` and a constructor whose positional order is the ABI. Adding one field (the next permission bit of ISO 32000-2, a sixth knob on `Rasterized`) changes `copy`'s JVM signature, breaks every compiled caller of `copy`, and shifts `componentN` for destructuring; a reordering silently swaps eight booleans of `PdfPermissions` with no compile error at the call site. The README and docs use `PdfPermissions.allowAll.copy(canModifyContents = false)` as the way to build permissions, so the `copy` of that class is the documented API. docs/about.md:25 says the API may change in a minor release before 1.0, so this is a known cost today, but each release that keeps a data class public makes the 1.0 freeze harder.
- What should happen: the value types that are part of the documented API (`PdfPermissions`, the render and zoom specs, `KiteHighlight`, `KiteSearchHit`, `EpubSettings`) become plain classes with named-argument constructors and explicit `copy` functions whose parameter lists can grow, before 1.0.
- How to reproduce or evidence: `grep -c component1 */api/jvm/*.api`.
- Cross-effects: finding 15.
- Fix sketch: a tracking issue for 1.0 that lists the data classes to convert; the ABI dumps make the conversion visible.

### BUILD-DOCS-TESTS-17: Three published modules are tested on the JVM only, and the CoreGraphics canvas only on macOS, although they ship for up to 17 targets
- Repo: KitePDF
- Area: kitepdf-net, kitepdf-skia-renderer, kitepdf-media, kitepdf-native-renderer
- Kind: test-gap
- Severity: low
- Confidence: verified
- Where: kitepdf-net (`test@jvm` only, 17 platforms in module.yaml:6-23); kitepdf-skia-renderer (`test@jvm` only, 12 platforms); kitepdf-media (`test@jvm` only, 4 platforms); kitepdf-native-renderer (`test@macosArm64` for CoreGraphics, 7 Apple and web platforms)
- What happens: `KiteDoc.openUrl`, `downloadBytes` and `EpubResourceFetcher` in kitepdf-net are common code with platform-specific Ktor engines underneath, and nothing runs them on JS, wasmJs, iOS or Linux; the Skia renderer's `PdfPageRasterizer` and `EpubPageRasterizer` are common Skiko code that runs on Android, iOS, macOS, Linux and the web with a different Skiko backend each, and only the AWT backend is tested; kitepdf-media's `KiteMediaOverlay` and `KiteReadAloud` have a JVM scene test and nothing on Android or iOS, the two targets a book reader ships to. The CoreGraphics canvas (`src@apple`) has its raster and flip tests in `test@macosArm64` only (the gotchas #289 and #308 came from that canvas), while the iOS simulator job runs just the common tests of the module. docs/platforms.md:64 says "CI compiles an artifact that a row does not name, but it does not test that artifact on that target"; the table does not say that these three artifacts are not tested on any non-JVM target at all.
- What should happen: the common tests of kitepdf-net run with `ktor-client-mock` on every target (the mock engine is multiplatform), the Skia rasterizer gets a common PNG round-trip test that the apple, web and linux jobs run, the CoreGraphics tests move from `test@macosArm64` to `test@apple`, and docs/platforms.md says which artifacts have no non-JVM tests.
- How to reproduce or evidence: `ls -d kitepdf-net/test* kitepdf-skia-renderer/test* kitepdf-media/test* kitepdf-native-renderer/test*`.
- Cross-effects: finding 07.
- Fix sketch: move `kitepdf-net/test@jvm` tests that only need `MockEngine` to `test`, add `test-dependencies: [$libs.ktor.client.mock]`; add `kitepdf-skia-renderer/test` with one synthetic page.

### BUILD-DOCS-TESTS-18: The consumer test resolves the same version from Maven Local and Maven Central, so a module missing from the local publish is taken from the last release without notice
- Repo: KitePDF
- Area: consumer-test/settings.gradle.kts, consumer-test/build.gradle.kts, .github/workflows/ci.yml
- Kind: test-gap
- Severity: low
- Confidence: likely
- Where: consumer-test/settings.gradle.kts:20-27 (`mavenLocal()` then `mavenCentral()`), consumer-test/build.gradle.kts:7-10 (the version under test is `settings.publishing.version`, `0.13.0`), library.module-template.yaml:22 (`version: 0.13.0`), .github/workflows/ci.yml:316-323
- What happens: the version under test is the version in library.module-template.yaml, which stays `0.13.0` after the release until the next bump, and `0.13.0` is already on Maven Central. Gradle resolves each module from the first repository that has it, module by module. If the Maven Local publish of the `api` job leaves one artifact out (a renamed artifact ID, a platform whose publish task was skipped, a POM that names a dependency the local publish did not write), Gradle takes that artifact from Central's 0.13.0 and the test passes against the released bytes instead of failing on the packaging mistake the test exists to catch. The CHANGELOG's Unreleased section lists exactly such renames (`-wasmjs` for `-wasm-js`, the root POM now depending on `-jvm`): on the JVM the consumer sees the root POM and the `-jvm` jar, and a mismatch between them would be covered by Central.
- What should happen: the version published to Maven Local for the consumer test is one that Central does not have (`0.13.0-ci` or the commit hash), or the consumer build excludes Central for the `io.github.yuroyami:kitepdf*` group (`exclusiveContent` with `mavenLocal()` for that group), so a missing artifact fails.
- How to reproduce or evidence: delete `~/.m2/repository/io/github/yuroyami/kitepdf-core-jvm` after the local publish and run `cd consumer-test && ./gradlew test`; it passes.
- Cross-effects: finding 09.
- Fix sketch: `dependencyResolutionManagement { repositories { exclusiveContent { forRepository { mavenLocal() }; filter { includeGroupAndSubgroups("io.github.yuroyami") } } ... } }` when `kitepdfRepo != "central"`, and keep KiteJS on Central by listing it in a second filter.

### BUILD-DOCS-TESTS-19: KitePDF and KiteJS have no SECURITY.md, though both parse untrusted input
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

### BUILD-DOCS-TESTS-21: 3.1 MB of hyphenation classes that only kitepdf-epub uses ship in kitepdf-core, so every PDF-only consumer pays for them
- Repo: KitePDF
- Area: kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen, kitepdf-compose-viewer/module.yaml
- Kind: design
- Severity: low
- Confidence: verified
- Where: kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen (71 files, 3.1 MB of source); kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/Hyphenator.kt:20-197; kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt (the one caller of `Hyphenator`); kitepdf-compose-viewer/module.yaml:24-26 (`//kitepdf-pdf` and `//kitepdf-epub`)
- What happens: in the built `kitepdf-core-jvm.jar` (6,741,167 bytes) the `text/hyphen` classes are 3,098,036 bytes, 46 percent of the artifact. `grep -rl Hyphenator */src*` finds one user outside the package: `kitepdf-epub/.../BoxLayout.kt`. kitepdf-pdf, kitepdf-svg, kitepdf-xps and kitepdf-cbz never hyphenate, yet the README's "server or command-line tool that only turns PDF pages into images" (README.md:103-110) and every Android app that shows PDFs only download, dex and ship the 71 pattern sets. The Compose viewer depends on both engines (module.yaml:24-26), so a PDF-only viewer app also gets kitepdf-epub, kitepdf-svg and kotlinx-coroutines. On the web the JS and wasm bundles carry the same tables.
- What should happen: the pattern sets live in kitepdf-epub (or a `kitepdf-hyphenation` artifact that kitepdf-epub depends on), and the viewer's format support is split or made optional so a PDF-only app does not depend on the EPUB engine.
- How to reproduce or evidence: `unzip -l build/tasks/_kitepdf-core_jarJvm/kitepdf-core-jvm.jar | awk '/text\/hyphen\//{s+=$1} END{print s}'`; `grep -rln "Hyphenator\b" --include=*.kt */src* | grep -v core/text/`.
- Cross-effects: finding 01 (the sets' notices), the CLAUDE.md gotcha about the 192 MB Android heap (class loading of 71 sets is lazy per `Hyphenator.forLanguage`, but the dex is not).
- Fix sketch: move `core/text/hyphen` and `Hyphenator` to kitepdf-epub (the package name can stay), update the ABI dumps; longer term, let the viewer take the engine as a parameter instead of a dependency.

### BUILD-DOCS-TESTS-22: No page says how an iOS app without Kotlin consumes KitePDF, and the framework script that makes it possible is undocumented
- Repo: KitePDF
- Area: docs/platforms.md, docs/getting-started.md, scripts/apple_frameworks.py, library.module-template.yaml
- Kind: docs
- Severity: low
- Confidence: verified
- Where: scripts/apple_frameworks.py:2-13 and :29-40 (ten framework names, the "dynamic iOS frameworks that the Gradle build linked"); docs/platforms.md:1-33; library.module-template.yaml:42-45 (`-Xexport-kdoc`); `grep -rni "xcframework\|swift\|cocoapods" README.md docs/*.md CHANGELOG.md` (empty)
- What happens: Maven Central holds klibs for the Apple targets and nothing else; no XCFramework, no Swift package, no CocoaPods spec. A Swift-only app cannot add a klib; it needs a Kotlin module of its own that depends on KitePDF and exports a framework. The repository has the tool for the other path (`scripts/apple_frameworks.py` links debug and release frameworks and XCFrameworks for ten modules from the klibs, and the template keeps KDoc in the klibs so those headers are documented), but no README, docs page or CONTRIBUTING section mentions the script, the frameworks or the iOS sample (`sample-ios/module.yaml`, product `ios/app`), and docs/platforms.md describes targets without saying that Apple consumption is Kotlin Multiplatform only.
- What should happen: docs/platforms.md (or getting-started) has an "iOS" section: a KMP app adds the coordinates in its shared module; a Swift-only app gets an XCFramework built by `scripts/apple_frameworks.py`, with the command, or the releases page attaches the XCFrameworks that the script builds.
- How to reproduce or evidence: the grep above.
- Cross-effects: finding 01 (a framework bundle needs the licence texts too), finding 09 (a release job could attach the XCFrameworks).
- Fix sketch: a docs section, and a `release` job step that runs `scripts/apple_frameworks.py --build-type release` and uploads the XCFrameworks.

### BUILD-DOCS-TESTS-23: Some automated EPUB conformance checks assert only that the pass sentence shows, not that the fail sentence does not
- Repo: KitePDF
- Area: kitepdf-compose-viewer/test@jvm/io/github/yuroyami/kitepdf/compose/EpubConformanceChecks.kt
- Kind: test-gap
- Severity: low
- Confidence: suspected
- Where: kitepdf-compose-viewer/test@jvm/io/github/yuroyami/kitepdf/compose/EpubConformanceChecks.kt:109 (`ocf-metainf-inc`: `"Test passes if this page ..." in text(0)`), :116 (`ocf-url_manifest`), :117 (`ocf-url_relative`), against :110-112 which also assert `"Test fails" !in text(0)`
- What happens: the W3C suite's books usually carry both sentences and let the feature under test decide which one shows (through CSS, a script, a fallback or the spine). The checks for `ocf-package_arbitrary`, `ocf-package_multiple` and `ocf-metainf-manifest` assert the pass sentence present and the fail sentence absent; the three at lines 109, 116 and 117 assert only presence. For a book whose fail sentence is hidden by a style sheet or shown by a fallback, a regression that shows both sentences (a style sheet not applied, a fallback shown beside the main content) still passes the check and the report keeps saying "Passes". Whether those three books carry a fail sentence was not checked here (the suite is not in this container), so this needs a look at the three test books.
- What should happen: every text check asserts both the pass sentence and the absence of the fail sentence where the book has one; the harness can enforce it by scanning each book's XHTML for "Test fails" and requiring the check to mention it.
- How to reproduce or evidence: `grep -n '"Test passes' EpubConformanceChecks.kt | grep -v 'Test fails'`.
- Cross-effects: none seen.
- Fix sketch: a `showsPassOnly(chapter)` helper that encodes both conditions, used by every text check.

### BUILD-DOCS-TESTS-24: The docs site build floats mkdocs-material and runs with --strict
- Repo: KitePDF
- Area: .github/workflows/docs.yml, docs/requirements.txt
- Kind: design
- Severity: info
- Confidence: verified
- Where: docs/requirements.txt:4 (`mkdocs-material>=9.5`), .github/workflows/docs.yml:52-56 (`pip install -r docs/requirements.txt` then `mkdocs build --strict`)
- What happens: every docs deploy installs whatever mkdocs-material is newest, and `--strict` turns any new deprecation warning of a Material or MkDocs major release into a failed deploy (mkdocs.yml uses `!!python/name:material.extensions.emoji.*` and the `navigation.*` features, which Material has renamed before). The toolchain cache is pinned by checksum and Dokka by version; the docs site is the one build in the repository whose inputs are not pinned, and it deploys to GitHub Pages on every push that touches a `.kt` file.
- What should happen: `mkdocs-material==9.x.y` (and `mkdocs==`) pinned in docs/requirements.txt, bumped on purpose.
- How to reproduce or evidence: `cat docs/requirements.txt`.
- Cross-effects: none seen.
- Fix sketch: pin the two packages; the same for ImageKodec and KiteJS if their requirements.txt float.

### Capabilities and opportunities (not defects)

- A `KITEPDF_REQUIRE_ORACLES` switch and a CI job that installs every oracle (finding 06) would turn the largest body of correctness tests in the repository (shaping, fonts, signatures, JBIG2, JPX) into a gate; today it is a Mac-only habit. Cost: a day; reach: every EPUB and font change.
- A release workflow with artifact inspection (findings 01, 04, 09): the Kotlin Toolchain has the publish command and ImageKodec has the template; adding "unzip the jar and assert LICENSE, NOTICE and the class-file version" is an hour of YAML.
- Apple XCFrameworks on the releases page (finding 22): `scripts/apple_frameworks.py` already links them; publishing them opens KitePDF to Swift-only apps, which is most iOS document apps.
- A `kitepdf-hyphenation` split or a move into kitepdf-epub (finding 21) cuts the PDF-only footprint by about 3 MB of classes on every platform.
- A non-JVM consumer check: the consumer test proves the Gradle JVM path only. One Android library project (`com.android.library` with minSdk 21) that depends on the viewer and the Skia renderer would have caught finding 03 at publish time, and a small Compose web project would prove the npm and klib resolution of the JS and wasmJs artifacts that no test touches today.
- A per-commit test count in the CI summary (passed, failed, skipped per module from `build/reports/*/jvm/TEST-*.xml`) so a jump in "skipped" is visible without opening the artifact.

(The lane's list of what it read and found sound is in FABLE_AUDIT_coverage.md.)

## Duplicates and cross-references between lanes

Two lanes sometimes reached the same defect from different directions. The entries below say which finding to post and which to fold into it, so the issue tracker gets one issue per defect.

- XCUT-01 and FONTS-TEXT-19 are the same finding (hyphenation tables in kitepdf-core). Post XCUT-01, which has the measurements; FONTS-TEXT-19 adds the independent confirmation.
- FONTS-TEXT-07 and PDF-PARSE-06 are the same finding (a broken /CIDToGIDMap stream escapes font loading and fails the page). Post FONTS-TEXT-07, which also covers text extraction and redaction; PDF-PARSE-06 confirms the parse side.
- VIEWER-API-09 and VIEWER-RUNTIME-08 are the same finding (accessibility nodes rebuilt on every form revision). Post VIEWER-API-09, which also carries the role question; VIEWER-RUNTIME-08 adds the per-frame cost on the web.
- VIEWER-API-03 and the cross-lane note of EPUB-LAYOUT about `reverseLayout` under an RTL locale are the same finding. Post VIEWER-API-03.
- PDF-PARSE-08 (saveRewritten drops /Encrypt) belongs with the PDF-WRITE lane's encryption findings (PDF-WRITE-14, -20); post it as its own issue, labelled area:pdf.
- XCUT-06 (no document close, uncancelled remote scope) and the VIEWER-RUNTIME coverage note "there is no close() on any document" describe the same gap from two sides; post XCUT-06.
- PDF-RENDER-11 (indirect scalars in ExtGState, pattern, shading and function dictionaries) is the cause behind one of the two cases of PDF-RENDER-02 (a /XStep given indirectly); post both, and let PDF-RENDER-02 reference PDF-RENDER-11.
- XCUT-02 (the viewer depends on the EPUB module) and VIEWER-RUNTIME-04 (the media overlay lays a chapter out in composition) are independent; both stand.
- PLATFORM-09 and PDF-RENDER-09 are the same finding (page-sized layers for every group and soft mask on Skia and Android; PLATFORM adds CoreGraphics). Post PDF-RENDER-09 with the CoreGraphics case folded in.
- PLATFORM-16 and PDF-RENDER-08 are the same finding (luminosity weights differ between canvases); PLATFORM-16 adds that the native canvases use 77/150/29 of 256 and the Compose viewer 0.299/0.587/0.114, so three formulas exist, not two. Post PDF-RENDER-08 with PLATFORM-16's table.
- PLATFORM-08 extends PDF-RENDER-10 (Skia native objects left to the cleaner) to fonts, text blobs, shaders and evicted images. Post one issue.
- PLATFORM-10 and PDF-RENDER-24 are the same finding (CoreGraphics rebuilds a CGImage per draw); PLATFORM-10 adds the CTFont per run. Post one issue.
- PLATFORM-22 (native KiteLock.unlock without lock) and XCUT-04 (native KiteLock identity hash) are two defects in one 60-line file; post them together or as one "harden the native KiteLock" issue.
- PLATFORM-03 and PLATFORM-04 overlap VIEWER-RUNTIME-01 (raster step buffers held at once) in the same code path; post PLATFORM-03 for the SVG filter and VIEWER-RUNTIME-01 for the PDF group composite, and let them reference each other.

## Appendix A: the orchestrator's verification log

Format: finding id, verdict (confirmed / confirmed with correction / contested / not checked), note.

### VIEWER-API
- VIEWER-API-01 confirmed: PageBox (KiteDocView.kt:1330) and SpreadBox (:2318) call PageSlotContent without sharedRasterizer; KitePageRaster falls back to rememberKitePageRasterizer (:1369); the cache key holds fontEnvironment = textMeasurer (KitePageRasterizer.kt:250) and rememberKitePageRasterizer makes a measurer per call site (:501). Upgrade confidence to verified.
- VIEWER-API-02 confirmed: pagerScrollEnabled = userScrollEnabled && !overflows && ... (KiteDocView.kt:1167); pan handler returns at once when !spec.panEnabled (KiteGestures.kt:296).
- VIEWER-API-03 confirmed: PageTurn XORs LocalLayoutDirection (KiteDocView.kt:1125, :2185) while HorizontalPager gets layout.reverseLayout raw (:1177, :2226) and SpreadBox reverseOrder = layout.reverseLayout (:2203).
- VIEWER-API-04 confirmed: kiteTransformGestures(state, zoomSpec, scope, pageTap, pageTurn) at :1147 takes no userScrollEnabled; the turn fires in KiteGestures.kt:327-332.
- VIEWER-API-05 confirmed by the module's own test (ContinuousPrefetchSceneTest.kt:51-55) and the KDoc at KiteDocView.kt:169-175.
- VIEWER-API-06 confirmed: the SideEffect at KiteDocView.kt:266-296 writes adapter-independent per-view inputs into the shared state on every composition; onDispose at :335-336 clears kept chapters and the vector cache for the shared document.
- VIEWER-API-07 to 10: not re-read line by line (low severity); the spec init blocks at KiteDocViewSpecs.kt:210-214, :284-287, :341-344 were read earlier and match the description of what they check.

### VIEWER-RUNTIME
- VIEWER-RUNTIME-02 confirmed: KiteDocView.kt:1688-1694 (loads counter, runCatching, no stop after a failure).
- VIEWER-RUNTIME-03 confirmed: ScriptCall.kt:13-19 catches Exception; KitePageRasterizer.kt:280-284 documents why Throwable is needed (#219).
- VIEWER-RUNTIME-04 confirmed: KiteMediaOverlay.kt:116 remember(epubPage) { epubPage.media }; EpubDocument.kt:3146 media = buildMedia(laidOut()); laidOut() = doc.render(chapter, index) (:1926-1927); render -> livePages -> paginateChapter under the chapter lock (:1247, :1056-1066).
- VIEWER-RUNTIME-06 confirmed: KiteWidgets.kt:188 remember(state.viewerDecorator) { PageBitmapCache(...) }; PdfPage holds its PdfDocument (PdfPage.kt:37).
- VIEWER-RUNTIME-07 confirmed: scriptScope set in the SideEffect (KiteDocView.kt:269) and never cleared by the DisposableEffects at :335-336, :367; post() launches on it (KiteDocViewState.kt:935).
- VIEWER-RUNTIME-01, 05, 08 to 12: not re-read line by line; 01 is marked likely by the lane and the memory arithmetic was not re-derived.
- Hand-off item "KiteBitmapCache() allocated per draw when imageCacheBudgetBytes = 0" confirmed at KiteDocView.kt:1718 (bitmaps = imageCache ?: KiteBitmapCache()).

### PDF-RENDER
- PDF-RENDER-01 confirmed: resolveIndexed (KiteColorSpace.kt:447-463) reads hival with no upper clamp; the lut is Array(hival.coerceAtLeast(0) + 1) (:150); the render loop (PageRenderer.kt:338-356) has try/finally and no catch, so the exception leaves render(). Correction: the lut lines are 146-150, not 146-155.
- PDF-RENDER-02 confirmed: PageRenderer.kt:1861 `if (tiles <= 0 || tiles > MAX_TILES) return`, MAX_TILES = 20_000L (:2816).
- PDF-RENDER-03 confirmed: index range from axs/ays (:1855-1859), tile matrix from the signed step (:1898).
- PDF-RENDER-07 confirmed: both JPX branches (KiteImageData.kt:422-445) set resolvedColorSpace from the decoder's string and never use resolvedCs.
- PDF-RENDER-08 confirmed on the KitePDF side: SkiaCanvas.kt:831-832 uses ColorFilter.luma (Skia's SkLumaColorFilter is BT.709, from knowledge of Skia, not run here).
- PDF-RENDER-11 confirmed in substance, citation corrected: the resolver-less reads are ExtGState.kt:64-72 and :88-90 (getReal("ca"), getReal("CA"), getReal("LW"), getInt("LC"), getInt("LJ"), getReal("ML")), not :251-277; PdfDictionary.getReal/getInt/getName (PdfObject.kt:95-101) return null for a PdfReference. /OP, /op, /OPM, /RI, /UseBlackPtComp and /SMask do go through deref (:81-98, :104), so the gap is the scalars named.
- PDF-RENDER-12 confirmed: "d1" sets type3IgnoreColor = true before the type3Depth check (PageRenderer.kt:1307-1309).
- PDF-RENDER-14 confirmed, citation corrected: bm.firstOrNull() is ExtGState.kt:68.
- PDF-RENDER-15 confirmed: no CalCMYK arm; else -> DeviceGray at KiteColorSpace.kt:330, :339, :397.
- PDF-RENDER-21 confirmed: ComposeCanvas.kt:692-699.
- PDF-RENDER-13 partly checked: no use of fillPattern on the image paths of PageRenderer (grep shows fillPattern read only at :1692, :2296-2308 for paths and text); the stencil tint comes from fillColor. Accept as verified.
- PDF-RENDER-04, 05, 06, 09, 10, 16 to 20, 22 to 26: not re-read line by line.

### FONTS-TEXT
- FONTS-TEXT-01 confirmed: CffFont.outline (CffFont.kt:109-114) builds CharstringInterpreter without a seacRenderer; the interpreter's seac branch is `seacRenderer?.invoke(...)` (CharstringInterpreter.kt:563); only Type1Font wires its own seac resolver.
- FONTS-TEXT-02 confirmed in structure: CompositeFont.textOf re-decodes the code's bytes through toUnicode.decodeAll (CompositeFont.kt:122), which segments by the ToUnicode CMap's codespace; the mis-split consequence follows from that design (the CMap code itself was not re-read).
- FONTS-TEXT-04 confirmed: `dict.getInt("MissingWidth")` at PdfFont.kt:659 reads the same dictionary that holds /FirstChar and /Widths (the font dictionary), while ISO 32000-1 Table 122 puts /MissingWidth in the font descriptor.
- FONTS-TEXT-07 confirmed: CidToGidMap.parseStream calls FilterChain.decode bare (CompositeFont.kt:244); PageRenderer.render calls loadFonts (PageRenderer.kt:303) before its try block.
- FONTS-TEXT-08 confirmed for the GPOS class matrix (OpenTypeKern.kt:144-151, `IntArray(class1Count * class2Count)` with two u16 counts) and for cmap format 12 (TtfCMap.kt:195); citation corrected (the lane cited lines past the end of OpenTypeKern.kt).
- FONTS-TEXT-16 confirmed: OpenTypeKern.parseGposKern ignores lookupFlag and skips every lookup whose type is not 2 (OpenTypeKern.kt:97-101); citation corrected.
- FONTS-TEXT-19 duplicates XCUT-01 (hyphenation footprint); keep one entry (XCUT-01 has the measurements) and note the lane's independent confirmation.
- FONTS-TEXT-03, 05, 06, 09 to 15, 17, 18, 20 to 22: not re-read line by line.

### PDF-WRITE (the lane ran its probes against the built jar; its fixtures, outputs and scripts are committed under audit/fixtures/writer/)
- PDF-WRITE-02 confirmed by the probe output: out_sharedform_redacted.pdf holds no copy of SHAREDFORMTEXT at all, so the one shared form object was rewritten rather than cloned.
- PDF-WRITE-03 confirmed: chainOf (SignatureValidator.kt:202-213) accepts an issuer on subject/issuer DER equality plus a valid signature only; X509.isCa (X509.kt:32, :67) is parsed and never read by the validator.
- PDF-WRITE-04 confirmed by the probe output: out_graft.pdf contains SOURCEPAGETWOSECRET and SOURCEPAGETHREESECRET after grafting page 1 alone.
- PDF-WRITE-05 and 06 confirmed: PdfBuilder writes `/Type /Font /Subtype /Type1 /BaseFont` with no /Encoding (PdfBuilder.kt:186-194); PdfText.encodeContentString writes `code <= 0xFF ? code : '?'` (PdfText.kt:41-42), so strings are Latin-1 bytes and anything above U+00FF becomes a question mark. Citation corrected: PdfText.kt:41-42, not :464-471.
- PDF-WRITE-07 to 20: not re-read line by line; 07, 08, 09 were executed by the lane.
- PDF-WRITE-01 confirmed by inflating the probe output: the rewritten page stream of out_actualtext_redacted.pdf reads `/Span << /ActualText (TOPSECRETACTUAL) >> BDC BT /F1 24 Tf 72 700 Td [-5890] TJ ET EMC`, so the replacement text survives the redaction of the run it stood for.

### KITEJS (all findings are KiteJS repository defects)
- KITEJS-01 confirmed: RhinoKiteJs.open sets the observer threshold to the budget itself when one is set (RhinoKiteJs.kt:366-373), and observeInstructionCount throws on `instructionsUsed >= budget` before it reads `config.interruptWhen` (:417-435), so with a budget the hook is first consulted at the moment the budget is already spent.
- KITEJS-04 confirmed: the handle cleaner appends with `now + h` on an AtomicReference<List<Int>> (QuickJsKiteJs.kt:103-109), one list copy per collected handle.
- KITEJS-05 confirmed: JsValue is `@JvmInline value class` (JsValue.kt:47-48); Kotlin/Native exports an inline class to Objective-C as its underlying type and exports none of its members, so a Swift caller of the Apple frameworks sees `id` in place of the value API (export rule, not run here).
- KITEJS-03 accepted as likely: `maxStackSize` defaults to 512 KiB (QuickJs.kt:61) and the KDoc two lines above names the 512 KiB secondary-thread stack on iOS as the case that needs a smaller value, so the default equals the whole stack of the thread the coroutine module runs on.
- KITEJS-02, 06 to 14: not re-read line by line.

### EPUB-LAYOUT
- EPUB-LAYOUT-01 confirmed: resolveLineHeight stores a unitless value as `s.toDouble() * b.fontSizePt` (StyleResolver.kt:942) and a child inherits `lineHeightPt` as that length (:1176), against CSS 2.1 10.8.1.
- EPUB-LAYOUT-02 confirmed: `colspan`, `rowspan` and `<col span>` are read with `coerceAtLeast(1)` and no upper bound (BoxBuilder.kt:763-764, :617).
- EPUB-LAYOUT-03 confirmed: Woff.toSfnt reads compLen as `u32(...).toInt()` (Woff.kt:37); the bounds check `e.woffOffset + e.compLen > b.size` passes a negative compLen and `copyOfRange(woffOffset, woffOffset + compLen)` (:47) then throws; FontRegistry.parse wraps TrueTypeFont.parse but not Woff.toSfnt (FontRegistry.kt:42-46).
- EPUB-LAYOUT-04 to 24: not re-read line by line.

### PDF-PARSE
- PDF-PARSE-02 CONFIRMED BY EXECUTION and raised to critical: four one-page PDFs made with pikepdf 10.17 (object streams on or off, encryption off, RC4/AES R4 or AES-256 R6 with an empty user password) were opened with PdfDocument.open on the JVM. plain_objstm.pdf and enc_classic_r4.pdf open and extract "Hello objstm"; enc_objstm_r4.pdf and enc_objstm.pdf both fail with `PdfFormatException: Repair found no catalog that leads to a page tree`. decodeObjectStream (PdfDocument.kt:905-920) parses the ObjStm container with Parser.readIndirectObject and hands the raw stream to FilterChain.decode without maybeDecrypt, which only the resolve path calls (:870). Every encrypted PDF whose catalog or pages live in an object stream (the default of qpdf, pikepdf and Acrobat's compressed saves) therefore fails to open; pikepdf, and by the lane's reading every other reader, opens the same files. The four fixtures are committed under audit/fixtures/encrypted-object-streams/.
- PDF-PARSE-03 confirmed: StandardSecurityHandler.decrypt returns `Aes.decryptCbc(key, ciphertext)` for `v >= 5` before the /StmF and /StrF algorithm check (StandardSecurityHandler.kt:84-88), so an /Identity crypt filter is ignored on V5 and V6 files.
- PDF-PARSE-04 confirmed in structure: keyLengthBits comes from /Length unclamped (:30), the key is `keyLengthBits / 8` bytes (:158, :197) and Rc4.ksa indexes `key[i % key.size]` (Rc4.kt:23), which throws ArithmeticException for an empty key.
- PDF-PARSE-06 duplicates FONTS-TEXT-07 (CIDToGIDMap decode escapes loadFonts); both lanes found it independently.
- PDF-PARSE-01: NOT reproduced on natural data. A probe deflated 200 KB of text, 200 KB of random bytes and 200 KB of text with java.util.zip at levels 1, 6 and 9 (random data makes zlib emit stored blocks after Huffman blocks) and the pure Inflate.decode matched the input at every level. The mechanism the lane describes is real in the code (decodeSymbol peeks `fastBits` bits through ensureBits and alignToByte drops the buffer without rewinding bytePos), but it needs more than seven bits left over after the end-of-block code and the next block's three header bits, which the end-of-block code of a natural dynamic block (a long code) rarely leaves. A second probe with a hand-made block whose end-of-block code is two bits long and whose longest code is fifteen bits is recorded below.
- PDF-PARSE-01 CONFIRMED BY EXECUTION with a crafted stream: a hand-built raw deflate stream (one dynamic Huffman block whose literal 'A' has a 1-bit code, whose end-of-block code is 2 bits and whose longest code is 15 bits, followed by a final stored block "XYZ") decodes as `AABXYZ` with java.util.zip.Inflater(nowrap = true), and the pure `Inflate.decode` throws `InflateException: Stored block LEN/NLEN mismatch`. The mechanism is as the lane says: decodeSymbol peeks `fastBits` (the table's longest code, Inflate.kt:233, :354) through ensureBits, and alignToByte (:131-134) drops the buffered whole bytes without rewinding bytePos. zlib's output on natural data did not trigger it in six block transitions (see above), because a natural end-of-block code is long; the stream is valid and a JS, Wasm, Apple, Linux or Windows build has no fast path to hide it. Severity kept at high for the parity consequence; the owner may prefer medium on frequency.
- PDF-PARSE-04 CONFIRMED BY EXECUTION: a file with `/Encrypt << /Filter /Standard /V 1 /R 2 /Length 0 ... >>` makes `PdfDocument.open(bytes, "")` throw `java.lang.ArithmeticException: / by zero` instead of a KiteFormatException or KiteWrongPasswordException.
- PDF-RENDER-01 CONFIRMED BY EXECUTION: `[/Indexed /DeviceRGB 2147483647 <FF0000>]` with `/CS0 cs 0 sc 0 0 100 100 re f` makes `renderTo` throw `NegativeArraySizeException: -2147483648` with no fill recorded.
- PDF-RENDER-02 CONFIRMED BY EXECUTION: a page-sized fill with a tiling pattern of 40 pt cells records 396 fills on a RecordingCanvas; the same fill with 2 pt cells records 0 fills and no error.
- PDF-RENDER-12 CONFIRMED BY EXECUTION: `0 0 0 0 10 10 d1 1 0 0 rg 0 0 100 100 re f` in page content records the fill in RgbColor(0, 0, 0) instead of red.

### EPUB-SCRIPT
- EPUB-SCRIPT-01 confirmed in structure: dispatch evaluates the event wrapper and then returns `host.lastEventResult` whatever happened (PdfScriptRunner.kt:690-691); `evaluate` answers null on a KiteScriptException (:701-704) and the wrapper's `finally` that calls `host.eventResult` (AcrobatApi.kt:656-664) is what a stopped script skips, so the previous event's result stands.
- EPUB-SCRIPT-02 confirmed: `Field.prototype.getArray = function () { return [this]; }` (AcrobatApi.kt:408), used by AFSimple_Calculate (AformApi.kt:265).
- EPUB-SCRIPT-03 confirmed in structure: formatAll runs `formatOnThread` for every field with a format action and keeps nothing (PdfScriptRunner.kt:754-759); no viewer or renderer source reads a formatted display value (grep for formattedValue, displayValue, format( in kitepdf-compose-viewer and the form state).
- EPUB-SCRIPT-04 accepted as likely: ScriptDom.insert (ScriptDom.kt:537) applies no depth limit while HtmlParser caps parsing at MAX_DEPTH = 32 (HtmlParser.kt:52); the overflow depth was not measured.
- EPUB-SCRIPT-05 to 22: not re-read line by line.

### IMAGEKODEC
- IMAGEKODEC-01 confirmed by reading the arithmetic: `ensure` grows with `var cap = out.size; while (cap < needed) cap = cap shl 1` (imagekodec/src/io/github/yuroyami/imagekodec/internal/flate/Inflate.kt:207-215); at out.size = 2^30 the shift gives Int.MIN_VALUE and then 0 for ever. Zlib.decompress passes no size hint when the expected size is above Int.MAX_VALUE (Zlib.kt:49), and PngDecoder allows 2^28 pixels (PngDecoder.kt:43, :56), which at 16-bit RGBA is 2^31 bytes. A host with less than 1 GiB free throws OutOfMemoryError before the loop; one with more hangs.
- IMAGEKODEC-03 duplicates PDF-RENDER-07 (JPX ignores the PDF /ColorSpace); post PDF-RENDER-07, which has the CMYK formula detail, and note the ImageKodec side (a raw-components decode) as the upstream half.
- IMAGEKODEC-02 and 04: not re-read line by line.

### BUILD-DOCS-TESTS
- BUILD-DOCS-TESTS-04 duplicates XCUT-03 (Java 21 bytecode undocumented), found independently; post one issue.
- BUILD-DOCS-TESTS-21 duplicates XCUT-01 and FONTS-TEXT-19 (hyphenation tables in core); the lane measured 46 percent of the released kitepdf-core-jvm.jar, which refines XCUT-01's 41 percent of the class files.
- BUILD-DOCS-TESTS-15 overlaps XCUT-07 (plumbing in the public API); XCUT-07 proposes the opt-in annotation, BUILD-DOCS-TESTS-15 lists the concrete members; post one issue with both.
- BUILD-DOCS-TESTS-12 is the KiteJS probe note the orchestrator also recorded (opt-in probes unreachable under the class-name filter); a one-line doc fix in KiteJS.
- BUILD-DOCS-TESTS-06 confirmed from ci.yml: the jvm job's only package install is fonts-noto-cjk, and hb-shape, pdfsig and the git-ignored MuPDF fonts are absent, so the oracle tests that assume on them skip on every CI run.
- BUILD-DOCS-TESTS-03 confirmed: kitepdf-skia-renderer/module.yaml sets no minSdk, so the template's 21 (library.module-template.yaml:17) applies, while kitepdf-compose-viewer (24), kitepdf-media (26) and kitepdf-native-renderer (29) override it and docs/platforms.md says 24 for the Skia renderer.
- BUILD-DOCS-TESTS-01 confirmed on the lane's downloaded 0.13.0 artifacts: kitepdf-core-jvm, kitejs-quickjs-jvm and imagekodec-jvm hold only META-INF/MANIFEST.MF and the kotlin_module file beside their classes (and the five QuickJS native libraries), with no LICENSE or NOTICE text for the URW fonts (OFL), the hyph-utf8 patterns, the PDFium port or QuickJS-ng (MIT).

### PLATFORM
- PLATFORM-01 confirmed: the only guard on the `<use>` walk is `if (depth > MAX_DEPTH) return` (SvgImage.kt:224) and drawUse walks its target with depth + 1 (:380), so a group of k self-referencing uses fans out k times per level; the lane ran it (k = 3 took 34 s on a no-op canvas).
- PLATFORM-02 confirmed: PathScanner.num consumes every digit and dot as one token (SvgImage.kt:2103) and answers 0.0 when the token is not a number (:2105); numbers() does the same (:1893-1897). The lane ran the two SVGO forms.
- PLATFORM-03 confirmed: runFilter converts the full step with FilterImage.of before computing shrink and resizing (SvgImage.kt:1396-1403); FilterImage holds four floats a pixel (FilterImage.kt:16-20).
- PLATFORM-22 confirmed from the orchestrator's own earlier read of KiteLock.native.kt:44-46 (`if (--depth == 0) owner.value = 0` with no ownership check).
- PLATFORM-04 to 21: not re-read line by line; 05 was run by the lane.
