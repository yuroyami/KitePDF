package io.github.yuroyami.kitepdf.epub

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * Opens the EPUB at [path], loading the WHOLE file into memory first (thin
 * sugar over [EpubDocument.open]; there is no incremental file reader).
 *
 * @throws EpubFormatException when the file can't be read, or is not a
 *   readable EPUB.
 */
@OptIn(ExperimentalForeignApi::class)
public fun EpubDocument.Companion.openFile(
    path: String,
    settings: EpubSettings = EpubSettings(),
): EpubDocument {
    val data = NSData.dataWithContentsOfFile(path)
        ?: throw EpubFormatException("Cannot read file: $path")
    return open(data.toByteArray(), settings)
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)
private fun NSData.toByteArray(): ByteArray {
    // NSUInteger differs in width between 32- and 64-bit Apple targets;
    // convert() keeps this shared appleMain source width-agnostic.
    // A length past Int.MAX_VALUE kept only its low 32 bits: a negative size, or the first bytes
    // of a file past 4 GiB opened as the file. Refuse it, as the POSIX reader does (#832).
    val size = length.toLong()
    if (size < 0L || size > Int.MAX_VALUE.toLong()) {
        throw EpubFormatException("file is larger than 2 GB, which this reader cannot hold ($size bytes)")
    }
    val n = size.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, n.convert()) }
    return out
}
