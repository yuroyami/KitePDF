package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.Rectangle
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Digital signature SCAFFOLD (T-84): the prepare-then-sign flow that stages a
 * signature field, computes the exact `/ByteRange`, and embeds a
 * caller-supplied CMS blob. The library does NO cryptography: the DER
 * `SignedData` comes from the application (JVM apps can build one with
 * `java.security`; that is their code, not ours).
 *
 * ```
 * val editor = doc.edit()
 * val signer = PdfSigner(doc, editor)
 * signer.prepareSignature("Signature1")
 * val target = signer.saveForSigning()
 * val cms = myPkcs7Detached(target.bytes, target.byteRange) // app-side crypto
 * val signed = PdfSigner.embedSignature(target.bytes, target.byteRange, cms)
 * ```
 */
public class PdfSigner(
    private val doc: PdfDocument,
    private val editor: PdfEditor,
    /** Reserved size of the `/Contents` placeholder in bytes (DER CMS must fit). */
    private val placeholderSize: Int = 16 * 1024,
) {

    /** The signed bytes plus the exact `/ByteRange` the CMS must cover. */
    public class SigningTarget(public val bytes: ByteArray, public val byteRange: IntArray)

    private var prepared = false

    /**
     * Stage the signature machinery: a `/Sig` value dict with the `/Contents`
     * placeholder and a fixed-width `/ByteRange` placeholder, a merged
     * field+widget on page [pageIndex] (invisible when [rect] is null), and
     * the AcroForm wiring (`/SigFlags 3`).
     */
    public fun prepareSignature(fieldName: String, rect: Rectangle? = null, pageIndex: Int = 0) {
        check(!prepared) { "prepareSignature was already called" }
        require(!doc.isEncrypted) { "Signing encrypted documents is not supported." }
        val page = doc.pages.getOrNull(pageIndex)
            ?: throw IllegalArgumentException("No page $pageIndex")
        val pageRef = page.reference
            ?: throw IllegalArgumentException("Page $pageIndex has no indirect reference")

        val sigRef = editor.addObject(
            PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Sig"),
                    "Filter" to PdfName("Adobe.PPKLite"),
                    "SubFilter" to PdfName("adbe.pkcs7.detached"),
                    // Serialized as a hex string of 2*placeholderSize zeros.
                    "Contents" to PdfString(ByteArray(placeholderSize)),
                    // Fixed-width placeholder, patched in place by saveForSigning.
                    "ByteRange" to PdfArray(listOf(PdfInt(0), PdfInt(RESERVED), PdfInt(RESERVED), PdfInt(RESERVED))),
                ),
            ),
        )

        val r = rect ?: Rectangle(0.0, 0.0, 0.0, 0.0)
        val fieldRef = editor.addObject(
            PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Annot"),
                    "Subtype" to PdfName("Widget"),
                    "FT" to PdfName("Sig"),
                    "T" to PdfString(PdfText.encodeTextString(fieldName)),
                    "V" to sigRef,
                    "Rect" to PdfArray(listOf(PdfReal(r.left), PdfReal(r.bottom), PdfReal(r.right), PdfReal(r.top))),
                    "F" to PdfInt(4), // print
                    "P" to pageRef,
                ),
            ),
        )

        // Page /Annots gains the widget.
        val pageDict = LinkedHashMap(page.dictionary.map)
        val annots = (pageDict["Annots"] as? PdfArray)?.items ?: emptyList()
        pageDict["Annots"] = PdfArray(annots + fieldRef)
        editor.updateObject(pageRef, PdfDictionary(pageDict))

        // AcroForm /Fields gains the field; /SigFlags marks the doc signed.
        val rootRef = (doc.trailer["Root"] as? PdfReference)
            ?: throw IllegalStateException("Trailer /Root is not an indirect reference")
        val catalog = LinkedHashMap(doc.catalog.map)
        val acroRef = catalog["AcroForm"] as? PdfReference
        if (acroRef != null) {
            val acro = (doc.resolve(acroRef) as? PdfDictionary)?.map?.let { LinkedHashMap(it) } ?: LinkedHashMap()
            val fields = (acro["Fields"] as? PdfArray)?.items ?: emptyList()
            acro["Fields"] = PdfArray(fields + fieldRef)
            acro["SigFlags"] = PdfInt(3)
            editor.updateObject(acroRef, PdfDictionary(acro))
        } else {
            val newAcro = editor.addObject(
                PdfDictionary(
                    linkedMapOf(
                        "Fields" to PdfArray(listOf<PdfObject>(fieldRef)),
                        "SigFlags" to PdfInt(3),
                    ),
                ),
            )
            catalog["AcroForm"] = newAcro
            editor.updateObject(rootRef, PdfDictionary(catalog))
        }
        prepared = true
    }

    /**
     * Serialize incrementally, locate the `/Contents` placeholder, patch the
     * `/ByteRange` in place (space-padded to the reserved width so no offset
     * moves), and return the bytes plus the range: `[0, start]` and
     * `[end, size-end]` cover the whole file except the hex string itself.
     */
    public fun saveForSigning(): SigningTarget {
        check(prepared) { "call prepareSignature first" }
        val bytes = editor.saveIncremental()

        // Find the placeholder: a hex string of exactly 2*placeholderSize
        // ASCII zeros. Verify the closing delimiter so a shorter zero run
        // elsewhere cannot false-match.
        val probe = ("<" + "0".repeat(64)).encodeToByteArray()
        var lt = -1
        var from = 0
        while (true) {
            val cand = indexOf(bytes, probe, from)
            check(cand >= 0) { "signature placeholder not found" }
            val end = cand + 1 + 2 * placeholderSize
            if (end < bytes.size && bytes[end] == '>'.code.toByte()) { lt = cand; break }
            from = cand + 1
        }
        val contentsEnd = lt + 1 + 2 * placeholderSize + 1 // past '>'
        val byteRange = intArrayOf(0, lt, contentsEnd, bytes.size - contentsEnd)

        // Patch the fixed-width /ByteRange (the reserved literal has the same
        // character count as any real value we write, padded with spaces).
        val placeholder = "[0 $RESERVED $RESERVED $RESERVED]".encodeToByteArray()
        val at = indexOf(bytes, placeholder)
        check(at >= 0) { "ByteRange placeholder not found" }
        val real = "[0 ${byteRange[1]} ${byteRange[2]} ${byteRange[3]}]"
        check(real.length <= placeholder.size) { "ByteRange overflows its placeholder" }
        val patched = real.padEnd(placeholder.size, ' ').encodeToByteArray()
        patched.copyInto(bytes, at)
        return SigningTarget(bytes, byteRange)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    public companion object {
        // 10 digits: wide enough for any offset the writer can produce.
        private const val RESERVED = 9999999999L

        /**
         * Write the DER [cms] into the `/Contents` hex string of [bytes] (as
         * produced by [saveForSigning] with [byteRange]). The blob must fit
         * the reserved placeholder; the tail keeps its zero padding, which
         * PKCS#7 validators ignore by convention.
         */
        public fun embedSignature(bytes: ByteArray, byteRange: IntArray, cms: ByteArray): ByteArray {
            val start = byteRange[1] + 1 // past '<'
            val capacity = (byteRange[2] - 1) - start
            require(cms.size * 2 <= capacity) {
                "CMS blob (${cms.size} bytes) exceeds the ${capacity / 2}-byte placeholder"
            }
            val out = bytes.copyOf()
            val hex = "0123456789ABCDEF"
            for (i in cms.indices) {
                val v = cms[i].toInt() and 0xFF
                out[start + 2 * i] = hex[v shr 4].code.toByte()
                out[start + 2 * i + 1] = hex[v and 0xF].code.toByte()
            }
            return out
        }
    }
}
