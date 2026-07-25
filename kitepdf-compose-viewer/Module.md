# Module kitepdf-compose-viewer

Compose Multiplatform components: `PdfView`, `EpubView`, a thumbnail strip and
an outline panel.

Pair it with `kitepdf` or `kitepdf-pdf`. This module holds its engine
dependency as `implementation`, so the `PdfDocument` type it takes in its own
signatures is not on your compile classpath unless you add the engine yourself.
