# Module kitepdf-core

The format-agnostic substrate shared by the PDF and EPUB modules.

Geometry, the `KiteCanvas` drawing interface, the font engine (TrueType, CFF,
Type3, WOFF2), stream filters and the hyphenation tables. You do not add this
directly — it arrives transitively with `kitepdf-pdf` or `kitepdf-epub`.
