# Differential-test corpus

The corpus holds the real documents that the differential harnesses render. It has two parts:

- **Public documents.** The [manifest](manifest.json) lists PDF and EPUB files that this repository tracks, so a clean checkout tests them. Each document keeps its own license.
- **Your own documents.** Put your own PDFs in `pdf/` and EPUBs in `epub/`. Git ignores them, because they can be under copyright. They add to your local counts, but never to the public set.

The public EPUBs include complete Standard Ebooks publications, a complete Japanese novel with an embedded vertical font, publications of the W3C and the EPUB 3 samples of the IDPF. Some of them carry scripts, which the scripted-book gate runs. The public PDFs include scientific articles, a Japanese chapter in vertical text and an original JBIG2 chart.

## Verify the public documents

Run this from the repository root with Python 3. It needs no third-party package and no network.

```bash
python3 tools/corpus.py verify
```

`verify` checks that each manifest entry exists and has its recorded byte count and SHA-256. The `jvmTest` tasks of the two renderers run it first, so a changed or missing file fails the build before a test runs.

The tool has two more commands:

- `list` prints the manifest.
- `fetch` downloads a missing entry from its recorded URL and checks it the same way. Use it to restore a deleted file, or to get the files into another folder with `--root /absolute/path`. Downloads go through a cache in `~/.cache/kitepdf/public-corpus`. `fetch --offline` reads only the cache.

Some download URLs can change upstream:

- The W3C EPUBs come from a GitHub Pages site.
- The Arabic PDF guide comes from the URL of the current file on Wikimedia Commons.
- Standard Ebooks serves its current editions.

When an upstream file changes, `fetch` fails on the digest. The tracked copy stays the reference. The generated documents have repository URLs. The recipes below reproduce them from pinned inputs.

## Run the harnesses

The harnesses find the PDFs and the EPUBs in their folders and in all subfolders, in a stable order. They render the public documents, your own documents and their own generated fixtures. Two files with the same name get different names in the report.

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*.difftest.DifferentialTest"
```

The PDF sweep scores up to six evenly spaced pages of each document, with the first and the last page. To change the number, set `-Dkitepdf.diff.maxpages=N`. To score every page, set `-Dkitepdf.diff.allpages=true`. The report in `kitepdf-native-renderer/build/difftest/report.md` lists the pages of each document, the selected pages, the failures and the scores. A page has a score only when KitePDF and MuPDF both render it.

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*EpubDifferentialTest*"
```

The EPUB sweep renders every page that KitePDF makes, and writes the page count of each book to `kitepdf-native-renderer/build/epub-difftest/report.md`. A render failure, or a change of a recorded page count without a reason, fails the sweep. The sweep compares only page 0 with MuPDF, and that score is for information only, because the two engines can reflow a book differently.

```bash
./gradlew :kitepdf-javascript:jvmTest --tests "*ScriptedBookGateTest*"
```

The scripted-book gate opens each chapter with scripts of the public EPUBs through `EpubScriptRunner`. It taps the page, runs its timers, and checks what the page shows afterwards. A book with scripts that you add here needs its checks in that gate. The gate fails until it has them.

To use other folders, set `-Dkitepdf.corpus=/absolute/pdf/path` and `-Dkitepdf.epub.corpus=/absolute/epub/path`. A path that does not exist fails the test. The [harness guide](../kitepdf-native-renderer/DIFFTEST.md) explains the oracle setup, the PDFium parity check, the score budget and the baseline updates.

## Rights

The documents keep their upstream licenses: CC0, CC BY 3.0 and 4.0, CC BY-SA 3.0 and 4.0, the W3C Software and Document License (2015), and MIT for the copy of jQuery inside one IDPF sample. The Japanese font uses the SIL OFL 1.1, and the original JBIG2 chart uses Apache-2.0. The license of the project does not replace the terms of a third party.

- [ATTRIBUTION.md](ATTRIBUTION.md) credits each document.
- The manifest records the license evidence.
- [licenses/](licenses/README.md) holds the full license texts.

The downloads from third parties are the unchanged upstream bytes. The two Japanese editions are declared adaptations, generated from CC0 chapter markup and an OFL font. Standard Ebooks dedicates its editorial contributions to CC0. The books keep their notices: the texts and illustrations are in the public domain in the United States, and they can be protected elsewhere.

Keep the credits and the license notices when you share a file. Follow the share-alike terms for an adaptation. A generated or reduced derivative needs its own review of provenance and license.

## Add a public document

1. Check the license of the document and of every asset inside it.
2. Add a manifest entry with the source and download URLs, the revision, the creators, the attribution, the license evidence, the byte count, the SHA-256 and what the document covers.
3. Add its credits to `ATTRIBUTION.md`. When its license is new, add the license text to `licenses/`.
4. Add its path to the corpus list in the root `.gitignore`.
5. Run `python3 tools/corpus.py verify` and the harnesses that it affects.

Never add a private file, or a file that a user gave you, to the manifest.

## Reproduce the generated documents

The tests use the tracked bytes, so they need neither generator.

### The Japanese editions

1. Download the three `inputs` URLs that the manifest entries of the two editions record. The generator checks their SHA-256 before it uses them.
2. In a separate virtual environment, install Python and `fonttools==4.66.1`.
3. Run the generator:

```bash
python tools/make_vertical_corpus.py /path/to/kusamakura-japanese-vertical-writing.epub \
  /path/to/NotoSansJP.ttf /path/to/OFL.txt
```

The EPUB keeps all thirteen chapters and the ruby. It replaces the placeholder font with a static subset of Noto Sans JP, and it leaves out the recordings, the cover and the old styles. The PDF sets the complete first chapter without ruby readings. Both embed the font with its vertical substitutions (`vert` and `vrt2`) and its vertical metrics. The font notices stay in its name table and in `licenses/OFL-1.1.txt`, and the EPUB also includes that notice. The font and ZIP timestamps are fixed, so a new run gives the same hashes as the manifest.

### The JBIG2 chart

Check out [agl/jbig2enc](https://github.com/agl/jbig2enc) at `d0dfca46216c98f11312a9c9f15615ed490cd7b3`, then run:

```bash
c++ -std=c++17 -O2 -I/path/to/jbig2enc/src tools/make_halftone_corpus.cpp \
  /path/to/jbig2enc/src/jbig2arith.cc -o /tmp/make-halftone
/tmp/make-halftone corpus/pdf/kitepdf-jbig2-arithmetic-halftone.pdf
```

The chart has eight tones. It holds an arithmetic pattern dictionary and an immediate arithmetic halftone region, with no image from an outside source.
