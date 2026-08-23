package io.github.yuroyami.kitepdf.epub

import java.io.File

/**
 * Opens the EPUB at [path], loading the WHOLE file into memory first (thin
 * sugar over [EpubDocument.open]; there is no incremental file reader).
 *
 * @throws java.io.IOException when the file can't be read.
 * @throws EpubFormatException when the bytes are not a readable EPUB.
 */
public fun EpubDocument.Companion.openFile(
    path: String,
    settings: EpubSettings = EpubSettings(),
): EpubDocument = open(File(path).readBytes(), settings)
