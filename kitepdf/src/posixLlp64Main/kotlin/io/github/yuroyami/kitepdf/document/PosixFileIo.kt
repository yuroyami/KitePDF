package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.core.KiteFormatException
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

/*
 * This file is deliberately byte-identical in posixLp64Main, posixIlp32Main
 * and posixLlp64Main; keep the three copies in sync. One shared copy cannot
 * exist because the metadata compiler refuses stdio signatures whose C widths
 * differ between member targets. convert()/toLong() absorb the per-family
 * widths (ftell is 64-bit on LP64, 32-bit on ILP32 and MinGW).
 */
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
@OptIn(ExperimentalForeignApi::class)
internal actual fun readPosixFile(path: String): ByteArray {
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
