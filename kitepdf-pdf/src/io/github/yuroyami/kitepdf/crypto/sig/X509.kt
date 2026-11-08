package io.github.yuroyami.kitepdf.crypto.sig

/** The public key of a certificate: RSA, or a point on one of the NIST curves (#203). */
internal sealed class PublicKey {
    class RsaKey(val n: BigNat, val e: BigNat) : PublicKey()
    class EcKey(val curve: EcCurve, val x: BigNat, val y: BigNat) : PublicKey()
}

/**
 * An X.509 certificate (RFC 5280, 4.1): the parts a signature check reads. [issuerDer] and
 * [subjectDer] are the encoded names, which a chain compares byte for byte.
 */
internal class X509(
    val der: ByteArray,
    val tbs: ByteArray,
    val serial: BigNat,
    /** The serial number in upper-case hexadecimal, without the sign byte of its encoding. */
    val serialHex: String,
    val issuerDer: ByteArray,
    val subjectDer: ByteArray,
    val issuer: String,
    val subject: String,
    val notBefore: String?,
    val notAfter: String?,
    val key: PublicKey?,
    /** The bits of the subject public key, which an OCSP response hashes to name a key. */
    val publicKeyBits: ByteArray,
    val signatureAlgorithm: String,
    val signatureParameters: Asn1Node?,
    val signature: ByteArray,
    val subjectKeyId: ByteArray?,
    val isCa: Boolean,
    /** The basicConstraints path length constraint (RFC 5280, 4.2.1.9), or null when it sets none. */
    val pathLength: Int?,
    /** Whether a key usage extension (RFC 5280, 4.2.1.3) is present. */
    val hasKeyUsage: Boolean,
    /** Whether that extension sets keyCertSign, bit 5. */
    val keyCertSign: Boolean,
    /** Whether it sets digitalSignature (bit 0) or nonRepudiation (bit 1), which a key that signs data needs. */
    val signsData: Boolean,
    /** Whether the extended key usage extension is critical. */
    val extendedKeyUsageCritical: Boolean,
    /** The OIDs of critical extensions this validator does not process (RFC 5280, 4.2). */
    val unknownCritical: List<String>,
    /** The extended key usages (RFC 5280, 4.2.1.12), such as OCSP signing. */
    val extendedKeyUsages: List<String>,
) {
    /** The name's common name, or the whole name when it has none. */
    val commonName: String get() = subject.split(", ").firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: subject

    /** True when [issuer] signed this certificate. */
    fun signedBy(issuer: X509): Boolean =
        issuer.key != null && Signatures.verify(signatureAlgorithm, signatureParameters, null, issuer.key, tbs, signature) == true

    companion object {
        /** The certificate encoded at [offset] of [bytes], or null when it is not one. */
        fun parse(bytes: ByteArray, offset: Int = 0): X509? = runCatching {
            val cert = Asn1.read(bytes, offset) ?: return null
            if (cert.tag != Asn1.SEQUENCE || cert.children.size < 3) return null
            val tbs = cert.children[0]
            val fields = tbs.children
            // The version is an explicit [0] before the serial when it is not v1.
            var i = if (fields.firstOrNull()?.tag == 0xA0) 1 else 0
            val serial = fields[i++].integer() ?: return null
            i++ // the signature algorithm inside the TBS repeats the outer one
            val issuer = fields[i++]
            val validity = fields[i++]
            val subject = fields[i++]
            val spki = fields[i++]
            val extensions = fields.drop(i).firstOrNull { it.tag == 0xA3 }?.children?.firstOrNull()?.children.orEmpty()
            var subjectKeyId: ByteArray? = null
            var isCa = false
            var pathLength: Int? = null
            var hasKeyUsage = false
            var keyCertSign = false
            var signsData = false
            var ekuCritical = false
            val unknownCritical = ArrayList<String>()
            var usages = emptyList<String>()
            for (ext in extensions) {
                val id = ext.children.firstOrNull()?.oid()
                val critical = ext.children.getOrNull(1)?.let { it.tag == 0x01 && it.content().firstOrNull()?.toInt() != 0 } ?: false
                val value = ext.children.lastOrNull()?.takeIf { it.tag == Asn1.OCTET_STRING }?.content() ?: continue
                when (id) {
                    "2.5.29.14" -> subjectKeyId = Asn1.read(value, 0)?.content()
                    "2.5.29.19" -> {
                        val fieldsOf = Asn1.read(value, 0)?.children.orEmpty()
                        isCa = fieldsOf.firstOrNull()?.let { it.tag == 0x01 && it.content().firstOrNull()?.toInt() != 0 } ?: false
                        pathLength = fieldsOf.firstOrNull { it.tag == 0x02 }?.content()?.let { bytes ->
                            if (bytes.size > 4) Int.MAX_VALUE else bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                        }
                    }
                    "2.5.29.15" -> {
                        hasKeyUsage = true
                        // A BIT STRING: its first byte counts the unused bits, then bit 0 is the top bit.
                        val bits = Asn1.read(value, 0)?.content()
                        keyCertSign = bits != null && bits.size >= 2 && (bits[1].toInt() and 0x04) != 0
                        signsData = bits != null && bits.size >= 2 && (bits[1].toInt() and 0xC0) != 0
                    }
                    "2.5.29.37" -> {
                        usages = Asn1.read(value, 0)?.children.orEmpty().mapNotNull { it.oid() }
                        ekuCritical = critical
                    }
                    else -> if (critical && id !in PROCESSED) unknownCritical += id ?: "?"
                }
            }
            val algorithm = cert.children[1]
            X509(
                der = cert.encoded(),
                tbs = tbs.encoded(),
                serial = serial,
                serialHex = (serial.toBytes()?.takeIf { it.isNotEmpty() } ?: byteArrayOf(0))
                    .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase(),
                issuerDer = issuer.encoded(),
                subjectDer = subject.encoded(),
                issuer = nameOf(issuer),
                subject = nameOf(subject),
                notBefore = validity.children.getOrNull(0)?.time(),
                notAfter = validity.children.getOrNull(1)?.time(),
                key = keyOf(spki),
                publicKeyBits = spki.children.getOrNull(1)?.bitString() ?: return null,
                signatureAlgorithm = algorithm.children.firstOrNull()?.oid() ?: return null,
                signatureParameters = algorithm.children.getOrNull(1),
                signature = cert.children[2].bitString() ?: return null,
                subjectKeyId = subjectKeyId,
                isCa = isCa,
                pathLength = pathLength,
                hasKeyUsage = hasKeyUsage,
                keyCertSign = keyCertSign,
                signsData = signsData,
                extendedKeyUsageCritical = ekuCritical,
                unknownCritical = unknownCritical,
                extendedKeyUsages = usages,
            )
        }.getOrNull()

        private fun keyOf(spki: Asn1Node): PublicKey? {
            val algorithm = spki.children.getOrNull(0) ?: return null
            val bits = spki.children.getOrNull(1)?.bitString() ?: return null
            return when (algorithm.children.firstOrNull()?.oid()) {
                "1.2.840.113549.1.1.1", "1.2.840.113549.1.1.10" -> {
                    val seq = Asn1.read(bits, 0) ?: return null
                    val n = seq.children.getOrNull(0)?.integer() ?: return null
                    val e = seq.children.getOrNull(1)?.integer() ?: return null
                    PublicKey.RsaKey(n, e)
                }
                "1.2.840.10045.2.1" -> {
                    val curve = EcCurve.byOid(algorithm.children.getOrNull(1)?.oid() ?: return null) ?: return null
                    val (x, y) = curve.decodePoint(bits) ?: return null
                    PublicKey.EcKey(curve, x, y)
                }
                else -> null
            }
        }

        /** A name as `CN=..., O=..., C=...`, in the order it is encoded. */
        private fun nameOf(name: Asn1Node): String = name.children.flatMap { rdn -> rdn.children }.mapNotNull { attribute ->
            val type = attribute.children.getOrNull(0)?.oid() ?: return@mapNotNull null
            val value = attribute.children.getOrNull(1)?.string() ?: return@mapNotNull null
            "${NAMES[type] ?: type}=$value"
        }.joinToString(", ")

        /**
         * Extensions that a critical flag does not make fail the path: those read above, and those
         * that only describe the certificate or point at data, which a check of one signature does
         * not depend on. Name constraints and policy constraints are left out, so a critical one
         * fails the path, as RFC 5280, 6.1.4 (o) asks of an extension a validator does not process.
         */
        private val PROCESSED = setOf(
            "2.5.29.17", // subject alternative name
            "2.5.29.18", // issuer alternative name
            "2.5.29.31", // CRL distribution points
            "2.5.29.32", // certificate policies: no policy is required, so any set is acceptable
            "2.5.29.35", // authority key identifier
            "2.5.29.46", // freshest CRL
            "1.3.6.1.5.5.7.1.1", // authority information access
            "1.3.6.1.5.5.7.48.1.5", // OCSP no check
        )

        private val NAMES = mapOf(
            "2.5.4.3" to "CN", "2.5.4.10" to "O", "2.5.4.11" to "OU", "2.5.4.6" to "C", "2.5.4.7" to "L",
            "2.5.4.8" to "ST", "2.5.4.5" to "SERIALNUMBER", "1.2.840.113549.1.9.1" to "E",
        )
    }
}
