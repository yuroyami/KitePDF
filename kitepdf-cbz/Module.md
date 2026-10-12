# Module kitepdf-cbz

The reader for CBZ comic archives. A ZIP file of images becomes a document with one page for each image.

- The pages come in natural file-name order, so `page2` comes before `page10`.
- The page sizes come from the image headers, so opening the archive decodes no image.
- A `ComicInfo.xml` at the root gives the title, the writers, the language, the reading direction and the page bookmarks to the common document API. `CbzDocument.comicMetadata` also gives the series, the issue number, the summary, and every other field and page attribute that the file declares. The metadata loads when you first ask for it. A damaged or missing `ComicInfo.xml` does not stop the comic from opening.
- Packaging files, such as `Thumbs.db` and hidden files, are skipped.

The module depends only on `kitepdf-core`. Add it with `kitepdf`, or on its own.
