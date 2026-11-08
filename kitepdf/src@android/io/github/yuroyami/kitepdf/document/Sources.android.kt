package io.github.yuroyami.kitepdf.document

import android.content.Context
import android.net.Uri
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.io.File
import java.io.InputStream

/**
 * Reads the file at [path] and opens it as whichever format it is.
 *
 * A file longer than [DEFAULT_MAX_LOCAL_DOCUMENT_BYTES] is refused before it is read; the
 * overload with `maxBytes` raises the limit.
 *
 * @throws java.io.IOException when the file cannot be read.
 * @throws KiteFormatException when it is neither a PDF nor an EPUB.
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

/** Drains [stream] and opens what comes out. The stream is closed either way. */
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

/**
 * Opens a content [uri], which is what the system file picker
 * (`ACTION_OPEN_DOCUMENT`) and share intents actually hand you. A `content://`
 * Uri has no file path, so this reads it through [Context.getContentResolver].
 *
 * ```kotlin
 * val pick = registerForActivityResult(OpenDocument()) { uri ->
 *     uri ?: return@registerForActivityResult
 *     val doc = KiteDoc.open(this, uri)
 * }
 * pick.launch(arrayOf("application/pdf", "application/epub+zip"))
 * ```
 *
 * Reads the whole document into memory, so keep it off the main thread for
 * anything large.
 *
 * @throws KiteFormatException when the Uri cannot be opened, or is neither
 *   a PDF nor an EPUB.
 */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.open(
    context: Context,
    uri: Uri,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(context, uri, password, epubSettings, DEFAULT_MAX_LOCAL_DOCUMENT_BYTES)

/**
 * [open] for a content [uri], refusing one larger than [maxBytes]: by the length the content
 * resolver reports, before it reads, and by a counted read when it reports none (#829).
 */
@Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class, io.github.yuroyami.kitepdf.core.KiteWrongPasswordException::class, java.io.IOException::class)
public fun KiteDoc.open(
    context: Context,
    uri: Uri,
    password: String,
    epubSettings: EpubSettings,
    maxBytes: Int,
): KiteDocument {
    val reported = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    if (reported > maxBytes) throw tooLarge("$uri ($reported bytes)", maxBytes)
    val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, maxBytes, uri.toString()) }
        ?: throw KiteFormatException("cannot open $uri through the content resolver")
    return open(bytes, password, epubSettings)
}
