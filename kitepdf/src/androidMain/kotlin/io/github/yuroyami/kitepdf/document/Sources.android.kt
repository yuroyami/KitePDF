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
 * @throws java.io.IOException when the file cannot be read.
 * @throws KiteFormatException when it is neither a PDF nor an EPUB.
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

/** Drains [stream] and opens what comes out. The stream is closed either way. */
public fun KiteDoc.open(
    stream: InputStream,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = stream.use { open(it.readBytes(), password, epubSettings) }

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
public fun KiteDoc.open(
    context: Context,
    uri: Uri,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw KiteFormatException("cannot open $uri through the content resolver")
    return open(bytes, password, epubSettings)
}
