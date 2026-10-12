# Module kitepdf-xps

The reader for XPS and OpenXPS packages, the fixed-page format of [ECMA-388](https://ecma-international.org/publications-and-standards/standards/ecma-388/). It reads the Microsoft XPS namespace of 2005 and the OpenXPS namespace.

`XpsDocument.open(bytes)` opens a package, and `KiteDoc` of the umbrella artifact recognizes it as `KiteDocFormat.Xps`. An `XpsDocument` is a `KiteDocument`, so the viewer, the renderers, search and text selection work on it as on the other formats.

The module reads and renders XPS. It does not write XPS, and it does not check conformance. See [XPS and OpenXPS](https://yuroyami.github.io/KitePDF/xps/) for what it renders.
