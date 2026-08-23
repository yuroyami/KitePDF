# Module kitepdf

The umbrella artifact: add this one and you get both the PDF engine and the
EPUB reader.

It re-exports both handlers, and adds `KiteDoc`: the format-neutral opener for
the case where you have bytes and do not yet know whether they are a PDF or a
book. Depend on `kitepdf-pdf` or `kitepdf-epub` directly instead if you only
need one of them, and call that handler's own `open`.
