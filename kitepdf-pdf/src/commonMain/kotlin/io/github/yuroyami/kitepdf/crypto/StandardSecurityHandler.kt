package io.github.yuroyami.kitepdf.crypto

import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName

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
public class StandardSecurityHandler(
    encryptDict: PdfDictionary,
    fileIdFirst: ByteArray,
    userPassword: ByteArray = byteArrayOf(),
    ownerPassword: ByteArray = byteArrayOf(),
) {

    private val v: Int = encryptDict.getInt("V")?.toInt() ?: 1
    /** Revision number — exposed so the public permissions API can pick the right bit semantics. */
    public val r: Int = encryptDict.getInt("R")?.toInt() ?: 2
    private val keyLengthBits: Int = encryptDict.getInt("Length")?.toInt()
        ?: when (v) { 1 -> 40; 4 -> 128; 5 -> 256; else -> 40 }
    private val o: ByteArray = (encryptDict["O"] as? io.github.yuroyami.kitepdf.core.parser.PdfString)?.bytes
        ?: throw PdfFormatException("/Encrypt missing /O")
    private val u: ByteArray = (encryptDict["U"] as? io.github.yuroyami.kitepdf.core.parser.PdfString)?.bytes
        ?: throw PdfFormatException("/Encrypt missing /U")
    /** Raw `/P` bit-flags. Negative because the high bit is set on permissive PDFs. */
    public val p: Int = encryptDict.getInt("P")?.toInt() ?: -1
    /**
     * Whether the /Metadata stream is encrypted. When false (V4+ with
     * /EncryptMetadata false), the metadata stream is left as clear text and
     * [Decryptor] must skip it. Exposed for that reason.
     */
    public val encryptMetadata: Boolean = (encryptDict["EncryptMetadata"]
        as? io.github.yuroyami.kitepdf.core.parser.PdfBoolean)?.value ?: true

    /** V5/V6 only — 32-byte SHA-256 derivation salts. */
    private val ue: ByteArray = (encryptDict["UE"] as? io.github.yuroyami.kitepdf.core.parser.PdfString)?.bytes ?: byteArrayOf()
    private val oe: ByteArray = (encryptDict["OE"] as? io.github.yuroyami.kitepdf.core.parser.PdfString)?.bytes ?: byteArrayOf()

    /**
     * For V4: per-filter algorithm. /CFM /V2 = RC4, /CFM /AESV2 = AES-128,
     * /CFM none or /Identity = NONE (passthrough). Streams use /StmF, strings
     * use /StrF, and the two can differ — hence two separate fields.
     */
    private val stmAlgorithm: V4Algo = detectV4Algorithm(encryptDict, "StmF")
    private val strAlgorithm: V4Algo = detectV4Algorithm(encryptDict, "StrF")

    /** File encryption key — null if authentication failed. */
    private val fileKey: ByteArray? = run {
        // V5/V6 first because their /U has a distinct structure.
        if (v >= 5) {
            deriveV5Key(userPassword, ownerPassword)
        } else {
            // Try user password, then owner password, then empty (in that order).
            tryUserPassword(userPassword, fileIdFirst)
                ?: tryOwnerPassword(ownerPassword, fileIdFirst)
                ?: tryUserPassword(byteArrayOf(), fileIdFirst)
        }
    }

    public val isAuthenticated: Boolean get() = fileKey != null

    /* ─── Public decrypt API ─────────────────────────────────────────────── */

    /** Decrypt a stream's raw bytes (call BEFORE running the filter chain). */
    public fun decryptStream(objNum: Long, genNum: Int, ciphertext: ByteArray): ByteArray =
        decrypt(objNum, genNum, ciphertext, isString = false)

    /** Decrypt a literal-string's bytes (replaces PdfString.bytes in place). */
    public fun decryptString(objNum: Long, genNum: Int, ciphertext: ByteArray): ByteArray =
        decrypt(objNum, genNum, ciphertext, isString = true)

    private fun decrypt(objNum: Long, genNum: Int, ciphertext: ByteArray, isString: Boolean): ByteArray {
        val key = fileKey ?: return ciphertext
        if (v >= 5) {
            // V5/V6: file key is used directly to AES-256-CBC decrypt; no per-object derivation.
            return Aes.decryptCbc(key, ciphertext)
        }
        // V1/V2/V4: route strings through /StrF, streams through /StmF.
        val algorithm = if (isString) strAlgorithm else stmAlgorithm
        // /Identity (or /CFM none): data is not encrypted — return it unchanged.
        if (algorithm == V4Algo.NONE) return ciphertext
        val objKey = derivePerObjectKey(key, objNum, genNum, useAesV2 = algorithm == V4Algo.AESV2)
        return when (algorithm) {
            V4Algo.AESV2 -> Aes.decryptCbc(objKey, ciphertext)
            else -> Rc4.process(objKey, ciphertext)
        }
    }

    /* ─── Write-side encrypt API (T-83) ──────────────────────────────────── */

    /**
     * Whether [Encryptor] can produce new objects matching this handler's
     * parameters: the document must be authenticated and use AES (V4 /AESV2
     * or V5/V6). Legacy RC4 documents are read-only.
     */
    public val supportsWrite: Boolean
        get() = fileKey != null && (
            v >= 5 || (v == 4 && stmAlgorithm != V4Algo.RC4 && strAlgorithm != V4Algo.RC4)
            )

    /** Encrypt a stream's raw bytes with a caller-supplied 16-byte [iv]. */
    internal fun encryptStream(objNum: Long, genNum: Int, plaintext: ByteArray, iv: ByteArray): ByteArray =
        encrypt(objNum, genNum, plaintext, isString = false, iv = iv)

    /** Encrypt a literal string's bytes with a caller-supplied 16-byte [iv]. */
    internal fun encryptString(objNum: Long, genNum: Int, plaintext: ByteArray, iv: ByteArray): ByteArray =
        encrypt(objNum, genNum, plaintext, isString = true, iv = iv)

    private fun encrypt(objNum: Long, genNum: Int, plaintext: ByteArray, isString: Boolean, iv: ByteArray): ByteArray {
        val key = fileKey ?: throw IllegalStateException("cannot encrypt: document not authenticated")
        if (v >= 5) return Aes.encryptCbc(key, iv, plaintext)
        val algorithm = if (isString) strAlgorithm else stmAlgorithm
        return when (algorithm) {
            V4Algo.NONE -> plaintext
            V4Algo.AESV2 -> {
                val objKey = derivePerObjectKey(key, objNum, genNum, useAesV2 = true)
                Aes.encryptCbc(objKey, iv, plaintext)
            }
            V4Algo.RC4 -> throw IllegalStateException("RC4 write support is intentionally absent")
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

    /**
     * Algorithm 7 (ISO 32000-1 §7.6.4.4.8): authenticate an owner password.
     * Compute the RC4 key from the padded owner password, decrypt /O to recover
     * the user password padding, then feed that back through the user-password
     * key computation and validate against /U.
     */
    private fun tryOwnerPassword(ownerPassword: ByteArray, fileId: ByteArray): ByteArray? {
        // (a) RC4 key = MD5(pad(ownerPw)); for R>=3, 50 extra MD5 rounds; take n bytes.
        val padded = padPassword(ownerPassword)
        var digest = Md5.hash(padded)
        val n = keyLengthBits / 8
        if (r >= 3) {
            repeat(50) { digest = Md5.hash(digest.copyOf(n)) }
        }
        val rc4Key = digest.copyOf(n)

        // (b) Decrypt /O to recover the padded user password.
        val recovered: ByteArray = if (r == 2) {
            Rc4.process(rc4Key, o)
        } else {
            var data = o
            for (round in 19 downTo 0) {
                val rotatedKey = ByteArray(rc4Key.size) { (rc4Key[it].toInt() xor round).toByte() }
                data = Rc4.process(rotatedKey, data)
            }
            data
        }

        // (c) Feed the recovered user password bytes into the user-password path.
        return tryUserPassword(recovered, fileId)
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

        for (raw in listOf(ownerPassword, userPassword, byteArrayOf())) {
            // Algorithm 2.A: truncate the UTF-8 password bytes to 127 bytes.
            // NOTE: SASLprep (RFC 4013) normalization is not yet applied here;
            // only the length truncation step is implemented.
            val pw = if (raw.size > 127) raw.copyOf(127) else raw

            // Try as user password.
            val userValidationSalt = u.copyOfRange(32, 40)
            val userKeySalt = u.copyOfRange(40, 48)
            val userValidate = hash2B(pw, userValidationSalt, udata = byteArrayOf())
            if (userValidate.contentEquals(u.copyOfRange(0, 32))) {
                if (ue.size >= 32) {
                    val intermediate = hash2B(pw, userKeySalt, udata = byteArrayOf())
                    return Aes.decryptCbc(intermediate, byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0) + ue, removePadding = false)
                }
            }
            // Try as owner password.
            val ownerValidationSalt = o.copyOfRange(32, 40)
            val ownerKeySalt = o.copyOfRange(40, 48)
            val u48 = u.copyOfRange(0, 48)
            val ownerValidate = hash2B(pw, ownerValidationSalt, udata = u48)
            if (ownerValidate.contentEquals(o.copyOfRange(0, 32))) {
                if (oe.size >= 32) {
                    val intermediate = hash2B(pw, ownerKeySalt, udata = u48)
                    return Aes.decryptCbc(intermediate, byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0) + oe, removePadding = false)
                }
            }
        }
        return null
    }

    private fun hash2B(password: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray =
        hash2B(password, salt, udata, r6 = r == 6)

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun padPassword(pwd: ByteArray): ByteArray {
        val out = ByteArray(32)
        val take = minOf(32, pwd.size)
        pwd.copyInto(out, 0, 0, take)
        PAD.copyInto(out, take, 0, 32 - take)
        return out
    }

    /**
     * Resolve the crypt-filter algorithm for a given filter-name key ("StmF"
     * for streams, "StrF" for strings). V1/V2 always use RC4; V4 looks the
     * named filter up in /CF and reads its /CFM. /Identity or /CFM none means
     * passthrough (NONE).
     */
    private fun detectV4Algorithm(dict: PdfDictionary, filterKey: String): V4Algo {
        if (v != 4) return if (v == 1 || v == 2) V4Algo.RC4 else V4Algo.NONE
        // V4 specifies per-filter algorithms in /CF dict. The filter used is
        // named by /StmF (streams) or /StrF (strings). /CF /<name>/CFM = /V2
        // (RC4), /AESV2, or None.
        val filterName = (dict[filterKey] as? PdfName)?.value ?: return V4Algo.RC4
        if (filterName == "Identity") return V4Algo.NONE
        val cf = dict.getDict("CF") ?: return V4Algo.RC4
        val filter = cf.map[filterName] as? PdfDictionary ?: return V4Algo.RC4
        return when (filter.getName("CFM")) {
            "AESV2" -> V4Algo.AESV2
            "V2" -> V4Algo.RC4
            "None" -> V4Algo.NONE
            else -> V4Algo.RC4
        }
    }

    private enum class V4Algo { RC4, AESV2, NONE }

    public companion object {
        /**
         * R5: plain SHA-256(password ++ salt [++ udata]).
         * R6 (PDF 2.0): Algorithm 2.B iterated hardening on top of that initial
         * hash. [udata] is the 48-byte /U value when the password being hashed
         * is the owner password, empty for a user password. Shared with the
         * write side ([Encryptor] creates V5 R6 material with the same math).
         */
        internal fun hash2B(password: ByteArray, salt: ByteArray, udata: ByteArray, r6: Boolean): ByteArray {
            var k = Sha256.hash(password + salt + udata)
            if (!r6) return k  // R5 (and any non-R6 V5) stops at the single SHA-256.

            // Algorithm 2.B (ISO 32000-2 §7.6.4.3.4). `round` counts rounds already
            // completed; the loop runs at least 64 rounds and keeps going until the
            // last byte of E is <= round - 32.
            var round = 0
            var lastE = 0
            while (round < 64 || (lastE and 0xFF) > round - 32) {
                // K1 = (password ++ K ++ udata) repeated 64 times.
                val block = password + k + udata
                val k1 = ByteArray(block.size * 64)
                for (i in 0 until 64) block.copyInto(k1, i * block.size)

                // E = AES-128-CBC(no-pad) with key=K[0:16], iv=K[16:32].
                val e = Aes.encryptCbcNoPadding(k.copyOfRange(0, 16), k.copyOfRange(16, 32), k1)

                // modulus = (sum of first 16 bytes of E) mod 3.
                var sum = 0
                for (i in 0 until 16) sum += e[i].toInt() and 0xFF
                k = when (sum % 3) {
                    0 -> Sha256.hash(e)
                    1 -> Sha512.hash384(e)
                    else -> Sha512.hash(e)
                }

                lastE = e[e.size - 1].toInt()
                round++
            }
            return k.copyOf(32)
        }

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
