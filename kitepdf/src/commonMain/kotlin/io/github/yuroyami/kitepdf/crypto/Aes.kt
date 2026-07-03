package io.github.yuroyami.kitepdf.crypto

/**
 * Pure-Kotlin AES (FIPS 197), modes ECB-decrypt + CBC-decrypt for PDF V4/V5
 * Standard Security Handlers. Supports 128-bit and 256-bit keys.
 *
 * PDF V4 encrypts streams with AES-128-CBC using an IV prepended to the
 * ciphertext (first 16 bytes). PDF V5/V6 uses AES-256-CBC the same way.
 *
 * **Not for general use.** This is a textbook implementation — no
 * side-channel hardening, no timing-attack resistance, no AES-NI. It's here
 * because PDF needs decryption to *open* the document; the underlying threat
 * model is "rightful owner who has the password," not "attacker with timing."
 */
object Aes {

    /** Decrypt CBC-mode ciphertext. The first 16 bytes are the IV. */
    fun decryptCbc(key: ByteArray, ciphertext: ByteArray, removePadding: Boolean = true): ByteArray {
        require(key.size == 16 || key.size == 32) { "AES key must be 16 or 32 bytes" }
        // A malformed / empty encrypted string (e.g. the literal `()`) must not
        // throw and null out the whole containing object. Fewer than one block,
        // or a non-block-multiple length, cannot be validly decrypted — return
        // empty rather than raising IllegalArgumentException.
        if (ciphertext.size < 16 || ciphertext.size % 16 != 0) return ByteArray(0)
        val expanded = expandKey(key)
        val out = ByteArray(ciphertext.size - 16)
        var prev = ciphertext.copyOfRange(0, 16)   // IV
        var pos = 16
        while (pos < ciphertext.size) {
            val block = ciphertext.copyOfRange(pos, pos + 16)
            val decrypted = decryptBlock(block, expanded)
            for (i in 0..15) out[pos - 16 + i] = (decrypted[i].toInt() xor prev[i].toInt()).toByte()
            prev = block
            pos += 16
        }
        return if (removePadding) stripPkcs7(out) else out
    }

    /** Decrypt one ECB block (used for the key-string encryption in V4). */
    fun decryptEcb(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == 16) { "AES ECB block must be 16 bytes" }
        return decryptBlock(block, expandKey(key))
    }

    /** Encrypt CBC-mode plaintext, prepending the IV. PKCS#7 padding. */
    fun encryptCbc(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 16 || key.size == 32)
        require(iv.size == 16)
        val expanded = expandKey(key)
        val padded = pkcs7Pad(plaintext)
        val out = ByteArray(16 + padded.size)
        iv.copyInto(out, 0)
        var prev = iv
        var pos = 0
        while (pos < padded.size) {
            val block = ByteArray(16) { i -> (padded[pos + i].toInt() xor prev[i].toInt()).toByte() }
            val encrypted = encryptBlock(block, expanded)
            encrypted.copyInto(out, 16 + pos)
            prev = encrypted
            pos += 16
        }
        return out
    }

    /**
     * Encrypt CBC-mode plaintext with NO padding and NO IV-prepend. The output
     * is exactly the same length as the input, which must already be a multiple
     * of 16 bytes. Used by the R6 Algorithm 2.B hardening loop, which encrypts a
     * length-multiple-of-16 buffer and inspects the raw ciphertext.
     */
    fun encryptCbcNoPadding(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16 || key.size == 32) { "AES key must be 16 or 32 bytes" }
        require(iv.size == 16) { "AES IV must be 16 bytes" }
        require(data.size % 16 == 0) { "data must be a multiple of 16 bytes (got ${data.size})" }
        val expanded = expandKey(key)
        val out = ByteArray(data.size)
        var prev = iv
        var pos = 0
        while (pos < data.size) {
            val block = ByteArray(16) { i -> (data[pos + i].toInt() xor prev[i].toInt()).toByte() }
            val encrypted = encryptBlock(block, expanded)
            encrypted.copyInto(out, pos)
            prev = encrypted
            pos += 16
        }
        return out
    }

    /* ─── AES core ─────────────────────────────────────────────────────────── */

    private fun expandKey(key: ByteArray): Array<IntArray> {
        val keyWords = key.size / 4
        val rounds = when (key.size) { 16 -> 10; 32 -> 14; else -> error("bad key size") }
        val expanded = IntArray((rounds + 1) * 4)
        for (i in 0 until keyWords) {
            expanded[i] = ((key[i * 4].toInt() and 0xFF) shl 24) or
                ((key[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((key[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (key[i * 4 + 3].toInt() and 0xFF)
        }
        for (i in keyWords until expanded.size) {
            var temp = expanded[i - 1]
            if (i % keyWords == 0) {
                temp = subWord(rotWord(temp)) xor (RCON[i / keyWords] shl 24)
            } else if (keyWords > 6 && i % keyWords == 4) {
                temp = subWord(temp)
            }
            expanded[i] = expanded[i - keyWords] xor temp
        }
        // Group words into per-round 4-int subkeys.
        return Array(rounds + 1) { r -> intArrayOf(expanded[r * 4], expanded[r * 4 + 1], expanded[r * 4 + 2], expanded[r * 4 + 3]) }
    }

    private fun decryptBlock(input: ByteArray, roundKeys: Array<IntArray>): ByteArray {
        val state = IntArray(16) { input[it].toInt() and 0xFF }
        val rounds = roundKeys.size - 1
        addRoundKey(state, roundKeys[rounds])
        for (round in rounds - 1 downTo 1) {
            invShiftRows(state)
            invSubBytes(state)
            addRoundKey(state, roundKeys[round])
            invMixColumns(state)
        }
        invShiftRows(state)
        invSubBytes(state)
        addRoundKey(state, roundKeys[0])
        return ByteArray(16) { state[it].toByte() }
    }

    private fun encryptBlock(input: ByteArray, roundKeys: Array<IntArray>): ByteArray {
        val state = IntArray(16) { input[it].toInt() and 0xFF }
        val rounds = roundKeys.size - 1
        addRoundKey(state, roundKeys[0])
        for (round in 1 until rounds) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, roundKeys[round])
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, roundKeys[rounds])
        return ByteArray(16) { state[it].toByte() }
    }

    private fun addRoundKey(state: IntArray, key: IntArray) {
        for (col in 0..3) {
            val k = key[col]
            state[col * 4]     = state[col * 4]     xor (k ushr 24 and 0xFF)
            state[col * 4 + 1] = state[col * 4 + 1] xor (k ushr 16 and 0xFF)
            state[col * 4 + 2] = state[col * 4 + 2] xor (k ushr 8 and 0xFF)
            state[col * 4 + 3] = state[col * 4 + 3] xor (k and 0xFF)
        }
    }

    private fun subBytes(state: IntArray) {
        for (i in 0..15) state[i] = SBOX[state[i]] and 0xFF
    }

    private fun invSubBytes(state: IntArray) {
        for (i in 0..15) state[i] = INV_SBOX[state[i]] and 0xFF
    }

    /** Column-major state: shiftRows rotates byte at row r by r positions. */
    private fun shiftRows(state: IntArray) {
        val s = state.copyOf()
        for (c in 0..3) {
            for (r in 0..3) state[c * 4 + r] = s[((c + r) % 4) * 4 + r]
        }
    }

    private fun invShiftRows(state: IntArray) {
        val s = state.copyOf()
        for (c in 0..3) {
            for (r in 0..3) state[c * 4 + r] = s[((c - r + 4) % 4) * 4 + r]
        }
    }

    private fun mixColumns(state: IntArray) {
        for (c in 0..3) {
            val s0 = state[c * 4]; val s1 = state[c * 4 + 1]
            val s2 = state[c * 4 + 2]; val s3 = state[c * 4 + 3]
            state[c * 4]     = (MUL2[s0] xor MUL3[s1] xor s2 xor s3) and 0xFF
            state[c * 4 + 1] = (s0 xor MUL2[s1] xor MUL3[s2] xor s3) and 0xFF
            state[c * 4 + 2] = (s0 xor s1 xor MUL2[s2] xor MUL3[s3]) and 0xFF
            state[c * 4 + 3] = (MUL3[s0] xor s1 xor s2 xor MUL2[s3]) and 0xFF
        }
    }

    private fun invMixColumns(state: IntArray) {
        for (c in 0..3) {
            val s0 = state[c * 4]; val s1 = state[c * 4 + 1]
            val s2 = state[c * 4 + 2]; val s3 = state[c * 4 + 3]
            state[c * 4]     = (MUL14[s0] xor MUL11[s1] xor MUL13[s2] xor MUL9[s3]) and 0xFF
            state[c * 4 + 1] = (MUL9[s0] xor MUL14[s1] xor MUL11[s2] xor MUL13[s3]) and 0xFF
            state[c * 4 + 2] = (MUL13[s0] xor MUL9[s1] xor MUL14[s2] xor MUL11[s3]) and 0xFF
            state[c * 4 + 3] = (MUL11[s0] xor MUL13[s1] xor MUL9[s2] xor MUL14[s3]) and 0xFF
        }
    }

    // Precomputed GF(2^8) multiply tables (built once via gmul) — turn each
    // MixColumns/InvMixColumns multiply into a single array index instead of the
    // 8-iteration shift/xor loop. (AES decrypts every encrypted stream/string.)
    private val MUL2 = IntArray(256) { gmul(it, 2) }
    private val MUL3 = IntArray(256) { gmul(it, 3) }
    private val MUL9 = IntArray(256) { gmul(it, 0x09) }
    private val MUL11 = IntArray(256) { gmul(it, 0x0B) }
    private val MUL13 = IntArray(256) { gmul(it, 0x0D) }
    private val MUL14 = IntArray(256) { gmul(it, 0x0E) }

    /** Galois Field (2^8) multiplication — used to build the MUL* tables above. */
    private fun gmul(a: Int, b: Int): Int {
        var p = 0
        var x = a and 0xFF
        var y = b and 0xFF
        repeat(8) {
            if (y and 1 != 0) p = p xor x
            val highBit = x and 0x80
            x = (x shl 1) and 0xFF
            if (highBit != 0) x = x xor 0x1B
            y = y ushr 1
        }
        return p and 0xFF
    }

    private fun subWord(w: Int): Int =
        (SBOX[w ushr 24 and 0xFF] shl 24) or
        (SBOX[w ushr 16 and 0xFF] shl 16) or
        (SBOX[w ushr 8 and 0xFF] shl 8) or
        SBOX[w and 0xFF]

    private fun rotWord(w: Int): Int = (w shl 8) or (w ushr 24)

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val padLen = 16 - (data.size % 16)
        val out = ByteArray(data.size + padLen)
        data.copyInto(out, 0)
        for (i in data.size until out.size) out[i] = padLen.toByte()
        return out
    }

    private fun stripPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data.last().toInt() and 0xFF
        if (pad in 1..16 && data.size >= pad) {
            return data.copyOf(data.size - pad)
        }
        return data
    }

    private val RCON = intArrayOf(
        0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36,
        0x6C, 0xD8, 0xAB, 0x4D, 0x9A,
    )

    /** AES S-box (FIPS 197 §5.1.1). */
    private val SBOX = intArrayOf(
        0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5, 0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
        0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0, 0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
        0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC, 0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
        0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A, 0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
        0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0, 0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
        0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B, 0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
        0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85, 0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
        0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5, 0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
        0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17, 0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
        0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88, 0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
        0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C, 0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
        0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9, 0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
        0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6, 0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
        0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E, 0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
        0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94, 0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
        0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68, 0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
    )

    private val INV_SBOX = IntArray(256).also { for (i in 0..255) it[SBOX[i]] = i }
}
