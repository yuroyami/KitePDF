# Differential-test corpus

The corpus holds the real documents that the differential harnesses render. It has two parts:

- **Public documents.** The [manifest](manifest.json) lists 95 documents: 49 PDFs with 249 pages, and 46 EPUBs. The default PDF sweep selects 207 public pages. They are tracked in this repository, so a clean checkout tests them. Each one keeps its own license.
- **Local drop-ins.** Put your own PDFs under `pdf/` and EPUBs under `epub/`. Git ignores them, because they can be copyrighted. They add to your local counts but never to the public set.

The EPUBs include 31 complete Standard Ebooks publications, a complete Japanese novel with an embedded vertical font, and the original fourteen publications and conformance samples. Six carry scripts, which the scripted-book gate runs (#496). The PDF additions include 32 scientific articles, a seven-page Japanese chapter and an original JBIG2 chart. The articles alone supply 175 selected pages across 32 documents (#624).

The expanded 96 dpi sweep scores 240 pages including generated harness fixtures, with mean MAE 0.0015 against colour-managed MuPDF 1.27.2. The original 58 selected pages retain their 0.0005 mean; the movement comes from the added documents. Two raster diagrams expose sampling differences against both MuPDF and PDFium, tracked in [#626](https://github.com/yuroyami/KitePDF/issues/626), and remain in the corpus with per-page scores. The EPUB sweep renders 6,178 pages including harness fixtures; the only two blank pages are the existing Page Blanche sample pages.

## Verify the public documents

Run from the repository root with Python 3. No third-party package is needed.

```bash
python3 tools/corpus.py verify
```

`verify` checks that every manifest entry exists and matches its recorded byte count and SHA-256. It uses no network. The renderer test tasks run it first, so a changed or missing file fails the build before a test runs.

`list` prints the manifest. `fetch` downloads a missing entry from its recorded URL and checks it the same way. Use it to restore a deleted file, or to get the files into another root with `--root /absolute/path`. Downloads go through a cache at `~/.cache/kitepdf/public-corpus`; `fetch --offline` reads only the cache.

Some download URLs can change upstream: the nine W3C EPUBs come from a GitHub Pages site, the Arabic PDF guide comes from the current-file Commons URL, and Standard Ebooks serves current editions. A changed upstream file fails `fetch` on the digest. The tracked copy stays the reference. Generated editions use repository URLs, which become available when those files are published; the recipes below can reproduce them from pinned inputs.

## Run the harnesses

The harnesses find PDFs and EPUBs recursively in their folders, in a stable order. They include the public documents, your drop-ins, and their own generated fixtures. Two files with the same stem get distinct report names.

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*.difftest.DifferentialTest"
```

The PDF sweep scores up to six evenly spaced pages per document, including the first and the last page. Set `-Dkitepdf.diff.maxpages=N` to change the number, or `-Dkitepdf.diff.allpages=true` to score every page. The report at `kitepdf-native-renderer/build/difftest/report.md` lists the available and selected pages, failures and scores. A page has a score only when KitePDF and the MuPDF oracle both render it.

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*EpubDifferentialTest*"
```

```bash
./gradlew :kitepdf-javascript:jvmTest --tests "*ScriptedBookGateTest*"
```

The scripted-book gate opens each scripted chapter of the public EPUBs through `EpubScriptRunner`, taps it, runs its timers, and checks what the page shows afterwards. A scripted book added here needs its checks there, and the gate fails until it has them.

The EPUB sweep renders every page that KitePDF paginates, and writes per-book page counts to `kitepdf-native-renderer/build/epub-difftest/report.md`. A render failure or an unexplained change of a recorded page count fails the gate. MuPDF compares page 0 only, and that score is for information, because reflow can differ.

Override the folders with `-Dkitepdf.corpus=/absolute/pdf/path` and `-Dkitepdf.epub.corpus=/absolute/epub/path`. A path that does not exist fails. The [harness guide](../kitepdf-native-renderer/DIFFTEST.md) covers oracle setup, PDFium parity, score budgets and baseline updates.

## Rights

The documents keep their upstream licenses: CC0, CC BY 3.0 and 4.0, CC BY-SA 3.0 and 4.0, the W3C Software and Document License (2015), and MIT for the copy of jQuery inside one IDPF sample. The Japanese font is SIL OFL 1.1; the original JBIG2 chart is Apache-2.0. The project license does not replace third-party terms. [ATTRIBUTION.md](ATTRIBUTION.md) credits each document, the manifest records the license evidence, and [licenses/](licenses/README.md) holds the full license texts.

The third-party downloads are unchanged upstream bytes. The two Japanese editions are declared adaptations, generated from CC0 chapter markup and an OFL font. Standard Ebooks dedicates its editorial contributions to CC0; the books retain their notices about the underlying texts and illustrations being public domain in the United States and potentially protected elsewhere.

Keep the credits and the license notices when you share a file. Follow the share-alike terms for an adaptation. A generated or reduced derivative needs its own provenance and license review.

## Add a public document

1. Check the license of the document and of every asset inside it.
2. Add a manifest entry: source and download URLs, revision, creators, attribution, license evidence, byte count, SHA-256, and what the document covers.
3. Add its credits to `ATTRIBUTION.md`, and its license text to `licenses/` when it is new.
4. Add its path to the corpus list in the root `.gitignore`.
5. Run `python3 tools/corpus.py verify` and the affected harnesses.

Never add a private or user-supplied file to the manifest.

## Reproduce the generated documents

Normal tests use the tracked bytes and need neither generator dependency.

For the Japanese editions (#620), download the three `inputs` URLs recorded in their manifest entries. The generator checks their SHA-256 before using them. With Python and `fonttools==4.66.1` installed in a separate virtual environment, run:

```bash
python tools/make_vertical_corpus.py /path/to/kusamakura-japanese-vertical-writing.epub \
  /path/to/NotoSansJP.ttf /path/to/OFL.txt
```

The EPUB keeps all thirteen chapters and ruby, replaces the placeholder font with a static Noto Sans JP subset, and omits recordings, cover and old styles. The PDF typesets the complete first chapter without ruby readings. Both embed 3,064 glyphs with 240 `vert`/`vrt2` substitutions and vertical metrics. The font notices remain in its name table and in `licenses/OFL-1.1.txt`; the EPUB also includes that notice. Font and ZIP timestamps are fixed, so regeneration reproduces the manifest hashes.

For the JBIG2 chart (#621), check out [agl/jbig2enc](https://github.com/agl/jbig2enc) at `d0dfca46216c98f11312a9c9f15615ed490cd7b3`, then run:

```bash
c++ -std=c++17 -O2 -I/path/to/jbig2enc/src tools/make_halftone_corpus.cpp \
  /path/to/jbig2enc/src/jbig2arith.cc -o /tmp/make-halftone
/tmp/make-halftone corpus/pdf/kitepdf-jbig2-arithmetic-halftone.pdf
```

This original eight-tone chart contains an arithmetic pattern dictionary and an immediate arithmetic halftone region, with no externally sourced image. MuPDF and PDFium reproduce all 19,200 chart pixels. Contrary to the older description in #621, the current KiteImageCodec 0.2.0 already renders this arithmetic template-0 case: the initial AWT score against MuPDF is 0.0010 MAE at 96 dpi. The baseline records the actual chart, without a decoder change or a dependency upgrade.
