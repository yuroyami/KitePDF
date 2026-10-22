# Differential rendering harness

The harness renders PDF pages with KitePDF and with MuPDF (`mutool draw`), compares the two images pixel by pixel, and lists the worst pages first. Every rendering fix is measured with it.

## Run

On Linux, install `fonts-noto-cjk` before you run the JVM rendering tests, as CI does. One public document has vertical Japanese text without an embedded font, so it needs a fallback font from the host. Without that font, the page shows boxes instead of text and fails its baseline.

```bash
./kotlin test -m kitepdf-native-renderer -p jvm \
  --include-classes='io.github.yuroyami.kitepdf.nativerenderer.difftest.DifferentialTest'
```

`./kotlin check corpus` checks the public corpus with `python3 tools/corpus.py verify`. CI runs it before the tests. The [corpus guide](../corpus/README.md) explains the credits, your own documents, and how to add a public document.

The output goes to `kitepdf-native-renderer/build/difftest/`:

```
build/difftest/
  inputs/          the synthetic fixtures, written to disk
  out/<doc>/       p<n>.kite.png, p<n>.ref.png and p<n>.diff.png (red where they differ)
  report.md        a table of the pages, worst first, with scores and image links
```

Open `report.md` and start at the top. That row is the worst page.

## Settings

Pass these as `-D` system properties in `--jvm-args`, for example `--jvm-args='-Dkitepdf.diff.dpi=150'`.

| Property | Default | Meaning |
|---|---|---|
| `kitepdf.mutool` | found automatically | The path of the `mutool` binary |
| `kitepdf.corpus` | `corpus/pdf` at the repo root | A folder of PDF files to add to the run |
| `kitepdf.diff.dpi` | `96` | The render density of both engines, a positive integer |
| `kitepdf.diff.maxpages` | `6` | The largest number of evenly spaced pages for each document |
| `kitepdf.diff.allpages` | `false` | Selects every page and ignores `maxpages` |
| `kitepdf.diff.budget` | `0.05` | The largest mean error that a page may have, from `0.0` to `1.0` |
| `kitepdf.diff.updateBaseline` | `false` | Writes the scores of this run to the baseline file instead of checking them |
| `kitepdf.difftest.out` | `build/difftest` | The output folder |
| `kitepdf.pdfium.python` | found automatically | A Python that has `pypdfium2`, for the parity check |

When you give a path for the corpus or for `mutool`, the path must be right. A missing folder, a missing binary or a binary that cannot run fails the test, so the run cannot cover less without telling you.

The per-page baseline applies only at 96 dpi. So a run at another density checks only the budget. For example, to look at fine detail:

```bash
./kotlin test -m kitepdf-native-renderer -p jvm \
  --include-classes='io.github.yuroyami.kitepdf.nativerenderer.difftest.DifferentialTest' \
  --jvm-args='-Dkitepdf.diff.dpi=150 -Dkitepdf.diff.budget=0.10'
```

## The gates

1. **Render success.** KitePDF must not throw on any page.
2. **Visible output.** Each synthetic fixture must draw something.
3. **Oracle completeness.** When the harness finds `mutool`, KitePDF and MuPDF must report the same page count. Each page that KitePDF renders must also give a readable reference PNG. A page count that differs, a timeout, an exit code that is not zero, or a missing or unreadable PNG fails the gate. `report.md` records the cause.
4. **Budget** (only when the harness finds `mutool`). No page may have a mean error above `kitepdf.diff.budget`. Lower the budget as the scores drop. Raise it for one run when a change moves the scores on purpose.
5. **Per-page baseline** (only when the harness finds `mutool`, at 96 dpi). Each page must not score clearly worse than its entry in `testResources@jvm/difftest-baseline.txt`, even when the mean holds. A page fails when one of these numbers grows past its margin:
    - the mean error, by more than a quarter plus 0.0005
    - the fraction of changed pixels, by more than a quarter plus 0.002
    - the largest channel error, by more than 48 levels

A fixture page without an entry fails. A corpus page without an entry is only reported. A fixture has its name as its key. A corpus document has a hash of its bytes as its key, so its file name stays out of the tracked file.

When a change moves a score on purpose, run the test again with `-Dkitepdf.diff.updateBaseline=true`, and say why in the same commit. The sweep prints each page that got better, so that you can record its new score.

## The corpus

- **Synthetic fixtures** (`SyntheticPdfs.kt`) always run. They cover text, paths, dashes, clips, transparency, blend modes, soft masks, CMYK, five shading types, a Type 3 font, page rotation and page boxes. They need no files, and both engines render the same bytes, so a difference is a real KitePDF gap.
- **Public PDFs** come from the manifest of the corpus. To add your own documents, put `.pdf` files in `corpus/pdf/` at the repo root, or set `-Dkitepdf.corpus` to another folder. The harness looks in subfolders too, in the order of the relative paths. Two files with the same name get different names in the report. A file that the manifest does not list stays local, and the manifest does not license it.
- **Page selection.** By default, the harness selects up to six evenly spaced pages of each document, with the first and the last page. A limit of one selects page 0. To select every page, add `-Dkitepdf.diff.allpages=true`. The report gives the selected pages and the page count of each document, apart from the pages that the oracle scored.

The EPUB sweep (`--tests '*EpubDifferentialTest*'`) renders every page that KitePDF makes, and checks the page count of each book against a recorded count. When the harness finds `mutool`, the sweep also compares page 0 with MuPDF. That score is for information only, because the two engines can reflow a book differently. The [corpus guide](../corpus/README.md) lists the public books with their rights.

## The oracle

The harness looks for `mutool` in this order:

1. `-Dkitepdf.mutool`
2. `$MUTOOL`
3. a build in `mupdf-master/build/release` or `mupdf-master/build/debug`
4. `$PATH`

The `mupdf-master/` folder is a read-only reference. Its third-party submodules are empty, so do not try to build it there. Without `mutool`, the harness still renders every page with KitePDF and writes the report without scores.

A score is only as steady as the `mutool` that made it. A `mutool` built without color management converts CMYK with plain arithmetic, so `0 1 1 0 k` comes out as pure red (255, 0, 0). A build with color management gives about (237, 28, 36). So one page can score two or three times higher with one build than with the other. CI uses MuPDF 1.27.2, built with `XCFLAGS=-DFZ_ENABLE_ICC=1`, as the `build` job of `.github/workflows/ci.yml` shows. Compare scores only between runs that used the same `mutool`, and record its version.

## The parity check (PDFium)

`PdfiumParityTest` asks one question: does PDFium, the PDF engine of Chrome, do anything better than KitePDF? It renders each page of the corpus and of the shared oracle fixtures three times: with KitePDF on AWT (K), with MuPDF (M) and with PDFium (P). Then it compares each pair of renders.

MuPDF and PDFium differ from each other in known ways: anti-aliasing, the weight of thin lines, mesh shadings, and the fonts that replace the 14 standard fonts. So a difference between KitePDF and PDFium proves nothing alone. The third render decides:

| Pattern | Verdict |
|---|---|
| M and P agree, and K differs from both | PDFium does better. The check fails. |
| K agrees with M or with P | The check passes. One reference goes its own way. |
| Each engine alone differs somewhere on the page | No consensus. The check passes and lists the page for review. |

`ThreeWayDiff` compares the whole page and each tile of 24 by 24 pixels, so a small missing element counts as much as a color shift. An engine is the odd one out when its distance to each of the other two is more than twice their distance to each other, plus a margin.

The check also fails when PDFium does one of these and KitePDF does not:

- opens a document, or finds its pages
- renders a page
- extracts a character from a page

The check writes these files:

```
build/difftest/
  parity.md                 each page with its three distances and its verdict
  parity/<doc>/p<n>.*.png   the KitePDF, MuPDF and PDFium renders, and the map
```

The map is red where only KitePDF differs, blue where only PDFium differs, and green where only MuPDF differs.

`PdfiumParityTest.KNOWN_GAPS` lists each page where PDFium does better, with its open issue. The label `plan:pdfium-parity` groups those issues. A new finding needs an issue before it goes on the list.

Sometimes KitePDF alone differs and is still right. MuPDF and PDFium can agree and both be wrong, or the spec can leave the look to the reader, such as the icon of a note. `PdfiumParityTest.EXEMPTIONS` lists those pages, each with its kind and the reason from the spec. An entry covers only the pixel finding of its page, and only up to the number of tiles that it records. So a new fault on the same page still fails.

A page on either list that stops failing must come off the list, or the check fails.

PDFium comes from `pypdfium2` and runs in its own Python process. Install the pinned version once:

```bash
python3 -m venv ~/.cache/kitepdf/pdfium-venv
~/.cache/kitepdf/pdfium-venv/bin/python -m pip install --only-binary :all: --no-deps pypdfium2==5.13.0
```

`PdfiumOracle` looks for Python in this order:

1. `-Dkitepdf.pdfium.python`
2. `$PDFIUM_PYTHON`
3. `~/.cache/kitepdf/pdfium-venv/bin/python`
4. a `python3` on `$PATH` that imports `pypdfium2`

Without PDFium or `mutool`, the check reports as skipped. CI installs the same version of `pypdfium2`.
