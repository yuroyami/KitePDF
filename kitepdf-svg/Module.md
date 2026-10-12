# Module kitepdf-svg

SVG support. It opens a standalone `.svg` file as a document with one page, drawn as vectors at the viewport of the file.

It also holds the SVG renderer that the EPUB reader uses for the SVG in a book. The renderer covers `<use>`, `<symbol>`, `<image>`, `<text>`, gradients, patterns, `clip-path`, `mask` and `filter`. A filter needs a canvas with raster steps. A canvas without raster steps draws the element without its filter.

Add it with `kitepdf`, or on its own.
