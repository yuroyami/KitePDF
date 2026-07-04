# Differential-test corpus (git-ignored)

Drop real-world sample files here to grow the differential harnesses. The
contents are **git-ignored** (this README and the `.gitkeep` files are the only
tracked things) — samples are often large or copyrighted, so never commit them.

```
corpus/
  pdf/    # *.pdf  → kitepdf-native-renderer DifferentialTest (rendered vs `mutool`)
  epub/   # *.epub → kitepdf-native-renderer EpubDifferentialTest (vs `mutool` when present)
```

## How they're used

- **PDF**: every `.pdf` under `corpus/pdf/` is rendered by KitePDF and by the
  `mutool` oracle, then compared pixel-by-pixel (mean absolute error). Run:

  ```
  MUTOOL="$(which mutool)" ./gradlew :kitepdf-native-renderer:jvmTest --tests "*DifferentialTest*"
  ```

  Report + per-page PNGs land in `kitepdf-native-renderer/build/difftest/`.
  Only the first few pages of each file are scored (`MAX_PAGES_PER_DOC`) to keep
  runs fast.

- **EPUB**: every `.epub` under `corpus/epub/` is rendered and (best-effort)
  diffed against `mutool`. Run `--tests "*EpubDifferentialTest*"`.

Override the location with `-Dkitepdf.corpus=/abs/path` (PDF) or
`-Dkitepdf.epub.corpus=/abs/path` (EPUB).

## Unblocking the deferred codecs

To let KitePDF implement + verify these, drop a sample that uses each:

- **JBIG2** image → any scanned/OCR'd `corpus/pdf/*.pdf` with a `/JBIG2Decode` image.
- **JPEG 2000 (JPX)** image → a `corpus/pdf/*.pdf` with a `/JPXDecode` image.

Then the differential test provides the `mutool` oracle to verify the decoder.

Good starting material: the pdf.js test suite, veraPDF corpus, `epub3-samples`,
government forms, and any file that currently renders wrong.
