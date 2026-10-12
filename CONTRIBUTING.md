# Contributing to KitePDF

Read this once before your first change. It is short on purpose.

## Ground rules

Breaking any of these fails the change, however good the code is.

- **Work on `main`.** Do not create branches. Do not add AI attribution to commits, tags, pull requests or files.
- **No em dashes** in any file, comment, commit message or document. Use commas, colons or brackets.
- **No new dependencies in `:kitepdf-core`, `:kitepdf-pdf` and `:kitepdf-epub`.** Besides the Kotlin standard library and other KitePDF modules, these three modules use two libraries: ImageKodec, the image codec library, and kotlinx.coroutines, which `:kitepdf-epub` uses for background work such as fetching the resources that a book names by URL. Do not add another. If a change seems to need a library, write `expect`/`actual` platform code instead. The JVM may use the JDK, Apple targets may use platform frameworks, JavaScript may use browser APIs, and common code stays pure Kotlin.
- **Never break lenient salvage.** A corrupt object, image, font or annotation becomes a skip or a placeholder. It never stops the page or the document. Every parser that you touch keeps this property.
- **Document each public API change** in the house style: explain why, and cite the spec section. `PdfDocument.kt` is the voice reference.
- **Match the surrounding code.** No wildcard imports, the same comment density as the file you are in, spec section citations, and `internal` for helpers that other files use but that are not public API.

## What not to do

- Do not rewrite a working subsystem for style. Every change traces to an issue.
- Do not make the lenient parser strict. A stricter parser shows up as corpus render failures: the count of rendered pages must never drop.
- Do not delete or weaken the pure-Kotlin codecs when you add a fast path for one platform. They are the only implementation on most targets.
- Do not touch `mupdf-master/` or `readium-kt-toolkit/`. They are read-only reference clones.
- Do not change the rendering defaults on screen (background, spacing, fade timings) without an issue that asks for it.
- Commit a corpus document only when `corpus/manifest.json` lists it with its license. Your own documents stay git-ignored, because they can be under copyright.

## The references

- **MuPDF is the architectural reference, and `mutool` is the oracle.** A source snapshot of MuPDF is in `mupdf-master/`. Its third-party submodules are empty, so do not try to build it. The tests find `mutool` as [DIFFTEST.md](kitepdf-native-renderer/DIFFTEST.md) describes. CI uses MuPDF 1.27.2 built with color management. A `mutool` without color management scores CMYK pages differently.
- **The specs are ISO 32000-1 (PDF 1.7) and ISO 32000-2 (PDF 2.0)** for PDF, EPUB 3.3 for books, and the CSS and SVG specs for layout and vector work. When you implement a spec behavior, cite the section number in the code comment.
- MuPDF is not always right. When the spec and MuPDF disagree, follow the spec and say so in the commit.
- **PDFium is the second oracle.** KitePDF must not do worse than PDFium. `PdfiumParityTest` fails where MuPDF and PDFium agree and KitePDF differs from both. A difference that only PDFium shows is not a failure. Install the pinned `pypdfium2` as DIFFTEST.md shows.
- **HarfBuzz is the shaping oracle for EPUB text.** `GsubOracleTest` shapes real and random words in the Noto fonts of `mupdf-master/resources/fonts/noto`. It compares the glyphs with `hb-shape` of HarfBuzz 14.4, from `/opt/homebrew/bin`, `/usr/local/bin` or `/usr/bin`. A newer HarfBuzz can shape differently, so read its release notes when a word stops matching. Without `hb-shape` or the fonts, the test reports as skipped.
- **The conformance files of Unicode are the bidi oracle.** `BidiConformanceTest` runs a committed sample of `BidiCharacterTest.txt` and `BidiTest.txt` of Unicode 17 in every build. It runs the full files when they are in `~/.cache/kitepdf/ucd-17`. Its KDoc gives the commands that download them.

## The gate

### Required checks

Run this before every commit:

```bash
./gradlew :kitepdf-core:jvmTest :kitepdf-pdf:jvmTest :kitepdf-epub:jvmTest \
          :kitepdf-cbz:jvmTest :kitepdf-svg:jvmTest :kitepdf-xps:jvmTest \
          :kitepdf-compose-viewer:jvmTest :kitepdf-skia-renderer:jvmTest \
          :kitepdf-native-renderer:jvmTest :kitepdf-javascript:jvmTest \
          :kitepdf-difftest:jvmTest :kitepdf:jvmTest :kitepdf-net:jvmTest \
          :kitepdf-media:jvmTest :sample:jvmTest
```

This also runs the differential sweep, which compares the pages that KitePDF renders with the pages of `mutool`. Then read `kitepdf-native-renderer/build/difftest/report.md`. The mean error must not be worse than before your change. Record the number and explain any movement.

The sweep also compares each page with its own score in `kitepdf-native-renderer/src/jvmTest/resources/difftest-baseline.txt`. A page fails when one of these numbers gets clearly worse, even when the mean holds:

- its mean error
- its fraction of changed pixels
- its largest channel error

A new fixture fails until it has a score. When your change moves a score on purpose, run the sweep again with `-Dkitepdf.diff.updateBaseline=true`, and say why in the same commit. The sweep also names each page that got better. Record the new score, so that the page cannot slip back unnoticed.

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*.difftest.DifferentialTest" -Dkitepdf.diff.updateBaseline=true
```

The gate also runs the PDFium parity check. When it fails, read `kitepdf-native-renderer/build/difftest/parity.md`. A page that your change fixes must come off `PdfiumParityTest.KNOWN_GAPS`. A new finding needs an issue before it goes on that list.

To run one sweep alone:

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*.difftest.DifferentialTest"
```

```bash
./gradlew :kitepdf-skia-renderer:jvmTest --tests "*SkiaDifferentialTest*"
```

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*EpubDifferentialTest*"
```

The EPUB sweep compares the page count of each book with `kitepdf-native-renderer/src/jvmTest/resources/epub-sweep-pages.txt`, so a count that moves without a reason stops the line. Some changes move a count on purpose. When yours does, run the sweep again with `-Dkitepdf.epub.updatePages=true`, and say why in the same commit:

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*EpubDifferentialTest*" -Dkitepdf.epub.updatePages=true
```

The render benchmark runs with the other JVM tests of `kitepdf-native-renderer`. Machine load does not fail it, for two reasons:

- It counts the fastest of three runs of each open and each page render.
- It times a fixed sort in the same run, and it scales its budgets by that time.

With `KITEPDF_BENCH=true`, it also renders the scanned books that `ScannedPdfs` makes. Their budget is the same, but it applies to thread CPU time. A scanned page is large, so a machine that is short of memory stretches its wall time.

### Public API

Each published module keeps a dump of its public API in its `api/` directory. A change to the public API changes that dump too. Run this, and commit the changed `api/` files in the same commit:

```bash
./gradlew updateKotlinAbi
```

CI runs `checkKotlinAbi` on macOS, the one host that compiles every target. It fails when the code and the dump differ. A diff in `api/` that you did not intend is an API change by accident.

### Consumer check

`consumer-test/` is an application that uses KitePDF the way the app of a user does: by coordinates, from a repository, never from the source tree. It opens a PDF, a book and an SVG and draws them. It also runs the script of a book and the script of a form through `kitepdf-javascript`, which gets KiteJS from Maven Central. So a broken POM, a missing dependency or a packaging mistake fails there. CI publishes the JVM artifacts to Maven Local and runs it on every push. Before a release, run the same two steps:

```bash
./gradlew publishKotlinMultiplatformPublicationToMavenLocal publishJvmPublicationToMavenLocal -PRELEASE_SIGNING_ENABLED=false
```

```bash
./gradlew -p consumer-test test
```

After the release reaches Maven Central, check the released artifacts too:

```bash
./gradlew -p consumer-test test -PkitepdfRepo=central
```

The first command writes to `~/.m2`, where other projects on the machine can pick up the build. To keep it apart, add `-Dmaven.repo.local=/some/folder` to both commands.

### Benchmarks

The performance benchmark runs only when you ask for it:

```bash
KITEPDF_BENCH=true ./gradlew :kitepdf-native-renderer:jvmTest --tests "*BenchmarkTest*"
```

## Build with a local ImageKodec or KiteJS

> [!WARNING]
> Do not release KitePDF with a local dependency. Switch back to the published version first.

To develop ImageKodec or KiteJS together with KitePDF, point the build at a local checkout:

```bash
./gradlew :kitepdf-core:jvmTest -PimageKodecPath=../ImageKodec
```

```bash
./gradlew :kitepdf-javascript:jvmTest -PkiteJsPath=../KiteJS
```

This composite build can fail when the two builds use different versions of the Kotlin plugin. In that case, publish ImageKodec to a Maven folder of its own, and point the build at that folder. Give the local build a version of its own, so that it cannot replace a released artifact:

```bash
../ImageKodec/gradlew -p ../ImageKodec \
  :imagekodec:publishKotlinMultiplatformPublicationToMavenLocal \
  :imagekodec:publishJvmPublicationToMavenLocal \
  -Pversion=0.3.1-dev -PRELEASE_SIGNING_ENABLED=false \
  -Dmaven.repo.local="$PWD/../imagekodec-maven" \
  -x :imagekodec:dokkaGeneratePublicationHtml
```

```bash
./gradlew :kitepdf-core:jvmTest \
  -PimageKodecRepository="$PWD/../imagekodec-maven" -PimageKodecVersion=0.3.1-dev
```

The documentation task of ImageKodec needs the Android SDK, so the first command skips it. Pass the same two `imageKodec` properties to the gate commands. To test another target, also publish its artifact, for example `:imagekodec:publishJsPublicationToMavenLocal`. The consumer check resolves the metadata of every native target, so it needs the complete publication of ImageKodec.

## The corpus

The repo root holds `corpus/pdf/` and `corpus/epub/`. The public documents that `corpus/manifest.json` lists are tracked with their credits and license texts, so a clean checkout tests them. The `jvmTest` tasks of the two renderers run `python3 tools/corpus.py verify` first. It checks the byte count and the SHA-256 of every listed file. The [corpus guide](corpus/README.md) explains how to add a public document.

Your own documents go in the same folders and stay git-ignored. They make your local counts differ from CI.

The PDF sweep scores up to six evenly spaced pages of each document. To score every page, add `-Dkitepdf.diff.allpages=true`, and read the selected and scored counts in the report. The EPUB sweep renders every page. Its comparison of page 0 with MuPDF is for information only.

When you touch a feature that no real document covers, grow the corpus. Add a generated fixture at once, and ask for a real file when a synthetic one cannot prove the case.

Some oracle tests read fonts from `mupdf-master/resources/fonts/`, which is also git-ignored. On a clean checkout, these tests report as skipped.

### DoomPDF

DoomPDF is a PDF whose page-open script runs a port of Doom. It is the benchmark of `kitepdf-javascript`. Download it from https://doompdf.pages.dev/doom.pdf to `corpus/pdf/doom.pdf`, then run:

```bash
KITEPDF_DOOM=true ./gradlew :kitepdf-javascript:jvmTest --tests '*DoomPdfBenchmark*'
```

It runs the script on KiteJS and reports the start-up time and the frame times. The same flag turns on the KiteJS probes next to it. Without the flag and the file, these tests report as skipped.

The benchmark gives the game a clock of its own, which moves one Doom tic for each frame that the harness pumps. So the time of a frame is the real time that the engine needs to move the game by one tic, and 1000 divided by it gives frames per second. The run first plays 15 seconds of game time to get past the still title screen, because a frame measured there is the cost of drawing nothing.

## Issues and commits

Every change starts with an issue. A defect that you find while you work becomes an issue before the fix. An idea becomes an issue before the code.

The commit that fixes an issue says `Fixes #n` in the commit body, not the subject. One commit can close several issues, one line each. Use Conventional Commits for the subject.

Group related issues with a `plan:` label. A plan gets a tracking issue only when it has prose worth keeping: a goal, an order of work, hazards and gate commands.
