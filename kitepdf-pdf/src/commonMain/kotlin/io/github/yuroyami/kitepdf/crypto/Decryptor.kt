package io.github.yuroyami.kitepdf.crypto

import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * Walks a freshly-parsed PdfObject tree and decrypts strings + streams in place
 * using a [StandardSecurityHandler]. The object number / generation of the
 * enclosing indirect object is the per-object key seed.
 *
 * /Encrypt and /XRef objects are skipped — those are part of the security
 * machinery itself and must not be re-decrypted.
 */
public object Decryptor {

    public fun decryptIndirect(
        objNum: Long,
        genNum: Int,
        value: PdfObject,
        handler: StandardSecurityHandler,
    ): PdfObject = when (value) {
        is PdfStream -> decryptStream(objNum, genNum, value, handler)
        is PdfString -> PdfString(handler.decryptString(objNum, genNum, value.bytes))
        is PdfDictionary -> decryptDict(objNum, genNum, value, handler)
        is PdfArray -> decryptArray(objNum, genNum, value, handler)
        else -> value
    }

    private fun decryptStream(
        objNum: Long,
        genNum: Int,
        stream: PdfStream,
        handler: StandardSecurityHandler,
    ): PdfStream {
        // Streams marked as /XRef or /Metadata (when EncryptMetadata=false) are not encrypted.
        val type = stream.dict.getName("Type")
        if (type == "XRef") return stream
        if (type == "Metadata" && !handler.encryptMetadata) return stream
        val decryptedDict = decryptDict(objNum, genNum, stream.dict, handler)
        val decryptedBytes = handler.decryptStream(objNum, genNum, stream.rawBytes)
        return PdfStream(decryptedDict, decryptedBytes)
    }

    private fun decryptDict(
        objNum: Long,
        genNum: Int,
        dict: PdfDictionary,
        handler: StandardSecurityHandler,
    ): PdfDictionary {
        var changed = false
        val out = LinkedHashMap<String, PdfObject>(dict.map.size)
        for ((k, v) in dict.map) {
            val replaced = decryptValue(objNum, genNum, v, handler)
            if (replaced !== v) changed = true
            out[k] = replaced
        }
        return if (changed) PdfDictionary(out) else dict
    }

    private fun decryptArray(
        objNum: Long,
        genNum: Int,
        array: PdfArray,
        handler: StandardSecurityHandler,
    ): PdfArray {
        var changed = false
        val out = ArrayList<PdfObject>(array.size)
        for (v in array) {
            val replaced = decryptValue(objNum, genNum, v, handler)
            if (replaced !== v) changed = true
            out.add(replaced)
        }
        return if (changed) PdfArray(out) else array
    }

    private fun decryptValue(
        objNum: Long,
        genNum: Int,
        value: PdfObject,
        handler: StandardSecurityHandler,
    ): PdfObject = when (value) {
        is PdfString -> PdfString(handler.decryptString(objNum, genNum, value.bytes))
        is PdfDictionary -> decryptDict(objNum, genNum, value, handler)
        is PdfArray -> decryptArray(objNum, genNum, value, handler)
        is PdfReference -> value   // resolved on its own and decrypted by its own indirect path
        else -> value
    }
}
