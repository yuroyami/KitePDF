package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

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
@OptIn(ExperimentalForeignApi::class)
public fun KiteDoc.openFile(
    path: String,
    password: String = "",
    epubSettings: EpubSettings = EpubSettings(),
): KiteDocument = open(readWholeFile(path), password, epubSettings)

@OptIn(ExperimentalForeignApi::class)
private fun readWholeFile(path: String): ByteArray {
    val file = fopen(path, "rb") ?: throw KiteFormatException("cannot open file: $path")
    try {
        if (fseek(file, 0L.convert(), SEEK_END) != 0) throw KiteFormatException("cannot seek file: $path")
        val size = ftell(file).toLong()
        if (size < 0L) throw KiteFormatException("cannot size file: $path")
        if (size == 0L) return ByteArray(0)
        if (size > Int.MAX_VALUE.toLong()) {
            throw KiteFormatException("file is larger than 2 GB, which this reader cannot hold: $path")
        }
        if (fseek(file, 0L.convert(), SEEK_SET) != 0) throw KiteFormatException("cannot rewind file: $path")
        val out = ByteArray(size.toInt())
        val read = out.usePinned { fread(it.addressOf(0), 1.convert(), size.convert(), file).toLong() }
        if (read != size) throw KiteFormatException("short read on $path ($read of $size bytes)")
        return out
    } finally {
        fclose(file)
    }
}
