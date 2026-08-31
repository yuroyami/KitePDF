# Module kitepdf-cbz

A reader for CBZ comic archives: a ZIP of images becomes a document with one
page per image.

Pages come in natural filename order (`page2` before `page10`), sizes are read
from image headers so opening does not decode the archive, and packaging noise
(`ComicInfo.xml`, `Thumbs.db`, hidden files) is skipped. It brings no EPUB
engine with it. Pair it with `kitepdf` or add it on its own.
