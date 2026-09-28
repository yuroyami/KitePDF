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
    private val document: PdfDocument,
    private val dictionary: PdfDictionary,
) {
    /**
     * Checks the signature against the file. [trustAnchors] are the DER encodings of the
     * certificates the caller trusts. An entry that is not a certificate is ignored. The check
     * hashes the signed bytes, which are almost the whole file, so run it off the main thread.
     *
     * [PdfCertificate.revocation] reports revocation from the CRLs and OCSP responses that the
     * document carries, in its document security store and in the signature, and from
     * [revocationData], each a DER CRL or OCSP response that the caller fetched. KitePDF fetches
     * nothing from the network. It does not check that the certificates were valid at the time
     * of signing: [PdfCertificate.notBefore] and [PdfCertificate.notAfter] give the dates.
     */
    public fun validate(
        trustAnchors: List<ByteArray> = emptyList(),
        revocationData: List<ByteArray> = emptyList(),
    ): PdfSignatureValidation = SignatureValidator(
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
            PdfSignature(
                field = field,
                subFilter = dict.getName("SubFilter"),
                isDocumentTimestamp = dict.getName("Type") == "DocTimeStamp",
                name = text("Name"),
                reason = text("Reason"),
                location = text("Location"),
                contactInfo = text("ContactInfo"),
                signingTime = (dict["M"]?.resolve(document) as? PdfString)?.asAsciiOrNull()?.let(PdfDate::parse),
                byteRange = dict.getArray("ByteRange", document)?.mapNotNull { (it.resolve(document) as? PdfInt)?.value }.orEmpty(),
                document = document,
                dictionary = dict,
            )
        }.getOrNull()

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
    /** True when [status] is [Status.Valid] and a certificate of [chain] is one of the trust anchors. */
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
     * the timestamp authority states.
     */
    public val signedTime: PdfDate?,
    /** Why [status] is not [Status.Valid], in words, or null when it is. */
    public val detail: String?,
) {
    /** True when [status] is [Status.Valid]. */
    public val isValid: Boolean get() = status == Status.Valid

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
    /** A CRL or an OCSP response that the certificate's issuer signed says that it is good. */
    Good,

    /** A CRL or an OCSP response that the certificate's issuer signed says that it was revoked. */
    Revoked,

    /** No revocation data that KitePDF can check covers the certificate, as for a root. */
    Unknown,
}
