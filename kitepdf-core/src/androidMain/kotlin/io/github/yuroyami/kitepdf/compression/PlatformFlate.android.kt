package io.github.yuroyami.kitepdf.compression

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Android fast path (same JDK classes as jvmMain; the source sets cannot share a file without a custom hierarchy, so this is a deliberate duplicate): `java.util.zip` (native zlib underneath). Measured ~9x
 * faster than the pure-Kotlin inflate on large streams. Any decode problem
 * returns null so the pure path can produce its lenient result.
 */
internal actual object PlatformFlate {

    actual fun inflateOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? =
        inflate(data, offset, length, maxOutput, raw = false)

    actual fun inflateRawOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? =
        inflate(data, offset, length, maxOutput, raw = true)

    private fun inflate(data: ByteArray, offset: Int, length: Int, maxOutput: Int, raw: Boolean): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > data.size) return null
        val inflater = Inflater(raw)
        try {
            inflater.setInput(data, offset, length)
            val out = ByteArrayBuilder(minOf(maxOutput.toLong(), maxOf(length.toLong() * 3, 8192L)).toInt())
            val chunk = ByteArray(64 shl 10)
            var total = 0L
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(chunk)
                } catch (_: DataFormatException) {
                    return null
                }
                if (n == 0) {
                    if (inflater.finished()) break
                    // Truncated input or a preset dictionary: no fast path.
                    return null
                }
                total += n
                // Past the cap: bail out and let the pure path raise its
                // proper decompression-bomb error.
                if (total > maxOutput) return null
                out.append(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    actual fun deflateOrNull(data: ByteArray, level: Int): ByteArray? {
        val deflater = Deflater(level.coerceIn(0, 9), /* nowrap = */ false)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayBuilder(data.size / 2 + 64)
            val chunk = ByteArray(64 shl 10)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.append(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
