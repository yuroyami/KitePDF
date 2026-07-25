# Module kitepdf-skia-renderer

A Skiko rasterizer: one rendering path with the same output on every target.

This is the geometrically correct one. It honours `/Rotate`, `/CropBox`,
non-zero origins and `/UserUnit`. Pair it with `kitepdf` or `kitepdf-pdf`.
