package io.github.yuroyami.kitepdf.core.compression

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.codec.deflate.Inflate as ArchiveInflate
import io.github.yuroyami.archivekodec.codec.deflate.InflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.InflateResult
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException

/** Thrown by [Inflate] and [Zlib] on malformed input or on output past the caller's cap. */
public class InflateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Raw DEFLATE (RFC 1951) decoding. The decoder is [ArchiveKodec](https://github.com/yuroyami/ArchiveKodec)'s.
 */
public object Inflate {

    /**
     * Inflate the DEFLATE payload in [input] that starts at [offset] and lies within [length] bytes.
     * Bytes after the end of the stream are ignored. [maxOutputBytes] caps the decoded size;
     * exceeding it throws [InflateException].
     */
    @Deprecated(
        "Use ArchiveKodec's Inflate.inflate, or decodePlatform for the platform fast path",
        ReplaceWith("decodePlatform(input, offset, length, maxOutputBytes)"),
    )
    public fun decode(
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
        maxOutputBytes: Int = Int.MAX_VALUE,
    ): ByteArray = Flate.inflateRaw(input, offset, length, maxOutputBytes).output

    /**
     * Inflate as [decode] does, through the platform's own zlib where the target has one (JVM and
     * Android). ArchiveKodec picks that path and gives the same result as its own decoder.
     */
    public fun decodePlatform(
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
        maxOutputBytes: Int = Int.MAX_VALUE,
    ): ByteArray = Flate.inflateRaw(input, offset, length, maxOutputBytes, platform = true).output
}

/** The calls into ArchiveKodec, with its exceptions turned into [InflateException]. */
internal object Flate {

    fun limits(maxOutputBytes: Int): ArchiveLimits = ArchiveLimits { maxUncompressedBytes = maxOutputBytes.toLong() }

    /** The whole of [input] as one raw DEFLATE stream, with no cap. For the tables built into the library. */
    fun inflate(input: ByteArray): ByteArray = inflateRaw(input, 0, input.size, Int.MAX_VALUE).output

    /** Raw DEFLATE. The result also reports how much input the stream took. */
    fun inflateRaw(input: ByteArray, offset: Int, length: Int, maxOutputBytes: Int, platform: Boolean = false): InflateResult {
        val options = InflateOptions {
            limits = limits(maxOutputBytes)
            allowTrailingData = true
            usePlatformInflater = platform
        }
        return archive { ArchiveInflate.inflate(input, offset, length, options) }
    }

    inline fun <T> archive(block: () -> T): T = try {
        block()
    } catch (e: DecompressionBombException) {
        throw InflateException("inflated output exceeds cap: ${e.message}", e)
    } catch (e: ArchiveException) {
        throw InflateException(e.message ?: "inflate failed", e)
    }
}
