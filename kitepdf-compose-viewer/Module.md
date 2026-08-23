# Module kitepdf-compose-viewer

Compose Multiplatform components: `KiteDocView`, a selection menu, a thumbnail
strip and an outline panel. One viewer path serves both PDF and EPUB.

Pair it with `kitepdf` (both formats) or a single handler module. This module
holds its engine dependency as `implementation`, so the document types it takes
in its own signatures are not on your compile classpath unless you add the
engine yourself.
