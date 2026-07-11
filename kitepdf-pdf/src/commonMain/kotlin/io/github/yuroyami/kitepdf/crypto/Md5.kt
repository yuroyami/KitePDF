package io.github.yuroyami.kitepdf.crypto

/**
 * Pure-Kotlin MD5 (RFC 1321).
 *
 * Used by PDF Standard Security Handlers V1–V4 for key derivation; never
 * for authentication. Don't use for anything else.
 *
 * Canonical single-loop form: 64 iterations, register-rotation expressed via
 * the four registers rotated each step. Reads (and verifies) cleanly against
 * the spec, which the earlier multi-round-loop form did not.
 */
public object Md5 {

    public fun hash(input: ByteArray): ByteArray {
        val state = State()
        state.update(input)
        state.finish()
        return state.digest()
    }

    private class State {
        private var h0 = 0x67452301.toInt()
        private var h1 = 0xEFCDAB89.toInt()
        private var h2 = 0x98BADCFE.toInt()
        private var h3 = 0x10325476.toInt()
        private val buffer = ByteArray(64)
        private var bufLen = 0
        private var byteCount = 0L
        private var finished = false

        fun update(bytes: ByteArray) {
            byteCount += bytes.size
            var offset = 0
            var remaining = bytes.size
            if (bufLen > 0) {
                val take = minOf(64 - bufLen, remaining)
                bytes.copyInto(buffer, bufLen, offset, offset + take)
                bufLen += take; offset += take; remaining -= take
                if (bufLen == 64) { processBlock(buffer, 0); bufLen = 0 }
            }
            while (remaining >= 64) {
                processBlock(bytes, offset); offset += 64; remaining -= 64
            }
            if (remaining > 0) {
                bytes.copyInto(buffer, 0, offset, offset + remaining)
                bufLen = remaining
            }
        }

        fun finish() {
            if (finished) return
            val bitLen = byteCount * 8
            buffer[bufLen++] = 0x80.toByte()
            if (bufLen > 56) {
                while (bufLen < 64) buffer[bufLen++] = 0
                processBlock(buffer, 0); bufLen = 0
            }
            while (bufLen < 56) buffer[bufLen++] = 0
            // 8-byte little-endian bit length.
            for (i in 0..7) buffer[bufLen++] = (bitLen ushr (8 * i)).toByte()
            processBlock(buffer, 0)
            finished = true
        }

        fun digest(): ByteArray = ByteArray(16).also { out ->
            for (i in 0..3) {
                out[i] = (h0 ushr (8 * i)).toByte()
                out[i + 4] = (h1 ushr (8 * i)).toByte()
                out[i + 8] = (h2 ushr (8 * i)).toByte()
                out[i + 12] = (h3 ushr (8 * i)).toByte()
            }
        }

        private fun processBlock(block: ByteArray, offset: Int) {
            val m = IntArray(16) { i ->
                (block[offset + i * 4].toInt() and 0xFF) or
                    ((block[offset + i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((block[offset + i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((block[offset + i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            var a = h0; var b = h1; var c = h2; var d = h3

            for (i in 0..63) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    i < 32 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) and 15
                    }
                    i < 48 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) and 15
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) and 15
                    }
                }
                val temp = d
                d = c
                c = b
                b = b + rotl(a + f + m[g] + T[i], S[i])
                a = temp
            }

            h0 += a; h1 += b; h2 += c; h3 += d
        }

        private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))
    }

    /** Per-step shift values — repeat-4 within each round. */
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** T[i] = floor(2^32 × |sin(i+1)|) — RFC 1321 §3.4. */
    private val T = intArrayOf(
        0xD76AA478.toInt(), 0xE8C7B756.toInt(), 0x242070DB,         0xC1BDCEEE.toInt(),
        0xF57C0FAF.toInt(), 0x4787C62A,         0xA8304613.toInt(), 0xFD469501.toInt(),
        0x698098D8.toInt(), 0x8B44F7AF.toInt(), 0xFFFF5BB1.toInt(), 0x895CD7BE.toInt(),
        0x6B901122,         0xFD987193.toInt(), 0xA679438E.toInt(), 0x49B40821,
        0xF61E2562.toInt(), 0xC040B340.toInt(), 0x265E5A51,         0xE9B6C7AA.toInt(),
        0xD62F105D.toInt(), 0x02441453,         0xD8A1E681.toInt(), 0xE7D3FBC8.toInt(),
        0x21E1CDE6,         0xC33707D6.toInt(), 0xF4D50D87.toInt(), 0x455A14ED,
        0xA9E3E905.toInt(), 0xFCEFA3F8.toInt(), 0x676F02D9,         0x8D2A4C8A.toInt(),
        0xFFFA3942.toInt(), 0x8771F681.toInt(), 0x6D9D6122,         0xFDE5380C.toInt(),
        0xA4BEEA44.toInt(), 0x4BDECFA9,         0xF6BB4B60.toInt(), 0xBEBFBC70.toInt(),
        0x289B7EC6,         0xEAA127FA.toInt(), 0xD4EF3085.toInt(), 0x04881D05,
        0xD9D4D039.toInt(), 0xE6DB99E5.toInt(), 0x1FA27CF8,         0xC4AC5665.toInt(),
        0xF4292244.toInt(), 0x432AFF97,         0xAB9423A7.toInt(), 0xFC93A039.toInt(),
        0x655B59C3,         0x8F0CCC92.toInt(), 0xFFEFF47D.toInt(), 0x85845DD1.toInt(),
        0x6FA87E4F,         0xFE2CE6E0.toInt(), 0xA3014314.toInt(), 0x4E0811A1,
        0xF7537E82.toInt(), 0xBD3AF235.toInt(), 0x2AD7D2BB,         0xEB86D391.toInt(),
    )
}
