package io.github.yuroyami.kitepdf.compression

/**
 * Optional platform-native flate fast path (T-10). Callers try it first and
 * fall back to the pure-Kotlin [Inflate]/[Deflate] on a null return, so a
 * platform either accelerates the hot path or costs nothing.
 *
 * Contract: any failure — malformed data, truncation, an output larger than
 * `maxOutput`, an unsupported feature (preset dictionary) — returns null
 * rather than throwing. The pure-Kotlin path then reproduces the proper
 * lenient behaviour and error messages; the fast path never changes WHAT is
 * decoded, only how fast the well-formed common case runs.
 *
 * The pure-Kotlin codec stays the correctness reference and the only
 * implementation on targets without a stdlib zlib (JS, Wasm, native).
 */
internal expect object PlatformFlate {

    /** Inflate a whole zlib (RFC 1950) stream, header and Adler-32 included. */
    fun inflateOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray?

    /** Inflate a bare DEFLATE (RFC 1951) stream — zip entries, WOFF2-adjacent uses. */
    fun inflateRawOrNull(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray?

    /** Deflate to a whole zlib stream at [level] (0..9), or null for no fast path. */
    fun deflateOrNull(data: ByteArray, level: Int): ByteArray?
}
