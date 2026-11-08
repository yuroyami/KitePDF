package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.crypto.sig.SignatureValidator

/**
 * A digital signature in a signature field (ISO 32000-1, 12.8): what its signature dictionary
 * says, and [validate] to check it. [PdfDocument.signatures] lists the signatures of a document.
 */
public class PdfSignature internal constructor(
    /** The signature field that holds this signature. */
    public val field: PdfFormField,
    /** The encoding of the signature (`/SubFilter`), such as `adbe.pkcs7.detached` or `ETSI.CAdES.detached`. */
    public val subFilter: String?,
    /**
     * True for a document timestamp (`/Type /DocTimeStamp`, ISO 32000-2, 12.8.5). A timestamp
     * authority signs it to prove that the document existed at a time. It names no person.
     */
    public val isDocumentTimestamp: Boolean,
    /** The signer's name as the signing application wrote it (`/Name`). [PdfSignatureValidation.signer] is the checked name. */
    public val name: String?,
    /** Why the document was signed (`/Reason`), or null. */
    public val reason: String?,
    /** Where the document was signed (`/Location`), or null. */
    public val location: String?,
    /** How to reach the signer (`/ContactInfo`), or null. */
    public val contactInfo: String?,
    /** When the document was signed, as the signing application wrote it (`/M`). Nothing checks this time. */
    public val signingTime: PdfDate?,
    /**
     * The signed bytes of the file (`/ByteRange`): an offset and a length for each part. The gap
     * between the parts holds the signature itself.
     */
    public val byteRange: List<Long>,
    /**
     * The permission level when this is a certification signature (`/DocMDP`, ISO 32000-1,
     * 12.8.2.2): 1 allows no change, 2 allows filling in forms and signing, and 3 also allows
     * annotations. Null for an approval signature.
     */
    public val certificationLevel: Int?,
    private val document: PdfDocument,
    private val dictionary: PdfDictionary,
    /** The FieldMDP lock of this signature, from its field's `/Lock` or its own reference. */
    private val lock: RevisionChanges.FieldLock?,
) {
    /**
     * Checks the signature against the file. [trustAnchors] are the DER encodings of the
     * certificates the caller trusts. An entry that is not a certificate is ignored. The check
     * hashes the signed bytes, which are almost the whole file, so run it off the main thread.
     *
     * [PdfCertificate.revocation] reports revocation from the CRLs and OCSP responses that the
     * document carries, in its document security store and in the signature, and from
     * [revocationData], each a DER CRL or OCSP response that the caller fetched. KitePDF fetches
     * nothing from the network. [PdfSignatureValidation.certificatesValidAtSigning] says whether
     * the chain was inside its validity period at the signing time, and a chain that was not is
     * not trusted.
     */
    public fun validate(
        trustAnchors: List<ByteArray> = emptyList(),
        revocationData: List<ByteArray> = emptyList(),
    ): PdfSignatureValidation {
        val result = check(trustAnchors, revocationData)
        return result.withChanges(if (!result.isModifiedAfterSigning) emptyList() else changesAfterSigning())
    }

    /**
     * The changes that later revisions made, judged at the document's certification level, or
     * null when the signed revision cannot be read (#448). The level and the lock come from the
     * signed revision, so the revision under judgement cannot set its own rules (#1002): the
     * certification signature is the one that the catalog's `/Perms /DocMDP` names (ISO 32000-1,
     * 12.8.2.2 and Table 258), and the FieldMDP lock is the one of the field as it was signed
     * (12.8.2.4).
     */
    private fun changesAfterSigning(): List<PdfRevisionChange>? {
        if (byteRange.size < 2) return null
        val end = byteRange[byteRange.size - 2] + byteRange[byteRange.size - 1]
        if (end > Int.MAX_VALUE) return null
        val signed = document.revisionEndingAt(end.toInt()) ?: return null
        return runCatching {
            val level = certificationLevelOf(signed)
            val signedLock = signed.signatures.firstOrNull { it.field.fullyQualifiedName == field.fullyQualifiedName }?.lock
            RevisionChanges.between(signed, document, level, signedLock)
        }.getOrNull()
    }

    private fun check(trustAnchors: List<ByteArray>, revocationData: List<ByteArray>): PdfSignatureValidation = SignatureValidator(
        file = document.fileBytes,
        byteRange = byteRange,
        subFilter = subFilter,
        // An encrypted file keeps /Contents in clear (ISO 32000-1, 7.6.1), but the parser decrypts every string.
        dictionaryContents = if (document.isEncrypted) null else (dictionary["Contents"] as? PdfString)?.bytes,
        certStrings = when (val cert = dictionary["Cert"]?.resolve(document)) {
            is PdfString -> listOf(cert.bytes)
            is PdfArray -> cert.mapNotNull { (it.resolve(document) as? PdfString)?.bytes }
            else -> emptyList()
        },
        documentCerts = documentStreams(document, "Certs"),
        trustAnchors = trustAnchors,
        revocationData = documentStreams(document, "CRLs") + documentStreams(document, "OCSPs") + revocationData,
    ).validate()

    override fun toString(): String = "PdfSignature(${field.fullyQualifiedName}, $subFilter)"

    internal companion object {
        /** The signature in [field], or null when the field is not signed. */
        fun of(field: PdfFormField, document: PdfDocument): PdfSignature? = runCatching {
            val dict = field.fieldDict.getDict("V", document) ?: return null
            fun text(key: String) = (dict[key]?.resolve(document) as? PdfString)?.asText()
            // The signature references (ISO 32000-1, Table 253): a DocMDP one certifies, a FieldMDP one locks fields.
            fun params(method: String) = transformParams(dict, method, document)
            PdfSignature(
                field = field,
                subFilter = dict.getName("SubFilter"),
                isDocumentTimestamp = dict.getName("Type") == "DocTimeStamp",
                name = text("Name"),
                reason = text("Reason"),
                location = text("Location"),
                contactInfo = text("ContactInfo"),
                signingTime = (dict["M"]?.resolve(document) as? PdfString)?.asText()?.let(PdfDate::parse),
                byteRange = dict.getArray("ByteRange", document)?.mapNotNull { (it.resolve(document) as? PdfInt)?.value }.orEmpty(),
                certificationLevel = docMdpLevel(dict, document),
                document = document,
                dictionary = dict,
                lock = RevisionChanges.FieldLock.of(field.fieldDict.getDict("Lock", document) ?: params("FieldMDP"), document),
            )
        }.getOrNull()

        /** The signature references of [dict] (ISO 32000-1, Table 253): a DocMDP one certifies, a FieldMDP one locks fields. */
        private fun transformParams(dict: PdfDictionary, method: String, document: PdfDocument): PdfDictionary? =
            dict.getArray("Reference", document).orEmpty()
                .mapNotNull { it.resolve(document) as? PdfDictionary }
                .firstOrNull { it.getName("TransformMethod") == method }
                ?.let { it.getDict("TransformParams", document) ?: PdfDictionary(emptyMap()) }

        /** The certification level of the signature dictionary [dict], or null when it has no DocMDP reference. A DocMDP reference without /P means level 2. */
        private fun docMdpLevel(dict: PdfDictionary, document: PdfDocument): Int? =
            transformParams(dict, "DocMDP", document)?.let { ((it["P"] as? PdfInt)?.value?.toInt() ?: 2).coerceIn(1, 3) }

        /**
         * The certification level of [document]: that of the signature the catalog's `/Perms
         * /DocMDP` names (ISO 32000-1, 12.8.4, Table 258). A file without `/Perms` falls back to a
         * signature of [document] with a DocMDP reference; [document] is a signed revision, so
         * nothing a later revision adds is in it.
         */
        private fun certificationLevelOf(document: PdfDocument): Int? {
            val perms = document.catalog.getDict("Perms", document)
            if (perms?.get("DocMDP") != null) return perms.getDict("DocMDP", document)?.let { docMdpLevel(it, document) }
            return document.signatures.firstNotNullOfOrNull { it.certificationLevel }
        }

        /**
         * The streams of the document security store under [key]: `Certs`, `CRLs` or `OCSPs`
         * (ISO 32000-2, 12.8.4.3). A long-term signature keeps its validation data there.
         */
        private fun documentStreams(document: PdfDocument, key: String): List<ByteArray> = runCatching {
            val items = document.catalog.getDict("DSS", document)?.getArray(key, document) ?: return emptyList()
            items.mapNotNull { item -> (item.resolve(document) as? PdfStream)?.let { runCatching { FilterChain.decode(it) }.getOrNull() } }
        }.getOrDefault(emptyList())
    }
}

/**
 * A signature time-stamp token: a timestamp authority's proof that the signature existed at
 * [time] (RFC 3161, 2.4.2). The signer's own claim, [PdfSignatureValidation.signedTime], stays
 * beside it.
 */
public class PdfSignatureTimestamp internal constructor(
    /** The time the authority states (`genTime`), or null when it does not read. */
    public val time: PdfDate?,
    /** Whether the token stamps this signature and the authority's signature over it holds. */
    public val isValid: Boolean,
    /**
     * Whether the token is valid, the authority's chain reaches a trust anchor and was valid at
     * [time], and its certificate has the one critical extended key usage id-kp-timeStamping.
     */
    public val isTrusted: Boolean,
    /** The authority's certificate, or null when the token holds none. */
    public val authority: PdfCertificate?,
    /** Why the token is not valid or not trusted, in words, or null. */
    public val detail: String?,
) {
    override fun toString(): String = "PdfSignatureTimestamp(time=$time, isValid=$isValid, isTrusted=$isTrusted)"
}

/** The result of [PdfSignature.validate]. */
public class PdfSignatureValidation internal constructor(
    /** Whether the signature matches the signed bytes and the signer's certificate. */
    public val status: Status,
    /** The signer's certificate, or null when the signature holds none for its signer. */
    public val signer: PdfCertificate?,
    /**
     * The certificate chain of the signer, signer first. Each certificate is signed by the next
     * one. The chain ends at a self-signed certificate, or where no issuer is found in the
     * signature, the document or the trust anchors.
     */
    public val chain: List<PdfCertificate>,
    /**
     * True when [status] is [Status.Valid], a certificate of [chain] is one of the trust anchors,
     * and the path from the signer to it holds by RFC 5280, 6.1: each issuer below the anchor is a
     * CA by its basicConstraints, its key usage, when it has one, allows certificate signing, its
     * path length constraint holds, and no certificate below the anchor has a critical extension
     * KitePDF does not process. [detail] says which rule a path that fails breaks. A chain whose
     * [certificatesValidAtSigning] is false is not trusted either.
     */
    public val isTrusted: Boolean,
    /**
     * True when the file holds bytes that the signature does not cover, apart from the
     * signature itself. Usually a revision was appended after signing. Such a revision can be a
     * permitted change, such as a filled form field or a second signature.
     */
    public val isModifiedAfterSigning: Boolean,
    /**
     * The time inside the signed data, or null when it holds none. For a signature it is the
     * signing-time attribute, which the signer states. For a document timestamp it is the time
     * the timestamp authority states; [isTrusted] holds only when the signer's certificate is a
     * time stamping authority's, with the one critical extended key usage id-kp-timeStamping
     * (RFC 3161, 2.3).
     */
    public val signedTime: PdfDate?,
    /** Why [status] is not [Status.Valid], or else why the chain is not trusted, in words, or null. */
    public val detail: String?,
    /**
     * The changes that later revisions made after signing, each with whether the certification
     * level and the field locks allow it. Empty when the file did not change after signing.
     * Null when KitePDF cannot read the signed revision to compare it.
     */
    public val changes: List<PdfRevisionChange>? = null,
    /**
     * Whether every certificate of [chain] below the trust anchor, or every one when none is an
     * anchor, was inside its validity period at [signedTime] (RFC 5280, 6.1.3 (a)(2)). Null when
     * the signature states no time. For a signature that time is the one its signer states, which
     * nothing but a timestamp vouches for; for a document timestamp it is the authority's.
     */
    public val certificatesValidAtSigning: Boolean? = null,
    /**
     * The signature time-stamp token that a timestamp authority added to the signer (RFC 3161,
     * Appendix A; ETSI EN 319 122-1, 5.3), or null when it has none. When it is trusted, its time is
     * the time [certificatesValidAtSigning] and revocation are judged at, rather than [signedTime],
     * which the signer states (#925).
     */
    public val timestamp: PdfSignatureTimestamp? = null,
) {
    /** True when [status] is [Status.Valid]. */
    public val isValid: Boolean get() = status == Status.Valid

    /** True when the file did not change after signing, or every change is permitted. */
    public val areChangesPermitted: Boolean get() = changes?.all { it.isPermitted } ?: !isModifiedAfterSigning

    internal fun withChanges(changes: List<PdfRevisionChange>?): PdfSignatureValidation =
        PdfSignatureValidation(status, signer, chain, isTrusted, isModifiedAfterSigning, signedTime, detail, changes, certificatesValidAtSigning, timestamp)

    /** Whether a signature matches the signed bytes and the signer's certificate. */
    public enum class Status {
        /** The signature matches the signed bytes and the signer's certificate. */
        Valid,

        /** The signed bytes changed after signing: their digest is not the one the signature holds. */
        DigestMismatch,

        /** The signature does not match the signer's certificate, or the signed attributes name another certificate. */
        Invalid,

        /** The signature uses an algorithm or an encoding that KitePDF does not check. */
        Unsupported,

        /** The byte range, the signature or its certificates cannot be read. */
        Malformed,
    }

    override fun toString(): String = "PdfSignatureValidation($status, signer=${signer?.commonName}, trusted=$isTrusted, modifiedAfterSigning=$isModifiedAfterSigning)"
}

/** An X.509 certificate from a signature (RFC 5280). */
public class PdfCertificate internal constructor(
    /** The subject's distinguished name, such as `CN=Jane Doe, O=Example, C=US`. */
    public val subject: String,
    /** The issuer's distinguished name. */
    public val issuer: String,
    /** The subject's common name, or [subject] when it has none. */
    public val commonName: String,
    /** The serial number in hexadecimal, such as `1A2B3C`. */
    public val serialNumber: String,
    /** The start of the validity period, or null when it cannot be read. */
    public val notBefore: PdfDate?,
    /** The end of the validity period, or null when it cannot be read. */
    public val notAfter: PdfDate?,
    /** What the revocation data says about this certificate. */
    public val revocation: PdfRevocation,
    /** When the certificate was revoked, or null when it was not or the data does not say. */
    public val revokedAt: PdfDate?,
    private val der: ByteArray,
) {
    /** The certificate in DER. */
    public val encoded: ByteArray get() = der.copyOf()

    override fun toString(): String = "PdfCertificate($subject)"
}

/** What the revocation data says about a certificate of a signature's chain. */
public enum class PdfRevocation {
    /**
     * A CRL or an OCSP response that the certificate's issuer signed says that it is good, and is
     * current at the signing time: its next update is not before it, or, with none, it was issued
     * no earlier. A CRL counts only when its scope covers the certificate and it has no critical
     * extension KitePDF does not process (RFC 5280, 5.2 and 6.3.3; RFC 6960, 4.2.2.1).
     */
    Good,

    /** A CRL or an OCSP response that the certificate's issuer signed says that it was revoked. */
    Revoked,

    /** No revocation data that KitePDF can check covers the certificate, as for a root. */
    Unknown,
}

/**
 * A change that a later revision made after a signature (ISO 32000-1, 12.8.2.2), with whether
 * the certification level and the field locks allow it. Without a certification signature,
 * KitePDF applies the permissions of level 3.
 */
public class PdfRevisionChange internal constructor(
    /** What kind of change it is. */
    public val kind: Kind,
    /** What the change touches, in words, such as `the value of field Name` or `a Text annotation on page 2`. */
    public val subject: String,
    /** The number of the object that changed. */
    public val objectNumber: Long,
    /** True when the certification level and the field locks allow the change. */
    public val isPermitted: Boolean,
) {
    public enum class Kind {
        /** The value of a form field, or how its widget shows it. Level 2 and up allow it, unless a lock covers the field. */
        FieldValue,

        /** A new signature, a new signature field, or a document timestamp. Level 2 and up allow a signature; a timestamp is always allowed. */
        Signature,

        /** An annotation that is not a form widget. Level 3 allows it. */
        Annotation,

        /** The document security store, which long-term validation adds to. Always allowed. */
        SecurityStore,

        /** Any other change, such as page content, a new form field or the catalog. Never allowed. */
        Other,
    }

    override fun toString(): String = "PdfRevisionChange($kind, $subject, permitted=$isPermitted)"
}
