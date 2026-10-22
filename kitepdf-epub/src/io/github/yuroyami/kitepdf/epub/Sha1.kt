package io.github.yuroyami.kitepdf.epub

/**
 * Minimal pure-Kotlin SHA-1 (RFC 3174). EPUB's IDPF font-obfuscation key is the
 * SHA-1 of the package's unique identifier, so it's needed to deobfuscate mangled
 * fonts. Not for security -- SHA-1 is broken for that -- only to reproduce the
 * IDPF key exactly.
 */
internal object Sha1 {

    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val ml = message.size.toLong() * 8
        // Pad: 0x80, then zeros, then 64-bit big-endian bit length, to a 64-byte multiple.
        val withOne = message.size + 1
        val totalLen = ((withOne + 8 + 63) / 64) * 64
        val msg = ByteArray(totalLen)
        message.copyInto(msg)
        msg[message.size] = 0x80.toByte()
        for (i in 0 until 8) msg[totalLen - 1 - i] = (ml ushr (8 * i)).toByte()

        val w = IntArray(80)
        var chunk = 0
        while (chunk < totalLen) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                w[i] = ((msg[o].toInt() and 0xFF) shl 24) or ((msg[o + 1].toInt() and 0xFF) shl 16) or
                    ((msg[o + 2].toInt() and 0xFF) shl 8) or (msg[o + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = rotl(w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16], 1)

            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val (f, k) = when {
                    i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                    i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val temp = rotl(a, 5) + f + e + k + w[i]
                e = d; d = c; c = rotl(b, 30); b = a; a = temp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            chunk += 64
        }

        val out = ByteArray(20)
        intToBytes(h0, out, 0); intToBytes(h1, out, 4); intToBytes(h2, out, 8)
        intToBytes(h3, out, 12); intToBytes(h4, out, 16)
        return out
    }

    private fun rotl(v: Int, bits: Int): Int = (v shl bits) or (v ushr (32 - bits))

    private fun intToBytes(v: Int, out: ByteArray, o: Int) {
        out[o] = (v ushr 24).toByte(); out[o + 1] = (v ushr 16).toByte()
        out[o + 2] = (v ushr 8).toByte(); out[o + 3] = v.toByte()
    }
}
