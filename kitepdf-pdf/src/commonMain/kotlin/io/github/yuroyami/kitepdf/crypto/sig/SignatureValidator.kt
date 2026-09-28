package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfCertificate
import io.github.yuroyami.kitepdf.PdfDate
import io.github.yuroyami.kitepdf.PdfRevocation
import io.github.yuroyami.kitepdf.PdfSignatureValidation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status

/**
 * Checks one PDF signature (ISO 32000-1, 12.8; ISO 32000-2, 12.8.3): its byte range, its CMS
 * message or PKCS #1 value, the signer's certificate chain, and trust (#203).
 */
internal class SignatureValidator(
    private val file: ByteArray,
    private val byteRange: List<Long>,
    private val subFilter: String?,
    /** The dictionary's `/Contents` as the parser read it, or null when the file is encrypted and the parser changed it. */
    private val dictionaryContents: ByteArray?,
    /** The `/Cert` strings of an `adbe.x509.rsa_sha1` signature. */
    private val certStrings: List<ByteArray>,
    /** Certificates the document keeps outside the signature, such as those of `/DSS`. */
    private val documentCerts: List<ByteArray>,
    trustAnchors: List<ByteArray>,
    /** CRLs and OCSP responses from the document and the caller, each in DER (#447). */
    revocationData: List<ByteArray> = emptyList(),
) {
    private val anchors = trustAnchors.mapNotNull { X509.parse(it) }
    private var modified = false

    // The revocation data found so far: the document's and the caller's, then the signature's own.
    private val crls = ArrayList<Crl>()
    private val ocsps = ArrayList<OcspResponse>()

    init {
        for (data in revocationData) {
            Crl.parse(data)?.let { crls += it } ?: OcspResponse.parse(data)?.let { ocsps += it }
        }
    }

    fun validate(): PdfSignatureValidation {
        val ranges = rangesOf() ?: return result(Status.Malformed, "the byte range is not a list of ascending ranges inside the file")
        val hole = holeOf(ranges) ?: return result(Status.Malformed, "the byte range does not skip exactly one hexadecimal string")
        val contents = hexOf(hole)
        if (dictionaryContents != null && !dictionaryContents.contentEquals(contents)) {
            return result(Status.Malformed, "the byte range skips a string that is not the /Contents of this signature")
        }
        modified = uncovered(ranges, hole)
        val signed = ByteArray(ranges.sumOf { it.last - it.first + 1 }.toInt())
        var at = 0
        for (r in ranges) {
            file.copyInto(signed, at, r.first.toInt(), r.last.toInt() + 1)
            at += (r.last - r.first + 1).toInt()
        }
        return when (subFilter) {
            "adbe.x509.rsa_sha1" -> pkcs1(contents, signed)
            else -> cms(contents, signed)
        }
    }

    /** The signed ranges, or null when they are not ascending, apart and inside the file. */
    private fun rangesOf(): List<LongRange>? {
        if (byteRange.size < 4 || byteRange.size % 2 != 0) return null
        val out = ArrayList<LongRange>()
        var end = 0L
        for (i in byteRange.indices step 2) {
            val start = byteRange[i]
            val length = byteRange[i + 1]
            if (start < end || length <= 0 || start + length > file.size) return null
            out += start until start + length
            end = start + length
        }
        return out
    }

    /**
     * The gap that holds the signature: the first gap between two ranges that is one hexadecimal
     * string and nothing else. Anything else in it, such as an object slipped in after the
     * string, would sit unsigned inside the signature's own hole.
     */
    private fun holeOf(ranges: List<LongRange>): LongRange? {
        for (i in 0 until ranges.size - 1) {
            val from = ranges[i].last + 1
            val to = ranges[i + 1].first
            if (to - from < 2) continue
            if (file[from.toInt()] != '<'.code.toByte() || file[to.toInt() - 1] != '>'.code.toByte()) continue
            if ((from + 1 until to - 1).all { hexValue(file[it.toInt()]) >= 0 || isWhite(file[it.toInt()]) }) return from until to
        }
        return null
    }

    /** True when a byte outside the ranges and the hole is not white space: the signature leaves part of the file unsigned. */
    private fun uncovered(ranges: List<LongRange>, hole: LongRange): Boolean {
        if (ranges.first().first > 0) return true
        for (i in 0 until ranges.size - 1) {
            val gap = ranges[i].last + 1 until ranges[i + 1].first
            if (gap != hole && gap.any { !isWhite(file[it.toInt()]) }) return true
        }
        return (ranges.last().last + 1 until file.size).any { !isWhite(file[it.toInt()]) }
    }

    private fun hexOf(hole: LongRange): ByteArray {
        val digits = (hole.first + 1 until hole.last).map { file[it.toInt()] }.filter { !isWhite(it) }
        return ByteArray((digits.size + 1) / 2) { i ->
            val high = hexValue(digits[2 * i])
            val low = digits.getOrNull(2 * i + 1)?.let(::hexValue) ?: 0
            ((high shl 4) or low).toByte()
        }
    }

    /** An `adbe.x509.rsa_sha1` signature: a PKCS #1 value in an OCTET STRING, with the certificates in `/Cert` (ISO 32000-1, 12.8.3.2). */
    private fun pkcs1(contents: ByteArray, signed: ByteArray): PdfSignatureValidation {
        val value = Asn1.read(contents, 0)?.takeIf { it.tag == Asn1.OCTET_STRING }?.content()
            ?: return result(Status.Malformed, "the signature is not an OCTET STRING")
        val certs = certStrings.mapNotNull { X509.parse(it) }
        val cert = certs.firstOrNull() ?: return result(Status.Malformed, "the signature has no certificate in /Cert")
        val chain = chainOf(cert, certs + pool())
        val key = cert.key as? PublicKey.RsaKey ?: return result(Status.Unsupported, "the certificate of an adbe.x509.rsa_sha1 signature holds no RSA key", chain)
        // The hash is SHA-1 or one ISO 32000-1, 12.8.3.2 allows; the DigestInfo inside names it.
        val hash = Rsa.hashNamedIn(key.n, key.e, value) ?: return result(Status.Invalid, "the signature does not match the signer's certificate", chain)
        return if (Rsa.verifyPkcs1(key.n, key.e, value, hash.oid, hash.digest(signed))) result(Status.Valid, null, chain)
        else result(Status.DigestMismatch, "the signed bytes changed after signing", chain)
    }

    /** A CMS signature: `adbe.pkcs7.detached`, `ETSI.CAdES.detached`, `adbe.pkcs7.sha1` or a document timestamp. */
    private fun cms(contents: ByteArray, signed: ByteArray): PdfSignatureValidation {
        val cms = CmsSignedData.parse(contents) ?: return result(Status.Malformed, "the signature is not a CMS SignedData")
        val signer = cms.signers.firstOrNull() ?: return result(Status.Malformed, "the signature names no signer")
        crls += cms.crls
        ocsps += cms.ocsps
        signer.archivedRevocation().let { (archivedCrls, archivedOcsps) ->
            crls += archivedCrls
            ocsps += archivedOcsps
        }
        val pool = cms.certificates + pool()
        val cert = pool.firstOrNull { signer.names(it) } ?: return result(Status.Malformed, "the signature holds no certificate for its signer")
        val chain = chainOf(cert, pool)
        var signedTime = asn1Date(signer.attribute(CmsSignedData.SIGNING_TIME)?.time())
        val content = when (subFilter) {
            "adbe.pkcs7.sha1" -> {
                // The message is the SHA-1 digest of the signed bytes (ISO 32000-1, 12.8.3.3.1).
                val message = cms.content ?: return result(Status.Malformed, "an adbe.pkcs7.sha1 signature encapsulates no digest", chain, signedTime)
                if (!message.contentEquals(Sha1.hash(signed))) return result(Status.DigestMismatch, "the signed bytes changed after signing", chain, signedTime)
                message
            }
            "ETSI.RFC3161" -> {
                // The message is a TSTInfo whose imprint is the digest of the signed bytes (RFC 3161, 2.4.2).
                val message = cms.content?.takeIf { cms.contentType == CmsSignedData.TST_INFO }
                    ?: return result(Status.Malformed, "the document timestamp encapsulates no TSTInfo", chain)
                val info = Asn1.read(message, 0) ?: return result(Status.Malformed, "the TSTInfo cannot be read", chain)
                val imprint = info.children.getOrNull(2)
                val imprintHash = Hash.byOid(imprint?.children?.firstOrNull()?.children?.firstOrNull()?.oid())
                    ?: return result(Status.Unsupported, "the timestamp digests with a hash KitePDF does not know", chain)
                signedTime = asn1Date(info.children.getOrNull(4)?.time())
                val imprinted = imprint?.children?.getOrNull(1)?.content()
                if (imprinted?.contentEquals(imprintHash.digest(signed)) != true) {
                    return result(Status.DigestMismatch, "the signed bytes changed after the timestamp", chain, signedTime)
                }
                message
            }
            else -> signed
        }
        val hash = Hash.byOid(signer.digestAlgorithm)
            ?: return result(Status.Unsupported, "the signature digests with ${signer.digestAlgorithm}, which KitePDF does not know", chain, signedTime)
        val key = cert.key ?: return result(Status.Unsupported, "the signer's certificate holds a kind of key KitePDF does not check", chain, signedTime)
        val covered = if (signer.hasSignedAttributes) {
            val digest = signer.attribute(CmsSignedData.MESSAGE_DIGEST)?.takeIf { it.tag == Asn1.OCTET_STRING }?.content()
                ?: return result(Status.Malformed, "the signed attributes hold no message digest", chain, signedTime)
            if (!digest.contentEquals(hash.digest(content))) return result(Status.DigestMismatch, "the signed bytes changed after signing", chain, signedTime)
            signer.signedAttributesForSignature()!!
        } else {
            content
        }
        val verified = Signatures.verify(signer.signatureAlgorithm, signer.signatureParameters, signer.digestAlgorithm, key, covered, signer.signature)
            ?: return result(Status.Unsupported, "the signature uses ${signer.signatureAlgorithm}, which KitePDF does not check", chain, signedTime)
        if (!verified) return result(Status.Invalid, "the signature does not match the signer's certificate", chain, signedTime)
        essProblem(signer, cert)?.let { return result(Status.Invalid, it, chain, signedTime) }
        return result(Status.Valid, null, chain, signedTime)
    }

    /**
     * A problem with the ESS signing-certificate attribute, which CAdES requires: it binds the
     * signer's certificate into the signed attributes, so a certificate swapped in later does not
     * match (RFC 5035, 3). Null when the attribute is absent or names [cert].
     */
    private fun essProblem(signer: CmsSigner, cert: X509): String? {
        val v2 = signer.attribute(CmsSignedData.SIGNING_CERTIFICATE_V2)
        val first = (v2 ?: signer.attribute(CmsSignedData.SIGNING_CERTIFICATE))?.children?.firstOrNull()?.children?.firstOrNull() ?: return null
        val (hash, certHash) = if (v2 != null && first.children.firstOrNull()?.tag == Asn1.SEQUENCE) {
            Hash.byOid(first.children[0].children.firstOrNull()?.oid()) to first.children.getOrNull(1)
        } else {
            (if (v2 != null) Hash.SHA256 else Hash.SHA1) to first.children.firstOrNull()
        }
        hash ?: return "the signing certificate attribute digests with a hash KitePDF does not know"
        return if (certHash?.content()?.contentEquals(hash.digest(cert.der)) == true) null
        else "the signing certificate attribute names a different certificate"
    }

    /** The certificates outside the signature: the document's own and the trust anchors. */
    private fun pool(): List<X509> = documentCerts.mapNotNull { X509.parse(it) } + anchors

    /** The chain from [cert] upwards: each next certificate names the last one's issuer and signed it. */
    private fun chainOf(cert: X509, pool: List<X509>): List<X509> {
        val chain = mutableListOf(cert)
        while (chain.size < MAX_CHAIN) {
            val last = chain.last()
            if (last.issuerDer.contentEquals(last.subjectDer) && last.signedBy(last)) break
            val issuer = pool.firstOrNull { candidate ->
                chain.none { it.der.contentEquals(candidate.der) } && candidate.subjectDer.contentEquals(last.issuerDer) && last.signedBy(candidate)
            } ?: break
            chain += issuer
        }
        return chain
    }

    private fun result(status: Status, detail: String?, chain: List<X509> = emptyList(), signedTime: PdfDate? = null): PdfSignatureValidation {
        // Trust speaks for the signer only when the signature holds: an invalid one claims nothing.
        val trusted = status == Status.Valid && chain.any { c -> anchors.any { it.der.contentEquals(c.der) } }
        val certificates = chain.mapIndexed { i, cert -> certificateOf(cert, revocationOf(cert, chain.getOrNull(i + 1))) }
        return PdfSignatureValidation(status, certificates.firstOrNull(), certificates, trusted, modified, signedTime, detail)
    }

    /**
     * What the revocation data says about [cert], issued by [issuer]. Only data that the issuer
     * signed counts, or an OCSP responder that the issuer delegated to. A revocation outweighs
     * a good status from another source. Without an issuer, as for a root, nothing is known.
     */
    private fun revocationOf(cert: X509, issuer: X509?): RevocationResult {
        issuer ?: return RevocationResult.UNKNOWN
        val results = crls.filter { it.issuerDer.contentEquals(cert.issuerDer) && it.signedBy(issuer) }.map { it.check(cert) } +
            ocsps.mapNotNull { it.check(cert, issuer) }
        return results.firstOrNull { it.state == RevocationState.REVOKED }
            ?: results.firstOrNull { it.state == RevocationState.GOOD }
            ?: RevocationResult.UNKNOWN
    }

    private companion object {
        const val MAX_CHAIN = 10

        fun certificateOf(cert: X509, revocation: RevocationResult) = PdfCertificate(
            subject = cert.subject,
            issuer = cert.issuer,
            commonName = cert.commonName,
            serialNumber = cert.serialHex,
            notBefore = asn1Date(cert.notBefore),
            notAfter = asn1Date(cert.notAfter),
            revocation = when (revocation.state) {
                RevocationState.GOOD -> PdfRevocation.Good
                RevocationState.REVOKED -> PdfRevocation.Revoked
                RevocationState.UNKNOWN -> PdfRevocation.Unknown
            },
            revokedAt = asn1Date(revocation.revokedAt),
            der = cert.der,
        )

        fun hexValue(b: Byte): Int = when (val c = b.toInt().toChar()) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        fun isWhite(b: Byte): Boolean = when (b.toInt()) {
            0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20 -> true
            else -> false
        }

        /** A UTCTime (two-digit year, RFC 5280, 4.1.2.5.1) or a GeneralizedTime as a [PdfDate]. */
        fun asn1Date(text: String?): PdfDate? = runCatching {
            val t = text?.trim() ?: return null
            val digits = t.takeWhile { it.isDigit() }
            val full = if (digits.length == 10 || digits.length == 12) {
                (if (digits.substring(0, 2).toInt() < 50) "20" else "19") + digits
            } else {
                digits
            }
            val rest = t.substring(digits.length).dropWhile { it == '.' || it == ',' || it.isDigit() }
            val zone = when {
                rest.startsWith("Z") -> "Z"
                rest.startsWith("+") || rest.startsWith("-") -> "${rest[0]}${rest.substring(1, 3)}'${rest.substring(3, 5)}'"
                else -> ""
            }
            PdfDate.parse("D:" + full.padEnd(14, '0') + zone)
        }.getOrNull()
    }
}
