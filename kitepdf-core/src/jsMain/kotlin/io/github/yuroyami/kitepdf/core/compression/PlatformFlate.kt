package io.github.yuroyami.kitepdf.core.compression

/**
 * No fast path on this target: the pure-Kotlin [Inflate]/[Deflate] handle
 * everything. On Apple platforms the Compression framework
 * (`compression_decode_buffer` with COMPRESSION_ZLIB) is the noted follow-up
 * when a measured need appears.
 */
internal actual object PlatformFlate {
    actual fun inflateOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? = null
    actual fun inflateRawOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? = null
    actual fun deflateOrNull(data: ByteArray, level: Int): ByteArray? = null
}
