package io.github.yuroyami.kitepdf.compression

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * zlib (RFC 1950) wrapper around [Inflate]/[Deflate].
 *
 * PDF's FlateDecode filter stores zlib-wrapped DEFLATE data, not bare DEFLATE.
 * The wrapper is: 2-byte CMF/FLG header, DEFLATE payload, 4-byte big-endian
 * Adler-32 trailer. We verify both header validity and the Adler checksum.
 */
object Zlib {

    /**
     * zlib-wrap a DEFLATE compression of [data] (PDF FlateDecode form). Header
     * is the conventional `0x78 0x9C` (32 KiB window, default level, no preset
     * dictionary; passes the mod-31 check), and the Adler-32 of the original
     * data is appended big-endian. Round-trips through [decode].
     */
    fun encode(data: ByteArray): ByteArray {
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

    fun decode(input: ByteArray, verifyChecksum: Boolean = true): ByteArray {
        if (input.size < 6) throw InflateException("Zlib stream too short: ${input.size} bytes")

        val cmf = input[0].toInt() and 0xFF
        val flg = input[1].toInt() and 0xFF

        // CMF: low 4 bits = method (8 = deflate); high 4 bits = window size info.
        if ((cmf and 0x0F) != 8) throw InflateException("Zlib: not DEFLATE (CM=${cmf and 0x0F})")
        if (((cmf shl 8) or flg) % 31 != 0) throw InflateException("Zlib: header checksum failed")
        if ((flg and 0x20) != 0) throw InflateException("Zlib: preset dictionary not supported")

        // Inflate the DEFLATE payload that sits between the 2-byte header and the
        // 4-byte Adler trailer — read in place, no slice.
        val decoded = Inflate.decode(input, offset = 2, length = input.size - 6)

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
    fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        // NMAX = largest run of bytes before (b) can overflow a signed int; defer
        // the modulo to once per block instead of twice per byte.
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
        return (b shl 16) or a
    }

    private const val MOD_ADLER = 65_521
    private const val NMAX = 5552
}
