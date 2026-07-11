package io.github.yuroyami.kitepdf.crypto

import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.PdfString
import kotlin.random.Random

/**
 * The write-side mirror of [Decryptor]: walks a PdfObject tree that is about
 * to be serialized as an indirect object and encrypts strings + streams with
 * a [StandardSecurityHandler]'s parameters. A fresh random IV is drawn per
 * string/stream from the injected [random] source, so tests can seed it for
 * reproducible output.
 *
 * /XRef streams and (when the handler says so) /Metadata streams are left in
 * clear text, matching the read-side skips.
 */
internal class Encryptor(
    private val handler: StandardSecurityHandler,
    private val random: Random,
) {

    init {
        check(handler.supportsWrite) { "security handler does not support writing" }
    }

    fun encryptIndirect(objNum: Long, genNum: Int, value: PdfObject): PdfObject = when (value) {
        is PdfStream -> encryptStream(objNum, genNum, value)
        is PdfString -> PdfString(handler.encryptString(objNum, genNum, value.bytes, iv()))
        is PdfDictionary -> encryptDict(objNum, genNum, value)
        is PdfArray -> encryptArray(objNum, genNum, value)
        else -> value
    }

    private fun iv(): ByteArray = random.nextBytes(16)

    private fun encryptStream(objNum: Long, genNum: Int, stream: PdfStream): PdfStream {
        val type = stream.dict.getName("Type")
        if (type == "XRef") return stream
        if (type == "Metadata" && !handler.encryptMetadata) return stream
        val dict = encryptDict(objNum, genNum, stream.dict)
        val bytes = handler.encryptStream(objNum, genNum, stream.rawBytes, iv())
        // /Length is recomputed from rawBytes by PdfObjectWriter, so the dict
        // needs no fixup for the AES IV + padding growth.
        return PdfStream(dict, bytes)
    }

    private fun encryptDict(objNum: Long, genNum: Int, dict: PdfDictionary): PdfDictionary {
        var changed = false
        val out = LinkedHashMap<String, PdfObject>(dict.map.size)
        for ((k, v) in dict.map) {
            val replaced = encryptValue(objNum, genNum, v)
            if (replaced !== v) changed = true
            out[k] = replaced
        }
        return if (changed) PdfDictionary(out) else dict
    }

    private fun encryptArray(objNum: Long, genNum: Int, array: PdfArray): PdfArray {
        var changed = false
        val out = ArrayList<PdfObject>(array.size)
        for (v in array) {
            val replaced = encryptValue(objNum, genNum, v)
            if (replaced !== v) changed = true
            out.add(replaced)
        }
        return if (changed) PdfArray(out) else array
    }

    private fun encryptValue(objNum: Long, genNum: Int, value: PdfObject): PdfObject = when (value) {
        is PdfString -> PdfString(handler.encryptString(objNum, genNum, value.bytes, iv()))
        is PdfDictionary -> encryptDict(objNum, genNum, value)
        is PdfArray -> encryptArray(objNum, genNum, value)
        is PdfReference -> value // encrypted under its own indirect path
        else -> value
    }

    companion object {

        /**
         * Create V5/AES-256 R6 encryption material (ISO 32000-2 Algorithms 8,
         * 9, 10): a random 32-byte file key wrapped for both passwords, and
         * the complete /Encrypt dictionary. Passwords are UTF-8, truncated to
         * 127 bytes (matching the verify side).
         */
        fun createV5(
            userPassword: ByteArray,
            ownerPassword: ByteArray,
            permissions: Int,
            encryptMetadata: Boolean,
            random: Random,
        ): PdfDictionary {
            val userPw = userPassword.copyOf(minOf(127, userPassword.size))
            val ownerPw = ownerPassword.copyOf(minOf(127, ownerPassword.size))
            val fileKey = random.nextBytes(32)
            val zeroIv = ByteArray(16)

            // Algorithm 8: /U and /UE.
            val uValidationSalt = random.nextBytes(8)
            val uKeySalt = random.nextBytes(8)
            val u = StandardSecurityHandler.hash2B(userPw, uValidationSalt, byteArrayOf(), r6 = true) +
                uValidationSalt + uKeySalt
            val uIntermediate = StandardSecurityHandler.hash2B(userPw, uKeySalt, byteArrayOf(), r6 = true)
            val ue = Aes.encryptCbcNoPadding(uIntermediate, zeroIv, fileKey)

            // Algorithm 9: /O and /OE (both hashed over the 48-byte /U).
            val oValidationSalt = random.nextBytes(8)
            val oKeySalt = random.nextBytes(8)
            val o = StandardSecurityHandler.hash2B(ownerPw, oValidationSalt, u, r6 = true) +
                oValidationSalt + oKeySalt
            val oIntermediate = StandardSecurityHandler.hash2B(ownerPw, oKeySalt, u, r6 = true)
            val oe = Aes.encryptCbcNoPadding(oIntermediate, zeroIv, fileKey)

            // Algorithm 10: /Perms = AES-256-ECB(fileKey) over the extended
            // permissions block.
            val block = ByteArray(16)
            block[0] = permissions.toByte()
            block[1] = (permissions ushr 8).toByte()
            block[2] = (permissions ushr 16).toByte()
            block[3] = (permissions ushr 24).toByte()
            for (i in 4..7) block[i] = 0xFF.toByte()
            block[8] = (if (encryptMetadata) 'T' else 'F').code.toByte()
            block[9] = 'a'.code.toByte()
            block[10] = 'd'.code.toByte()
            block[11] = 'b'.code.toByte()
            random.nextBytes(4).copyInto(block, 12)
            val perms = Aes.encryptEcb(fileKey, block)

            val cf = PdfDictionary(
                linkedMapOf(
                    "StdCF" to PdfDictionary(
                        linkedMapOf(
                            "CFM" to PdfName("AESV3"),
                            "AuthEvent" to PdfName("DocOpen"),
                            "Length" to PdfInt(32),
                        ),
                    ),
                ),
            )
            val dict = LinkedHashMap<String, PdfObject>()
            dict["Filter"] = PdfName("Standard")
            dict["V"] = PdfInt(5)
            dict["R"] = PdfInt(6)
            dict["Length"] = PdfInt(256)
            dict["CF"] = cf
            dict["StmF"] = PdfName("StdCF")
            dict["StrF"] = PdfName("StdCF")
            dict["P"] = PdfInt(permissions.toLong())
            dict["O"] = PdfString(o)
            dict["U"] = PdfString(u)
            dict["OE"] = PdfString(oe)
            dict["UE"] = PdfString(ue)
            dict["Perms"] = PdfString(perms)
            if (!encryptMetadata) dict["EncryptMetadata"] = PdfBoolean(false)
            return PdfDictionary(dict)
        }
    }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    copyInto(out, 0)
    other.copyInto(out, size)
    return out
}
