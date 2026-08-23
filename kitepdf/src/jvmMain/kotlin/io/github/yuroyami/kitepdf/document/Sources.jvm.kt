package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.io.File
import java.io.InputStream

/**
 * Reads the file at [path] and opens it as whichever format it is.
 *
 * The whole file is loaded into memory first: the engine has no incremental
 * reader, so every source ends up as one byte array.
 *
 * @throws java.io.IOException when the file cannot be read.
 * @throws io.github.yuroyami.kitepdf.core.KiteFormatException when it is
 *   neither a PDF nor an EPUB.
 */
public fun KiteDoc.openFile(
    path: String,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(File(path).readBytes(), password, epubSettings)

/** [openFile] for a [File] you already hold. */
public fun KiteDoc.open(
    file: File,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(file.readBytes(), password, epubSettings)

/**
 * Drains [stream] and opens what comes out. The stream is closed either way.
 *
 * Use it for a classpath resource, a servlet upload, a decrypted stream: any
 * source that is already an [InputStream].
 */
public fun KiteDoc.open(
    stream: InputStream,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = stream.use { open(it.readBytes(), password, epubSettings) }
