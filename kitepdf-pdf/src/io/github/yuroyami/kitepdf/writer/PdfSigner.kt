package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Digital signature SCAFFOLD: the prepare-then-sign flow that stages a
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

    /**
     * What a signature says about itself and how its widget looks (ISO 32000-1, 12.8.1, Table 252;
     * #922). [name], [reason], [location] and [contactInfo] are written as text strings, and
     * [signingTime] as `/M`, which stays optional: validators trust the signing time in the CMS.
     *
     * [appearance] draws the widget of a visible signature, in a box from (0, 0) to the width and
     * height it is given, as a form XObject in `/AP /N` (12.7.4.5). Null draws the default, as
     * Acrobat does: "Digitally signed by" the name, then the date, the reason and the location, in
     * Helvetica. An invisible signature, one without a rectangle, has no appearance.
     *
     * [certificationLevel] makes a certification signature (ISO 32000-1, 12.8.2.2, Tables 252 to
     * 254): 1 allows no change after it, 2 allows filling in forms and signing, and 3 also allows
     * annotations. Null makes an approval signature. A certification signature must be the first
     * one, so [prepareSignature] refuses one for a document that is already signed or certified
     * (#923).
     *
     * [subFilter] is the encoding of the CMS: `adbe.pkcs7.detached`, or `ETSI.CAdES.detached` for a
     * PAdES baseline signature (ISO 32000-2, 12.8.3.4; ETSI EN 319 142-1). The app makes the CMS
     * either way; for PAdES B-T it puts the signature time-stamp token in it as an unsigned
     * attribute (#924).
     */
    public class Options(
        public val name: String? = null,
        public val reason: String? = null,
        public val location: String? = null,
        public val contactInfo: String? = null,
        public val signingTime: io.github.yuroyami.kitepdf.PdfDate? = null,
        public val appearance: (ContentStreamBuilder.(width: Double, height: Double) -> Unit)? = null,
        public val certificationLevel: Int? = null,
        public val subFilter: String = "adbe.pkcs7.detached",
    ) {
        init {
            require(subFilter == "adbe.pkcs7.detached" || subFilter == "ETSI.CAdES.detached") {
                "The subfilter is adbe.pkcs7.detached or ETSI.CAdES.detached, not $subFilter"
            }
            require(certificationLevel == null || certificationLevel in 1..3) { "A certification level is 1, 2 or 3, not $certificationLevel" }
        }
    }

    /** The signed bytes plus the exact `/ByteRange` the CMS must cover. */
    public class SigningTarget(public val bytes: ByteArray, public val byteRange: IntArray)

    private var prepared = false

    /**
     * Stage the signature machinery: a `/Sig` value dict with the `/Contents`
     * placeholder and a fixed-width `/ByteRange` placeholder, a merged
     * field+widget on page [pageIndex] (invisible when [rect] is null), and
     * the AcroForm wiring (`/SigFlags 3`).
     */
    public fun prepareSignature(fieldName: String, rect: KiteRectangle? = null, pageIndex: Int = 0): Unit =
        prepareSignature(fieldName, rect, pageIndex, Options())

    /** [prepareSignature] with the entries and the appearance of [options] (#922). */
    public fun prepareSignature(fieldName: String, rect: KiteRectangle?, pageIndex: Int, options: Options): Unit =
        prepare(fieldName, rect, pageIndex, options, timestamp = false)

    /**
     * Stage a document timestamp: an invisible signature field whose value has `/Type
     * /DocTimeStamp` and `/SubFilter /ETSI.RFC3161` (ISO 32000-2, 12.8.5). Then [saveForSigning]
     * gives the bytes, the app has a timestamp authority stamp their digest, and [embedSignature]
     * takes the authority's time-stamp token as the CMS (#924).
     */
    public fun prepareDocumentTimestamp(fieldName: String = "DocTimeStamp", pageIndex: Int = 0): Unit =
        prepare(fieldName, null, pageIndex, Options(), timestamp = true)

    private fun prepare(fieldName: String, rect: KiteRectangle?, pageIndex: Int, options: Options, timestamp: Boolean) {
        check(!prepared) { "prepareSignature was already called" }
        require(!doc.isEncrypted) { "Signing encrypted documents is not supported." }
        val level = options.certificationLevel
        if (level != null) {
            require(doc.signatures.isEmpty()) { "A certification signature must be the first signature of the document." }
            require(doc.catalog.getDict("Perms", doc)?.get("DocMDP") == null) { "The document is already certified (/Perms /DocMDP)." }
        }
        val page = doc.pages.getOrNull(pageIndex)
            ?: throw IllegalArgumentException("No page $pageIndex")
        val pageRef = page.reference
            ?: throw IllegalArgumentException("Page $pageIndex has no indirect reference")

        val sigDict = linkedMapOf<String, PdfObject>(
            "Type" to PdfName(if (timestamp) "DocTimeStamp" else "Sig"),
            "Filter" to PdfName("Adobe.PPKLite"),
            "SubFilter" to PdfName(if (timestamp) "ETSI.RFC3161" else options.subFilter),
            // Serialized as a hex string of 2*placeholderSize zeros.
            "Contents" to PdfString(ByteArray(placeholderSize)),
            // Fixed-width placeholder, patched in place by saveForSigning.
            "ByteRange" to PdfArray(listOf(PdfInt(0), PdfInt(RESERVED), PdfInt(RESERVED), PdfInt(RESERVED))),
        )
        options.name?.let { sigDict["Name"] = PdfString(PdfText.encodeTextString(it)) }
        options.reason?.let { sigDict["Reason"] = PdfString(PdfText.encodeTextString(it)) }
        options.location?.let { sigDict["Location"] = PdfString(PdfText.encodeTextString(it)) }
        options.contactInfo?.let { sigDict["ContactInfo"] = PdfString(PdfText.encodeTextString(it)) }
        options.signingTime?.let { sigDict["M"] = PdfString(it.toString().encodeToByteArray()) }
        if (level != null) {
            // The DocMDP transform (Table 253 and 254): the level, at version 1.2.
            sigDict["Reference"] = PdfArray(
                listOf(
                    PdfDictionary(
                        linkedMapOf(
                            "Type" to PdfName("SigRef"),
                            "TransformMethod" to PdfName("DocMDP"),
                            "TransformParams" to PdfDictionary(
                                linkedMapOf("Type" to PdfName("TransformParams"), "P" to PdfInt(level.toLong()), "V" to PdfName("1.2")),
                            ),
                        ),
                    ),
                ),
            )
        }
        val sigRef = editor.addObject(PdfDictionary(sigDict))

        val r = (rect ?: KiteRectangle(0.0, 0.0, 0.0, 0.0)).normalized()
        val widget = linkedMapOf<String, PdfObject>(
            "Type" to PdfName("Annot"),
            "Subtype" to PdfName("Widget"),
            "FT" to PdfName("Sig"),
            "T" to PdfString(PdfText.encodeTextString(fieldName)),
            "V" to sigRef,
            "Rect" to PdfArray(listOf(PdfReal(r.left), PdfReal(r.bottom), PdfReal(r.right), PdfReal(r.top))),
            "F" to PdfInt(4), // print
            "P" to pageRef,
        )
        if (r.width > 0 && r.height > 0) widget["AP"] = PdfDictionary(linkedMapOf("N" to appearanceOf(r.width, r.height, options)))
        val fieldRef = editor.addObject(PdfDictionary(widget))

        // The page, the catalog and the form are read as the editor has them, staged edits
        // included: an incremental update holds the new version of each changed object (ISO
        // 32000-1, 7.5.6), and the base copies undid a stamp, a rotation or /NeedAppearances (#680).
        // Each container may be direct or indirect (7.3.10): a direct one is written back where it
        // was, and an indirect array grows in its own object (#674).
        fun current(ref: PdfReference): PdfObject? = editor.effectiveObject(ref.objectNumber)
        fun appended(container: PdfObject?, item: PdfObject): PdfObject? = when (container) {
            is PdfReference -> {
                val arr = current(container) as? PdfArray
                editor.updateObject(container, PdfArray((arr?.items ?: emptyList()) + item))
                null // the holder keeps its reference
            }
            is PdfArray -> PdfArray(container.items + item)
            else -> PdfArray(listOf(item))
        }

        // Page /Annots gains the widget.
        val pageDict = LinkedHashMap((current(pageRef) as? PdfDictionary ?: page.dictionary).map)
        appended(pageDict["Annots"], fieldRef)?.let { pageDict["Annots"] = it }
        editor.updateObject(pageRef, PdfDictionary(pageDict))

        // AcroForm /Fields gains the field; /SigFlags marks the doc signed.
        val rootRef = (editor.trailerOverrides["Root"] ?: doc.trailer["Root"]) as? PdfReference
            ?: throw IllegalStateException("Trailer /Root is not an indirect reference")
        val catalog = LinkedHashMap((current(rootRef) as? PdfDictionary ?: doc.catalog).map)
        when (val acroEntry = catalog["AcroForm"]) {
            is PdfReference -> {
                val acro = (current(acroEntry) as? PdfDictionary)?.map?.let { LinkedHashMap(it) } ?: LinkedHashMap()
                appended(acro["Fields"], fieldRef)?.let { acro["Fields"] = it }
                acro["SigFlags"] = PdfInt(3)
                editor.updateObject(acroEntry, PdfDictionary(acro))
            }
            is PdfDictionary -> {
                val acro = LinkedHashMap(acroEntry.map)
                appended(acro["Fields"], fieldRef)?.let { acro["Fields"] = it }
                acro["SigFlags"] = PdfInt(3)
                catalog["AcroForm"] = PdfDictionary(acro)
                editor.updateObject(rootRef, PdfDictionary(catalog))
            }
            else -> {
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
        }
        if (level != null) {
            // The catalog names the certification signature in /Perms /DocMDP (Table 258).
            val now = LinkedHashMap((current(rootRef) as? PdfDictionary ?: doc.catalog).map)
            val perms = LinkedHashMap((now["Perms"]?.resolve(editor.effective) as? PdfDictionary)?.map ?: emptyMap())
            perms["DocMDP"] = sigRef
            now["Perms"] = PdfDictionary(perms)
            editor.updateObject(rootRef, PdfDictionary(now))
        }
        prepared = true
    }

    /** The widget's normal appearance, a form XObject of [width] by [height] (ISO 32000-1, 12.7.4.5). */
    private fun appearanceOf(width: Double, height: Double, options: Options): PdfReference {
        val fonts = LinkedHashMap<StandardFont, String>()
        val content = ContentStreamBuilder({ font -> fonts.getOrPut(font) { "KF${fonts.size + 1}" } })
        val draw = options.appearance ?: { w, h -> defaultAppearance(w, h, options) }
        content.draw(width, height)
        val fontDict = LinkedHashMap<String, PdfObject>()
        for ((font, name) in fonts) {
            fontDict[name] = editor.addObject(
                PdfDictionary(
                    linkedMapOf<String, PdfObject>(
                        "Type" to PdfName("Font"),
                        "Subtype" to PdfName("Type1"),
                        "BaseFont" to PdfName(font.baseFont),
                    ).withTextEncoding(font),
                ),
            )
        }
        val extra = linkedMapOf<String, PdfObject>(
            "Type" to PdfName("XObject"),
            "Subtype" to PdfName("Form"),
            "BBox" to PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(width), PdfReal(height))),
            "Resources" to PdfDictionary(linkedMapOf("Font" to PdfDictionary(fontDict))),
        )
        return editor.addObject(PdfStreams.flate(content.toByteArray(), extra))
    }

    /** Acrobat's default look: who signed, then when, why and where, in Helvetica, sized to fit the box. */
    private fun ContentStreamBuilder.defaultAppearance(width: Double, height: Double, options: Options) {
        val lines = buildList {
            add(options.name?.let { "Digitally signed by $it" } ?: "Digitally signed")
            options.signingTime?.let { t ->
                fun two(v: Int) = v.toString().padStart(2, '0')
                val zone = if (t.tzSign == 'Z') "Z" else "${t.tzSign}${two(t.tzHour)}:${two(t.tzMinute)}"
                add("Date: ${t.year}-${two(t.month)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}:${two(t.second)} $zone")
            }
            options.reason?.let { add("Reason: $it") }
            options.location?.let { add("Location: $it") }
        }
        val pad = 2.0
        val size = minOf(10.0, (height - 2 * pad) / (lines.size * 1.2)).coerceAtLeast(1.0)
        setFillGray(0.0)
        for ((i, line) in lines.withIndex()) text(StandardFont.Helvetica, size, pad, height - pad - size * (1.2 * i + 1), line)
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
        // The placeholders are in the revision just written, after the original bytes: a document
        // that keeps an unfilled placeholder from an earlier saveForSigning must not match it (#922).
        val revisionStart = doc.fileSize

        // Find the placeholder: a hex string of exactly 2*placeholderSize
        // ASCII zeros. Verify the closing delimiter so a shorter zero run
        // elsewhere cannot false-match.
        val probe = ("<" + "0".repeat(64)).encodeToByteArray()
        var lt = -1
        var from = revisionStart
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
        val at = indexOf(bytes, placeholder, revisionStart)
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
