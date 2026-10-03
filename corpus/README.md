# Differential-test corpus

The corpus holds the real documents that the differential harnesses render. It has two parts:

- **Public documents.** The [manifest](manifest.json) lists 29 documents: 15 PDFs with 47 pages, and 14 EPUBs. They are tracked in this repository, so a clean checkout tests them. Each one keeps its own license.
- **Local drop-ins.** Put your own PDFs under `pdf/` and EPUBs under `epub/`. Git ignores them, because they can be copyrighted. They add to your local counts but never to the public set.

The EPUBs are publications and small conformance samples. They do not stand for fourteen full books. Six of them carry scripts, which the scripted-book gate runs (#496).

## Verify the public documents

Run from the repository root with Python 3. No third-party package is needed.

```bash
python3 tools/corpus.py verify
```

`verify` checks that every manifest entry exists and matches its recorded byte count and SHA-256. It uses no network. The renderer test tasks run it first, so a changed or missing file fails the build before a test runs.

`list` prints the manifest. `fetch` downloads a missing entry from its recorded URL and checks it the same way. Use it to restore a deleted file, or to get the files into another root with `--root /absolute/path`. Downloads go through a cache at `~/.cache/kitepdf/public-corpus`; `fetch --offline` reads only the cache.

Ten download URLs can change upstream: the nine W3C EPUBs come from a GitHub Pages site, and the Arabic PDF guide comes from the current-file Commons URL. A changed upstream file fails `fetch` on the digest. The tracked copy stays the reference.

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

The documents keep their upstream licenses: CC BY 3.0 and 4.0, CC BY-SA 3.0 and 4.0, the W3C Software and Document License (2015), and MIT for the copy of jQuery inside one IDPF sample. The project license does not replace them. [ATTRIBUTION.md](ATTRIBUTION.md) credits each document, the manifest records the license evidence, and [licenses/](licenses/README.md) holds the full license texts. The files are the unchanged upstream bytes.

Keep the credits and the license notices when you share a file. Follow the share-alike terms for an adaptation. A generated or reduced derivative needs its own provenance and license review.

## Add a public document

1. Check the license of the document and of every asset inside it.
2. Add a manifest entry: source and download URLs, revision, creators, attribution, license evidence, byte count, SHA-256, and what the document covers.
3. Add its credits to `ATTRIBUTION.md`, and its license text to `licenses/` when it is new.
4. Add its path to the corpus list in the root `.gitignore`.
5. Run `python3 tools/corpus.py verify` and the affected harnesses.

Never add a private or user-supplied file to the manifest.

What the corpus still lacks, such as a real vertical Japanese book or an arithmetic-coded JBIG2 halftone file, is tracked in [#215](https://github.com/yuroyami/KitePDF/issues/215).
