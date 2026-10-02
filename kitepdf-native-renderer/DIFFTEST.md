# Differential rendering harness

The correctness scoreboard. It renders selected corpus PDF pages with **KitePDF** and
with **MuPDF** (`mutool draw`, the in-repo oracle), pixel-diffs the two, and
ranks the worst-rendering pages first. Every rendering fix is graded against
it.

## Run

The public corpus documents are tracked, and the test task checks them with
`python3 tools/corpus.py verify` before it runs. The [corpus guide](../corpus/README.md)
covers attribution, local drop-ins and how to add a public document.

```bash
./gradlew :kitepdf-native-renderer:jvmTest \
  --tests "io.github.yuroyami.kitepdf.nativerenderer.difftest.DifferentialTest"
```

Outputs land in `kitepdf-native-renderer/build/difftest/`:

```
build/difftest/
  inputs/          synthetic fixtures materialized to disk
  out/<doc>/       p<n>.kite.png · p<n>.ref.png · p<n>.diff.png  (red = divergence)
  report.md        worst-first table with scores + image links
```

Open `report.md` and start at the top. That is the worst-rendering page.

## Knobs (Gradle `-D` system properties)

| Property | Default | Meaning |
|---|---|---|
| `kitepdf.mutool` | _auto_ | explicit `mutool` binary path |
| `kitepdf.corpus` | repo-root `corpus/pdf` | extra real-world PDF directory |
| `kitepdf.diff.dpi` | `96` | positive render density for both engines |
| `kitepdf.diff.maxpages` | `6` | positive maximum evenly spaced pages selected per document |
| `kitepdf.diff.allpages` | `false` | select every PDF page, overriding the sample limit |
| `kitepdf.diff.budget` | `0.05` | finite max per-page MAE from `0.0` to `1.0` |
| `kitepdf.diff.updateBaseline` | `false` | write this run's per-page scores to the baseline file instead of checking them |
| `kitepdf.difftest.out` | `build/difftest` | output directory |
| `kitepdf.pdfium.python` | _auto_ | Python with `pypdfium2`, for the parity check |

Explicit corpus and `mutool` paths are strict: a missing directory, missing
binary, or non-executable binary fails the test instead of silently reducing
coverage.

Example: tighten the gate and crank density once correctness improves:

```bash
./gradlew :kitepdf-native-renderer:jvmTest \
  -Dkitepdf.diff.dpi=150 -Dkitepdf.diff.budget=0.10
```

## The gates

1. **Render success**: KitePDF must not throw on any page.
2. **Non-blank**: synthetic fixtures must produce visible output.
3. **Oracle completeness**: when `mutool` is found, KitePDF and MuPDF must
   report the same page count and every KitePDF-rendered page must produce a
   readable reference PNG. A mismatch, timeout, non-zero exit, or
   missing/unreadable PNG fails the gate and is recorded in `report.md`.
4. **Regression budget** (only when the oracle is present): no page may
   exceed `kitepdf.diff.budget`. The default sits near twice the worst page
   seen at the time it was set, so a real regression fails instead of hiding
   under a lenient ceiling. Lower it as the score drops, and raise it for one
   run when a deliberate change moves the baseline.

   The number is only as steady as the oracle. A `mutool` built without colour
   management converts CMYK the plain arithmetic way (`0 1 1 0 k` comes out
   pure 255/0/0), while a colour-managed build lands near 237/28/36, so the
   same page can score two or three times higher against one build than the
   other. Compare scores only across runs that used the same oracle.
5. **Per-page baseline** (only when the oracle is present, at 96 dpi): each
   page must not score clearly worse than its entry in
   `src/jvmTest/resources/difftest-baseline.txt`, even when the mean holds.
   A page fails when one of these grows past its margin:
   - the mean error, by more than a quarter plus 0.0005
   - the fraction of changed pixels, by more than a quarter plus 0.002
   - the largest channel error, by more than 48 levels

   A fixture page with no entry fails. A corpus page with no entry is only
   reported. A fixture keys by its name. A corpus document keys by a hash of
   its bytes, so its file name stays out of the tracked file. When a change
   moves a score on purpose, rerun with `-Dkitepdf.diff.updateBaseline=true`
   and say why in the same commit. The sweep prints each page that got
   better, so that its new score can be recorded.

## The corpus

- **Synthetic fixtures** (`SyntheticPdfs.kt`) always run: text, vector
  fills/strokes/curves, transparency, multi-page. Deterministic, no external
  files, and both engines render the same bytes, so any divergence is a real
  KitePDF gap.
- **Public and local PDFs**: the pinned manifest supplies 15 public PDFs. Put
  additional local `.pdf` files in the repo-root `corpus/pdf/`, or point
  `-Dkitepdf.corpus` elsewhere. Discovery is recursive and ordered by relative
  path; duplicate file stems receive distinct report names. Files outside the
  public manifest remain local and are not licensed by that manifest.
- **Page selection**: the default is up to six evenly spaced pages per document,
  including the first and last pages. A limit of one selects page 0. Set
  `-Dkitepdf.diff.allpages=true` to select every page. The report records the
  selected page indices and available page counts, separate from successful
  oracle scores. The default run selects 25 of the 47 public PDF pages.

The EPUB sweep (`--tests '*EpubDifferentialTest*'`) renders every page produced
by KitePDF and checks recorded per-book page counts. Its optional MuPDF
comparison covers page 0 only and is informational because reflow can differ.
What the corpus still lacks is tracked in
[#215](https://github.com/yuroyami/KitePDF/issues/215).

## The oracle (`mutool`)

Located automatically from `-Dkitepdf.mutool`, `$MUTOOL`, the in-repo build, or
`$PATH`. Use an installed `mutool`, such as the Homebrew binary at
`/opt/homebrew/bin/mutool`. The `mupdf-master/` checkout is a read-only reference
with empty third-party submodules; do not try to build it. Record the oracle
version when comparing scores. Without any oracle the harness still runs as a
KitePDF-only smoke pass and emits the report without oracle scores.

## The parity check (PDFium)

`PdfiumParityTest` asks one question: does PDFium, the PDF engine of Chrome, do
anything better than KitePDF? It renders each page of the corpus and of the
shared oracle fixtures three times: KitePDF on AWT (K), MuPDF (M) and PDFium
(P). Then it compares each pair of renders.

MuPDF and PDFium disagree with each other in known ways: anti-aliasing, the
weight of thin lines, mesh shadings, and the fonts that replace the standard 14
fonts. So a difference between KitePDF and PDFium proves nothing by itself. The
third render breaks the tie:

| Pattern | Verdict |
|---|---|
| M and P agree, and K differs from both | PDFium does better. The check fails. |
| K agrees with M or with P | The check passes. One reference goes its own way. |
| Each engine alone differs somewhere on the page | No consensus. The check passes and lists the page for review. |

`ThreeWayDiff` measures agreement on the whole page and on each tile of 24
pixels, so a small missing element counts as well as a colour shift. An engine
is the odd one out when its distance to each of the other two is more than
twice their distance to each other, plus a margin.

The check also fails when PDFium opens a document or finds pages that KitePDF
does not, renders a page that KitePDF fails on, or extracts a character from a
page that KitePDF does not extract.

```
build/difftest/
  parity.md                 every page with its three distances and its verdict
  parity/<doc>/p<n>.*.png   kite, mupdf and pdfium renders, and the map
```

The map is red where KitePDF alone differs, blue where PDFium alone differs,
and green where MuPDF alone differs.

`PdfiumParityTest.KNOWN_GAPS` lists each page where PDFium does better today,
with its open issue. The label `plan:pdfium-parity` groups those issues. A new
finding needs an issue before it goes on the list.

Sometimes KitePDF alone differs and is still right. MuPDF and PDFium can agree
with each other and both be wrong, or the spec can leave the look to the
reader, such as the icon of a note. `PdfiumParityTest.EXEMPTIONS` lists those
pages, each with its kind and the reason from the spec. An entry covers only
the pixel finding of its page, and only up to the number of tiles it records,
so a new fault on the same page still fails.

A page on either list that stops failing must come off it, or the check fails.

PDFium comes from `pypdfium2` and runs in its own Python process. Install the
pinned version once:

```bash
python3 -m venv ~/.cache/kitepdf/pdfium-venv
~/.cache/kitepdf/pdfium-venv/bin/python -m pip install --only-binary :all: --no-deps pypdfium2==5.13.0
```

`PdfiumOracle` finds it from `-Dkitepdf.pdfium.python`, `$PDFIUM_PYTHON`, that
virtual environment, or a `python3` on `$PATH` that imports `pypdfium2`.
Without PDFium or `mutool`, the check reports as skipped. CI installs the same
version.
