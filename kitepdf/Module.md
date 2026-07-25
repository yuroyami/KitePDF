# Module kitepdf

The umbrella artifact: add this one and you get both the PDF engine and the
EPUB reader.

It contains no code of its own beyond a marker object — it exists so that a
single dependency line pulls in `kitepdf-pdf` and `kitepdf-epub`. Depend on
those directly instead if you only need one of them.
