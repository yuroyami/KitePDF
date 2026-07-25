# Module kitepdf-native-renderer

Rasterizers built on each platform's own drawing API: AWT on the JVM,
`android.graphics` on Android, CoreGraphics on Apple, Canvas2D on the web.

Smaller than the Skia renderer, but the per-platform back ends differ in what
they support — see the README's limits. Pair it with `kitepdf` or `kitepdf-pdf`.
