package io.github.yuroyami.kitepdf.crypto.sig

/** SHA-1 (FIPS 180-4), which older PDF signatures digest with (#203). */
internal object Sha1 {

    fun hash(input: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 8) / 64 + 1) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(80)
        for (block in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = block + i * 4
                w[i] = ((padded[o].toInt() and 0xFF) shl 24) or ((padded[o + 1].toInt() and 0xFF) shl 16) or
                    ((padded[o + 2].toInt() and 0xFF) shl 8) or (padded[o + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val f = when {
                    i < 20 -> ((b and c) or (b.inv() and d)) + 0x5A827999
                    i < 40 -> (b xor c xor d) + 0x6ED9EBA1
                    i < 60 -> ((b and c) or (b and d) or (c and d)) + 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) + 0xCA62C1D6.toInt()
                }
                val t = a.rotateLeft(5) + f + e + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = t
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        val out = ByteArray(20)
        for ((i, h) in intArrayOf(h0, h1, h2, h3, h4).withIndex()) {
            out[i * 4] = (h ushr 24).toByte(); out[i * 4 + 1] = (h ushr 16).toByte()
            out[i * 4 + 2] = (h ushr 8).toByte(); out[i * 4 + 3] = h.toByte()
        }
        return out
    }
}
