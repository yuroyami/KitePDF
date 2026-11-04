# OPUS_AUDIT

An audit of KitePDF, with the parts of KiteJS and ImageKodec that KitePDF depends on. Every finding is written as an issue in the house style, so that it can be checked and then posted as it is. Nothing here is posted yet.

- KitePDF at `c4e7574`, KiteJS at `5882331`, ImageKodec at `aba04d0` (the heads on 2026-11-04).
- Each finding names the repository where its fix belongs. A problem that only KiteJS or ImageKodec can fix is filed there, even when KitePDF shows the symptom.
- Each finding has a severity and a confidence. **Confirmed** means every step was traced in the source or the behaviour was run. **Likely** means the evidence is strong but one step was not proven; its "How to reproduce" gives the probe to run.
- Known issues were left out. A finding that touches one says "Related: #n".

## How the audit ran

The source was split into ten lanes, each read end to end against the specs and against the reference implementations that CONTRIBUTING.md names: the Compose viewer, PDF parsing and bounds, PDF rendering and fonts, PDF writing and signatures, EPUB and SVG, canvases and the small formats, KiteJS and the script integration, ImageKodec, concurrency and platform semantics, and distribution, docs and tests. Each lane checked its candidates against the issue tracker and the CHANGELOG, and ran an adversarial pass to drop what did not hold.

The lead then built KitePDF at `c4e7574` for the JVM and ran the critical and high findings it could, with Poppler 24.02 (`pdftotext`, `pdftoppm`, `pdfinfo`) as an independent reader. Those runs are the paragraphs that start "Measured on the JVM at `c4e7574` by the lead". They moved two findings from Likely to Confirmed and corrected one claim (in the WOFF finding). `mutool`, PDFium and HarfBuzz were not available, and no Apple, Android device, JavaScript or Wasm target ran, so findings about those targets are read from source. The generators, fixtures and probe classes are not in the repository: they came with this audit as `audit-repro.tar.gz`, and every path in it that starts with `audit-repro/` is relative to that archive.

## Severity

- **Critical**: memory corruption, a crash or hang on input that a stranger can supply, data loss, a redaction or signature check that lies, a security bypass.
- **High**: wrong output on common documents, a leak or race that real apps hit, a broken public contract.
- **Medium**: wrong output on less common input, a performance cliff, an API trap.
- **Low**: edge cases and documentation.

## Summary

| Repository | Critical | High | Medium | Low | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| KitePDF | 17 | 25 | 57 | 31 | 130 |
| KiteJS | 1 | 3 | 1 | 2 | 7 |
| ImageKodec | 1 | 0 | 1 | 1 | 3 |

140 findings in all, 125 of them Confirmed. The lead reproduced 40 of them on the JVM (the Run column); the rest are traced in the source, and the Likely ones carry the probe that would settle them.

### KitePDF

| ID | Severity | Confidence | Run | Labels | Title |
| --- | --- | --- | --- | --- | --- |
| [KP-01](#kp-01) | critical | Confirmed | yes | area:core, kind:bug | A type 5 mesh shading with zero bits per coordinate and per component never finishes parsing |
| [KP-02](#kp-02) | critical | Confirmed | yes | area:core, kind:bug | A type 4 function grows its operand stack without limit, so a function of 370 bytes exhausts memory |
| [KP-03](#kp-03) | critical | Confirmed | yes | area:core, kind:bug | A TrueType glyph built from repeated composite components takes exponential time and memory to parse |
| [KP-04](#kp-04) | critical | Confirmed |  | area:pdf, kind:bug | A later revision can change the certification level and the field lock that judge it |
| [KP-05](#kp-05) | critical | Confirmed |  | area:pdf, kind:bug | A revision that rewrites an object stream changes the objects inside it, and RevisionChanges reports nothing |
| [KP-06](#kp-06) | critical | Confirmed |  | area:pdf, kind:bug | A revision that adds or removes an annotation or a field through /Annots or /Fields reports no change |
| [KP-07](#kp-07) | critical | Confirmed | yes | area:pdf, kind:bug | A certificate without the CA flag can issue a signer certificate that validate calls trusted |
| [KP-08](#kp-08) | critical | Confirmed | yes | area:pdf, kind:bug | Redaction keeps the /ActualText of the text it removes, and extractText() still returns that text |
| [KP-09](#kp-09) | critical | Confirmed | yes | area:pdf, kind:bug | Redaction keeps the data of a removed image when the page shares or inherits its /Resources |
| [KP-10](#kp-10) | critical | Confirmed | yes | area:core, area:epub, area:svg, kind:bug | Entities that expand to nothing escape the expansion budget, so a 600-byte document hangs every XML reader |
| [KP-11](#kp-11) | critical | Confirmed | yes | area:svg, area:epub, kind:bug | An SVG whose use elements fan out paints exponentially many shapes, so a 150-byte file hangs its render, its text and its links |
| [KP-12](#kp-12) | critical | Confirmed | yes | area:epub, kind:bug | A table cell's colspan or rowspan, or a col's span, is not clamped as HTML clamps it, so one attribute runs the layout out of memory |
| [KP-13](#kp-13) | critical | Confirmed | yes | area:epub, kind:bug | Frames that repeat one document are followed once per frame, so a book of eight small files hangs isScripted and the page paint |
| [KP-14](#kp-14) | critical | Confirmed | yes | area:pdf, kind:bug | A tiling pattern or a soft mask that paints with itself recurses until the stack overflows |
| [KP-15](#kp-15) | critical | Confirmed | yes | area:pdf, kind:bug | A `/Kids` that names its own node sends the page label, named destination and form field walks into endless recursion |
| [KP-16](#kp-16) | critical | Confirmed | yes | area:core, area:pdf, kind:bug | A function that names itself, or a Type 4 program of deep braces, recurses until the stack overflows |
| [KP-17](#kp-17) | critical | Likely | yes | area:pdf, kind:bug | The parser's nesting cap and the resolution depth cap multiply, so a 150 KB file needs about 130,000 stack frames |
| [KP-18](#kp-18) | high | Confirmed | yes | area:pdf, kind:api | A redacted copy of an encrypted PDF can only be saved without encryption, and nothing can encrypt it again |
| [KP-19](#kp-19) | high | Confirmed | yes | area:viewer, kind:bug | With pan turned off, a pager page that overflows the view can be neither scrolled nor swiped |
| [KP-20](#kp-20) | high | Confirmed | yes | area:viewer, kind:bug | An animated navigation call from the UI thread outside a composition throws for want of a frame clock |
| [KP-21](#kp-21) | high | Confirmed |  | area:pdf, kind:bug | A content stream operator can take any number of operands, and the operation cache counts them as nothing |
| [KP-22](#kp-22) | high | Confirmed | yes | area:pdf, kind:perf | Repair scans to the end of the file once for every stream that has no endstream, so a 4.5 MB file blocks open for minutes |
| [KP-23](#kp-23) | high | Confirmed | yes | area:core, kind:bug | An Indexed colour space with a large hival allocates hival + 1 colours |
| [KP-24](#kp-24) | high | Confirmed |  | area:core, kind:bug | An accented letter that a CFF font builds with seac draws nothing |
| [KP-25](#kp-25) | high | Confirmed |  | area:pdf, kind:bug | RevisionChanges calls a change to page content a field or annotation appearance when an appearance also names the object |
| [KP-26](#kp-26) | high | Confirmed |  | area:pdf, kind:bug | PdfSigner.prepareSignature drops the form's fields and the page's annotations, and undoes earlier edits |
| [KP-27](#kp-27) | high | Confirmed | yes | area:pdf, kind:bug | Redacting a form XObject on one page also redacts every other page that draws it |
| [KP-28](#kp-28) | high | Confirmed | yes | area:pdf, kind:bug | Redaction keeps the page's thumbnail and the private data of the application that made the page |
| [KP-29](#kp-29) | high | Confirmed |  | area:pdf, kind:bug | Revocation data of any date counts: a CRL that expired years before the signing says Good |
| [KP-30](#kp-30) | high | Confirmed | yes | area:pdf, kind:bug | An encrypted PDF whose objects live in object streams does not open, so it cannot be edited |
| [KP-31](#kp-31) | high | Confirmed | yes | area:epub, kind:bug | A WOFF or WOFF2 font can make the layout inflate and hold gigabytes, because no table size is checked against the font's total |
| [KP-32](#kp-32) | high | Confirmed |  | area:epub, kind:bug | A WOFF font whose table directory overflows an Int throws out of the font parser, and every chapter of the book fails to lay out |
| [KP-33](#kp-33) | high | Confirmed |  | area:epub, kind:bug | Two regular expressions over a book's CSS backtrack quadratically, so one style sheet stalls the layout of its chapters for minutes |
| [KP-34](#kp-34) | high | Confirmed |  | area:svg, kind:bug | SVG path data drops a number that starts with a dot right after another number, and misreads arc flags written without a separator |
| [KP-35](#kp-35) | high | Confirmed | yes | area:renderer, backend:awt, kind:bug | A dash pattern finer than a pixel makes the AWT canvas stroke millions of dashes, and a finer one ends the page render |
| [KP-36](#kp-36) | high | Confirmed |  | area:javascript, plan:javascript, kind:bug | A field event whose script does not compile, or is stopped, takes the previous event's result |
| [KP-37](#kp-37) | high | Confirmed |  | area:javascript, plan:javascript, kind:bug | `calculateNow()` inside a calculation starts a new calculation round, and the deadline never fires |
| [KP-38](#kp-38) | high | Confirmed |  | area:javascript, plan:javascript, kind:bug | A document can set any number of timers, and one pump runs every due timer without a limit |
| [KP-39](#kp-39) | high | Confirmed | yes | area:pdf, kind:bug | The name tree and revision walkers stop at depth 64 but keep no visited set, so a node that lists itself twice takes 2^64 steps |
| [KP-40](#kp-40) | high | Confirmed | yes | area:pdf, kind:bug | A PDF keeps every page's decoded content and two copies of its text for the life of the document, so reading or searching a long file fills the heap |
| [KP-41](#kp-41) | high | Likely |  | area:pdf, kind:bug | An object stream's /N sizes an array before anything checks it |
| [KP-42](#kp-42) | high | Likely |  | area:epub, area:javascript, kind:bug | A book's script can build a tree deeper than the parser allows, and the layout and the script DOM walk it recursively |
| [KP-43](#kp-43) | medium | Confirmed | yes | area:pdf, kind:bug | The documented loss of accents in standard fonts comes from a missing /Encoding, and the docs blame the fonts |
| [KP-44](#kp-44) | medium | Confirmed | yes | area:pdf, kind:bug | Search and text extraction keep ligature characters, so "find" does not match a page that draws "ﬁnd" |
| [KP-45](#kp-45) | medium | Confirmed | yes | area:viewer, kind:perf | In Paged, Spread and SinglePage layouts every page slot makes its own rasterizer, so a page that comes back misses the bitmap cache |
| [KP-46](#kp-46) | medium | Confirmed | yes | area:viewer, kind:bug | Page Down, Space and the arrow keys skip the rest of a page that is taller than the view |
| [KP-47](#kp-47) | medium | Confirmed | yes | area:viewer, kind:bug | `userScrollEnabled = false` does not stop a drag past the edge of a zoomed page from turning it |
| [KP-48](#kp-48) | medium | Confirmed |  | area:epub, area:viewer, kind:bug | Media on a page the reader turned away from keeps playing, and an autoplay element on an unseen page starts by itself |
| [KP-49](#kp-49) | medium | Confirmed |  | area:viewer, area:javascript, kind:bug | The script effects follow the first state shown, so a new state for the same document loses page scripts, chapter scripts and `:target` |
| [KP-50](#kp-50) | medium | Confirmed | yes | area:core, kind:bug | A Flate stream that is cut short, or lacks its Adler-32 trailer, decodes to nothing |
| [KP-51](#kp-51) | medium | Confirmed |  | area:core, kind:bug | Numbers that Acrobat, MuPDF and pdf.js read as one number split into two operands or into an operator |
| [KP-52](#kp-52) | medium | Confirmed | yes | area:pdf, kind:bug | Only 32 cross-reference sections are read, so a file with more incremental updates silently loses its oldest objects |
| [KP-53](#kp-53) | medium | Confirmed |  | area:pdf, kind:bug | Repair keeps the last copy of an object that it finds, even a copy that does not parse or one inside another object's stream data |
| [KP-54](#kp-54) | medium | Confirmed |  | area:pdf, kind:bug | A tiling pattern paints nothing when its fill needs more than 20,000 cells, and only a strip when its step is negative |
| [KP-55](#kp-55) | medium | Confirmed |  | area:core, kind:bug | Stroked text in a CFF font leaves every contour but the last one open |
| [KP-56](#kp-56) | medium | Confirmed | yes | area:core, kind:bug | A JPEG 2000 image ignores the /ColorSpace of its image dictionary |
| [KP-57](#kp-57) | medium | Confirmed |  | area:core, kind:bug | A non-symbolic TrueType font with only a Macintosh cmap draws the wrong glyph for accented letters |
| [KP-58](#kp-58) | medium | Confirmed |  | area:core, kind:bug | An embedded TrueType font whose hhea counts more metrics than maxp counts glyphs is thrown away |
| [KP-59](#kp-59) | medium | Confirmed |  | area:core, area:pdf, kind:bug | A /CIDToGIDMap stream or a shading's Indexed lookup stream that fails to decode stops the whole page |
| [KP-60](#kp-60) | medium | Confirmed |  | area:pdf, kind:bug | RevisionChanges reports a filled choice field or rich text field as a forbidden change |
| [KP-61](#kp-61) | medium | Confirmed |  | area:pdf, kind:bug | setTextFieldValue and setChoiceValue redraw only the first widget of a field |
| [KP-62](#kp-62) | medium | Confirmed |  | area:pdf, kind:bug | A stamp or a redaction box lands in the page's leftover graphics state when the page leaves a q open |
| [KP-63](#kp-63) | medium | Confirmed |  | area:pdf, kind:bug | Password and comb text fields draw their value as plain text |
| [KP-64](#kp-64) | medium | Confirmed |  | area:pdf, kind:bug | Field appearances ignore /MK /R, so a field on a turned page gets its value drawn sideways |
| [KP-65](#kp-65) | medium | Confirmed |  | area:pdf, kind:bug | Removing or reordering pages drops a /CropBox that the page tree gave the pages |
| [KP-66](#kp-66) | medium | Confirmed |  | area:pdf, kind:bug | setChoiceValue saves an appearance that shows the export value, not the option's text |
| [KP-67](#kp-67) | medium | Confirmed |  | area:svg, kind:bug | An SVG arc whose end point is its start point becomes a curve of NaN, so the whole path can disappear |
| [KP-68](#kp-68) | medium | Confirmed |  | area:epub, kind:bug | Percentages resolve against the page, not against the containing block |
| [KP-69](#kp-69) | medium | Confirmed |  | area:epub, area:core, kind:bug | vw and vh resolve against the property's own reference, and rem against the reader's size instead of the root element's |
| [KP-70](#kp-70) | medium | Confirmed |  | area:epub, kind:bug | text-transform uses the simple case mapping of one character, so ß stays ß and Turkish i loses its dot |
| [KP-71](#kp-71) | medium | Confirmed |  | area:epub, kind:perf | The subsequent-sibling combinator costs the cube of the number of siblings, and every sibling test scans from the first child |
| [KP-72](#kp-72) | medium | Confirmed |  | area:epub, kind:bug | A book whose chapters are encrypted opens and lays out its ciphertext as text |
| [KP-73](#kp-73) | medium | Confirmed |  | area:xps, kind:bug | An XPS path ignores StrokeDashCap and StrokeEndLineCap, so a dotted line written with round dash caps draws nothing |
| [KP-74](#kp-74) | medium | Confirmed |  | area:xps, kind:bug | An XPS path whose stroke is a gradient, image or visual brush draws no stroke at all |
| [KP-75](#kp-75) | medium | Confirmed |  | area:xps, kind:bug | An XPS ContextColor paints nothing, so CMYK and n-channel content disappears |
| [KP-76](#kp-76) | medium | Confirmed |  | area:xps, kind:bug | An XPS image brush reads the resolution of a PNG or a JFIF JPEG only, so a TIFF or an EXIF JPEG shows the wrong part of the image |
| [KP-77](#kp-77) | medium | Confirmed |  | area:core, area:svg, kind:bug | KiteDoc does not recognise an SVG whose root element starts after byte 512, such as every Illustrator export with its entity DOCTYPE |
| [KP-78](#kp-78) | medium | Confirmed |  | area:cbz, kind:bug | A comic drops its TIFF, AVIF and JPEG XL pages, though KitePDF decodes all three formats |
| [KP-79](#kp-79) | medium | Confirmed |  | area:core, area:cbz, area:renderer, kind:bug | An image of more than 40 megapixels draws as a grey box whenever it is drawn at more than half its size, so a long comic strip shows grey tiles at fit-width |
| [KP-80](#kp-80) | medium | Confirmed |  | area:renderer, backend:skia, kind:bug | SkiaCanvas weighs a luminosity soft mask by Rec. 709, every other canvas by PDF's 0.30, 0.59, 0.11, and one mask kind serves PDF and SVG, which need different weights |
| [KP-81](#kp-81) | medium | Confirmed |  | area:javascript, plan:javascript, kind:bug | The per-event deadline counts the time an `app.alert` or `app.response` dialog waits for the reader |
| [KP-82](#kp-82) | medium | Confirmed |  | area:javascript, plan:javascript, kind:bug | Closing a `PdfScriptRunner` from one of its own callbacks throws and leaves its engine open for good |
| [KP-83](#kp-83) | medium | Confirmed |  | area:javascript, plan:javascript, kind:perf | `PdfScriptRunner` keeps every failure for as long as the document is open, and copies the whole list on each one |
| [KP-84](#kp-84) | medium | Confirmed |  | area:javascript, plan:javascript, kind:bug | `util.printf` gets the separator styles 2 and 3, `%d`, `%x` and a bare `%f` wrong |
| [KP-85](#kp-85) | medium | Confirmed |  | area:javascript, plan:javascript, kind:bug | `AFNumber_Format` prints a minus sign for negative style 1 and rounds half a cent down |
| [KP-86](#kp-86) | medium | Confirmed |  | area:javascript, plan:javascript, kind:bug | `AFSimple_Calculate` skips empty fields and does not round, so averages, minimums and decimal sums differ from Chrome |
| [KP-87](#kp-87) | medium | Confirmed | yes | area:render, kind:perf | CBZ, EPUB, SVG and XPS images are always decoded at full size, never through decodeScaled |
| [KP-88](#kp-88) | medium | Confirmed |  | area:pdf, kind:perf | The object cache keeps a copy of every stream the document has read, with no budget and no drop call |
| [KP-89](#kp-89) | medium | Confirmed |  | area:core, kind:bug | An image `/Decode` entry of more than 308 digits makes `roundToInt` throw, and the whole page fails instead of the image |
| [KP-90](#kp-90) | medium | Confirmed |  | area:ci, kind:test-gap | The consumer check cannot see a KitePDF module that the local publish leaves out, and it checks only the JVM variant of three artifacts |
| [KP-91](#kp-91) | medium | Confirmed |  | area:docs, kind:docs | The JVM and Android artifacts of KitePDF are Java 21 class files, and no page says so |
| [KP-92](#kp-92) | medium | Confirmed |  | area:docs, kind:docs | NOTICE leaves out the Unicode data, the Brotli dictionary, HarfBuzz's shaping tables and the HTML entity table that KitePDF bundles |
| [KP-93](#kp-93) | medium | Confirmed |  | area:ci, kind:test-gap | About 50 font, shaping and signature tests in 21 classes report SKIPPED on every CI run, because CI never provides their fonts or tools |
| [KP-94](#kp-94) | medium | Confirmed |  | area:core, kind:api | No KitePDF function declares @Throws, so a Swift caller of PdfDocument.open or KiteDoc.open ends the app on a bad file |
| [KP-95](#kp-95) | medium | Likely |  | area:viewer, kind:bug | In a right-to-left app, a spread places its pages left to right but turns to the left |
| [KP-96](#kp-96) | medium | Likely |  | area:epub, kind:bug | GPOS cursive attachment is not applied, so Urdu Nastaliq text has the right glyphs in the wrong places, although the docs list it as covered |
| [KP-97](#kp-97) | medium | Likely |  | area:renderer, backend:canvas2d, kind:bug | Canvas2dCanvas makes a new canvas for every group, soft mask, raster step and image and never releases it, so Safari's canvas memory limit ends a page render |
| [KP-98](#kp-98) | medium | Likely |  | area:javascript, area:epub, kind:bug | An EPUB can hold eight QuickJS engines of 512 MiB each, so one book can take 4 GiB of native memory |
| [KP-99](#kp-99) | medium | Likely |  | area:viewer, kind:api | The viewer's public API takes types from kitepdf-pdf and kitepdf-epub, which it does not export, so the README's KiteDocView sample fails to compile in a PDF-only or EPUB-only app |
| [KP-100](#kp-100) | low | Confirmed | yes | area:pdf, kind:docs | The KDoc of setFont(EmbeddedFont) says any Unicode the font covers renders, but the builder does not shape text |
| [KP-101](#kp-101) | low | Confirmed |  | area:viewer, kind:api | One `KiteDocViewState` shown by two `KiteDocView`s mixes their geometry, position and caches, and nothing says it must not be |
| [KP-102](#kp-102) | low | Confirmed |  | area:viewer, kind:bug | The tiles of a deeply zoomed page keep their old pixels when the form layer or the font environment changes |
| [KP-103](#kp-103) | low | Confirmed |  | area:viewer, kind:perf | A `KiteViewerStrings` built inline, as its KDoc shows, recomposes the whole viewer and rebuilds the accessibility nodes on every recomposition of the host |
| [KP-104](#kp-104) | low | Confirmed |  | area:viewer, kind:docs | `prefetchPages` and `pagePlaceholder` do nothing in Vectorized mode, and prefetch stops when the cache is off |
| [KP-105](#kp-105) | low | Confirmed | yes | area:pdf, kind:bug | An /Encrypt /Length below 40 is read as bits, so /Length 5 throws ArithmeticException out of open and /Length 16 asks for a password the file does not have |
| [KP-106](#kp-106) | low | Confirmed |  | area:core, kind:bug | One malformed token inside a dictionary or array loses the whole object |
| [KP-107](#kp-107) | low | Confirmed |  | area:pdf, kind:bug | `metadata`, `xmp` and `xmpMetadataXml` throw when the XMP stream does not decode |
| [KP-108](#kp-108) | low | Confirmed |  | area:pdf, kind:bug | A stream that names a /Crypt filter is decrypted with the document's default filter, then fails on /Crypt |
| [KP-109](#kp-109) | low | Confirmed |  | area:pdf, kind:bug | extractText() returns the text of hidden optional content |
| [KP-110](#kp-110) | low | Confirmed |  | area:core, kind:bug | /MissingWidth is read from the font dictionary instead of the font descriptor |
| [KP-111](#kp-111) | low | Confirmed |  | area:core, kind:bug | An embedded CMap that builds on another with usecmap loses the base CMap |
| [KP-112](#kp-112) | low | Confirmed |  | area:pdf, kind:bug | setButtonValue clears a radio group without an error when the value names no button |
| [KP-113](#kp-113) | low | Confirmed |  | area:pdf, kind:bug | PdfAttachment.bytes throws for a stream that does not decode, though its KDoc promises an empty array |
| [KP-114](#kp-114) | low | Confirmed |  | area:core, area:epub, kind:bug | A document that declares ISO-8859-15 decodes as windows-1252, so œ reads as ½ and € as ¤ |
| [KP-115](#kp-115) | low | Confirmed |  | area:renderer, kind:bug | An embedded font whose glyphs have no outlines draws in a system font on four canvases and draws nothing on three |
| [KP-116](#kp-116) | low | Confirmed |  | area:renderer, backend:coregraphics, backend:canvas2d, kind:bug | CoreGraphicsCanvas and Canvas2dCanvas give no host glyph outlines, so stroked text in a font without a program paints filled and clip text clips to boxes |
| [KP-117](#kp-117) | low | Confirmed |  | area:core, kind:bug | KiteDoc.open turns a wrong password into "not a readable PDF" when the PDF header is not in the first kilobyte |
| [KP-118](#kp-118) | low | Confirmed |  | area:net, kind:api | KiteDoc.openUrl parses the download on the caller's dispatcher |
| [KP-119](#kp-119) | low | Confirmed |  | area:core, kind:api | The Android Uri and InputStream openers read with no limit, so a large file ends the app with OutOfMemoryError |
| [KP-120](#kp-120) | low | Confirmed |  | area:core, kind:bug | On Apple platforms a file over 2 GB opens a wrong prefix or throws IllegalArgumentException, because its length is cut to an Int |
| [KP-121](#kp-121) | low | Confirmed |  | area:javascript, area:epub, kind:bug | On Kotlin/JS, `\s` in a Regex also matches no-break and other Unicode spaces, so `getPageNumWords` and collapsed text differ by platform |
| [KP-122](#kp-122) | low | Confirmed |  | area:core, kind:perf | Every app that reads EPUB carries all 71 hyphenation sets: 2.6 million characters, about 5.3 MB in a Kotlin/Native binary |
| [KP-123](#kp-123) | low | Confirmed |  | area:pdf, kind:api | KiteRawApi guards five declarations, while the raw object model it describes stays public without it |
| [KP-124](#kp-124) | low | Confirmed |  | area:pdf, kind:test-gap | TextSearchCorpusTest returns early when its PDF is missing, so it reports PASS on CI without running |
| [KP-125](#kp-125) | low | Confirmed |  | area:docs, kind:docs | The README and the docs site say KitePDF uses no JNI or native binary, but three of its artifacts load native code |
| [KP-126](#kp-126) | low | Confirmed |  | area:docs, kind:docs | The README does not say that the viewer and kitepdf-media pull Compose Multiplatform 1.13.0-alpha02 into the app |
| [KP-127](#kp-127) | low | Likely |  | area:viewer, area:epub, kind:bug | Read-aloud takes its own interrupted page turn for a move by the reader and reads the clip again |
| [KP-128](#kp-128) | low | Likely |  | area:viewer, kind:api | `ImageDecoder.decodeRaw` does not check the length of its pixel array |
| [KP-129](#kp-129) | low | Likely |  | area:core, kind:bug | On mingwX64, KiteDoc.openFile cannot open a path with a character outside ASCII |
| [KP-130](#kp-130) | low | Likely |  | area:viewer, area:javascript, kind:bug | On the web, a keystroke whose field loses focus while its script waits writes the raw key into the form, past the keystroke script |

### KiteJS

| ID | Severity | Confidence | Run | Labels | Title |
| --- | --- | --- | --- | --- | --- |
| [KJ-01](#kj-01) | critical | Confirmed | yes | area:quickjs, area:interpreter, QuickJS, Rhino, upstream:quickjs-ng, kind:bug | One built-in call runs past the instruction budget and the interrupt hook, so a single statement hangs the engine |
| [KJ-02](#kj-02) | high | Confirmed |  | area:quickjs, QuickJS, kind:bug | When the JNI library cannot load, `KiteJs(QuickJs)` throws `ExceptionInInitializerError`, then `NoClassDefFoundError`, not `JsEngineError` |
| [KJ-03](#kj-03) | high | Likely |  | area:api, area:quickjs, QuickJS, kind:bug | `asyncKiteJs(QuickJs)` on Apple runs a 512 KiB stack limit on a 512 KiB thread, so deep recursion crashes the process |
| [KJ-04](#kj-04) | high | Likely |  | area:interpreter, Rhino, kind:bug | `JSON.parse` on Rhino recurses once per nesting level with no limit, so deep input overflows the host stack |
| [KJ-05](#kj-05) | medium | Confirmed |  | area:api, kind:api | No KiteJS function declares @Throws, so a Swift caller of evaluate ends the app on any script error |
| [KJ-06](#kj-06) | low | Confirmed |  | area:quickjs, QuickJS, kind:bug | QuickJS rejects an identifier with a character outside the Basic Multilingual Plane |
| [KJ-07](#kj-07) | low | Likely |  | area:interpreter, Rhino, kind:bug | Rhino on the JVM leaves an array unsorted when its comparator breaks TimSort's contract, while the other targets sort it |

### ImageKodec

| ID | Severity | Confidence | Run | Labels | Title |
| --- | --- | --- | --- | --- | --- |
| [IK-01](#ik-01) | critical | Confirmed | yes | area:jbig2, kind:bug | JBIG2 halftone region decode runs for a time unrelated to the input size (DoS) |
| [IK-02](#ik-02) | medium | Confirmed | yes | area:jpx, kind:bug | A decoded JPEG 2000 image ignores the PDF dictionary's /ColorSpace, so a CMYK or indexed JPX draws with wrong colours |
| [IK-03](#ik-03) | low | Confirmed |  | area:jpx, kind:perf | A PDF JPEG 2000 image is decoded once at load and again at draw, because probe never reports the JP2 colour channel count |

## Findings for KitePDF

### KP-01

**A type 5 mesh shading with zero bits per coordinate and per component never finishes parsing**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `MeshShadingParser.parseTriangles` reads the rows of a lattice-form mesh while `bs.remaining >= minVertexBits * perRow`, where `minVertexBits` is `2 * BitsPerCoordinate + ncomp * BitsPerComponent`. The two widths come from the dictionary unchecked.
- With `/BitsPerCoordinate 0 /BitsPerComponent 0`, `minVertexBits` is 0, a row reads no bits, `remaining` never falls, and the condition stays true, even for an empty stream. Each row adds two triangles per pair of vertices, without end. A negative width does the same.
- The free-form branch of the same function stops at `MAX_TRIANGLES` (65,536), and the patch parser stops at `MAX_PATCHES`. The lattice branch has no cap, so with valid widths it still allocates triangles in proportion to the decoded data.
- The shading only has to sit in the page's `/Resources`: `render` parses every shading at its start (`loadShadings`), whether or not an `sh` uses it, and it does so again on every render of the page, `structuredText` included.
- On the JVM the loop ends when the heap is full. `KiteShading.parse` runs the parser in `runCatching`, which catches the `OutOfMemoryError`, and the shading becomes `Unsupported`. Until then the heap fills, and other threads that allocate at that moment can fail. On Kotlin/Native and in a browser, running out of memory ends the process.

#### What should happen

- A width that ISO 32000-1, 8.7.4.5.5 does not allow (it lists 1 to 32 bits for coordinates and 1 to 16 for components, and 8.7.4.5.6 takes them for a lattice) makes the shading unusable, and it paints nothing.
- The lattice branch stops at `MAX_TRIANGLES`, as the free-form branch does.

#### How to reproduce

`pdfs/mesh5-zero-bits.pdf`. The page resources are `/Shading << /Sh0 5 0 R >>`, the content is `/Sh0 sh`, and:

```
5 0 obj << /ShadingType 5 /ColorSpace /DeviceGray /BitsPerCoordinate 0 /BitsPerComponent 0
           /VerticesPerRow 2 /Decode [0 612 0 792 0 1] /Length 0 >>
stream

endstream
```

Probe for `kitepdf-core/test@jvm`:

```kotlin
class LatticeMeshProbeTest {
    @Test fun a_lattice_with_zero_bit_widths_parses_quickly() {
        val dict = PdfDictionary(linkedMapOf<String, PdfObject>(
            "ShadingType" to PdfInt(5), "ColorSpace" to PdfName("DeviceGray"),
            "BitsPerCoordinate" to PdfInt(0), "BitsPerComponent" to PdfInt(0), "VerticesPerRow" to PdfInt(2),
        ))
        val worker = kotlin.concurrent.thread { KiteShading.parse(PdfStream(dict, ByteArray(0)), IndirectResolver { null }) }
        worker.join(5_000)
        assertFalse(worker.isAlive, "the parse still runs after 5 s")   // fails today
    }
}
```

Run it alone: the thread goes on allocating after the assertion, until the heap is full.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:51`: the bit widths, read unchecked.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:125`: the lattice loop, with no cap.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:150`: the free-form loop, with its cap.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:247`: `runCatching` around the parse.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:307`: `render` parses the shadings first.

#### Notes

Read from source. `mapValue` divides by `2^bits - 1`, which is 0 here, so the coordinates are NaN, and nothing throws to end the loop.

Measured on the JVM at `c4e7574` by the lead: `mesh5-zero-bits.pdf` under `AwtPdfRasterizer` was still running after 25 s.

### KP-02

**A type 4 function grows its operand stack without limit, so a function of 370 bytes exhausts memory**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: KP-16 (the nesting part)

#### What happens

- `PostScriptCalc` keeps its operands in an `ArrayList` with no size limit. `copy` duplicates the top n entries, so `{ 1 copy 2 copy 4 copy ... 536870912 copy }` doubles the stack 30 times, to 2^30 entries, from a program of about 370 bytes. Near 2^29 entries the list and the copy it makes to grow need more than the 3 GB heap of the JVM gate, and `Type4.evaluateInternal` then copies the whole stack once more (`stack.mapNotNull`).
- `KiteFunction.evaluate` catches `RuntimeException` only, so the `OutOfMemoryError` leaves the renderer. As a tint transform, the function runs at the `cs` that selects the space, since `DeviceN.defaultColor` evaluates it, again at every `sc`, and for the colours of every image in that space.
- The compiler and the interpreter also recurse once per `{` level, with no limit. On the JVM the compile runs in `runCatching`, which catches the `StackOverflowError`, and the function becomes `Unsupported`. On Kotlin/Native, where a secondary thread has 512 KB of stack (CLAUDE.md, #450), a function of a few thousand nested braces overflows it while the page's colour spaces load, and the process ends.

#### What should happen

- The calculator stops at a fixed stack size and a fixed nesting depth, and the function then gives zeros, as a malformed function does now (#128). MuPDF's calculator keeps at most 100 operands (`PS_STACK_SIZE` in `pdf-function.c`).

#### How to reproduce

`pdfs/type4-copy-doubling.pdf`: the page selects `[/Separation /Spot /DeviceGray 5 0 R]` with `/CS0 cs 1 sc`, and function 5 is `<< /FunctionType 4 /Domain [0 1] /Range [0 1] >>` with the program above. `pdfs/type4-deep-nesting.pdf` holds 200,000 nested braces instead.

A probe for `kitepdf-core/test` that measures the stack without running out of memory:

```kotlin
class CalculatorStackProbeTest {
    @Test fun the_operand_stack_is_bounded() {
        val program = "{ " + (0 until 20).joinToString(" ") { "${1 shl it} copy" } + " }"
        val stack = ArrayList<Any>().apply { add(1.0) }
        PostScriptCalc.exec(PostScriptCalc.compile(program), stack)
        assertTrue(stack.size <= 100, "the stack holds ${stack.size}")   // 1048576 today
    }

    @Test fun nesting_is_bounded() {
        // On the JVM this throws StackOverflowError today; on Kotlin/Native it ends the process.
        PostScriptCalc.compile("{".repeat(100_000) + "}".repeat(100_000))
    }
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:451`: `copy`, with no bound on the result.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:65`: `evaluate` catches `RuntimeException` only.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:381`: `parseBlockBody`, recursive with no depth limit (and `exec` at line 398 through `if`/`ifelse`).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:194`: `DeviceN.defaultColor` evaluates the tint transform.

#### Notes

Read from source. Related: #128 and #149, which fixed other calculator faults.

Measured on the JVM at `c4e7574` by the lead: `type4-copy-doubling.pdf` under `AwtPdfRasterizer` threw OutOfMemoryError after 27.7 s and 4.5 GiB of allocation. `type4-deep-nesting.pdf` (200,000 nested braces) rendered on the JVM, even on a thread with a 512 KB stack, because `runCatching` around the compile catches the StackOverflowError; the Kotlin/Native half was not run.

### KP-03

**A TrueType glyph built from repeated composite components takes exponential time and memory to parse**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `TrueTypeFont.parseCompositeGlyph` parses each component with `parseGlyph` again. Within one parse there is no cache. It guards the depth (`maxCompositeDepth`, 8) and cycles (the `active` set), but not repetition.
- So eight levels of composites, each with 10 components that all name the next level, parse the last glyph 10^8 times, and each level copies every point it collects into its `merged` list. The font is under 1 KB. The first draw of that glyph runs for minutes and fills the heap; `parseOrEmpty` catches only `PdfFormatException`, so an `OutOfMemoryError` leaves the renderer.
- The charstring interpreters have the same shape. `CharstringInterpreter.execute` and `Type1CharstringInterpreter` limit the subroutine depth to 10, not the number of calls, so a subroutine that calls the next one 10 times, ten levels down, runs 10^10 calls, and a leaf that draws a line fills `pathBuilder` with as many segments.
- `Type1CharstringInterpreter.seac` renders the base and the accent with a fresh interpreter, whose `depth` starts at 0 again. A glyph whose `seac` names itself as the base recurses until the stack overflows. `Type1Font.outlineForGlyphName` catches that with `runCatching` on the JVM; on Kotlin/Native it ends the process.

#### What should happen

- Each glyph program runs within a budget, such as a total of points or segments and of components or subroutine calls, past which the glyph is empty. A `seac` whose base or accent is itself a `seac` draws nothing more.
- Poppler (FreeType) renders the proof-of-concept page in 20 ms: the glyph draws nothing, and the blue square next to it draws.

#### How to reproduce

`fonts/fanout.ttf` (and `fonts2/fanout.ttf`, the same font with valid `maxp` limits of 10 components and depth 8): glyphs 1 to 8 are composites, each with 10 components (flags `0x0022`, the last `0x0002`, byte offsets 0 0) naming the next glyph, and glyph 9 is a square of 4 points. Its `cmap` maps `A` to glyph 1. `pdfs/tt-fanout.pdf` and `pdfs/tt-fanout-validmaxp.pdf` draw `(A) Tj` in it, then a blue square.

```kotlin
// kitepdf-core/test@jvm
class CompositeFanOutProbeTest {
    @Test fun a_wide_composite_tree_parses_quickly() {
        val font = TrueTypeFont.parse(java.io.File("audit-repro/L3/fonts/fanout.ttf").readBytes())
        val worker = kotlin.concurrent.thread { font.outlinePath(1) }
        worker.join(5_000)
        assertFalse(worker.isAlive, "glyph 1 still parses after 5 s")   // fails today
    }
}
```

Run it alone, for the same reason as the mesh probe.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:156`: `maxCompositeDepth`, the only bound.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:334`: each component parses again.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:183`: `parseOrEmpty` catches `PdfFormatException` only.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:92` and `:159`: depth limit, no call count.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/Type1CharstringInterpreter.kt:302` and `:331`: `seac` and `appendGlyph`.

#### Notes

Read from source, and the PoC rendered with Poppler. The lead may want to file the CFF/Type 1 call count and the Type 1 `seac` recursion as separate issues; they share the cause, which is that no glyph program has a total budget.

Measured on the JVM at `c4e7574` by the lead: `tt-fanout.pdf` under `AwtPdfRasterizer` was still running after 30 s.

### KP-04

**A later revision can change the certification level and the field lock that judge it**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #448

#### What happens

`PdfSignature.validate` judges the changes after signing by a certification level and a field lock. It reads both from the document as it is now, so the revision under judgement sets its own rules.

- The level: `changesAfterSigning` takes `document.signatures.firstNotNullOfOrNull { it.certificationLevel }`. `document` is the whole file, and `signatures` lists the signature fields in `/AcroForm /Fields` order. `PdfSignature.of` reads `certificationLevel` from any `/V` dictionary that has a `DocMDP` reference, without checking the signature and without looking at the catalog's `/Perms /DocMDP` (ISO 32000-1, 12.8.2.2 and 12.8.4).
- So a revision can add a signature field first in `/Fields`, whose `/V` holds `/Reference [<< /TransformMethod /DocMDP /TransformParams << /P 3 >> >>]`. Every change of that revision is then judged at level 3, also on a document that was certified at level 1. The new field and its dictionary pass as "a new signature" (`effective >= 2`), the new `/Fields` order passes as a form change, and an annotation passes at level 3.
- The lock: `PdfSignature.of` reads the FieldMDP lock from the signature field's own `/Lock` first, in the current document. A revision can rewrite that field with a looser `/Lock`, such as `/Action /Include /Fields [(Nothing)]`. `classify` files the rewrite as "the signing of field X" because `Lock` is in `SIGNING_KEYS`, and it does not check that the field was already signed. The values of the fields that the original lock covered can then change, and each change reads as permitted (ISO 32000-1, 12.8.2.4).
- A third path has the same cause: `V` is in `SIGNING_KEYS` too, so a revision can point an already signed certification field at a new signature dictionary with no `DocMDP` reference, and the level falls back to 3.

`areChangesPermitted` is then true for a certified or locked document whose content changed.

#### What should happen

- The certification level is the one of the signature that the catalog's `/Perms /DocMDP` names in the signed revision, and only when that signature is valid (ISO 32000-1, 12.8.2.2, Table 254). A signature that a later revision adds does not change it.
- The FieldMDP lock comes from the signed revision: the `/Lock` of the field as it was signed, or the FieldMDP reference inside the signed signature dictionary.
- A change to `/Lock` or `/V` of a field that was already signed is not "signing". It is a change that no level allows.

#### How to reproduce

JVM test, with the setup of `RevisionChangesTest` (`root`, `signer`, `signed(o)`):

```kotlin
@Test
fun aRevisionCannotRaiseTheCertificationLevel() {
    val o = PdfOptions(certification = 1)
    val note = "<< /Type /Annot /Subtype /FreeText /Rect [10 10 190 60] /Contents (PAID IN FULL) /DA (/Helv 12 Tf 0 g) /P 3 0 R >>"
    // Control: the annotation alone is not permitted at level 1.
    val plain = SigFixtures.update(signed(o), mapOf(
        9 to note,
        3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 9 0 R] /Contents 6 0 R >>",
    ))
    assertFalse(PdfDocument.open(plain).signatures.single().validate(listOf(root.cert)).areChangesPermitted)

    // The same annotation, with a signature field first in /Fields that claims level 3.
    val raised = SigFixtures.update(signed(o), mapOf(
        1 to "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [7 0 R 4 0 R] /SigFlags 3 >> /Perms << /DocMDP 5 0 R >> >>",
        3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 7 0 R 9 0 R] /Contents 6 0 R >>",
        7 to "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Fake) /Rect [0 0 0 0] /F 132 /P 3 0 R /V 8 0 R >>",
        8 to "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached /Reference [<< /Type /SigRef /TransformMethod /DocMDP /TransformParams << /Type /TransformParams /P 3 /V /1.2 >> >>] /ByteRange [0 1 2 1] /Contents <00> >>",
        9 to note,
    ))
    val sig = PdfDocument.open(raised).signatures.first { it.field.fullyQualifiedName == "Signature1" }
    assertEquals(1, sig.certificationLevel)
    val result = sig.validate(listOf(root.cert))
    assertEquals(Status.Valid, result.status)
    assertFalse(result.areChangesPermitted)   // fails today: true
}

@Test
fun aRevisionCannotLoosenTheLock() {
    val o = PdfOptions(lock = "<< /Type /SigFieldLock /Action /All >>", textField = "before")
    val n = SigFixtures.numbers(o)
    val pdf = SigFixtures.update(signed(o), mapOf(
        n.signatureField to "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1) /Rect [0 0 0 0] /F 132 /P 3 0 R /V 5 0 R /Lock << /Type /SigFieldLock /Action /Include /Fields [(Nothing)] >> >>",
        n.textField to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /F 4 /P 3 0 R /V (after) >>",
    ))
    val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
    assertEquals(Status.Valid, result.status)
    assertFalse(result.areChangesPermitted)   // fails today: true, with "the signing of field Signature1" permitted
}
```

Object numbers: with `certification = 1` and no text field, `SigFixtures.numbers(o)` gives content 6, so 7, 8 and 9 are free.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfSignature.kt:80` (the level), `:122` (`certificationLevel` from any `DocMDP` reference), `:125` (the lock from the current field)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:101` (a signed field's change filed as signing), `:249` (`SIGNING_KEYS`)

#### Notes

Read from source. `RevisionChangesTest.aSecondSignatureIsPermittedFromLevel2` adds a second signature after the certification one, so the certification level comes first there and the test cannot see this.

### KP-05

**A revision that rewrites an object stream changes the objects inside it, and RevisionChanges reports nothing**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #448

#### What happens

`RevisionChanges.between` compares only the objects whose cross-reference entries differ (`if (oldEntry == newEntry) continue`). It also skips any object stream or cross-reference stream (`isContainer`).

An object inside an object stream has a type 2 entry: the number of its object stream and its index (ISO 32000-1, 7.5.8.3). A revision that writes a new version of object stream N, under the same number, changes every member of N. The members' entries stay equal, `XrefEntry.Compressed` being a data class, so they are skipped. N itself is skipped as a container. `between` returns an empty list.

So a signed file whose dictionaries live in object streams (fonts, annotations, the page tree, ExtGStates, OCG dictionaries) can have any of them changed after signing. `validate` reports `isModifiedAfterSigning = true` with `changes = []`, so `areChangesPermitted` is true, also at certification level 1. For example, a new `/Encoding /Differences` on a font turns "1000" into "9000" on the page.

#### What should happen

When an object stream changes, every object whose entry names it is compared, as if its own entry had changed. A member whose value differs is classified like any other change.

#### How to reproduce

Common test:

```kotlin
@OptIn(KiteRawApi::class)
@Test
fun a_rewritten_object_stream_is_a_change() {
    val packed = PdfDocument.open(
        TestPdf.onePage(
            "BT /F1 24 Tf 20 100 Td (1000) Tj ET",
            resources = "/Font << /F1 5 0 R >>",
            extra = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
        ),
    ).edit().saveRewritten(useObjectStreams = true)
    val old = PdfDocument.open(packed)
    val container = old.xref.values.filterIsInstance<XrefEntry.Compressed>().first().containingObjectStream
    val stm = old.resolve(PdfReference(container, 0)) as PdfStream
    val n = stm.dict.getInt("N")!!.toInt()
    val first = stm.dict.getInt("First")!!.toInt()
    val text = FilterChain.decode(stm).decodeToString()
    val pairs = text.substring(0, first).trim().split(Regex("\\s+"))
    val bodies = (0 until n).map { i ->
        val from = first + pairs[2 * i + 1].toInt()
        val to = if (i + 1 < n) first + pairs[2 * i + 3].toInt() else text.length
        text.substring(from, to).trim()
            .replace("/BaseFont /Helvetica", "/BaseFont /Helvetica /Encoding << /Differences [49 /nine] >>")
    }
    val header = StringBuilder()
    var at = 0
    for (i in 0 until n) { header.append("${pairs[2 * i]} $at "); at += bodies[i].length + 1 }
    val data = header.toString() + bodies.joinToString("\n", postfix = "\n")
    val changed = appendRevision(
        packed,
        mapOf(container.toInt() to "<< /Type /ObjStm /N $n /First ${header.length} /Length ${data.length} >>\nstream\n$data\nendstream"),
        size = old.trailer.getInt("Size")!!.toInt(),
        root = (old.trailer["Root"] as PdfReference).objectNumber.toInt(),
    )
    val new = PdfDocument.open(changed)
    assertTrue("9000" in new.pages[0].extractText(), "the revision changed the page")
    assertTrue(RevisionChanges.between(old, new, level = 1, lock = null).isNotEmpty())   // fails today: empty
}
```

The same holds through `PdfSignature.validate` for a signed file: sign `packed` with `PdfSigner`, then append the same revision.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:57` (entry comparison), `:62` (containers skipped)

#### Notes

Read from source. No test of `RevisionChangesTest` uses an object stream. Many writers since PDF 1.5 store dictionaries in object streams, KitePDF's own `saveRewritten(useObjectStreams = true)` among them, so this covers a large share of signed files.

### KP-06

**A revision that adds or removes an annotation or a field through /Annots or /Fields reports no change**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #448

#### What happens

`RevisionChanges.classify` returns null (no report) for a page whose only changed key is `/Annots`, for an indirect `/Annots` array (`Role.ANNOTS`), for an indirect `/Fields` array (`Role.FIELDS`), and for an `/AcroForm` whose changed keys are all in `FORM_KEYS`, which holds `Fields`. The objects that such a change adds or takes away are reported only when their own entries change.

So two changes pass unreported at every level:

- Removing an annotation or a widget from `/Annots`, or a field from `/Fields`. The removed object did not change, so nothing compares it. Level 1 allows no change and level 2 allows no annotation change (ISO 32000-1, 12.8.2.2, Table 254), and no level allows removing a form field: level 3 adds only annotation creation, deletion and modification.
- Adding an object that the signed revision already held but did not use, such as an annotation that no page listed. `anObjectThatNothingReachesIsNoChange` lets such an orphan into the signed revision, and a later `/Annots` entry makes it visible without any changed object.

This is the "hide" and "hide-and-replace" shape of the shadow attacks on PDF signatures: an opaque annotation that covers text is taken off the page after a level 1 certification, and `areChangesPermitted` stays true.

#### What should happen

Compare the members of `/Annots` and `/Fields` (and `/CO`) between the two revisions. An annotation that leaves or joins a page is an annotation change (level 3), and a widget or field that leaves or joins is a form change that no level allows.

#### How to reproduce

Common test:

```kotlin
@Test
fun an_annotation_taken_off_the_page_is_a_change() {
    val base = TestPdf.onePage(
        "0 0 1 rg 20 20 160 40 re f",
        pageEntries = "/Annots [5 0 R]",
        extra = listOf("<< /Type /Annot /Subtype /Square /Rect [10 10 190 70] /IC [1 1 1] /Contents (covers the bar) >>"),
    )
    val old = PdfDocument.open(base)
    val new = PdfDocument.open(appendRevision(base, mapOf(3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Contents 4 0 R >>"), size = 6))
    assertEquals(0, new.pages[0].annotations.size)
    assertTrue(RevisionChanges.between(old, new, level = 1, lock = null).isNotEmpty())   // fails today: empty
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:112` (`ANNOTS`, `FIELDS`), `:113` (`FORM`), `:114` (`PAGE`)

#### Notes

Read from source. `RevisionChangesTest.anAnnotationIsPermittedAtLevel3` only adds a new annotation object, which is reported through its own entry.

### KP-07

**A certificate without the CA flag can issue a signer certificate that validate calls trusted**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #634

#### What happens

`SignatureValidator.chainOf` takes any certificate whose subject is the last one's issuer and whose key verifies its signature. `X509.parse` reads basicConstraints into `isCa`, but nothing reads `isCa`. Key usage is not parsed at all, and no path length is checked.

So anyone who holds an end-entity certificate under a trusted root can sign a certificate for any name with that end-entity key. `validate(listOf(root))` returns `Valid`, `isTrusted = true`, and `signer.commonName` is the forged name. The same holds for a document timestamp: any trusted end-entity certificate can sign a TSTInfo with any time, and the extended key usage `id-kp-timeStamping` that RFC 3161, 2.3 requires is not checked.

#### What should happen

Every certificate that issues another one in the chain has basicConstraints `cA = TRUE`, a key usage with `keyCertSign` when key usage is present, and a path length that allows the chain (RFC 5280, 6.1.4 (k), (l), (m), (n)). A document timestamp's signer has `id-kp-timeStamping`. A chain that breaks a rule is not trusted, and the result says why.

#### How to reproduce

JVM test:

```kotlin
@Test
fun anEndEntityCannotIssueATrustedSigner() {
    val root = SigFixtures.certificate("Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
    val alice = SigFixtures.certificate("Alice", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 2)
    val ceo = SigFixtures.certificate("CEO", SigFixtures.rsaKeys(), issuer = alice, ca = false, serial = 3)
    val pdf = SigFixtures.signedPdf(PdfOptions()) { SigFixtures.cms(ceo, listOf(ceo, alice, root), it) }
    val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
    assertEquals(listOf("CEO", "Alice", "Root"), result.chain.map { it.commonName })
    assertFalse(result.isTrusted)   // fails today: true
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/SignatureValidator.kt:202` (`chainOf`), `:217` (`trusted`)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/X509.kt:67` (`isCa` parsed, never used)

#### Notes

Read from source. `docs/reading.md` warns about the validity dates (#634) but not about this. `SigFixtures.certificate(ca = false)` already writes a critical basicConstraints without `cA`.

Measured on the JVM at `c4e7574` by the lead: with `SigFixtures`, a chain Root (CA) to Alice (not a CA) to CEO, signed by CEO, gives `status=Valid`, `isTrusted=true`, chain `[CEO, Alice, Root]`, the same as a control chain whose intermediate is a CA.

### KP-08

**Redaction keeps the /ActualText of the text it removes, and extractText() still returns that text**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

`RedactionEngine.run` passes `BDC`, `BMC` and `EMC` through unchanged (`else -> out.add(op)`). A marked-content sequence can carry the text it encloses as `/ActualText` (ISO 32000-1, 14.9.4), inline or through `/Resources /Properties`. Redaction replaces the `Tj` with a `TJ` advance and keeps the `BDC` with its string.

`TextExtractor` writes the `/ActualText` of a `BDC` in place of what the sequence encloses, whether or not any text is left inside it. So after `redactRegion` and `saveRewritten`, `page.extractText()` returns the redacted words. Any other tool reads them from the content stream too.

The same holds for `/Alt` and `/E` in a property list, and for `/ActualText`, `/Alt` and `/E` on the structure elements of a tagged PDF (14.9.3, 14.9.5), which describe a redacted figure or text. LibreOffice writes a picture's alternate text as `/Alt` on its `/Figure` structure element, so a redacted photo of a passport keeps "Passport of Jane Doe" in the file. `docs/editing.md` says "no tool can extract them" and lists neither case.

#### What should happen

A marked-content sequence whose text redaction removes loses `/ActualText`, `/Alt` and `/E`, inline or in `/Properties` (a property list that other sequences share gets a copy). A structure element whose content leaves the page loses the same entries.

#### How to reproduce

Common test:

```kotlin
@Test
fun redaction_removes_the_actual_text_of_what_it_removes() {
    val pdf = RawPdf.page("BT /F1 24 Tf 100 700 Td /Span << /ActualText (SSN 123-45-6789) >> BDC (SSN 123-45-6789) Tj EMC ET".encodeToByteArray())
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 400.0, 730.0)) }.saveRewritten()
    assertFalse("123-45-6789" in PdfDocument.open(out).pages[0].extractText())   // fails today
}
```

JVM test, for the structure tree, with the LibreOffice file:

```kotlin
@Test
fun redactingAPictureDropsItsAlternateText() {
    val doc = PdfDocument.open(java.io.File("audit-repro/L4/lo/alt.pdf").readBytes())
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(50.0, 700.0, 220.0, 790.0)) }.saveRewritten()
    // "Pass" in UTF-16BE after the BOM, as PdfObjectWriter writes a hex string.
    assertFalse("FEFF0050006100730073" in out.decodeToString())   // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:307`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextExtractor.kt:109`
- `docs/editing.md`, "What redaction removes"

#### Notes

Read from source, and checked that LibreOffice 24.2 writes `/Alt` on the `/Figure` element (object 7 of `alt.pdf`). Tagged PDFs, PDF/UA files and OCR text layers use `/ActualText`; the corpus has `public-pdfa-actualtext-ocr.pdf`.

Measured on the JVM at `c4e7574` by the lead: after `redactRegion` over the run and `saveRewritten()`, `extractText()` of the output returns `SSN 123-45-6789`.

### KP-09

**Redaction keeps the data of a removed image when the page shares or inherits its /Resources**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

`redactRegions` drops the `Do` of an image in a region, then copies the page's effective resources onto the page and prunes the image from that copy (`prunePageResourceXObjects`). `saveRewritten` then relies on reachability to drop the image stream.

When the resources dictionary is shared, the original still names the image: an indirect `/Resources` that several pages use, or `/Resources` on a `/Pages` node that the pages inherit (ISO 32000-1, 7.7.3.4). The image stays reachable and the rewrite writes its data. For inherited resources this holds even for a one-page file, because the catalog still reaches the `/Pages` node. The code comment at the bake says the opposite ("so the image XObject entry is actually removed"). `docs/editing.md` says images go "with their data".

LibreOffice 24.2 writes exactly this layout: one resources dictionary that lists every picture of the document, named by `/Resources` on each page and on the `/Pages` node. A photo or a scanned signature redacted on page 1 of such a file stays in the rewritten file.

#### What should happen

After the rewrite, an image that no page, form, pattern or annotation draws is not in the file. Either prune the shared dictionary when nothing else draws the image, or give the other pages and the `/Pages` node their own copies without the entry.

#### How to reproduce

JVM test, with the LibreOffice file:

```kotlin
@OptIn(KiteRawApi::class)
@Test
fun aRedactedLibreOfficePictureLeavesTheFile() {
    val doc = PdfDocument.open(java.io.File("audit-repro/L4/lo/doc.pdf").readBytes())
    val out = PdfDocument.open(doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(50.0, 700.0, 220.0, 790.0)) }.saveRewritten())
    val images = out.xref.keys.mapNotNull { out.resolve(PdfReference(it, 0)) as? PdfStream }.filter { it.dict.getName("Subtype") == "Image" }
    assertEquals(0, images.size)   // fails today: 1
}
```

Common test, without LibreOffice:

```kotlin
@Test
fun a_redacted_image_in_inherited_resources_leaves_the_file() {
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 612 792] /Resources << /XObject << /Im1 5 0 R >> >> >>",
        "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>",
        "<< /Type /Page /Parent 2 0 R /Contents 7 0 R >>",
        TestPdf.stream("SECRETIMAGEBYTES", "/Type /XObject /Subtype /Image /Width 16 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8"),
        TestPdf.stream("q 160 0 0 10 100 700 cm /Im1 Do Q"),
        TestPdf.stream("q Q"),
    ))
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 300.0, 720.0)) }.saveRewritten()
    assertFalse(RawPdf.containsBytes(out, "SECRETIMAGEBYTES".encodeToByteArray()))   // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:761`, `:762` (effective resources), `:885` to `:891` (the comment, the copy and the prune)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditorRedaction.kt` (`prunePageResourceXObjects`)

#### Notes

Read from source. Made `doc.pdf` with LibreOffice and read its objects: pages 1 and 10 and the `/Pages` node 14 all name `/Resources 21 0 R`, which holds `/XObject << /Im8 8 0 R >>`. No redaction test in the repository draws an image at all, so the "with their data" promise has no test. `RedactionInheritedResourcesTest` covers a form without `/Resources`, not a page that inherits them.

Measured on the JVM at `c4e7574` by the lead: the output of `saveRewritten(useObjectStreams = false)` still contains the image bytes `SECRETIMAGEBYTES`.

### KP-10

**Entities that expand to nothing escape the expansion budget, so a 600-byte document hangs every XML reader**

- Repo: KitePDF
- Labels: area:core, area:epub, area:svg, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #571

#### What happens

- `KiteXml` expands the general entities that the internal subset of a DOCTYPE declares (#571). `DeclaredEntities.expand` checks its budget before an expansion and spends it afterwards, by the length of the text that the entity became (`budget -= it.length`).
- An entity whose replacement text is empty becomes `""` and spends nothing. An external entity returns `""` before any check at all.
- The depth limit is 16, and each level may name the level below it any number of times. Ten references at each of twelve levels make about 1.1 x 10^11 calls of `expand`, and the budget stays at 1,000,000 the whole time.
- The document that does this is 577 bytes: one line of the subset for each level.
- `KiteXml.tokenize` reads every XML file of KitePDF: the EPUB `container.xml`, the OPF, `encryption.xml`, the chapters, the nav, NCX and SMIL files, SVG files, XPS parts, the CBZ `ComicInfo.xml`, and the `/RV` rich text of a PDF field. So `EpubDocument.open` with such a `container.xml` or OPF never returns, and neither does `SvgDocument.open`. Nothing in the tokenizer checks a cancellation.
- The test `nested_entities_stop_expanding_at_a_limit` covers entities that expand to `lol`, which spend the budget.

#### What should happen

- Each expansion costs something, whatever it expands to, so the work of one document stays within the budget: count every expansion against it, or charge at least one unit for each.
- XML 1.0 leaves this limit to the processor. The JDK's parser, for one, counts entity expansions (`jdk.xml.entityExpansionLimit`), not only characters.

#### How to reproduce

```kotlin
// kitepdf-core, test/
@Test
fun entities_that_expand_to_nothing_still_spend_the_budget() {
    val subset = buildString {
        append("""<!ENTITY a "">""")
        for (k in 1 until 12) append("""<!ENTITY ${'a' + k} "${"&${'a' + k - 1};".repeat(10)}">""")
    }
    val xml = "<!DOCTYPE svg [$subset]><svg><text>&l;</text></svg>"
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    KiteXml.parse(xml)
    assertTrue(mark.elapsedNow().inWholeSeconds < 2, "took ${mark.elapsedNow()}") // runs for hours today
}
```

The same document passed as bytes to `SvgDocument.open` does not return either. With `<!ENTITY a SYSTEM "x">` as the first declaration, the external entity does the same.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/xml/KiteXml.kt:353-369`: `DeclaredEntities.expand`, the budget and the depth limit.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/xml/KiteXml.kt:233-261`: `decodeEntities`, which calls it for each reference.
- `kitepdf-core/test/io/github/yuroyami/kitepdf/core/xml/KiteXmlTest.kt:136`: the test that only uses entities with text.

#### Notes

Read from source, and ran a Python port of `decodeEntities` and `expand` on the 577-byte document: 3,000,000 calls in 2.6 s with the budget unchanged, of the 1.1 x 10^11 calls that the whole run needs. The port is `audit-repro/L5/entity_sim.py`, and the document is `audit-repro/L5/empty-laughs.svg`.

Measured on the JVM at `c4e7574` by the lead: `SvgDocument.open` on the document of the probe with 6, 7 and 8 levels returns after 91, 155 and 558 ms; with 12 levels (600 bytes) it was still running after 25 s. An EPUB whose `container.xml` carries the 12-level subset and references the last entity: `KiteDoc.open` was still running after 25 s.

### KP-11

**An SVG whose use elements fan out paints exponentially many shapes, so a 150-byte file hangs its render, its text and its links**

- Repo: KitePDF
- Labels: area:svg, area:epub, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `SvgImage.walk` stops at depth 32 (its comment says "`<use>` cycles"), and that is the only guard against `<use>`. A `<use>` and its target take two levels, so `<use>` nests about 15 times.
- Nothing counts the elements that `<use>` instances make. A group with ten `<use href="#previous">` at each of 15 levels walks 10^15 elements.
- A cycle is not an error either. `<g id="a"><use href="#a"/><use href="#a"/><use href="#a"/><use href="#a"/></g>` walks the group 4^15, about 10^9 times, each with its four uses. The test `a_use_cycle_stops_instead_of_hanging` has one `<use>` in its cycle, so it costs 16 walks.
- `render` stops between elements when a viewer cancels, but `SvgImage.links` and `SvgImage.textContent` walk with `stop = null`. So `SvgPage.textContent()` and `SvgPage.hyperlinks`, and the text of an EPUB page with an inline SVG (`svgTexts`), cannot be stopped at all.
- `boundsOfElement`, which an `objectBoundingBox` clip path calls, follows `<use>` the same way.

#### What should happen

- SVG 2, 5.6 (the `use` element): a `<use>` whose references lead back to itself or to an ancestor is in error and renders nothing.
- The elements that the `<use>` instances of one image make stay within a budget, as browsers keep them, so fan-out cannot multiply past it.

#### How to reproduce

```kotlin
// kitepdf-svg, test/SvgFeaturesTest.kt (which has fills())
@Test
fun use_fan_out_is_bounded() {
    fun bomb(levels: Int) = buildString {
        append("<svg width='10' height='10'><defs><rect id='g0' width='1' height='1'/>")
        for (k in 1..levels) { append("<g id='g$k'>"); repeat(10) { append("<use href='#g${k - 1}'/>") }; append("</g>") }
        append("</defs><use href='#g$levels'/></svg>")
    }
    assertTrue(fills(bomb(5)).size <= 10_000) // 100,000 fills today, and bomb(9) asks for 10^9
}
```

The cycle, as a file:

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">
  <g id="a"><rect width="1" height="1"/><use href="#a"/><use href="#a"/><use href="#a"/><use href="#a"/></g>
</svg>
```

`SvgDocument.open(bytes).pages[0].textContent()` does not return.

#### Where

- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:224`: the depth check, with `MAX_DEPTH` at line 1704.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:363-380`: `drawUse`.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:145`: `links`, which renders with `stop = null`.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:668-683`: `boundsOfElement`.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgDocument.kt:85` and `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:3462`: the text passes.
- `kitepdf-svg/test/io/github/yuroyami/kitepdf/svg/SvgFeaturesTest.kt:58`: the cycle test.

#### Notes

Read from source. The counts are arithmetic over the depth check: the deepest group of the cycle is at depth 31, reached 4^15 times.

Measured on the JVM at `c4e7574` by the lead: opening and rendering the fan-out file with 10 uses per level: 4 levels 293 ms and 35 MiB allocated, 5 levels 324 ms and 194 MiB, 6 levels 2.3 s and 1,951 MiB, 9 levels still running after 25 s. The four-use cycle: still running after 25 s.

### KP-12

**A table cell's colspan or rowspan, or a col's span, is not clamped as HTML clamps it, so one attribute runs the layout out of memory**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `BoxBuilder` reads `colspan` and `rowspan` as any positive `Int`, and so does it read the `span` of a `col` or `colgroup`.
- `placeCells` adds one boxed `Long` to a `HashSet` for every slot that a cell covers, `rowspan` times `colspan` entries. `<td colspan="2147483647">` asks for two billion, and `colspan="100000" rowspan="100000"` for ten billion. With `border-collapse: collapse`, `collapseBorders` fills a `HashMap` over the same slots.
- `scanColWidths` puts one map entry for each column that a `span` covers.
- The layout then makes arrays with one value per column, such as `DoubleArray(cols)` in `layoutTable`.
- The key of a slot is `row * 100_000 + col`, so in a table wider than 100,000 columns the cells of one row also land on the slots of the next.
- The DOM that a book's scripts see clamps `colSpan` to 1 to 1000 and `rowSpan` to 0 to 65534, as HTML does. The layout does not.

#### What should happen

HTML, 4.9.12.1 (forming a table): a `colspan` above 1000 counts as 1000, a `rowspan` above 65534 as 65534, and a `span` above 1000 as 1000. A `rowspan` of 0 spans to the end of the row group.

#### How to reproduce

```kotlin
// kitepdf-epub, test/BoxLayoutTest.kt (which has layout())
@Test
fun a_huge_colspan_is_clamped() {
    val root = layout("""<table><tr><td colspan="2147483647">x</td></tr></table>""")
    assertTrue(root.borderBoxHeight > 0.0) // OutOfMemoryError, or minutes of work, today
}
```

A chapter of a book with that table does the same in `EpubDocument.pageCount` or in the viewer.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:763-764`: `colspan` and `rowspan`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:617` and `:624`: `span`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:712-724` (`placeCells`), `:645-657` (`collapseBorders`), `:606-633` (`scanColWidths`).
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1536-1546`: the column arrays.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomElements.kt:961`: the clamped DOM reflection.

#### Notes

Read from source.

Measured on the JVM at `c4e7574` by the lead: a one-chapter EPUB with `<td colspan="2147483647">` threw OutOfMemoryError on a 3 GB heap after 17 s, in `BoxBuilder.placeCells` (`Long.valueOf`).

### KP-13

**Frames that repeat one document are followed once per frame, so a book of eight small files hangs isScripted and the page paint**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #528, #613

#### What happens

- `ParsedEpub.runsScripts` lists the path of every `iframe` and `object` of a document, repeats included, and reads and tokenizes the framed document once for each entry. Only the length of the chain is capped, at 8 documents (`MAX_FRAME_NESTING`).
- So chapter c0 with 20 `<iframe src="c1.xhtml">`, c1 with 20 frames of c2, and so on to c7, make `isScripted(0)` tokenize c7 20^7 times, about 1.3 x 10^9.
- `isScripted` is public, and `scriptedChapters` calls it for every chapter. `KiteDocView` calls it on the UI thread for a tap on an EPUB page while a script handler is set (`scriptedTap`), and on the script lane for each chapter that the reader reaches. Its result is kept only once it returns.
- The paint has the same shape. `paintFrameBox` paints each frame box, and the page of the frame paints its own frame boxes, down to `MAX_FRAME_DEPTH` (8). The layouts are cached by path and size, but the paints multiply: 20^7 frame paints for one page of c0.
- `framesOf` keeps each path once, so only these two walks fan out.

#### What should happen

- Each distinct document is read once: the frames of a document as a set, and the answer kept per path.
- A chapter paints a bounded number of frames in all, not only a bounded depth of them. WebKit and Chromium stop at 1000 frames for a page.

#### How to reproduce

```kotlin
// kitepdf-epub, test/EmbedTest.kt (which has book())
@Test
fun repeated_frames_are_read_once() {
    val doc = book((0..7).map { i -> if (i < 7) """<iframe src="c${i + 1}.xhtml"></iframe>""".repeat(20) else "<p>last</p>" })
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    doc.isScripted(0)
    assertTrue(mark.elapsedNow().inWholeSeconds < 2) // about 1.3 x 10^9 tokenizations today
}
```

With 5 frames per document instead of 20, the call makes 5^7 = 78,125 tokenizations and returns in seconds, which shows the growth. `page(doc).renderTo(RecordingCanvas())` on that book paints c7 5^7 times.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:166-179`: `runsScripts`, with `MAX_FRAME_NESTING` at line 941.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1944-1952`: `paintFrameBox`, and `framePage` at line 414.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:528` and `KiteDocViewState.kt:1013`: the viewer's calls.

#### Notes

Read from source.

Measured on the JVM at `c4e7574` by the lead: opening and rendering every page of the eight-chapter book: 3 frames per chapter 260 ms, 5 frames 355 ms and 239 MiB allocated, 20 frames still running after 25 s.

### KP-14

**A tiling pattern or a soft mask that paints with itself recurses until the stack overflows**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #55, #188, #450

#### What happens

- `renderTilingPattern` replays the cell with `dispatch` and the cell's own resources. A cell whose resources name the same pattern, and which fills or strokes with it, calls `renderTilingPattern` again. Nothing counts this nesting: `formDepth` covers forms and Type 3 glyphs only, `MAX_TILES` bounds one level, and `MAX_DISPATCHED_OPS` (20 million) allows millions of levels of four operators each, far past any stack.
- A soft mask group renders in a new `PageRenderer(target, resolver)` (`renderMaskGroup`). That renderer starts with `formDepth = 0`, an op budget of its own and no `KiteCancellation`. The group resets the soft mask on entry (ISO 32000-1, 11.6.6), but `gs` sets it again (`applyExtGState` has no guard), so a group whose content selects the ExtGState that names it, then paints, renders itself again in another new renderer. Each level also opens two layers of the mask box before it recurses: `KiteRasterCanvas.applySoftMask` allocates two `IntArray(width * height)`, and `ComposeCanvas` two `saveLayer`.
- The same recursion runs through a pattern stroke (`strokeWithPattern`) and a pattern fill of text (`glyphShapes`, then `paintFill`).
- The recursion ends only when the stack overflows, or, for the soft mask, when memory runs out first. `render` has a `try`/`finally` and no `catch`, so nothing on the page after the fill draws.
- Kotlin/Native: the process ends. Opening such a page in `KiteDocView` on iOS or macOS ends the app.
- JVM and Android: a StackOverflowError (or OutOfMemoryError) leaves `renderTo`. The viewer's raster guard catches Throwable and shows a placeholder after two attempts. The tiling case also recurses during text extraction, since `StructuredTextExtractor` runs the same renderer on a collector canvas, so `page.structuredText`, `page.textContent()` and `doc.search(...)` throw StackOverflowError into the app.
- The soft mask sub-renderer also drops the caller's cancellation and op budget, so a raster that the viewer cancelled keeps drawing every soft mask group of the page (#188), and each mask group gets 20 million operators of its own.
- MuPDF stops both: `pdf_run_xobject` keeps a cycle list for every xobject it runs, soft mask groups included ("Avoid infinite recursion", `pdf-op-run.c`), and a pattern runs under its clip and layer nesting limit ("layer/clip nesting too deep"). Poppler renders both files below.

#### What should happen

- A pattern that is already painting, and a mask group that is already rendering, paint nothing when they come round again, as MuPDF's cycle list does. ISO 32000-1, 8.7.3 and 11.6.5 do not describe such a cycle, so this is salvage.
- The renderer of a mask group inherits the caller's form depth, op budget and cancellation.

#### How to reproduce

Self tiling (`selftiling.pdf`):

```
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Pattern << /P1 5 0 R >> >> /Contents 4 0 R >>
4 0 obj (content) /Pattern cs /P1 scn 0 0 200 200 re f
5 0 obj << /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 20 20] /XStep 20 /YStep 20
          /Resources << /Pattern << /P1 5 0 R >> >> /Length .. >>
stream
/Pattern cs /P1 scn 0 0 10 10 re f
endstream
```

Self soft mask (`selfmask.pdf`):

```
3 0 obj << /Type /Page ... /Resources << /ExtGState << /G1 5 0 R >> >> /Contents 4 0 R >>
4 0 obj (content) /G1 gs 0 0 200 200 re f
5 0 obj << /Type /ExtGState /SMask << /S /Luminosity /G 6 0 R >> >>
6 0 obj << /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceGray >>
          /Resources << /ExtGState << /G1 5 0 R >> >> /Length .. >>
stream
/G1 gs 1 g 0 0 200 200 re f
endstream
```

Probe, `kitepdf-pdf`, `test@jvm`, in a class whose name ends in `Test`:

```kotlin
for (name in listOf("selftiling.pdf", "selfmask.pdf")) {
    val doc = PdfDocument.open(File("$dir/$name").readBytes())
    println("$name render: " + runCatching { doc.pages[0].renderTo(KiteRasterCanvas(200, 200)) }.exceptionOrNull())
}
val tiling = PdfDocument.open(File("$dir/selftiling.pdf").readBytes())
println("text: " + runCatching { tiling.pages[0].structuredText }.exceptionOrNull())
```

Expected: StackOverflowError for each (OutOfMemoryError is possible for the mask). The same render on `-p macosArm64` ends the test binary with a signal.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1836` `renderTilingPattern`, `:1869` (the cell loads its own patterns), `:1909` (the replay), `:1698` and `:1783` (fill and stroke call it).
- `PageRenderer.kt:1954` `withSoftMask`, `:2008`, `:2097` `renderMaskGroup`, `:2107` the new renderer with no depth, budget or cancellation.
- `PageRenderer.kt:1029` (the form depth guard these paths skip), `:1293` and `:2860` (the per-renderer op budget).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/GraphicsState.kt:108` `applyExtGState`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:205-215` (two full layers per level).

#### Notes

Read from source. The tests cover a tiling pattern (`TilingPatternTest`) and soft masks (`SoftMaskBackdropTest`, `TransparencyTest`), but none that refers to itself. MuPDF reference: `pdf_run_xobject` and `pdf_show_pattern` in `source/pdf/pdf-op-run.c` (1.27.2).

Measured on the JVM at `c4e7574` by the lead: `selftiling.pdf` throws StackOverflowError out of `renderTo` and out of `structuredText` on a thread with the default stack; the PoC of the rendering lane (`tiling-self-recursion.pdf`, a pattern that fills with itself, followed by a blue square) still overflows on a thread with a 512 MiB stack, after 13.9 s and 2.8 GiB of allocation; `selfmask.pdf` throws StackOverflowError under `AwtPdfRasterizer`. A `RecordingCanvas` does not reach the soft mask case. Found by two lanes.

### KP-15

**A `/Kids` that names its own node sends the page label, named destination and form field walks into endless recursion**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #55, #450

#### What happens

Three walkers follow `/Kids` through `resolve` with neither a visited set nor a depth:

- `PageLabelTree.walk`, behind `PdfPage.label`.
- `DestinationCatalog.walkNameTree`, behind `PdfDocument.resolveDestination`, `PdfDocument.outline` and every PDF link tap in the viewer (`KiteDocView.kt:565`). `outline` resolves the destination of each entry, and the first call builds the whole `/Names /Dests` tree, even when every entry has an explicit destination.
- `PdfFormField.walk`, for kids that carry `/T`, behind `formFields`, `widgetOwners`, `PdfPage.widgetAt`, `signatures` and the form APIs. Each level also builds the dotted name again (`a.a.a...`), so the strings on the stack grow with the square of the depth.

A node `5 0 obj << /Kids [5 0 R] >>` recurses until the stack overflows.

- Kotlin/Native: the process ends, on the first `label`, outline, link tap or form access.
- JVM and Android: StackOverflowError, an Error, leaves `page.label`, `doc.outline` and `doc.formFields`. `skipIfBroken` in `PdfFormField` catches `Exception` only, and a `lazy` that throws runs again on the next access. `KiteOutlinePanel` reads `state.document.outline` as a default argument, in composition on the UI thread, so showing the outline panel of such a file crashes the app on every platform.
- The generic `NameTreeWalker` and `NumberTreeWalker` (`parser/NameTree.kt`) carry a depth cap of 64. These three walkers do not use them.
- MuPDF keeps a `pdf_cycle_list` while it loads a name tree (`pdf_load_name_tree_imp` in `pdf-nametree.c`). Poppler opens and renders all three files below.

#### What should happen

Each walk keeps the object numbers it has entered and skips a kid it has seen, and keeps a depth cap as a backstop, as `walkPageTree` does. A cycle is malformed (ISO 32000-1, 7.9.6, 7.9.7 and 12.7.3.1 describe trees), so the walk salvages what it reached.

#### How to reproduce

```
% page labels (pagelabels-cycle.pdf)
1 0 obj << /Type /Catalog /Pages 2 0 R /PageLabels 5 0 R >>
5 0 obj << /Kids [5 0 R] >>

% named destinations, with one outline entry whose destination is explicit (dests-cycle.pdf)
1 0 obj << /Type /Catalog /Pages 2 0 R /Names << /Dests 5 0 R >> /Outlines 6 0 R >>
5 0 obj << /Kids [5 0 R] >>
6 0 obj << /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>
7 0 obj << /Title (Start) /Parent 6 0 R /Dest [3 0 R /Fit] >>

% form fields (field-cycle.pdf)
1 0 obj << /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] >> >>
5 0 obj << /T (a) /FT /Tx /Kids [5 0 R] >>
```

Probe, `kitepdf-pdf`, `test@jvm`:

```kotlin
println(runCatching { open("pagelabels-cycle.pdf").pages[0].label }.exceptionOrNull())   // StackOverflowError
println(runCatching { open("dests-cycle.pdf").outline }.exceptionOrNull())               // StackOverflowError
println(runCatching { open("field-cycle.pdf").formFields }.exceptionOrNull())            // StackOverflowError or OutOfMemoryError
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PageLabels.kt:100-129` (`walk`), reached from `PdfDocument.kt:504` and `PdfPage.kt:61`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDestination.kt:72` and `:76-98` (`walkNameTree`), reached from `PdfDocument.kt:513`, `:549-558` and `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:565`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfFormField.kt:335-367` (`walk`), `:351` (the name), `:228-234` (`skipIfBroken` catches `Exception`).
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteWidgets.kt:280` (the outline read in composition).

#### Notes

Read from source. The tests cover cycles in object streams, forms (`TextExtractorScopesTest`, `RedactionFormCloneTest`), colour spaces (`SeparationLabTest`), visibility expressions (`AnnotationRulesTest`) and article threads (`ArticleThreadTest`), not in these three trees.

Measured on the JVM at `c4e7574` by the lead: with the default thread stack, `outline` throws StackOverflowError in `DestinationCatalog.walkNameTree`, `formFields` in `PdfFormField.walk`, `signatures` in `PdfFormField.resolveDictRef`, and `PdfPage.label` in `PageLabelTree.walk`.

### KP-16

**A function that names itself, or a Type 4 program of deep braces, recurses until the stack overflows**

- Repo: KitePDF
- Labels: area:core, area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #128, #450

#### What happens

- `KiteFunction.parse` follows a function array and the `/Functions` of a stitching function (Type 3) through `resolve`, with no cycle list and no depth. A shading whose `/Function 6 0 R` is `6 0 obj [6 0 R]`, or `6 0 obj << /FunctionType 3 /Domain [0 1] /Functions [6 0 R] /Bounds [] /Encode [0 1] >>`, recurses until the stack overflows.
- `PostScriptCalc.parseBlockBody` recurses once for each `{` of a Type 4 program, with no limit, and `exec` once for each nested `if` or `ifelse`. A tint transform of 200,000 `{` is 400 KB of program and about 1 KB after Flate.
- Functions are parsed when a page loads its resources (`loadShadings`, `loadColorSpaces`, a soft mask's `/TR`), so a render and a text pass both reach them.
- Kotlin/Native: the process ends. On a 512 KB pool thread of an Apple platform a few thousand braces are enough. No `runCatching` helps there.
- JVM: most paths overflow and then salvage, because a `runCatching` (which catches Throwable) sits around `compile`, around each shading (`KiteShading.parseDict`) and around each colour space (`ContentStreamParser.colorSpaces`). The soft mask transfer function has none: `loadExtGStates` calls `ExtGState.parse`, which calls `KiteFunction.parse` for `/TR`, so a `/TR` cycle throws StackOverflowError out of `render` (the viewer shows a placeholder) and out of `structuredText`, `textContent()` and `doc.search` into the app.
- MuPDF: `pdf_load_function_imp` keeps a `pdf_cycle_list` and throws "recursive function", and `parse_code` stops at 100 levels with "too much nesting in calculator function" (`pdf-function.c`). Poppler, which has no such limit, ends with a segmentation fault on `ps-nesting.pdf`, which shows that the file reaches deep recursion in a real reader.

#### What should happen

`parse` keeps the functions it has entered and returns null for a function that refers to itself, and the calculator parser stops at a fixed depth, as MuPDF does (ISO 32000-1, 7.10.4 and 7.10.5).

#### How to reproduce

`functioncycle-array.pdf` and `functioncycle-stitch.pdf`: a page with content `/Sh1 sh` and `/Shading << /Sh1 5 0 R >>`, where `5 0 obj << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] /Function 6 0 R >>` and object 6 is one of the two forms above.

`ps-nesting.pdf`: content `/CS1 cs 0.5 scn 0 0 200 200 re f`, `/ColorSpace << /CS1 [/Separation /Spot /DeviceGray 5 0 R] >>`, and object 5 a Flate stream `<< /FunctionType 4 /Domain [0 1] /Range [0 1] >>` holding 200,000 `{` then 200,000 `}`.

`functioncycle-tr.pdf`: content `/G1 gs 0 0 200 200 re f`, `5 0 obj << /Type /ExtGState /SMask << /S /Luminosity /G 7 0 R /TR 6 0 R >> >>`, `6 0 obj [6 0 R]`, and object 7 a transparency group form.

Probe, `kitepdf-pdf`, `test@jvm`:

```kotlin
val tr = open("functioncycle-tr.pdf")
println(runCatching { tr.pages[0].renderTo(KiteRasterCanvas(200, 200)) }.exceptionOrNull())   // StackOverflowError
println(runCatching { tr.pages[0].structuredText }.exceptionOrNull())                          // StackOverflowError
```

The other three files draw on the JVM after the overflow is caught, and each one ends a `-p macosArm64` or `-p linuxX64` test binary that renders its page.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:265-279` (`parse`, the array case at `:272`), `:307` (stitching sub-functions), `:316` (the `runCatching` around `compile`).
- `KiteFunction.kt:351` `compile`, `:381-395` `parseBlockBody`, `:398` `exec`, `:479-480` `if` and `ifelse`.
- Callers: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:263`, `:284`, `:303` (inside the `runCatching` of `parseDict`, `:247`), `KiteColorSpace.kt:405`, `:415` (inside the `runCatching` of `kitepdf-pdf/.../content/ContentStream.kt:90`), `MeshShading.kt:54`, `ExtGState.kt:133` (no catch on the way from `PageRenderer.kt:507-512`).

#### Notes

Read from source. `FunctionEvalTest` has no nested or self-referring case. The colour space walk has its own cap of 8 (`KiteColorSpace.kt:322`), which does not cover the function inside.

Measured on the JVM at `c4e7574` by the lead: the stitching and array cycles render on the JVM (the `runCatching` of the shading and colour space paths recovers); the `/TR` cycle throws StackOverflowError out of `renderTo` and out of `structuredText`; `ps-nesting.pdf` renders on the JVM even on a 512 KB stack (the compile's `runCatching` catches the overflow). The Kotlin/Native crash was not run.

### KP-17

**The parser's nesting cap and the resolution depth cap multiply, so a 150 KB file needs about 130,000 stack frames**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Likely
- Related: #450

#### What happens

- `Parser` allows 256 nested arrays and dictionaries, counted per parser.
- A stream dictionary whose `/Length` is a reference resolves it while the parser is still inside its containers. `resolveInPlace` parses the target with a new `Parser`, whose count starts at 0, and `withCycleClaim` allows 256 such levels.
- So object 10 made of 255 nested arrays around `<< /Length 11 0 R >> stream`, with object 11 the same shape, and so on for 256 objects, recurses about 256 x (2 x 255 + 10), or 133,000 frames deep. Each cap was tested alone: `ResolutionGuardTest.deep_length_chain_hits_depth_cap_not_stack` builds the chain without containers, about 2,500 frames, and passes on native targets.
- JVM: the innermost `runCatching` (in `resolveStreamLengthOrNull` or `resolve`) catches the StackOverflowError, the stream falls back to the `endstream` scan, and the walk recovers.
- Kotlin/Native: nothing catches it. 133,000 frames fit in the 8 MB main thread only if a frame is under 64 bytes, and the viewer resolves on a 512 KB pool thread on Apple platforms. If the catalog's `/Pages` points into the chain, `pageCount` ends the process right after `open`.
- Poppler opens and renders the file.

#### What should happen

One depth budget covers the whole recursion: the parser of a nested resolve starts from the depth of the parser that asked for it, or the resolution depth counts container levels too, so the product stays within what a 512 KB stack holds.

#### How to reproduce

Probe, `kitepdf-pdf`, `test`, run with `-p macosArm64` or `-p linuxX64`:

```kotlin
private fun nestedLengthChainPdf(arrays: Int = 255, chain: Int = 256): ByteArray {
    val sb = StringBuilder("%PDF-1.4\n")
    val offsets = HashMap<Int, Int>()
    fun obj(n: Int, body: String) { offsets[n] = sb.length; sb.append("$n 0 obj\n$body\nendobj\n") }
    obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
    obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
    for (i in 0 until chain) {
        val n = 10 + i
        val length = if (i + 1 < chain) "${n + 1} 0 R" else "1"
        obj(n, "[".repeat(arrays) + "<< /Length $length >>\nstream\nx\nendstream" + "]".repeat(arrays))
    }
    val size = 10 + chain
    val xrefAt = sb.length
    sb.append("xref\n0 $size\n0000000000 65535 f \n")
    for (n in 1 until size) {
        sb.append(offsets[n]?.let { it.toString().padStart(10, '0') + " 00000 n \n" } ?: "0000000000 65535 f \n")
    }
    sb.append("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
    return sb.toString().encodeToByteArray()
}

@Test fun nested_containers_times_length_chain() {
    PdfDocument.open(nestedLengthChainPdf()).resolve(PdfReference(10, 0))
}
```

Expected: returns on the JVM, ends the native test binary with a signal. The same file is `nested-length-chain.pdf` (with a page that draws object 10 as an XObject).

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/Parser.kt:52-60` (`nested`), `:115`, `:153`, `:302` (`MAX_NESTING`, per parser), `:206` and `:252-265` (`/Length` resolved from inside the containers).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:820-848` (`withCycleClaim`), `:851-866` (`resolveInPlace`, a new parser at `:860`), `:1014` (`MAX_RESOLUTION_DEPTH`).
- `kitepdf-pdf/test/io/github/yuroyami/kitepdf/ResolutionGuardTest.kt:105` (the chain tested without containers).

#### Notes

Read from source and counted. Likely, not Confirmed: the frame size of `readArray` and `readObject` on Kotlin/Native was not measured; the probe above settles it.

Measured on the JVM at `c4e7574` by the lead: `nested-length-chain.pdf` opens and renders on the JVM on a thread with a 512 KB stack, so the crash is a Kotlin/Native matter and stays Likely.

### KP-18

**A redacted copy of an encrypted PDF can only be saved without encryption, and nothing can encrypt it again**

- Repo: KitePDF
- Labels: area:pdf, kind:api
- Severity: high
- Confidence: Confirmed

#### What happens

Each piece is documented, but together they leave no safe path:

- A redaction must be saved with `saveRewritten()`. `saveIncremental()` throws `IllegalStateException` after `redactRegions`, so the old content cannot stay in the file.
- `saveRewritten()` drops the `/Encrypt` dictionary and writes every object in plain text (the class KDoc of `PdfEditor` says so; the KDoc of `saveRewritten()` itself does not).
- `PdfEditor` has no way to encrypt its output, and `PdfBuilder.encrypt` only applies to a new document. There is no API that takes existing bytes and encrypts them.
- `docs/editing.md` says "Encrypt the file again before you share it". KitePDF cannot do that.

So an app that redacts a confidential, password-protected PDF hands back a copy that opens without any password, with all the content that was not redacted. The permissions go with it: a file opened with the user password, whose `/P` forbids changes, can be edited and rewritten, and the copy has no restrictions at all.

#### What should happen

- `saveRewritten()` keeps the encryption of the document by default: the same handler, a new `/ID` second element, new IVs, and the `/Encrypt` dictionary written unencrypted, as `saveIncremental()` already does. A parameter can opt out.
- Or `PdfEditor` gets an `encrypt(userPassword, ownerPassword, permissions, random)` that applies to the rewritten output, and `PdfBuilder`'s encryptor is shared with it.
- The KDoc of `saveRewritten()` states what happens to the encryption.
- Decide whether `edit()` respects `/P` when the document opened with the user password only. ISO 32000-1, 7.6.3.1 asks a conforming reader to restrict access according to the permissions in the file. Document the choice either way.

#### How to reproduce

Measured on the JVM at `c4e7574`:

```kotlin
val rnd = SecureRandom().asKotlinRandom()
val pdf = PdfBuilder().page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "Secret page text") }
    .encrypt(userPassword = "user", ownerPassword = "owner",
        permissions = PdfPermissions.allowAll.copy(canModifyContents = false), random = rnd)
    .build(compress = true)
val rewritten = PdfDocument.open(pdf, "user").edit(rnd).apply { setInfo(title = "Rewritten") }.saveRewritten()
PdfDocument.open(rewritten).isEncrypted   // false: no password needed
```

`pdfinfo` reports the input as `Encrypted: yes (... change:no ... algorithm:AES-256)` and the rewritten file as `Encrypted: no`. `pdftotext` reads "Secret page text" from it without a password.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:52-57`: the class KDoc.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:1069-1077`: `saveRewritten()` and its KDoc.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:61-75`: the only encrypt entry point.
- `docs/editing.md:294-295`: the warning that asks for something the API cannot do.

#### Notes

Measured on the JVM, checked with Poppler 24.02 (`pdfinfo`, `pdftotext`).

### KP-19

**With pan turned off, a pager page that overflows the view can be neither scrolled nor swiped**

- Repo: KitePDF
- Labels: area:viewer, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #505, #398

#### What happens

- A pager turns its own swipe off while the page overflows the view: `pagerScrollEnabled = userScrollEnabled && !overflows && ...` in Paged and Spread. The one-finger pan is meant to take over then, and the drag past the edge of the page, which turns it, lives inside that pan handler.
- The pan handler returns at once when `KiteZoomSpec.panEnabled` is false (`if (!spec.panEnabled) return@pointerInput`). So with pan off and an overflowing page, a drag does nothing at all: no pan, no swipe, no turn. Only the wheel still pans, since `kiteWheelPaging` calls `panBy`, which does not check `panEnabled`.
- Two documented setups reach this state:
  - `KiteDocLayout.forDocument(book)` gives `Paged(fit = KitePageFit.WIDTH)` for a `scrolled-doc` book (#505). A chapter is taller than the view at zoom 1, so it overflows from the start. With `zoomSpec = KiteZoomSpec.Disabled`, a common choice for a reflowable book, a touch reader sees the top of the first chapter and cannot scroll down it or swipe to the next one.
  - `KiteZoomSpec`'s KDoc invites "an app driving zoom from its own slider (gestures disabled)". After `state.setZoom(2f)` in Paged or Spread, the page overflows, and the reader can neither move it nor swipe until the app zooms back out.
- `ScrolledFlowSceneTest` covers the scrolled chapter with the default spec only, where pan is on.

#### What should happen

A page the reader cannot pan never takes the swipe away: either the pager keeps its swipe while pan is off, or the edge turn and the scroll through a fit-width page do not depend on `panEnabled`. The wheel and the finger should follow the same flag.

#### How to reproduce

Probe for `kitepdf-compose-viewer`, `test@jvm`, in `ScrolledFlowSceneTest` (it reuses `book`, `drag` and `withPager`'s setup):

```kotlin
@Test
fun a_scrolled_chapter_with_zoom_disabled_still_scrolls_and_turns() {
    val doc = book("scrolled-doc")
    val state = KiteDocViewState(doc)
    ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
        KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.forDocument(doc), zoomSpec = KiteZoomSpec.Disabled)
    }.use { scene ->
        val driver = SceneTestDriver(scene)
        driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
        drag(scene, Offset(100f, 250f), Offset(100f, 50f))
        driver.pumpFrames(30)
        assertTrue(state.panOffset.y < -150f, "the chapter did not scroll: ${state.panOffset.y}") // fails per source: 0
        drag(scene, Offset(190f, 150f), Offset(10f, 150f))
        driver.pumpUntilState { state.currentPage == 1 } // fails per source: stays 0
    }
}
```

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1165-1168` and `:2188-2191`: the swipe is off while the page overflows.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:295-296`: the pan handler, and with it the edge turn at `:327-332`, is skipped when `panEnabled` is false.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:464-471`: the wheel pans with `panBy` whatever `panEnabled` says.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:154`: `forDocument` gives fit WIDTH for `scrolled-doc`; `:183-186` and `:217-224`: the slider use and `KiteZoomSpec.Disabled`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:445-450`: `overflows`.

#### Notes

Read from source, and measured on the JVM at `c4e7574` with the probe above (an `ImageComposeScene` of 200 by 300): the chapter's slot is 1,880 px tall, a vertical drag leaves `panOffset.y` at 0.0, and a sideways swipe leaves `currentPage` at 0.

### KP-20

**An animated navigation call from the UI thread outside a composition throws for want of a frame clock**

- Repo: KitePDF
- Labels: area:viewer, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #429

#### What happens

- The KDoc of `KiteDocViewState` says its suspend calls "may come from any thread" and run on the viewer's thread. `onViewerThread` enters the viewer's context, which carries the composition's `MonotonicFrameClock`, only when the caller is on another thread. On the viewer's own thread, and when no view is attached, it runs the call in place, in the caller's context.
- The animated calls need a frame clock in that context. `animateZoomTo` calls Compose's `animate`, and `nextPage`, `previousPage`, `animateScrollToPage`, `animateScrollToSlot` and `scrollTo(..., animate = true)` end in `PagerState.animateScrollToPage` or `LazyListState.animateScrollToItem`. These read `coroutineContext.monotonicFrameClock`, which throws `IllegalStateException: A MonotonicFrameClock is not available in this CoroutineContext` when there is none.
- A main-thread coroutine that does not come from a composition has no frame clock: `lifecycleScope` and `viewModelScope` on Android (`Dispatchers.Main.immediate`), a Swing scope on the desktop, `MainScope()` on the web. On the web every call counts as the viewer's thread, because `currentThreadMarker()` is `Unit` there.
- So `viewModelScope.launch { state.nextPage() }` throws into the host's scope instead of turning the page. `animateZoomTo` throws the same way from any such scope while no view is attached.
- `BackgroundNavigationSceneTest` calls from `Dispatchers.Default` only, and every other test calls the animated API from a `LaunchedEffect` or `rememberCoroutineScope`, whose context has the clock. `SceneTestDriver.runOnUi` is used with non-animated calls only.

#### What should happen

The suspend calls work from any coroutine, as the KDoc says: `onViewerThread` adds the viewer's frame clock when the caller's context has none (or always enters `viewer.context`), and a call with no view either waits for one or does not animate.

#### How to reproduce

Probe for `kitepdf-compose-viewer`, `test@jvm`. `runOnUi` runs its block on the EDT, the viewer's thread, under `TestUiDispatcher`, which has no frame clock:

```kotlin
@Test
fun next_page_from_a_ui_coroutine_without_a_frame_clock() {
    val doc = PdfDocument.open(PdfBuilder().apply { repeat(4) { page(width = 200.0, height = 200.0) {} } }.build())
    val state = KiteDocViewState(doc)
    val (scene, driver) = drivenScene(200, 200, queued = false) {
        KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
    }
    scene.use {
        driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
        driver.runOnUi { state.nextPage() } // expected per source: IllegalStateException (no MonotonicFrameClock)
        driver.pumpUntilState { state.currentPage == 1 }
    }
}
```

The same with `driver.runOnUi { state.animateZoomTo(2f) }`.

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:120-123`: the "any thread" promise.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1843-1850`: `onViewerThread` runs in place on the viewer's thread and with no viewer.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1322`, `:2773`, `:2812`, `:2877`: the animations.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ThreadMarker.kt` and `src@web/.../ThreadMarker.web.kt`: one marker for every caller on the web.
- `kitepdf-compose-viewer/test@jvm/io/github/yuroyami/kitepdf/compose/BackgroundNavigationSceneTest.kt:46-58`: only background callers are tested.

#### Notes

Read from source, and measured on the JVM at `c4e7574` with the probe above: `driver.runOnUi { state.nextPage() }` throws `java.lang.IllegalStateException: A MonotonicFrameClock is not available in this CoroutineContext. Callers should supply an appropriate MonotonicFrameClock using withContext.`, and the page stays 0. `animateZoomTo(2f)` throws the same, and the zoom stays 1.

### KP-21

**A content stream operator can take any number of operands, and the operation cache counts them as nothing**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #118, #218, #224

#### What happens

- `ContentStreamParser.parse` pushes every operand onto `operandStack` until the next operator, with no limit (`ContentStream.kt:163`). At the operator it copies the whole stack into the `Operation` (`:154`). `MAX_OPS_PER_STREAM` counts operations, not operands, so it does not apply.
- Each integer operand is a new `PdfInt`: 24 bytes on the JVM, plus 4 bytes for its slot in the list. `0 ` repeated 5,000,000 times and then `n` is 10 MB of content, about 10 KB as Flate, and the parse holds about 140 MB in one `Operation`. 50,000,000 operands (100 MB decoded, well under the 512 MiB filter cap) need about 1.4 GB.
- `ContentStreamParser.retainedBytes` counts 104 bytes per operation plus the bytes of strings, arrays and inline images (`:99` to `:114`). An integer, real, name or dictionary operand counts 0. So the 140 MB operation is counted as 104 bytes and enters the operation cache (`PdfDocument.kt:395`, budget 16 MB). The trim removes it only when other content fills the budget. Ten such pages keep about 1.4 GB alive in a cache that believes it holds 1 KB, and `dropOperationCache()` is the only way out.
- On Android the 5,000,000-operand parse can already run out of a 192 MB heap. The rasterizer catches `OutOfMemoryError` (#219), but any other thread that allocates in that moment can die with it. On iOS a process that grows by 1.4 GB is ended by the system.
- MuPDF keeps content operands on a fixed stack of 32 numbers (`float stack[32]` in `interpret.h`) and raises a syntax error, which it recovers from, past it (`pdf-interpret.c`, `"stack overflow"`).

#### What should happen

- An operator keeps a bounded number of operands, as MuPDF keeps 32. The most any operator of ISO 32000-1, Annex A needs is `SCN` or `scn` in a DeviceN space: one per colorant, at most 32 (Annex C, Table C.1), plus a pattern name. Past a small fixed bound the parser drops operands and warns once.
- `retainedBytes` counts every operand, so the cache budget means what it says.

#### How to reproduce

Probe, `kitepdf-pdf`, `test@jvm` (the class name must end with `Test`):

```kotlin
class OperandBudgetTest {
    @Test
    fun operands_have_a_limit_and_a_cost() {
        val content = "0 ".repeat(5_000_000) + "n"
        val rt = Runtime.getRuntime()
        System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val ops = ContentStreamParser.parse(content.encodeToByteArray())
        System.gc(); val after = rt.totalMemory() - rt.freeMemory()
        println("operands=${ops.single().operands.size} retained=${(after - before) shr 20} MB estimate=${ContentStreamParser.retainedBytes(ops)} B")
        // today: operands=5000000 retained=~140 MB estimate=104 B
    }
}
```

To see the cache keep it, put the same content in a page of a test PDF as one Flate stream, render the page twice with `NoopCanvas`, and check that `document.operationParseCount` stays at 1 while the used heap stays about 140 MB higher after a GC.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/content/ContentStream.kt:154`, `:163` (no limit), `:68` (`MAX_OPS_PER_STREAM`), `:99` to `:114` (`retainedBytes`, `stringBytes`).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:382` to `:400` (`operations`, the cache that trusts the estimate).

#### Notes

Read from source. Memory per operand is the JVM layout with compressed pointers; Kotlin/JS keeps a `Long` as an object of its own, so a `PdfInt` costs more there.

### KP-22

**Repair scans to the end of the file once for every stream that has no endstream, so a 4.5 MB file blocks open for minutes**

- Repo: KitePDF
- Labels: area:pdf, kind:perf
- Severity: high
- Confidence: Confirmed

#### What happens

- Pass 1 of `PdfRepair.rebuild` finds the next `obj` keyword with a byte search that starts 3 bytes after the last one (`PdfRepair.kt:59`), not after the end of the object it just parsed, and parses every object it finds in full (`:65` to `:69`).
- `Parser.readDictionaryThenMaybeStream` looks for `endstream` from the stream start when `/Length` is missing, indirect (repair has no resolver) or wrong (`Parser.kt:212` to `:214`). When there is no `endstream` after it, `ByteReader.indexOf` runs to the end of the file and the parse throws.
- So each stream without `endstream` costs a scan of the rest of the file. 200,000 objects `N 0 obj<<>>stream` in a 4.5 MB file make 455 billion byte positions to check (counted by the generator script below). At about one nanosecond a position that is about 8 minutes on the JVM, longer on Kotlin/Native and JS, and four times as long for each doubling of the file.
- `open` runs repair on the caller's thread, with no cancellation and no budget. The normal path has the same cost when a page's `/Contents` array names many such streams: each one scans to the end once when the page content is first decoded.
- MuPDF's repair lexes forward from where the last object ended. After a stream that has no `endstream` its scan is at the end of the file, so the pass ends (`pdf_repair_obj` and the main loop of `pdf_repair_xref`, `pdf-repair.c`). The work is linear.

#### What should happen

The repair scan continues after the end of the object it parsed (after `endstream` or `endobj`), and an `endstream` search that reaches the end of the file ends the scan for the objects after it, as MuPDF does. The cost of repair stays linear in the size of the file.

#### How to reproduce

```
python3 -I audit-repro/L2/scripts/make_no_endstream.py out.pdf 200000
# bytes 4489015 objects 200000 positions scanned 454947583805
```

Probe, `kitepdf-pdf`, `test` (any platform), timing two sizes to show the square:

```kotlin
class RepairScanCostTest {
    private fun file(n: Int) = buildString {
        append("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj\n")
        for (i in 3 until n + 3) append("$i 0 obj<<>>stream\n")
    }.encodeToByteArray()

    @Test
    fun repair_is_linear() {
        for (n in listOf(25_000, 50_000)) {
            val t = kotlin.time.measureTime { PdfDocument.openOrNull(file(n)) }
            println("$n objects: $t")   // today the second line is about 4 times the first
        }
    }
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/PdfRepair.kt:56` to `:69` (the search resumes inside the object).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/Parser.kt:212` to `:214` (the `endstream` scan).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/ByteReader.kt:97` to `:106` (`indexOf`).

#### Notes

Read from source; the position count comes from the generator. The same resume point makes repair read objects inside stream data, see the finding on repair copies below.

Measured on the JVM at `c4e7574` by the lead: `PdfDocument.open` on `no-endstream.pdf` (4.5 MB, written by `audit-repro/L2/scripts/make_no_endstream.py` and not committed) was still running after 20 s. Poppler's `pdfinfo` gives up on the same file in 85 ms.

### KP-23

**An Indexed colour space with a large hival allocates hival + 1 colours**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

- `KiteColorSpace.resolveIndexed` keeps `hival` as the file gives it. `Indexed.lut` makes an array of `hival + 1` colours the first time a colour is read, and `cs` reads one through `defaultColor()`.
- `hival` 2147483647 overflows to `Array(-2147483648)`, which throws `NegativeArraySizeException`. `dispatch` and `render` do not catch it, so the page stops drawing at that `cs`, and the exception leaves `renderTo` and `structuredText`.
- `hival` 1000000000 asks for a billion references, 4 GB or more, which is past the heap of the JVM gate and of any phone. On Kotlin/Native and in a browser, running out of memory ends the process.

#### What should happen

ISO 32000-1, 8.6.6.3 limits hival to 0 to 255. A larger value is clamped to 255, so the palette never has more than 256 entries. Neither MuPDF nor PDFium accepts a hival above 255.

#### How to reproduce

`pdfs/indexed-hival-max.pdf`: `/ColorSpace << /CS0 [/Indexed /DeviceRGB 2147483647 <FF0000>] >>` and `/CS0 cs 0 sc 0 0 100 100 re f 0 0 1 rg 100 100 100 100 re f`. Expected: a red square and a blue one. `pdfs/indexed-hival-1e9.pdf` uses 1000000000.

```kotlin
// kitepdf-core/test
@Test fun a_huge_hival_is_clamped() {
    val red = KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, Int.MAX_VALUE, byteArrayOf(-1, 0, 0)).defaultColor()
    assertEquals(RgbColor(1.0, 0.0, 0.0), red)   // NegativeArraySizeException today
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:150`: `Array(hival.coerceAtLeast(0) + 1)`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:451`: `hival` read with no upper bound.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1376`: `cs` builds the table through `defaultColor()`.

#### Notes

Read from source. `DefaultColorSpaces.imageSpace` (line 85) copies the same `hival` into a new `Indexed`.

Measured on the JVM at `c4e7574` by the lead: under `AwtPdfRasterizer`, `indexed-hival-1e9.pdf` throws OutOfMemoryError and `indexed-hival-max.pdf` throws `NegativeArraySizeException: -2147483648`, both from `KiteColorSpace.Indexed.lut` at the `cs` operator, so the whole render fails.

### KP-24

**An accented letter that a CFF font builds with seac draws nothing**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #303

#### What happens

- In a Type 2 charstring, `endchar` with the four operands `adx ady bchar achar` is the `seac` of Type 1: the glyph is the base glyph plus the accent, both named by their StandardEncoding codes (Adobe Technical Note 5177, Appendix C). CFF keeps this form for fonts converted from Type 1, where accented letters are often built this way.
- `CharstringInterpreter.endchar` hands those operands to `seacRenderer` and ends the glyph. `CffFont.outline` is the only place that builds a `CharstringInterpreter`, and it passes no `seacRenderer`. The hook is always null, so the glyph comes out empty.
- A run such as "café" then draws "caf" and a gap: `AwtCanvas.drawGlyphs` falls back to a system font only when no glyph of the run draws. Text extraction still reads é from the encoding, so the letter can be selected and copied but is not seen.
- The KDoc of `CharstringInterpreter` says `seac` works "via the seacRenderer hook the CFF font supplies". No CFF font supplies it.
- The bundled Nimbus programs have no four-operand `endchar` (checked with a script), so the standard 14 fonts are not affected. Embedded `/FontFile3 /Type1C` fonts are.

#### What should happen

The glyph draws the base glyph, then the accent glyph moved by (adx, ady), both found through StandardEncoding and the font's charset, as FreeType (which MuPDF uses) and pdf.js do.

#### How to reproduce

`work/seac.cff` is a CFF program with the glyphs `.notdef`, `e` (a box), `acute` (a small box) and `eacute`, whose charstring is `500 0 0 101 194 endchar` (101 is `e` and 194 is `acute` in StandardEncoding). Its bytes:

```
010004010001010109536561635465737400010101181d000000310f1d00000038111d0000000c1d000000681200000000000046007d00cf00040101021221290ef888bd8b15f8248b8bf888fc248b050ef7c0f75cf8ec15ef8b8bef278b050ef8888b8bf0f7560e1d00000000141d0000000015
```

```kotlin
// kitepdf-core/test, next to CffEncodingTest
class CffSeacProbeTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    @Test fun a_seac_glyph_draws_its_base_and_its_accent() {
        val cff = CffFont.parse(hex("0100...0015"))   // the bytes above
        assertTrue(cff.outline(cff.glyphIdForName("e"))!!.segments.isNotEmpty())
        val eacute = cff.outline(cff.glyphIdForName("eacute"))
        assertTrue(eacute != null && eacute.segments.isNotEmpty(), "eacute draws nothing")   // fails today
    }
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CffFont.kt:109`: the interpreter is built without `seacRenderer`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:557`: the `seac` branch of `endchar`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:20`: the KDoc that says the hook is supplied.

#### Notes

Read from source, and the probe font made with `work/mkcff.py`. #303 had the same symptom from another cause (standard strings past SID 149). A fixture that embeds a Type1C font with a `seac` letter would guard both.

### KP-25

**RevisionChanges calls a change to page content a field or annotation appearance when an appearance also names the object**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #448

#### What happens

`placesOf` walks the form first. For each field it marks every object that the field's `/AP` reaches as `WIDGET_LOOK` (`reach` follows every key but `/P` and `/Parent`). Then it walks the pages and marks every object an annotation's `/AP` reaches as `ANNOTATION_LOOK`. It never marks a page's content streams or resources, and the first mark wins.

So an object that both the page and an appearance use takes the appearance's role. A change to it is "the appearance of field X" (`FieldValue`, permitted from level 2) or "the appearance of a ... annotation" (`Annotation`, permitted at level 3, which is the rule for every approval signature). This holds for a font or a form XObject that a page and an appearance share, and for a content stream that an appearance's `/Resources` names without drawing it.

A document prepared this way before signing can have its page content replaced after signing, and `areChangesPermitted` is true. This is the "replace" shadow attack.

#### What should happen

An object that a page or anything outside the appearance also uses keeps the strictest role, or is classified as page content. One way: mark the page content and resources first, and let `reach` from `/AP` only claim objects that nothing else reaches.

#### How to reproduce

Common test:

```kotlin
@Test
fun page_content_that_an_appearance_names_is_not_a_field_value() {
    val base = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] >> >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Annots [5 0 R] >>",
        TestPdf.stream("0 0 1 rg 20 20 50 50 re f"),
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /P 3 0 R /AP << /N 6 0 R >> >>",
        TestPdf.stream("", "/Type /XObject /Subtype /Form /BBox [0 0 160 20] /Resources << /XObject << /X 4 0 R >> >>"),
    ))
    val old = PdfDocument.open(base)
    val content = "1 0 0 rg 20 20 160 120 re f"
    val new = PdfDocument.open(appendRevision(base, mapOf(4 to "<< /Length ${content.length} >>\nstream\n$content\nendstream"), size = 7))
    val change = RevisionChanges.between(old, new, level = 2, lock = null).single()
    assertEquals(PdfRevisionChange.Kind.Other, change.kind)   // fails today: FieldValue
    assertFalse(change.isPermitted)                          // fails today: true
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:182` (form walk first), `:211` (pages second), `:216` to `:232` (`reach`), `:110`, `:111`

#### Notes

Read from source.

### KP-26

**PdfSigner.prepareSignature drops the form's fields and the page's annotations, and undoes earlier edits**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`prepareSignature` builds its three updates from the base document, not from the editor's staged state, and reads two arrays without resolving them:

- An `/AcroForm` written straight into the catalog (legal, and handled by `clearNeedAppearances` and `detachRedactedFields`) is not a `PdfReference`, so the `else` branch replaces it with a new form that holds only the signature field. Every other field leaves `/Fields`, and `/DR`, `/DA`, `/NeedAppearances`, `/CO` and `/XFA` are gone.
- An indirect `/Annots` on the page is not a `PdfArray`, so the page gets `/Annots [sigField]` and loses every other annotation: links, widgets, comments.
- An indirect `/Fields` in an indirect `/AcroForm` is read as empty, with the same loss of fields.
- The page dictionary comes from `page.dictionary`, the catalog from `doc.catalog` and the form from `doc.resolve(acroRef)`. A `stampPage`, `rotatePage`, `setTextFieldValue` (which may stage the form) or a catalog change made earlier on the same editor is overwritten. "Stamp APPROVED, then sign" signs a page without the stamp.

The damage lands inside the signed revision, so it cannot be fixed afterwards without breaking the signature.

#### What should happen

`prepareSignature` reads and merges the effective (staged or base) page, catalog and form, resolves an indirect `/Annots` or `/Fields` before it appends, and keeps a direct `/AcroForm` by adding to it.

#### How to reproduce

Common test:

```kotlin
@Test
fun signing_keeps_a_direct_form_and_the_page_annotations() {
    val pdf = TestPdf.onePage(
        "q Q",
        pageEntries = "/Annots 6 0 R",
        catalogEntries = "/AcroForm << /Fields [5 0 R] /DA (/Helv 0 Tf 0 g) >>",
        extra = listOf(
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /V (Jane) /Rect [10 10 150 30] /P 3 0 R >>",
            "[5 0 R]",
        ),
    )
    val doc = PdfDocument.open(pdf)
    val editor = doc.edit()
    editor.stampPage(doc.pages[0]) { text(StandardFont.Helvetica, 20.0, 50.0, 100.0, "APPROVED") }
    val target = PdfSigner(doc, editor, placeholderSize = 1024).apply { prepareSignature("Sig1") }.saveForSigning()
    val signed = PdfDocument.open(target.bytes)
    assertEquals(2, signed.acroForm?.fieldCount)                         // fails today: 1
    assertEquals(2, signed.pages[0].annotations.size)                    // fails today: 1
    assertTrue("APPROVED" in signed.pages[0].extractText())              // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfSigner.kt:87`, `:88`, `:95`, `:96`, `:99`, `:112`

#### Notes

Read from source. `PdfSignerTest` signs only `PdfBuilder` output, which has no form and no annotations.

### KP-27

**Redacting a form XObject on one page also redacts every other page that draws it**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`recurseIntoForms` decides whether a form may be rewritten in place from the `Do` operations of the current page only (`pristine`). When every invocation on that page meets a region, `redactFormXObject` claims the original object and stages the rewrite over it (`target = formRef`). Another page that draws the same form, through its own resources or shared ones, now draws the redacted copy, and gets no black box.

The KDoc and `docs/editing.md` promise the opposite: "A place that no region meets keeps the original form." A letterhead, logo or footer drawn as one form on every page loses its content on every page when page 1 is redacted.

#### What should happen

A form that any other page, form, pattern or annotation can draw is cloned for the redacted invocation, as a form with a pristine invocation is today. Only a form that nothing else reaches is rewritten in place.

#### How to reproduce

Common test:

```kotlin
@Test
fun redacting_page_one_keeps_the_form_that_page_two_draws() {
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Fm1 6 0 R >> >> /Contents 7 0 R >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Fm1 6 0 R >> >> /Contents 8 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        TestPdf.stream("BT /F1 24 Tf 100 700 Td (ACME LETTERHEAD) Tj ET", "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >>"),
        TestPdf.stream("q /Fm1 Do Q"),
        TestPdf.stream("q /Fm1 Do Q"),
    ))
    val doc = PdfDocument.open(pdf)
    val out = PdfDocument.open(doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 400.0, 730.0)) }.saveRewritten())
    assertTrue("ACME LETTERHEAD" in out.pages[1].extractText())   // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditorRedaction.kt:159` (`pristine` from this page only), `:238`, `:239`, `:275`

#### Notes

Read from source. Every test of `RedactionFormCloneTest` has one page.

Measured on the JVM at `c4e7574` by the lead: after a redaction on page 1 only, page 2's `extractText()` is empty: `ACME LETTERHEAD` is gone from both pages. `docs/editing.md` (What redaction removes) promises a copy of the form for each place that needs a different redaction.

### KP-28

**Redaction keeps the page's thumbnail and the private data of the application that made the page**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`redactRegions` rebuilds the page dictionary with `withEntry`, which keeps every other key. Two of them hold the page as it was:

- `/Thumb` is an image of the whole page (ISO 32000-1, 12.3.4). It shows the redacted region, photos and large text included.
- `/PieceInfo` holds private data of the application that made the page (14.5). Illustrator's "preserve editing capabilities" stores the whole artwork there, text included.

`saveRewritten` reaches both through the page and writes them. `docs/editing.md` does not list either.

#### What should happen

A redacted page loses `/Thumb` (or gets a thumbnail drawn from the redacted page) and `/PieceInfo`. The docs say so.

#### How to reproduce

Common test:

```kotlin
@Test
fun redaction_drops_the_thumbnail_and_private_data_of_the_page() {
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R /Thumb 6 0 R /PieceInfo << /Illustrator << /Private 7 0 R >> >> >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        TestPdf.stream("BT /F1 24 Tf 100 700 Td (SECRET) Tj ET"),
        TestPdf.stream("THUMBPIXELSOFTHEPAGE", "/Width 20 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8"),
        TestPdf.stream("%AI private: BT (SECRET) Tj ET"),
    ))
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 300.0, 730.0)) }.saveRewritten()
    assertFalse(RawPdf.containsBytes(out, "THUMBPIXELSOFTHEPAGE".encodeToByteArray()))   // fails today
    assertFalse(RawPdf.containsBytes(out, "(SECRET)".encodeToByteArray()))              // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:884` to `:897` (the page rebuilt with every key kept)
- `docs/editing.md`, "What redaction removes"

#### Notes

Read from source. Both entries are uncommon in office documents, but a redaction that keeps them leaks the region in full.

Measured on the JVM at `c4e7574` by the lead: the rewritten output still contains the thumbnail bytes and the private `(SECRET)` stream.

### KP-29

**Revocation data of any date counts: a CRL that expired years before the signing says Good**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #634, #447

#### What happens

`Crl.parse` checks that `thisUpdate` is present and skips `nextUpdate`. `Crl.check` says Good for every serial the list does not name. `OcspResponse` skips `producedAt` and never reads `thisUpdate` or `nextUpdate`. `revocationOf` never compares a time with the signing time.

A signer whose certificate was revoked can embed a CRL or an OCSP response that the issuer published before the revocation, in `/DSS` or in `adbe-revocationInfoArchival`. `PdfCertificate.revocation` is then `Good`. KitePDF fetches nothing, so an app that relies on what the file carries is told the certificate is good.

The CRL scope is not checked either: a CRL with an `issuingDistributionPoint` that covers only CA certificates or one partition (RFC 5280, 5.2.5) says Good for every certificate outside it.

#### What should happen

A CRL or OCSP response counts only when its validity covers the time it is used for (RFC 5280, 6.3.3 (a)(1); RFC 6960, 4.2.2.1): for a signature, its `thisUpdate` is at or after the signing time, or the result says the data predates it. A CRL whose scope does not cover the certificate does not count.

#### How to reproduce

JVM test, in `RevocationTest` (its `validate` helper signs at 2026-09-28):

```kotlin
@Test
fun aCrlThatExpiredBeforeTheSigningSaysNothing() {
    fun crl(issuer: SigFixtures.Identity, thisUpdate: String, nextUpdate: String): ByteArray {
        val tbs = SigFixtures.seq(SigFixtures.int(1), issuer.signatureAlgorithm, issuer.name, SigFixtures.utcTime(thisUpdate), SigFixtures.utcTime(nextUpdate))
        return SigFixtures.seq(tbs, issuer.signatureAlgorithm, SigFixtures.bits(issuer.sign(tbs)))
    }
    val stale = crl(root, "200101000000Z", "200201000000Z")
    assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssCrls = listOf(stale))).signer?.revocation)   // fails today: Good
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/Revocation.kt:27`, `:41`, `:42`, `:88`, `:136`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/SignatureValidator.kt` (`revocationOf`)

#### Notes

Read from source. #447 notes that "a revocation check needs the time of signing too"; #634 covers only the certificates' own dates.

### KP-30

**An encrypted PDF whose objects live in object streams does not open, so it cannot be edited**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`PdfDocument.resolveInPlace` decrypts each object it reads (`maybeDecrypt`). `decodeObjectStream` reads the object stream with its own parser and passes the still encrypted bytes to `FilterChain.decode` (ISO 32000-1, 7.6.2: an object stream is encrypted as a whole, and its members are not encrypted again). `PdfRepair.objStreamMembers` does the same. Every compressed object of an encrypted file resolves to null.

When the catalog is in an object stream, `isStructurallyUsable` fails, repair finds no catalog, and `PdfDocument.open` throws "Repair found no catalog that leads to a page tree". When the catalog and the page tree are not compressed (a linearized file keeps the catalog and the pages out of object streams, 7.5.7), the file opens, and its compressed fonts, annotations and fields read as missing. Either way `edit()` and `saveIncremental()` of such an encrypted original cannot work, and `docs/editing.md` promises AES editing.

#### What should happen

`decodeObjectStream` and `PdfRepair.objStreamMembers` decrypt the object stream with its own object number before they decode it, as `resolveInPlace` does.

#### How to reproduce

`audit-repro/L4/scripts/aes_objstm.py` writes a 1 KB AES-128 (V4, R4) file with an empty user password, whose catalog, page tree, page and font are in an object stream, with a cross-reference stream:

```
python3 -I audit-repro/L4/scripts/aes_objstm.py out.pdf
```

A copy is at `audit-repro/L4/out/aes128-objstm.pdf`. Poppler 24 (`pdfinfo`, `pdftotext`) and PDFium (pypdfium2) open it and read "Hello object streams".

JVM test:

```kotlin
@Test
fun anAesFileWithObjectStreamsOpens() {
    val bytes = java.io.File("audit-repro/L4/out/aes128-objstm.pdf").readBytes()
    val doc = PdfDocument.open(bytes)   // throws today
    assertEquals("Hello object streams", doc.pages[0].extractText().trim())
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:903` to `:918` (`decodeObjectStream`), compare `:870`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/PdfRepair.kt:143`

#### Notes

Ran the script and checked the file with `pdfinfo`, `pdftotext` and pypdfium2. The KitePDF behaviour is read from source. This is a reading bug that may also come up in the reading lane; it is here because it blocks editing encrypted originals. No test opens an encrypted file with object streams: `EncryptionIntegrationTest` builds classic files, and `RepairedIncrementalSaveTest` encrypts `PdfBuilder` output.

The parsing lane found the same defect and adds one point for the fix: repair builds its table before the security handler exists (`kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:1071` and `:1072`), so repair has no key to decrypt an object stream with. An RC4 version of the file is `audit-repro/L2/repro/encrypted-objstm.pdf`.

Measured on the JVM at `c4e7574` by the lead: `PdfDocument.open` on `audit-repro/L2/repro/encrypted-objstm.pdf` throws `PdfFormatException: Repair found no catalog that leads to a page tree`.

### KP-31

**A WOFF or WOFF2 font can make the layout inflate and hold gigabytes, because no table size is checked against the font's total**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #218, #224

#### What happens

- WOFF 1.0: a table whose `compLength` is below its `origLength` inflates with `maxOutputBytes = FilterChain.MAX_DECODED_STREAM`, the 512 MiB cap of PDF streams, and is cut to `origLength` only afterwards. So a table that declares 600 KB over a 500 KB zlib stream inflates up to 500 MiB first. (A table whose `compLength` is not below its `origLength` is taken as stored, so it does not inflate.) Nothing checks the declared `origLength` of the tables against the header's `totalSfntSize`, or against any ceiling for a font.
- Every inflated table stays in `payloads` until the SFNT is put together, and two directory entries may point at the same compressed bytes. A 510 KB WOFF with four entries over one zlib stream of 500 MiB, each declaring an `origLength` of 500 MiB, holds 2 GiB of tables and then asks for a 2 GiB output array (`ByteArray(pos)`).
- WOFF2: `Brotli.decode(compressed, maxOutputBytes = FilterChain.MAX_DECODED_STREAM)`. The table directory already gives the size of the decompressed block (`srcOffset`, the sum of the table lengths), but nothing caps the decode with it. The output buffer doubles up to 512 MiB, so the peak is about 768 MiB, plus the `copyOf` of the result. A meta-block of Brotli holds up to 16 MiB, so a stream of a few hundred bytes describes that much output.
- An app on Android has a heap of 192 to 512 MB, and iOS ends an app that grows that far. The JVM suites run on a 3 GB heap (CLAUDE.md, #218), and no test decodes a font that declares less than it holds.
- The `runCatching` around `Zlib.decode` catches an `OutOfMemoryError` there, but not the one of `ByteArray(pos)`, and another thread that allocates at that moment fails too.

#### What should happen

- WOFF 1.0 gives the length of each uncompressed table in the table directory (`origLength`), and the size of the whole font in the header (`totalSfntSize`). Inflate each table with its `origLength` as the cap, and check that the tables fit `totalSfntSize`.
- WOFF2: decode the Brotli block with the size the table directory gives as the cap, as the reference decoder sizes its output from the directory.
- A font has a ceiling of its own far below 512 MiB.

#### How to reproduce

`audit-repro/L5/make_woff_bomb.py <dir>` writes the 510 KB `bomb.woff` above. Then:

```kotlin
// kitepdf-epub, test@jvm/
FontProgram.parse(File("bomb.woff").readBytes()) // holds 2 GiB, then asks for 2 GiB more
```

A book whose style sheet names it in an `@font-face` does the same on the layout of its first chapter.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff.kt:44-56`: each table, inflated with the stream cap; `:74`: the output array.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff2.kt:81-83`: the Brotli block.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/filters/Filters.kt:65`: `MAX_DECODED_STREAM`.

#### Notes

Read from source, and built the WOFF file with Python's zlib (509,728 bytes). No Brotli stream was built, since this machine has no Brotli encoder; the WOFF2 part rests on the cap that the code passes.

Measured on the JVM at `c4e7574` by the lead: a one-chapter EPUB whose `<style>` names `bomb.woff` in an `@font-face` (510 KB; four entries over one 500 MiB zlib stream, `origLength` 500 MiB each, `totalSfntSize` 92) fails with OutOfMemoryError on a 3 GB heap, 0.45 s into the layout of its chapter. The first version of this finding said that a table which declares 4 bytes still inflates; it does not (`compLength >= origLength` is read as stored), and the text above is corrected.

### KP-32

**A WOFF font whose table directory overflows an Int throws out of the font parser, and every chapter of the book fails to lay out**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #331, #582

#### What happens

- `Woff.toSfnt` checks `e.woffOffset + e.compLen > b.size` in `Int`. A `compLength` of `0x80000000` or more is negative after `toInt()`, and a large one wraps the sum, so the check passes.
- `b.copyOfRange(e.woffOffset, e.woffOffset + e.compLen)` then throws `IllegalArgumentException`.
- `FontProgram.parse` calls `Woff.toSfnt` outside any `runCatching`. `Woff2.toSfnt`, `TrueTypeFont.parse` and the CFF parse are each wrapped.
- The exception goes up through `programAt` and `loadFace` into the `fonts` lazy of `ParsedEpub`, which every chapter without a remote font reads in `fontsFor`. A lazy whose initializer throws throws again at the next read, so the layout of every chapter throws.
- In `KiteDocView` every chapter stays a placeholder (#331). Through the API, `pageCount`, `page` and `prepareChapter` throw `IllegalArgumentException`.

#### What should happen

A font that does not parse is skipped, and the rest of the book lays out with its other fonts (CONTRIBUTING.md, "Never break lenient salvage"). The directory check runs in `Long`, and the WOFF 1.0 unwrapping is guarded as the WOFF2 one is.

#### How to reproduce

```kotlin
// kitepdf-epub, test/
@Test
fun a_woff_whose_directory_overflows_is_skipped() {
    val b = ByteArray(64)
    "wOFF".encodeToByteArray().copyInto(b, 0)
    b[13] = 1                                          // numTables 1
    "cmap".encodeToByteArray().copyInto(b, 44)          // the one entry: tag
    b[51] = 44                                          // offset 44
    for (i in 52..54) b[i] = 0xFF.toByte(); b[55] = 0xF0.toByte() // compLength 0xFFFFFFF0
    b[59] = 1                                           // origLength 1
    assertNull(FontProgram.parse(b)) // throws "fromIndex (44) is greater than toIndex (28)" today
}
```

A book whose linked style sheet has `@font-face { font-family: X; src: url(bad.woff) }` with these bytes lays out no chapter at all.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff.kt:46-47`: the check in `Int`, and the copy that throws.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/FontRegistry.kt:41-45`: the unwrapping outside `runCatching`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:463-467` (`fonts`), `:611-621` (`programAt`), and `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:866` (`fontsFor`).

#### Notes

Read from source.

### KP-33

**Two regular expressions over a book's CSS backtrack quadratically, so one style sheet stalls the layout of its chapters for minutes**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

- `ParsedEpub` rewrites every style sheet and `<style>` block with two regular expressions before it parses them: `IMPORT_RE` in `inlineImports` and `URL_RE` in `absoluteUrls`. `remoteCssUrls` runs `URL_RE` over every `style` attribute and declaration as well.
- In `IMPORT_RE`, `([^"')\s;]+)` and the later `[^;{]*;` can both take the same letters. After `@import ` with no `;` behind it, the engine tries every split between the two, which is quadratic in the length of the text.
- In `URL_RE`, `([^"')]*)` runs to the end of the text when no `"`, `'` or `)` follows, then gives back one character at a time. A text of `url(` repeated starts a new attempt at each `url(`, which is quadratic too.
- The rewrite runs on the thread that parses the chapter, with no cancellation, once per style sheet and once per `<style>` block.

#### What should happen

The rewrite is linear in the length of the style sheet: it walks the tokens of `CssTokenizer`, or uses patterns whose parts cannot take the same characters, so that a failed match costs one pass.

#### How to reproduce

The same patterns in Python's backtracking engine, which works as `java.util.regex` does:

| Input | Length | Time |
|---|---|---|
| `"@import " + "a" * n` | 20,000 | 2.4 s |
| `"@import " + "a" * n` | 40,000 | 9.4 s |
| `"url(" * n` | 40,000 | 5.0 s |
| `"url(" * n` | 80,000 | 19.8 s |

In Kotlin on the JVM:

```kotlin
// kitepdf-epub, test/
@Test
fun a_long_unterminated_import_rewrites_in_linear_time() {
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    ParsedEpub.absoluteUrls("url(".repeat(50_000), "OEBPS")
    assertTrue(mark.elapsedNow().inWholeSeconds < 1, "took ${mark.elapsedNow()}")
}
```

A book whose style sheet is `@import ` followed by 500 KB without a `;`, or 500 KB of `url(`, does not lay out the chapters that link it.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:875-878`: `IMPORT_RE`, used by `inlineImports` at line 637.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:860`: `URL_RE`, used by `absoluteUrls` at line 851 and `remoteCssUrls` at line 863.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:501` and `:555-559`: where they run on each style sheet.

#### Notes

Measured the two patterns, copied as written, with Python 3's `re`: `audit-repro/L5/import_re.py` and `url_re.py`. The JVM timing is the probe above.

### KP-34

**SVG path data drops a number that starts with a dot right after another number, and misreads arc flags written without a separator**

- Repo: KitePDF
- Labels: area:svg, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

- `PathScanner.num` takes every digit and every dot that follow, so `0.5.5` is one token. `"0.5.5".toDoubleOrNull()` is null, and the scanner answers 0.0 for it. The next `num()` then finds a command letter and answers 0.0 too.
- So `M.5.5L10.5.5` draws a point at the origin: both coordinate pairs are read as `0 0`. The grammar of SVG 1.1, 8.3.9 lets a number that starts with a dot follow another number with no separator, and optimizers such as svgo write paths that way.
- An arc flag is a single `0` or `1` in the same grammar (`flag: "0" | "1"`), and optimizers can write the flags with no separator: `a1 1 0 01-1-1`. `num()` reads `01` as one number, so the large-arc flag is true, the sweep flag is read from `-1`, and the end point from the next numbers. The arc lands elsewhere and the rest of the command shifts.
- A number that cannot be read becomes 0.0 and the path goes on, so a broken path draws lines to the origin. SVG 1.1, F.2 renders a path up to, and not including, the command with the first error.
- `Path2D` of a book's canvas scripts parses with the same scanner (`CanvasHost.kt:328`).

#### What should happen

The scanner reads one number at a time as the grammar of SVG 1.1, 8.3.9 has it: at most one dot and one exponent, and a flag as one character. An unreadable number ends the path at the last good command (SVG 1.1, F.2).

#### How to reproduce

```kotlin
// kitepdf-svg, test/
@Test
fun numbers_may_start_with_a_dot_after_a_number() {
    val path = SvgImage.parsePathData("M.5.5L10.5.5")
    val end = path.segments.last() as KitePath.Segment.LineTo
    assertEquals(10.5, end.x, 1e-9) // 0.0 today
    assertEquals(0.5, end.y, 1e-9)  // 0.0 today
}

@Test
fun arc_flags_need_no_separator() {
    val joined = SvgImage.parsePathData("M10 10a5 5 0 0110 0")
    val spaced = SvgImage.parsePathData("M10 10a5 5 0 0 1 10 0")
    assertEquals(spaced.segments.toString(), joined.segments.toString())
}
```

#### Where

- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:2099-2106`: `PathScanner.num`.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:2018-2023`: the arc command, which reads its flags with `num()`.

#### Notes

Read from source. No test of `kitepdf-svg` has a compact number or compact flags.

### KP-35

**A dash pattern finer than a pixel makes the AWT canvas stroke millions of dashes, and a finer one ends the page render**

- Repo: KitePDF
- Labels: area:renderer, backend:awt, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #106, #107

#### What happens

- The renderer passes the dash array of `d` (and of `/D` in an ExtGState) to the canvas unchanged, except an all-zero array (`PageRenderer.kt:1346`, `GraphicsState.kt:127`).
- `awtDash` keeps every positive element, however small, and `strokePath` hands it to `BasicStroke` (`AwtCanvas.kt:125-131`, `:1618-1621`). Java2D's Marlin dasher then emits one dash for each element along the whole path. The count is the path length divided by the dash length, so it does not depend on the scale.
- Measured with JDK 21's Marlin, as `AwtCanvas` strokes (device-space path, width and dashes scaled by 2, antialiasing on, `STROKE_PURE`), one line of 1,000 points on a 1,224 by 1,584 image:

| Dash array (user units) | Dashes | Time of one `g.draw` |
|---|---|---|
| `[0.001 0.001]` | 0.5 million | 0.4 s |
| `[0.0001 0.0001]` | 5 million | 3.4 s |
| `[0.00001 0.00001]` | 50 million | 8 s, then `ArrayIndexOutOfBoundsException: array exceeds maximum capacity !` from `Renderer.addLine` |

- Nothing catches the exception: `withComposite` only restores the composite, the content stream has no per-operator catch, and `AwtPdfRasterizer.renderToImage` has none. So the whole page fails after the wait.
- A page of 50 such lines (`tiny-dash-x50.pdf`, 1.4 KB, 186 million dashes) costs about two minutes at the rate measured above. The time adds up with each stroke, so a small file holds `AwtPdfRasterizer` for as long as it likes.
- The other canvases do not have the problem. The pure-Kotlin stroker draws solid past 100,000 dashes (`StrokeOutline.kt:42-44`, `MAX_DASHES`), and Skia, under `SkiaCanvas`, `ComposeCanvas`, `AndroidNativeCanvas` and Chrome's Canvas2D, gives up dashing past one million dashes and strokes the path solid (`SkDashPath`, `kMaxDashCount`).

#### What should happen

A dash pattern too fine to show strokes solid on AWT too, by the rule the pure-Kotlin stroker already has, or by MuPDF's (`fz_flatten_dash_path` strokes without dashes when the pattern is shorter than 0.01 units or half a device pixel). ISO 32000-1, 8.4.3.6 sets no lower limit, so the choice is the reader's.

#### How to reproduce

`fixtures/tiny-dash-1e-4.pdf` and `fixtures/tiny-dash-1e-5.pdf` are one page each with this content (`1e-5` for the second):

```
2 w [0.0001 0.0001] 0 d 0 0 m 612 792 l S
```

Probe for `kitepdf-native-renderer`, `test@jvm` (the class name must end in `Test`):

```kotlin
class TinyDashTest {
    private fun page(name: String) = PdfDocument.open(
        java.io.File("audit-repro/L6/fixtures/$name").readBytes(),
    ).pages[0]

    @Test fun aDashFinerThanAPixelStrokesInBoundedTime() {
        val start = System.nanoTime()
        AwtPdfRasterizer.renderToImage(page("tiny-dash-1e-4.pdf"), scale = 2.0)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 500, "one stroke took $ms ms")          // about 3,400 ms today
    }

    @Test fun aFinerDashStillRendersThePage() {
        AwtPdfRasterizer.renderToImage(page("tiny-dash-1e-5.pdf"), scale = 2.0)   // throws ArrayIndexOutOfBoundsException
    }
}
```

The standalone measurement is `L6/dashprobe/DashProbe.java` (`javac DashProbe.java && java -Djava.awt.headless=true DashProbe`).

#### Where

- `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:125-131` (the stroke) and `:1618-1621` (`awtDash`).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/StrokeOutline.kt:42-44`, `:57`: the guard the AWT path lacks.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1346`: the dash array reaches the canvas as written.

#### Notes

Measured the Java2D part with a standalone JDK 21 program that mirrors `AwtCanvas.strokePath`; KitePDF itself was not run. Skia's one-million-dash limit is quoted from its source (`SkDashPath.cpp`), not measured here. Poppler's `pdftoppm` is slow on the same files too (22 s and 16 s), so this is a weak spot of rasterizers in general, but KitePDF's own stroker and every Skia-based canvas already guard against it. CoreGraphics was not checked. MuPDF's rule is quoted from memory, since `mupdf-master/` is not in this checkout.

Measured on the JVM at `c4e7574` by the lead: with `AwtPdfRasterizer`, `tiny-dash-1e-4.pdf` (one line) took 4.8 s; `tiny-dash-1e-5.pdf` threw `ArrayIndexOutOfBoundsException: array exceeds maximum capacity` from Marlin's `Renderer.addLine` after 11.7 s, and the page failed; `tiny-dash-x50.pdf` was still running after 25 s.

### KP-36

**A field event whose script does not compile, or is stopped, takes the previous event's result**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`PdfScriptRunner.dispatch` runs a field script as `__kiteEvent(__kiteEventInfo, function () { <script> })` and then returns `host.lastEventResult`. That result is only written by `__kite.eventResult`, which `__kiteEvent` calls in a `finally` block. It is never reset before an event, and `dispatch` ignores whether `evaluate` failed. Two kinds of failure never reach the `finally`:

- A script that does not compile. The wrapper fails to parse, so `__kiteEvent` never runs.
- A script that the engine stops: the deadline (`budgetMillis`), the instruction budget, the memory limit, or a host function that threw. QuickJS raises these as uncatchable errors, and an uncatchable error skips every `catch` and `finally` block (`quickjs.c:20965`, the unwinding loop runs only for a catchable error).

Then the event uses the rc, value and change of whatever event ran before it, often another field's:

- `calculateAll`: `if (result.rc && ...) formState.setValue(name, result.value)` writes the previous field's calculated value into this field.
- `commitOnThread`: a stale `rc = false` refuses the commit, and a stale value from the validate step is stored in the field.
- `keystrokeHere`: the reader's keystroke is replaced by the previous event's `change`.
- `formatOnThread`: the field shows the previous field's formatted text.

The values go into `PdfFormState`, which the editor saves. A real form only needs one broken script, for example one written for Acrobat with syntax QuickJS refuses, or one calculation that runs past five seconds on a slow phone.

#### What should happen

An event whose script fails leaves the event unchanged: rc true, the value and change it started with. That is the `unchanged` result `dispatch` already builds for an engine that would not open.

#### How to reproduce

A `kitepdf-javascript` test (common). Two text fields, `a` and `b`, with calculate scripts `event.value = 42;` and `event.value = ;`, and `/CO [a b]`:

```kotlin
@Test
fun a_calculation_that_does_not_compile_leaves_its_field_alone(): TestResult = scriptTest {
    // Build the PDF as FormScriptTest.invoicePdf does, with:
    //   4 0 obj << /Type /Annot /Subtype /Widget /FT /Tx /T (a) /V () /Rect [20 200 120 220]
    //              /AA << /C << /S /JavaScript /JS (event.value = 42;) >> >> >>
    //   5 0 obj << /Type /Annot /Subtype /Widget /FT /Tx /T (b) /V () /Rect [20 160 120 180]
    //              /AA << /C << /S /JavaScript /JS (event.value = ;) >> >> >>
    //   /AcroForm << /Fields [4 0 R 5 0 R] /CO [4 0 R 5 0 R] >>
    val doc = PdfDocument.open(pdf)
    PdfScriptRunner(doc).use { runner ->
        runner.runCalculations()
        assertEquals("42", runner.formState.value("a"))
        assertEquals("", runner.formState.value("b"))   // today "42", the result of a's calculation
        assertEquals(1, runner.failures.size)
    }
}
```

The same with `b`'s script `while (true) {}` and `PdfScriptPolicy(budgetMillis = 200)` shows the stopped case.

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:678` `dispatch` (`:690` evaluates and ignores the outcome, `:691` returns `host.lastEventResult`).
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:63` `lastEventResult`, `:90` the only writer.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:635` `__kiteEvent`, `:656` the `finally` that reports the result.
- Users of the result: `PdfScriptRunner.kt:405` `keystrokeHere`, `:434` `commitOnThread`, `:539` `calculateAll`, `:560` `formatOnThread`.

#### Notes

Read from source, every step. No test runs a field script that fails to compile or is stopped (`PdfScriptRunnerTest`, `FormScriptTest`, `ChoiceScriptTest`, `WidgetScriptTest`). After an uncatchable stop, `g.event` also keeps pointing at the failed event, because the same `finally` restores it.

### KP-37

**`calculateNow()` inside a calculation starts a new calculation round, and the deadline never fires**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`this.calculateNow()` reaches `PdfScriptHost.action("calculateNow")`, which calls `runCalculations()`. On the script thread `onScriptThread` runs the block in place, so a calculate script that calls `calculateNow()` runs `calculateAll()` again from inside its own event. That round runs the same script, which calls `calculateNow()` again. Nothing stops the nesting until the native stack limit (8 MiB of a 16 MiB script thread) throws a RangeError in the innermost script, which the runner records and goes on from.

With one such field each commit nests rounds until the stack limit, and the innermost run fails with a RangeError that the runner records. With two such fields every level runs both, and each of them nests again, so the work doubles with each level of depth and the call does not return.

The per-event deadline does not stop it. `PdfScriptRunner.evaluate` sets `eventStartedAt = now()` on every nested evaluation and never restores it, so the outer event's clock restarts each time a nested event starts, and `deadlinePassed()` compares against the newest start. The default policy sets no instruction budget and no document budget.

PDFium ignores a calculation request while a calculation runs: `CPDFSDK_InteractiveForm::OnCalculate` returns at once when `busy_` is set, and `CJS_Document::calculateNow` calls `OnCalculate`. So a form that calls `this.calculateNow()` from a calculation script works in Chrome.

#### What should happen

A `calculateNow()` that comes while a calculation round runs does nothing, as in PDFium. A nested evaluation does not reset the deadline of the event around it.

#### How to reproduce

A `kitepdf-javascript` test on the JVM (`test@jvm`). Fields `a` and `b`, each with the calculate script `this.calculateNow(); event.value = 1;`, and `/CO [a b]`:

```kotlin
@Test
fun calculate_now_inside_a_calculation_does_not_recurse() {
    val doc = PdfDocument.open(pdf)
    val runner = PdfScriptRunner(doc, policy = PdfScriptPolicy(budgetMillis = 1_000))
    val worker = thread(isDaemon = true) { runner.runCalculations() }
    worker.join(30_000)
    val stillRunning = worker.isAlive
    // close() waits for the script thread on the JVM, so it gets a thread of its own here.
    thread(isDaemon = true) { runner.close() }
    assertFalse(stillRunning, "the calculation round is still nesting")   // fails today
    assertTrue(runner.failures.isEmpty())
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:318` `"calculateNow"`, `:328` `calculateNow`.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:228` binds it to `runCalculations()`, `:534` `runCalculations`, `:539` `calculateAll` (no guard), `:698` `eventStartedAt = now()` on every nested call, `:718` `deadlinePassed`.
- PDFium `fpdfsdk/cpdfsdk_interactiveform.cpp` `OnCalculate` (`if (busy_) return;`), `fxjs/cjs_document.cpp` `calculateNow`.

#### Notes

Read from source, and the PDFium sources fetched from pdfium.googlesource.com (not committed). The exponential case is inferred from the code path, not run. I did not work out whether `close()` ends it: closing makes each later poll stop the script that runs, which ends one nested script per 256 checks, while the rounds outside it go on with their remaining fields.

### KP-38

**A document can set any number of timers, and one pump runs every due timer without a limit**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #241, #368

#### What happens

`PdfScriptHost.setTimer` adds each `app.setInterval` and `app.setTimeOut` to a map with no cap. `pumpTimers` takes every due timer and evaluates each in turn. The deadline is per evaluation, and a tiny timer script never comes near it, so nothing bounds the pump as a whole. A period of 0 makes a repeating timer due at every pump. So an open action such as

```js
for (var i = 0; i < 100000; i++) app.setInterval('1', 0);
```

which finishes inside its own five seconds, makes every later pump compile and run 100,000 scripts. In the glue alone, without the Kotlin and JNI or WebAssembly layers above it, 100,000 such scripts take 0.78 s on this machine (`qjs/timers.c`), and the viewer pumps once a frame while a timer waits.

- On JavaScript and Wasm the pump runs on the page's only thread. Where the runtime has no WebAssembly stack switching (KitePDF's docs: only Chrome 137 and Node 25 and newer have it), `evaluatePausing` is a plain `evaluate`, so the page gets no frame until the whole pump is over, and the pump starts again on the next frame. Where it has stack switching, each evaluation resumes through the event loop and the page can draw, but `QuickJsKiteJs.pausingCall` starts a new 16 ms slice for each call, so no single short script pauses, and the runner's other calls wait behind the pump for the `turns` mutex.
- On the JVM, Android and Kotlin/Native the script thread runs pumps back to back, so every keystroke, commit and tap script of that document waits behind them. `close()` waits for the pump in progress, since the evaluations in it never reach a poll that would see `closed`.

#### What should happen

A pump stops after a time slice and leaves the rest of the due timers for the next one, and a document has a bounded number of timers, so a document cannot take the thread from the host, as `pumpTimers`'s KDoc says: "Nothing runs on its own: a document cannot take the thread from the host."

#### How to reproduce

```kotlin
@Test
fun one_pump_has_a_limit(): TestResult = scriptTest {
    var now = 0L
    PdfScriptRunner(doc, clock = { now }).use { runner ->
        runner.run(PdfAction.JavaScript("for (var i = 0; i < 100000; i++) app.setInterval('1', 0)", PdfDictionary(emptyMap())))
        val start = TimeSource.Monotonic.markNow()
        runner.pumpTimers(now)
        assertTrue(start.elapsedNow() < 100.milliseconds)   // today it runs 100,000 scripts in this one call
    }
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:345` `setTimer`, `:364` `dueTimers`.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:659` `pumpTimers`, `:694` `evaluate`.
- KiteJS `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:174` `pausingCall`, which restarts the slice per call.

#### Notes

Read from source; the cost per script measured in C only. The Kotlin layers add to it, so the real pump is slower than the figure above.

### KP-39

**The name tree and revision walkers stop at depth 64 but keep no visited set, so a node that lists itself twice takes 2^64 steps**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #448

#### What happens

- `NameTreeWalker.walk` and `NumberTreeWalker.walk` stop at depth 64, but visit a kid each time a `/Kids` array lists it. `5 0 obj << /Kids [5 0 R 5 0 R] >>` makes 2 calls at depth 1, 4 at depth 2, and about 2^65 in all. At 10 million calls a second that is about 100,000 years.
- `NameTreeWalker` reads `/Names /EmbeddedFiles` for `PdfDocument.attachments`, and `/Names /JavaScript` for `documentJavaScripts`, which `PdfScriptRunner` reads when it starts.
- `RevisionChanges.walkField` and `walkPages` have the same shape. `PdfSignature.validate()` runs them on both revisions when the file changed after signing, so validating a crafted signed file never returns.
- The calling thread spins for good: an app that lists attachments on the UI thread stops responding, and a viewer with document scripts never starts them.
- Poppler reports "loop in NameTree" on the same file and goes on. MuPDF keeps a cycle list (`pdf-nametree.c`).

#### What should happen

Each walk keeps the object numbers it has entered and skips a kid it has seen. The depth cap stays as a backstop.

#### How to reproduce

`nametree-fanout.pdf`: `1 0 obj << /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles 5 0 R /JavaScript 5 0 R >> >>` and `5 0 obj << /Kids [5 0 R 5 0 R] >>`.

```kotlin
val doc = open("nametree-fanout.pdf")
val worker = thread { doc.attachments }
worker.join(10_000)
println("still running after 10 s: ${worker.isAlive}")   // true
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/NameTree.kt:32-58`, `:74-99` (both walks), `:38`, `:80` (the depth check, the only guard).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfAttachment.kt:47`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:623-626`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:161-209` (`walkField`, `walkPages`), reached from `PdfSignature.kt:81`.

#### Notes

Read from source. `RevisionChanges.reach` in the same file has a work budget (`MAX_REACH`), so it ends.

Measured on the JVM at `c4e7574` by the lead: on `nametree-fanout.pdf`, `attachments` and `documentJavaScripts` were still running after 15 s.

### KP-40

**A PDF keeps every page's decoded content and two copies of its text for the life of the document, so reading or searching a long file fills the heap**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #218, #224, #116

#### What happens

- `PdfPage.contentBytes` is a `lazy`: the decoded content of a page stays in the page once a render or a text pass parses it. The operation cache has a byte budget, but each miss goes through `contentBytes`, so the decoded bytes stay after the operations leave the cache.
- `PdfPage.structuredText` and `kiteTextContent` are `lazy` too, and hold the page's text twice. Each character keeps a boxed `Pair<Double, Double>` in `PdfTextSpan.charEdgePoints`, about 56 bytes plus a list slot on a 64-bit JVM, a `Double` edge in the display copy, and once a selection hit-tests, a `CharRef` in `flatChars`, about 28 bytes more.
- `PdfDocument.pages` lives as long as the document, so nothing releases these. `dropOperationCache`, `dropFontCache` and `dropDecodedImageCache` do not reach them.
- Two common paths fill them for every page:
  - `doc.search(...)` builds the text of every page it passes.
  - `KiteDocView` with the default `selectionEnabled = true` builds the text of each page the reader rests on (`prepareText`). So reading a file page by page keeps the text of every page read.
- At about 3,000 characters a page that is roughly 200 to 300 KB a page: 200 to 300 MB for a 1,000-page book after one search, plus the decoded content (tens of KB for a text page, megabytes for a map). A 256 MB Android heap does not hold it, and the JVM suites run on a 3 GB heap that never measures retained memory (CLAUDE.md).
- EPUB does not have the problem: its text lives in the live chapter, which the layout budget drops (#218). XPS keeps its text per page the same way as PDF (`XpsDocument.kt:141`).

#### What should happen

The decoded content and the text of a page live in document caches with a byte budget, as the operations, the fonts and the images already do, and a drop call frees them. `charEdgePoints` is a `DoubleArray`.

#### How to reproduce

Probe, `kitepdf-pdf`, `test@jvm`:

```kotlin
val builder = PdfBuilder()
repeat(1_000) { p ->
    builder.page { repeat(60) { line -> text(StandardFont.Helvetica, 9.0, 40.0, 750.0 - 12 * line, "Page $p line $line " + "lorem ipsum dolor ".repeat(4)) } }
}
val doc = PdfDocument.open(builder.build())
fun used(): Long { repeat(3) { System.gc(); Thread.sleep(200) }; val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }
val before = used()
doc.search("zzzz").count()
doc.dropOperationCache(); doc.dropFontCache(); doc.dropDecodedImageCache()
println("retained after one search: ${(used() - before) / 1_000_000} MB")
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPage.kt:219` (`contentBytes`), `:229-233` (`operations`), `:254` (`structuredText`), `:264` (`kiteTextContent`), `:278` (`readingItems`).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/StructuredText.kt:122`, `:474` (the boxed edge per character).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/KiteStructuredText.kt:87` (`flatChars`).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextSearch.kt:37`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1007-1011`, `KiteDocViewState.kt:1579`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:173` (pages for the life of the document), `:338`, `:417`, `:493` (the drop calls that miss these).

#### Notes

Read from source. The retention is certain; the sizes per character are estimates for a 64-bit JVM with compressed references, and the probe measures them.

Measured on the JVM at `c4e7574` by the lead (heap after a full GC, relative to the heap before `open`):

| File | Pages | After `open` | After `doc.search("e")` | After all three `drop...Cache()` calls |
| --- | --- | --- | --- | --- |
| `public-wikimedia-editing-arabic-wikipedia.pdf` (2 MB) | 28 | 0.2 MB | 48.9 MB | 16.7 MB (0.6 MB a page) |
| `public-joss-10131.pdf` (4.4 MB) | 7 | 0.03 MB | 26.4 MB | 8.6 MB (1.2 MB a page) |

So the part that no drop call frees is 0.6 to 1.2 MB a page on these files, above the estimate in the text: 600 MB to 1.2 GB for 1,000 such pages.

### KP-41

**An object stream's /N sizes an array before anything checks it**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Likely

#### What happens

- `PdfDocument.decodeObjectStream` reads `/N` and allocates `IntArray(n)` before it reads the first header pair (`PdfDocument.kt:913`, `:924`). `PdfRepair.objStreamMembers` allocates `ArrayList(n)` the same way (`PdfRepair.kt:142`, `:145`). Nothing bounds `n` by the size of the decoded header.
- `/N 2147483647` asks for 8 GB (`IntArray`) and 8 to 16 GB (an `ArrayList` of references, by pointer size).
- JVM and Android: the `OutOfMemoryError` comes at once and the `runCatching` in `resolve` and in repair catches it, so the members read as null and `open` fails with "Repair found no catalog". A value such as 300,000,000 (1.2 GB) is granted by a desktop heap, zeroed and dropped, and that happens again for every member that is resolved, because a failed resolve is not cached (`PdfDocument.kt:770`, a cached null is parsed again).
- Kotlin/Native ends the process when it cannot map an allocation ("Out of memory trying to allocate ... Aborting."), and Kotlin/Wasm traps on an array that large. Neither reaches the `runCatching`. This is the step not run here.
- MuPDF refuses a count above `PDF_MAX_OBJECT_NUMBER` (8,388,607) before it allocates (`pdf_load_obj_stm`, `pdf-xref.c`). Poppler prints "Too many objects in an object stream" for the file below and does not open it.

#### What should happen

`/N` is checked before any allocation: each header pair takes at least 4 bytes of the decoded stream, so `n <= decoded.size / 4`, and `n` stays under MuPDF's limit. The offsets grow as pairs are read.

#### How to reproduce

`make_objstm_huge_n.py` writes `objstm-huge-n.pdf` (catalog, page tree and page in an object stream with `/N 2147483647`) and, with an argument of 3, the control file `objstm-n3.pdf`, which poppler opens.

Probe, `kitepdf-pdf`, `test`, run with `-p jvm` and with `-p linuxX64` or `-p macosArm64`. It builds the same file without Flate, so it needs no file access:

```kotlin
class ObjectStreamCountTest {
    private fun file(n: Long): ByteArray {
        val members = listOf(
            "<< /Type /Catalog /Pages 3 0 R >>",
            "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 612 792] >>",
        )
        val header = StringBuilder(); val body = StringBuilder()
        members.forEachIndexed { i, m -> header.append("${i + 2} ${body.length} "); body.append(m).append('\n') }
        val stm = "$header\n$body"
        val out = ByteArrayBuilder()
        fun w(s: String) = out.append(s.encodeToByteArray())
        w("%PDF-1.5\n")
        val o1 = out.size()
        w("1 0 obj\n<< /Type /ObjStm /N $n /First ${header.length + 1} /Length ${stm.length} >>\nstream\n$stm\nendstream\nendobj\n")
        val xref = out.size()
        val rows = ByteArrayBuilder()
        fun row(t: Int, a: Int, b: Int) { rows.append(t.toByte()); rows.appendU32BE(a); rows.appendU16BE(b) }
        row(0, 0, 65535); row(1, o1, 0); for (i in members.indices) row(2, 1, i); row(1, xref, 0)
        val r = rows.toByteArray()
        w("5 0 obj\n<< /Type /XRef /Size 6 /W [1 4 2] /Root 2 0 R /Length ${r.size} >>\nstream\n")
        out.append(r)
        w("\nendstream\nendobj\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    @Test fun control_opens() { assertEquals(1, PdfDocument.open(file(3)).pageCount) }

    @Test fun a_huge_count_is_refused_without_allocating() {
        // JVM: returns null after two caught OutOfMemoryErrors. Native: expected to end the test process.
        println(PdfDocument.openOrNull(file(2_147_483_647L)))
    }
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:913` (`/N`), `:924` (`IntArray(n)`), `:936` (`HashMap(n)`), `:770` (a failed resolve is tried again).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/PdfRepair.kt:142` to `:145`.

#### Notes

The allocation is read from source. The Native abort and the Wasm trap are the runtimes' behaviour for a failed huge allocation, not run here.

### KP-42

**A book's script can build a tree deeper than the parser allows, and the layout and the script DOM walk it recursively**

- Repo: KitePDF
- Labels: area:epub, area:javascript, kind:bug
- Severity: high
- Confidence: Likely
- Related: #450

#### What happens

- #450 capped the depth of a chapter's tree at 32 levels, in `HtmlParser`, because the layout recurses once for each level and a Kotlin/Native debug build takes about 10 KB of stack for each level (CLAUDE.md). A secondary thread on Apple platforms has 512 KB.
- A tree that a book's scripts change does not go through that cap. `appendChild` in a loop, or `innerHTML` in an XHTML chapter (which `XmlReader.fragment` parses with no depth limit), make a tree of any depth.
- When the script returns, `commit` hands `ScriptDom.snapshot()` to the layout. `snapshot` copies the tree recursively (`copy`), and `innerHTML` registers its text nodes recursively first (`registerTexts`). `replaceChapterTree` then lays the chapter out on the script thread (16 MB of stack), and another document over the book, such as one after a font size change, lays it out on the raster dispatcher.
- The layout walks the tree recursively in `ParsedEpub.walkStyleSources`, `containsScript`, `Paginator.gatherUnits`, `collect` and `numberPaintOrder`. `gatherUnits` and `BoxBuilder` (`childAncestors`) also copy the list of ancestors at each level, so a depth of `d` holds `d * d / 2` references at once: 50 million for a depth of 10,000.
- On Apple platforms a stack overflow ends the process with no exception. On the JVM and Android, a `StackOverflowError` or `OutOfMemoryError` from the script thread passes through `EpubScriptRunner.call` and through the viewer's `scriptCall`, which catches `Exception` only, so it ends the coroutine of `KiteDocView` and the app.

#### What should happen

The tree that the layout reads keeps the parser's limit whoever made it: `snapshot` moves an element deeper than `HtmlParser.MAX_DEPTH` up, as `HtmlParser.parse` does, and the DOM's own walks (`registerTexts`, `copy`, `appendText`, `find`, `fixParents`, the XML serializer) do not use the call stack for each level.

#### How to reproduce

The layout part, in `kitepdf-epub`, `test@jvm/`:

```kotlin
@Test
fun a_scripted_tree_deeper_than_the_parser_allows_lays_out() {
    val doc = EpubDocument.open(EpubFixtures.epub("<p>x</p>"))
    val root = KiteXmlNode.Element("#root", emptyMap())
    val html = KiteXmlNode.Element("html", emptyMap()).also { it.parent = root; root.children.add(it) }
    var at = KiteXmlNode.Element("body", emptyMap()).also { it.parent = html; html.children.add(it) }
    repeat(20_000) { val d = KiteXmlNode.Element("div", emptyMap()); d.parent = at; at.children.add(d); at = d }
    at.children.add(KiteXmlNode.Text("deep"))
    var failure: Throwable? = null
    // The stack of a secondary thread on Apple platforms.
    val t = Thread(null, { try { doc.replaceChapterTree(0, root) } catch (e: Throwable) { failure = e } }, "probe", 512L * 1024)
    t.start(); t.join()
    assertNull(failure) // StackOverflowError or OutOfMemoryError expected today
}
```

The script part: an XHTML chapter whose script runs `var e = document.body; for (var i = 0; i < 20; i++) { e.innerHTML = '<div>'.repeat(10000) + '</div>'.repeat(10000); while (e.firstElementChild) e = e.firstElementChild; }` makes a tree 200,000 levels deep in linear time, through `EpubScriptRunner`.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/HtmlParser.kt:52`: the cap, applied by the parser only.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:1178-1234` (`snapshot`, `copy`), `:1167` (`registerTexts`), `:490` (`appendText`), `:875` (`fixParents`).
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:590-596`: `commit`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:318-322`: `replaceChapterTree`, which lays out on the caller's thread.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Paginator.kt:259-260` and `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:137`: the lists of ancestors.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ScriptCall.kt:13-19`: `scriptCall` catches `Exception` only.

#### Notes

Read from source. Not run: the depth at which each platform overflows is not measured, which is why this is Likely.

Found by two lanes. Neither could run a Kotlin/Native or Apple target, so the crash there stays Likely.

### KP-43

**The documented loss of accents in standard fonts comes from a missing /Encoding, and the docs blame the fonts**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`docs/writing.md` says that "a standard font has only the characters of Latin-1 (U+0000 to U+00FF)", that the builder writes a question mark for each other character, and warns that a character such as é can show as another one, since MuPDF and Poppler show café as cafØ, so text with accents needs an embedded font. The fonts are not the cause. The standard 14 Latin fonts carry every character of WinAnsiEncoding, and `AnnotationAppearance` and `FieldAppearance` already write `/Encoding /WinAnsiEncoding` for them.

`PdfBuilder` and `PdfEditor.stampPage` write the font dictionary of a standard font with `/Type`, `/Subtype` and `/BaseFont` only. A non-symbolic standard 14 font with no `/Encoding` uses its built-in encoding, which for Helvetica, Times and Courier is StandardEncoding (ISO 32000-1, 9.6.6.1 and Annex D.2). KitePDF's own reader follows that rule (`PdfFont.defaultEncodingFor`).

The text, though, is written as Latin-1 bytes: `PdfText.encodeContentString` writes the code of each char up to 0xFF and `?` for the rest. In StandardEncoding most of the Latin-1 upper half means something else, or nothing:

| Input | Byte | StandardEncoding | What a reader draws |
| --- | --- | --- | --- |
| `é` | 0xE9 | Oslash | `Ø` |
| `ñ` | 0xF1 | ae | `æ` |
| `©` | 0xA9 | quotesingle | `'` |
| `®` | 0xAE | fi | `ﬁ` |
| `ü`, `ä`, `ö`, `ß`, `É`, `°` | | none | nothing |
| `'` (U+0027) | 0x27 | quoteright | `’` |

Characters above 0xFF become `?`, even the ones WinAnsiEncoding has: `’ ‘ “ ” • … € ™ Œ œ Š š Ž ž Ÿ ƒ † ‡ ‰` and the en and em dashes (U+2013, U+2014). Curly quotes and dashes are in almost every real sentence.

`StandardFont.stringWidth` measures each char through `Encodings.winAnsiEncoding`, a third mapping, so a width computed for centring does not match what is drawn.

#### What should happen

- The font dictionaries that `PdfBuilder` and `stampPage` write carry `/Encoding /WinAnsiEncoding` (except Symbol and ZapfDingbats, which keep their built-in encodings).
- `encodeContentString` maps Unicode to WinAnsi bytes through the reverse of `Encodings.winAnsiEncoding`, so `’` becomes 0x92 and `€` becomes 0x80. Only a char that WinAnsi lacks becomes `?` (or the call fails, or the KDoc points to `EmbeddedFont`; whichever is chosen, it is documented).
- `stringWidth` uses the same mapping as the bytes.

#### How to reproduce

Measured on the JVM at `c4e7574`:

```kotlin
val pdf = PdfBuilder().page(width = 300.0, height = 100.0) {
    text(StandardFont.Helvetica, 14.0, 10.0, 40.0, "café Müller © ’q’ €5")
}.build(compress = false)
PdfDocument.open(pdf).pages[0].extractText()   // "cafØ Müller ' ?q? ?5"
```

The page holds `<636166E9204DFC6C6C657220A9203F713F203F35> Tj` and the font `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`. Poppler's `pdftotext` reads it as `cafØ Mller ' ?q? ?5`; the `ü` draws nothing. The same bytes with `/Encoding /WinAnsiEncoding` read `café Müller ©`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:188-194`: the standard font dictionary.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:262-271`: the same dictionary for `stampPage`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfText.kt:41-42`: `encodeContentString`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/StandardFontMetrics.kt:19-28`: `stringWidth`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:506`: the reader's default, which is right.
- `docs/writing.md:105-108`: the documented limit and the warning.

#### Notes

Measured on the JVM, and checked with Poppler 24.02. `AnnotationAppearance` and `FieldAppearance` already write `/WinAnsiEncoding`, so only the builder and the stamp path miss it. No test writes a non-ASCII char through `PdfBuilder` with a standard font. The docs describe the symptom (`docs/writing.md:105-108`); this finding is about its cause, which is a missing dictionary entry rather than a limit of the fonts. Once fixed, the warning and the "only Latin-1" sentence of the docs change too.

### KP-44

**Search and text extraction keep ligature characters, so "find" does not match a page that draws "ﬁnd"**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

A simple font without a ToUnicode CMap gets its text from glyph names through the Adobe Glyph List. The names `fi`, `fl`, `ff`, `ffi` and `ffl` map to the presentation forms U+FB01, U+FB02, U+FB00, U+FB03 and U+FB04 (`GlyphList.kt`), and StandardEncoding puts `fi` and `fl` at 0xAE and 0xAF. Nothing between the font and the search folds them back, so:

- `extractText()`, `structuredText` and `textContent()` return `ﬁnd`, not `find`.
- `search("find")`, `search("first")` and `search("flow")` find nothing on a page that shows those words. `KiteStructuredText.search` compares code units with `regionMatches` and no normalization of either side.
- The viewer's search, selection copy and the JavaScript `getPageNthWord` read the same text.

pdfTeX writes no ToUnicode CMap unless `\pdfgentounicode=1` is set, so its Type 1 fonts reach the text layer only through the glyph names `fi`, `fl`, `ff`, `ffi` and `ffl`. None of the 49 PDFs in `corpus/pdf` triggers this: each one carries ToUnicode CMaps, and the corpus has no pdfTeX file without them. The same gap covers other compatibility characters: Arabic presentation forms (U+FB50 to U+FEFF) from glyph names, full-width forms, and decomposed accents (`e` + U+0301) against a precomposed needle.

MuPDF expands ligatures in structured text unless `preserve-ligatures` is asked for, Poppler's `pdftotext` writes `find the first flow` for the page below, and pdf.js normalizes text for search. KitePDF finds nothing.

#### What should happen

- Search matches across compatibility forms: fold both the page text and the needle with NFKC (or at least expand U+FB00 to U+FB06), keeping a map from folded positions back to the original chars so hit quads stay right. KitePDF already has `Normalizer.kt` in `kitepdf-epub`; it would need to move to the core.
- Decide whether `extractText()` and `structuredText` expand ligatures by default, as MuPDF does, and document it either way.
- Optional, as pdf.js offers it: a diacritic-insensitive search mode.

#### How to reproduce

Measured on the JVM at `c4e7574`:

```kotlin
val pdf = TestPdf.onePage(
    content = "BT /F1 12 Tf 10 100 Td (\\256nd the \\256rst \\257ow) Tj ET",
    resources = "/Font << /F1 5 0 R >>",
    extra = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >>"),
)
val page = PdfDocument.open(pdf).pages[0]
page.extractText()      // "ﬁnd the ﬁrst ﬂow" (starts with U+FB01)
page.search("find")     // 0 hits
page.search("first")    // 0 hits
page.search("ﬁnd") // 1 hit
```

`pdftotext` (Poppler 24.02) on the same file writes `find the first flow`.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/KiteStructuredText.kt:41-79`: `search`, raw `regionMatches`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:320-331`: glyph name to Unicode.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/GlyphList.kt:317`: `fi=FB01`, `fl=FB02` and the rest.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextSearch.kt`: the PDF entry points and their KDoc.

#### Notes

Measured on the JVM. EPUB text comes from the markup, so this is mostly a PDF problem, but the fix belongs in the shared walker.

### KP-45

**In Paged, Spread and SinglePage layouts every page slot makes its own rasterizer, so a page that comes back misses the bitmap cache**

- Repo: KitePDF
- Labels: area:viewer, kind:perf
- Severity: medium
- Confidence: Confirmed
- Related: #437, #421, #382

#### What happens

- The page-bitmap cache key holds the rasterizer's text measurer as `fontEnvironment` (#421), compared by identity, since `TextMeasurer` has no `equals`.
- `rememberKitePageRasterizer` remembers a new `TextMeasurer` through `rememberTextMeasurer()` for each composition that calls it.
- The Continuous strip makes one rasterizer and hands it to every slot, and its comment says why: "the bitmap cache key holds the rasterizer's text measurer, so a slot finds what another slot or the strip drew" (#437).
- Paged (`PageBox`), Spread (`SpreadBox`) and SinglePage pass no shared rasterizer, so `KitePageRaster` remembers one per slot. A pager disposes a page once it is more than `offscreenPages` away, and its remembered state goes with it. When the reader comes back, the slot composes afresh with a new text measurer, the key misses, and the page rasterizes again behind a placeholder and a fade.
- So in the layouts that `pagedFor` and `forDocument` give most documents, the 96 MB LRU never serves a page turn back. It fills with entries that no later key can hit, `onPageRendered` fires again for the same page and size (its KDoc says "once per page and bitmap size" and "Cache hits from the page-bitmap LRU do not re-fire it"), and each slot's glyph mask cache (#382) starts empty, so glyphs are filled again on every page.
- No test turns back past the composed window in a pager: `PageIdentitySceneTest` and `ContinuousPrefetchSceneTest` use the strip.

#### What should happen

Every layout shares one rasterizer for the life of the layout, as the strip does, so a page that comes back is a cache hit and its glyph masks are kept.

#### How to reproduce

Probe for `kitepdf-compose-viewer`, `test@jvm`:

```kotlin
@Test
fun paging_back_past_the_window_is_a_cache_hit() {
    val doc = PdfDocument.open(PdfBuilder().apply { repeat(6) { page(width = 200.0, height = 200.0) { setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 200.0, 200.0); fill() } } }.build())
    val renders = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    val state = KiteDocViewState(doc)
    val (scene, driver) = drivenScene(200, 200, queued = false) {
        KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(offscreenPages = 0),
            onPageRendered = { i, _ -> renders.merge(i, 1, Int::plus) })
    }
    scene.use {
        driver.pumpUntilState { renders[0] == 1 }
        driver.runOnUi { state.scrollToPage(3) }
        driver.pumpUntilState { renders[3] == 1 }
        driver.runOnUi { state.scrollToPage(0) }
        driver.pumpFrames(60)
        assertEquals(1, renders[0], "page 0 rendered again: the cache missed") // fails per source: 2
    }
}
```

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1369-1374`: a slot without a shared rasterizer remembers its own.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1330-1333` (`PageBox`) and `:2318-2321` (`SpreadBox`): no rasterizer passed; `:795-801` and `:978`: the strip shares one.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageRasterizer.kt:251`: `fontEnvironment = textMeasurer` in the key; `:498-505`: a new measurer per composition; `:90`: the glyph mask cache per rasterizer.

#### Notes

Read from source, and measured on the JVM at `c4e7574` with the probe above: after `scrollToPage(3)` and back to 0 in `Paged(offscreenPages = 0)`, `onPageRendered` had fired twice for page 0 (`{0=2, 3=1}`).

### KP-46

**Page Down, Space and the arrow keys skip the rest of a page that is taller than the view**

- Repo: KitePDF
- Labels: area:viewer, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #411, #505

#### What happens

- `keyAction` maps Page Down, Space and the arrow keys to `turn`, which calls `nextPage()` or `previousPage()` outside a spread.
- In a Continuous strip, `currentPage` is the slot nearest the viewport's centre, and `nextPage()` scrolls the next page's top to the top of the view. The default layout is `Continuous(fit = WIDTH)`, so on a desktop or web window that is wider than about 0.7 of the page's height (1280 x 800 with an A4 page gives a 1810 px slot) each page is taller than the view. Page Down at the top of a page jumps past more than half of it, and the reader never sees that part.
- In `Paged(fit = WIDTH)`, the layout of a `scrolled-doc` book, Page Down at the top of a long chapter goes to the next chapter. The wheel was fixed for this case (#505): it scrolls down the chapter first and turns only at its end. The keys were not.
- `KeyboardAndWheelSceneTest` uses 200 x 300 pages in a 200 x 300 view, which never overflow.

#### What should happen

As in a browser or a desktop PDF reader: Page Down and Space scroll by about a screen and the arrows by a step while the page overflows the view, and they turn the page only at its edge, as the wheel does.

#### How to reproduce

Probe for `kitepdf-compose-viewer`, `test@jvm`, with `KeyboardAndWheelSceneTest`'s `pagesPdf`, `click` and `key` helpers:

```kotlin
@Test
fun page_down_does_not_skip_the_rest_of_a_tall_page() {
    val state = KiteDocViewState(pagesPdf(4)) // 200 x 300 pt pages
    ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
        KiteDocView(state = state, modifier = Modifier.fillMaxSize()) // Continuous, fit WIDTH: 400 x 600 slots
    }.use { scene ->
        val driver = SceneTestDriver(scene)
        driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
        click(scene); driver.pumpFrames(30)
        key(scene, Key.PageDown); driver.pumpFrames(60)
        // Expected: page 0, about 300 px in. Per source: page 1 at offset 0, the lower half of page 0 skipped.
        assertEquals(KiteLocation(0, 0), state.currentScrollPosition.location)
    }
}
```

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:531-569`: `keyAction`; `:408-415`: `turn`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:2382-2385`: `nextPage`; `:2732-2748`: the centre slot; `:2773`: `animateScrollToItem`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:464-471`: the wheel, which pans first.

#### Notes

Read from source, and measured on the JVM at `c4e7574`: in a 400 by 300 view with the default `Continuous` layout and 200 by 300 pt pages (600 px slots), one Page Down after a click moved the position from `0:0` at offset 0 to `0:1` at offset 0, so the lower half of page 0 never showed.

### KP-47

**`userScrollEnabled = false` does not stop a drag past the edge of a zoomed page from turning it**

- Repo: KitePDF
- Labels: area:viewer, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #410

#### What happens

- The KDoc of `userScrollEnabled` says it turns off swiping, the wheel that turns pages, and the page keys, so that a host can "drive paging exclusively through [KiteDocViewState] (nav buttons)".
- Paged and Spread hand their `PageTurn` to `kiteTransformGestures` whatever `userScrollEnabled` says. Once the reader zooms in, a drag that goes 64 dp past the edge of the page calls `state.turn(...)`, so the page turns in a view the host locked (a presentation, a quiz, a guided tour).
- The flag also takes too much: `kiteWheelPaging` returns at once when it is false, so the wheel no longer pans a zoomed page or scrolls down a fit-width chapter, while the finger still does. The wheel's pan is not a turn of the layout.

#### What should happen

`userScrollEnabled = false` stops every way the reader turns pages, the edge turn included, and leaves panning a page alone for both the finger and the wheel.

#### How to reproduce

Probe for `kitepdf-compose-viewer`, `test@jvm`, with `ScrolledFlowSceneTest`'s touch `drag` helper:

```kotlin
@Test
fun a_locked_pager_does_not_turn_at_the_edge_of_a_zoomed_page() {
    val state = KiteDocViewState(PdfDocument.open(PdfBuilder().apply { repeat(3) { page(width = 200.0, height = 300.0) {} } }.build()))
    ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
        KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), userScrollEnabled = false)
    }.use { scene ->
        val driver = SceneTestDriver(scene)
        driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
        onTestUiThread { state.setZoom(2f) }
        driver.pumpFrames(5)
        drag(scene, Offset(195f, 150f), Offset(5f, 150f))
        driver.pumpFrames(60)
        assertEquals(0, state.currentPage) // fails per source: 1
    }
}
```

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:165-168`: the KDoc.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1121-1126` and `:1146-1148` (Paged), `:2181-2186` and `:2206-2208` (Spread): the turn goes to the gestures unconditionally.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteGestures.kt:327-332`: the edge turn; `:441-442`: the wheel modifier is dropped, pan and all.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:455`.

#### Notes

Read from source, and measured on the JVM at `c4e7574`: in `Paged()` with `userScrollEnabled = false`, after `setZoom(2f)` and three drags from x = 195 to x = 5, `currentPage` was 1 (and the zoom read 1 again, the `resetZoomOnPageChange` that follows a turn).

### KP-48

**Media on a page the reader turned away from keeps playing, and an autoplay element on an unseen page starts by itself**

- Repo: KitePDF
- Labels: area:epub, area:viewer, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #481, #31

#### What happens

- `KiteMediaOverlay`'s KDoc says "The player is closed when this leaves the composition, so a page that scrolls away stops." A Continuous strip disposes a page soon after it leaves the view, but Paged and Spread keep `offscreenPages` pages composed on each side, 1 by default, and the viewer composes the page overlay for each of them.
- So a video or an audio element the reader started, and unmuted, plays on with sound after the reader turns the page, until the page is more than `offscreenPages` turns away. `Paged` is what `forDocument` gives an EPUB.
- `LaunchedEffect(media)` starts an `autoplay` element as soon as its page composes. The page after the current one composes ahead, so its element plays, muted, before the reader sees it. A clip that does not loop has played through when the reader arrives. When it starts, `KiteMediaSession.started` pauses every other player of the session. When the reader starts another element first, that pauses the autoplay element, which then shows paused when its page comes into view, and autoplay never happens.
- `MediaSessionSceneTest` scrolls to the last page of the book, far past the window, so a one-page turn is not covered.

#### What should happen

An element plays only while its page is the one the reader sees: a page that stops being current pauses its media, and autoplay waits until the page is current. The page overlay scope needs to say whether its page is on screen, or the viewer composes the overlay for the current page or spread only.

#### How to reproduce

Probe for `kitepdf-media`, `test@jvm`, with `MediaSessionSceneTest`'s `book`, `fixture`, `newPlayer` and `players`: a book whose page 0 holds `<video src="clip.mp4" controls>` and whose page 1 holds `<video src="clip.mp4" autoplay>`, shown in `KiteDocLayout.Paged()` with `pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer, session = session) }`.

1. Without any tap, wait: per source a player for page 1 is made and becomes active (`players.size == 1`, `isActive`) while page 0 is on screen.
2. Tap Play on page 0, then `state.scrollTo(KiteLocation(0, 1), animate = false)`: per source page 0's player is still `isActive` after the turn.

#### Where

- `kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaOverlay.kt:170-173`: the KDoc; `:236-242`: autoplay on composition; `:244-253`: the player closes only on dispose.
- `kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteMediaSession.kt:57-67`: `started` pauses the others.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1056`: an overlay for every composed page; `:1175`, `:1186`, `:2224`, `:2232`: `beyondViewportPageCount`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:58-63`: `offscreenPages` defaults to 1.

#### Notes

Read from source.

### KP-49

**The script effects follow the first state shown, so a new state for the same document loses page scripts, chapter scripts and `:target`**

- Repo: KitePDF
- Labels: area:viewer, area:javascript, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #346, #366, #550

#### What happens

- #346 made a new `KiteDocViewState` under the same `KiteDocView` a supported case: the layout is keyed on the state.
- The two effects that follow the reader for the document's scripts are keyed on `scripts` and `state.document`, not on `state`: `LaunchedEffect(scripts, state.document)` for a PDF and `LaunchedEffect(epubScripts, state.document)` for a book. Their blocks capture the `state` of the composition that started them.
- When a host gives the view a new state for the same document (a reset, or reopening at a bookmark by building `KiteDocViewState(doc, bookmark)`), neither effect restarts. `runPageScripts` and `followChapters` go on watching the old state, whose adapter is gone and whose location no longer moves.
- From then on: a PDF's page open and close scripts never run (`pageOpened`, `pageClosed`); a book's scripted chapters reached later never run (`chapterOpened`); a fragment the reader goes to never reaches `setFragment`, so `:target` stops matching, with or without scripts (#550); and a script that changes `location` navigates the old state through `onNavigate`, so the move is lost.
- `PositionOwnershipSceneTest` swaps the state together with a new document, which restarts both effects.

#### What should happen

Both effects restart for a new state: key them on `state` as well, as the other effects of the view are.

#### How to reproduce

Probe for `kitepdf-compose-viewer`, `test@jvm`:

```kotlin
@Test
fun page_scripts_follow_a_new_state_of_the_same_document() {
    val doc = PdfDocument.open(PdfBuilder().apply { repeat(4) { page(width = 200.0, height = 200.0) {} } }.build())
    val opened = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val handler = object : PdfScriptHandler {
        override val formState = PdfFormState(doc)
        override suspend fun pageOpened(pageIndex: Int) { opened += pageIndex }
    }
    var supplied by mutableStateOf(KiteDocViewState(doc))
    val (scene, driver) = drivenScene(200, 200, queued = false) {
        KiteDocView(state = supplied, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), scripts = handler)
    }
    scene.use {
        driver.pumpUntilState { 0 in opened }
        val next = KiteDocViewState(doc)
        supplied = next
        driver.pumpUntilState { next.adapter != null }
        driver.runOnUi { next.scrollToPage(2) }
        driver.pumpUntilState { 2 in opened } // times out per source
    }
}
```

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:345-364`: `LaunchedEffect(scripts, state.document)` and `state.runPageScripts`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:382-397`: `LaunchedEffect(epubScripts, state.document)`, `followChapters` and `onNavigate`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:470`: the layout is keyed on the state.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:871-897` and `:994-1021`: what the stale effects watch.

#### Notes

Read from source. The stale effects also keep the old state, and its 96 MB bitmap cache, alive until `scripts` or the document changes.

### KP-50

**A Flate stream that is cut short, or lacks its Adler-32 trailer, decodes to nothing**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `Zlib.decode` gives the pure-Kotlin inflater the bytes between the 2-byte header and the last 4 bytes, on the assumption that those 4 are the Adler-32 (`Zlib.kt:66`). `Inflate.decode` writes into a builder that it drops when `inflateTo` throws (`Inflate.kt:32` to `:34`), so any `InflateException` loses every byte decoded before it.
- A stream with no Adler-32 trailer: the inflater never sees the last 4 bytes of the deflate data and throws. On the JVM the zlib fast path inflates the whole stream, then waits for the trailer (`n == 0` and not finished) and returns null (`PlatformFlate.jvm.kt:169` to `:173`), so the JVM ends in the same throw.
- A stream cut in the middle (a partial download, or a writer that stopped before it finished the stream): the inflater throws at the cut.
- The predictor pass is all or nothing too: `Predictors.apply` throws when the data is not a whole number of rows (`Predictors.kt:48` to `:51`), on a PNG row filter byte above 4 (`:77`), and on a `/Predictor` other than 1, 2 or 10 to 15 (`:42` to `:44`).
- The callers drop the whole stream: page content reads as empty (`PageContents.decodeOrNull`), an image becomes a placeholder, an xref stream sends the file to repair. The same throw-on-error shape holds for LZW, RunLength and ASCII85.
- MuPDF warns "premature end of data in flate filter" and keeps what zlib inflated (`next_flated`, `filter-flate.c`), decodes a short last predictor row, and reads an unknown row filter or predictor as none with a warning (`filter-predict.c`). Its content interpreter reads the stream as it inflates, so the operators before a corrupt block still draw.

#### What should happen

A damaged Flate stream (ISO 32000-1, 7.4.4) gives the bytes before the damage, as MuPDF does:

- the inflater stops at the end of its final block wherever the trailer is, and the caller of `Zlib.decode` gets the output so far when the data ends early or a block is corrupt;
- the predictor decodes a short last row and treats an unknown row filter or predictor as none;
- the bomb cap still throws.

#### How to reproduce

`make_flate_variants.py` writes three one-page PDFs whose content is 50 `Tj` lines in one Flate stream: complete, without its last 4 bytes, and cut at 40 %. `pdftotext` finds 50, 50 and 7 lines.

Probe, `kitepdf-pdf`, `test@jvm`:

```kotlin
class DamagedFlateTest {
    private fun text(name: String) = PdfDocument.open(
        java.io.File("audit-repro/L2/repro/$name").readBytes()
    ).pages[0].extractText().lines().count { "Line" in it }

    @Test fun counts() {
        println(listOf("flate-ok.pdf", "flate-no-adler.pdf", "flate-truncated.pdf").map(::text))
        // poppler: [50, 50, 7]; KitePDF by the trace above: [50, 0, 0]
    }
}
```

And at the codec, in `kitepdf-core`, `test`:

```kotlin
val text = "BT /F1 12 Tf 72 700 Td (Hello) Tj ET".encodeToByteArray()
val full = Zlib.encode(text)
Zlib.decode(full.copyOf(full.size - 4), verifyChecksum = false)   // InflateException today; zlib gives the text
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/compression/Zlib.kt:62` to `:66`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/compression/Inflate.kt:26` to `:35`, `:98` to `:109`, `:230` to `:237`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/compression/PlatformFlate.jvm.kt:169` to `:173` (and the Android copy).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/filters/Predictors.kt:42` to `:51`, `:77`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PageContents.kt:56` to `:57`.

#### Notes

Ran the generator and poppler; the KitePDF side is read from source. No test feeds a truncated or trailer-less stream to `Zlib` (`InflateTest` covers only whole streams and a wrong checksum).

Measured on the JVM at `c4e7574` by the lead: `extractText()` gives nothing for `flate-truncated.pdf` and `flate-no-adler.pdf`, where `pdftotext` gives 8 and 51 lines; `flate-ok.pdf` reads in both.

### KP-51

**Numbers that Acrobat, MuPDF and pdf.js read as one number split into two operands or into an operator**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `Lexer.readNumber` stops at the first byte that is not a digit or the first dot, and a sign or dot with no digit becomes a keyword (`Lexer.kt:205` to `:225`).
- `0.000000000000-5684342` lexes as the real 0.0 and the integer -5684342: two operands. MuPDF's lexer notes that "Some PDFs (particularly those generated by google docs) apparently have numbers like 0.000000000000-5684342 in them" (bug 703248, `pdf-lex.c`).
- `--5` lexes as the keyword `-` and then -5. A lone `-`, `+` or `.` is a keyword.
- In a content stream a keyword is an operator. `ContentStreamParser` makes an `Operation("-")` that takes every operand before it and clears the stack (`ContentStream.kt:154` to `:155`). The real operator gets only what follows, and `PageRenderer` reads operands from the front (`num(a, 0)`), so `cm`, `m`, `re` and `Td` take the wrong values: geometry 5.7 million units off the page, or a zero-height rectangle.
- Inside an object, `Parser.readObject` throws "Unexpected keyword '-'" (`Parser.kt:74`), and the whole object resolves to null.
- MuPDF skips extra minus signs at the start, stops at a minus inside a number but swallows its digits, and reads a lone sign as 0 (`lex_number`, `fast_atoi`). pdf.js ignores a minus in the middle and a double minus "to match Adobe's behavior", and reads a lone sign before white space, `(`, `<` or the end as 0 (`Lexer.getNumber`, with the fixtures issue9252, issue15604, bug1753983, bug1953099).

| Content | KitePDF | MuPDF |
|---|---|---|
| `0.000000000000-5684342 100 m` | `m` [0.0, -5684342, 100] | `m` [0.0, 100] |
| `100 --100 200 300 re` | `-` [100], `re` [-100, 200, 300] | `re` [100, -100, 200, 300] |
| `72 - Td` | `-` [72], `Td` [] | `Td` [72, 0] |
| `/Rect [0 0 --5 10]` in an object | the object is lost | [0 0 -5 10] |

#### What should happen

The lexer reads these forms as one number, as the references do: extra leading minus signs are dropped, a minus after the first digit ends the value and its digits are skipped, and a sign or dot with no digit is 0 (ISO 32000-1, 7.3.3 defines the valid forms; the others are salvage).

#### How to reproduce

Probe, `kitepdf-pdf`, `test`:

```kotlin
class MalformedNumberTest {
    @Test fun one_number_each() {
        val ops = ContentStreamParser.parse("0.000000000000-5684342 100 m 100 --100 200 300 re 72 - Td".encodeToByteArray())
        println(ops.map { it.operator to it.operands })
        // today: [(m, [0.0, -5684342, 100]), (-, [100]), (re, [-100, 200, 300]), (-, [72]), (Td, [])]
    }
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/Lexer.kt:188` to `:243` (`readNumber`).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/content/ContentStream.kt:139` to `:156`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/Parser.kt:63` to `:75`.

#### Notes

Read from source, and from MuPDF's `pdf-lex.c` and pdf.js's `parser.js`. Acrobat's behaviour is as pdf.js's comments describe it, not observed here. PDFium reads a content word up to white space or a delimiter and calls it a number when every byte is a digit, sign or dot (`CPDF_StreamParser::ParseNextElement`, `cpdf_streamparser.cpp`), so it also gives one operand for each of these. `LexerTest` covers only well-formed numbers.

### KP-52

**Only 32 cross-reference sections are read, so a file with more incremental updates silently loses its oldest objects**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `parseWithPrevChain` stops after `MAX_PREV_HOPS = 32` sections (`PdfDocument.kt:1209`, `:1246`), although the set of visited offsets beside it already stops a `/Prev` loop.
- Each incremental update adds a section: each incremental save, each signature, each DSS or timestamp update. In a file with 33 or more sections the oldest ones are never read, and the oldest one holds the original body.
- When the newest 32 sections hold the catalog and the page tree root, the structure probe passes and repair does not run. Pages, content, fonts and images defined only in the older sections resolve to null. A page whose dictionary is lost becomes a blank page (the #585 path). Nothing is logged.
- MuPDF follows `/Prev` until an offset repeats (`pdf_read_xref_sections`, `pdf-xref.c`).

#### What should happen

The chain is followed until an offset repeats (ISO 32000-1, 7.5.6). A bound, if one is kept as a backstop, is far above any real file and logs when it is reached.

#### How to reproduce

`make_many_updates.py` writes `many-updates.pdf`: an original revision with the page, its content and its font, 33 updates that each rewrite an `/Info`-like object, and a newest update that rewrites the catalog and the page tree root (35 sections). `pdftotext` prints "Text from the original revision".

Probe, `kitepdf-pdf`, `test@jvm`:

```kotlin
val doc = PdfDocument.open(java.io.File("audit-repro/L2/repro/many-updates.pdf").readBytes())
println(doc.pageCount to doc.pages[0].extractText())   // today (1, ""): page 3 is outside the 32 sections read
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:1202` to `:1243` (`parseWithPrevChain`), `:1246` (`MAX_PREV_HOPS`).

#### Notes

Ran the generator and poppler; the KitePDF side is read from source. `revisionEndingAt` (signature revisions) goes through the same function.

Measured on the JVM at `c4e7574` by the lead: `extractText()` of `many-updates.pdf` is empty, where `pdftotext` reads the page.

### KP-53

**Repair keeps the last copy of an object that it finds, even a copy that does not parse or one inside another object's stream data**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #584, #586

#### What happens

`PdfRepair.rebuild` stores `entries[num]` for every `N G obj` it finds, before it parses the object (`PdfRepair.kt:62`), and a later find replaces an earlier one. Three cases follow:

- A copy that does not parse still wins. A partial download of a file with an incremental update ends inside the update's new copy of the page. Repair keeps the cut copy, resolving it throws, and the page becomes a blank kid. MuPDF stops at the first object it cannot parse ("cannot parse object ... ignoring rest of file", `pdf_repair_xref`) and keeps the complete earlier copy.
- The search for `obj` is a byte search that resumes 3 bytes after each match (`:59`), so it reads inside stream data and literal strings. A PDF attached without compression, or an uncompressed content stream that shows `(1 0 obj)`, adds entries at those offsets, and the later ones replace the file's own objects, catalog included. MuPDF lexes token by token, reads a string as one token, and steps over a stream body by its `/Length` or its `endstream` (`pdf_repair_obj`), so nothing inside a stream or a string becomes an object.
- An object stream member never replaces a direct object (`:113`), whatever the order in the file. An update that moved object N into a new object stream after its first, direct copy repairs to the old copy. MuPDF lets the member win when the object stream comes later in the file (`pdf_repair_obj_stm`, its note on bug 708286).

#### What should happen

A copy enters the table only when it parses, an earlier good copy is kept when a later one does not, the scan steps over stream bodies and strings, and file order decides between a direct copy and an object stream member, as in MuPDF.

#### How to reproduce

- `make_truncated_update.py` writes `truncated-update.pdf` (6,724 bytes): one page, then an update that adds an annotation with an appearance stream and is cut inside its new copy of the page. The last `startxref` is 6,144 bytes from the end, so `open` repairs. `pdftotext` prints "Text of the original page".

  ```kotlin
  val doc = PdfDocument.open(java.io.File("audit-repro/L2/repro/truncated-update.pdf").readBytes())
  println(doc.pages[0].extractText())   // today "": object 3 points at the cut copy
  ```

- `make_embedded_pdf.py` writes `embedded-pdf.pdf`: a page "Outer document page", then an uncompressed `/EmbeddedFile` that holds a whole PDF whose page reads "Inner attachment page", and no xref. KitePDF repairs to the inner catalog and page. (Poppler shows the inner page too; MuPDF, by `pdf_repair_obj`, would show the outer one.)

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/PdfRepair.kt:56` to `:69` (entry before parse, search inside the object), `:108` to `:116` (direct copies always win).

#### Notes

Ran the generators and poppler; the KitePDF side is read from source.

### KP-54

**A tiling pattern paints nothing when its fill needs more than 20,000 cells, and only a strip when its step is negative**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `renderTilingPattern` counts the cells over the device bounds of the whole fill path, and returns without painting anything when the count passes `MAX_TILES` (20,000).
- So a full Letter page filled with a cell smaller than about 4.9 points paints nothing. A 3-point cell needs 206 x 266 = 54,796 cells. Dot screens, hatching, and the small repeated backgrounds that browsers export use cells of a few points.
- The count is over the whole path, not the part of it on the page or inside the clip. A rectangle drawn past the page edge, such as a bleed or an oversized background, reaches the cap with larger cells too.
- The index range is computed with |XStep| and |YStep|, but each cell is placed at `i * XStep`. With a negative step the cells land on the other side of the origin: a 400-point square with `/XStep -20` paints only the 2 columns of cells next to x = 0, not 20. The lattice of cells is the same for a step and its negative, so the result should be the same.

#### What should happen

- Every cell that the visible part of the fill needs is painted (ISO 32000-1, 8.7.3.1). A small cell is drawn once and repeated, as MuPDF's tile cache does, or the work is bounded by the visible area, but the fill is never dropped as a whole.
- The index range follows the sign of the step.

#### How to reproduce

- `pdfs/tiling-small-cell.pdf`: `/Pattern cs /P0 scn 0 0 612 792 re f`, with `/BBox [0 0 3 3] /XStep 3 /YStep 3` and a cell `0 0 1 rg 0 0 2 2 re f`. KitePDF: no fill at all. Poppler at 36 dpi: 30,294 of 121,176 pixels blue. `pdfs/tiling-6pt-cell.pdf` (a 6-point cell, 13,936 cells) is the control that paints.
- `pdfs/tiling-negative-step.pdf`: `/BBox [0 0 20 20] /XStep -20 /YStep 20`, cell `1 0 0 rg 0 0 15 15 re f`, over `0 0 400 400 re f`. Poppler paints the whole square with red cells.

```kotlin
// kitepdf-pdf/test
@Test fun a_small_cell_fills_the_page() {
    val pdf = TestPdf.onePage(
        content = "/Pattern cs /P0 scn 0 0 612 792 re f", mediaBox = "0 0 612 792",
        resources = "/Pattern << /P0 5 0 R >>",
        extra = listOf(TestPdf.stream("0 0 1 rg 0 0 2 2 re f",
            "/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 3 3] /XStep 3 /YStep 3 /Resources << >>")),
    )
    assertTrue(TestPdf.calls(pdf).any { it is RecordingCanvas.Call.Fill })   // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1845`: the region is the whole fill path.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1856` to `:1861`: the index range from |step|, and the early return.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1898`: cells placed at `i * xs`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2816`: `MAX_TILES`.

#### Notes

Read from source, and the PoCs rendered with Poppler. No test or CHANGELOG entry covers the cap.

### KP-55

**Stroked text in a CFF font leaves every contour but the last one open**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #85, #415

#### What happens

- A Type 2 charstring has no `closepath`: a new moveto and `endchar` close the subpath (Adobe Technical Note 5177, 4.1).
- `CharstringInterpreter`'s `rmoveto`, `hmoveto` and `vmoveto` start a new subpath without closing the previous one. Only `endchar` closes, so only the last contour of a glyph is closed.
- A fill does not see the difference. A stroke does: `strokeTextGlyphs` strokes the outline as it is (render modes 1, 2, 5 and 6), and so does a pattern stroke of text. The closing edge of every contour but the last is not stroked, and its two ends get caps instead of a join.
- The bundled Helvetica (Nimbus Sans) shows it. Decoded with a script, the first contour of `B` starts at (79, 0) and ends at (79, 729), so the left side of the stem, 729 units long, is never stroked; the second contour (the upper bowl) is open too. `A`, `a`, `e` and `i` have open first contours the same way.
- So fake bold (`2 Tr`) and outlined text in any CFF font, embedded or one of the standard 14, has gaps. TrueType outlines close each contour (`TrueTypeFont.kt:721`), and Type 1 has an explicit `closepath`.

#### What should happen

Each moveto closes the open subpath first, as FreeType does when it builds the outline, so every contour is closed.

#### How to reproduce

The page `BT /F1 72 Tf 1 Tr 2 w 50 50 Td (B) Tj ET`, with `/F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`, strokes a `B` without the left edge of its stem.

```kotlin
// kitepdf-core/test
@Test fun every_contour_of_a_cff_glyph_is_closed() {
    val cff = Standard14Fonts.program("Helvetica")!!
    val b = cff.outline(cff.glyphIdForName("B"))!!
    assertEquals(
        b.segments.count { it is KitePath.Segment.MoveTo },
        b.segments.count { it == KitePath.Segment.Close },
    )   // 3 and 1 today
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:278` to `:301`: the three movetos.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:574`: the only `close()`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2454`: `strokeTextGlyphs` strokes the outline as it is.

#### Notes

Read from source, and the contours checked by decoding `work/NimbusSans-Regular.cff` with `work/cff.py`.

### KP-56

**A JPEG 2000 image ignores the /ColorSpace of its image dictionary**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: IK-02 (the ImageKodec half)

#### What happens

- For `/JPXDecode`, `KiteImageData.from` sets the image's colour space to DeviceRGB or DeviceGray, from what the decoder returns, on both decode paths. The image's own `/ColorSpace` (`resolvedCs`) is used only when the data does not decode at all.
- An image with `/ColorSpace [/Indexed /DeviceRGB 1 <FF0000 0000FF>]` over a one-component codestream paints its indices as grey levels 0 and 1, so red and blue come out black.
- A codestream without a JP2 header that holds four components, with `/ColorSpace /DeviceCMYK`: ImageKodec reads it as RGB plus an opacity channel (`Jp2Color.plan`), and KitePDF paints the C, M and Y samples as R, G and B and drops K. Paper with no ink paints black, and cyan paints red.
- An ICCBased space, and the DefaultRGB, DefaultCMYK and output intent substitution that `PageRenderer.decodeImageCached` asks for, are lost the same way.

#### What should happen

ISO 32000-1, 8.9.5, Table 89 (`ColorSpace`): when the image dictionary of a JPXDecode image has `/ColorSpace`, any colour space data in the JPEG 2000 stream is ignored. The samples convert through the dictionary's space when its component count matches the stream's, an Indexed space included, as MuPDF and Poppler do. `/Decode` stays ignored (Table 89, `Decode`).

#### How to reproduce

- `pdfs/jpx-indexed.pdf`: an 8 x 8 one-component codestream (`pdfs/index-1comp.j2k`, left half 0, right half 1) with the Indexed space above. Poppler: red and blue halves. KitePDF: black.
- `pdfs/jpx-cmyk-raw.pdf`: an 8 x 8 four-component codestream (`pdfs/cmyk-4comp.j2k`, left half no ink, right half full cyan) with `/DeviceCMYK`. Poppler: white and cyan (0, 172, 239). KitePDF: black and red.

```kotlin
// kitepdf-core/test@jvm
val dict = PdfDictionary(linkedMapOf<String, PdfObject>(
    "Width" to PdfInt(8), "Height" to PdfInt(8), "BitsPerComponent" to PdfInt(8), "Filter" to PdfName("JPXDecode"),
    "ColorSpace" to PdfArray(listOf(PdfName("Indexed"), PdfName("DeviceRGB"), PdfInt(1), PdfString(byteArrayOf(-1, 0, 0, 0, 0, -1)))),
))
val bytes = java.io.File("audit-repro/L3/pdfs/index-1comp.j2k").readBytes()
val image = KiteImageData.from(PdfStream(dict, bytes), IndirectResolver { null })
assertTrue(image.resolvedColorSpace is KiteColorSpace.Indexed)   // DeviceGray today
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:422` and `:426`: the lazy path, from the decoder's space.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:440`: the full decode, the same.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageSamples.kt:89`: the space comes from the decoder.

#### Notes

Read from source, and both PoCs rendered with Poppler. The codestreams were made with Pillow (OpenJPEG) by `work/mkjpx.py`. A four-component codestream is read as RGB plus opacity by ImageKodec on purpose (its #82), so the fix belongs in KitePDF, which knows the PDF's space.

Measured on the JVM at `c4e7574` by the lead: a 64 by 64 raw four-component codestream written by Pillow (OpenJPEG), every pixel C = 255 and M = Y = K = 0, with `/DeviceCMYK`: Poppler paints (0, 172, 239), KitePDF paints (255, 0, 0).

### KP-57

**A non-symbolic TrueType font with only a Macintosh cmap draws the wrong glyph for accented letters**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- ISO 32000-1, 9.6.6.4: with no (3,1) subtable but a (1,0) one, the glyph name maps to a code of the standard Mac OS Roman encoding, and that code looks up the (1,0) subtable.
- `TtfCMap.parse` keeps one subtable, the best ranked. `PdfFont.simpleGid` looks up `resolveByteToUnicode(code)`, a Unicode value, in it. For a (1,0) subtable that key is wrong above 0x7F.
- WinAnsi code 0xE9 is `eacute`, U+00E9. KitePDF looks up slot 0xE9 of the Macintosh table, which is `Egrave`, so é draws as È. `eacute` is Mac Roman 0x8E. When slot 0xE9 is empty, the fallbacks end at glyph 233 used as a glyph index, or at `.notdef`, which draws nothing.

#### What should happen

For a (1,0) subtable, the name maps to its Mac OS Roman code, and that code looks up the subtable, as 9.6.6.4 says. Poppler draws the right glyph.

#### How to reproduce

`fonts/macroman-only.ttf` has only a (1,0) format 0 `cmap`: 0x65 `e` is a square, 0x8E (`eacute`) a tall bar, and 0xE9 (`Egrave`) a triangle. `pdfs/tt-macroman-only.pdf` embeds it as `/TrueType /Encoding /WinAnsiEncoding /Flags 32` and shows `(e\351) Tj`. Poppler: a square and a tall bar. KitePDF (by the code path above): a square and a triangle.

```kotlin
// kitepdf-pdf/test@jvm
val pdf = java.io.File("audit-repro/L3/pdfs/tt-macroman-only.pdf").readBytes()
val run = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
assertEquals(listOf(1, 3), run.glyphs.map { it.gid })   // [1, 2] today
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:275`: the Unicode value as the key.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TtfCMap.kt:45`: one subtable is kept, (1,0) at rank 8.

#### Notes

Read from source, and the PoC rendered with Poppler.

### KP-58

**An embedded TrueType font whose hhea counts more metrics than maxp counts glyphs is thrown away**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `parseHmtx` sizes `widths` by `maxp.numGlyphs` and writes `hhea.numberOfHMetrics` entries into it. When a subsetter cut glyphs and left `hhea` as it was, `numberOfHMetrics` is larger, and `widths[i]` throws `ArrayIndexOutOfBoundsException`.
- `PdfFont.loadEmbeddedTtf` and `CompositeFont.loadTtf` run the parse in `runCatching`, so the whole program is dropped. The text then draws in the bundled standard 14 face or a host font. For a symbolic subset that gives the wrong letters, and for an Identity CIDFontType2 without `/ToUnicode` and outside the Adobe collections it draws nothing.
- An `hmtx` or `loca` table that ends early and is the last table of the file does the same, through `PdfFormatException` from `TtfReader`.

#### What should happen

`parseHmtx` reads `min(numberOfHMetrics, numGlyphs)` metrics, and a short table gives what it holds, so the font is kept and draws. Poppler (FreeType) draws the proof-of-concept page with the embedded glyphs, and pdf.js clamps the same count.

#### How to reproduce

`fonts/hmetrics5-glyphs3.ttf` has `maxp.numGlyphs` 3, `hhea.numberOfHMetrics` 5 and five metrics. `pdfs/tt-hmetrics.pdf` shows `(AB)` in it: Poppler draws a square and a triangle.

```kotlin
// kitepdf-core/test@jvm
TrueTypeFont.parse(java.io.File("audit-repro/L3/fonts/hmetrics5-glyphs3.ttf").readBytes())   // ArrayIndexOutOfBoundsException today
```

The same with `MalformedGlyfTest.buildFont`: set `put16(hhea, 34, 3)` and give `hmtx` three metrics, for two glyphs.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:557`: `parseHmtx`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:437` and `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:203`: the font is dropped on any exception.

#### Notes

Read from source, and the PoC rendered with Poppler.

### KP-59

**A /CIDToGIDMap stream or a shading's Indexed lookup stream that fails to decode stops the whole page**

- Repo: KitePDF
- Labels: area:core, area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #581, #582, #583

#### What happens

- `CidToGidMap.parseStream` calls `FilterChain.decode` with no guard. `FilterChain.decode` rethrows every filter failure, and an unknown filter throws `UnsupportedFilterException`. A stream marked `/FlateDecode` whose bytes are not zlib, or are fewer than 6, throws `InflateException` from `Zlib.decode`.
- That exception leaves `CompositeFont.from`, then `PdfFont.from`, then `PageRenderer.loadFonts`, which `render` calls first (line 303), before `beginPage`. The page draws nothing, not even its annotations, and the exception leaves `renderTo` and `structuredText`. `TextExtractor.loadFonts` fails the same way, so `extractText()` throws too.
- A shading has the same hole. `KiteShading.parseDict` resolves the shading's colour space and its `/Background` before its `runCatching` starts. An Indexed space whose lookup is a stream that fails to decode (`KiteColorSpace.kt:460`, also unguarded) throws out of `loadShadings`, which `render` calls at line 307, again before anything draws.
- Every other decode in these paths has a guard: the font programs, the ToUnicode and encoding CMaps, the pattern cell, the function samples and the mesh data.

#### What should happen

A broken map or lookup table is a skip (CONTRIBUTING, "Never break lenient salvage"): the CIDToGIDMap falls back to Identity, or the font to its fallback, and the shading becomes `Unsupported`. The rest of the page draws.

#### How to reproduce

```kotlin
// kitepdf-pdf/test
@Test fun a_cid_to_gid_map_that_does_not_inflate_leaves_the_page_drawing() {
    val pdf = TestPdf.onePage(
        content = "BT /F1 12 Tf 10 10 Td <0001> Tj ET 0 0 1 rg 50 50 50 50 re f",
        resources = "/Font << /F1 5 0 R >>",
        extra = listOf(
            "<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding /Identity-H /DescendantFonts [6 0 R] >>",
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /X /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /CIDToGIDMap 7 0 R >>",
            TestPdf.stream("these bytes are not zlib", "/Filter /FlateDecode"),
        ),
    )
    // InflateException today; expected: the blue square draws
    assertTrue(TestPdf.calls(pdf).any { it is RecordingCanvas.Call.Fill && it.color == RgbColor(0.0, 0.0, 1.0) })
}
```

For the shading, give the page `/Shading << /Sh0 5 0 R >>` with `<< /ShadingType 2 /ColorSpace [/Indexed /DeviceRGB 1 6 0 R] /Coords [0 0 100 0] /Function << /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 1 >> >>` and the same broken stream as object 6. The content `0 0 1 rg 50 50 50 50 re f` does not draw.

The two cases are `pdfs/cidtogidmap-not-flate.pdf` and `pdfs/shading-indexed-not-flate.pdf`. Poppler draws the blue square in both, with a warning about the flate stream.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:244`: `FilterChain.decode` with no guard.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:238` to `:246`: the colour space and background, outside the `runCatching` at line 247.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:460`: the Indexed lookup stream, decoded with no guard.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1248` and `:481`: `loadFonts` and `loadShadings`, with no catch.

#### Notes

Read from source. `ContentStreamParser.colorSpaces` and `KiteImageData.resolveColorSpace` wrap `KiteColorSpace.resolve` in `runCatching`, so the same Indexed space in `/ColorSpace` or on an image is skipped as it should be. Only the shading path misses it.

### KP-60

**RevisionChanges reports a filled choice field or rich text field as a forbidden change**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #448

#### What happens

`FILLING_KEYS` is `V`, `AS` and `AP`. Filling a list box or a combo box also sets `/I`, the selected indices (ISO 32000-1, 12.7.4.4), and filling a rich text field sets `/RV` (12.7.3.4). A field whose changed keys include either is classified `Other` ("field X"), which no level permits.

KitePDF's own editor writes both: `setChoiceValue` sets `/I` and `setTextFieldValue` sets `/RV` on a rich text field. So a certified form at level 2, filled in with KitePDF or Acrobat, reads as illegally changed.

#### What should happen

`/I` and `/RV` count as filling (`FILLING_KEYS`), as does `/TI` for a list box that only scrolled.

#### How to reproduce

Common test:

```kotlin
@Test
fun choosing_an_option_is_filling_a_form() {
    val base = TestPdf.onePage(
        "q Q",
        pageEntries = "/Annots [5 0 R]",
        catalogEntries = "/AcroForm << /Fields [5 0 R] /DA (/Helv 0 Tf 0 g) >>",
        extra = listOf("<< /Type /Annot /Subtype /Widget /FT /Ch /Ff 131072 /T (Pick) /Opt [(a) (b)] /V (a) /Rect [10 10 150 30] /P 3 0 R >>"),
    )
    val old = PdfDocument.open(base)
    val new = PdfDocument.open(old.edit().apply { setChoiceValue(old.formField("Pick")!!, "b") }.saveIncremental())
    val changes = RevisionChanges.between(old, new, level = 2, lock = null)
    assertTrue(changes.all { it.isPermitted }, changes.toString())   // fails today: "field Pick" is Other
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:246`

#### Notes

Read from source. `RevisionChangesTest.fillingAFieldIsPermittedFromLevel2` only changes `/V` of a text field.

### KP-61

**setTextFieldValue and setChoiceValue redraw only the first widget of a field**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

Both methods build one appearance from `field.rect` and write it to `field.widgetReference`, the first widget. A field shown in several places has one widget per place (ISO 32000-1, 12.7.3.1), for example a name repeated on every page. The other widgets keep their old `/AP`, so they show the old value. Both methods then set `/NeedAppearances` to false, so a reader does not rebuild them. `PdfFormField.widgets` already lists every widget.

#### What should happen

Every widget of the field gets an appearance drawn for its own rectangle.

#### How to reproduce

Common test:

```kotlin
@Test
fun filling_a_field_redraws_every_widget() {
    val ap = "BT /Helv 12 Tf 2 6 Td (Old) Tj ET"
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] /DA (/Helv 12 Tf 0 g) >> >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [6 0 R] >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [7 0 R] >>",
        "<< /FT /Tx /T (Name) /V (Old) /Kids [6 0 R 7 0 R] >>",
        "<< /Type /Annot /Subtype /Widget /Parent 5 0 R /Rect [10 10 200 30] /P 3 0 R /AP << /N 8 0 R >> >>",
        "<< /Type /Annot /Subtype /Widget /Parent 5 0 R /Rect [10 10 200 30] /P 4 0 R /AP << /N 9 0 R >> >>",
        TestPdf.stream(ap, "/Type /XObject /Subtype /Form /BBox [0 0 190 20]"),
        TestPdf.stream(ap, "/Type /XObject /Subtype /Form /BBox [0 0 190 20]"),
    ))
    val doc = PdfDocument.open(pdf)
    val out = PdfDocument.open(doc.edit().apply { setTextFieldValue(doc.formField("Name")!!, "New") }.saveIncremental())
    val second = out.resolve(PdfReference(7, 0)) as PdfDictionary
    assertNotEquals(PdfReference(9, 0), second.getDict("AP", out)!!["N"])   // fails today: still 9 0 R, which shows "Old"
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:319` to `:332` (`setTextFieldValue`), `:434` to `:446` (`setChoiceValue`)

#### Notes

Read from source. The tests that fill fields (`FormFillTest`, `RichTextFieldTest`, `NeedAppearancesTest`) use fields with one widget. The viewer is not affected: `PageRenderer.liveWidgetAppearance` draws every widget by name.

### KP-62

**A stamp or a redaction box lands in the page's leftover graphics state when the page leaves a q open**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`stampPage` writes `q`, the page content, `Q`, then the stamp. `redactRegions` writes `q`, the rewritten content, `Q`, then the black boxes. When the page content has a `q` without its `Q`, which real files have (#47 is the form XObject case), the wrapper's `Q` only closes the page's own last `q`. The graphics state at that `q` stays in force, so a `cm` or a clip that the page set before it applies to the stamp and to the boxes.

For a page that starts `0.5 0 0 0.5 0 0 cm q ...` with no `Q`, a stamp at (100, 100) draws at (50, 50) at half size, and a redaction box covers half the region, at another place on the page. The removed content stays removed, but the box can cover text that should stay, and the region shows uncovered.

#### What should happen

Count the nesting of the content and close every open `q` before the stamp and the boxes, as MuPDF's content filter does.

#### How to reproduce

Common test:

```kotlin
@Test
fun a_stamp_ignores_a_q_that_the_page_left_open() {
    val pdf = TestPdf.onePage("0.5 0 0 0.5 0 0 cm q 0 0 1 rg 0 0 50 50 re f", mediaBox = "0 0 300 300")
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply {
        stampPage(doc.pages[0]) { setFillRgb(1.0, 0.0, 0.0); rectangle(100.0, 100.0, 50.0, 50.0).fill() }
    }.saveIncremental()
    val red = TestPdf.calls(out).filterIsInstance<RecordingCanvas.Call.Fill>().single { it.color == RgbColor(1.0, 0.0, 0.0) }
    assertEquals(50.0, red.path.bounds(red.ctm)!!.width, 1e-6)   // fails today: 25.0
}
```

For the redaction box: `RawPdf.page("0.5 0 0 0.5 0 0 cm q BT /F1 24 Tf 200 1400 Td (SECRET) Tj ET".encodeToByteArray())`, redact `KiteRectangle(90.0, 690.0, 300.0, 730.0)`, save with `saveRewritten()`: the black fill is 105 points wide instead of 210.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:252` (stamp), `:863` and `:878` (redaction)

#### Notes

Read from source.

### KP-63

**Password and comb text fields draw their value as plain text**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

Neither `FieldAppearance.build` (used by `setTextFieldValue`) nor `FieldAppearance.synthesize` (used by the renderer for a live or missing value) reads the Password or the Comb flag (ISO 32000-1, 12.7.4.3, Table 228).

- Password (bit 14): the value is drawn in clear. `KiteFormInput` masks the typing with `PasswordVisualTransformation`, but once the value is committed, `liveWidgetAppearance` draws it on the page, and `setTextFieldValue` writes it into the saved `/AP` where any reader shows it. The spec asks that the value be "echoed in some unreadable form, such as asterisks".
- Comb (bit 25, with `/MaxLen`): the value is drawn as one run instead of one character in each of `MaxLen` equal cells. Forms that ask for a tax number, a date or a code in boxes show the characters bunched at the left.

#### What should happen

A password field draws one bullet or asterisk per character. A comb field divides its width into `MaxLen` cells and centres one character in each.

#### How to reproduce

Common test:

```kotlin
@Test
fun a_password_field_does_not_show_its_value() {
    val base = TestPdf.onePage(
        "q Q",
        pageEntries = "/Annots [5 0 R]",
        catalogEntries = "/AcroForm << /Fields [5 0 R] /DA (/Helv 12 Tf 0 g) >>",
        extra = listOf("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 8192 /T (Pin) /Rect [10 10 150 30] /P 3 0 R >>"),
    )
    val doc = PdfDocument.open(base)
    val out = PdfDocument.open(doc.edit().apply { setTextFieldValue(doc.formField("Pin")!!, "hunter2") }.saveIncremental())
    val widget = out.resolve(PdfReference(5, 0)) as PdfDictionary
    val ap = out.resolve(widget.getDict("AP", out)!!["N"] as PdfReference) as PdfStream
    assertFalse("hunter2" in FilterChain.decode(ap).decodeToString())   // fails today
}
```

For comb, the same field with `/Ff 16777216 /MaxLen 5` and the value `12345` gives one `(12345) Tj`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/FieldAppearance.kt:59` (`build`), `:216` (`synthesize`)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt` (`liveWidgetAppearance`)

#### Notes

Read from source. No code in `kitepdf-pdf` reads either flag.

### KP-64

**Field appearances ignore /MK /R, so a field on a turned page gets its value drawn sideways**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`/MK /R` turns a widget's appearance by a multiple of 90 degrees (ISO 32000-1, 12.5.6.19, Table 189). Form editors set it on fields of a page with `/Rotate`, so the value reads upright to the reader. No code in `kitepdf-pdf` reads it. `setTextFieldValue`, `setChoiceValue` and the toggle appearances write `/BBox [0 0 w h]` with no `/Matrix`, and `synthesize` does the same on screen.

So on a field with `/MK << /R 90 >>`, 20 points wide and 200 tall, the value is laid out across the 20-point side and clipped to a few letters, and it runs along the page instead of along the field.

#### What should happen

For `R` 90 or 270 the appearance swaps width and height in `/BBox` and gets a `/Matrix` that turns it (for 90: `[0 1 -1 0 w 0]`, as pdf.js builds it). 180 turns without the swap.

#### How to reproduce

Common test: a text field with `/MK << /R 90 >> /Rect [100 100 120 300]`, then `setTextFieldValue(field, "Hello")` and `saveIncremental()`. The new `/AP /N` has `/BBox [0 0 20 200]` and no `/Matrix`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/FieldAppearance.kt:83` (`build`), `:168` (`buildToggle`), `synthesize`

#### Notes

Read from source.

### KP-65

**Removing or reordering pages drops a /CropBox that the page tree gave the pages**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`applyPageOrder` replaces the root `/Pages` node with `/Type`, `/Kids` and `/Count`. To keep inherited attributes, it writes `/MediaBox`, `/Resources` and `/Rotate` onto each page that lacks them. `/CropBox` is the fourth inheritable attribute (ISO 32000-1, 7.7.3.4, Table 30), and it is not written. `graftablePageDict` has the same gap for a copied page.

So after `removePage`, `setPageOrder`, `insertPageAt`, `appendPage` or `mergeDocument`, every page whose crop box came from a `/Pages` node shows its whole media box: bleed and crop marks appear, and the page size changes. `PdfPage.cropBox` itself reads the inherited value.

#### What should happen

`/CropBox` is baked onto each page like the other three.

#### How to reproduce

Common test:

```kotlin
@Test
fun removing_a_page_keeps_the_inherited_crop_box() {
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /CropBox [50 50 250 250] >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] >>",
    ))
    val doc = PdfDocument.open(pdf)
    assertEquals(KiteRectangle(50.0, 50.0, 250.0, 250.0), doc.pages[0].cropBox)
    val out = PdfDocument.open(doc.edit().apply { removePage(doc.pages[1]) }.saveIncremental())
    assertEquals(KiteRectangle(50.0, 50.0, 250.0, 250.0), out.pages[0].cropBox)   // fails today: 0, 0, 300, 300
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:644` to `:671` (`applyPageOrder`), `:714` (`graftablePageDict`)

#### Notes

Read from source. The comment in `applyPageOrder` names the three attributes it bakes, so the fourth was missed rather than refused.

### KP-66

**setChoiceValue saves an appearance that shows the export value, not the option's text**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`/Opt` can pair each option's export value with the text to show (ISO 32000-1, 12.7.4.4, Table 231). `setChoiceValue` takes the export value (that is what `optionIndex` matches) and passes the same string to `FieldAppearance.build`, so the saved `/AP` shows the export value: "FR" instead of "France". A list box is drawn as one line of text, not as its rows.

The viewer draws both right (`ChoiceAppearance`, "Live choice appearances preserve export/display distinctions"), so a choice looks right on screen and wrong in the saved file.

#### What should happen

`setChoiceValue` builds its appearance with `ChoiceAppearance.build`, as the viewer does.

#### How to reproduce

Common test: a combo box (`/Ff 131072`) with `/Opt [[(US) (United States)] [(FR) (France)]]`, then `setChoiceValue(field, "FR")` and `saveIncremental()`. The decoded `/AP /N` holds `(FR) Tj`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:427`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/ChoiceAppearance.kt` (the viewer's version)

#### Notes

Read from source.

### KP-67

**An SVG arc whose end point is its start point becomes a curve of NaN, so the whole path can disappear**

- Repo: KitePDF
- Labels: area:svg, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `arcTo` handles a zero radius, but not an arc that ends where it starts. Then the start angle comes from `angle(1, 0, 0, 0)`, which divides 0 by a length of 0. `(0.0 / 0.0).coerceIn(-1.0, 1.0)` is still NaN, so both angles are NaN.
- `ceil(NaN).toInt()` is 0, the segment count becomes 1, and `b.curveTo` gets NaN for all six coordinates. `KitePath.Builder` keeps them.
- Skia, and so the Compose canvas, does not draw a path that has a coordinate that is not finite, so the shape with the arc vanishes, not only the arc. A pie chart's full slice, `M50 50 L90 50 A40 40 0 1 1 90 50 Z`, is this case.

#### What should happen

SVG 1.1, F.6.2: when the end points of an arc are the same, the arc segment is left out, and the path goes on from that point.

#### How to reproduce

```kotlin
// kitepdf-svg, test/
@Test
fun an_arc_that_ends_where_it_starts_is_left_out() {
    val path = SvgImage.parsePathData("M50 50 L90 50 A40 40 0 1 1 90 50 Z")
    for (s in path.segments) if (s is KitePath.Segment.CurveTo) assertTrue(s.x3.isFinite(), "a NaN curve: $s")
}
```

#### Where

- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:2032-2076`: `arcTo`, which checks only the radii at line 2037.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:2078-2084`: `angle`, which divides by the product of the two lengths.

#### Notes

Read from source. What each canvas does with the NaN curve is not run; Skia's rule for paths that are not finite is from its documentation.

### KP-68

**Percentages resolve against the page, not against the containing block**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `StyleResolver` turns a percentage into points when it computes the style, against `refWidthPt`, one value for the whole chapter: the page's content width (`docRootBuild`).
- `BoxLayout` takes `widthPt`, the margins and the paddings as they come, so a box nested in a narrower box still uses the page's width.
- So `<div style="width:50%"><div style="width:50%">` gives both boxes half the page. An image of `width: 50%` inside a figure of `width: 80%` is 50% of the page, not 40%. Percentage margins, paddings, `text-indent`, `left` and `flex-basis` inside a narrower box are too large in the same way.
- An image that the page width makes too wide is scaled down to its column, which hides the case of 100%, but not a smaller one.

#### What should happen

CSS 2.1, 10.2 and 8.3: a percentage of `width`, the margins, the paddings and `text-indent` refers to the width of the containing block. The style keeps the percentage, and the layout resolves it against the box it lays out in.

#### How to reproduce

```kotlin
// kitepdf-epub, test/BoxLayoutTest.kt (which has layout() and child())
@Test
fun a_percentage_width_refers_to_the_containing_block() {
    val outer = layout("""<div style="width:50%"><div style="width:50%">x</div></div>""", width = 300.0).child(0)
    assertEquals(150.0, outer.borderBoxWidth, 1e-6)
    assertEquals(75.0, outer.child(0).borderBoxWidth, 1e-6) // 150 today
}
```

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:496`, `:576-585` and `:617-620`: `text-indent`, the margins, the paddings and the widths, against `refWidthPt`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:727-735`: the one resolver of a chapter.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:277`: the width taken as it is.

#### Notes

Read from source. No test of `kitepdf-epub` nests one percentage width in another.

### KP-69

**vw and vh resolve against the property's own reference, and rem against the reader's size instead of the root element's**

- Repo: KitePDF
- Labels: area:epub, area:core, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `CssValues.length` reads `vw` and `vh` as a share of `refPt`, the reference that the caller passes for a percentage. That is the content width for widths and margins, the content height for heights, and the parent's font size for `font-size`.
- So `font-size: 5vw` is 5% of the parent's font: 0.6 pt for a 12 pt parent, text too small to read. `margin-top: 30vh` is 30% of the width of the page's content box, not of the page's height, a common way to set a title down a page. `height: 50vw` is half the height of the content box. `line-height: 3vh` is 3% of the font size.
- `rem` uses `rootFontSizePt`, the reader's font size, whatever the root element sets. With `html { font-size: 62.5% } body { font-size: 1.6rem }`, a common pattern, the body text is 1.6 times the reader's size, where a browser gives it the reader's size.
- `vmin` and `vmax` read as no length, so the declaration is dropped.

#### What should happen

CSS Values 3, 6.1.1 and 6.1.2: `rem` is the computed `font-size` of the root element, and `vw` and `vh` are 1% of the width and the height of the initial containing block, the page, whatever the property. `vmin` and `vmax` are the smaller and the larger of the two.

#### How to reproduce

```kotlin
// kitepdf-epub, test/BoxLayoutTest.kt (which has layout())
@Test
fun viewport_units_refer_to_the_page() {
    val p = layout("""<p style="font-size:5vw">x</p>""", width = 300.0).children[0] as BlockBox
    assertEquals(15.0, p.style.fontSizePt, 1e-6) // 0.6 today
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/css/CssValues.kt:27` and `:35-36`: `rem`, `vw` and `vh`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/StyleResolver.kt:930-945`: `font-size` and `line-height`, which pass the font size as the reference.

#### Notes

Read from source. No test reads `vw`, `vh` or `rem` in a chapter.

### KP-70

**text-transform uses the simple case mapping of one character, so ß stays ß and Turkish i loses its dot**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #322, #616

#### What happens

- `BoxBuilder.transform` maps one code point to one code point through `CaseMapping`, which uses `Char.uppercaseChar`, `lowercaseChar` and `titlecaseChar`.
- So `text-transform: uppercase` gives `STRAßE` for `Straße`, where a browser gives `STRASSE`, and `ﬁ` keeps its ligature. In a chapter with `lang="tr"`, `istanbul` becomes `ISTANBUL`, not `İSTANBUL`. `text-transform: lowercase` of `ΟΔΟΣ` gives `οδοσ`, with no final sigma. `font-variant: small-caps` capitals come from the same mapping.
- Nothing reads the language of the element for the case.

#### What should happen

CSS Text 3, 2.1.1: the UA uses the full case mappings of Unicode, including the conditional ones (final sigma), and the language-sensitive mappings when the language is known (Turkish, Azeri, Lithuanian). A mapping may change the number of characters.

#### How to reproduce

```html
<p style="text-transform: uppercase">Straße</p>
<p lang="tr" style="text-transform: uppercase">istanbul</p>
<p style="text-transform: lowercase">ΟΔΟΣ</p>
```

`page.textContent()` gives `STRAßE`, `ISTANBUL` and `οδοσ`. Chrome and Firefox show `STRASSE`, `İSTANBUL` and `οδος`.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:1125-1133`: `transform`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/CaseMapping.kt:10-15`: the simple mappings.

#### Notes

Read from source.

### KP-71

**The subsequent-sibling combinator costs the cube of the number of siblings, and every sibling test scans from the first child**

- Repo: KitePDF
- Labels: area:epub, kind:perf
- Severity: medium
- Confidence: Confirmed
- Related: #549

#### What happens

- `previousElement` finds the sibling before an element by scanning the parent's children from the start, so one call costs the element's position.
- The `~` combinator calls it once for each sibling it walks back over. For the element at position `i`, matching `h1 ~ p` costs about `i * i / 2`, and a chapter of `n` paragraphs costs about `n^3 / 6`. With 3,000 paragraphs under one `body`, and their white space text between them, that is about 10^10 steps for one rule.
- `+`, `:first-child`, `:nth-child` and the other sibling tests cost the position of the element, so a rule such as `p + p`, the most common one in book CSS, costs `n^2 / 2` per chapter.
- The cascade matches every rule for every element at each layout, and a font size change lays the chapter out again. `querySelectorAll` of the book's scripts uses the same matcher.

#### What should happen

A sibling step costs O(1): the matcher keeps each element's index among its siblings for the layout of a chapter, or the tree links its siblings. Then `~` costs the number of siblings it walks, and `+` one step.

#### How to reproduce

```kotlin
// kitepdf-epub, test/BoxLayoutTest.kt (which has layout())
@Test
fun a_subsequent_sibling_rule_is_not_cubic() {
    val html = "<h1>t</h1>" + "<p>x</p>\n".repeat(3000)
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    layout(html, css = "h1 ~ p { text-indent: 1em }")
    assertTrue(mark.elapsedNow().inWholeSeconds < 2, "took ${mark.elapsedNow()}")
}
```

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:512-519`: `previousElement`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:452-460`: the `~` combinator.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:206-221`: `:nth-child` and its kin.

#### Notes

Read from source. The cost is counted, not timed.

### KP-72

**A book whose chapters are encrypted opens and lays out its ciphertext as text**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `parseEncryption` reads `META-INF/encryption.xml` into a map of path to algorithm, and only `programAt` uses it, to undo the two font obfuscations. Any other algorithm, such as `http://www.w3.org/2001/04/xmlenc#aes128-cbc` of a book sold with DRM, is ignored.
- `chapterText` reads such a chapter as it is stored, and `TextEncoding.decode` turns the ciphertext, which is not valid UTF-8, into windows-1252 characters. The layout then makes pages of random characters, and the encrypted cover image draws nothing.
- `EpubDocument.open` succeeds, and nothing tells the app that the book is protected, so it cannot say so to the reader. No test opens a book whose `encryption.xml` names a chapter.

#### What should happen

- EPUB 3.3, 4.2.6.3.2: `encryption.xml` names each encrypted resource and its algorithm. A resource whose algorithm KitePDF cannot undo is not decoded as text: it counts as missing, as a file the zip does not hold.
- A book whose content documents are all encrypted reports it, through an `EpubFormatException` from `open` or a property that the app can read before it shows pages.

#### How to reproduce

A book with this `META-INF/encryption.xml`, and `OEBPS/chapter1.xhtml` replaced by random bytes:

```xml
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptedData>
    <enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
    <enc:CipherData><enc:CipherReference URI="OEBPS/chapter1.xhtml"/></enc:CipherData>
  </enc:EncryptedData>
</encryption>
```

`EpubDocument.open(bytes).pages[0].textContent()` gives the random characters.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:800-813`: `parseEncryption`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:255-258`: `chapterText`, which reads the stored bytes.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:611-621`: `programAt`, the one reader of the map.

#### Notes

Read from source.

### KP-73

**An XPS path ignores StrokeDashCap and StrokeEndLineCap, so a dotted line written with round dash caps draws nothing**

- Repo: KitePDF
- Labels: area:xps, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `XpsRenderer.drawPath` takes the cap of every dash from `StrokeStartLineCap` alone (`XpsRenderer.kt:195`). It never reads `StrokeDashCap` or `StrokeEndLineCap`, which ECMA-388 lists beside it on the `Path` element (§11.1), each with the default `Flat`.
- XAML writes a dotted line as zero-length dashes with round dash caps, `StrokeDashArray="0 2" StrokeDashCap="Round"`, and an XPS saved from WPF keeps the attributes. KitePDF strokes it with butt caps and dashes `[0, 2 × thickness]`, and a zero-length dash with a butt cap paints nothing (ISO 32000-1, 8.4.3.6, as every canvas follows). The line disappears.
- The other way round, a dashed path with a round start cap and the default flat dash cap gets round ends on every dash, so its dashes grow by the thickness. A path with a round end cap and a flat start cap loses its round end.
- MuPDF's XPS reader keeps a start cap, a dash cap and an end cap for each stroke (`fz_stroke_state`), and draws the dots.

#### What should happen

The dashes take `StrokeDashCap`, and the ends of an open path take `StrokeStartLineCap` and `StrokeEndLineCap`. With one cap in `KiteCanvas.strokePath`, a dashed path can at least take its dash cap, which is what most of its ends are.

#### How to reproduce

`fixtures/xps-dotted-dashcap.xps` is one page with:

```xml
<Path Data="M 20,150 L 380,150" Stroke="#FF000000" StrokeThickness="8" StrokeDashArray="0 2" StrokeDashCap="Round"/>
```

Expected: a row of black dots 8 units wide. KitePDF: an empty page. In `XpsDocumentTest`:

```kotlin
@Test fun dashCapRoundDrawsDots() {
    val canvas = render("""<Path Data="M10,48 L180,48" Stroke="#000" StrokeThickness="4" StrokeDashArray="0 2" StrokeDashCap="Round"/>""")
    val stroke = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
    assertEquals(listOf(0.0, 8.0), stroke.dashArray)
    assertEquals(1, stroke.lineCap)   // 0 today
}
```

#### Where

- `kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsRenderer.kt:191-198`.

#### Notes

Read from source. The `Triangle` cap of ECMA-388 also falls back to butt, which is a smaller gap.

### KP-74

**An XPS path whose stroke is a gradient, image or visual brush draws no stroke at all**

- Repo: KitePDF
- Labels: area:xps, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #284, #286

#### What happens

- `drawPath` reads the stroke as `property(el, "stroke", …)?.let { brushes.solid(it) } ?: return` (`XpsRenderer.kt:189`). `solid` returns null for anything but a `SolidColorBrush` (`XpsBrushes.kt:35-39`), so a `LinearGradientBrush`, `RadialGradientBrush`, `ImageBrush` or `VisualBrush` stroke returns before any paint.
- A gradient outline, which WPF and Office drawings allow, is such a stroke. The fill of the same path still paints, so the shape loses its outline without a warning.
- ECMA-388 §11.1 allows any brush in `Path.Stroke`, and MuPDF fills the stroked area with the brush. KitePDF already does this for PDF (#284) and SVG (#286) with `strokeOutline`.

#### What should happen

The brush fills the area of the stroke: `strokeOutline` of the stroke path with its thickness, caps, joins and dashes, filled through `XpsBrushes.fill`, as `strokeWithPattern` does for PDF.

#### How to reproduce

`fixtures/xps-gradient-stroke.xps`: a horizontal line 40 units thick with a red to blue `LinearGradientBrush` stroke. Expected: a red to blue bar. KitePDF: an empty page. In `XpsDocumentTest`:

```kotlin
@Test fun gradientStrokePaints() {
    val canvas = render("""<Path Data="M10,48 L180,48" StrokeThickness="8"><Path.Stroke>
      <LinearGradientBrush StartPoint="0,0" EndPoint="192,0"><LinearGradientBrush.GradientStops>
      <GradientStop Offset="0" Color="#FF0000"/><GradientStop Offset="1" Color="#0000FF"/>
      </LinearGradientBrush.GradientStops></LinearGradientBrush></Path.Stroke></Path>""")
    assertTrue(canvas.calls.any { it is RecordingCanvas.Call.Fill || it is RecordingCanvas.Call.Stroke })   // fails
}
```

#### Where

- `kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsRenderer.kt:189`.
- `kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:35-39`, `:52-70`.

#### Notes

Read from source. `docs/xps.md` lists gradient, image and visual brushes without saying that they fill only.

### KP-75

**An XPS ContextColor paints nothing, so CMYK and n-channel content disappears**

- Repo: KitePDF
- Labels: area:xps, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `xpsColor` knows `#AARRGGBB`, `#RRGGBB`, `sc#` and CSS colours (`XpsBrushes.kt:221-237`). A `ContextColor` value, ECMA-388's colour with an ICC profile for CMYK and n-channel spaces (§15.1), goes to `CssValues.color`, which returns null for it.
- A null colour makes `solid` null. A fill then pushes and pops a clip and paints nothing, since `fill` has no branch for a `solidcolorbrush` without a colour. A stroke returns. A glyph run with outlines fills nothing. A `GradientStop` with a `ContextColor` is dropped.
- An XPS made for print with CMYK colours writes them as `ContextColor`. All of that content vanishes from the page.
- MuPDF's `xps_parse_color` reads the alpha and the channel values after the profile URI, and paints them in DeviceGray, DeviceRGB or DeviceCMYK by their count when it does not use the profile.

#### What should happen

A `ContextColor` paints in its channel values: one channel as grey, three as RGB, four as CMYK through KitePDF's CMYK conversion (`AdobeCmyk`), with its alpha. Using the ICC profile is a further step.

#### How to reproduce

`fixtures/xps-contextcolor.xps` fills a rectangle with `Fill="ContextColor /Resources/swop.icc 1,0,1,1,0"` (alpha 1, C 0, M 1, Y 1, K 0, a red). Expected: a red rectangle. KitePDF: nothing. In `XpsDocumentTest`:

```kotlin
@Test fun contextColorFillPaints() {
    val canvas = render("""<Path Data="M0,0 L100,0 100,50 0,50Z" Fill="ContextColor /Resources/swop.icc 1,0,1,1,0"/>""")
    assertTrue(canvas.calls.any { it is RecordingCanvas.Call.Fill })   // fails
    assertNotNull(xpsColor("ContextColor /swop.icc 1,0,1,1,0"))       // fails
}
```

#### Where

- `kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:221-237` (`xpsColor`), `:35-39` (`solid`), `:57`, `:149-152` (gradient stops).

#### Notes

Read from source. `CssColors.parse` returns null for any value without `(` that is not a named colour (`CssColors.kt:22-44`).

### KP-76

**An XPS image brush reads the resolution of a PNG or a JFIF JPEG only, so a TIFF or an EXIF JPEG shows the wrong part of the image**

- Repo: KitePDF
- Labels: area:xps, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- An image brush's `Viewbox` is in units of 1/96 inch of the image, so its size in those units is its pixels times 96 over its resolution (ECMA-388 §13.4.1). `imageDpi` reads the resolution from a PNG `pHYs` chunk and a JPEG `JFIF` segment, and answers 96 dpi for everything else (`XpsBrushes.kt:243-278`).
- TIFF is one of the image formats every XPS consumer reads (ECMA-388 §9.1.5), and its resolution is in its `XResolution`, `YResolution` and `ResolutionUnit` tags. A JPEG from a camera carries its resolution in EXIF and often has no JFIF segment. Both read as 96 dpi.
- A producer writes the `Viewbox` from the true resolution. So a 300 dpi TIFF of 300 by 300 pixels has `Viewbox="0,0,96,96"`, and KitePDF, which thinks the image is 300 units wide, maps only its top left 32 percent into the viewport, enlarged about three times.
- MuPDF scales by `image->xres` and `image->yres`, which its TIFF, JPEG (EXIF included) and JPEG XR loaders fill.

#### What should happen

`imageDpi` reads the TIFF resolution tags and the EXIF `XResolution` and `YResolution` of a JPEG, as it reads `pHYs` and `JFIF`.

#### How to reproduce

`fixtures/xps-png-300dpi.xps` and `fixtures/xps-tiff-300dpi.xps` hold the same 300 by 300 pixel image at 300 dpi, its left half red and its right half blue, in an `ImageBrush` with `Viewbox="0,0,96,96"` over a 192 by 192 viewport. The PNG page shows both halves. The TIFF page shows only red, enlarged.

`L6/dpiport.py` runs a line-by-line port of `imageDpi` on the two embedded files:

```
Resources/img.png KitePDF imageDpi -> (299.9994, 299.9994)
Resources/img.tif KitePDF imageDpi -> (96.0, 96.0)        # the file says 300 dpi
```

Kotlin probe for `kitepdf-xps` tests: `assertEquals(300.0, imageDpi(tiffBytes).first, 0.01)` with the TIFF of the fixture fails with 96.0.

#### Where

- `kitepdf-xps/src/io/github/yuroyami/kitepdf/xps/XpsBrushes.kt:82-83` (the natural size) and `:243-278` (`imageDpi`).

#### Notes

Ran a Python port of `imageDpi` on the fixture bytes; the Kotlin was not run. Related gap: JPEG XR (`.wdp`), the fourth required format of §9.1.5, does not decode at all (`ImageFormat.sniff` has no JPEG XR), so such a brush draws the grey "unreadable image brush" box.

### KP-77

**KiteDoc does not recognise an SVG whose root element starts after byte 512, such as every Illustrator export with its entity DOCTYPE**

- Repo: KitePDF
- Labels: area:core, area:svg, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `formatOf` calls `SvgImage.isSvg`, which looks for `<svg` in the first 512 bytes only (`SvgImage.kt:1760-1763`). `docs/loading.md` gives the same rule.
- Illustrator's SVG export (CS through CC 2017, "Preserve Illustrator Editing Capabilities") starts with an XML declaration, a generator comment and a DOCTYPE with eight `ENTITY` declarations. The `<svg` tag of such a file starts at byte 735.
- `formatOf` then answers null, `open` tries the bytes as a damaged PDF, and the caller gets `KiteFormatException: not a readable PDF, EPUB, CBZ, SVG or XPS`. `SvgDocument.open` reads the same bytes without complaint. So a file picker, `openUrl` or `openBase64` fails on a common kind of SVG.
- An SVGZ file (gzip, `1F 8B`) is not recognised either, and `SvgDocument.open` does not inflate it, though `kitepdf-core` has an inflater.

#### What should happen

`isSvg` skips the prolog (XML declaration, comments, processing instructions and a DOCTYPE with its internal subset) and checks the first element, or reads a larger window, such as the 1,024 bytes the PDF check reads, and more when it is still inside the prolog. A gzip stream that inflates to an SVG opens as one.

#### How to reproduce

`fixtures/illustrator-doctype.svg` is the standard Illustrator header with a red rectangle (`<svg` at offset 735). Probe for `kitepdf` tests:

```kotlin
@Test fun anIllustratorSvgIsAnSvg() {
    val bytes = java.io.File(".../L6/fixtures/illustrator-doctype.svg").readBytes()
    assertEquals(KiteDocFormat.Svg, KiteDoc.formatOf(bytes))   // null
    KiteDoc.open(bytes)                                         // throws KiteFormatException
}
```

#### Where

- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:1760-1763`.
- `kitepdf/src/io/github/yuroyami/kitepdf/document/KiteDoc.kt:62-72`, `:98-101`.
- `docs/loading.md`, the detection table.

#### Notes

Measured the offset with Python on the fixture. The 512-byte window is documented, so this is a limit that real files cross, not a regression.

The lead hit this while checking another finding: a 600-byte SVG whose DOCTYPE fills the first 520 bytes made `KiteDoc.open` throw `KiteFormatException: not a readable PDF, EPUB, CBZ, SVG or XPS`.

### KP-78

**A comic drops its TIFF, AVIF and JPEG XL pages, though KitePDF decodes all three formats**

- Repo: KitePDF
- Labels: area:cbz, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #129

#### What happens

- A comic page is an entry whose extension is in `jpg, jpeg, png, gif, bmp, webp` (`CbzDocument.kt:13-14`). `KiteDoc.looksLikeCbz` has its own copy of the same set (`KiteDoc.kt:187`).
- `KiteImageData.fromEncodedImage` decodes TIFF, AVIF, JPEG XL and JPEG 2000 too (`KiteImageData.kt:481-485`), and `ImageDims` reads TIFF sizes for comic pages (`ImageDims.kt:57-74`), but a `.tif` entry never reaches either.
- An archive of `.tif`, `.avif` or `.jxl` pages: `KiteDoc.formatOf` answers null and `CbzDocument.open` throws `ZIP archive holds no image entries`. A mixed archive silently loses those pages, and the page numbers of ComicInfo bookmarks then point at the wrong images.
- The CHANGELOG of 0.10.0 says "TIFF images decode wherever an image is read (comics, EPUB and SVG)". Converting comic collections to JPEG XL or AVIF is a common way to save space.

#### What should happen

The page extensions follow what the decoders read: `tif`, `tiff`, `avif`, `jxl` and `jfif` at least, from one set that both `CbzDocument` and `KiteDoc` use.

#### How to reproduce

`fixtures/comic-tiff.cbz` holds three TIFF pages. `CbzDocument.open(bytes)` throws `KiteFormatException("ZIP archive holds no image entries")`, and `KiteDoc.formatOf(bytes)` is null.

#### Where

- `kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/CbzDocument.kt:13-14`, `:81-85`.
- `kitepdf/src/io/github/yuroyami/kitepdf/document/KiteDoc.kt:187-199`.

#### Notes

Read from source.

### KP-79

**An image of more than 40 megapixels draws as a grey box whenever it is drawn at more than half its size, so a long comic strip shows grey tiles at fit-width**

- Repo: KitePDF
- Labels: area:core, area:cbz, area:renderer, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #381, #386

#### What happens

- `imageSampling` averages an image down only in a direction where it draws at half its size or less (`ImageSampling.kt:76-80`, `shrinkFactor`). Drawn at more than half its size, `toSampledRgbaBytes` calls `toRgbaBytes`, which returns null above 40 megapixels (`ImageRaster.kt:38-39`). Every canvas then draws its grey placeholder.
- #381 moved the limit to the shrunk output, so a large scan drawn small now draws. The same scan drawn at more than half its size is still a grey box, after it was decoded in full.
- A webtoon chapter is often one strip, such as 800 by 60,000 pixels (48 megapixels). The viewer fits it to a 1,080 pixel screen at 1.35 times its size, keeps a capped base bitmap, and draws the part on screen in tiles at full resolution (`KiteDocViewSpecs.kt:249-262`). Each tile draws the image at 1.35 times its size, so each tile is the grey placeholder over the base bitmap. A 600 dpi A3 scan (69.6 megapixels) does the same once the reader zooms in.
- The decoded strip (144 MB of RGB) is larger than the comic's 64 MB cache of scans, so `CbzImageCache.put` drops it and every tile reads and decodes the whole entry again before it draws grey (`CbzPage.kt:54-62`, `CbzImageCache.kt:27`).

#### What should happen

An image above the limit still draws: a canvas converts only the rows and columns under its clip, or keeps the limit by averaging down one more power of two, which looks better than a grey box. A comic page that is drawn larger than its pixels is decoded once, not once per tile.

#### How to reproduce

Probe for `kitepdf-core` tests (the constructor is internal):

```kotlin
@Test fun aLargeImageDrawnAtItsOwnSizeStillHasPixels() {
    val w = 1000; val h = 50_000                     // 50 megapixels, a comic strip
    val image = KiteImageData(
        width = w, height = h, bitsPerComponent = 8, colorSpace = "DeviceRGB",
        kind = KiteImageData.Kind.RAW, encodedBytes = ByteArray(0),
        pixelBytes = ByteArray(w * h * 3), resolvedColorSpace = KiteColorSpace.DeviceRGB,
    )
    // 1.35 times its size, as a 1,080 px wide viewer draws an 800 px strip.
    val ctm = KiteMatrix(w * 1.35, 0.0, 0.0, -h * 1.35, 0.0, h * 1.35)
    assertNotNull(image.toSampledRgbaBytes(imageSampling(w, h, ctm, interpolate = true)))   // null today
}
```

End to end: a CBZ with one 1,000 by 50,000 JPEG page, opened in `KiteDocView` at fit-width on the desktop, shows grey on screen.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageRaster.kt:31-39`, `:115-125`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageSampling.kt:54-80`, `imageSampling` and `shrinkFactor`.
- `kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/CbzPage.kt:54-62`, `kitepdf-cbz/src/io/github/yuroyami/kitepdf/cbz/CbzDocument.kt:106`.

#### Notes

Read from source; the probe was not run. On a phone the strip may fail earlier: `fromEncodedImage` holds the decoder's ARGB array and the RGB copy at once (`KiteImageAdapter.kt:30-48`), 336 MB for 48 megapixels.

### KP-80

**SkiaCanvas weighs a luminosity soft mask by Rec. 709, every other canvas by PDF's 0.30, 0.59, 0.11, and one mask kind serves PDF and SVG, which need different weights**

- Repo: KitePDF
- Labels: area:renderer, backend:skia, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- `SkiaCanvas.applySoftMask` turns a luminosity mask into alpha with `ColorFilter.luma` (`SkiaCanvas.kt:826-833`). Skia documents that filter as `{0,0,0, clamp(0.2126*r + 0.7152*g + 0.0722*b)}` (`SkLumaColorFilter.h`).
- The other canvases use the PDF weights of ISO 32000-1, 11.6.5.2 and 10.3.2: 77, 150 and 29 over 256 on AWT, CoreGraphics and Canvas2D, 0.30, 0.59 and 0.11 on Android and `KiteRasterCanvas`, 0.299, 0.587 and 0.114 in `ComposeCanvas`.
- A mask group in a colour therefore gates differently on `kitepdf-skia-renderer`, which promises "the same result on every target". A pure red group gives a mask value of 0.21 on Skia and 0.30 elsewhere; a pure green one 0.72 against 0.59. A black square under a red luminosity mask over white reads 201 on Skia, while Poppler gives 178 and the KitePDF canvases about 179.
- `KiteCanvas` has one `SoftMask.Kind.Luminosity`, and SVG uses it for `<mask>` (`SvgImage.kt:971`). CSS Masking 1, 7.1 computes a luminance mask with the `luminanceToAlpha` weights of Filter Effects (0.2125, 0.7154, 0.0721), which `SvgFilter.kt:317` already uses. So on every canvas but Skia, a coloured SVG mask has the PDF weights, and on Skia a PDF mask has the CSS weights. The KDoc of `applySoftMask` cites only §11.6.5.2.

#### What should happen

A luminosity mask carries its weights through the contract, as two kinds or a parameter, and each canvas applies them: a colour matrix with the PDF weights for a PDF mask on Skia, and the CSS weights for an SVG mask everywhere.

#### How to reproduce

`fixtures/red-luminosity-mask.pdf`: `q /GS1 gs 0 g 0 0 200 200 re f Q` under a luminosity mask whose group is `1 0 0 rg 0 0 200 200 re f`. Poppler: 178. Probe for `kitepdf-skia-renderer`, `test@jvm`:

```kotlin
@Test fun aRedLuminosityMaskGatesByPdfLuminosity() {
    val surface = Surface.makeRasterN32Premul(4, 4)
    surface.canvas.clear(Color.WHITE)
    val canvas = SkiaCanvas(surface.canvas)
    val square = KitePath.Builder().apply { rectangle(0.0, 0.0, 4.0, 4.0) }.build()
    canvas.beginPage(4.0, 4.0, KiteMatrix.IDENTITY)
    canvas.applySoftMask(
        SoftMask.Kind.Luminosity, KiteRectangle(0.0, 0.0, 4.0, 4.0), KiteMatrix.IDENTITY,
        render = { canvas.fillPath(square, KiteMatrix.IDENTITY, RgbColor.BLACK, evenOdd = false) },
        renderMask = { it.fillPath(square, KiteMatrix.IDENTITY, RgbColor(1.0, 0.0, 0.0), evenOdd = false) },
    )
    canvas.endPage()
    val red = Color.getR(Bitmap.makeFromImage(surface.makeImageSnapshot()).getColor(1, 1))
    assertTrue(kotlin.math.abs(red - 178) <= 2, "got $red")   // about 201 today
}
```

#### Where

- `kitepdf-skia-renderer/src/io/github/yuroyami/kitepdf/skia/SkiaCanvas.kt:826-833`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteCanvas.kt:218-222`, `:224-251`: one `Luminosity` kind.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:971`.
- The PDF weights: `AwtCanvas.kt:1093`, `CoreGraphicsCanvas.kt:1068`, `Canvas2dCanvas.kt:731`, `AndroidNativeCanvas.kt:912`, `KiteRasterCanvas.kt:221`, `SoftMaskFilter.kt:28-33`.

#### Notes

Read from source; Skia's formula is from its public header. Ran Poppler on the fixture. A grey mask group, the common case, gives the same value with either weights, which is why the corpus does not show it.

### KP-81

**The per-event deadline counts the time an `app.alert` or `app.response` dialog waits for the reader**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`deadlinePassed()` compares the wall clock with `eventStartedAt`, which `evaluate` sets when the event starts. `app.alert` and `app.response` call `onAlert` and `onResponse` from inside the script, on the script thread, and these hooks return the reader's answer, so a host blocks in them until the reader has answered. The clock keeps running while the dialog is open. When the hook returns after more than `budgetMillis` (5 seconds by default), the next interrupt poll stops the script.

A script that asks for a name and then works with the answer loses that work whenever the reader takes more than five seconds to type:

```js
var name = app.response('Your name?');
for (var i = 0; i < this.numFields; i++) { /* fill fields from name */ }
```

Combined with the finding above, the stopped event then also takes the previous event's result.

#### What should happen

Time spent in a dialog hook does not count against the event's budget. Acrobat and Chrome have no deadline at all, so a dialog never stops a script there.

#### How to reproduce

A `kitepdf-javascript` test, with the runner's own clock moved by the dialog. Document open script `var r = app.response('Name?'); for (var i = 0; i < 5000; i++) {} getField('log').value = r;` and a text field `log` (as in `RunnerLifecycleTest.pdf`):

```kotlin
var now = 0L
PdfScriptRunner(doc, onResponse = { now += 6_000; "Ada" }, clock = { now }).use { runner ->
    runner.runDocumentOpen()
    assertEquals("Ada", runner.formState.value("log"))   // today "", the script was stopped after the dialog
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:698` `eventStartedAt = now()`, `:718` `deadlinePassed`.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:284` `alert`, `:293` `response`.

#### Notes

Read from source. Whether the stop lands depends on the script doing a few hundred calls or loop steps after the dialog, since QuickJS polls every 256 of them.

### KP-82

**Closing a `PdfScriptRunner` from one of its own callbacks throws and leaves its engine open for good**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`onAlert`, `onConsole`, `onRequest` and `onResponse` run on the script thread while a script waits for them. A host that answers `PdfScriptRequest.Document("closeDoc")` by closing the document, and so the runner, calls `close()` from there. `close()` defers the close while a call is busy only where the work runs in place (the web) or the engine is the caller's; with a script thread of its own it goes straight to `closeHere()`. `thread.call` sees that it already runs on that thread and runs the block in place, and `KiteJsScriptEngine.close()` reaches `QuickJsKiteJs.close()`, which throws `JsEngineError("this engine is running a script; close it once that call has returned")` because the script that called the hook is still on the stack. The `finally` around it sets `openEngine = null` anyway.

So `close()` throws into the host's callback, and the engine is never closed: its QuickJS runtime, up to the 512 MiB `MEMORY_LIMIT` of native memory, stays allocated, and `QuickJsHost` keeps the engine in its registry, so the collector never takes it either. The script that asked is then stopped by the host failure, and its event takes the previous event's result (see above).

#### What should happen

A close from the script thread during a call waits for the call to end, as the paused case on the web already does with `closeWaits`, and the engine is closed when the call returns.

#### How to reproduce

```kotlin
lateinit var runner: PdfScriptRunner
runner = PdfScriptRunner(doc, onRequest = { if (it is PdfScriptRequest.Document) runner.close() })
runner.run(PdfAction.JavaScript("this.closeDoc()", PdfDictionary(emptyMap())))
assertTrue(runner.failures.isEmpty())
// Today runner.failures holds "action: this engine is running a script; close it once that call has
// returned", and the engine behind it is never closed (read from source: nothing calls close() on it again).
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:779` `close` (the deferral at `:783` excludes a runner with its own thread), `:790` `closeHere` (`:801` `openEngine?.close()`, `:803` `openEngine = null` in `finally`).
- KiteJS `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:197` `close` (`depth > 0` throws), `:203` the only `unregister` besides a failed start.

#### Notes

Read from source. `EpubScriptRunner.close` has the same structure; I did not trace its callbacks (`onConsole`, `onNavigate`) through `EpubScriptSession`.

### KP-83

**`PdfScriptRunner` keeps every failure for as long as the document is open, and copies the whole list on each one**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:perf
- Severity: medium
- Confidence: Confirmed

#### What happens

`recordFailure` appends to `failureList` and then copies the whole list into `failureCopy`. Nothing ever removes an entry. A timer that throws each time it runs, for example `app.setInterval("update()", 100)` where `update` is not defined, adds a failure ten times a second for as long as the viewer pumps it, and each addition copies the list again, so the cost of each failure grows with the number before it.

Each entry is a `KiteScriptException` whose cause is a KiteJS `JsError`, and `JsError.value` keeps a handle to the thrown error object inside the engine. So the list also pins every thrown error object in the QuickJS heap, which counts against the engine's 512 MiB `MEMORY_LIMIT`: when it is reached, every script of the document fails with "the engine ran out of memory".

The EPUB side fixed exactly this: `EpubScriptSession` keeps the last `MAX_FAILURES` (100) entries, "since a timer that throws each time it runs would otherwise grow it for as long as the book is open".

#### What should happen

The PDF runner keeps a bounded number of failures, as the EPUB session does.

#### How to reproduce

```kotlin
@Test
fun a_timer_that_always_throws_keeps_a_bounded_list(): TestResult = scriptTest {
    var now = 0L
    PdfScriptRunner(doc, clock = { now }).use { runner ->
        runner.run(PdfAction.JavaScript("app.setInterval('update()', 10)", PdfDictionary(emptyMap())))
        repeat(10_000) { now += 10; runner.pumpTimers(now) }
        assertTrue(runner.failures.size <= 100)   // today 10,000
    }
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:168` `failureList`, `:174` `recordFailure`.
- Compare `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:377` `recordFailure` and `:1048` `MAX_FAILURES`.
- `kitejs-api/src/io/github/yuroyami/kitejs/api/JsException.kt` `JsError.value` (KiteJS), which holds the engine handle.

#### Notes

Read from source.

### KP-84

**`util.printf` gets the separator styles 2 and 3, `%d`, `%x` and a bare `%f` wrong**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

I ran KitePDF's bundled `AcrobatApi` source in Node with a stub host:

| Call | KitePDF | Expected |
|---|---|---|
| `util.printf("%,2.2f", 1234.56)` | `1.234.56` | `1.234,56` (Acrobat, nDecSep 2: "Period separated, comma decimal point") |
| `util.printf("%,3.2f", 1234.56)` | `1234.56` | `1234,56` (Acrobat, nDecSep 3: "No separator, comma decimal point") |
| `util.printf("%d", 2.7)` | `3` | `2` (Acrobat, d: "Integer (truncating if necessary)"; PDFium converts with `ToInt32`) |
| `util.printf("%x", 255.9)` | `100` | `ff` (Acrobat, x: "Integer (truncating if necessary) and formatted in unsigned hexadecimal notation") |
| `util.printf("%x", -1)` | `-1` | `ffffffff` (unsigned; PDFium: `ToInt32`, then `%x`) |
| `util.printf("%f", 1.5)` | `1.50` | `1.500000` (PDFium hands `%f` to `swprintf`, whose default precision is 6) |

`groupThousands` picks the group separator for styles 2 and 3 but never changes the decimal point, so style 2 prints two periods and the output reads as a different number. `%d` and `%x` use `Math.round`, and a bare `%f` defaults to two digits.

The quotes come from the Acrobat JavaScript API reference, `util.printf`. PDFium does not implement nDecSep and prints such a specification unchanged, so for the first two rows Acrobat is the only reference; KitePDF parses `%,n` on purpose, and gets 2 and 3 wrong. A date or age that a form computes with `util.printf("%d", days / 365.25)` shows one more than Acrobat whenever the fraction is a half or more.

#### What should happen

The documented nDecSep styles 0 to 3 switch both the group separator and the decimal mark; `%d` and `%x` truncate; `%x` formats as unsigned; `%f` without a precision prints six digits.

#### How to reproduce

`node audit-repro/L7/acro/harness.js 'util.printf("%,2.2f", 1234.56)' 'util.printf("%d", 2.7)'`, or in a `kitepdf-javascript` test:

```kotlin
PdfScriptRunner(doc).use { runner ->
    val run = { s: String -> runner.run(PdfAction.JavaScript(s, PdfDictionary(emptyMap()))) }
    assertEquals("1.234,56", run("util.printf('%,2.2f', 1234.56)"))   // today 1.234.56
    assertEquals("2", run("util.printf('%d', 2.7)"))                  // today 3
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AcrobatApi.kt:142` `printf`, `:151` and `:156` `Math.round`, `:153` the default precision, `:223` `groupThousands`.
- PDFium `fxjs/cjs_util.cpp` `CJS_Util::printf` (`ToInt32Reentrant` for `d` and `x`, `swprintf` for `f`).

#### Notes

Ran in Node. Not reported: `%%` prints `%%`, but PDFium's printf does the same, so it matches the stated target.

### KP-85

**`AFNumber_Format` prints a minus sign for negative style 1 and rounds half a cent down**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`formatNumber` puts a minus sign in front for negative styles 0 and 1. In PDFium (and in Acrobat's AForm.js, which it follows) style 1 means "red, no sign": only style 0 adds the sign, styles 2 and 3 add parentheses, and styles 1 and 3 color the field red. KitePDF prints `-$1,234.50` in red where Chrome prints `$1,234.50` in red.

`formatNumber` rounds with `Math.abs(n).toFixed(nDec)`, which rounds the binary double. PDFium adds `kDoubleCorrect` (1e-15) before it rounds whenever there are decimals, so a value that is a half cent in decimal but just below it in binary rounds up there:

| Value | KitePDF | PDFium |
|---|---|---|
| `1.005`, `AFNumber_Format(2, 0, 0, 0, "", true)` | `1.00` | `1.01` |
| `2.675`, `AFNumber_Format(2, 0, 0, 0, "$", true)` | `$2.67` | `$2.68` |
| `-1234.5`, `AFNumber_Format(2, 0, 1, 0, "$", true)` | `-$1,234.50` | `$1,234.50`, red |

The class says "A script that works in Chrome's viewer is meant to work here", and `AFNumber_Format` is the most common format script in real forms.

#### What should happen

Style 1 adds no sign, and rounding follows PDFium's half-cent correction.

#### How to reproduce

`node .../L7-js/acro/harness.js 'event("-1234.5", function(){ AFNumber_Format(2, 0, 1, 0, "$", true) })' 'event("2.675", function(){ AFNumber_Format(2, 0, 0, 0, "$", true) })'`, or a field with format script `AFNumber_Format(2, 0, 1, 0, '$', true)` and value `-1234.5`: `runner.formattedValue(name)` answers `-$1,234.50`.

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:83` `formatNumber`, `:88` `toFixed`, `:97` `case 0: case 1:`.
- PDFium `fxjs/cjs_publicmethods.cpp` `AFNumber_Format` (`if (iDec > 0) dValue += kDoubleCorrect;`, `if (iNegStyle == 0) Value.InsertAtFront(L'-')`).

#### Notes

Ran in Node; PDFium source read. Not reported: PDFium draws no separator for style 4 in `AFNumber_Format`, while KitePDF and Acrobat use an apostrophe; KitePDF matches Acrobat there.

### KP-86

**`AFSimple_Calculate` skips empty fields and does not round, so averages, minimums and decimal sums differ from Chrome**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

KitePDF skips a field whose value does not read as a number (`if (n === null) continue;`), so it does not count for `AVG` and does not take part in `MIN` or `MAX`. PDFium reads an empty text field as 0 and counts it, and it rounds the result to six decimals (`floor(dValue * 10^6 + 0.49) / 10^6`) before it stores it. Run in Node against KitePDF's bundled source:

| Fields | Call | KitePDF | PDFium |
|---|---|---|---|
| `0.1`, `0.2` | `AFSimple_Calculate("SUM", ...)` | `0.30000000000000004` | `0.3` |
| `10`, empty | `AFSimple_Calculate("AVG", ...)` | `10` | `5` |
| `10`, empty | `AFSimple_Calculate("MIN", ...)` | `10` | `0` |

The sum is the field's stored value, so it is what the editor saves and what a total without a format script shows. An invoice's totals row is the case the class comment names: "The totals row of every invoice form".

#### What should happen

The same results as PDFium for the same fields.

#### How to reproduce

`node .../L7-js/acro/harness.js 'fields.a="0.1", fields.b="0.2", event("", function(){ AFSimple_Calculate("SUM", ["a","b"]) })'`, or `FormScriptTest.invoicePdf()` with `a = 0.1` and `b = 0.2`: `runner.formState.value("total")` is `0.30000000000000004`.

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/AformApi.kt:254` `AFSimple_Calculate`, `:268` the skip, `:280` the store.
- PDFium `fxjs/cjs_publicmethods.cpp` `AFSimple_Calculate`.

#### Notes

Ran in Node; PDFium source read.

### KP-87

**CBZ, EPUB, SVG and XPS images are always decoded at full size, never through decodeScaled**

- Repo: KitePDF
- Labels: area:render, kind:perf
- Severity: medium
- Confidence: Confirmed
- Related: #381 (the same fix, done for PDF JPEG/JPX only), #386

#### What happens
`KiteImageData.fromEncodedImage(bytes)`, the entry point for every non-PDF image
(EPUB `img`/SVG `image`, CBZ pages, XPS image brushes), calls
`ImageKodec.decode(bytes)` and wraps the result with `toKiteImageData()`, which
materialises the full-size interleaved RGB `pixelBytes` plus a full-size alpha
plane. The reduced-decode machinery that the PDF loader uses (the lazy
`KiteImageSamples` kept encoded, decoded per draw at the drawn size through
`reducedFor` / `decodeReduced`, #381) is never used here. So a 10000x10000 JPEG
scan in a CBZ, or a large image in an EPUB drawn into a small inline box,
produces a ~300 MB `pixelBytes` array on each decode, even when the image is
only ever drawn small. A cache keeps it at full size when it fits the cache's
budget, and decodes it in full again on the next draw when it does not. `ImageKodec.decodeScaled` / `decodeReduced` exist and
would avoid the full-size buffer for JPEG and JPEG 2000 sources, but
`fromEncodedImage` does not reach them.

Callers confirm the images are drawn small: `CbzPage` sizes 1 px = 1 pt and lets
the viewer scale; `EpubDocument`/`SvgImage` draw into layout boxes. The draw path
(`ImageRaster.toShrunkRgbaBytes`) then shrinks from the already full-size
`pixelBytes`, so the full-size working set exists regardless of the drawn size.

#### What should happen
`fromEncodedImage` for a JPEG or JPEG 2000 source should keep the encoded bytes
and decode at the drawn size (the lazy `KiteImageSamples` path the PDF loader
uses), or `decodeScaled` to a bounded size when the format is not
reduction-capable, so the working set follows the output size as #381 requires
for PDF. For CBZ, where zoom to full resolution is wanted, a reduced form keyed
by sampling (as #381's PDF cache does) still avoids holding full size when zoomed
out.

#### How to reproduce
Open a CBZ whose page is a 10000x10000 baseline JPEG, on a JVM with a small heap,
and render it fit-to-screen. Expected (per the #381 contract): working set scaled
to the output. From the code: `fromEncodedImage` -> `ImageKodec.decode` allocates
the full 10000x10000x3 (~286 MB) `pixelBytes` at load, before any shrink.

Measured on the JVM at `c4e7574`: a CBZ with one 6000 by 4000 baseline JPEG
(2.5 MB), drawn with `page.renderTo(RecordingCanvas(), KiteMatrix(0.05, 0, 0, 0.05, 0, 0))`,
allocated 210 MiB on the drawing thread, for an output that needs about 175 KiB.
Nothing was kept afterwards: the decoded scan is larger than the 64 MiB budget of
`CbzImageCache`, which keeps no image larger than its budget, so every redraw of
that page (a zoom, a scroll back) decodes the whole scan again. A scan under the
budget is kept at full size instead.

#### Where
- `kitepdf-core/.../render/KiteImageData.kt:481-499` (`fromEncodedImage`, full `ImageKodec.decode`), `render/KiteImageAdapter.kt:222-241` (`toKiteImageData`, full `pixelBytes`).
- Callers: `kitepdf-cbz/.../CbzPage.kt:60`, `kitepdf-epub/.../EpubDocument.kt:1603`, `kitepdf-svg/.../SvgImage.kt:395` and `:1439`, `kitepdf-xps/.../XpsBrushes.kt:81`.

#### Notes
Read from source, and measured on the JVM for CBZ. #381 fixed this for PDF JPEG/JPX (lazy `samples`); the non-PDF
`fromEncodedImage` path was not changed and has no lazy/reduced route.

### KP-88

**The object cache keeps a copy of every stream the document has read, with no budget and no drop call**

- Repo: KitePDF
- Labels: area:pdf, kind:perf
- Severity: medium
- Confidence: Confirmed
- Related: #116, #218

#### What happens

- `Parser` copies the bytes of each stream out of the file (`copyOfRange`) into `PdfStream.rawBytes`.
- `PdfDocument.resolve` keeps every object it resolves in `objectCache` for the life of the document, and nothing removes an entry.
- So every image, content stream, font program and form that a render, a thumbnail or a text pass touched stays in memory a second time, next to the file's own `ByteArray`. Paging once through a 100 MB scanned PDF adds about 100 MB that the app cannot free.
- The image, operation and font caches have budgets and `drop...` calls "when the app runs low on memory". This cache, usually the largest, has neither.
- MuPDF also caches parsed objects, but reads a stream's data from the file each time it loads it.

#### What should happen

A stream keeps its offset and length into the file and reads its bytes when asked, or the cache gives up stream objects past a budget, with a drop call like the others.

#### How to reproduce

Render every page of a large scanned PDF to a `KiteRasterCanvas(64, 64)`, call the three `drop...` functions, run `System.gc()`, and compare the used heap with the size of the file. The difference is about the sum of the stream lengths.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/Parser.kt:227` (the copy).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/PdfObject.kt:123-126` (`PdfStream.rawBytes`).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:110` (`objectCache`), `:769-796` (`resolve`, the only write at `:791`), `:338`, `:417`, `:493` (the drop calls of the other caches).

#### Notes

Read from source.

### KP-89

**An image `/Decode` entry of more than 308 digits makes `roundToInt` throw, and the whole page fails instead of the image**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

- The lexer reads a real of more than 15 significant digits with `toDoubleOrNull`, so a real of 309 digits becomes `Infinity`. `readDecode` keeps it.
- `unpackIndexed` maps each sample with `(dmin + sample * (dmax - dmin) / maxval).roundToInt()`. With `dmax` infinite, sample 0 gives `0 * Infinity`, which is NaN, and `roundToInt()` throws IllegalArgumentException ("Cannot round NaN value"). `unpackRgbDecode` does the same through `coerceIn(0.0, 1.0)`, which keeps NaN.
- Nothing between `toRgbaBytes` and the page catches it: `KiteBitmapCache.getOrPut`, `ComposeCanvas.drawImage`, `PageRenderer.paintImage` and `render` let it through. The viewer's raster guard turns the whole page into a placeholder, and Vectorized mode paints the page blank. An app that calls `renderTo` gets the exception.
- CONTRIBUTING asks that a corrupt image become a skip or a placeholder and never stop the page.

#### What should happen

A non-finite `/Decode` entry falls back to the default range of the colour space (ISO 32000-1, 8.9.5.2, Table 89), and the unpack paths never round a NaN.

#### How to reproduce

```
5 0 obj << /Type /XObject /Subtype /Image /Width 1 /Height 1 /BitsPerComponent 8
          /ColorSpace [/Indexed /DeviceRGB 0 <FF0000>] /Decode [0 1000...0.0] /Length 1 >>
stream
\x00
endstream
```

with 309 zeros in the second `/Decode` entry, and page content `q 100 0 0 100 50 50 cm /Im1 Do Q`. `page.renderTo(KiteRasterCanvas(200, 200))` throws IllegalArgumentException.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/Lexer.kt:230-236` (a long real through `toDoubleOrNull`).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:546-556` (`readDecode`).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/ImageRaster.kt:441` (`unpackIndexed`), `:634` (`unpackRgbDecode`), `:79-80` (the dispatch to `unpackIndexed`).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:2073-2083` (`paintImage`, no guard).

#### Notes

Read from source. `roundToInt` on NaN throws on every target (Kotlin stdlib). The other `roundToInt` calls on document values that I checked are guarded by `isFinite` or a clamp of finite input; this survey covered the 93 `roundToInt` and `roundToLong` calls, not every `toDoubleOrNull` site.

### KP-90

**The consumer check cannot see a KitePDF module that the local publish leaves out, and it checks only the JVM variant of three artifacts**

- Repo: KitePDF
- Labels: area:ci, kind:test-gap
- Severity: medium
- Confidence: Confirmed
- Related: #212, #556

#### What happens

- `consumer-test/build.gradle.kts` resolves the version the source tree is at, `settings.publishing.version` of `library.module-template.yaml`, which is `0.13.0`.
- `consumer-test/settings.gradle.kts` lists `mavenLocal()` and then `mavenCentral()` for every module.
- Maven Central already holds `0.13.0` of every KitePDF artifact (a GET of `kitepdf-pdf-0.13.0.module` and `kitepdf-compose-viewer-0.13.0.module` answered 200). The tree keeps that version until the next release commit.
- Gradle takes each module from the first repository that has it. So a KitePDF module or variant that `./kotlin publish mavenLocal --transitive ...` does not write, or writes under another coordinate, comes from Central's 0.13.0, built by the old Gradle build, and the test passes. `ConsumerSmokeTest` never checks where an artifact came from, and `KitePDF.VERSION` is `0.13.0` in both.
- The test applies `kotlin("jvm")`, so it resolves only the JVM variant, and only of `kitepdf`, `kitepdf-native-renderer` and `kitepdf-javascript` (CI publishes only those three, with their dependencies). No pre-release check resolves the Android AAR, the common metadata a `commonMain` dependency uses (the way the README tells users to add KitePDF), or any variant of `kitepdf-compose-viewer`, `kitepdf-skia-renderer`, `kitepdf-net` or `kitepdf-media`.
- The next release is the first one that the Kotlin Toolchain publishes. The [Unreleased] part of CHANGELOG.md lists the changes to the published files: classes of the AAR in `libs/<artifact>-jvm.jar`, no `aar-metadata.properties`, a root POM that depends on `-jvm`, renamed wasm artifacts, no native `-metadata.jar`. None of them is checked by a consumer before the release.

#### What should happen

- The consumer resolves KitePDF's own coordinates from Maven Local only (Gradle's `exclusiveContent { forRepository { mavenLocal() }; filter { includeGroupAndSubgroups("io.github.yuroyami") ... } }` for the KitePDF modules), or CI publishes a version that is not on Central (for example `0.13.1-ci`). A missing module then fails the build.
- The consumer also compiles a KMP `commonMain` with an Android target and the viewer (or a second consumer does), so the metadata, the AAR and the viewer's POM are resolved once before a release.

#### How to reproduce

1. In a checkout, publish to Maven Local as CI does, then delete one transitive module from `~/.m2`, for example `rm -r ~/.m2/repository/io/github/yuroyami/kitepdf-svg*`.
2. `cd consumer-test && ./gradlew test --info | grep -i "kitepdf-svg"`.
3. The build passes, and `--info` shows `kitepdf-svg-jvm-0.13.0.jar` downloaded from `repo.maven.apache.org`.

#### Where

- `consumer-test/build.gradle.kts:7` (the version), `:13` to `:16` (the three artifacts), `:21`
- `consumer-test/settings.gradle.kts:24` to `:26` (the repository order)
- `.github/workflows/ci.yml:323` (`./kotlin publish mavenLocal --transitive -m kitepdf -m kitepdf-native-renderer -m kitepdf-javascript`)
- `CONTRIBUTING.md`, Consumer check ("So a broken POM, a missing dependency or a packaging mistake fails there")

#### Notes

Read from source, plus two GETs to repo1.maven.org that show 0.13.0 is published.

### KP-91

**The JVM and Android artifacts of KitePDF are Java 21 class files, and no page says so**

- Repo: KitePDF
- Labels: area:docs, kind:docs
- Severity: medium
- Confidence: Confirmed
- Related: ImageKodec #85

#### What happens

- `common.module-template.yaml` sets `settings.jvm.release: 21` ("The JVM and Android bytecode target") for every module, so the published jars hold class files of version 65.
- README.md, `docs/getting-started.md`, `docs/platforms.md` and CHANGELOG.md never name a Java version. `docs/index.md` says "The same artifacts work in a plain Android or JVM project", and the README offers KitePDF for "a server or a command-line tool".
- The Gradle module metadata does not say it either: the root `.module` of 0.13.0 names no `org.gradle.jvm.version` for its JVM variants, and the toolchain's metadata writer has no such attribute (no `TargetJvmVersion` class in its `dependency-resolution-jvm.jar`, and no `org.gradle.jvm.version` string in its jars). So for the artifacts that the toolchain publishes, Gradle has nothing to refuse the dependency with.
- A server on Java 17 (the baseline of Spring Boot 3) that follows the README fails when it first loads a class: `UnsupportedClassVersionError: io/github/yuroyami/kitepdf/PdfDocument has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 61.0`. A Kotlin consumer with `jvmTarget` 17 that calls the public inline `KiteLock.withLock` gets "Cannot inline bytecode built with JVM target 21 into bytecode that is being built with JVM target 17".
- The two libraries under it target Java 11: ImageKodec since #85, KiteJS ("JVM (bytecode 11)" in its README). `consumer-test` runs on `jvmToolchain(21)`, so it cannot notice.

#### What should happen

Either KitePDF targets the release that its code needs (ImageKodec chose 11), or README.md and `docs/platforms.md` say that the JVM and Android artifacts need Java 21, and an Android toolchain that reads Java 21 class files.

#### How to reproduce

`javap -v -cp kitepdf-pdf-jvm-<version>.jar io.github.yuroyami.kitepdf.PdfDocument | grep major` prints `major version: 65`. Run any `PdfDocument.open` call on a Java 17 JVM.

#### Where

- `common.module-template.yaml:11`
- `README.md`, Install and Platforms; `docs/platforms.md`, Targets; `docs/getting-started.md`, step 1
- `consumer-test/build.gradle.kts:21`

#### Notes

Read from source, and from the published 0.13.0 module metadata. `javap -v` on `PdfDocument.class` of a local JVM build at `c4e7574` prints `major version: 65`.

### KP-92

**NOTICE leaves out the Unicode data, the Brotli dictionary, HarfBuzz's shaping tables and the HTML entity table that KitePDF bundles**

- Repo: KitePDF
- Labels: area:docs, kind:docs
- Severity: medium
- Confidence: Confirmed
- Related: #307

#### What happens

README.md says "KitePDF contains no third-party source code, but it bundles some third-party data ... [NOTICE](NOTICE) lists every piece with its source." `docs/about.md` and NOTICE say the same. NOTICE lists nine pieces. These are bundled and not listed:

| Data | Where | Upstream licence and its condition |
| --- | --- | --- |
| Unicode Character Database: `DerivedBidiClass.txt`, `BidiBrackets.txt`, `UnicodeData.txt`, `Scripts.txt`, `PropertyValueAliases.txt`, `ArabicShaping.txt`, `IndicSyllabicCategory.txt`, `IndicPositionalCategory.txt`, `IdnaMappingTable.txt` | `kitepdf-core/.../core/text/Bidi.kt`; `kitepdf-epub/.../epub/GeneralCategory.kt`, `UnicodeScript.kt`, `ArabicJoining.kt`, `CaseMapping.kt`, `CombiningClass.kt`, `Normalizer.kt`, `FullWidth.kt`, `IndicShaper.kt`, `script/IdnaData.kt` | Unicode License v3: the copyright and permission notice must appear with all copies, or in the associated documentation |
| The Brotli static dictionary (122,784 bytes), context table and transforms | `kitepdf-epub/.../epub/brotli/BrotliData.kt:9` to `:11` ("Byte-identical to the reference data published in google/brotli (MIT license, Copyright 2013 Google Inc.)") | MIT: the notice must be included in all copies or substantial portions |
| HarfBuzz's Indic and USE category tables, "HarfBuzz's table, which its gen-indic-table.py builds ... with its own overrides", and the Indic, USE, Khmer and Myanmar shapers written "after the ... shaper of HarfBuzz 14.4" | `kitepdf-epub/.../epub/IndicShaper.kt:7`, `:289`; `UseShaper.kt:7`, `:259`; `KhmerShaper.kt:7`; `MyanmarShaper.kt` | HarfBuzz's Old MIT licence: the copyright notice and two paragraphs must appear in all copies |
| HTML's named character references (2,125 names, from WHATWG's `entities.json`) | `kitepdf-core/.../core/xml/HtmlEntities.kt:1` | WHATWG: CC BY 4.0, BSD-3-Clause for parts in source code; both need the notice |

ImageKodec's NOTICE, by contrast, names each "altered Kotlin port" (lcms2, libwebp, libjxl) with its licence text. KitePDF's shapers are ports of the same kind, and NOTICE line 11 says "It contains no third-party source code".

#### What should happen

NOTICE names the Unicode data, the Brotli data, HarfBuzz (with its notice) and the WHATWG entity table, each with its licence. README.md and `docs/about.md` stop saying that KitePDF contains no third-party source code, or say what the shapers follow.

#### How to reproduce

`grep -rlE "UnicodeData.txt|Scripts.txt|ArabicShaping|IdnaMappingTable|DerivedBidiClass|google/brotli|of HarfBuzz 14.4|named character references" kitepdf-*/src` lists the files; none of these sources is in NOTICE.

#### Where

- `NOTICE:11` and items 1 to 9
- `README.md:511` to `:513`
- `docs/about.md:58` to `:67`

#### Notes

Read from source. The hyphenation sets match the README: every set has a licence other than the GPL or the LGPL, and the excluded list in `tools/generate_hyphenation.py` matches the six languages the README names.

### KP-93

**About 50 font, shaping and signature tests in 21 classes report SKIPPED on every CI run, because CI never provides their fonts or tools**

- Repo: KitePDF
- Labels: area:ci, kind:test-gap
- Severity: medium
- Confidence: Confirmed
- Related: #44, #497, #622, ImageKodec #14

#### What happens

- These tests look for fonts in the git-ignored `mupdf-master/resources/fonts/` (walking up from the working directory), for `hb-shape` in `/opt/homebrew/bin`, `/usr/local/bin` or `/usr/bin`, or for poppler's `pdfsig`, and call `orSkip` or `assumeTrue` without them.
- The `jvm` job, the required gate, installs `fonts-noto-cjk` and checks MuPDF out to `.oracle-source` (only when the mutool cache misses). It never creates `mupdf-master/` and installs neither `hb-shape` nor `pdfsig`. So these tests are skipped on every run:
  - `kitepdf-epub`: `GsubOracleTest` (9), `ArabicCorpusOracleTest` (2, the test that closed #622), `ShapingTest` (3), `FontFallbackTest` (4), `EmbeddedFontRenderTest` (4), `LazyFontScopeTest` (4), `KerningTest` (2), `WoffTest` (2), `Woff2Test` (2), `CffFontRenderTest` (2), `SmcpFeatureTest` (1), `SupplementaryPlaneRenderTest` (1), `BidiLayoutTest` (3)
  - `kitepdf-native-renderer`: `EmbeddedFontOracleTest` (3), `CffEmbedOracleTest` (2), `CjkCMapOracleTest`, `OpenTypeFontFileOracleTest`, `TextClipOracleTest`, `VerticalTextOracleTest` (1 each)
  - `kitepdf-pdf`: `TrueTypeSubsetterTest` (2), `SignatureOracleTest.pdfsigAgreesWithKitePdf`
- So the gate never decodes a real WOFF or WOFF2 font, never compares GSUB or mark positions with HarfBuzz, and never checks a signed file with pdfsig. CONTRIBUTING.md says these tests skip "on a clean checkout", but `docs/platforms.md` says CI tests "All artifacts" on the JVM. `EpubConformanceTest` already shows the other pattern: it fails on CI when its input is missing (#497).

#### What should happen

CI provides the fonts (MuPDF's tree at the pinned commit carries `resources/fonts`, and the job already checks it out) and the tools, or each of these tests fails instead of skipping when `CI` is set, as `EpubConformanceTest` does.

#### How to reproduce

Download `test-reports-jvm` from any CI run and count the skipped tests of the classes above; or run `./kotlin test -p jvm -m kitepdf-epub` on a clean clone and read the skipped count.

#### Where

- `kitepdf-epub/test@jvm/io/github/yuroyami/kitepdf/epub/HarfBuzzOracle.kt:14` to `:21`
- The `fontsDir()`, `droidSans()`, `naskh()`, `noto()` helpers of the classes above
- `.github/workflows/ci.yml:37` to `:175` (the `jvm` job)
- `docs/platforms.md`, What CI tests

#### Notes

Read from source, and seen locally at `c4e7574`: `./kotlin test -m kitepdf-pdf -p jvm` without the folder reports `TrueTypeSubsetterTest` as aborted with "DroidSansFallback.ttf is not available here, skipping". The counts are the `@Test` functions of each class whose body calls `orSkip` or an assumption, plus the three of `BidiLayoutTest`, which skip through its `glyphs` helper. `ShapingTest` has four more tests that need no font, and `SignatureOracleTest.kitePdfAcceptsWhatOpenSslSigns` needs only `openssl`; both are left out.

### KP-94

**No KitePDF function declares @Throws, so a Swift caller of PdfDocument.open or KiteDoc.open ends the app on a bad file**

- Repo: KitePDF
- Labels: area:core, kind:api
- Severity: medium
- Confidence: Confirmed
- Related: ImageKodec #37, #84

#### What happens

- `grep -rn "@Throws" kitepdf*/src*` finds nothing. The entry points document their failures only in KDoc: `PdfDocument.open` (`KiteWrongPasswordException`, `PdfFormatException`), `KiteDoc.open` (`KiteFormatException`), `EpubDocument.open`, the suspend `KiteDoc.openUrl`, `PdfEditor.saveIncremental` after a redaction, and the others.
- On Kotlin/Native, an exception that leaves a function without `@Throws` at the Objective-C or Swift boundary terminates the process. The ImageKodec issues #37 and #84 fixed the same thing there, and its README promises it. KitePDF publishes iOS, macOS, tvOS and watchOS klibs and says nothing about Swift.
- So a Swift app whose shared framework exports KitePDF ends at the exported `PdfDocument.Companion.open` on a damaged or locked PDF that a stranger sent it, instead of getting an `NSError`. A suspend function such as `openUrl` converts only `CancellationException`.

#### What should happen

The public functions that throw on input carry `@Throws` with the exceptions their KDoc names, with a contract test like ImageKodec's `ThrowsContractTest`; or the README says that KitePDF is for Kotlin callers only on Apple platforms.

#### How to reproduce

In a KMP framework that exports `kitepdf-pdf`, call from Swift: `PdfDocument.companion.open(bytes: KotlinByteArray(size: 4), password: "", allowInvalidPassword: false)`. Expected: the app terminates with "Uncaught Kotlin exception: PdfFormatException".

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:1031` and `:1095`
- `kitepdf/src/io/github/yuroyami/kitepdf/document/KiteDoc.kt:88`
- `kitepdf-net/src/io/github/yuroyami/kitepdf/net/RemoteSources.kt:96` and `:108`

#### Notes

Read from source. Not run on an Apple target.

### KP-95

**In a right-to-left app, a spread places its pages left to right but turns to the left**

- Repo: KitePDF
- Labels: area:viewer, kind:bug
- Severity: medium
- Confidence: Likely
- Related: #401

#### What happens

- `HorizontalPager` mirrors in a right-to-left layout direction: with `reverseLayout = false` the next item sits to the left, and a swipe to the right goes forward. The viewer relies on this elsewhere: `PageTurn.reversed` and `keyAction`'s `forwardIsLeft` XOR the layout direction with `reverseLayout`, and `KeyboardAndWheelSceneTest` asserts that a Paged layout in an RTL app advances to the left.
- Since #401, `SpreadBox` places the two pages of a spread from the physical left, with `AbsoluteAlignment.TopLeft` and `absoluteOffset`, so drawing and hit testing agree. The pager around it still mirrors.
- So in an Arabic, Hebrew or Persian app, `Spread()` shows page 0 on the left and page 1 on the right, and the next spread, pages 2 and 3, comes in from the left, with a rightward swipe and the left arrow. `Spread(reverseLayout = true)` shows page 1 left and page 0 right, and the next spread comes in from the right. Either way the order inside a spread runs against the order of the spreads, so the book reads 0, 1, then jumps back across the screen.
- The KDoc says `reverseLayout` "swaps both the swipe direction and the in-spread visual order"; in an RTL app the two halves no longer agree. Before #401 both mirrored together (drawing was mirrored by `padding(start)`), only hit testing was wrong.

#### What should happen

The order inside a spread and the order of the spreads come from the same direction. Either the spread pager ignores the app's layout direction (provide `LayoutDirection.Ltr` to it and let `reverseLayout` alone decide), or the in-spread order mirrors with it, with the hit rectangles following.

#### How to reproduce

Probe for `kitepdf-compose-viewer`, `test@jvm`, with `SpreadRtlSceneTest`'s `rgbDoc` (page 0 red, 1 blue, 2 green) and a touch drag:

```kotlin
CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Spread())
}
```

At rest, read pixels (50, 100) and (150, 100): red left, blue right. Then drag from (20, 100) to (180, 100), a rightward swipe, and pump: per source `state.currentPage` becomes 2. The forward swipe of a spread that reads left to right goes to the right.

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:2219-2235`: the spread pager, mirrored by the layout direction.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:2272-2287` and `:2325-2336`: pages placed from the physical left.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:2183-2186` and `KiteGestures.kt:552-558`: the turn and the keys follow the mirrored pager.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:96-99`: the KDoc.

#### Notes

Read from source. Pager mirroring in RTL is Compose's behaviour and the viewer's own tests depend on it; the probe confirms the combination.

### KP-96

**GPOS cursive attachment is not applied, so Urdu Nastaliq text has the right glyphs in the wrong places, although the docs list it as covered**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: medium
- Confidence: Likely
- Related: #622

#### What happens

- The EPUB layout positions glyphs with three things only: the advance from `hmtx`, the `xAdvance` of GPOS pair adjustment (`OpenTypeKern`), and mark attachment, lookup types 4, 5 and 6 (`OpenTypeMarks`). `OpenTypeMarks.parse` drops every other lookup type, and the KDoc of `OpenTypeKern` says that cursive positioning is out of scope.
- So GPOS cursive attachment (lookup type 3, the `curs` feature), single adjustment (type 1) and contextual positioning (types 7 and 8) never apply.
- A Nastaliq font such as Noto Nastaliq Urdu joins the letters of a word by cursive attachment: each glyph's exit anchor meets the entry anchor of the next, which gives the script its slope. Without it the glyphs stand side by side on one baseline.
- `docs/epub.md` (Shaping) says that the shaping covers "Urdu Nastaliq", and the CHANGELOG says the same. `GsubOracleTest` shapes `NotoNastaliqUrdu-Regular.otf`, but it compares glyph ids only, which CLAUDE.md warns cannot see a glyph in the wrong place (#622).

#### What should happen

- OpenType, GPOS lookup type 3 (cursive attachment) and the right-to-left lookup flag apply, so the glyphs of a Nastaliq word attach as HarfBuzz attaches them. Single and contextual positioning apply too.
- Until then, `docs/epub.md` does not list Urdu Nastaliq among the scripts it covers.

#### How to reproduce

`hb-shape --output-format=json NotoNastaliqUrdu-Regular.otf "نستعلیق"` gives a `dy` for most glyphs. The positions of the glyph run of the same word on an EPUB page, read from a `RecordingCanvas`, are all on one baseline. A position oracle next to `GsubOracleTest`, as #622 added for Arabic marks, shows the difference for each word.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/OpenTypeMarks.kt:85-91`: the lookup types it reads.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/OpenTypeKern.kt:1-12`: the KDoc that leaves cursive positioning out.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:2207-2208`: `positionMarks` and `kernWord`, the only positioning steps.
- `docs/epub.md:385`: the claim.

#### Notes

Read from source. Not run: the font is not on this machine, so that its lookups use cursive attachment is from knowledge of the font, not measured here, which is why this is Likely.

### KP-97

**Canvas2dCanvas makes a new canvas for every group, soft mask, raster step and image and never releases it, so Safari's canvas memory limit ends a page render**

- Repo: KitePDF
- Labels: area:renderer, backend:canvas2d, kind:bug
- Severity: medium
- Confidence: Likely

#### What happens

- `openLayer` creates a `<canvas>` for each layered transparency group, `applySoftMask` two (content and mask), `rasterStep` one for each render and draw, and `offscreenFor` one for each image (`Canvas2dCanvas.kt:388`, `:539-551`, `:611`, `:641-690`). Each is dropped after it composites, at its full size, without `width = 0`.
- WebKit counts the backing store of a canvas against a budget for all live canvases (about 384 MB on iOS) until the element is garbage collected. Past it, `getContext("2d")` returns null and the console says "Total canvas memory use exceeds the maximum limit".
- `openLayer` and `offscreenFor` cast that result to `CanvasRenderingContext2D`, and the cast or the next call on it throws, out of `beginTransparencyGroup`, `applySoftMask` or `drawImage`, whose contract says it never throws (#184). A page with a few dozen soft-masked paints at a retina scale makes 2 canvases of up to the page size each, for example 100 canvases of 8.5 MB for 50 masks.

#### What should happen

A layer canvas is released when it has composited (`width = height = 0`, as pdf.js does for the same reason), or a few canvases are reused. A null context falls back to drawing without the layer, not to an exception.

#### How to reproduce

In Safari on an iPhone, render a page with 100 paints under a soft mask (`q /GS1 gs … re f Q` repeated) into a canvas of 1,224 by 1,584 pixels with `Canvas2dCanvas`. Expected: the page. Likely: a `TypeError` once the budget is spent. `scripts/web-tests/run.py` runs the Canvas2D tests in headless Chrome only, which collects the canvases in time.

#### Where

- `kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt:382-394`, `:523-562`, `:604-614`, `:641-690`.

#### Notes

Read from source. Not run in Safari: the WebKit limit and its null context are documented WebKit behaviour, and pdf.js zeroes its canvases to avoid them, but this lane could not observe it.

### KP-98

**An EPUB can hold eight QuickJS engines of 512 MiB each, so one book can take 4 GiB of native memory**

- Repo: KitePDF
- Labels: area:javascript, area:epub, kind:bug
- Severity: medium
- Confidence: Likely

#### What happens

Every `KiteJsScriptEngine` opens QuickJS with `memoryLimit = MEMORY_LIMIT`, 512 MiB, sized for "a compiled program's heap". `EpubScriptRunner` gives each chapter an engine of its own and keeps up to `LIVE_CHAPTERS` (8) of them open. A chapter script such as

```js
window.keep = []; for (;;) keep.push(new Array(100000).fill(1));
```

stops at its own limit with "the engine ran out of memory", but the array stays reachable from the chapter's global object until that engine closes. A book whose first eight chapters each do this holds about 4 GiB of native memory once the reader has opened them. On a phone that ends the app, which the KDoc rules out: "A document cannot take the app's memory: past the limit its allocation fails and the call throws."

#### What should happen

One limit for the whole book, or a per-engine limit small enough that `LIVE_CHAPTERS` engines fit what a reader app can spend.

#### How to reproduce

On the JVM, a book of eight chapters, each with the script above; open the chapters one by one with `EpubScriptRunner.chapterOpened(i)` and watch the process's resident size (QuickJS allocates with `malloc`, so the Java heap does not show it).

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/KiteJsScriptEngine.kt:61` `memoryLimit = MEMORY_LIMIT`, `:183` `MEMORY_LIMIT`.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/EpubScriptRunner.kt:196` one engine per chapter, `:209` `liveChapters`, `:293` `LIVE_CHAPTERS`.

#### Notes

Read from source, not run. The memory limit itself works: the probe build stops `new Array(4000000).fill(1)` with "out of memory" at a 64 MiB limit.

### KP-99

**The viewer's public API takes types from kitepdf-pdf and kitepdf-epub, which it does not export, so the README's KiteDocView sample fails to compile in a PDF-only or EPUB-only app**

- Repo: KitePDF
- Labels: area:viewer, kind:api
- Severity: medium
- Confidence: Likely

#### What happens

- `kitepdf-compose-viewer/module.yaml` depends on `//kitepdf-pdf` and `//kitepdf-epub` without `exported`. The Kotlin Toolchain writes such a dependency with Maven scope `runtime` (`PomKt.mavenScopeName` in `amper-maven-publish-jvm.jar` of the toolchain cache: compile, runtime and not exported gives `"runtime"`). The 0.13.0 Gradle build had them as `implementation` too.
- The public API of the viewer uses types of both modules: `KiteDocView(scripts: PdfScriptHandler?, epubScripts: EpubScriptHandler?)`, `KiteLinkAction.Pdf.action: PdfAction`, `KiteLinkAction.Epub.link: EpubLink`, `KitePageRasterizer.rasterize(..., formState: PdfFormState?, ...)`, `rememberPdfViewState(PdfDocument)`, `EpubView(EpubDocument, ...)`.
- A PDF-only app adds `kitepdf-pdf` and `kitepdf-compose-viewer`, which the README offers ("Or one format only", then "Optional ... kitepdf-compose-viewer"). Its compile classpath has `kitepdf-pdf` and `kitepdf-core`, but not `kitepdf-epub`. The README's "full API surface" sample passes `epubScripts = null`, and the compiler stops with `cannot access class 'io.github.yuroyami.kitepdf.epub.EpubScriptHandler'`. An EPUB-only app (`kitepdf-epub` and the viewer, or the viewer and `kitepdf-media` as `docs/media.md` installs them) stops at `scripts = null` the same way.
- I checked the compiler rule with kotlinc 2.4.21: a library function `view(state: Int, modifier: String = "", scripts: a.Handler? = null)` compiles in a consumer without `a` on its classpath when `scripts` is left out, and fails with `error: cannot access class 'a.Handler'. Check your module classpath for missing or conflicting dependencies.` when the call passes `scripts = null`.
- `consumer-test` would not see it: it depends on the `kitepdf` umbrella, which exports every format module, and it never compiles against the viewer.

#### What should happen

`kitepdf-compose-viewer` exports `//kitepdf-pdf` and `//kitepdf-epub`, since both are part of its API. `kitepdf-skia-renderer` (API takes `PdfPage`, `EpubPage`) and `kitepdf-native-renderer` (API takes `PdfPage`) should export theirs for the same reason, although their common setups bring the module in anyway.

#### How to reproduce

A Gradle JVM project with the Compose plugin, `implementation("io.github.yuroyami:kitepdf-pdf:0.13.0")` and `implementation("io.github.yuroyami:kitepdf-compose-viewer:0.13.0")` only, and the README sample from `README.md:146` to `:178` in a source file. Expected: `cannot access class 'io.github.yuroyami.kitepdf.epub.EpubScriptHandler'` at `epubScripts = null`.

#### Where

- `kitepdf-compose-viewer/module.yaml:25` and `:26`
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:235` and `:249`
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteLinkAction.kt:72` and `:85`
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageRasterizer.kt:193`
- `kitepdf-skia-renderer/module.yaml` (`//kitepdf-pdf`, `//kitepdf-epub`), `kitepdf-native-renderer/module.yaml` (`//kitepdf-pdf`)
- `README.md:142` to `:178`

#### Notes

Likely, not Confirmed: the scope and the compiler rule are confirmed, but I did not build a consumer against artifacts that the toolchain published. With the Compose compiler plugin, an omitted parameter of a missing type may fail too; I did not check that.

### KP-100

**The KDoc of setFont(EmbeddedFont) says any Unicode the font covers renders, but the builder does not shape text**

- Repo: KitePDF
- Labels: area:pdf, kind:docs
- Severity: low
- Confidence: Confirmed

#### What happens

- The KDoc of `ContentStreamBuilder.setFont(font: EmbeddedFont, size)`, which an IDE shows, says: "Subsequent [showText] calls encode their text as Identity-H 2-byte glyph codes for this font, so any Unicode the font covers (CJK included) renders."
- `docs/writing.md:162` says the truth: "The builder maps each character to one glyph. It does not shape text, so Arabic letters do not join and right-to-left text runs left to right."
- So a developer who reads only the KDoc ships a PDF in Arabic, Hebrew, Persian, Urdu, Devanagari or Thai that reads wrong. KitePDF already has the shapers this needs (`TextShaper`, `IndicShaper`, `UseShaper`, `ArabicJoining`, `Bidi` in `kitepdf-epub`), which feature proposal P6 would reuse.

#### What should happen

The KDoc of `setFont(EmbeddedFont, ...)`, `showText` and `text(EmbeddedFont, ...)` states the limit as the docs page does, and names the scripts it affects.

#### How to reproduce

Measured on the JVM at `c4e7574`, with DejaVu Sans:

```kotlin
val font = EmbeddedFont.load(File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf").readBytes())
val pdf = PdfBuilder().page(width = 300.0, height = 120.0) {
    text(font, 28.0, 20.0, 70.0, "سلام عليكم")
}.build(compress = true)
```

`pdftoppm` shows the ten letters isolated and laid out left to right; `pdftotext` gives `مكيلع مالس`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/ContentStreamBuilder.kt:117-127`: the KDoc of `setFont`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/ContentStreamBuilder.kt:170-180`: `embeddedString`, one glyph per code point.
- `docs/writing.md:162`: the correct statement.

#### Notes

Measured on the JVM, rendered with Poppler 24.02. The missing feature itself is proposal P6 in the capability section, not a defect.

### KP-101

**One `KiteDocViewState` shown by two `KiteDocView`s mixes their geometry, position and caches, and nothing says it must not be**

- Repo: KitePDF
- Labels: area:viewer, kind:api
- Severity: low
- Confidence: Confirmed

#### What happens

The KDoc presents the state as "the single point of control" and never says that only one view may show it. The state keeps one copy of what each view writes:

- `adapter`: the last layout to attach wins. `currentPage` and every navigation follow that view; the other one scrolls on its own and is not reported.
- `viewportSize`: both views write it from `onSizeChanged`. With two sizes, pan clamping, focal zoom and hit testing in one view use the other's size.
- `pageGeometry`, keyed by slot: both views write the rectangle of the same slot, so taps, links, selection handles and the accessibility nodes of one view map through the other's rectangles, and a view that leaves removes the other's.
- The caches: a Vectorized view calls `bitmapCacheFor(0L)` and a Rasterized one `vectorImageCacheFor(0L)` in every `SideEffect`, and two different `cacheBudgetBytes` replace the cache in each slot composition. Each recomposition of one view then throws away the other's bitmaps, and its pages render again.

Two states over one document clash too: each view calls `document.keepChapters(...)` with its own chapters, `EpubDocument` replaces the kept set rather than joining it, and a view that leaves calls `keepChapters(emptySet())` for both. The other view's chapters can then be dropped by the layout budget and laid out again.

#### What should happen

Either the KDoc says that a state belongs to one view at a time and a second attach warns, or the per-view parts (adapter, viewport size, geometry, cache budget) move out of the state into the view. `keepChapters` should take a union per caller.

#### How to reproduce

Two views in one scene, `KiteDocView(state, layout = Paged())` at 200 x 200 and `KiteDocView(state, layout = Continuous())` at 300 x 600: tap a link at its place in the first view and see `state.hitTest` answer with the second view's rectangle.

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:1070`, `:1159-1170`, `:1192`, `:1243-1268`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:283-284`, `:334-335`, `:443`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1108-1113`: `kept` is replaced.

#### Notes

Read from source.

### KP-102

**The tiles of a deeply zoomed page keep their old pixels when the form layer or the font environment changes**

- Repo: KitePDF
- Labels: area:viewer, kind:bug
- Severity: low
- Confidence: Confirmed
- Related: #375, #358, #421

#### What happens

- `PageTiles` keeps the tiles it drew in `bitmaps`, remembered on `page, full, paper, theme, hairline, decorator, contentVersion`. The key leaves out `drawsFormLayer` and the rasterizer, and the effect skips a tile already in `bitmaps`.
- So when a host passes a `scripts` handler while the reader is zoomed past `maxBitmapLongSide`, the tiles keep the file's widgets, and the form layer draws the live fields over them: a field the reader cleared shows its old printed value underneath, which #358 avoids for the whole page. The reverse change drops the widgets from the tiles.
- A new font environment re-renders the page's own bitmap (#421), but not the tiles over it.

#### What should happen

The tiles' map is keyed on everything the tile raster depends on, as the whole page's `produceState` is.

#### How to reproduce

Zoom a page past `maxBitmapLongSide` (for example `Rasterized(maxBitmapLongSide = 256)` and `setZoom(4f)`), let the tiles land, then pass a `scripts` handler whose form clears a text field: the tiles still show the field's appearance stream.

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:1564-1581`.

#### Notes

Read from source.

### KP-103

**A `KiteViewerStrings` built inline, as its KDoc shows, recomposes the whole viewer and rebuilds the accessibility nodes on every recomposition of the host**

- Repo: KitePDF
- Labels: area:viewer, kind:perf
- Severity: low
- Confidence: Confirmed
- Related: #427

#### What happens

- `LocalKiteViewerStrings` and `LocalKiteChoiceStrings` are `staticCompositionLocalOf`, and `KiteViewerStrings` and `KiteChoiceStrings` define no `equals`. The KDoc's example builds the strings inside `CompositionLocalProvider(...)`.
- Each recomposition of the host's scope then provides a new instance, and a static local that changes recomposes all of its content: the viewer, every page slot and the overlay.
- `PageSemantics` keys its effect on `strings`, so every visible page also builds its accessibility nodes again on the raster dispatcher. A host scope that reads `state.currentPage`, as a page counter in the same screen does, pays this on every page change while scrolling.

#### What should happen

The strings compare by value (or the KDoc example remembers them), so the same words do not count as a change.

#### How to reproduce

Wrap `KiteDocView` in `CompositionLocalProvider(LocalKiteViewerStrings provides KiteViewerStrings(previousPage = "Prev"))` inside a composable that reads `state.currentPage`, and count compositions of a `pageOverlay`: it recomposes on every page change.

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteViewerStrings.kt:14-20`, `:40`, `:59`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteChoiceInput.kt:66-75`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KitePageSemantics.kt:157`.

#### Notes

Read from source.

### KP-104

**`prefetchPages` and `pagePlaceholder` do nothing in Vectorized mode, and prefetch stops when the cache is off**

- Repo: KitePDF
- Labels: area:viewer, kind:docs
- Severity: low
- Confidence: Confirmed
- Related: #437

#### What happens

- The strip draws pages ahead only when `renderSpec` is `Rasterized` and its cache budget is above 0. With `Vectorized`, or `Rasterized(cacheBudgetBytes = 0)`, `Continuous(prefetchPages = 3)` silently does nothing, and in Vectorized mode the first draw of each page still parses it and converts its images on the UI thread as it scrolls in.
- `pagePlaceholder` is drawn only by the raster path; a Vectorized page never shows it.
- `KiteRenderSpec`'s KDoc promises "no settings that silently do nothing"; the layout and the view parameters do not say these depend on the render mode.

#### What should happen

The KDoc of `prefetchPages` and `pagePlaceholder` says when they apply, or Vectorized mode prefetches what it can (the page's content and its converted images into the vector image cache).

#### How to reproduce

`Continuous(prefetchPages = 2)` with `KiteRenderSpec.Vectorized()`: `state.prefetchedPages` stays empty.

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:822-823`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewSpecs.kt:42-47` and `:228-232`.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:176-177` and `:1045-1050`.

#### Notes

Read from source.

### KP-105

**An /Encrypt /Length below 40 is read as bits, so /Length 5 throws ArithmeticException out of open and /Length 16 asks for a password the file does not have**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- `StandardSecurityHandler` takes `/Length / 8` as the key length with no range check (`StandardSecurityHandler.kt:30`, `:158`, `:197`), and reads `/Length` for `/V 1` too.
- Some writers give the length in bytes. `/Length 16` gives a 2-byte key, no password authenticates, and `open` throws `KiteWrongPasswordException` for a file whose user password is empty.
- `/Length` below 8 gives an empty key, and `Rc4.ksa` divides by the key size (`Rc4.kt:23`). The constructor throws `ArithmeticException`. `open` builds the handler outside any `runCatching` on its repair path (`PdfDocument.kt:1072`), so the `ArithmeticException` leaves `open`, while `KiteDoc.open` documents only `KiteFormatException` and `KiteWrongPasswordException`. A negative `/Length` throws from `copyOf` the same way. On Kotlin/JS `i % 0` is `NaN` and nothing throws, so the targets differ.
- MuPDF multiplies a `/Length` below 40 by 8 ("work-around for pdf generators that assume length is in bytes"), refuses a length that is not a multiple of 8 or is above 128 as a format error, and uses 40 bits for `/V 1` whatever `/Length` says (`pdf_new_crypt`, `pdf-crypt.c`). pdf.js multiplies a crypt filter `/Length` below 40 by 8.

#### What should happen

As MuPDF: a `/Length` below 40 is taken as bytes, `/V 1` is 40 bits, and a length that is still out of range is a `PdfFormatException` (ISO 32000-1, 7.6.1, Table 20: `/Length` is read only for `/V` 2 and 3, a multiple of 8 from 40 to 128; for `/V 4` the crypt filter gives it, Table 25).

#### How to reproduce

- In `EncryptionIntegrationTest.buildEncryptedPdf`, write `/Length 5` instead of `/Length 40` in the `/Encrypt` dictionary (the key stays 40 bits): `KitePDF.open(bytes)` throws `ArithmeticException: / by zero` on the JVM.
- `make_enc_len16.py` writes `encrypted-length16.pdf`, an RC4-128 file with an empty user password whose `/Length` is 16: `PdfDocument.open(bytes)` throws `KiteWrongPasswordException`. (Poppler refuses this file too; MuPDF's work-around opens it.)

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/StandardSecurityHandler.kt:30` to `:31`, `:158`, `:197`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/Rc4.kt:23`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:1072`.

#### Notes

Read from source.

Measured on the JVM at `c4e7574` by the lead: `PdfDocument.open` on `encrypted-length16.pdf` throws `KiteWrongPasswordException`.

### KP-106

**One malformed token inside a dictionary or array loses the whole object**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

`Lexer` and `Parser` throw `PdfFormatException` on each of these, and `resolve` turns the throw into a null object:

- a name whose `#` is not followed by two hex digits, such as `/App#2G` (`Lexer.kt:169` to `:175`); the second byte is consumed even when it is a delimiter;
- a hex string with a byte that is neither hex nor white space, such as `<41 4G>` (`Lexer.kt:149` to `:150`);
- an integer that overflows a `Long` (`Lexer.kt:257`), though the check misses some overflows: `25000000000000000000` reads as 6553255926290448384;
- a dictionary or array value that is a keyword other than `true`, `false` and `null`, such as `/X Unknown` (`Parser.kt:74`).

A page dictionary with one of them becomes a blank page; a catalog with one of them makes `open` fail, because repair parses the same bytes. MuPDF keeps `#` as a literal, reads junk in a hex string as a zero digit with a warning, ignores integer overflow on purpose ("We deliberately ignore overflow here"), and stores null for an unknown token in a dictionary or array (`pdf-lex.c`, `pdf_parse_dict`, `pdf_parse_array`).

#### What should happen

These are salvaged inside the object, as MuPDF does, and the rest of the object is kept.

#### How to reproduce

`make_misc.py` writes `bad-hex.pdf`, `bad-name.pdf` and `bad-keyword.pdf`: one page whose dictionary carries `/PieceInfo << /X <41 4G> >>`, `/PieceInfo << /App#2G (x) >>` or `/PieceInfo << /X Unknown >>`. `pdftotext` prints "Hello" for each. In KitePDF the page dictionary resolves to null and `pages[0].extractText()` is empty.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/Lexer.kt:134` to `:181`, `:246` to `:262`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/Parser.kt:63` to `:78`.

#### Notes

Ran the generator and poppler; the KitePDF side is read from source.

### KP-107

**`metadata`, `xmp` and `xmpMetadataXml` throw when the XMP stream does not decode**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- `xmpMetadataXml` runs `FilterChain.decode` on the `/Metadata` stream with no guard (`PdfDocument.kt:155`). `FilterChain.decode` rethrows every filter failure. A stream marked `/FlateDecode` that does not inflate (see the Flate finding above), an unknown filter, or one over the cap throws out of `xmpMetadataXml` and `xmp`.
- `metadata`, the `KiteDocument` view, reads `xmp` whenever `/Info` has no `/Title` or no `/Author` (`PdfDocument.kt:536` to `:541`), which is common. `KiteDocLayout.pagedFor` and `forDocument` read `metadata.rightToLeft`, so they throw too. The exception is an `InflateException` or `UnsupportedFilterException`, not a `KiteFormatException`.
- The other decodes that build document state are guarded (`PageContents.decodeOrNull`, `PdfAction.scriptText`, the font and image loaders), as CLAUDE.md asks of every filter chain read.

#### What should happen

A metadata stream that does not decode reads as no XMP, with a warning.

#### How to reproduce

`make_misc.py` writes `bad-metadata.pdf`: no `/Info`, and `/Metadata` is `<< /Type /Metadata /Subtype /XML /Filter /FlateDecode /Length 4 >> stream ABCD endstream`. `PdfDocument.open(bytes).metadata` throws `InflateException: Zlib stream too short: 4 bytes`. Poppler renders the page.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:148` to `:166`, `:536` to `:541`.

#### Notes

Read from source.

### KP-108

**A stream that names a /Crypt filter is decrypted with the document's default filter, then fails on /Crypt**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- ISO 32000-1, 7.4.10 and 7.6.5: a stream whose `/Filter` holds `/Crypt` is decrypted with the crypt filter that its `/DecodeParms /Name` names, not with `/StmF`, and `/Identity` means it is not encrypted.
- `Decryptor.decryptStream` decrypts every stream except `/Type /XRef` and an unencrypted `/Metadata` with the document's `/StmF` (`Decryptor.kt:40` to `:44`), so an `/Identity` stream is scrambled.
- `FilterChain` has no `Crypt` entry (`Filters.kt:30`, `:77` to `:80`), so the decode then throws `UnsupportedFilterException`, in encrypted and unencrypted files alike, and the stream is lost.
- MuPDF skips the default decryption for a stream that has a Crypt filter (`pdf_stream_has_crypt`), applies the named filter in the chain, and passes the bytes through with a warning in an unencrypted file (`pdf-stream.c`).

#### What should happen

The Crypt filter is part of the chain: `Decryptor` leaves a stream with an explicit `/Crypt` alone, and the chain applies the named crypt filter (`/Identity`: nothing; a name in `/CF`: that filter's method).

#### How to reproduce

`make_misc.py` writes `crypt-identity.pdf`, an unencrypted page whose content stream has `/Filter /Crypt /DecodeParms << /Name /Identity >>`. `pdftotext` prints "Hello" (with a warning). In KitePDF the content decodes to nothing and `pages[0].extractText()` is empty.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/Decryptor.kt:33` to `:46`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/filters/Filters.kt:30`, `:68` to `:89`, `:138` to `:151`.

#### Notes

Ran the generator and poppler; the KitePDF side is read from source. The KDoc of `Filters.kt` lists Crypt as unimplemented; the decryption half is not documented anywhere.

### KP-109

**extractText() returns the text of hidden optional content**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: low
- Confidence: Confirmed
- Related: #644

#### What happens

- `TextExtractor` reads every text operator. It does not track `BDC /OC` sections, and it reads a form XObject whatever its `/OC` says.
- `PageRenderer` skips that content when the layer is off in the default configuration, so the page, `structuredText` and search leave it out, while `extractText()` returns it.
- A document that keeps one language per layer, or an answer key on a layer that is off, gives every layer's text from `extractText()`, mixed.

#### What should happen

`extractText()` leaves out what the default configuration hides (ISO 32000-1, 8.11), by the rules the renderer uses (`PdfOptionalContent.offByDefault`, OCMDs and `/OC` on XObjects), or its KDoc says that it reads hidden layers.

#### How to reproduce

```kotlin
// kitepdf-pdf/test
val pdf = TestPdf.onePage(
    content = "/OC /L1 BDC BT /F1 12 Tf 10 100 Td (Hidden) Tj ET EMC BT /F1 12 Tf 10 50 Td (Shown) Tj ET",
    resources = "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> /Properties << /L1 5 0 R >>",
    catalogEntries = "/OCProperties << /OCGs [5 0 R] /D << /OFF [5 0 R] >> >>",
    extra = listOf("<< /Type /OCG /Name (Answers) >>"),
)
val page = PdfDocument.open(pdf).pages[0]
assertEquals(page.structuredText.plainText, page.extractText())   // "Shown" against "Hidden\nShown" today
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextExtractor.kt:85`: `run`, with no optional content state.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextExtractor.kt:205`: `form`, which does not read `/OC`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPage.kt:240`: `extractText`.

#### Notes

Read from source.

### KP-110

**/MissingWidth is read from the font dictionary instead of the font descriptor**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- `PdfFont.resolveWidths` reads `dict.getInt("MissingWidth")`, where `dict` is the font dictionary. ISO 32000-1, Table 122 puts `/MissingWidth` in the font descriptor, so the entry is never read.
- With `/Widths` present, a code outside `FirstChar` to `LastChar`, or past the end of `/Widths`, then advances by 0 instead of the descriptor's width, in a Type 1, Type1C or non-embedded font. The next glyph draws over it. (An embedded TrueType font falls back to its `hmtx` advance first.)
- `getInt` also skips a real such as `/MissingWidth 250.0`.

#### What should happen

The missing width comes from the font descriptor, as a number, with 0 as the default (Table 122), as MuPDF reads it.

#### How to reproduce

```kotlin
// kitepdf-core/test
val font = PdfFont.from(PdfDictionary(linkedMapOf<String, PdfObject>(
    "Type" to PdfName("Font"), "Subtype" to PdfName("Type1"), "BaseFont" to PdfName("Custom"),
    "FirstChar" to PdfInt(65), "LastChar" to PdfInt(65), "Widths" to PdfArray(listOf(PdfInt(600))),
    "FontDescriptor" to PdfDictionary(linkedMapOf<String, PdfObject>("Type" to PdfName("FontDescriptor"), "MissingWidth" to PdfInt(400))),
)), IndirectResolver { null })
assertEquals(400, font.widthOf(66))   // 0 today
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/PdfFont.kt:659`.

#### Notes

Read from source. No test reads `/MissingWidth`.

### KP-111

**An embedded CMap that builds on another with usecmap loses the base CMap**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- `CMap.parse` reads codespace ranges and bf and cid mappings, and ignores the `usecmap` operator and the stream's `/UseCMap` entry (ISO 32000-1, 9.7.5.3, Table 120).
- An embedded `/Encoding` CMap that adds a few entries to `Identity-H` through `usecmap` has no codespace range of its own. `CMap.segment` then falls back to a code width of 1, so every byte is a code, and a code without a `cidrange` maps to CID = code. Each 2-byte character draws as two wrong glyphs.
- A `/ToUnicode` CMap that uses `usecmap` loses the base's mappings in the same way.

#### What should happen

`usecmap`, and `/UseCMap` as a name or a stream, chain to the base CMap, as `PredefinedCMaps` already chains the predefined ones, and as MuPDF does (`pdf_load_embedded_cmap`). A cycle stops the chain.

#### How to reproduce

```kotlin
// kitepdf-core/test
val cmap = CMap.parse(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap " +
    "/CMapName /Custom-H def /Identity-H usecmap 1 begincidchar <0041> 34 endcidchar endcmap").encodeToByteArray())
val units = cmap.codeUnits(byteArrayOf(0x00, 0x42))
assertEquals(listOf(0x0042), units.map { it.cid })   // [0, 66] today, two one-byte units
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CMap.kt:235`: `parse`, with no `usecmap`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CompositeFont.kt:153`: the embedded encoding CMap.

#### Notes

Read from source.

### KP-112

**setButtonValue clears a radio group without an error when the value names no button**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

`setButtonValue` sets `/V` to the name it gets and sets each widget's `/AS` to that name when the widget has such a state, else to `/Off`. A name that no widget has turns every button off and leaves `/V` naming a state that does not exist, with no error.

A radio group with `/Opt` may name its buttons' states by index, `/0`, `/1` (ISO 32000-1, 12.7.4.2, Table 227), while `/Opt` holds the export values. The method's KDoc and `docs/editing.md` call its parameter the export value, and `PdfFormField.options` lists the `/Opt` strings. So `setButtonValue(field, field.options[1])` clears the group.

#### What should happen

A value that is one of `/Opt` selects the button at that index. A value that names no state throws `IllegalArgumentException`, as the other methods do for a wrong field.

#### How to reproduce

Common test: a radio field (`/Ff 49152`) with `/Opt [(Yes) (No)]` and two kids whose `/AP /N` hold `/0` and `/Off`, and `/1` and `/Off`. `setButtonValue(field, "No")`, then `saveIncremental()`: both kids have `/AS /Off`, and `/V` is `/No`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:391`

#### Notes

Read from source.

### KP-113

**PdfAttachment.bytes throws for a stream that does not decode, though its KDoc promises an empty array**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

The KDoc of `bytesProvider` says "Empty array on failure". The provider calls `FilterChain.decode`, which throws for a filter it does not know or for bad data, and `bytes` rethrows. An app that lists attachments and reads each one stops at the first damaged file, against the lenient salvage rule of CONTRIBUTING.md.

#### What should happen

The provider catches the failure and returns an empty array, with a `kiteWarn`.

#### How to reproduce

Common test: an `/EmbeddedFiles` entry whose stream has `/Filter /ASCIIHexDecode` and the data `zz>`. `doc.attachments.single().bytes` throws `PdfFormatException`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfAttachment.kt:34`, `:90` to `:92`

#### Notes

Read from source.

### KP-114

**A document that declares ISO-8859-15 decodes as windows-1252, so œ reads as ½ and € as ¤**

- Repo: KitePDF
- Labels: area:core, area:epub, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- `TextEncoding.canonical` puts `iso-8859-15` with `utf-8` and `us-ascii`. A document that declares it and is not valid UTF-8 then takes the fallback, windows-1252.
- ISO-8859-15 and windows-1252 differ at eight bytes: 0xA4, 0xA6, 0xA8, 0xB4, 0xB8, 0xBC, 0xBD and 0xBE. So French text in Latin-9 shows `c½ur` for `cœur` and `¤` for `€`, and `Š`, `š`, `Ž`, `ž`, `Œ` and `Ÿ` turn into other signs.
- Every other declared encoding, such as windows-1251 or GBK, decodes as windows-1252 too. The class says so, and EPUB asks for UTF-8 or UTF-16, but the same module carries a decoder for each WHATWG encoding for `TextDecoder` (`WhatwgEncoding`, `EncodingData`).

#### What should happen

A document that declares ISO-8859-15 decodes as ISO-8859-15. A declared single-byte encoding that the WHATWG tables cover decodes with its own table, as a browser reads the same file.

#### How to reproduce

```kotlin
// kitepdf-core, test/
@Test
fun iso_8859_15_keeps_its_own_letters() {
    val text = "<?xml version=\"1.0\" encoding=\"ISO-8859-15\"?><p>cœur 5 €</p>"
    val bytes = ByteArray(text.length) { i ->
        when (text[i]) { 'œ' -> 0xBD.toByte(); '€' -> 0xA4.toByte(); else -> text[i].code.toByte() }
    }
    assertTrue("cœur 5 €" in TextEncoding.decode(bytes)) // gives "c½ur 5 ¤"
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/TextEncoding.kt:143`: `iso-8859-15` read as UTF-8.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/TextEncoding.kt:27-34`: the fallback to windows-1252.

#### Notes

Read from source.

### KP-115

**An embedded font whose glyphs have no outlines draws in a system font on four canvases and draws nothing on three**

- Repo: KitePDF
- Labels: area:renderer, kind:bug
- Severity: low
- Confidence: Confirmed
- Related: #280

#### What happens

- When `hasOutlines` is true and no glyph of the run yields an outline, `AwtCanvas`, `AndroidNativeCanvas`, `CoreGraphicsCanvas` and `Canvas2dCanvas` draw the text in a host font (`AwtCanvas.kt:195`, `AndroidNativeCanvas.kt:184`, `CoreGraphicsCanvas.kt:284`, `Canvas2dCanvas.kt:175`).
- `SkiaCanvas`, `ComposeCanvas` and `KiteRasterCanvas` draw nothing for the same run (`SkiaCanvas.kt:243-262`, `ComposeCanvas.kt:287-290` and `:302-346`, `KiteRasterCanvas.kt:124-143`).
- So the viewer, which draws with `ComposeCanvas`, shows a blank where `AwtPdfRasterizer` and the AWT differential sweep show Helvetica letters. A symbolic TrueType font whose cmap KitePDF cannot map, or a program whose glyphs fail, is such a run. The CLAUDE.md gotcha of #280 is this fallback hiding a parser gap from the AWT sweep.
- The contract of `drawGlyphs` does not say which is right.

#### What should happen

One rule in the KDoc of `drawGlyphs`, and every canvas follows it. MuPDF draws the glyphs the font has (here none), so drawing nothing is the rule that keeps the oracle meaningful.

#### How to reproduce

Over a white 100 by 80 bitmap, draw `TextGlyph(0, 1, 36, "A", 600.0, outline = null, isWordSpace = false)` with `drawGlyphs(listOf(glyph), 48.0, 1000, hasOutlines = true, FontSpec.SansSerif, KiteMatrix(1.0, 0.0, 0.0, -1.0, 10.0, 60.0), RgbColor.BLACK)`. `AwtCanvas` paints an A, `SkiaCanvas` and `KiteRasterCanvas` paint nothing.

#### Where

- The seven `drawGlyphs` above, and the contract in `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteCanvas.kt:105-129`.

#### Notes

Read from source.

### KP-116

**CoreGraphicsCanvas and Canvas2dCanvas give no host glyph outlines, so stroked text in a font without a program paints filled and clip text clips to boxes**

- Repo: KitePDF
- Labels: area:renderer, backend:coregraphics, backend:canvas2d, kind:bug
- Severity: low
- Confidence: Confirmed
- Related: #85, #415

#### What happens

- `hostGlyphOutline` defaults to null (`KiteCanvas.kt:141`). AWT, Skia, Compose and Android from API 34 override it. CoreGraphics and Canvas2D do not, though `CoreGraphicsCanvas` already builds glyph paths with `CTFontCreatePathForGlyph` (`CoreGraphicsCanvas.kt:420-430`).
- For a font with no program and no standard 14 substitute (a non-embedded Verdana, or a CJK font), the renderer then paints a stroked run (`1 Tr`) filled in the stroke colour, and adds an advance-wide box per glyph to a clipping run (`PageRenderer.kt:2326-2333`, `:2509-2515`). On AWT the same page outlines the letters and clips to their shapes.

#### What should happen

`CoreGraphicsCanvas` returns the outline of the face it draws in from `CTFontCreatePathForGlyph`, scaled to 1,000 units with y up. `Canvas2dCanvas` documents that it has none, or gets one from the bundled standard faces.

#### How to reproduce

A page with a non-embedded `/BaseFont /Verdana` TrueType font and `1 Tr (Outline) Tj`: CoreGraphics and Canvas2D draw solid letters; AWT draws outlined letters.

#### Where

- `kitepdf-native-renderer/src@apple/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt` (no override).
- `kitepdf-native-renderer/src@js/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt` (no override).

#### Notes

Read from source. Below API 34, `AndroidNativeCanvas` has the same gap by design.

### KP-117

**KiteDoc.open turns a wrong password into "not a readable PDF" when the PDF header is not in the first kilobyte**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- A file with no `%PDF-` in its first 1,024 bytes gets `formatOf == null`, and `open` falls back to `PdfDocument.openOrNull(bytes, password.encodeToByteArray())` (`KiteDoc.kt:98-101`).
- `openOrNull` catches every exception, `KiteWrongPasswordException` included, so the caller gets `KiteFormatException("not a readable PDF, EPUB, CBZ, SVG or XPS …")`. The KDoc of `open` promises `KiteWrongPasswordException` for an encrypted PDF whose password does not authenticate, and an app uses it to ask for the password.
- The fallback also passes the password as UTF-8 only, while `PdfDocument.open(bytes, String)` retries a non-ASCII password as Latin-1 for revision 4 and older (`PdfDocument.kt:1095-1121`).

#### What should happen

The fallback calls `PdfDocument.open(bytes, password)` (the String overload) and lets `KiteWrongPasswordException` through.

#### How to reproduce

Take any encrypted PDF of the `kitepdf-pdf` tests and prepend 2,048 spaces. `PdfDocument.open(bytes, "wrong")` throws `KiteWrongPasswordException` after its repair. `KiteDoc.open(bytes, "wrong")` throws `KiteFormatException`.

#### Where

- `kitepdf/src/io/github/yuroyami/kitepdf/document/KiteDoc.kt:98-101`.

#### Notes

Read from source.

### KP-118

**KiteDoc.openUrl parses the download on the caller's dispatcher**

- Repo: KitePDF
- Labels: area:net, kind:api
- Severity: low
- Confidence: Confirmed

#### What happens

- `openUrl` is a `suspend` function, and the docs call it from a composable's coroutine before `KiteDocView`. After the download it runs `KiteDoc.open(bytes)` in the caller's context (`RemoteSources.kt:96-115`). There is no `withContext`.
- From `Dispatchers.Main`, the parse of up to 128 MiB runs on the UI thread: the repair scan of a damaged PDF, the format checks that read the central directory of a ZIP, and the package parse of an EPUB or XPS. A suspend function is expected to be safe to call from the main thread.

#### What should happen

`openUrl` and `openUrlOrNull` run `open` on `Dispatchers.Default`, or the KDoc says to call them off the main thread.

#### How to reproduce

From `Dispatchers.Main`, call `KiteDoc.openUrl` with a `MockEngine` that answers a 50 MB PDF whose xref is broken. The repair runs on the main thread (a profiler or `Thread.currentThread()` in a breakpoint in `PdfRepair.rebuild` shows it).

#### Where

- `kitepdf-net/src/io/github/yuroyami/kitepdf/net/RemoteSources.kt:96-115`, `:118-146`.

#### Notes

Read from source.

### KP-119

**The Android Uri and InputStream openers read with no limit, so a large file ends the app with OutOfMemoryError**

- Repo: KitePDF
- Labels: area:core, kind:api
- Severity: low
- Confidence: Confirmed

#### What happens

- `KiteDoc.open(context, uri)` and `KiteDoc.open(stream)` drain the stream with `readBytes()` (`Sources.android.kt:35`, `:62`; `Sources.jvm.kt:41`). A file picker hands over whatever the user picks, such as a 3 GB video.
- `readBytes` grows its buffer until the heap ends and throws `OutOfMemoryError`, an `Error`. The KDoc promises `KiteFormatException`, and an app that catches `Exception` around the call does not catch an `Error`, so the app ends.
- `openUrl` stops at 128 MiB for this reason (`RemoteSources.kt:18-23`).

#### What should happen

The local openers take a `maxBytes` with the same default as `openUrl`, check the size the content resolver reports (`OpenableColumns.SIZE`, or the length of the asset file descriptor), and throw `KiteFormatException` past it.

#### How to reproduce

On a device with a 256 MB heap, `KiteDoc.open(context, uriOfA2GbFile)`: `OutOfMemoryError` out of `readBytes`.

#### Where

- `kitepdf/src@android/io/github/yuroyami/kitepdf/document/Sources.android.kt:31-35`, `:56-65`.
- `kitepdf/src@jvm/io/github/yuroyami/kitepdf/document/Sources.jvm.kt:37-41`.

#### Notes

Read from source. The KDoc says the whole document goes into memory; the gap is the missing bound and the `Error`.

### KP-120

**On Apple platforms a file over 2 GB opens a wrong prefix or throws IllegalArgumentException, because its length is cut to an Int**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

- `NSData.toByteArray()` does `length.toLong().toInt()` in three places.
- A file of 2 to 4 GiB gives a negative `n`, and `ByteArray(n)` throws IllegalArgumentException, where the KDoc promises `KiteFormatException` (or `PdfFormatException`, `EpubFormatException`).
- A file of 4 GiB plus `k` bytes gives `n = k`: the reader copies the first `k` bytes and opens them with no error. A PDF then goes through repair on a cut file.
- The POSIX reader refuses a file over 2 GB with `KiteFormatException("file is larger than 2 GB, ...")`.

#### What should happen

The Apple readers check `length` against `Int.MAX_VALUE` first and throw the documented exception, as the POSIX reader does.

#### How to reproduce

On macOS, `KiteDoc.openFile` on a sparse file of 4 GiB + 1,000 bytes whose first bytes are a small PDF: it opens the small PDF.

#### Where

- `kitepdf/src@apple/io/github/yuroyami/kitepdf/document/Sources.apple.kt:62-70` (`:65`).
- `kitepdf-pdf/src@apple/io/github/yuroyami/kitepdf/OpenFile.kt:31-40` (`:35`).
- `kitepdf-epub/src@apple/io/github/yuroyami/kitepdf/epub/OpenFile.kt:29-37` (`:32`).
- `kitepdf/src@posixLp64/io/github/yuroyami/kitepdf/document/PosixFileIo.kt:32` (the guard the Apple readers lack).

#### Notes

Read from source.

Found by two lanes.

### KP-121

**On Kotlin/JS, `\s` in a Regex also matches no-break and other Unicode spaces, so `getPageNumWords` and collapsed text differ by platform**

- Repo: KitePDF
- Labels: area:javascript, area:epub, kind:bug
- Severity: low
- Confidence: Confirmed
- Related: #136

#### What happens

- `PdfScriptHost.pageWords` splits `extractText()` on `Regex("\\s+")` for the Acrobat calls `getPageNumWords` and `getPageNthWord`.
- On the JVM, Android and Kotlin/Native, `\s` is `[ \t\n\x0B\f\r]` (`java.util.regex` and the Kotlin/Native engine that ports it). On Kotlin/JS the pattern runs on the browser's `RegExp`, where `\s` also matches U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000 and U+FEFF (ECMA-262, 22.2.2.9).
- So "10 000 €" written with no-break spaces, as French and many other locales write it, counts 1 word on the JVM and 3 in a browser, and `getPageNthWord` returns different words for one document.
- The same split collapses no-break spaces in ruby text (`BoxBuilder.kt:949`) and in note text (`EpubLinks.kt:185`) on JS only.

#### What should happen

The splits name the characters they mean, for example `[ \t\n\r\u000C]+` or a split on `Char.isWhitespace`, so every target gives one answer.

#### How to reproduce

A page whose text is "10 000 €" with no-break spaces, and a form script `app.alert(this.getPageNumWords(0))`: 1 on the JVM, 3 under `python3 scripts/web-tests/run.py` (JS).

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:270-273`, used by `AcrobatApi.kt:536-541`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:949`, `:1230`, `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubLinks.kt:185`, `:253`.

#### Notes

Read from source and from the two regex specifications. The CSS value splits on `\s+` (`StyleResolver.kt`, `Grid.kt`, `Flex.kt`) differ the same way, but CSS itself does not treat a no-break space as white space, so only exotic style sheets see it.

### KP-122

**Every app that reads EPUB carries all 71 hyphenation sets: 2.6 million characters, about 5.3 MB in a Kotlin/Native binary**

- Repo: KitePDF
- Labels: area:core, kind:perf
- Severity: low
- Confidence: Confirmed
- Related: #207

#### What happens

- The 71 `Hyph*` objects hold 2,645,196 characters of patterns and exceptions as `const val` raw strings: 120 chunks of at most 60,000 UTF-8 bytes, under the class file limit. That is 2,968,913 bytes in UTF-8, 302,732 characters outside ASCII. Hungarian alone is 528 KB, the three German sets 807 KB.
- At run time only the sets a book asks for load, since each object is a class of its own, so there is no start-up cost.
- But `HyphenPatternSets.byId` names every object in one `when`, and EPUB layout reaches `Hyphenator.forLanguage`, so dead code elimination (R8, Kotlin/Native, Kotlin/JS, Wasm) keeps all 71 sets in any app that uses `kitepdf-epub` or the `kitepdf` umbrella, even one that only reads English or never hyphenates. A PDF-only app sheds them everywhere except on the desktop JVM, where the `kitepdf-core` jar always carries them.
- Estimated cost per target, from the measured strings:
  - JVM jar and Android APK: about 3 MB of modified UTF-8 in the class files or the dex, about 1.2 MB after Deflate (zlib over the strings).
  - Kotlin/Native stores a string literal as UTF-16, so the iOS framework and every native binary carry about 5.3 MB, unless the app sets the experimental `latin1Strings` binary option.
  - Kotlin/JS: about 3 MB of the bundle before gzip.
- With the Standard 14 font data (658 KB of base64), the WHATWG encoding indexes and the script DOM prelude, the string data of `kitepdf-core`, `kitepdf-pdf` and `kitepdf-epub` is about 3.9 million characters: 7.7 MB as UTF-16, 1.8 MB compressed.
- Neither the README nor the docs say what the libraries add to an app.

#### What should happen

The docs state the size each artifact adds per target. The pattern data ships as UTF-8 bytes (or compressed), so that native binaries do not double it, and an app can leave out the sets it does not need, for example through an artifact for the patterns or a registry that the app fills.

#### How to reproduce

`python3 -I audit-repro/L9/scripts/hyphsize.py kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen` prints the counts above. A release iOS framework built with and without the `byId` table shows the binary difference.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen/HyphenPatternSets.kt` (`byId`).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/hyphen/Hyph*.kt` (the data).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/text/Hyphenator.kt:196-197` (the process-wide cache, which keeps each built trie for good).

#### Notes

Measured from the sources; the binary sizes follow from each target's string encoding and were not built.

### KP-123

**KiteRawApi guards five declarations, while the raw object model it describes stays public without it**

- Repo: KitePDF
- Labels: area:pdf, kind:api
- Severity: low
- Confidence: Confirmed

#### What happens

- The KDoc of `KiteRawApi` says it "Marks the raw object-model surface: direct access to xref tables, trailer dictionaries, indirect-object resolution, and the low-level editor primitives", whose Kotlin surface "may change between minor releases without a deprecation cycle".
- It marks `PdfDocument.xref`, `trailer`, `rawBytes`, `resolve` and four `PdfEditor` primitives. These are public with no opt-in: `PdfDocument.catalog: PdfDictionary`, `PdfPage.resources`, `PdfPage.reference`, `PdfPage.annotationReferences`, the `raw` dictionaries of actions and annotations, `PdfEditor.addFlateStream` (which calls the marked `addObject`), the `IndirectResolver` interface, `PdfDictionary.getDict(key, refs)` and `getArray`, and the whole `core.parser` package with `Lexer` and `Token`.
- `PdfDocument.resolve` is an override of the unmarked `IndirectResolver.resolve`, so the marker does nothing for a caller that goes through the interface. `doc.catalog.getDict("Names", doc)` or `(doc as IndirectResolver).resolve(ref)` reaches any object with no warning.
- kotlinc 2.4.21 shows it on a copy of the shape (`@Raw override fun resolve` in `class Doc : Resolver`): `d.resolve(2)` warns "This declaration needs opt-in", while `(d as Resolver).resolve(1)` and a helper that takes `d` as a `Resolver` compile with no warning.

#### What should happen

The marker sits on `IndirectResolver.resolve`, on the members that hand out raw dictionaries, and on the parser classes, or its KDoc says which surface it covers. A cross-module marker for the internals that other KitePDF modules need (KiteJS has `InternalKiteJsApi` for this) would also keep `Lexer`, `Token`, `Inflate`, `ZipReader` and the TrueType table readers out of the stable surface.

#### How to reproduce

```kotlin
val doc = PdfDocument.open(bytes)
val names = doc.catalog.getDict("Names", doc)          // no opt-in warning
val obj = (doc as IndirectResolver).resolve(PdfReference(1, 0))   // no opt-in warning
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/KiteRawApi.kt:3` to `:23`
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/parser/PdfObject.kt:102`, `:107`, `:150`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:114`, `:768`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfPage.kt:49`, `:140`, `:341`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:128`

#### Notes

Read from source, and the compiler experiment above.

### KP-124

**TextSearchCorpusTest returns early when its PDF is missing, so it reports PASS on CI without running**

- Repo: KitePDF
- Labels: area:pdf, kind:test-gap
- Severity: low
- Confidence: Confirmed
- Related: #44

#### What happens

`searching_a_known_word_in_a_real_pdf_page_returns_hits` starts with `val file = corpusPdf("GoldenHour-byIOS.pdf") ?: return`. That PDF is not in `corpus/manifest.json` or `corpus/pdf/`, so on CI and on any clean checkout the test asserts nothing and reports PASS. Its KDoc says "Skipped when the git-ignored corpus is not present (CI)", which is the trap that CLAUDE.md describes (#44). `DeflateWriterOracleTest` reads the same file with `orSkip`.

#### What should happen

The test calls `orSkip` (or an assumption), or uses a PDF of the public corpus.

#### How to reproduce

`ls corpus/pdf | grep -i golden` is empty; run the test and see it pass.

#### Where

- `kitepdf-pdf/test@jvm/io/github/yuroyami/kitepdf/TextSearchCorpusTest.kt:26`

#### Notes

Read from source.

### KP-125

**The README and the docs site say KitePDF uses no JNI or native binary, but three of its artifacts load native code**

- Repo: KitePDF
- Labels: area:docs, kind:docs
- Severity: low
- Confidence: Confirmed

#### What happens

- `README.md:43` and `docs/index.md:32`: "It doesn't rely on any platform PDF library, web view, JNI or native binary". `mkdocs.yml:5`: "no JNI, no platform PDF engine, no native binary".
- `kitepdf-javascript` runs QuickJS through KiteJS: a JNI library in the jar on the JVM and one per ABI on Android. `kitepdf-skia-renderer` draws through Skiko, which loads its Skia library through JNI on the JVM and Android (`docs/getting-started.md` asks for `skiko-awt-runtime-*`). `kitepdf-media` plays through FFmpeg.
- `docs/about.md:16` to `:19` says "Two optional artifacts use native code" and names only `kitepdf-javascript` and `kitepdf-media`.

#### What should happen

The sentence names the format modules, or says that `kitepdf-javascript`, `kitepdf-skia-renderer` and `kitepdf-media` carry native code; `docs/about.md` lists the Skia renderer too.

#### How to reproduce

Read the lines above next to `kitepdf-skia-renderer/module.yaml` and KiteJS's `docs/engines.md:106`.

#### Where

- `README.md:43`, `docs/index.md:32`, `mkdocs.yml:5`, `docs/about.md:16` to `:19`

#### Notes

Read from source.

### KP-126

**The README does not say that the viewer and kitepdf-media pull Compose Multiplatform 1.13.0-alpha02 into the app**

- Repo: KitePDF
- Labels: area:docs, kind:docs
- Severity: low
- Confidence: Confirmed

#### What happens

`compose.module-template.yaml` sets Compose 1.13.0-alpha02 for `kitepdf-compose-viewer` and `kitepdf-media`, and the viewer exports `$compose.runtime`, `$compose.foundation` and `$compose.ui`. Gradle takes the highest version in the graph, so an app on a stable Compose Multiplatform 1.12 that adds the viewer compiles and ships the alpha, and on Android the androidx Compose versions that the alpha depends on. Only the 0.13.0 notes of CHANGELOG.md mention it. The README's "What else you need" table and `docs/getting-started.md` do not.

#### What should happen

The README and `docs/compose-viewer.md` name the Compose version the viewer needs, and that it is an alpha.

#### How to reproduce

`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` in an app on Compose Multiplatform 1.12.x with `kitepdf-compose-viewer:0.13.0` shows `org.jetbrains.compose.ui:ui:1.12.x -> 1.13.0-alpha02`.

#### Where

- `compose.module-template.yaml:5`
- `kitepdf-compose-viewer/module.yaml:27` to `:29`
- `README.md`, What else you need

#### Notes

Read from source and CHANGELOG.md:1034.

### KP-127

**Read-aloud takes its own interrupted page turn for a move by the reader and reads the clip again**

- Repo: KitePDF
- Labels: area:viewer, area:epub, kind:bug
- Severity: low
- Confidence: Likely
- Related: #629, #524

#### What happens

- `ReadAloud.show` turns the view with `turn = scope.launch { view.scrollTo(page, animate = true); settled = view.currentLocation }`.
- While the book still paginates, a chapter that lands calls `publishNow()`, which, with a navigation in flight, calls `adapter.requestSlot(target, ...)`. That snaps the pager to the target and interrupts the animation with `MutationInterruptedException` (the mechanism of #629). The turn job ends there, before `settled` is updated.
- `awaitReaderMove` then sees the view at rest on a page that is not `settled`, with no turn active, and treats it as the reader's move: the reading pauses, starts again from the first clip of that page, opens the audio again, and repeats the clip it was reading.

#### What should happen

The reading records where its own turn left the view even when the turn is interrupted, for example with `try { view.scrollTo(page, animate = true) } finally { settled = view.currentLocation }`. A fix of #629 that stops the interruption also removes this.

#### How to reproduce

`ReadAloudTest` with a narrated book that is still laying out (a `LatchedDocument` that releases a chapter while the reading turns the page), and a counter in `onClip`: the clip that triggered the turn is reported twice.

#### Where

- `kitepdf-media/src/io/github/yuroyami/kitepdf/media/KiteReadAloud.kt:400-405`: the turn; `:271-274`: the move detection.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocViewState.kt:2188-2191`: the correction that interrupts the animation.

#### Notes

Read from source.

### KP-128

**`ImageDecoder.decodeRaw` does not check the length of its pixel array**

- Repo: KitePDF
- Labels: area:viewer, kind:api
- Severity: low
- Confidence: Likely

#### What happens

- `decodeRaw(rgba, width, height)` is public and documents `rgba` as `width * height * 4` bytes, but no target checks it.
- On Android, a short array throws an index error inside the copy loop, which the catch turns into null.
- On the Skiko targets (desktop JVM, iOS, macOS, JS, Wasm), `Bitmap.installPixels(info, rgba, width * 4)` gives Skia a buffer of `rgba.size` bytes for a bitmap of `width * 4 * height`. `SkBitmap::installPixels` takes no length, so drawing or encoding the bitmap reads past the buffer: a native out-of-bounds read on the JVM and Apple targets, and a read of other heap memory in Wasm.
- KitePDF's own callers pass full buffers (`toSampledRgbaBytes` and the canvas build exactly `w * h * 4`), so this is an API hardening item for host callers.

#### What should happen

`decodeRaw` returns null when `width` or `height` is not positive, or `rgba.size` is below `width.toLong() * height * 4`, the same on every target.

#### How to reproduce

`ImageDecoder.decodeRaw(ByteArray(4), 1024, 1024)?.encodeToPng()` on the desktop JVM: expected null; per source it returns a PNG of whatever the heap held, or the JVM crashes. Run it in a child process.

#### Where

- `kitepdf-compose-viewer/src@jvm/io/github/yuroyami/kitepdf/compose/ImageDecoder.jvm.kt:28-43`, and the same code in `src@ios`, `src@macos`, `src@js`, `src@wasmJs`.
- `kitepdf-compose-viewer/src@android/io/github/yuroyami/kitepdf/compose/ImageDecoder.android.kt:17-40`.

#### Notes

Read from source. Skiko's native side was not read here; the probe settles it.

### KP-129

**On mingwX64, KiteDoc.openFile cannot open a path with a character outside ASCII**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Likely

#### What happens

- `readPosixFile` calls `fopen(path, "rb")` (`src@mingw/.../PosixFileIo.kt:26`). Kotlin/Native passes a `String` to a `const char*` parameter as UTF-8.
- The Windows C runtime reads a narrow file name in the ANSI code page of the process, not UTF-8, unless the program opts into UTF-8. So `C:\Users\Zoë\book.pdf` reaches `CreateFileA` as `ZoÃ«` and fails, and `openFile` throws `cannot open file`. Every path under a user profile with a non-ASCII name fails.

#### What should happen

The mingw source set opens the file with `_wfopen` and the path as UTF-16 (`path.wcstr`), as the JVM and Apple openers already handle any name.

#### How to reproduce

On Windows, a mingwX64 test: write a PDF to `"${tmp}\\Zoë.pdf"` and call `KiteDoc.openFile` on it. Expected: the document. Likely: `KiteFormatException("cannot open file: …")`.

#### Where

- `kitepdf/src@mingw/io/github/yuroyami/kitepdf/document/PosixFileIo.kt:26`.

#### Notes

Read from source; not run on Windows. The step not proven here is that the Kotlin/Native mingw runtime does not switch the process to the UTF-8 code page. The comment in the three copies names `posixLlp64Main`, which is `src@mingw`.

### KP-130

**On the web, a keystroke whose field loses focus while its script waits writes the raw key into the form, past the keystroke script**

- Repo: KitePDF
- Labels: area:viewer, area:javascript, kind:bug
- Severity: low
- Confidence: Likely
- Related: #363, #489

#### What happens

- `askScript` asks the field's keystroke script through `scripts.keystroke(...)`, a suspend call, inside `catch (failure: Exception)`. A handler that fails is logged and "the key stands": the catch returns `edit.applyTo(confirmed)`, and `askScript` stores it with `formState.setValue` (or `setChoiceSelection` for an editable combo box).
- `CancellationException` is an `Exception`, and this catch does not rethrow it, unlike `scriptCall` and every other catch site of the viewer.
- On JS and Wasm, `PdfScriptRunner.keystroke` waits for its turn on a `Mutex` and runs the script with pauses (`pausingCall`, #489), so it can be cancelled midway. On the JVM, Android and native targets the call blocks on the script thread, so it is not.
- So on the web, when the input's `LaunchedEffect` is cancelled while a key waits behind another script (the field loses focus, the page scrolls away, the view closes), the catch logs "the keystroke script of ... failed" and writes the key unchecked into the form state. A key that the script refuses, such as a letter in an `AFNumber_Keystroke` field, stays in the value. The blur hand-off asks the script about the same key again, and a refusal there returns null without undoing the write.

#### What should happen

`askScript` rethrows `CancellationException` before the generic catch, as `scriptCall` does, and leaves the form as it was.

#### How to reproduce

On JS (`python3 scripts/web-tests/run.py`, a viewer scene test): a number field with `AFNumber_Keystroke(2, 0, 0, 0, "", true)` as its keystroke script, and another field whose calculate script runs long enough to pause (a loop of a few hundred milliseconds). Commit a value that starts the calculation, type `x` into the number field while it runs, then move the focus before it ends. `formState.value("number")` then holds the `x`.

#### Where

- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteFormInput.kt:269-293` (`askScript`, the catch at `:281-284`), `:164` and `:195-201` (the two callers).
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:118-121` (`pausingCall`), `:271-274` (`keystroke`).
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ScriptCall.kt:13-19` (the pattern that rethrows).

#### Notes

Read from source. Likely, not Confirmed: the cancellation has to land while the key waits, which the probe arranges.

## Findings for KiteJS

### KJ-01

**One built-in call runs past the instruction budget and the interrupt hook, so a single statement hangs the engine**

- Repo: KiteJS
- Labels: area:quickjs, area:interpreter, QuickJS, Rhino, upstream:quickjs-ng, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: KitePDF #242

#### What happens

QuickJS polls the interrupt handler only at calls and backward jumps (`js_poll_interrupts`). Several array built-ins loop over `length` in C without polling. For `join`, `toLocaleString`, `reverse` and `copyWithin`, called on a plain object, `length` can be up to 2^53 - 1 and no element has to exist, so the loop allocates nothing and does not end. Neither `instructionBudget` nor `interruptWhen` is asked while it runs. (`fill`, `with`, `toReversed`, `toSorted` and `toSpliced` do not poll either, but they allocate as they go, so the memory limit ends them.)

I built the vendored QuickJS-ng and `kitejs_quickjs.c` with gcc and ran the glue with an interrupt hook that answers "stop" once one second has passed, the way KitePDF's `budgetMillis` deadline answers:

| Script | Result |
|---|---|
| `for(;;){}` | stopped after 1.0 s, 1,271,132 polls |
| `Array.prototype.join.call({length: 1e8}, '')` | returned after 2.3 s, 1 poll |
| `Array.prototype.reverse.call({length: 1e8})` | returned after 2.9 s, 1 poll |
| `Array.prototype.copyWithin.call({length: 1e8}, 0, 1)` | returned after 5.1 s, 1 poll |
| `Array.prototype.join.call({length: 2**53 - 1}, '')` | still running when killed after 20 s |
| `reverse`, `copyWithin(0, 1)` and `toLocaleString` on `{length: 2**53 - 1}` | each still running when killed after 8 s |

The time is linear in the length: at 2.3 s per 1e8 elements, the last rows would run for years. With an instruction budget of 1000 the result is the same, as the budget is only charged at a poll.

In KitePDF a document script is untrusted input that runs on QuickJS with a 5 second deadline. One line in a keystroke, calculate or open script blocks the runner's script thread for good. `PdfScriptRunner.close()` then blocks its caller too: on the JVM, Android and Kotlin/Native it queues the close on the script thread and waits (`closeHere`, `thread.call { ... }` then `executor.submit(...).get()`), so a host that closes the runner from `onDispose` on the main thread hangs there (an ANR on Android). On JavaScript and Wasm the engine runs on the page's only thread and can pause only at a poll, so the tab freezes.

Rhino has the same gap: `NativeArray.js_reverse` loops `for (i in 0 until half)` over a `Long` half of the length with no `addInstructionCount`, and only the interpreter and `NativeRegExp` charge the budget (read from source, not run).

#### What should happen

The budget and `interruptWhen` stop any script, including one built-in call that loops, as `docs/limits.md` promises: "When one call passes the budget it gives up and throws JsEngineError". KitePDF's `KiteJsScriptEngine` promises the same: "a script that never returns fails with a KiteScriptException instead of hanging the app".

#### How to reproduce

`audit-repro/L7/qjs/hang.c`, linked against the vendored sources:

```sh
./hang "Array.prototype.join.call({length: 2**53 - 1}, '')"   # never returns; the hook says stop after 1 s
```

As a KiteJS test in `kitejs-quickjs` (common), which hangs today:

```kotlin
@Test
fun aBuiltInLoopStopsAtTheBudget() {
    KiteJs(QuickJs) { instructionBudget = 100_000 }.use { js ->
        val start = TimeSource.Monotonic.markNow()
        assertFailsWith<JsEngineError> { js.evaluate("Array.prototype.join.call({ length: 2 ** 53 - 1 }, '')") }
        assertTrue(start.elapsedNow() < 5.seconds)
    }
}
```

The same with `interruptWhen = { deadline.hasPassedNow() }` instead of a budget, and the same on Rhino with `Array.prototype.reverse.call({ length: 2 ** 53 - 1 })`.

#### Where

- `kitejs-quickjs/native/quickjs/quickjs.c:44139` `js_array_join` (also `toLocaleString`), `:44315` `js_array_reverse`, `:44616` `js_array_copyWithin`: no `js_poll_interrupts` in the loop (compare `js_array_lastIndexOf` at `:43984`, which polls).
- `kitejs-quickjs/native/kitejs_quickjs.c:163` `interrupt_handler`: only reached from a poll.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeArray.kt:898` `js_join`, the non-dense path of `js_reverse` after it.
- KitePDF: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:779` `close`, `:790` `closeHere`; `src@jvm/.../ScriptThread.jvm.kt` `call`.

#### Notes

Ran the QuickJS part (see above). The Rhino part and the KitePDF consequences are read from source. KiteJS already patches QuickJS (`native/kitejs-quickjs.patch`), so a poll in these loops fits the existing patch.

Measured on the JVM at `c4e7574` by the lead: through KitePDF, `PdfScriptRunner(doc).runDocumentOpen()` on a PDF whose `/OpenAction` script is `Array.prototype.join.call({length: 2**53 - 1}, '')` was still running after 25 s, while `for(;;){}` is stopped after 5.1 s. `runner.close()` from another thread was then still blocked after 10 s.

### KJ-02

**When the JNI library cannot load, `KiteJs(QuickJs)` throws `ExceptionInInitializerError`, then `NoClassDefFoundError`, not `JsEngineError`**

- Repo: KiteJS
- Labels: area:quickjs, QuickJS, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #122, KitePDF #365

#### What happens

`JniBridge` is a Kotlin `object` whose `init` block calls `loadNativeLibrary()`. That runs in the class's static initializer, and the JVM wraps anything thrown there in an `ExceptionInInitializerError`, an `Error`. Every later use of the class throws `NoClassDefFoundError: Could not initialize class ...JniBridge`. `QuickJsKiteJs.open` builds the engine object, whose first field is `bridge()`, before its `try`, so the error leaves `KiteJs(QuickJs)` as it is.

So the loader's own message ("QuickJS has no JNI library for windows on aarch64; set kitejs.quickjs.library ...") reaches the caller only as the cause of an `Error`, and a caller that catches `JsException`, `RuntimeException` or `Exception` does not catch it. The same happens when `System.load` or `System.loadLibrary` throws `UnsatisfiedLinkError`: a glibc library on a musl system such as Alpine, or an Android app packaged without the ABI's library.

The platforms this reaches are ordinary ones: a Windows on Arm laptop with an Arm64 JDK (`os.arch` is `aarch64`, and the jar has no `windows-aarch64` library), Alpine, FreeBSD, Linux on riscv64.

In KitePDF it ends the app. `PdfScriptRunner.ensureEngine` catches `RuntimeException` to record "the engine did not open", and the viewer's `scriptCall` catches `Exception` (the #365 fix), so the `Error` escapes the `LaunchedEffect` that runs `documentOpened`. `ensureEngine` runs for every widget event and keystroke too, so typing into any field of a form shown with `scripts = PdfScriptRunner(doc)` is enough.

A plain Java model of the same shape (`audit-repro/L7/clinit/Demo.java`) prints:

```
call 1: escapes a RuntimeException catch: java.lang.ExceptionInInitializerError
call 2: escapes a RuntimeException catch: java.lang.NoClassDefFoundError: Could not initialize class Demo$JniBridge
```

#### What should happen

Each attempt to open an engine on a platform without a loadable library throws the `JsEngineError` the loader builds, with its message, as the docs promise for the web ("Opening an engine on the web before loading throws a JsEngineError that says so").

#### How to reproduce

A `kitejs-quickjs` JVM test in its own JVM (forked), with `-Dkitejs.quickjs.library=/nonexistent.so`:

```kotlin
@Test
fun aLibraryThatDoesNotLoadIsAnEngineError() {
    repeat(2) { assertFailsWith<JsEngineError> { KiteJs(QuickJs) } }   // today ExceptionInInitializerError, then NoClassDefFoundError
}
```

#### Where

- `kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/JniBridge.kt:63` `private object JniBridge`, `:65` `init { loadNativeLibrary() }`, `:113` `bridge()`.
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:62` `private val bridge = bridge()`, `:673` `QuickJsKiteJs(config, id)` before the `try` at `:675`.
- `kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/NativeLibrary.kt:51` and `:55` the `JsEngineError`s that get wrapped.
- KitePDF: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:221` `catch (failure: RuntimeException)`; `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ScriptCall.kt` `catch (failure: Exception)`.

#### Notes

Read from source; the JVM behaviour run with the Java model above. `NativeLibraryTest` covers `libraryCopy` only, not a failed load through `KiteJs(QuickJs)`.

### KJ-03

**`asyncKiteJs(QuickJs)` on Apple runs a 512 KiB stack limit on a 512 KiB thread, so deep recursion crashes the process**

- Repo: KiteJS
- Labels: area:api, area:quickjs, QuickJS, kind:bug
- Severity: high
- Confidence: Likely

#### What happens

`asyncKiteJs` opens the engine on an `EngineThread` unless the caller passes a dispatcher. On Kotlin/Native that is `newSingleThreadContext("KiteJS engine")`, a Worker with the platform's default stack, which is 512 KiB for a secondary thread on iOS and macOS. QuickJS's default `maxStackSize` is also 512 KiB, measured from where the outermost call enters (`kite_enter` calls `JS_UpdateStackTop`), and the Worker has already used part of its stack by then. So a script that recurses without end runs off the end of the thread's stack before QuickJS's check throws its RangeError, and the process dies with a stack overflow signal.

`QuickJsConfig.maxStackSize` says that "a secondary thread on iOS at 512 KiB, needs a smaller value", but here KiteJS makes both the thread and the default, and `asyncKiteJs` neither lowers the limit nor documents it. KitePDF ran into the same platform default and starts its script threads with 16 MiB for that reason (`ScriptThread.native.kt`: "since a Worker takes the platform's default").

#### What should happen

`asyncKiteJs(QuickJs)` with defaults turns runaway recursion into a catchable RangeError on every target, as `ErrorsContract.deepRecursionIsARangeErrorTheScriptCanCatch` requires of an engine on the test thread: either the `EngineThread` gets a stack that fits the limit, or the limit fits the thread.

#### How to reproduce

A `kitejs-coroutines` test on `macosArm64` or `iosSimulatorArm64`:

```kotlin
@Test
fun deepRecursionOnTheEngineThreadIsARangeError() = runTest {
    val js = asyncKiteJs(QuickJs)
    try {
        assertEquals("RangeError", js.evaluate("try { (function f() { return f() + 1 })() } catch (e) { e.name }").asString())
    } finally {
        js.close()
    }
}
```

Expected today: the test process ends with a stack overflow (EXC_BAD_ACCESS) instead of a failed assertion.

#### Where

- `kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt:288` `asyncKiteJs`, `dispatcher: CoroutineDispatcher = EngineThread()`.
- `kitejs-coroutines/src@native/io/github/yuroyami/kitejs/coroutines/EngineThread.native.kt` `newSingleThreadContext`.
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/QuickJs.kt:61` `maxStackSize = 512L * 1024`.
- `kitejs-quickjs/native/kitejs_quickjs.c:395` `kite_enter`.

#### Notes

Read from source. Not run: this machine cannot build Apple targets. The 512 KiB default comes from Apple's pthread default, from `QuickJsConfig`'s own KDoc and from KitePDF's CLAUDE.md (#450). No coroutine test recurses deeply.

### KJ-04

**`JSON.parse` on Rhino recurses once per nesting level with no limit, so deep input overflows the host stack**

- Repo: KiteJS
- Labels: area:interpreter, Rhino, kind:bug
- Severity: high
- Confidence: Likely
- Related: #139, #119, #120

#### What happens

`JsonParser.readValue` calls `readArray` or `readObject`, which call `readValue` again for each element, one Kotlin frame pair per nesting level. Nothing counts the depth, and the parser never calls `checkInterpreterInvocation` or the native stack probe, because no interpreter frame is entered. `'['.repeat(n)` costs one call of budget, so a short script feeds the parser any depth:

```js
JSON.parse('['.repeat(1e6) + ']'.repeat(1e6))
```

As #139 lays out for the source parser, what the host sees then depends on the target: a `StackOverflowError` on the JVM and Android, which `RhinoKiteJs.call` catches as a Throwable and reports as an engine error; a crash of the whole process on Kotlin/Native; the platform's own stack error on JS and a trap on Wasm.

`JSON.stringify` has the same shape (`NativeJSON.str`, `jo` and `ja` recurse per level, and `state.stack.contains` is a linear scan per level), for an object that a loop nests deeply.

#### What should happen

Deep JSON ends in a RangeError the script can catch, as on QuickJS, whose JSON parser checks the stack (`json_parse_value` runs `js_check_stack_overflow`; the probe build answers `RangeError: Maximum call stack size exceeded` for `JSON.parse('['.repeat(200000) + ']'.repeat(200000))`).

#### How to reproduce

On Rhino, on `linuxX64` or `macosArm64`:

```kotlin
KiteJs(Rhino).use { js ->
    js.evaluate("try { JSON.parse('['.repeat(1e6) + ']'.repeat(1e6)); 'parsed' } catch (e) { e.name }")
}
```

Expected: `"RangeError"`. Today the native test process ends.

#### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/json/JsonParser.kt:29` `readValue`, `:47` `readObject`, `:88` `readArray`.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeJSON.kt:76` the call, `:204` `str`, `:264` `jo`, `:302` `ja`.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/Context.kt:112` `checkInterpreterInvocation`, which these paths never reach.

#### Notes

Read from source. Not run: the Rhino build needs `./kotlin`. #139 covers the source parser only and says so; this is the JSON parser, a separate path with the same missing guard. Upstream Rhino 1.9.1's `JsonParser.java` has no depth limit either, so the port inherited it; upstream only ever runs on the JVM, where it ends in a `StackOverflowError`.

### KJ-05

**No KiteJS function declares @Throws, so a Swift caller of evaluate ends the app on any script error**

- Repo: KiteJS
- Labels: area:api, kind:api
- Severity: medium
- Confidence: Confirmed
- Related: ImageKodec #37, #84

#### What happens

- KiteJS has no `@Throws` in any source set. `KiteJs.evaluate` throws a `JsException` for every script that throws or does not parse, and a `JsEngineError` for a failing host function, as the class KDoc says. `close()` throws on another thread.
- KiteJS publishes iOS and macOS klibs for both engines. From Swift, `js.evaluate(source: "throw new Error('x')", fileName: "<eval>")` has no `error:` parameter, and the exception ends the process. A script error is an ordinary result for a script engine, so any app that runs untrusted or user scripts from Swift can be ended by them.

#### What should happen

`evaluate`, `compile`, `JsScript.run`, `JsFunction.call` and the other calls into the engine carry `@Throws(JsException::class, JsEngineError::class)`, as ImageKodec's entry points do since #37.

#### How to reproduce

In a framework that exports `kitejs-quickjs`, call `evaluate` from Swift with `throw 1`. Expected: "Uncaught Kotlin exception".

#### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/KiteJs.kt:25` to `:41`
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:123`
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/RhinoKiteJs.kt:81`

#### Notes

Read from source. Not run on an Apple target.

### KJ-06

**QuickJS rejects an identifier with a character outside the Basic Multilingual Plane**

- Repo: KiteJS
- Labels: area:quickjs, QuickJS, kind:bug
- Severity: low
- Confidence: Confirmed

#### What happens

`kite_compile` turns the UTF-16 source into bytes for `JS_Eval` with `JS_ToCStringLen2(ctx, &utf8_length, text, true)`. The last argument asks for CESU-8, which writes a surrogate pair as two 3-byte sequences instead of one 4-byte sequence. QuickJS's lexer then decodes each half as its own code point, and a surrogate is neither ID_Start nor ID_Continue, so it stops with "unexpected character". String literals survive, because there each half is stored as a UTF-16 unit again.

Run on the probe build:

| Source | Through `kite_compile` | Plain UTF-8 into `JS_Eval` |
|---|---|---|
| `var 𝑥 = 2; 𝑥 * 3` (U+1D465) | `SyntaxError: unexpected character` | `6` |
| `var 𠮷 = 4; 𠮷` (U+20BB7) | `SyntaxError: unexpected character` | not run |
| `'😀'.length + ':' + '😀'.codePointAt(0)` | `2:128512` | |

ECMA-262 §12.7 allows any UnicodeIDStart code point in an identifier, the supplementary planes included, and V8 accepts both.

#### What should happen

`kite_compile` hands the parser UTF-8 (`cesu8 = false`), as `qjs` does. A lone surrogate is written as a 3-byte sequence in both modes, so nothing else changes.

#### How to reproduce

`./probe astral-identifier` in `.../L7-js/qjs/`, or in `kitejs-quickjs` (common): `KiteJs(QuickJs).use { assertEquals(6.0, it.evaluate("var 𝑥 = 2; 𝑥 * 3").asDouble()) }`.

#### Where

- `kitejs-quickjs/native/kitejs_quickjs.c:631` `JS_ToCStringLen2(ctx, &utf8_length, text, true)`.
- `kitejs-quickjs/native/quickjs/quickjs.c:4900` `JS_ToCStringLen2` (the `cesu8` branch), `:23551` the lexer's non-ASCII case.

#### Notes

Ran. No KiteJS test has a source with a supplementary character.

### KJ-07

**Rhino on the JVM leaves an array unsorted when its comparator breaks TimSort's contract, while the other targets sort it**

- Repo: KiteJS
- Labels: area:interpreter, Rhino, kind:bug
- Severity: low
- Confidence: Likely

#### What happens

`NativeArray.sort` sorts a copy with Kotlin's `sortWith` and, when that throws `IllegalArgumentException`, returns the array as it was. Only the JVM and Android throw: their `sortWith` is TimSort, which reports "Comparison method violates its general contract!" when it sees an inconsistent comparator during a merge. Kotlin/JS sorts with the JavaScript `sort`, and Kotlin/Native and Wasm with the stdlib's merge sort, and neither throws.

So the common shuffle idiom `a.sort(function () { return Math.random() - 0.5 })` on an array of a few hundred elements is a no-op some of the time on the JVM and Android and shuffles everywhere else. ECMA-262 §23.1.3.30 lets the order be implementation-defined for an inconsistent comparator, so this is not a spec violation, but the README promises that either engine "runs the same script the same way on every target".

#### What should happen

The same algorithm on every target, one that does not throw. Upstream Rhino 1.9.1 has the same `Arrays.sort` and `catch` (`NativeArray.sort`), so the JVM parity run agrees with the port here; the difference shows only between targets, which the CLAUDE.md gotcha says the parity run cannot see.

#### How to reproduce

On Rhino, on the JVM and on JS: `var a = []; for (var i = 0; i < 1000; i++) a.push(i); a.sort(function () { return Math.random() - 0.5 }); a.every(function (v, i) { return v === i })`. Expected `false` everywhere; on the JVM it is `true` in a share of runs.

#### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeArray.kt:993` `sort`, `:1005` `working.sortWith(comparator)`, `:1006` the `catch`.

#### Notes

Read from source (and upstream's `NativeArray.java` at the `Rhino1_9_1_Release` tag), not run. The budget is not the problem here: every comparator call goes through the interpreter and is charged.

## Findings for ImageKodec

### IK-01

**JBIG2 halftone region decode runs for a time unrelated to the input size (DoS)**

- Repo: ImageKodec
- Labels: area:jbig2, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #102 (the hardening pass that added `rowsShown` and the region-width cap but did not cover this path), KitePDF #621 (no halftone corpus file, so no sweep covers it)

#### What happens
`Jbig2Decoder` decodes a halftone region by iterating every cell of the gray
grid and calling `drawInto` once per cell with a pattern bitmap:

```
for (mg in 0 until hgh) for (ng in 0 until hgw) {       // up to 2^26 cells
    ...
    drawInto(region, pats[minOf(g, pats.size - 1)], x, y, hCombOp)
}
```

`drawInto` loops over the whole source pattern (`src.h * src.w`) regardless of
how much of it lands inside the small destination region:

```
for (yy in 0 until src.h) for (xx in 0 until src.w) { ... if (px >= dst.w ...) continue ... }
```

The grid cell count is capped at `hgw.toLong() * hgh > 1 shl 26` (line 582), and
the pattern side comes from the pattern dictionary's HDPW/HDPH, each a u8 (up to
255). Neither the product `grid cells * pattern area` nor the total halftone
work is bounded. A halftone segment with HGW=HGH=8192 (2^26 cells), HRX=HRY=0
(so every cell draws at (0,0)), referencing a pattern dictionary of one 255x255
pattern (GRAYMAX=0), forces 2^26 * 255 * 255 ≈ 4.4e12 inner iterations from a
JBIG2 stream well under 1 KiB, into a page a few hundred pixels wide. The MQ
arithmetic decoder feeds made-up 1-bits once the data ends (T.88 E.3.4), so the
gray-plane decode (`decodeGeneric` over 2^26 pixels, which also has no exhaustion
check) and the draw loop both run in full with almost no real coded data.

The grayscale `decodeGeneric` loop (line 611 path) does not consult
`mq.exhausted` and has no work cap of its own either, so even reaching the draw
loop already costs ~67M MQ-bit decodes from near-zero input.

This reaches KitePDF directly: `KiteImageData` calls `Jbig2Decoder.decode` for a
`/JBIG2Decode` image (`render/KiteImageData.kt:399`) and for a JBIG2 stencil
`/Mask` (`render/KiteImageData.kt:757`), both during page render. KitePDF wraps
the call in `runCatching`, but a hang is not an exception, so the lenient-salvage
wrapper cannot stop it. Opening a crafted PDF hangs the renderer. SECURITY.md
lists "a decode that does not terminate, or that runs for a time unrelated to the
input size" as a reportable vulnerability, and AGENTS.md notes `FuzzTest` never
drives `Jbig2Decoder`, so the fuzzer cannot find this.

#### What should happen
The halftone decode must be bounded by a work budget that is a function of the
input size (or at least the output page), the way `rowsShown` already bounds
generic/text regions. Options: bound `grid cells * pattern area` against a
ceiling before the draw loop; clip the `drawInto` loop bounds to the destination
(`for yy in max(0,-y0) until min(src.h, dst.h-y0)`), which also fixes the
per-cell waste; and give the gray-plane `decodeGeneric` an `mq.exhausted` early
stop as the symbol-dictionary loop has (`Jbig2Decoder.kt:713`). Malformed or
oversized halftone input must fail as `ImageDecodeException` (null from the
public overload), not hang.

#### How to reproduce
Build an embedded JBIG2 stream (no file header) of three segments for the lead
to run through `Jbig2Decoder.decodeChecked(data, null, 100, 100)`:

1. Page info (type 48): width 100, height 100, flags 0.
2. Pattern dictionary (type 16): flags 0x00 (HDMMR 0, template 0), HDPW 255,
   HDPH 255, GRAYMAX 0 (one 255x255 pattern), then a few bytes of coded data.
3. Immediate halftone region (type 22, referring to segment 2): region info
   100x100 at (0,0) combOp 0, flags 0x00, HGW 8192, HGH 8192, HGX 0, HGY 0,
   HRX 0, HRY 0, then a few bytes of coded data.

Expected: a prompt `ImageDecodeException`/null. Actual: ~4.4e12 iterations
(minutes to hours).

Measured through KitePDF on the JVM at `c4e7574` (imagekodec 0.3.0 from Maven
Central): a one-page PDF whose only image is a 100 by 100 `/JBIG2Decode` image
holding the three segments above (114 bytes of JBIG2, 792 bytes of PDF), rendered
with `AwtPdfRasterizer.renderToImage(page, 1.0)`. Only HGW and HGH change:

| Grid | JBIG2 bytes | Render time |
| --- | --- | --- |
| 16 by 16 | 114 | 30 ms |
| 32 by 32 | 114 | 91 ms |
| 64 by 64 | 114 | 395 ms |
| 128 by 128 | 114 | 1,297 ms |

The time grows about four times with each doubling of the grid side, so it
follows the cell count, not the input. The decoder accepts 2^26 cells (8192 by
8192), 4,096 times the last row: about 1.5 hours of CPU for one render of one
page, and the viewer renders again after a zoom.

#### Where
- `imagekodec/src/io/github/yuroyami/imagekodec/codec/Jbig2Decoder.kt:582` (grid cap, no product cap), `:589-592` (skip-map loop, same unbounded grid), `:611` (gray-plane decode, no exhaustion/work check), `:617-626` (draw loop), `:461-468` (`drawInto`, loops whole pattern without clipping).
- Reached from `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:399` and `:757`.

#### Notes
Read from source, loops traced end to end, and measured on the JVM as above. #102 hardened generic and text
regions (`rowsShown`, the >4x-page-width refusal) but did not touch the halftone
grid/pattern product. The grid cap alone (2^26) bounds the gray grid memory, not
the composite work.

### IK-02

**A decoded JPEG 2000 image ignores the PDF dictionary's /ColorSpace, so a CMYK or indexed JPX draws with wrong colours**

- Repo: ImageKodec
- Labels: area:jpx, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #57 (JP2 colour/channel boxes, fixed), KitePDF #72 (JPEG colour space), KitePDF #115 (indexed inline image painted as grey)

#### What happens
For a `/JPXDecode` image, KitePDF decodes through `JpxDecoder.decode(terminal.bytes)`
or the lazy `KiteImageSamples.jpx(terminal.bytes)`, neither of which receives the
PDF image dictionary's `/ColorSpace` (`resolvedCs`). The decoded `KiteImageData`
sets its `resolvedColorSpace` from the JPX's own reported colour space
(`render/KiteImageData.kt:426`, `:440-442`), never from `resolvedCs`.
`JpxDecoder.Result.colorSpace` is only ever `"DeviceRGB"` or `"DeviceGray"`
(`JpxDecoder.kt:1596`). `JpxDecoder.decode` has no parameter for a default or
override colour space, so there is no way for KitePDF to supply it.

A JPEG 2000 codestream that does not self-describe its colour (a raw J2K
codestream, which PDFs commonly embed instead of a full JP2 container, or a JP2
whose `colr` box this decoder cannot apply) then falls back to
`Jp2Color.space`'s guess: `if (channels >= 3) RGB else GRAY`
(`Jp2Color.kt:163`). For a four-component (CMYK) raw codestream this produces an
RGB image from components 0,1,2 with the K component silently dropped and C,M,Y
read as R,G,B (`Jp2Color.plan` sizes `colors` to `space.channels == 3`, so the
fourth channel is never read). For an indexed JPX whose palette lives in the PDF
`/ColorSpace [/Indexed ...]` (not in a JP2 `pclr` box), the raw index samples are
painted as grey or RGB. Both are wrong output.

MuPDF loads the dictionary `/ColorSpace` and passes it to the decoder as `defcs`:
for a codestream with no colour of its own it uses `defcs` when `defcs->n <=
numcomps` and keeps it when `defcs->n == n` (`load-jpx.c`, the
`OPJ_CLRSPC_UNSPECIFIED` branch and the `if (defcs)` block), so a CMYK raw
codestream decodes as CMYK. ISO 32000-1, 7.4.9 makes the image dictionary's
`/ColorSpace` authoritative for a JPXDecode image whose data does not carry a
matching colour space.

#### What should happen
`JpxDecoder` needs an API to accept the container's declared colour space (or to
expose the raw decoded components, as `decodeJpegComponents` does for JPEG), so a
PDF reader can honour `/ColorSpace` when the codestream does not self-describe:
CMYK -> convert through the dictionary's space, indexed -> look up the palette.
KitePDF then passes `resolvedCs` through. Until then a CMYK or indexed JPX in a
PDF renders with wrong colours and diverges from `mutool`.

#### How to reproduce
A PDF with an image XObject: `/Filter /JPXDecode`, `/ColorSpace /DeviceCMYK`,
whose stream is a raw J2K codestream (SOC `FF 4F`) of four components and no JP2
colour box. Render at 96 dpi and compare with `mutool draw`. Expected: CMYK
colours matching mutool. From the code: KitePDF produces a DeviceRGB image from
components 0,1,2 (K dropped), far from mutool. A second fixture: a 1-component
raw J2K with `/ColorSpace [/Indexed /DeviceRGB 255 <palette>]`; expected the
palette colours, actual grey indices.

Measured on the JVM at `c4e7574`: Pillow 12.3 (OpenJPEG) wrote a 64 by 64 raw
codestream of four components (it starts `FF 4F FF 51`), every pixel C = 255,
M = Y = K = 0, which is cyan. In a PDF image with `/ColorSpace /DeviceCMYK`:

| Reader | Pixel at (32, 32) |
| --- | --- |
| Poppler 24.02 (`pdftoppm`) | (0, 172, 239), cyan |
| KitePDF (`AwtPdfRasterizer`, scale 1) | (255, 0, 0), red |

KitePDF reads C, M and Y as R, G and B and drops K.

#### Where
- `imagekodec/.../codec/JpxDecoder.kt:44` and `:56` (no colour-space parameter), `:1596` (`result()` only DeviceRGB/DeviceGray), `codec/Jp2Color.kt:163` and `:212-265` (no-box fallback and `plan` dropping the 4th channel).
- `kitepdf-core/.../render/KiteImageData.kt:422-442` (decoded JPX ignores `resolvedCs`), `render/KiteImageSamples.kt:84-90`.

#### Notes
Read from source; MuPDF `load-jpx.c` confirms the oracle uses `defcs`. The CMYK
case was measured on the JVM as above; the indexed case was not run. JP2
containers that self-describe CMYK (enumerated 12) are handled by `assemble`, so
this is specifically the codestream-without-matching-colour case.

### IK-03

**A PDF JPEG 2000 image is decoded once at load and again at draw, because probe never reports the JP2 colour channel count**

- Repo: ImageKodec
- Labels: area:jpx, kind:perf
- Severity: low
- Confidence: Confirmed
- Related: #475 (the same double decode, fixed for JPEG by using the headers), #82

#### What happens
`KiteImageSamples.jpx` runs `JpxDecoder.decode(bytes, 8)`, a real decode at an
eighth, when the page loads, only to learn whether the image comes out grey or
RGB and whether it carries an opacity channel. Each draw then decodes again at
the drawn reduction. This is the double decode #475 removed for JPEG (which now
reads the frame headers via `ImageKodec.probe` and never decodes at load).

The JPX check-decode exists because `ImageProbe.jp2` does not set
`colorChannels` on the returned `ImageInfo` (the `jp2()` builder, unlike the PNG,
JPEG, GIF, BMP and TIFF builders, has no `.also { it.colorChannels = ... }`), so
it stays 0. `probe` already reports `hasAlpha` for a JP2, and the channel count
is available from `Jp2Color.plan(boxes, comps)`, so grey-vs-RGB could come from
the header alone, as #475 did for JPEG.

The cost is lower than #475's JPEG case because JPEG 2000 is resolution-scalable,
so the 1/8 decode only reads the coarse levels, but it is still a redundant decode
per JPX image at page load, and `info.colorChannels == 0` for a JP2 also means the
facade's `conversion()` channel-count guard (`ImageKodec.kt:522`) never fires for a
JP2 under `ColorTarget.Srgb`.

#### What should happen
`ImageProbe.jp2` should set `colorChannels` from the colour plan (1 for gray, 3
otherwise). Then `KiteImageSamples.jpx` can decide grey-vs-RGB and alpha from
`probe` alone and drop the load-time `decode(8)`, as the JPEG path does, and the
facade's own channel-count guard works for JP2.

#### How to reproduce
In `kitepdf-native-renderer`, render the scanned/JP2 benchmark book with a stage
timer around image load, as #475 measured for JPEG. From the code:
`KiteImageSamples.jpx` -> `JpxDecoder.decode(bytes, 8)` at load, then
`samples.decode(r)` at draw. For the facade gap: `ImageKodec.probe(jp2).colorChannels`
returns 0 for every JP2.

#### Where
- `imagekodec/.../codec/ImageProbe.kt:577-591` (`jp2()` returns `ImageInfo` with no `colorChannels` assignment, unlike `:130`, `:182`, `:246`, `:503`).
- `kitepdf-core/.../render/KiteImageSamples.kt:84-90` (`jpx` factory decodes at load).

#### Notes
Read from source. Lower severity than #475 because the JPX check-decode is at 1/8
resolution and resolution-scalable.

## Capability matrix

This section is about what ships, not about defects. "Ships" means a public API that works end to end from `commonMain`. "Partial" means it works with a limit that a user will meet. "Missing" means there is no public way to do it. Each KitePDF cell was read from the public API dumps (`*/api/*.klib.api`), the docs and the source at `c4e7574`, and some were run. The other columns summarize the public documentation of each project as of late 2026, at the level of "it has an API for this"; they are context, not measurements. A `-` means not compared.

### PDF

| Capability | KitePDF | MuPDF | PDFium | pdf.js |
| --- | --- | --- | --- | --- |
| Render pages | Ships: AWT, Android, CoreGraphics, Canvas2D, Skia, Compose and a pure-Kotlin raster canvas | Ships | Ships | Ships |
| Plain and positioned text | Ships | Ships | Ships | Ships |
| Ligature and compatibility folding in text and search | Missing (finding KP-44) | Ships (expands ligatures by default) | - | Ships (normalizes for search) |
| Search options beyond case (whole word, diacritics) | Missing | - | Ships (whole word) | Ships (whole word, diacritics) |
| Word boundaries | Missing (#639) | - | - | - |
| Structure tree of a tagged PDF | Missing (#640); a reading order ships | - | Ships | - |
| Fill AcroForm fields | Ships | Ships | Ships | Ships |
| XFA forms | Missing (detects `/XFA` only) | Missing | Optional build | Partial |
| Form scripts | Ships through `kitepdf-javascript` (experimental) | Ships (MuJS) | Optional (V8) | Ships (sandboxed) |
| Flatten forms or annotations | Missing | - | Ships | - |
| FDF or XFDF data exchange | Missing | - | - | - |
| Draw annotations | Ships | Ships | Ships | Ships |
| Create, change or delete annotations | Missing (#638); raw objects only | Ships | Ships | Ships (free text, ink, stamp, highlight) |
| Viewer highlights saved into the file | Missing: `KiteHighlight` is an overlay that the app stores | - | - | Ships |
| Redaction that removes content | Ships, with listed limits (#630 to #633) | Ships | Missing | Missing |
| Page insert, delete, rotate, reorder, merge | Ships | Ships | Ships | - |
| Object-level content editing | Partial: operator lists and stamps | Ships | Ships (page objects) | Missing |
| Create PDFs | Partial: a low-level builder with no text layout and no shaping (findings KP-43 and KP-100) | Ships, including HTML and CSS to PDF (Story) | Partial | Missing |
| Open RC4, AES-128 and AES-256 files | Ships | Ships | Ships | Ships |
| Encrypt a new document | Ships (AES-256) | Ships | Missing | Missing |
| Keep, change or remove the encryption of an existing file | Missing: `saveRewritten()` drops it (finding KP-18) | Ships | - | Missing |
| Validate signatures | Ships (CMS, CAdES, PKCS #1, timestamps; RSA and ECDSA) | Ships | Read only | Missing |
| Sign | Partial: prepares the field and embeds a CMS that the app makes | Ships | Missing | Missing |
| Long-term validation data (DSS) on write | Missing | - | Missing | Missing |
| Incremental save | Ships | Ships | Ships | Ships |
| Rewrite with object streams | Ships | Ships | - | Missing |
| Open while downloading, or linearized output | Missing: the whole file must be one `ByteArray` | Ships (progressive loading) | Ships (`FPDFAvail`) | Ships (range requests) |
| Attachments | Partial: read only | Ships | Ships | Read |
| Outline and page labels | Partial: read only | Ships | - | Read |
| XMP metadata | Partial: read only (the Info dictionary is writable) | - | - | Read |
| Optional content (layers) | Partial: default visibility only, no API to switch a layer | Ships | - | Ships |
| OCR | Missing | Ships (with Tesseract) | Missing | Missing |
| Print | Missing | - | Platform | Browser |

### Books and other formats

| Capability | KitePDF | MuPDF | Readium toolkits |
| --- | --- | --- | --- |
| EPUB 2 and 3, reflowable and fixed layout | Ships (own layout engine, no web view; every applicable "must" of the W3C suite passes) | Ships (own engine, smaller CSS) | Ships (platform web view) |
| EPUB scripts | Ships through `kitepdf-javascript` (own DOM) | Missing | Ships (web view) |
| EPUB audio, video and media overlays | Ships through `kitepdf-media` | Missing | - |
| Highlights anchored to text that survive a reflow | Missing: highlights hold page coordinates, as the docs say | - | Ships (locators with text context) |
| DRM (LCP) | Missing | Missing | Ships |
| CBZ | Ships | Ships | Ships |
| CBR, CB7 | Missing | - | - |
| SVG | Ships | Ships | - |
| XPS and OpenXPS | Ships | Ships | - |
| FB2 | Missing | Ships | - |
| MOBI, AZW3 | Missing | Ships (MOBI) | - |
| Plain text, HTML and image files as documents | Missing | Ships | - |
| OPDS catalogues | Missing | - | Ships |

### Where KitePDF stands out

- One engine in common Kotlin for every target, so a page renders the same way on Android, iOS, the desktop JVM and the web. None of the others runs in `commonMain`.
- A Compose-native viewer that shows PDF, EPUB, CBZ, SVG and XPS with one composable and one state.
- EPUB layout without a web view, with shaping, bidi, hyphenation in about 60 languages, ruby and vertical text.
- Redaction and signature validation in pure Kotlin.

## Feature proposals

Ranked by need, reach and cost. These are proposals, not defects. None is filed yet unless an issue is named.

| # | Proposal | Need | Reach | Cost | Notes |
| --- | --- | --- | --- | --- | --- |
| P1 | Fold text for search and extraction: NFKC with a position map, ligature expansion, optional diacritic and whole-word modes | High | Every PDF app | Low | Pairs with finding KP-44. `Normalizer.kt` already exists in `kitepdf-epub`. |
| P2 | Keep the encryption on `saveRewritten()`, and let the editor encrypt, change or remove a password | High | Every app that edits protected files | Low to medium | Pairs with finding KP-18. |
| P3 | Annotation authoring: highlight, underline, strike-out, note, free text, ink and stamp, with appearance streams, and a way to save viewer highlights as `/Highlight` annotations | High | Readers, review apps | Medium | #638. Appearance generation already exists for fields (`AnnotationAppearance`). |
| P4 | Text-anchored ranges for reflowable books (two `KiteBookmark.Flow` offsets plus some text context), so highlights survive a change of font or viewport | High | Every EPUB reader | Medium | The docs already state the limit. |
| P5 | Open from a random-access source (a file handle, an HTTP range reader) instead of one `ByteArray`, and write linearized files | High for large files and the web | Large scans, servers, web apps | High: the parser and every cache | MuPDF and PDFium both read lazily. A file over 2 GiB cannot open in KitePDF today. |
| P6 | Text layout in `PdfBuilder` (paragraphs, wrapping, shaping and bidi) by reusing the EPUB layout engine, or HTML and CSS to PDF as MuPDF's Story does | High | Report, invoice and ticket generation in KMP | Medium: the engine exists | Today an Arabic or Devanagari string written with an `EmbeddedFont` comes out unshaped, and right-to-left text in the wrong order. |
| P7 | FB2, plain text and HTML documents through the EPUB engine | Medium | Russian and Eastern European readers, web content | Low | FB2 is XML with a small vocabulary. |
| P8 | MOBI and AZW3 (KF8) without DRM | Medium | Kindle libraries | Medium | KF8 is XHTML and CSS behind PalmDOC or HUFF/CDIC compression. |
| P9 | Switch optional content layers at render time | Medium | Maps, engineering drawings | Low | `PdfOptionalContent` already parses the groups. |
| P10 | Form flattening, and FDF or XFDF data exchange | Medium | Form back offices | Low to medium | |
| P11 | Edit attachments, the outline and page labels | Medium | Editors | Low | |
| P12 | Print through each platform (Android `PrintDocumentAdapter`, `UIPrintInteractionController`, AWT `PrinterJob`, the browser) | Medium | Desktop and office apps | Medium | |
| P13 | CBR and CB7 comics | Low to medium | Comic readers | High for CBR (RAR licence terms) | |
| P14 | DRM (Readium LCP) | Low to medium | Library and store apps | High | |

## Checked and held up

These claims were tested at `c4e7574` on the JVM (Linux, JDK 21) and held:

- `kitepdf-pdf` JVM suite: 880 tests, 875 pass, 0 fail, 5 skip for missing tools (`mutool`, `opj_compress`, the DroidSansFallback font, the GoldenHour corpus file).
- "Thread-safe for concurrent reads and rendering" (`PdfDocument` KDoc): 8 threads rendering, extracting structured text and searching the 7 pages of `public-joss-10131.pdf` for 3 rounds, with all three cache budgets set to 1 byte to force eviction on every lookup. No exception, no hang.
- AES-256 output of `PdfBuilder.encrypt` opens in Poppler 24.02 with the user password, which reports `algorithm:AES-256` and the permissions that were set. `saveIncremental()` on that document keeps the encryption, and Poppler reads the edit.
- Redaction of a text line followed by `saveRewritten()`: the text is gone from every stream (checked by decompressing each stream), `pdftotext` does not find it, and `saveIncremental()` after a redaction throws, as documented.
- EPUB memory on a phone-sized heap (CLAUDE.md, #218): with `-Xmx192m`, all 45 books of `corpus/epub` open, lay out every chapter at 360 by 640 points, and give the text of every page. The heap that each book holds while open runs from 2 MiB to 52 MiB for the text-heavy books, and 92 MiB and 94 MiB for the two illustrated Lewis Carroll editions (18 MB and 24 MB files). Eight open and layout cycles of `A Christmas Carol` leave no retained growth.
- Render time: warm, in one JVM, `AwtPdfRasterizer.renderToImage` at scale 2 takes a median of 17 to 63 ms per page on seven corpus PDFs (JOSS papers, the 28-page Arabic Wikipedia PDF, the vertical Japanese book, two PDF/A colour files). Open takes under 1 ms. No reference was timed under the same conditions, so these numbers are context, not a comparison.

## What the lanes checked and found sound

### PDF parsing, decryption, filters and archives

#### Confirmed here, already written up by another lane

These were found and traced independently in this lane; the other lane's entry is complete, so they are not repeated.

- **L4, "An encrypted PDF whose objects live in object streams does not open"**: `decodeObjectStream` and `PdfRepair.objStreamMembers` never decrypt the object stream. One thing to add to its fix: repair runs before the security handler exists (`PdfDocument.kt:1071` builds the repair, `:1072` the handler), so `objStreamMembers` has no key; the handler must be built from the `/Encrypt` that the scan found before pass 3 decodes object streams. An RC4 variant of the reproduction is `make_enc_objstm.py` (`encrypted-objstm.pdf`, poppler prints "Hello from an object stream").
- **L9, "A /Kids that names its own node sends the page label, named destination and form field walks into endless recursion"**, and **L9, "The name tree and revision walkers stop at depth 64 but keep no visited set"**: same five walks. Test files: `cycle-dests.pdf`, `cycle-pagelabels.pdf`, `cycle-fields.pdf`, `fanout-javascript.pdf` (poppler prints "loop in NameTree" on the last and goes on).
- **L9, "The parser's nesting cap and the resolution depth cap multiply"**: same chain, test file `depth-product.pdf` (250 x 250). Streams are accepted inside arrays and dictionaries (`Parser.kt:69`, through `readDictionaryThenMaybeStream`), which MuPDF does not do; refusing that also breaks the product.
- **L9, "The object cache keeps a copy of every stream the document has read"** and **"A PDF keeps every page's decoded content"**: same. One more source of growth: `PageRenderer.loadXObjects` resolves every XObject of the resources, not only the ones the page draws, so a file whose pages share one resource dictionary copies all its images into the object cache on the first render.

#### Checked and not reported

- `ZipReader`: ZIP64, lying sizes and offsets, mismatched local headers, data descriptors, duplicate names, encryption and entry counts are all bounded or refused, and each refusal is deliberate and tested (`ZipReaderTest`, CHANGELOG). A ZIP with bytes after its end record is refused (the record must end the file); Java's `ZipFile` accepts it, but the strictness is deliberate.
- The decompression cap is per filter call; nothing bounds a page or a document. A `/Contents` array that names one bomb many times decodes it once per entry. MuPDF has no page budget either (it streams with constant memory, but its time is the same), so this is not reported against the reference.
- LZW code width and `EarlyChange`, ASCII85 `z` and short groups, RunLength, ASCIIHex, predictor overflow of `Columns x Colors x BitsPerComponent` (throws, caught), xref stream `/W` and `/Index` (no allocation before data), `startxref` past the end, `/Prev` loops, hybrid `/XRefStm`, indirect and self-referencing `/Length`, an object stream inside an object stream: each fails into the salvage path without a crash.
- The R6 password check runs the hardened hash up to six times for a wrong password (owner and user are the same bytes and are both tried), a constant cost; not reported.

### PDF writing, forms, redaction and signatures

#### Checked and found sound, or already documented

- `PdfObjectWriter.formatReal` never writes an exponent, NaN or Infinity, and does not use `Double.toString`, so Kotlin/JS prints the same. Strings with bytes outside 0x20 to 0x7E go out as hex; names escape `#`, delimiters and non-printing bytes. `CffSubsetter.dictReal` uses `toString`, but both the JVM and JS forms encode as valid CFF reals.
- Streams: `/Length` is always the written byte count, and the `stream` and `endstream` keywords have their end-of-line markers.
- `PdfBuilder.encrypt` and `edit()` of an encrypted file require a random source; there is no unsafe default. R6 `/U`, `/UE`, `/O`, `/OE` and `/Perms` follow ISO 32000-2 Algorithms 8 to 10. The `/Encrypt` dictionary, `/XRef` streams and an unencrypted `/Metadata` stream are left in clear.
- `Rsa.verifyPkcs1` compares the whole DigestInfo against its two DER forms, so no Bleichenbacher-style forgery. ECDSA checks `r` and `s` ranges, point validity and hash truncation. `BigNat` division follows Knuth D with the overflow guards in place.
- `TrueTypeSubsetter` pulls in composite children and rewrites their ids; `SfntWriter` checksums are by the spec.
- Already documented, not reported: Standard 14 text outside Latin-1 becomes `?` and accented letters show as other glyphs (`docs/writing.md`); embedded text is not shaped; the editor's field appearances always use Helvetica; `saveRewritten` drops encryption and invalidates signatures; redaction of soft masks, `/LW`, broken `/Rect` and `/EmbeddedFiles` (#630 to #633).

#### Not finished

- `PdfFormState`, `RichText` and `AnnotationAppearance` were read only for the paths above.
- No fuzzing of `Asn1`, `Cms` or `X509`; the bounds checks read correctly.
- `CffSubsetter` always writes a CID-keyed font, and a name-keyed source font that builds accents with `seac` loses them even with `subset = false` (the Type 2 charstring spec has no `seac` in CID fonts). Not verified with a real font, so not reported.

### Canvases, headless renderers, XPS, CBZ, net

#### Appendix: what each canvas does for each feature

Read from source at `c4e7574b`. "Placeholder" is the grey box of #184. Where a cell names Skia's dash limit, the dashing is Skia's own: android.graphics, Compose on every target and Chrome's canvas all stroke with Skia.

| Feature | AwtCanvas | AndroidNativeCanvas | CoreGraphicsCanvas | Canvas2dCanvas | SkiaCanvas | ComposeCanvas | KiteRasterCanvas |
|---|---|---|---|---|---|---|---|
| Embedded font, no glyph has an outline | host font | host font | host font | host font | nothing | nothing | nothing |
| Host glyph outlines (stroke and clip text without a program) | yes | API 34 and up; below, filled run and advance boxes | none: filled run, advance boxes | none: filled run, advance boxes | yes | yes | only with `hostOutlines` |
| Luminosity mask weights | 77, 150, 29 / 256 | 0.30, 0.59, 0.11 | 77, 150, 29 / 256 | 77, 150, 29 / 256 | 0.2126, 0.7152, 0.0722 | 0.299, 0.587, 0.114 | 0.30, 0.59, 0.11 |
| Dash pattern finer than a pixel | no guard: seconds a stroke, then Marlin's exception | solid past 1 M dashes | not checked | browser (Chrome: solid past 1 M) | solid past 1 M dashes | solid past 1 M dashes | solid past 100 k dashes |
| Negative dash phase | clamped to 0 (`AwtCanvas.kt:129`) | modulo | not checked | modulo | modulo | modulo | modulo |
| Knockout group | Src per paint | SRC | Copy | erase, then paint | SRC | Src | ignored: paints composite over each other |
| Non-isolated group with alpha: layer starts from the backdrop | yes | no, transparent | no, transparent | yes | yes | yes, except Android | no, transparent |
| Soft mask /TR that is not nearly linear | exact table | pixel gate, at most 1 MP, scaled up | exact table | exact table | exact table | table on Skia, pixel gate on Android | exact table |
| Radial shading between two circles | own paint | API 31 and up; below, an image of pixels | native | native | native | native, or an image of pixels | per pixel |
| Image of a kind other than RAW | placeholder | placeholder | placeholder | placeholder | placeholder | placeholder | nothing |
| RAW image above 40 MP drawn at more than half its size | placeholder | placeholder | placeholder | placeholder | placeholder | placeholder | nothing |
| Glyph offsets (`xOffset`, `yOffset`) of host-font text | ignored | ignored | applied | ignored | ignored | ignored | applied (outline path) |

The last row is harmless today, because the EPUB layout moves a host glyph's origin itself and zeroes its offset (`EpubDocument.kt:2349-2357`), but it is a trap for the next caller of the contract.

### KiteJS and the script integration

#### Checked and found sound

- JNI strings cross as UTF-16 (`NewString`, `GetStringChars`) in both directions, so supplementary characters, embedded NUL and lone surrogates need no modified UTF-8. `NewStringUTF` is used only for the ASCII version string.
- No JNI local reference is created in a loop on the native side; host callbacks run Java code whose locals are freed when `CallStaticIntMethod` returns, and a pending Java exception is cleared right after each callback (`failed`).
- An engine is confined to its thread on every target (`currentThreadToken` checked on every call, including `close`), `close` refuses while a script runs (`depth > 0`), handles released by the collector are queued atomically and freed on the engine thread.
- Android natives are linked with `-Wl,-z,max-page-size=16384`, and the keep rules in `kitejs-quickjs-android-natives` keep `JniNatives` and `JniHost` whole.
- The JVM library copy goes to a private, non-symlinked folder named for the library's SHA-256, through an atomic move, with a fresh copy as fallback.
- QuickJS stops regex backtracking and comparator-driven sorts at the budget (both poll), deep `JSON.parse` throws a RangeError, and a memory limit ends a runaway allocation with "out of memory" (all run on the probe build). Rhino charges regex backtracking to the budget (`NativeRegExp`).
- KitePDF's script threads have 16 MiB stacks on every native family and the JVM, and pass an 8 MiB QuickJS limit, so the Apple 512 KiB problem above does not reach `PdfScriptRunner` or `EpubScriptRunner`.
- Every outbound request of a PDF script (`launchURL`, `submitForm`, `print`, `mailDoc`, `mailForm`, `mailMsg`, `closeDoc`, `saveAs`, `execMenuItem`) arrives at `onRequest`, which drops it by default. `openDoc` and `newDoc` answer null and `importAnFDF` false; `exportDataObject`, `importDataObject` and `getURL` are not defined, so a call throws a TypeError in the script and reaches nothing. The viewer does not route these through `KiteExternalLinks`; the host decides, as `docs/javascript.md` says.
- KitePDF on JS and Wasm today gets `@js-joda/timezone` through KiteJS 0.7.0, which still declared the npm package. KiteJS's unreleased change drops that declaration (KiteJS CHANGELOG), and KitePDF's README and docs never mention the package, so the next KiteJS upgrade needs a line in KitePDF's docs.

### Concurrency, memory and platform semantics

#### Appendix A: recursive walkers and their limits

| Walker | Limit | Status |
|---|---|---|
| PDF object parser containers (`Parser.nested`) | 256 per parser | multiplies with the next row, finding 4 |
| Object resolution chain (`withCycleClaim`) | 256, cycle claim per thread | see finding 4 |
| Page tree (`walkPageTree`) | visited set, depth 50 | sound |
| Outline (`PdfOutline`), `outline` mapping | visited set, 64; 64 | sound |
| Form XObjects, Type 3 glyphs, annotation appearances | 15 per renderer | reset by the soft mask sub-renderer, finding 1 |
| Soft mask groups | none | finding 1 |
| Tiling patterns | none | finding 1 |
| Optional content visibility expressions | 32 (#55) | sound |
| Graphics state, marked content, clips | 4096, 4096, 1024 | sound |
| Colour spaces | 8 | sound |
| Functions (stitching, arrays) | none | finding 3 |
| PostScript calculator braces | none | finding 3 |
| Name and number trees (`NameTreeWalker`) | 64, no visited set | finding 5 |
| Page labels, `/Names /Dests`, AcroForm `/Kids` | none | finding 2 |
| Field `/Parent` chains | 32 | sound |
| Structure tree, reading order | visited set, 256 | sound |
| Text extraction forms | 15 | sound |
| Revision changes (`walkField`, `walkPages`) | 64, no visited set | finding 5 |
| ASN.1 (signatures), rich text `/RV` | 64, 64 | sound |
| TrueType composites, CFF and Type 1 subroutines, GSUB lookups | depth and active set, 10, 10, 64 | sound |
| Bidi embedding, mesh subdivision | 125, 6 | sound |
| `KiteXml.parse` | iterative, no depth cap on the tree; entities 16 levels plus a budget | the tree it builds is safe only where the walker caps |
| EPUB markup, navigation, NCX (`HtmlParser`) | 32 (#450) | sound |
| EPUB CSS nesting and selectors | 32 (#451) | sound |
| EPUB MathML, frames | 64, 8 | sound |
| EPUB media overlay | iterative | sound |
| EPUB script DOM and the layout of a scripted tree | none | finding 7 |
| SVG `<use>` and render walk, patterns, masks, filters, `href` chains | 32, 1, 2, 4, 16 | sound |
| XPS canvas walk, resource dictionaries | 64 plus a work budget, 32 plus the budget | sound |

#### Appendix B: checked, no finding

- `KiteLock` is reentrant on every target: `ReentrantLock` on the JVM and Android, an owner word and a depth on Kotlin/Native (spin, yield, then 500 us sleeps, #388), and a no-op on the single-threaded JS, Wasm and WASI targets. No `withLock` block holds a suspend call; the lock order is `chapterLocks` then `tableLock`, never the reverse.
- Every `lazy` uses the default or `SYNCHRONIZED` mode. Shared caches sit behind a `KiteLock`, or document a benign race (`CharText`, `IccProfile.renderings`, `KiteImageData.renderings`, `KiteImageSamples.failed`). `PdfDocument` parses outside its lock and publishes the first result.
- The viewer's catch sites around suspend calls rethrow `CancellationException`, as do `KiteReadAloud`, `EpubRemote` and `kitepdf-net`, except `askScript` (finding 12). Every page renderer checks `KiteCancellation` (PDF every 32 operators); the gap is the PDF soft mask sub-renderer, finding 1. EPUB chapter layout cannot be cancelled, but the loader lays out one chapter at a time.
- No `freeze`, `@SharedImmutable` or `Worker` is left. `@ThreadLocal` is used for per-thread state only. The script thread's `staticCFunction` disposes its `StableRef`.
- The PDF writer formats reals without `Double.toString`, so JS and the JVM write the same numbers.
- Compose Multiplatform 1.13.0-alpha02, the version the viewer builds with, turns a NaN alpha of an sRGB colour into 0 rather than throwing (read from the desktop bytecode of `ColorKt.Color`), so a NaN opacity from SVG (`"NaN".toDoubleOrNull()` is NaN) paints nothing instead of failing the page.
- Largest document per platform: one `ByteArray`, so at most 2^31 - 1 bytes anywhere, in practice the Android heap. The POSIX reader refuses a larger file; the Apple readers do not (finding 10). docs/loading.md states that every source is read into memory.

### Distribution, docs, tests and CI

#### Checked and not reported

- README and `docs/*.md` code samples: every named argument, method, property and import I could resolve matches the current source (KiteDocView, KiteDoc, the builder DSL, the rasterizers, `KiteReadAloud`, `PdfScriptRunner(...).use`, `openUrl`, XPS, media). The link check of the three repositories found no broken relative link or anchor.
- README platform table: matches every module's `platforms` and `minSdk` (the Skia renderer inherits minSdk 21, and 24 is Skiko's own floor).
- The Space repository for Skiko on Android answers 301 to `packages.jetbrains.team/maven/p/cmp/dev`, which serves `skiko-android-0.153.0.pom` (200).
- KiteJS Android natives: built with `-Wl,-z,max-page-size=16384`, and the R8 keep rules for `JniNatives` and `JniHost` ship in `META-INF/proguard/`. KitePDF uses no reflection or ServiceLoader.
- Hyphenation licences: no GPL-only, LGPL-only or unlicensed set is bundled.
- `kitepdf-core` does not expose ImageKodec types; `kitepdf-javascript` does not expose KiteJS types.
- Version check and `@Ignore` (none). Test classes skipped by name on the JVM: none in KitePDF or ImageKodec. KiteJS has three opt-in asm probes without a `Test` suffix (`AsmRealModuleProbe`, `AsmProfileProbe`, `AsmCodeHistogram`); the toolchain turns `--include-classes` into a JUnit class-name filter, so they should still run when named, and I did not report them.

### Checked by the other lanes, from their reports

- Viewer: every URL path of the viewer goes through `offerExternalLink`, and PDF script requests go to the host's `onRequest`. The cache keys carry the theme and the paper. `contentPadding` with `fit = PAGE`, PDF `/PageLayout` with `reverseLayout` and `firstPageAlone`, an out-of-range `SinglePage`, `RasterGate`, the locking of `PageBitmapCache`, and the saver are sound.
- PDF rendering and fonts: the lane dropped as ambiguous or unproven a link border drawn when `/C` is an empty array, `NoRotate` (neither oracle implements it), an empty text clip read as no clip, and the cost of Type 0 sampled functions (MuPDF has the same within its sample cap).
- EPUB: remote fetching is off unless the app sets a fetcher, and then only `https` is fetched; `file:` URLs, and `@import` or `<link>` of a remote style sheet, never fetch. Each hyphenation language is its own object, loaded and cached on first use.
- ImageKodec: the JPEG frame header (component count limited to 1, 3 or 4, sampling factors 1 to 4), PNG dimensions and budget, GIF LZW and frame budgets, the TIFF IFD cycle guard, CCITT with zero rows, embedded BMP payloads, AVIF grid tiles, the division of premultiplied alpha for AVIF and JPEG 2000, and the JBIG2 symbol dictionary and text region loops are sound. The halftone grid is the one JBIG2 path with no work budget.

## What the audit did not cover

Each lane listed what it could not finish. These are open questions, not findings.

- No Apple, Android device, JavaScript or Wasm target ran. Every claim about those targets is read from source: a stack overflow ending a Kotlin/Native process, the `@Throws` gaps on the Swift boundary, Safari's canvas memory limit, `NSData` lengths, the web's single thread.
- `mutool`, PDFium and HarfBuzz were not available. Poppler 24.02 served as the independent reader, so a few comparisons name Poppler where CONTRIBUTING.md names MuPDF.
- Viewer: a deep pass over IME and focus in `KiteFormInput` and `KiteChoiceInput`; the web behaviour of prefetch and of building accessibility nodes on the UI thread; Android's `LazyLayoutCacheWindow`; whether a custom `PdfScriptHandler` that suspends mid-call breaks the "one call at a time" contract (the bundled `PdfScriptRunner` serializes itself).
- PDF rendering: knockout and soft mask compositing (`KnockoutCanvas`, `GroupRasters`), the overprint simulation, `PdfReadingOrder` and `StructureTree` beyond their bounds, and a full comparison of `RedactionEngine` with the renderer's text state machine (its fixed text box of -0.35 to 1.0 em may miss glyphs drawn outside it).
- PDF writing: `PdfFormState`, `RichText` and `AnnotationAppearance` beyond the traced paths; no fuzzing of `Asn1`, the CMS reader or the X.509 reader; `CffSubsetter` writes CID-keyed output, so a `seac` accent of a name-keyed source font may be lost (not tested with a real font).
- EPUB: bidi against UAX #9 and line breaking against UAX #14 beyond spot checks, the chaining and reverse chaining GSUB lookups, the Indic, Khmer, Myanmar and USE shapers, the normalizer, Brotli beyond its bounds; the script DOM (timers, storage, `window.open`) and fixed-layout viewports had a light pass.
- Canvases: CoreGraphics with a tiny dash pattern and a negative dash phase (needs a Mac); the size of the transparency layer of each translucent XPS element (not measured); Skia images, paints and paragraphs that wait for the collector to be freed (judged too weak to file).
- KiteJS: the legacy `kitejs` artifact; closing a runner from the EPUB callbacks; whether `close()` ends nested `calculateNow` rounds; nothing ran on Rhino or on an Apple target.
- Concurrency: no data race was found, but not every write of `KiteDocViewState` was reviewed; not every `toDoubleOrNull` site was checked for NaN; binary sizes per target were computed from the source strings, not built.
- Distribution: no consumer built against artifacts that the Kotlin Toolchain published; whether a module's `freeCompilerArgs` replace the template's `-Xexport-kdoc` on native targets; whether AGP packages KiteJS's `.so` files from the new plain natives jar; ImageKodec's docs samples got the link check only.

## Reproduction material

`audit-repro.tar.gz`, which came with this audit and is not in the repository, holds the generators, the small fixtures and the lead's probe classes, with a README that says how to run them. Paths in the findings that start with `audit-repro/` are relative to that archive; the rendering lane's `pdfs/<file>` and `fonts/<file>` are in `audit-repro/L3/`, and the concurrency lane's PDFs are in `audit-repro/L9/pdfs/`. Several of its files are hostile on purpose (decompression and recursion bombs, files that hang a reader), so open them only in a test JVM with a timeout.
