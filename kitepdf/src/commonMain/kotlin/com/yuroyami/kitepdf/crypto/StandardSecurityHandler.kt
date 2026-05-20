package com.yuroyami.kitepdf.crypto

import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfName

/**
 * PDF Standard Security Handler (ISO 32000-1 §7.6.4).
 *
 * Implements key derivation and per-object decryption for security handlers
 * V1, V2, V4 (RC4 / AES-128), and V5 (AES-256). V6 (PDF 2.0) shares V5's
 * mechanism but with a slightly different password-validation algorithm; we
 * accept both since most readers in the wild treat them interchangeably.
 *
 * Construction tries the supplied password, then the empty password as
 * fallback. [isAuthenticated] reports whether one of them worked; if not,
 * decryption returns the original bytes (so the document can still be opened
 * read-only for its public metadata).
 */
class StandardSecurityHandler(
    encryptDict: PdfDictionary,
    fileIdFirst: ByteArray,
    userPassword: ByteArray = byteArrayOf(),
    ownerPassword: ByteArray = byteArrayOf(),
) {

    private val v: Int = encryptDict.getInt("V")?.toInt() ?: 1
    /** Revision number — exposed so the public permissions API can pick the right bit semantics. */
    val r: Int = encryptDict.getInt("R")?.toInt() ?: 2
    private val keyLengthBits: Int = encryptDict.getInt("Length")?.toInt()
        ?: when (v) { 1 -> 40; 4 -> 128; 5 -> 256; else -> 40 }
    private val o: ByteArray = (encryptDict["O"] as? com.yuroyami.kitepdf.parser.PdfString)?.bytes
        ?: throw PdfFormatException("/Encrypt missing /O")
    private val u: ByteArray = (encryptDict["U"] as? com.yuroyami.kitepdf.parser.PdfString)?.bytes
        ?: throw PdfFormatException("/Encrypt missing /U")
    /** Raw `/P` bit-flags. Negative because the high bit is set on permissive PDFs. */
    val p: Int = encryptDict.getInt("P")?.toInt() ?: -1
    private val encryptMetadata: Boolean = (encryptDict["EncryptMetadata"]
        as? com.yuroyami.kitepdf.parser.PdfBoolean)?.value ?: true

    /** V5/V6 only — 32-byte SHA-256 derivation salts. */
    private val ue: ByteArray = (encryptDict["UE"] as? com.yuroyami.kitepdf.parser.PdfString)?.bytes ?: byteArrayOf()
    private val oe: ByteArray = (encryptDict["OE"] as? com.yuroyami.kitepdf.parser.PdfString)?.bytes ?: byteArrayOf()

    /**
     * For V4: per-filter algorithm. /CFM /V2 = RC4, /CFM /AESV2 = AES-128.
     * Looked up via /CF /<name>.
     */
    private val v4Algorithm: V4Algo = detectV4Algorithm(encryptDict)

    /** File encryption key — null if authentication failed. */
    private val fileKey: ByteArray? = run {
        // V5/V6 first because their /U has a distinct structure.
        if (v >= 5) {
            deriveV5Key(userPassword, ownerPassword)
        } else {
            // Try user password first, then empty.
            tryUserPassword(userPassword, fileIdFirst)
                ?: tryUserPassword(byteArrayOf(), fileIdFirst)
        }
    }

    val isAuthenticated: Boolean get() = fileKey != null

    /* ─── Public decrypt API ─────────────────────────────────────────────── */

    /** Decrypt a stream's raw bytes (call BEFORE running the filter chain). */
    fun decryptStream(objNum: Long, genNum: Int, ciphertext: ByteArray): ByteArray =
        decrypt(objNum, genNum, ciphertext, isString = false)

    /** Decrypt a literal-string's bytes (replaces PdfString.bytes in place). */
    fun decryptString(objNum: Long, genNum: Int, ciphertext: ByteArray): ByteArray =
        decrypt(objNum, genNum, ciphertext, isString = true)

    private fun decrypt(objNum: Long, genNum: Int, ciphertext: ByteArray, isString: Boolean): ByteArray {
        val key = fileKey ?: return ciphertext
        if (v >= 5) {
            // V5/V6: file key is used directly to AES-256-CBC decrypt; no per-object derivation.
            return Aes.decryptCbc(key, ciphertext)
        }
        // V1/V2/V4: derive a per-object key.
        val objKey = derivePerObjectKey(key, objNum, genNum, useAesV2 = (v4Algorithm == V4Algo.AESV2) && isString.let { true })
        return when (v4Algorithm) {
            V4Algo.AESV2 -> Aes.decryptCbc(objKey, ciphertext)
            else -> Rc4.process(objKey, ciphertext)
        }
    }

    /* ─── V1/V2/V4 derivation (Algorithm 2, ISO 32000-1 §7.6.4.3.2) ──────── */

    private fun tryUserPassword(password: ByteArray, fileId: ByteArray): ByteArray? {
        val key = computeFileKey(password, fileId)
        // Validate: compute expected /U from this key and compare.
        val expected = computeUserPasswordEntry(key, fileId)
        val matched = when (r) {
            2 -> expected.contentEquals(u)
            // R≥3 stores 32 bytes; only first 16 are deterministic, rest are arbitrary.
            else -> expected.take(16).toByteArray().contentEquals(u.copyOf(16))
        }
        return if (matched) key else null
    }

    private fun computeFileKey(password: ByteArray, fileId: ByteArray): ByteArray {
        val padded = padPassword(password)
        val md5In = ByteArray(padded.size + o.size + 4 + fileId.size + (if (r >= 4 && !encryptMetadata) 4 else 0))
        var pos = 0
        padded.copyInto(md5In, pos); pos += padded.size
        o.copyInto(md5In, pos); pos += o.size
        // /P as 4-byte little-endian signed integer.
        md5In[pos] = p.toByte()
        md5In[pos + 1] = (p ushr 8).toByte()
        md5In[pos + 2] = (p ushr 16).toByte()
        md5In[pos + 3] = (p ushr 24).toByte()
        pos += 4
        fileId.copyInto(md5In, pos); pos += fileId.size
        if (r >= 4 && !encryptMetadata) {
            for (i in 0..3) md5In[pos + i] = 0xFF.toByte()
        }
        var digest = Md5.hash(md5In)
        val n = keyLengthBits / 8
        if (r >= 3) {
            repeat(50) {
                digest = Md5.hash(digest.copyOf(n))
            }
        }
        return digest.copyOf(n)
    }

    private fun computeUserPasswordEntry(key: ByteArray, fileId: ByteArray): ByteArray {
        return if (r == 2) {
            // Algorithm 4: RC4-encrypt the standard padding string with file key.
            Rc4.process(key, PAD)
        } else {
            // Algorithm 5: MD5(padding ++ fileId), then RC4 19 + 1 times.
            val md5In = ByteArray(PAD.size + fileId.size)
            PAD.copyInto(md5In, 0)
            fileId.copyInto(md5In, PAD.size)
            var data = Rc4.process(key, Md5.hash(md5In))
            for (round in 1..19) {
                val rotatedKey = ByteArray(key.size) { (key[it].toInt() xor round).toByte() }
                data = Rc4.process(rotatedKey, data)
            }
            // Pad to 32 bytes with arbitrary data — caller compares first 16 only.
            ByteArray(32).also { data.copyInto(it, 0, 0, minOf(16, data.size)) }
        }
    }

    private fun derivePerObjectKey(fileKey: ByteArray, objNum: Long, genNum: Int, useAesV2: Boolean): ByteArray {
        val extra = if (useAesV2) "sAlT".encodeToByteArray() else byteArrayOf()
        val input = ByteArray(fileKey.size + 5 + extra.size)
        fileKey.copyInto(input, 0)
        input[fileKey.size] = (objNum and 0xFF).toByte()
        input[fileKey.size + 1] = ((objNum ushr 8) and 0xFF).toByte()
        input[fileKey.size + 2] = ((objNum ushr 16) and 0xFF).toByte()
        input[fileKey.size + 3] = (genNum and 0xFF).toByte()
        input[fileKey.size + 4] = ((genNum ushr 8) and 0xFF).toByte()
        extra.copyInto(input, fileKey.size + 5)
        val digest = Md5.hash(input)
        val keyLen = minOf(fileKey.size + 5, 16)
        return digest.copyOf(keyLen)
    }

    /* ─── V5/V6 derivation (Algorithm 2.A) ─────────────────────────────── */

    private fun deriveV5Key(userPassword: ByteArray, ownerPassword: ByteArray): ByteArray? {
        // PDF 1.7 errata / PDF 2.0 §7.6.4.4.7.
        // The /U entry is 48 bytes: 32 validation salt + 8 user-validation salt + 8 user-key salt.
        // Try owner password first, then user password, then empty.
        if (u.size < 48 || o.size < 48) return null

        for (pw in listOf(ownerPassword, userPassword, byteArrayOf())) {
            // Try as user password.
            val userValidationSalt = u.copyOfRange(32, 40)
            val userKeySalt = u.copyOfRange(40, 48)
            val userValidate = Sha256.hash(pw + userValidationSalt)
            if (userValidate.contentEquals(u.copyOfRange(0, 32))) {
                if (ue.size >= 32) {
                    val intermediate = Sha256.hash(pw + userKeySalt)
                    return Aes.decryptCbc(intermediate, byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0) + ue, removePadding = false)
                }
            }
            // Try as owner password.
            val ownerValidationSalt = o.copyOfRange(32, 40)
            val ownerKeySalt = o.copyOfRange(40, 48)
            val ownerValidate = Sha256.hash(pw + ownerValidationSalt + u.copyOfRange(0, 48))
            if (ownerValidate.contentEquals(o.copyOfRange(0, 32))) {
                if (oe.size >= 32) {
                    val intermediate = Sha256.hash(pw + ownerKeySalt + u.copyOfRange(0, 48))
                    return Aes.decryptCbc(intermediate, byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0) + oe, removePadding = false)
                }
            }
        }
        return null
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun padPassword(pwd: ByteArray): ByteArray {
        val out = ByteArray(32)
        val take = minOf(32, pwd.size)
        pwd.copyInto(out, 0, 0, take)
        PAD.copyInto(out, take, 0, 32 - take)
        return out
    }

    private fun detectV4Algorithm(dict: PdfDictionary): V4Algo {
        if (v != 4) return if (v == 1 || v == 2) V4Algo.RC4 else V4Algo.NONE
        // V4 specifies per-filter algorithms in /CF dict. The default for streams
        // is named by /StmF. /CF /<StmF>/CFM = /V2 (RC4) or /AESV2.
        val stmF = (dict["StmF"] as? PdfName)?.value ?: return V4Algo.RC4
        if (stmF == "Identity") return V4Algo.NONE
        val cf = dict.getDict("CF") ?: return V4Algo.RC4
        val filter = cf.map[stmF] as? PdfDictionary ?: return V4Algo.RC4
        return when (filter.getName("CFM")) {
            "AESV2" -> V4Algo.AESV2
            "V2" -> V4Algo.RC4
            else -> V4Algo.RC4
        }
    }

    private enum class V4Algo { RC4, AESV2, NONE }

    companion object {
        /** 32-byte standard padding string from ISO 32000-1 §7.6.4.3. */
        private val PAD = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
            0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
            0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
        )
    }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    copyInto(out, 0)
    other.copyInto(out, size)
    return out
}
