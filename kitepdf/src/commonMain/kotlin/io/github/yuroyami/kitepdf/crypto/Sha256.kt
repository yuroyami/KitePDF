package io.github.yuroyami.kitepdf.crypto

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

/**
 * Pure-Kotlin SHA-512 core (FIPS 180-4), with SHA-384 as the same permutation
 * under a different IV and truncated output.
 *
 * Used by PDF Standard Security Handler R6 (PDF 2.0) Algorithm 2.B, which
 * selects SHA-256/384/512 per iteration based on the running state.
 */
object Sha512 {

    fun hash(input: ByteArray): ByteArray = digest(input, IV_512, 64)

    /** SHA-384 = SHA-512 with a different IV, output truncated to 48 bytes. */
    fun hash384(input: ByteArray): ByteArray = digest(input, IV_384, 48)

    private fun digest(input: ByteArray, iv: LongArray, outLen: Int): ByteArray {
        val h = iv.copyOf()
        // Pad: append 0x80, then zeros, then 128-bit big-endian bit length.
        val msgLen = input.size
        val bitLen = msgLen.toLong() * 8
        // Total length must be a multiple of 128; reserve 16 bytes for length.
        val padLen = ((112 - (msgLen + 1) % 128) + 128) % 128
        val total = msgLen + 1 + padLen + 16
        val buf = ByteArray(total)
        input.copyInto(buf, 0)
        buf[msgLen] = 0x80.toByte()
        // 128-bit length: high 64 bits are always 0 for our sizes; low 64 bits at the end.
        for (i in 0..7) buf[total - 1 - i] = (bitLen ushr (8 * i)).toByte()

        val w = LongArray(80)
        var off = 0
        while (off < total) {
            for (i in 0..15) {
                w[i] = ((buf[off + i * 8].toLong() and 0xFF) shl 56) or
                    ((buf[off + i * 8 + 1].toLong() and 0xFF) shl 48) or
                    ((buf[off + i * 8 + 2].toLong() and 0xFF) shl 40) or
                    ((buf[off + i * 8 + 3].toLong() and 0xFF) shl 32) or
                    ((buf[off + i * 8 + 4].toLong() and 0xFF) shl 24) or
                    ((buf[off + i * 8 + 5].toLong() and 0xFF) shl 16) or
                    ((buf[off + i * 8 + 6].toLong() and 0xFF) shl 8) or
                    (buf[off + i * 8 + 7].toLong() and 0xFF)
            }
            for (i in 16..79) {
                val s0 = rotr(w[i - 15], 1) xor rotr(w[i - 15], 8) xor (w[i - 15] ushr 7)
                val s1 = rotr(w[i - 2], 19) xor rotr(w[i - 2], 61) xor (w[i - 2] ushr 6)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0..79) {
                val S1 = rotr(e, 14) xor rotr(e, 18) xor rotr(e, 41)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + S1 + ch + K[i] + w[i]
                val S0 = rotr(a, 28) xor rotr(a, 34) xor rotr(a, 39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = S0 + maj
                hh = g; g = f; f = e
                e = d + temp1
                d = c; c = b; b = a
                a = temp1 + temp2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            off += 128
        }

        val out = ByteArray(outLen)
        for (i in 0 until outLen) {
            val word = h[i / 8]
            out[i] = (word ushr (56 - 8 * (i % 8))).toByte()
        }
        return out
    }

    private fun rotr(v: Long, n: Int): Long = (v ushr n) or (v shl (64 - n))

    private val IV_512 = longArrayOf(
        0x6A09E667F3BCC908uL.toLong(), 0xBB67AE8584CAA73BuL.toLong(),
        0x3C6EF372FE94F82BuL.toLong(), 0xA54FF53A5F1D36F1uL.toLong(),
        0x510E527FADE682D1uL.toLong(), 0x9B05688C2B3E6C1FuL.toLong(),
        0x1F83D9ABFB41BD6BuL.toLong(), 0x5BE0CD19137E2179uL.toLong(),
    )

    private val IV_384 = longArrayOf(
        0xCBBB9D5DC1059ED8uL.toLong(), 0x629A292A367CD507uL.toLong(),
        0x9159015A3070DD17uL.toLong(), 0x152FECD8F70E5939uL.toLong(),
        0x67332667FFC00B31uL.toLong(), 0x8EB44A8768581511uL.toLong(),
        0xDB0C2E0D64F98FA7uL.toLong(), 0x47B5481DBEFA4FA4uL.toLong(),
    )

    private val K = longArrayOf(
        0x428A2F98D728AE22uL.toLong(), 0x7137449123EF65CDuL.toLong(),
        0xB5C0FBCFEC4D3B2FuL.toLong(), 0xE9B5DBA58189DBBCuL.toLong(),
        0x3956C25BF348B538uL.toLong(), 0x59F111F1B605D019uL.toLong(),
        0x923F82A4AF194F9BuL.toLong(), 0xAB1C5ED5DA6D8118uL.toLong(),
        0xD807AA98A3030242uL.toLong(), 0x12835B0145706FBEuL.toLong(),
        0x243185BE4EE4B28CuL.toLong(), 0x550C7DC3D5FFB4E2uL.toLong(),
        0x72BE5D74F27B896FuL.toLong(), 0x80DEB1FE3B1696B1uL.toLong(),
        0x9BDC06A725C71235uL.toLong(), 0xC19BF174CF692694uL.toLong(),
        0xE49B69C19EF14AD2uL.toLong(), 0xEFBE4786384F25E3uL.toLong(),
        0x0FC19DC68B8CD5B5uL.toLong(), 0x240CA1CC77AC9C65uL.toLong(),
        0x2DE92C6F592B0275uL.toLong(), 0x4A7484AA6EA6E483uL.toLong(),
        0x5CB0A9DCBD41FBD4uL.toLong(), 0x76F988DA831153B5uL.toLong(),
        0x983E5152EE66DFABuL.toLong(), 0xA831C66D2DB43210uL.toLong(),
        0xB00327C898FB213FuL.toLong(), 0xBF597FC7BEEF0EE4uL.toLong(),
        0xC6E00BF33DA88FC2uL.toLong(), 0xD5A79147930AA725uL.toLong(),
        0x06CA6351E003826FuL.toLong(), 0x142929670A0E6E70uL.toLong(),
        0x27B70A8546D22FFCuL.toLong(), 0x2E1B21385C26C926uL.toLong(),
        0x4D2C6DFC5AC42AEDuL.toLong(), 0x53380D139D95B3DFuL.toLong(),
        0x650A73548BAF63DEuL.toLong(), 0x766A0ABB3C77B2A8uL.toLong(),
        0x81C2C92E47EDAEE6uL.toLong(), 0x92722C851482353BuL.toLong(),
        0xA2BFE8A14CF10364uL.toLong(), 0xA81A664BBC423001uL.toLong(),
        0xC24B8B70D0F89791uL.toLong(), 0xC76C51A30654BE30uL.toLong(),
        0xD192E819D6EF5218uL.toLong(), 0xD69906245565A910uL.toLong(),
        0xF40E35855771202AuL.toLong(), 0x106AA07032BBD1B8uL.toLong(),
        0x19A4C116B8D2D0C8uL.toLong(), 0x1E376C085141AB53uL.toLong(),
        0x2748774CDF8EEB99uL.toLong(), 0x34B0BCB5E19B48A8uL.toLong(),
        0x391C0CB3C5C95A63uL.toLong(), 0x4ED8AA4AE3418ACBuL.toLong(),
        0x5B9CCA4F7763E373uL.toLong(), 0x682E6FF3D6B2B8A3uL.toLong(),
        0x748F82EE5DEFB2FCuL.toLong(), 0x78A5636F43172F60uL.toLong(),
        0x84C87814A1F0AB72uL.toLong(), 0x8CC702081A6439ECuL.toLong(),
        0x90BEFFFA23631E28uL.toLong(), 0xA4506CEBDE82BDE9uL.toLong(),
        0xBEF9A3F7B2C67915uL.toLong(), 0xC67178F2E372532BuL.toLong(),
        0xCA273ECEEA26619CuL.toLong(), 0xD186B8C721C0C207uL.toLong(),
        0xEADA7DD6CDE0EB1EuL.toLong(), 0xF57D4F7FEE6ED178uL.toLong(),
        0x06F067AA72176FBAuL.toLong(), 0x0A637DC5A2C898A6uL.toLong(),
        0x113F9804BEF90DAEuL.toLong(), 0x1B710B35131C471BuL.toLong(),
        0x28DB77F523047D84uL.toLong(), 0x32CAAB7B40C72493uL.toLong(),
        0x3C9EBE0A15C9BEBCuL.toLong(), 0x431D67C49C100D4CuL.toLong(),
        0x4CC5D4BECB3E42B6uL.toLong(), 0x597F299CFC657E2AuL.toLong(),
        0x5FCB6FAB3AD6FAECuL.toLong(), 0x6C44198C4A475817uL.toLong(),
    )
}
