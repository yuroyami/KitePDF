package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

/**
 * Reads the file at [path] and opens it as whichever format it is.
 *
 * @throws KiteFormatException when the file cannot be read, or is neither a
 *   PDF nor an EPUB.
 */
@OptIn(ExperimentalForeignApi::class)
public fun KiteDoc.openFile(
    path: String,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument {
    val data = NSData.dataWithContentsOfFile(path)
        ?: throw KiteFormatException("cannot read file: $path")
    return open(data, password, epubSettings)
}

/**
 * Opens an [NSURL]. A file URL is read straight off disk; anything else is
 * left to Foundation, so a `file://` bookmark from `UIDocumentPickerViewController`
 * works, and a remote URL blocks the calling thread. For remote documents
 * prefer the `kitepdf-net` artifact, which fetches asynchronously.
 *
 * @throws KiteFormatException when the URL cannot be read, or is neither
 *   format.
 */
@OptIn(ExperimentalForeignApi::class)
public fun KiteDoc.open(
    url: NSURL,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument {
    val data = NSData.dataWithContentsOfURL(url)
        ?: throw KiteFormatException("cannot read URL: $url")
    return open(data, password, epubSettings)
}

/** Opens [data] you already hold, e.g. from a network call or the pasteboard. */
@OptIn(ExperimentalForeignApi::class)
public fun KiteDoc.open(
    data: NSData,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(data.toByteArray(), password, epubSettings)

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)
private fun NSData.toByteArray(): ByteArray {
    // NSUInteger differs in width between 32- and 64-bit Apple targets;
    // convert() keeps this shared appleMain source width-agnostic.
    val n = length.toLong().toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, n.convert()) }
    return out
}
