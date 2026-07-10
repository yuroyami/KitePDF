package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.PdfFormatException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * Opens the PDF at [path], loading the WHOLE file into memory first (thin
 * sugar over [PdfDocument.open]; there is no incremental file reader).
 *
 * @throws PdfFormatException when the file can't be read.
 * @throws WrongPasswordException when the document is encrypted and
 *   [password] doesn't authenticate.
 */
@OptIn(ExperimentalForeignApi::class)
fun PdfDocument.Companion.openFile(
    path: String,
    password: ByteArray = byteArrayOf(),
): PdfDocument {
    val data = NSData.dataWithContentsOfFile(path)
        ?: throw PdfFormatException("Cannot read file: $path")
    return open(data.toByteArray(), password)
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
