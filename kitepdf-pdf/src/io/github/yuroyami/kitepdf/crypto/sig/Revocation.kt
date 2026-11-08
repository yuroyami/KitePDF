package io.github.yuroyami.kitepdf.crypto.sig

/** What revocation data says about one certificate (#447). */
internal enum class RevocationState { GOOD, REVOKED, UNKNOWN }

/** A revocation state and, for a revoked certificate, the time of its revocation as written. */
internal class RevocationResult(val state: RevocationState, val revokedAt: String? = null) {
    companion object {
        val UNKNOWN = RevocationResult(RevocationState.UNKNOWN)
    }
}

/**
 * Whether data issued at [thisUpdate] that is next updated at [nextUpdate] still gives the status at
 * [at], all in seconds since 1970: it has not expired by then, or, with no next update, it was
 * issued no earlier.
 */
private fun currentAt(thisUpdate: Long?, nextUpdate: Long?, at: Long): Boolean =
    if (nextUpdate != null) nextUpdate >= at else thisUpdate != null && thisUpdate >= at

/** A certificate revocation list (RFC 5280, 5.1): the serial numbers that its issuer revoked. */
internal class Crl(
    private val tbs: ByteArray,
    val issuerDer: ByteArray,
    private val revoked: Map<BigNat, String?>,
    private val signatureAlgorithm: String,
    private val signatureParameters: Asn1Node?,
    private val signature: ByteArray,
    /** thisUpdate and nextUpdate, as seconds since 1970; nextUpdate null when the list has none. */
    private val thisUpdate: Long?,
    private val nextUpdate: Long?,
    /** False when the list has a critical extension KitePDF does not process (RFC 5280, 5.2). */
    private val usable: Boolean = true,
    private val scope: Scope = Scope(),
) {
    /** The scope an issuing distribution point gives the list (RFC 5280, 5.2.5). */
    class Scope(
        val onlyUserCerts: Boolean = false,
        val onlyCaCerts: Boolean = false,
        val onlySomeReasons: Boolean = false,
        val indirect: Boolean = false,
        val onlyAttributeCerts: Boolean = false,
    )

    /** True when [issuer] signed this list. */
    fun signedBy(issuer: X509): Boolean =
        issuer.key != null && Signatures.verify(signatureAlgorithm, signatureParameters, null, issuer.key, tbs, signature) == true

    /**
     * What this list says about [cert], whose issuer signed it, at the time of interest [at] in
     * seconds since 1970, or null when it says nothing (#1001): revoked when it lists the serial
     * number. Good when it does not, but only from a list current at [at]: its nextUpdate is not
     * before [at], or, with no nextUpdate, it was issued at or after [at] (RFC 5280, 6.3.3 (a)).
     * So a list that had expired before the signing shows nothing about it. A list with a critical
     * extension KitePDF does not process, or whose scope leaves [cert] out, says nothing (5.2, 5.2.5,
     * 6.3.3 (b)). With no time of interest, any list that covers [cert] counts.
     */
    fun check(cert: X509, at: Long? = null): RevocationResult? {
        if (!usable || scope.onlyAttributeCerts || scope.indirect) return null
        if (scope.onlyCaCerts && !cert.isCa || scope.onlyUserCerts && cert.isCa) return null
        if (revoked.containsKey(cert.serial)) return RevocationResult(RevocationState.REVOKED, revoked[cert.serial])
        // A list of some reasons only cannot say a certificate was revoked for none.
        if (scope.onlySomeReasons) return null
        if (at != null && !currentAt(thisUpdate, nextUpdate, at)) return null
        return RevocationResult(RevocationState.GOOD)
    }

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
            val thisUpdate = fields[i++].time() ?: return null
            val nextUpdate = fields.getOrNull(i)?.time()
            if (nextUpdate != null) i++
            val revoked = HashMap<BigNat, String?>()
            fields.getOrNull(i)?.takeIf { it.tag == Asn1.SEQUENCE }?.children?.forEach { entry ->
                val serial = entry.children.getOrNull(0)?.integer() ?: return@forEach
                revoked[serial] = entry.children.getOrNull(1)?.time()
            }
            var usable = true
            var scope = Scope()
            val extensions = fields.firstOrNull { it.tag == 0xA0 }?.children?.firstOrNull()?.children.orEmpty()
            for (ext in extensions) {
                val id = ext.children.firstOrNull()?.oid()
                val critical = ext.children.getOrNull(1)?.let { it.tag == 0x01 && it.content().firstOrNull()?.toInt() != 0 } ?: false
                val value = ext.children.lastOrNull()?.content() ?: continue
                when (id) {
                    "2.5.29.28" -> {
                        // IssuingDistributionPoint: the flags are implicitly tagged booleans [1] to [5].
                        val flags = Asn1.read(value, 0)?.children.orEmpty()
                        fun flag(tag: Int) = flags.any { it.tag == tag && it.content().firstOrNull()?.toInt() != 0 }
                        scope = Scope(
                            onlyUserCerts = flag(0x81), onlyCaCerts = flag(0x82),
                            onlySomeReasons = flags.any { it.tag == 0x83 }, indirect = flag(0x84), onlyAttributeCerts = flag(0x85),
                        )
                    }
                    "2.5.29.20", "2.5.29.35", "2.5.29.46", "1.3.6.1.5.5.7.1.1" -> {}
                    // A delta CRL (2.5.29.27) is critical and means nothing without its base list.
                    else -> if (critical) usable = false
                }
            }
            val algorithm = list.children[1]
            Crl(
                tbs = tbs.encoded(),
                issuerDer = issuer.encoded(),
                revoked = revoked,
                signatureAlgorithm = algorithm.children.firstOrNull()?.oid() ?: return null,
                signatureParameters = algorithm.children.getOrNull(1),
                signature = list.children[2].bitString() ?: return null,
                thisUpdate = asn1Seconds(thisUpdate),
                nextUpdate = asn1Seconds(nextUpdate),
                usable = usable,
                scope = scope,
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
        /** thisUpdate and nextUpdate as seconds since 1970, nextUpdate null when absent. */
        val thisUpdate: Long?,
        val nextUpdate: Long?,
    )

    /**
     * What this response says about [cert], whose issuer is [issuer], or null when it says
     * nothing that KitePDF can trust. The response counts when [issuer] signed it, or a
     * responder certificate that [issuer] issued for OCSP signing (RFC 6960, 4.2.2.2). At the time
     * of interest [at], a response whose nextUpdate has passed is unreliable (4.2.2.1), and one with
     * no nextUpdate gives a good status only when it was produced at or after [at] (#1001).
     */
    fun check(cert: X509, issuer: X509, at: Long? = null): RevocationResult? {
        val single = responses.firstOrNull { s ->
            s.serial == cert.serial &&
                s.issuerNameHash.contentEquals(s.hash.digest(cert.issuerDer)) &&
                s.issuerKeyHash.contentEquals(s.hash.digest(issuer.publicKeyBits))
        } ?: return null
        if (signerOf(issuer) == null) return null
        if (at != null && single.result.state == RevocationState.GOOD && !currentAt(single.thisUpdate, single.nextUpdate, at)) return null
        return single.result
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
                thisUpdate = asn1Seconds(node.children.getOrNull(2)?.time()),
                nextUpdate = asn1Seconds(node.children.firstOrNull { it.tag == 0xA0 }?.children?.firstOrNull()?.time()),
            )
        }
    }
}
