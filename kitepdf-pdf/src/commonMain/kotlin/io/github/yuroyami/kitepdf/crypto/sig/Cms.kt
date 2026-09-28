package io.github.yuroyami.kitepdf.crypto.sig

/** One signer of a CMS SignedData (RFC 5652, 5.3): who signed, with what, and the signed attributes (#203). */
internal class CmsSigner(
    private val issuerDer: ByteArray?,
    private val serial: BigNat?,
    private val subjectKeyId: ByteArray?,
    val digestAlgorithm: String,
    private val signedAttributes: Asn1Node?,
    val signatureAlgorithm: String,
    val signatureParameters: Asn1Node?,
    val signature: ByteArray,
) {
    val hasSignedAttributes: Boolean get() = signedAttributes != null

    /**
     * The CRLs and OCSP responses that Acrobat keeps in the signed attribute
     * `adbe-revocationInfoArchival` (ISO 32000-1, 12.8.3.3.2).
     */
    fun archivedRevocation(): Pair<List<Crl>, List<OcspResponse>> {
        val archive = attribute(CmsSignedData.REVOCATION_ARCHIVAL) ?: return emptyList<Crl>() to emptyList()
        val crls = archive.context(0)?.children?.firstOrNull()?.children.orEmpty().mapNotNull { Crl.parse(it.bytes, it.start) }
        val ocsps = archive.context(1)?.children?.firstOrNull()?.children.orEmpty().mapNotNull { OcspResponse.parse(it.bytes, it.start) }
        return crls to ocsps
    }

    /** The first value of the signed attribute [oid], or null when the signer has none. */
    fun attribute(oid: String): Asn1Node? = signedAttributes?.children
        ?.firstOrNull { it.children.firstOrNull()?.oid() == oid }
        ?.children?.getOrNull(1)?.children?.firstOrNull()

    /** The signed attributes as the signature covers them: their encoding with the SET tag in place of [0] (RFC 5652, 5.4). */
    fun signedAttributesForSignature(): ByteArray? = signedAttributes?.encoded()?.also { it[0] = Asn1.SET.toByte() }

    /** True when [cert] is the certificate this signer names, by issuer and serial number or by key identifier. */
    fun names(cert: X509): Boolean = when {
        subjectKeyId != null -> cert.subjectKeyId?.contentEquals(subjectKeyId) == true
        issuerDer != null && serial != null -> cert.serial == serial && cert.issuerDer.contentEquals(issuerDer)
        else -> false
    }
}

/**
 * A CMS SignedData (RFC 5652, 5.1): the certificates it carries, its signers, and the content
 * it encapsulates, if any. A detached signature, as PDF uses, encapsulates none (#203).
 */
internal class CmsSignedData(
    val certificates: List<X509>,
    val signers: List<CmsSigner>,
    val contentType: String?,
    val content: ByteArray?,
    /** The CRLs of the revocation field (RFC 5652, 10.2.1). */
    val crls: List<Crl> = emptyList(),
    /** The OCSP responses of the revocation field, which RFC 5940 puts there. */
    val ocsps: List<OcspResponse> = emptyList(),
) {
    companion object {
        const val SIGNED_DATA = "1.2.840.113549.1.7.2"
        const val MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
        const val SIGNING_TIME = "1.2.840.113549.1.9.5"
        const val SIGNING_CERTIFICATE = "1.2.840.113549.1.9.16.2.12"
        const val SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47"
        const val TST_INFO = "1.2.840.113549.1.9.16.1.4"
        const val REVOCATION_ARCHIVAL = "1.2.840.113583.1.1.8"
        private const val OCSP_RESPONSE_INFO = "1.3.6.1.5.5.7.16.2"

        /** The SignedData in [bytes], or null when they do not hold one. Bytes after it, such as the zeros that pad a PDF signature, are ignored. */
        fun parse(bytes: ByteArray): CmsSignedData? = runCatching {
            val info = Asn1.read(bytes, 0) ?: return null
            if (info.tag != Asn1.SEQUENCE || info.children.firstOrNull()?.oid() != SIGNED_DATA) return null
            val signed = info.children.getOrNull(1)?.takeIf { it.tag == 0xA0 }?.children?.firstOrNull() ?: return null
            val parts = signed.children
            val encapsulated = parts.getOrNull(2) ?: return null
            val certificates = parts.firstOrNull { it.tag == 0xA0 }?.children.orEmpty()
                .filter { it.tag == Asn1.SEQUENCE }
                .mapNotNull { X509.parse(it.bytes, it.start) }
            val signers = parts.lastOrNull()?.takeIf { it.tag == Asn1.SET }?.children.orEmpty().mapNotNull(::signerOf)
            // [1]: each choice is a CRL, or another format such as an OCSP response.
            val revocation = parts.firstOrNull { it.tag == 0xA1 }?.children.orEmpty()
            CmsSignedData(
                certificates = certificates,
                signers = signers,
                contentType = encapsulated.children.firstOrNull()?.oid(),
                content = encapsulated.context(0)?.children?.firstOrNull()?.takeIf { it.tag == Asn1.OCTET_STRING || it.tag == 0x24 }?.content(),
                crls = revocation.filter { it.tag == Asn1.SEQUENCE }.mapNotNull { Crl.parse(it.bytes, it.start) },
                ocsps = revocation.filter { it.tag == 0xA1 && it.children.firstOrNull()?.oid() == OCSP_RESPONSE_INFO }
                    .mapNotNull { other -> other.children.getOrNull(1)?.let { OcspResponse.parse(it.bytes, it.start) } },
            )
        }.getOrNull()

        private fun signerOf(node: Asn1Node): CmsSigner? {
            val c = node.children
            if (node.tag != Asn1.SEQUENCE || c.size < 5) return null
            var i = 1
            val sid = c[i++]
            val digestAlgorithm = c[i++].children.firstOrNull()?.oid() ?: return null
            val signedAttributes = if (c[i].tag == 0xA0) c[i++] else null
            val signatureAlgorithm = c.getOrNull(i++) ?: return null
            val signature = c.getOrNull(i)?.takeIf { it.tag == Asn1.OCTET_STRING || it.tag == 0x24 }?.content() ?: return null
            val byName = sid.tag == Asn1.SEQUENCE
            return CmsSigner(
                issuerDer = if (byName) sid.children.getOrNull(0)?.encoded() else null,
                serial = if (byName) sid.children.getOrNull(1)?.integer() else null,
                subjectKeyId = if (sid.tag == 0x80 || sid.tag == 0xA0) sid.content() else null,
                digestAlgorithm = digestAlgorithm,
                signedAttributes = signedAttributes,
                signatureAlgorithm = signatureAlgorithm.children.firstOrNull()?.oid() ?: return null,
                signatureParameters = signatureAlgorithm.children.getOrNull(1),
                signature = signature,
            )
        }
    }
}
