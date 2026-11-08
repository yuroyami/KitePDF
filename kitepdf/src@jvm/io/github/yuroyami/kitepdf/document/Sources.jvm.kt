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
 * A file longer than [DEFAULT_MAX_LOCAL_DOCUMENT_BYTES] is refused before it is read; the
 * overload with `maxBytes` raises the limit.
 *
 * @throws java.io.IOException when the file cannot be read.
 * @throws io.github.yuroyami.kitepdf.core.KiteFormatException when it is
 *   neither a PDF nor an EPUB.
 */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.openFile(
    path: String,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = openFile(path, password, epubSettings, DEFAULT_MAX_LOCAL_DOCUMENT_BYTES)

/**
 * [openFile], refusing a file longer than [maxBytes] with a
 * [io.github.yuroyami.kitepdf.core.KiteFormatException] before it reads a byte (#829).
 */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.openFile(
    path: String,
    password: String,
    epubSettings: EpubSettings,
    maxBytes: Int,
): KiteDocument = open(readBounded(File(path), maxBytes), password, epubSettings)

/** [openFile] for a [File] you already hold. */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.open(
    file: File,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(file, password, epubSettings, DEFAULT_MAX_LOCAL_DOCUMENT_BYTES)

/** [open] for a [File], refusing one longer than [maxBytes]. */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.open(
    file: File,
    password: String,
    epubSettings: EpubSettings,
    maxBytes: Int,
): KiteDocument = open(readBounded(file, maxBytes), password, epubSettings)

/**
 * Drains [stream] and opens what comes out. The stream is closed either way.
 *
 * Use it for a classpath resource, a servlet upload, a decrypted stream: any
 * source that is already an [InputStream].
 */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.open(
    stream: InputStream,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(stream, password, epubSettings, DEFAULT_MAX_LOCAL_DOCUMENT_BYTES)

/** [open] for a stream, stopping with a [io.github.yuroyami.kitepdf.core.KiteFormatException] once it passes [maxBytes]. */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.open(
    stream: InputStream,
    password: String,
    epubSettings: EpubSettings,
    maxBytes: Int,
): KiteDocument = stream.use { open(readBounded(it, maxBytes, "the stream"), password, epubSettings) }
