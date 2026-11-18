package io.github.yuroyami.kitepdf.epub.brotli

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * Brotli (RFC 7932) for WOFF2 font unpacking. The decoder is
 * [ArchiveKodec](https://github.com/yuroyami/ArchiveKodec)'s.
 *
 * Throws an `ArchiveException` on malformed input or when the output would exceed [decode]'s
 * `maxOutputBytes` (decompression-bomb guard).
 */
internal object Brotli {

    fun decode(input: ByteArray, maxOutputBytes: Int = Int.MAX_VALUE): ByteArray {
        // Only the absolute cap counts: a valid Brotli stream can pass any ratio limit.
        val limits = ArchiveLimits {
            maxUncompressedBytes = maxOutputBytes.toLong()
            maxCompressionRatio = Int.MAX_VALUE
        }
        return ArchiveKodec.brotli.decompress(input, limits)
    }
}
