package io.github.yuroyami.kitepdf

import java.io.File

/**
 * Opens the PDF at [path], loading the WHOLE file into memory first (thin
 * sugar over [PdfDocument.open]; there is no incremental file reader).
 *
 * @throws java.io.IOException when the file can't be read.
 * @throws WrongPasswordException when the document is encrypted and
 *   [password] doesn't authenticate.
 */
public fun PdfDocument.Companion.openFile(
    path: String,
    password: ByteArray = byteArrayOf(),
): PdfDocument = open(File(path).readBytes(), password)
