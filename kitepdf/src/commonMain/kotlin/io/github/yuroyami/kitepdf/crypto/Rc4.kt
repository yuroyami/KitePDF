package io.github.yuroyami.kitepdf.crypto

/**
 * Pure-Kotlin RC4 stream cipher (RSA Data Security, Inc., 1987).
 *
 * Used by PDF Standard Security Handler V1 (40-bit) and V2 (40–128-bit). RC4
 * is symmetric: same routine for encrypt + decrypt. Don't use this anywhere
 * else — RC4 has well-known weaknesses; PDF kept it for back-compat.
 */
object Rc4 {

    /** Returns the [data] XOR-ed with the RC4 keystream derived from [key]. */
    fun process(key: ByteArray, data: ByteArray): ByteArray {
        val s = ksa(key)
        return prga(s, data)
    }

    /** Key-Scheduling Algorithm: build the 256-byte permutation table. */
    private fun ksa(key: ByteArray): IntArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        return s
    }

    /**
     * Pseudo-Random Generation Algorithm: XOR each plaintext byte with the
     * keystream. Mutates [s] in place — `ksa` already returns a fresh array
     * used nowhere else, so the previous defensive copy was dead weight.
     */
    private fun prga(s: IntArray, data: ByteArray): ByteArray {
        var i = 0; var j = 0
        val out = ByteArray(data.size)
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            val key = s[(s[i] + s[j]) and 0xFF]
            out[k] = (data[k].toInt() xor key).toByte()
        }
        return out
    }
}
