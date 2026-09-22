# Module kitepdf-cbz

A reader for CBZ comic archives: a ZIP of images becomes a document with one
page per image.

Pages come in natural filename order (`page2` before `page10`), and sizes are
read from image headers so opening does not decode the archive. Root-level
`ComicInfo.xml` supplies title, writers, language, reading direction and page
bookmarks to the common document API. `CbzDocument.comicMetadata` also exposes
series, issue number, summary and every declared scalar field and page
attribute from the ComicInfo schema. Metadata is loaded lazily; a damaged or
missing file does not prevent reading the comic.

Packaging noise (`Thumbs.db`, hidden files) is skipped. It brings no EPUB
engine with it. Pair it with `kitepdf` or add it on its own.
