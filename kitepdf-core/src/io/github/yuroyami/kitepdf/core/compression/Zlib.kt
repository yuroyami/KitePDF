package io.github.yuroyami.kitepdf.core.compression

import io.github.yuroyami.archivekodec.checksum.Adler32
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.InflateOptions
import io.github.yuroyami.archivekodec.wrapper.Zlib as ArchiveZlib
import io.github.yuroyami.kitepdf.core.kiteWarn

/**
 * zlib (RFC 1950) streams, the form that PDF's FlateDecode filter stores.
 *
 * [ArchiveKodec](https://github.com/yuroyami/ArchiveKodec) does the work. On JVM and Android it
 * uses the platform's own zlib where that gives the same result as its own code.
 */
public object Zlib {

    /** zlib-wrap a DEFLATE compression of [data]. Round-trips through [decode]. */
    public fun encode(data: ByteArray): ByteArray =
        ArchiveZlib.compress(data, DeflateOptions { level = 6; usePlatformDeflater = true })

    /**
     * Unwrap and inflate a zlib stream. Bytes after the end of the stream are ignored.
     * [maxOutputBytes] caps the decoded size; exceeding it throws [InflateException].
     */
    public fun decode(
        input: ByteArray,
        verifyChecksum: Boolean = true,
        maxOutputBytes: Int = Int.MAX_VALUE,
    ): ByteArray = decode(input, verifyChecksum, maxOutputBytes, salvage = false)

    /**
     * [decode] for a PDF stream. With [salvage], a stream that ends early, lacks its Adler-32
     * trailer or has a corrupt block gives what it inflated before the damage, with a warning, as
     * MuPDF's "premature end of data in flate filter" does, where it gave nothing and a page of
     * content read as empty (#813). The cap still throws.
     */
    internal fun decode(
        input: ByteArray,
        verifyChecksum: Boolean,
        maxOutputBytes: Int,
        salvage: Boolean,
    ): ByteArray {
        if (input.size < (if (salvage) 2 else 6)) throw InflateException("Zlib stream too short: ${input.size} bytes")

        val cmf = input[0].toInt() and 0xFF
        val flg = input[1].toInt() and 0xFF

        // CMF: low 4 bits = method (8 = deflate); high 4 bits = window size info.
        if ((cmf and 0x0F) != 8) throw InflateException("Zlib: not DEFLATE (CM=${cmf and 0x0F})")
        if (((cmf shl 8) or flg) % 31 != 0) throw InflateException("Zlib: header checksum failed")
        if ((flg and 0x20) != 0) throw InflateException("Zlib: preset dictionary not supported")

        val options = InflateOptions {
            limits = Flate.limits(maxOutputBytes)
            this.verifyChecksum = verifyChecksum && !salvage
            allowTrailingData = true
            keepPartialOutput = salvage
            usePlatformInflater = true
        }
        val result = Flate.archive { ArchiveZlib.decompress(input, options) }
        result.error?.let { kiteWarn { "flate: ${it.message}; keeping the ${result.output.size} bytes inflated before it" } }
        return result.output
    }

    /** RFC 1950, 9: the Adler-32 of [data]. */
    @Deprecated(
        "Use ArchiveKodec's Adler32.of",
        ReplaceWith("Adler32.of(data).toInt()", "io.github.yuroyami.archivekodec.checksum.Adler32"),
    )
    public fun adler32(data: ByteArray): Int = Adler32.of(data).toInt()
}
