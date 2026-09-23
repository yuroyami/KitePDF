# Contributing to KitePDF

Read this once before your first change. It is short on purpose.

## Ground rules

Breaking any of these fails the change, however good the code is.

- **Work on `main`.** Do not create branches. Do not add AI attribution to commits, tags, pull requests or files.
- **No em dashes** in any file, comment, commit message or document. Use commas, colons or brackets.
- **Zero new dependencies in `:kitepdf-core`, `:kitepdf-pdf` and `:kitepdf-epub`.** These three depend on the Kotlin standard library and nothing else. If a change seems to need a library, it needs `expect`/`actual` platform code instead: the JVM may use the JDK, Apple targets may use platform frameworks, JavaScript may use browser APIs, and common code stays pure Kotlin.
- **Never break lenient salvage.** A single corrupt object, image, font or annotation degrades to a skip or a placeholder. It never aborts the page or the document. Every parser you touch keeps this property.
- **Public API changes need documentation** in the house style: explain why, and cite the spec section. `PdfDocument.kt` is the voice reference.
- **Match the surrounding code.** No wildcard imports, the same comment density as the file you are in, spec section citations, and `internal` for cross-file helpers that are not public API.

## What not to do

- Do not rewrite a working subsystem for style. Every change traces to an issue.
- Do not make the lenient parser strict. Strictness regressions show up as corpus render failures: the count of rendered pages must never drop.
- Do not delete or weaken the pure-Kotlin codecs when adding a platform fast path. They are the only implementation on most targets.
- Do not touch `mupdf-master/` or `readium-kt-toolkit/`. They are read-only reference clones.
- Do not change on-screen rendering defaults (background, spacing, fade timings) without an issue that says so.
- Do not commit corpus files. They are git-ignored on purpose and user supplied.

## The references

- **MuPDF is the architectural reference and `mutool` is the oracle.** A source snapshot lives in `mupdf-master/`. Its third-party submodules are empty placeholders, so do not try to build it. The runnable oracle is the Homebrew `mutool` binary at `/opt/homebrew/bin/mutool`.
- **The spec is ISO 32000-1 (PDF 1.7) and ISO 32000-2 (PDF 2.0)** for PDF, EPUB 3.3 for books, and the relevant CSS and SVG specs for layout and vector work. Cite the section number in the code comment when you implement spec behaviour.
- MuPDF is not automatically right. Where the spec and the reference disagree, say so in the commit and follow the spec.

## The gate

Run this before every commit.

```bash
./gradlew :kitepdf-core:jvmTest :kitepdf-pdf:jvmTest :kitepdf-epub:jvmTest \
          :kitepdf-compose-viewer:jvmTest :kitepdf-skia-renderer:jvmTest \
          :kitepdf-native-renderer:jvmTest :kitepdf-javascript:jvmTest
```

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*DifferentialTest*"
```

Then read `kitepdf-native-renderer/build/difftest/report.md`. The mean error must not be worse than before your change. Record the number and explain any movement.

The other harnesses:

```bash
./gradlew :kitepdf-skia-renderer:jvmTest --tests "*SkiaDifferentialTest*"
```

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*EpubDifferentialTest*"
```

The EPUB sweep checks every book's page count against `kitepdf-native-renderer/src/jvmTest/resources/epub-sweep-pages.txt`, so an unexplained movement stops the line. Several kinds of change move a count on purpose. When yours does, rerun the sweep with `-Dkitepdf.epub.updatePages=true` and say why in the same commit:

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*EpubDifferentialTest*" -Dkitepdf.epub.updatePages=true
```

Two suites are timing-sensitive and run only behind a flag:

```bash
./gradlew :kitepdf-native-renderer:jvmTest :kitepdf-compose-viewer:jvmTest -PslowTests
```

They are local checks. No CI job passes the flag, so run them yourself before a change that could slow rendering.

Each published module keeps a dump of its public API in its `api/` directory. A change to the public API changes that dump too. Run this and commit the changed `api/` files in the same commit:

```bash
./gradlew updateKotlinAbi
```

CI runs `checkKotlinAbi` on macOS, the one host that compiles every target, and fails when the code and the dump differ. A diff in `api/` that the change did not intend is an accidental API change.

Benchmarks run only when asked:

```bash
KITEPDF_BENCH=true ./gradlew :kitepdf-native-renderer:jvmTest --tests "*BenchmarkTest*"
```

## The corpus

The repo root holds a `corpus/` folder with `pdf/` and `epub/` subfolders. It is git-ignored and user supplied, so a clean checkout scores fewer pages than a local machine with documents in it. Never commit its contents.

When you touch a feature with no real-world coverage, grow the corpus: add a generated fixture straight away, and ask for a real file when a synthetic one cannot prove the case.

Several oracle tests read fonts from `mupdf-master/resources/fonts/`, which is also ignored. On a clean checkout those tests report as skipped.

One corpus file is a benchmark of its own: DoomPDF, the Doom port that lives in a PDF's page-open script, kept as `corpus/pdf/doom.pdf` (https://doompdf.pages.dev/doom.pdf). `KITEPDF_DOOM=true ./gradlew :kitepdf-javascript:jvmTest --tests '*DoomPdfBenchmark*'` runs its script on KiteJS and reports start-up and frame times; the same flag turns on the KiteJS probes next to it. Without the flag and the file, all of them report as skipped.

The clock the benchmark gives the game is its own, and it moves one Doom tic for each frame the harness pumps. A frame's time is therefore the real time the engine needs to advance the game by one tic, so 1000 divided by it is frames a second. The run first plays 15 seconds of game time to get past the still title screen, because a frame measured there is the cost of drawing nothing. Add `-Dkitepdf.doom.asm=false` to run the same game with the asm.js module interpreted instead of compiled, which is how the two are compared under one harness.

## Issues and commits

Every change starts with an issue. A defect found while working becomes an issue before the fix. An idea becomes an issue before the code.

The commit that fixes it says `Fixes #n` in the commit body, not the subject. One commit may close several issues, one line each. Use Conventional Commits for the subject.

Group related issues with a `plan:` label. A plan gets a tracking issue only when it has prose worth keeping: a goal, an order of work, hazards and gate commands.
