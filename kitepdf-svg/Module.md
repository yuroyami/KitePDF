# Module kitepdf-svg

SVG support: open a standalone `.svg` as a one-page document, drawn as vectors
at its own viewport.

It also holds the SVG renderer the other handlers call when a page embeds
vector art, covering `<use>`, `<symbol>`, `<image>`, `<text>`, gradients,
patterns, `clip-path`, `mask` and `filter`. A filter runs on the pixels of the
canvas's raster step, so a canvas without one draws the element unfiltered.
Pair it with `kitepdf` or add it on its own.
