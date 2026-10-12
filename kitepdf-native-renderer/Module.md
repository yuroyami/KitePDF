# Module kitepdf-native-renderer

Rasterizers that use the drawing API of each platform: AWT on the JVM, `android.graphics` on Android, CoreGraphics on Apple platforms and Canvas2D in the browser. They add no graphics library to your app.

They render PDF pages. For EPUB pages, use the Skia renderer. For any page in a Compose app, use `KitePageRasterizer` of the Compose viewer. The back ends differ in a few details, which [How the renderers draw](https://yuroyami.github.io/KitePDF/rendering/#how-the-renderers-draw) lists.

Add it with `kitepdf` or `kitepdf-pdf`. See [Headless rendering](https://yuroyami.github.io/KitePDF/rendering/).
