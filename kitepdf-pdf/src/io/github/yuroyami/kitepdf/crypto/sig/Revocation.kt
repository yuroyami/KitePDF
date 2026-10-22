package io.github.yuroyami.kitepdf.crypto.sig

/** What revocation data says about one certificate (#447). */
internal enum class RevocationState { GOOD, REVOKED, UNKNOWN }

/** A revocation state and, for a revoked certificate, the time of its revocation as written. */
internal class RevocationResult(val state: RevocationState, val revokedAt: String? = null) {
    companion object {
        val UNKNOWN = RevocationResult(RevocationState.UNKNOWN)
    }
}

/** A certificate revocation list (RFC 5280, 5.1): the serial numbers that its issuer revoked. */
internal class Crl(
    private val tbs: ByteArray,
    val issuerDer: ByteArray,
    private val revoked: Map<BigNat, String?>,
    private val signatureAlgorithm: String,
    private val signatureParameters: Asn1Node?,
    private val signature: ByteArray,
) {
    /** True when [issuer] signed this list. */
    fun signedBy(issuer: X509): Boolean =
        issuer.key != null && Signatures.verify(signatureAlgorithm, signatureParameters, null, issuer.key, tbs, signature) == true

    /** What this list says about [cert], whose issuer signed it: revoked when it lists the serial number, good otherwise. */
    fun check(cert: X509): RevocationResult =
        if (revoked.containsKey(cert.serial)) RevocationResult(RevocationState.REVOKED, revoked[cert.serial]) else RevocationResult(RevocationState.GOOD)

    companion object {
        /** The list encoded at [offset] of [bytes], or null when it is not one. */
        fun parse(bytes: ByteArray, offset: Int = 0): Crl? = runCatching {
            val list = Asn1.read(bytes, offset) ?: return null
            if (list.tag != Asn1.SEQUENCE || list.children.size != 3) return null
            val tbs = list.children[0]
            val fields = tbs.children
            // The version is there only for a v2 list.
            var i = if (fields.firstOrNull()?.tag == Asn1.INTEGER) 1 else 0
            i++ // the signature algorithm inside repeats the outer one
            val issuer = fields[i++]
            if (fields[i++].time() == null) return null // thisUpdate
            if (fields.getOrNull(i)?.time() != null) i++ // nextUpdate, when present
            val revoked = HashMap<BigNat, String?>()
            fields.getOrNull(i)?.takeIf { it.tag == Asn1.SEQUENCE }?.children?.forEach { entry ->
                val serial = entry.children.getOrNull(0)?.integer() ?: return@forEach
                revoked[serial] = entry.children.getOrNull(1)?.time()
            }
            val algorithm = list.children[1]
            Crl(
                tbs = tbs.encoded(),
                issuerDer = issuer.encoded(),
                revoked = revoked,
                signatureAlgorithm = algorithm.children.firstOrNull()?.oid() ?: return null,
                signatureParameters = algorithm.children.getOrNull(1),
                signature = list.children[2].bitString() ?: return null,
            )
        }.getOrNull()
    }
}

/**
 * An OCSP response (RFC 6960, 4.2.1): its signer states the status of certificates that it names
 * by the hashes of their issuer's name and key, and their serial numbers.
 */
internal class OcspResponse(
    private val tbs: ByteArray,
    private val responderName: ByteArray?,
    private val responderKeyHash: ByteArray?,
    private val responses: List<Single>,
    private val signatureAlgorithm: String,
    private val signatureParameters: Asn1Node?,
    private val signature: ByteArray,
    private val certs: List<X509>,
) {
    class Single(
        val hash: Hash,
        val issuerNameHash: ByteArray,
        val issuerKeyHash: ByteArray,
        val serial: BigNat,
        val result: RevocationResult,
    )

    /**
     * What this response says about [cert], whose issuer is [issuer], or null when it says
     * nothing that KitePDF can trust. The response counts when [issuer] signed it, or a
     * responder certificate that [issuer] issued for OCSP signing (RFC 6960, 4.2.2.2).
     */
    fun check(cert: X509, issuer: X509): RevocationResult? {
        val single = responses.firstOrNull { s ->
            s.serial == cert.serial &&
                s.issuerNameHash.contentEquals(s.hash.digest(cert.issuerDer)) &&
                s.issuerKeyHash.contentEquals(s.hash.digest(issuer.publicKeyBits))
        } ?: return null
        return if (signerOf(issuer) != null) single.result else null
    }

    private fun signerOf(issuer: X509): X509? = (listOf(issuer) + certs).firstOrNull { candidate ->
        names(candidate) && candidate.key != null &&
            Signatures.verify(signatureAlgorithm, signatureParameters, null, candidate.key, tbs, signature) == true &&
            (candidate === issuer || (candidate.issuerDer.contentEquals(issuer.subjectDer) && OCSP_SIGNING in candidate.extendedKeyUsages && candidate.signedBy(issuer)))
    }

    /** True when the responder ID names [cert], by its name or by the SHA-1 of its key. */
    private fun names(cert: X509): Boolean = when {
        responderName != null -> responderName.contentEquals(cert.subjectDer)
        responderKeyHash != null -> responderKeyHash.contentEquals(Sha1.hash(cert.publicKeyBits))
        else -> false
    }

    companion object {
        private const val BASIC = "1.3.6.1.5.5.7.48.1.1"
        private const val OCSP_SIGNING = "1.3.6.1.5.5.7.3.9"

        /** The response encoded at [offset] of [bytes]: a whole OCSPResponse, or the BasicOCSPResponse inside one. */
        fun parse(bytes: ByteArray, offset: Int = 0): OcspResponse? = runCatching {
            val outer = Asn1.read(bytes, offset) ?: return null
            if (outer.tag != Asn1.SEQUENCE) return null
            val basic = if (outer.children.firstOrNull()?.tag == 0x0A) {
                // OCSPResponse: a successful status, then the bytes of a basic response.
                if (outer.children[0].content().singleOrNull()?.toInt() != 0) return null
                val body = outer.context(0)?.children?.firstOrNull() ?: return null
                if (body.children.firstOrNull()?.oid() != BASIC) return null
                val octets = body.children.getOrNull(1)?.content() ?: return null
                Asn1.read(octets, 0) ?: return null
            } else {
                outer
            }
            basicOf(basic)
        }.getOrNull()

        private fun basicOf(basic: Asn1Node): OcspResponse? {
            val data = basic.children.getOrNull(0) ?: return null
            val fields = data.children
            var i = if (fields.firstOrNull()?.tag == 0xA0) 1 else 0
            val responder = fields.getOrNull(i++) ?: return null
            i++ // producedAt
            val responses = fields.getOrNull(i)?.children.orEmpty().mapNotNull(::singleOf)
            val algorithm = basic.children.getOrNull(1) ?: return null
            return OcspResponse(
                tbs = data.encoded(),
                responderName = if (responder.tag == 0xA1) responder.children.firstOrNull()?.encoded() else null,
                responderKeyHash = if (responder.tag == 0xA2) responder.children.firstOrNull()?.content() else null,
                responses = responses,
                signatureAlgorithm = algorithm.children.firstOrNull()?.oid() ?: return null,
                signatureParameters = algorithm.children.getOrNull(1),
                signature = basic.children.getOrNull(2)?.bitString() ?: return null,
                certs = basic.context(0)?.children?.firstOrNull()?.children.orEmpty().mapNotNull { X509.parse(it.bytes, it.start) },
            )
        }

        private fun singleOf(node: Asn1Node): Single? {
            val id = node.children.getOrNull(0) ?: return null
            val status = node.children.getOrNull(1) ?: return null
            val result = when (status.tag) {
                0x80 -> RevocationResult(RevocationState.GOOD)
                0xA1 -> RevocationResult(RevocationState.REVOKED, status.children.firstOrNull()?.time())
                else -> RevocationResult.UNKNOWN
            }
            return Single(
                hash = Hash.byOid(id.children.getOrNull(0)?.children?.firstOrNull()?.oid()) ?: return null,
                issuerNameHash = id.children.getOrNull(1)?.content() ?: return null,
                issuerKeyHash = id.children.getOrNull(2)?.content() ?: return null,
                serial = id.children.getOrNull(3)?.integer() ?: return null,
                result = result,
            )
        }
    }
}
