# Missing Features and Release Gaps

Last audited: 2026-07-24

KitePDF's JVM/AWT renderer is in good shape: the current local MuPDF
differential run covers 36 pages with zero render, blank, oracle, or comparison
failures, a mean MAE of `0.0062`, and a worst-page MAE of `0.0281`.

That result is intentionally narrow. It validates the AWT backend and includes
three locally supplied, git-ignored PDFs. A clean checkout has no tracked
real-world PDF or EPUB corpus, and the Android, Apple, Canvas2D, and Compose
rendering implementations do not run through that oracle.

This file tracks concrete gaps found in the implementation. Items are ordered
by release risk, not by implementation size.

## Release blockers

### 1. Make page edits cumulative

`PdfEditor.editPageContent`, `stampPage`, and `redactRegions` rebuild from the
original `PdfPage` snapshot. They do not read the already staged page
dictionary and content stream. A second edit to the same page can therefore
replace the first one.

This is especially serious for redaction: two separate `redactRegion` calls can
leave content from the first region in the rewritten document.

Source:

- [`PdfEditor.editPageContent`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/PdfEditor.kt#L192)
- [`PdfEditor.stampPage`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/PdfEditor.kt#L219)
- [`PdfEditor.redactRegions`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/PdfEditor.kt#L633)
- The existing staged-object helper:
  [`effectivePageDict`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/PdfEditor.kt#L605)

Acceptance criteria:

- Two stamps on one page both survive incremental and rewritten saves.
- Two content transforms compose in call order.
- Two separate redaction calls remove both regions from rendered output,
  extracted text, reachable objects, and raw rewritten bytes.
- Mixed operations such as stamp → edit → redact preserve every intended
  mutation.

### 2. Complete the redaction reachability model

Current redaction has two additional structural gaps:

1. Shared Form XObjects are guarded only by object number. If the same form is
   invoked with different transforms, the first invocation's mapped rectangles
   win and later invocations are skipped. Rewriting the shared object can also
   over-redact invocations outside the selected page region.
2. Removing a widget annotation from a page's `/Annots` array does not remove
   its field from `/AcroForm /Fields`. Values such as `/V`, `/DV`, `/T`, and
   appearance streams may remain reachable after `saveRewritten()`.

Source:

- [`redactedForms`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/PdfEditor.kt#L692)
- [`redactFormXObject`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/PdfEditor.kt#L700)
- [`pruneIntersectingAnnots`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/PdfEditor.kt#L766)
- [`RedactionEngine.FormHit`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt#L58)

The fix should accumulate form-space rectangles per invocation or clone a
shared form when invocation-specific redaction is required. Cycle detection
must be separate from the set of already processed rectangles.

Acceptance criteria:

- A shared form drawn under two different transforms is redacted correctly at
  each selected invocation.
- Unselected invocations remain unchanged.
- Redacted widget values and appearance streams are no longer reachable from
  `/AcroForm`.
- Rewritten bytes do not contain the removed field value.

Vector paths intersecting a redaction region also remain in the content stream.
That limitation is documented, but it must be closed before advertising
arbitrary-region redaction as fully destructive for every content type.

### 3. Bring non-JVM renderers to content parity

The platform backends are separate implementations, and several currently drop
common content:

- CoreGraphics returns immediately for fonts without embedded outlines, so
  Standard-14 and other non-embedded fonts render blank.
- Android and CoreGraphics do not create platform images from
  `ImageXObject.Kind.RAW`.
- Successful JPEG, JPX, and JBIG2 decoding normally produces `RAW`, so the
  missing path affects much more than raw Flate image streams.
- Canvas2D paints a placeholder for every image.
- Compose's bitmap transform uses only translation and scale magnitudes; it
  drops CTM rotation, reflection, and shear.
- CoreGraphics accepts an image alpha argument but does not apply it.

Source:

- [`CoreGraphicsCanvas.drawGlyphs`](kitepdf-native-renderer/src/appleMain/kotlin/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt#L160)
- [`AndroidNativeCanvas.drawImage`](kitepdf-native-renderer/src/androidMain/kotlin/io/github/yuroyami/kitepdf/nativerenderer/AndroidNativeCanvas.kt#L278)
- [`CoreGraphicsCanvas.drawImage`](kitepdf-native-renderer/src/appleMain/kotlin/io/github/yuroyami/kitepdf/nativerenderer/CoreGraphicsCanvas.kt#L283)
- [`Canvas2dCanvas.drawImage`](kitepdf-native-renderer/src/jsMain/kotlin/io/github/yuroyami/kitepdf/nativerenderer/Canvas2dCanvas.kt#L268)
- [`ComposeCanvas.drawBitmap`](kitepdf-compose-viewer/src/commonMain/kotlin/io/github/yuroyami/kitepdf/compose/ComposeCanvas.kt#L407)
- [`ImageXObject.from`](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/render/ImageXObject.kt#L112)

Acceptance criteria:

- Every backend has golden coverage for Standard-14 text, embedded-outline
  text, RAW RGB/gray/indexed images, image masks, image soft masks, and decoded
  JPEG/JPX/JBIG2 images.
- Rotated, reflected, and sheared image CTMs match the MuPDF reference.
- Image alpha is honored consistently.
- Canvas2D paints already decoded RAW pixels synchronously; only genuinely
  undecoded encoded fallbacks may require an asynchronous preload API.

### 4. Use the page's complete display geometry in rasterizers

The AWT, Android, and Apple convenience rasterizers size output from unrotated
MediaBox width/height and pass a plain Y-flip. That bypasses
`PdfPage.pageToDeviceBase()`, which already accounts for `/Rotate`, `/CropBox`,
and non-zero page-box origins.

`/UserUnit` is parsed but is not incorporated into display dimensions or
transforms.

Source:

- [`AwtPdfRasterizer`](kitepdf-native-renderer/src/jvmMain/kotlin/io/github/yuroyami/kitepdf/nativerenderer/AwtPdfRasterizer.kt#L20)
- [`AndroidPdfBitmapRenderer`](kitepdf-native-renderer/src/androidMain/kotlin/io/github/yuroyami/kitepdf/nativerenderer/AndroidPdfBitmapRenderer.kt#L23)
- [`ApplePdfRasterizer`](kitepdf-native-renderer/src/appleMain/kotlin/io/github/yuroyami/kitepdf/nativerenderer/ApplePdfRasterizer.kt#L57)
- Correct Skia reference:
  [`PdfPageRasterizer`](kitepdf-skia-renderer/src/commonMain/kotlin/io/github/yuroyami/kitepdf/skia/PdfPageRasterizer.kt#L38)
- [`PdfPage.pageToDeviceBase`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/PdfPage.kt#L166)
- [`PdfPage.userUnit`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/PdfPage.kt#L126)

Acceptance criteria:

- Output dimensions use the rotated display box at the requested scale.
- CropBox, non-zero MediaBox origins, rotations 0/90/180/270, and `/UserUnit`
  match MuPDF in both geometry and pixels.
- All convenience rasterizers share the same transform contract.

### 5. Decode prefix filters before terminal image codecs

A valid image stream may use a chain such as:

```pdf
/Filter [/ASCII85Decode /DCTDecode]
```

KitePDF correctly classifies the terminal codec as JPEG, JPX, or JBIG2, but
passes `stream.rawBytes` directly to that decoder. Prefix filters are therefore
still encoded, and the terminal decode fails or degrades to a placeholder.

Source:

- [`ImageXObject` filter classification](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/render/ImageXObject.kt#L112)
- [`pickKind`](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/render/ImageXObject.kt#L369)

Acceptance criteria:

- Prefix filters are decoded in order before DCT, JPX, or JBIG2 processing.
- Decode parameters remain aligned with their corresponding filters.
- Regression fixtures cover ASCII85+DCT, ASCIIHex+JPX, and a wrapped JBIG2
  stream.

### 6. Harden the Skia differential harness

The native-renderer/AWT harness now treats oracle failures as failures. The
separate Skia harness still has older false-green behavior:

- A failed MuPDF render becomes `score = null`, then the gate treats it as
  `0.0`.
- Only the overlapping minimum dimensions are compared, hiding geometry
  mismatches.
- Process output is read before the timeout wait, so a full pipe can defeat the
  timeout.
- Invalid explicitly configured `mutool` paths are silently ignored.
- Its default corpus lookup points at `kitepdf-native-renderer/corpus` rather
  than the repository's `corpus/pdf`.
- A stale exemption still allows generated text to be blank.

Source:

- [`SkiaDifferentialTest`](kitepdf-skia-renderer/src/jvmTest/kotlin/io/github/yuroyami/kitepdf/skia/SkiaDifferentialTest.kt#L27)
- Hardened reference implementation:
  [`MuPdfOracle`](kitepdf-native-renderer/src/jvmTest/kotlin/io/github/yuroyami/kitepdf/nativerenderer/difftest/MuPdfOracle.kt)
- Hardened image comparison:
  [`ImageDiff`](kitepdf-native-renderer/src/jvmTest/kotlin/io/github/yuroyami/kitepdf/nativerenderer/difftest/ImageDiff.kt)

The shared oracle and comparison code should be extracted into reusable test
infrastructure rather than copied again.

Acceptance criteria:

- Every rendered page has either a finite score or an explicit failing oracle
  diagnostic.
- Page counts and image dimensions are checked.
- Timeouts, exit codes, missing output, corrupt output, and invalid
  configuration fail deterministically.
- Standard text is part of the non-blank gate.

### 7. Publish a consumable dependency graph

`kitepdf-core` currently declares:

```kotlin
api("io.github.yuroyami:kiteimage:0.0.1-SNAPSHOT")
```

Local builds resolve this from `mavenLocal()`. CI substitutes a pinned KiteImage
source checkout. Neither mechanism is available to a normal Maven Central
consumer.

Source:

- [`kitepdf-core/build.gradle.kts`](kitepdf-core/build.gradle.kts#L78)
- [`settings.gradle.kts`](settings.gradle.kts#L12)
- [CI composite checkout](.github/workflows/ci.yml#L28)

Required release sequence:

1. Publish a stable KiteImage version.
2. Replace the snapshot dependency with that immutable version.
3. Publish every KitePDF module to a temporary isolated repository.
4. Build clean JVM, KMP, and Android consumer projects using only the published
   coordinates.
5. Inspect generated POM and Gradle module metadata.
6. Bump KitePDF beyond the already released `0.2.0` and add a changelog entry.

### 8. Resolve third-party source provenance

[`Encodings.kt`](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/font/Encodings.kt#L3)
identifies its tables as ported from AGPL-3.0 MuPDF, while the repository is
distributed under Apache-2.0.

Before release, either regenerate the tables from an independently usable
specification/source, obtain appropriate permission, or document the licensing
decision and notices after qualified review. Do not rely solely on the claim
that an isolated source file cannot affect distribution obligations.

## Verification and CI gaps

### Platform coverage

Pull requests currently run JVM tests only. Pushes to `main` add core/PDF/EPUB
tests on iOS Simulator, macOS, and JS Node, but do not exercise the renderer or
Compose implementations on those platforms.

There is no CI job that actually verifies the complete advertised matrix:

- Android unit/instrumentation rendering
- Browser Canvas2D
- wasmJs or WASI
- Linux Native
- Windows/mingw
- Android Native targets
- Device iOS, tvOS, or watchOS compilation
- The sample applications

This also makes the statement that all remaining targets are
"compile-verified" inaccurate.

Source:

- [CI workflow](.github/workflows/ci.yml)
- [Platform claims](docs/platforms.md#what-ci-actually-tests)

### Corpus reproducibility

All real PDF and EPUB samples are git-ignored. The current 36-page local report
includes three local PDFs, while a clean checkout exercises 30 generated PDF
pages and five small synthetic EPUBs. Only the first six pages of a PDF are
rendered by default.

Add a pinned, license-clean public corpus artifact with:

- rotated/cropped/non-zero-origin pages
- RAW, wrapped JPEG, JPX, JBIG2, masks, and image alpha
- Standard-14, embedded TrueType/CFF, Type 3, and CJK fonts
- forms, annotations, optional content, transparency groups, and soft masks
- malformed but recoverable files
- fixed-layout and hybrid EPUB 3 books

Several existing font oracle tests also depend on ignored MuPDF source-tree
fonts, so clean CI skips those paths. Test fonts should be tracked when their
licenses permit it or fetched by immutable checksum.

### Regression thresholds

The main differential test defaults to an MAE budget of `0.50`; the current
worst page is `0.0281`. A severe regression can therefore pass.

Replace the single permissive ceiling with:

- per-fixture or per-feature baselines
- a global ceiling near current observed quality
- MAE plus changed-pixel fraction and maximum-channel-error gates
- explicit approval when a baseline is updated

### Failure artifacts

CI uploads JUnit reports but not the MuPDF references, rendered images, diff
heatmaps, EPUB reports, benchmark reports, or fuzz reproducers. Upload these on
failure so a CI-only regression remains diagnosable.

### Release qualification

There is no tag-gated release workflow, published-artifact consumer test,
signature verification step, or binary/API compatibility baseline.

Before the next public version:

- build every publication
- verify signatures and metadata
- consume the artifacts from an isolated repository
- compare public API against the previous release
- run the full platform and oracle matrix
- retain reports and diff images

## Remaining measured rendering fidelity

### Mesh shadings

The largest current MuPDF differences are shading types 4–7:

- Coons patches use a fixed 8×8 grid with one flat color per quad.
- Tensor patches discard their four interior control points and use the Coons
  approximation.
- Type 4/5 triangle meshes use fixed depth-3 subdivision and flat
  mean-colored triangles.

Source:

- [`MeshShadingParser`](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/render/MeshShading.kt#L140)
- [discarded tensor control points](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/render/MeshShading.kt#L212)
- [fixed patch grid](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/render/MeshShading.kt#L253)
- [flat triangle subdivision](kitepdf-core/src/commonMain/kotlin/io/github/yuroyami/kitepdf/core/render/MeshShading.kt#L390)

Next steps:

- implement the exact tensor-product surface
- tessellate adaptively in device space
- interpolate vertex colors where a backend supports it
- add a tensor fixture whose interior points materially affect the surface

### Fonts and CMaps

- Deterministic Standard-14 outlines would reduce host-font differences.
- The full MacExpertEncoding table is missing.
- Predefined CJK CMaps reproduce codespace segmentation but not Adobe registry
  CID mappings. Common UniJIS/UniGB/UniCNS/UniKS documents can therefore select
  incorrect glyphs or tofu when no sufficient embedded mapping is present.
- CFF2 and TrueType Collections are unsupported.

### Color and transparency

- ICC profiles are not applied; ICCBased spaces fall back by component count.
- Rendering intents and overprint state are ignored.
- CalGray/CalRGB handling is incomplete.
- AWT and Canvas2D transparency groups do not provide true isolated-group
  compositing.
- Android and CoreGraphics ignore knockout semantics and do not implement
  luminosity masks equivalently to Skia/Compose/AWT.

## API hardening

`PdfDocument` describes itself as immutable and thread-safe but publicly
exposes the mutable input `ByteArray`. A caller can mutate that array after
opening and corrupt later lazy object resolution.

Source:

- [`PdfDocument.bytes`](kitepdf-pdf/src/commonMain/kotlin/io/github/yuroyami/kitepdf/PdfDocument.kt#L58)

Own a defensive copy internally and expose copied bytes, a read-only byte
source, or an explicitly unsafe opt-in accessor.

Add binary/API compatibility validation before 1.0 so accidental public API
changes are visible during review.

## Product feature backlog

These are known feature additions rather than defects in existing guarantees:

- Digital-signature validation and certificate trust reporting
- Full ICC color management and rendering intents
- Less common form widgets, rich text, and media annotations
- Fixed-layout and mixed fixed/reflowable EPUB 3
- TrueType Collection (`.ttc`) embedding
- XPS, CBZ, and SVG document handlers
- Additional hyphenation languages and EPUB accessibility/navigation coverage

## Recommended implementation order

1. Make page edits cumulative and close redaction reachability leaks.
2. Fix native backend Standard-14 text, RAW images, image transforms, and alpha.
3. Correct rasterizer page geometry and `/UserUnit`.
4. Decode image-filter prefixes before terminal codecs.
5. Share the hardened MuPDF oracle with Skia and add platform golden tests.
6. Tighten differential thresholds and add the pinned corpus.
7. Publish stable KiteImage, add clean consumer tests, and close licensing
   provenance.
8. Improve mesh shading, color management, CJK mappings, and advanced
   transparency.
9. Complete the product feature backlog.

