package io.github.yuroyami.kitepdf.crypto

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Walks a freshly-parsed PdfObject tree and decrypts strings + streams in place
 * using a [StandardSecurityHandler]. The object number / generation of the
 * enclosing indirect object is the per-object key seed.
 *
 * /Encrypt and /XRef objects are skipped. Those are part of the security
 * machinery itself and must not be re-decrypted.
 */
public object Decryptor {

    public fun decryptIndirect(
        objNum: Long,
        genNum: Int,
        value: PdfObject,
        handler: StandardSecurityHandler,
    ): PdfObject = decryptIndirect(objNum, genNum, value, handler, streamsOnDemand = false)

    /**
     * [decryptIndirect], where [streamsOnDemand] keeps a stream that reads its body when asked
     * as one: it decrypts the body each time it reads it, so it holds no copy (#906).
     */
    internal fun decryptIndirect(
        objNum: Long,
        genNum: Int,
        value: PdfObject,
        handler: StandardSecurityHandler,
        streamsOnDemand: Boolean,
    ): PdfObject = when (value) {
        is PdfStream -> decryptStream(objNum, genNum, value, handler, streamsOnDemand)
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
        streamsOnDemand: Boolean,
    ): PdfStream {
        // Streams marked as /XRef or /Metadata (when EncryptMetadata=false) are not encrypted.
        val type = stream.dict.getName("Type")
        if (type == "XRef") return stream
        if (type == "Metadata" && !handler.encryptMetadata) return stream
        val decryptedDict = decryptDict(objNum, genNum, stream.dict, handler)
        // ISO 32000-1, 7.4.10 and 7.6.5: a stream whose filters start with /Crypt is decrypted by the
        // crypt filter that its /DecodeParms /Name names, /Identity by default, not by /StmF. An
        // /Identity stream is stored in clear, and decrypting it with /StmF scrambled it (#817).
        val identity = cryptFilterName(stream.dict) == "Identity"
        if (streamsOnDemand) {
            return if (identity) PdfStream(decryptedDict) { stream.rawBytes }
            else PdfStream(decryptedDict) { handler.decryptStream(objNum, genNum, stream.rawBytes) }
        }
        if (identity) return PdfStream(decryptedDict, stream.rawBytes)
        val decryptedBytes = handler.decryptStream(objNum, genNum, stream.rawBytes)
        return PdfStream(decryptedDict, decryptedBytes)
    }

    /** The crypt filter that a stream names with a leading /Crypt filter, or null when it names none. */
    private fun cryptFilterName(dict: PdfDictionary): String? {
        val first = when (val f = dict["Filter"]) {
            is PdfName -> f.value
            is PdfArray -> (f.getOrNull(0) as? PdfName)?.value
            else -> null
        }
        if (first != "Crypt") return null
        val parms = when (val p = dict["DecodeParms"]) {
            is PdfDictionary -> p
            is PdfArray -> p.getOrNull(0) as? PdfDictionary
            else -> null
        }
        return parms?.getName("Name") ?: "Identity"
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
