package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.epub.EpubSettings

/**
 * Reads the file at [path] and opens it as whichever format it is.
 *
 * The Kotlin/Native desktop and Android-NDK targets have no `java.io` and no
 * Foundation, so this goes through `stdio`. The whole file is read into memory,
 * like every other source.
 *
 * @throws KiteFormatException when the file cannot be read, or is neither a
 *   PDF nor an EPUB.
 */
public fun KiteDoc.openFile(
    path: String,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(readPosixFile(path), password, epubSettings)

/*
 * The stdio calls live in the per-ABI-family source sets, not here: the
 * metadata compiler refuses cinterop signatures whose C widths differ
 * between the member targets (CLong, size_t).
 */
internal expect fun readPosixFile(path: String): ByteArray
