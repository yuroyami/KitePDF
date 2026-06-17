# Drop-in corpus

Put real-world `.pdf` files here. The differential harness
(`DifferentialTest`) renders each with KitePDF and MuPDF, pixel-diffs them, and
ranks the worst offenders in `../build/difftest/report.md`.

- Only the first few pages of each file are scored (see
  `DiffHarness.MAX_PAGES_PER_DOC`) to keep runs fast.
- Point the harness at a different directory with
  `-Dkitepdf.corpus=/path/to/pdfs`.
- Good starting material: the pdf.js test suite, veraPDF corpus, government
  forms, and any PDF that currently renders wrong — add it here so it becomes a
  tracked regression.

This directory is intentionally kept (via this README) so the default corpus
path exists out of the box.
