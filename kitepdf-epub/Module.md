# Module kitepdf-epub

The reader for EPUB 2 and 3 books, on the same core as the PDF engine.

It reads the container and the package, applies the HTML and CSS cascade with web fonts (WOFF and WOFF2), and lays out the chapters into pages, with hyphenation, CJK text and vertical writing. A fixed-layout chapter keeps its own page size. A reflowable chapter flows over pages of the size that the reader picks. The module also holds the DOM that the scripts of a book use with `kitepdf-javascript`.

See [Reading EPUBs](https://yuroyami.github.io/KitePDF/epub/).
