package io.github.yuroyami.kitepdf.crypto.sig

/** Checks a signature by the algorithm its identifier names (#203). */
internal object Signatures {

    /**
     * True when [signature] signs [data] under [key], false when it does not, and null when the
     * algorithm is not one KitePDF checks. [digestOid] gives the hash when the algorithm names
     * only the key kind, as a CMS signer's `rsaEncryption` does.
     */
    fun verify(algorithm: String, parameters: Asn1Node?, digestOid: String?, key: PublicKey, data: ByteArray, signature: ByteArray): Boolean? {
        val rsaHash = RSA_ALGORITHMS[algorithm] ?: if (algorithm == RSA_ENCRYPTION) Hash.byOid(digestOid) else null
        val ecHash = EC_ALGORITHMS[algorithm] ?: if (algorithm == EC_PUBLIC_KEY) Hash.byOid(digestOid) else null
        return when {
            rsaHash != null -> (key as? PublicKey.RsaKey)?.let { Rsa.verifyPkcs1(it.n, it.e, signature, rsaHash.oid, rsaHash.digest(data)) } ?: false
            algorithm == RSA_PSS -> {
                val rsa = key as? PublicKey.RsaKey ?: return false
                // RFC 4055, 3.1: the hash, the mask's hash and the salt length, each with a default.
                val hash = parameters?.context(0)?.children?.firstOrNull()?.children?.firstOrNull()?.oid()?.let { Hash.byOid(it) ?: return null } ?: Hash.SHA1
                val mask = parameters?.context(1)?.children?.firstOrNull()
                if (mask != null && mask.children.firstOrNull()?.oid() != MGF1) return null
                val mgfHash = mask?.children?.getOrNull(1)?.children?.firstOrNull()?.oid()?.let { Hash.byOid(it) ?: return null } ?: Hash.SHA1
                val salt = parameters?.context(2)?.children?.firstOrNull()?.integer()?.let { if (it.bitLength > 16) return false else it.toBytes(2) }
                    ?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) } ?: 20
                Rsa.verifyPss(rsa.n, rsa.e, signature, hash, mgfHash, hash.digest(data), salt)
            }
            ecHash != null -> {
                val ec = key as? PublicKey.EcKey ?: return false
                val pair = Asn1.read(signature, 0) ?: return false
                val r = pair.children.getOrNull(0)?.integer() ?: return false
                val s = pair.children.getOrNull(1)?.integer() ?: return false
                ec.curve.verify(ec.x, ec.y, ecHash.digest(data), r, s)
            }
            else -> null
        }
    }

    private const val RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
    private const val RSA_PSS = "1.2.840.113549.1.1.10"
    private const val EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    private const val MGF1 = "1.2.840.113549.1.1.8"

    private val RSA_ALGORITHMS = mapOf(
        "1.2.840.113549.1.1.5" to Hash.SHA1,
        "1.2.840.113549.1.1.11" to Hash.SHA256,
        "1.2.840.113549.1.1.12" to Hash.SHA384,
        "1.2.840.113549.1.1.13" to Hash.SHA512,
    )

    private val EC_ALGORITHMS = mapOf(
        "1.2.840.10045.4.1" to Hash.SHA1,
        "1.2.840.10045.4.3.2" to Hash.SHA256,
        "1.2.840.10045.4.3.3" to Hash.SHA384,
        "1.2.840.10045.4.3.4" to Hash.SHA512,
    )
}
