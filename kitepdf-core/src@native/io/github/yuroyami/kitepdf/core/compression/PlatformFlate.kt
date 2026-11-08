package io.github.yuroyami.kitepdf.core.compression

/**
 * No fast path on this target: the pure-Kotlin [Inflate]/[Deflate] handle
 * everything. On Apple platforms the Compression framework
 * (`compression_decode_buffer` with COMPRESSION_ZLIB) is the noted follow-up
 * once a measurement shows a need: on the JVM, native zlib is only 1.1 to 1.6
 * times faster than the pure inflate (#835), and Kotlin/Native has not been
 * measured.
 */
internal actual object PlatformFlate {
    actual fun inflateOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? = null
    actual fun inflateRawOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? = null
    actual fun deflateOrNull(data: ByteArray, level: Int): ByteArray? = null
}
