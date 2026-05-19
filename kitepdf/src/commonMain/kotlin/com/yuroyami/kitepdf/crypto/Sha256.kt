package com.yuroyami.kitepdf.crypto

/**
 * Pure-Kotlin SHA-256 (FIPS 180-4).
 *
 * Used by PDF Standard Security Handler V5/V6 for password-to-key derivation.
 */
object Sha256 {

    fun hash(input: ByteArray): ByteArray {
        val state = ShaState()
        state.update(input)
        state.finish()
        return state.digest()
    }

    private class ShaState {
        private val h = intArrayOf(
            0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
            0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
        )
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
            while (remaining >= 64) { processBlock(bytes, offset); offset += 64; remaining -= 64 }
            if (remaining > 0) {
                bytes.copyInto(buffer, 0, offset, offset + remaining); bufLen = remaining
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
            for (i in 7 downTo 0) buffer[bufLen++] = (bitLen ushr (8 * i)).toByte()
            processBlock(buffer, 0)
            finished = true
        }

        fun digest(): ByteArray {
            val out = ByteArray(32)
            for (i in 0..7) {
                out[i * 4]     = (h[i] ushr 24).toByte()
                out[i * 4 + 1] = (h[i] ushr 16).toByte()
                out[i * 4 + 2] = (h[i] ushr 8).toByte()
                out[i * 4 + 3] = h[i].toByte()
            }
            return out
        }

        private fun processBlock(block: ByteArray, offset: Int) {
            val w = IntArray(64)
            for (i in 0..15) {
                w[i] = ((block[offset + i * 4].toInt() and 0xFF) shl 24) or
                    ((block[offset + i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((block[offset + i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (block[offset + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16..63) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0..63) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g; g = f; f = e
                e = d + temp1
                d = c; c = b; b = a
                a = temp1 + temp2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }

        private fun rotr(v: Int, n: Int): Int = (v ushr n) or (v shl (32 - n))
    }

    private val K = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )
}
