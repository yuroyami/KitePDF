# Module kitepdf-native-renderer

Rasterizers built on each platform's own drawing API: AWT on the JVM,
`android.graphics` on Android, CoreGraphics on Apple, Canvas2D on the web.
PDF pages only; use the Skia renderer or the Compose viewer for EPUB.

Smaller than the Skia renderer, but the per-platform back ends differ in what
they support. See the README's limits. Pair it with `kitepdf` or `kitepdf-pdf`.
