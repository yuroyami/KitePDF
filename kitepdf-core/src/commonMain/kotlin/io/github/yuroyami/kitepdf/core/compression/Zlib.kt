package io.github.yuroyami.kitepdf.core.compression

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * zlib (RFC 1950) wrapper around [Inflate]/[Deflate].
 *
 * PDF's FlateDecode filter stores zlib-wrapped DEFLATE data, not bare DEFLATE.
 * The wrapper is: 2-byte CMF/FLG header, DEFLATE payload, 4-byte big-endian
 * Adler-32 trailer. We verify both header validity and the Adler checksum.
 */
public object Zlib {

    /**
     * zlib-wrap a DEFLATE compression of [data] (PDF FlateDecode form). Header
     * is the conventional `0x78 0x9C` (32 KiB window, default level, no preset
     * dictionary; passes the mod-31 check), and the Adler-32 of the original
     * data is appended big-endian. Round-trips through [decode].
     */
    public fun encode(data: ByteArray): ByteArray {
        // Platform fast path (T-10): native zlib at level 6 emits the whole
        // RFC 1950 stream (header + Adler) and compresses far better than the
        // pure-Kotlin encoder. Null means no fast path on this target.
        PlatformFlate.deflateOrNull(data, level = 6)?.let { return it }

        val out = ByteArrayBuilder(data.size / 2 + 16)
        out.append(0x78.toByte())
        out.append(0x9C.toByte())
        out.append(Deflate.encode(data))
        val adler = adler32(data)
        out.append(((adler ushr 24) and 0xFF).toByte())
        out.append(((adler ushr 16) and 0xFF).toByte())
        out.append(((adler ushr 8) and 0xFF).toByte())
        out.append((adler and 0xFF).toByte())
        return out.toByteArray()
    }

    /**
     * Unwrap and inflate a zlib stream. [maxOutputBytes] caps the decoded size
     * (see [Inflate.decode]); exceeding it throws [InflateException].
     */
    public fun decode(
        input: ByteArray,
        verifyChecksum: Boolean = true,
        maxOutputBytes: Int = Int.MAX_VALUE,
    ): ByteArray {
        if (input.size < 6) throw InflateException("Zlib stream too short: ${input.size} bytes")

        val cmf = input[0].toInt() and 0xFF
        val flg = input[1].toInt() and 0xFF

        // CMF: low 4 bits = method (8 = deflate); high 4 bits = window size info.
        if ((cmf and 0x0F) != 8) throw InflateException("Zlib: not DEFLATE (CM=${cmf and 0x0F})")
        if (((cmf shl 8) or flg) % 31 != 0) throw InflateException("Zlib: header checksum failed")
        if ((flg and 0x20) != 0) throw InflateException("Zlib: preset dictionary not supported")

        // Platform fast path (T-10): hand the native inflater the whole stream
        // (it parses the header and verifies the Adler trailer itself, so no
        // re-verification below). Null — malformed, truncated, over the cap —
        // falls through to the pure-Kotlin path for its lenient behaviour and
        // proper error messages.
        PlatformFlate.inflateOrNull(input, 0, input.size, maxOutputBytes)?.let { return it }

        // Inflate the DEFLATE payload that sits between the 2-byte header and the
        // 4-byte Adler trailer — read in place, no slice.
        val decoded = Inflate.decode(input, offset = 2, length = input.size - 6, maxOutputBytes = maxOutputBytes)

        if (verifyChecksum) {
            val n = input.size
            val expected =
                ((input[n - 4].toLong() and 0xFF) shl 24) or
                    ((input[n - 3].toLong() and 0xFF) shl 16) or
                    ((input[n - 2].toLong() and 0xFF) shl 8) or
                    (input[n - 1].toLong() and 0xFF)
            val actual = adler32(decoded).toLong() and 0xFFFFFFFFL
            if (expected != actual) {
                throw InflateException("Zlib: Adler-32 mismatch (expected=$expected actual=$actual)")
            }
        }
        return decoded
    }

    /** RFC 1950 §9: Adler-32, mod 65521 over the decoded data. */
    public fun adler32(data: ByteArray): Int {
        // Accumulate in Long. NMAX (5552) is the C zlib run length before `b`
        // overflows an *unsigned* 32-bit int; within it `b` reaches ~3.9e9, which
        // overflows a signed Int (max ~2.1e9) and corrupted the checksum on large
        // inputs (small ones stayed under 2^31, so the bug hid). Long has headroom.
        var a = 1L
        var b = 0L
        var i = 0
        val n = data.size
        while (i < n) {
            var k = minOf(NMAX, n - i)
            while (k-- > 0) {
                a += data[i++].toInt() and 0xFF
                b += a
            }
            a %= MOD_ADLER
            b %= MOD_ADLER
        }
        return ((b shl 16) or a).toInt()
    }

    private const val MOD_ADLER = 65_521
    private const val NMAX = 5552
}
