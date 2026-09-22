# Module kitepdf

The umbrella artifact: add this one for the PDF, EPUB, CBZ, SVG and XPS/OpenXPS
handlers through one dependency.

It re-exports the handlers and adds `KiteDoc`, the format-neutral opener for
bytes whose document format is not known in advance. Depend on a handler such
as `kitepdf-pdf` or `kitepdf-xps` directly if that is the only format you need,
and call its own `open` method.
