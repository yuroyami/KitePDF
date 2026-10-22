package io.github.yuroyami.kitepdf.crypto.sig

/** RSA signature verification: PKCS #1 v1.5 and PSS (RFC 8017, 8.2.2 and 8.1.2, #203). */
internal object Rsa {

    /** The encoded message that [signature] opens to under the public key ([n], [e]), [length] bytes long, or null. */
    private fun open(n: BigNat, e: BigNat, signature: ByteArray, length: Int): ByteArray? {
        // A hostile certificate could name a huge key; real keys stay far below these sizes.
        if (n.bitLength > MAX_MODULUS_BITS || e.bitLength > MAX_EXPONENT_BITS) return null
        val s = BigNat.fromBytes(signature)
        if (s >= n) return null
        return s.modPow(e, n).toBytes(length)
    }

    /** The DigestInfo that a PKCS #1 v1.5 [signature] opens to (RFC 8017, 9.2), or null when it does not open to one. */
    private fun digestInfo(n: BigNat, e: BigNat, signature: ByteArray): ByteArray? {
        val em = open(n, e, signature, (n.bitLength + 7) / 8) ?: return null
        if (em.size < 11 || em[0].toInt() != 0 || em[1].toInt() != 1) return null
        var i = 2
        while (i < em.size && em[i] == 0xFF.toByte()) i++
        if (i - 2 < 8 || i >= em.size || em[i].toInt() != 0) return null
        return em.copyOfRange(i + 1, em.size)
    }

    /**
     * True when [signature] is a PKCS #1 v1.5 signature of [digest], a hash of the kind [hashOid]
     * names, under ([n], [e]). The DigestInfo must be one of its two DER encodings, with or
     * without NULL parameters: a parser that skips extra bytes there lets a signature be forged.
     */
    fun verifyPkcs1(n: BigNat, e: BigNat, signature: ByteArray, hashOid: String, digest: ByteArray): Boolean {
        val info = digestInfo(n, e, signature) ?: return false
        val oid = Asn1.oid(hashOid)
        val octets = Asn1.encode(Asn1.OCTET_STRING, digest)
        return info.contentEquals(Asn1.sequence(Asn1.sequence(oid, Asn1.encode(Asn1.NULL, ByteArray(0))), octets)) ||
            info.contentEquals(Asn1.sequence(Asn1.sequence(oid), octets))
    }

    /** The hash a PKCS #1 v1.5 [signature] names inside it, or null. Only picks the hash: [verifyPkcs1] checks the signature. */
    fun hashNamedIn(n: BigNat, e: BigNat, signature: ByteArray): Hash? {
        val info = digestInfo(n, e, signature) ?: return null
        return Hash.byOid(Asn1.read(info, 0)?.children?.firstOrNull()?.children?.firstOrNull()?.oid())
    }

    /** True when [signature] is an RSASSA-PSS signature of [digest] with [hash], MGF1 over [mgfHash], and a salt of [saltLength]. */
    fun verifyPss(n: BigNat, e: BigNat, signature: ByteArray, hash: Hash, mgfHash: Hash, digest: ByteArray, saltLength: Int): Boolean {
        val emBits = n.bitLength - 1
        val emLen = (emBits + 7) / 8
        if (saltLength < 0) return false
        val em = open(n, e, signature, emLen) ?: return false
        val hLen = digest.size
        if (emLen < hLen + saltLength + 2 || em.last() != 0xBC.toByte()) return false
        val maskedDb = em.copyOfRange(0, emLen - hLen - 1)
        val h = em.copyOfRange(emLen - hLen - 1, emLen - 1)
        val clear = 8 * emLen - emBits
        if (clear > 0 && (maskedDb[0].toInt() and 0xFF) ushr (8 - clear) != 0) return false
        val mask = mgf1(mgfHash, h, maskedDb.size)
        val db = ByteArray(maskedDb.size) { (maskedDb[it].toInt() xor mask[it].toInt()).toByte() }
        if (clear > 0) db[0] = (db[0].toInt() and (0xFF ushr clear)).toByte()
        val ps = emLen - hLen - saltLength - 2
        for (j in 0 until ps) if (db[j].toInt() != 0) return false
        if (db[ps].toInt() != 1) return false
        val salt = db.copyOfRange(db.size - saltLength, db.size)
        val mPrime = ByteArray(8) + digest + salt
        return hash.digest(mPrime).contentEquals(h)
    }

    private const val MAX_MODULUS_BITS = 16384
    private const val MAX_EXPONENT_BITS = 256

    private fun mgf1(hash: Hash, seed: ByteArray, length: Int): ByteArray {
        val out = ArrayList<Byte>(length)
        var counter = 0
        while (out.size < length) {
            val c = byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
            for (b in hash.digest(seed + c)) if (out.size < length) out += b
            counter++
        }
        return out.toByteArray()
    }
}

/** A hash a signature digests with, by the object identifier that names it. */
internal enum class Hash(val oid: String, val digest: (ByteArray) -> ByteArray) {
    SHA1("1.3.14.3.2.26", Sha1::hash),
    SHA256("2.16.840.1.101.3.4.2.1", io.github.yuroyami.kitepdf.crypto.Sha256::hash),
    SHA384("2.16.840.1.101.3.4.2.2", io.github.yuroyami.kitepdf.crypto.Sha512::hash384),
    SHA512("2.16.840.1.101.3.4.2.3", io.github.yuroyami.kitepdf.crypto.Sha512::hash);

    companion object {
        fun byOid(oid: String?): Hash? = entries.firstOrNull { it.oid == oid }
    }
}
