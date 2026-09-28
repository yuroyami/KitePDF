package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.PSS_SHA256
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The arithmetic, hashes and signature schemes under PDF signatures, against the JDK's own (#203). */
class SignaturePrimitivesTest {

    private val random = Random(203)

    private fun big(bits: Int): BigInteger = BigInteger(bits, java.util.Random(random.nextLong()))

    private fun nat(b: BigInteger): BigNat = BigNat.fromBytes(b.toByteArray())

    private fun BigNat.big(): BigInteger = BigInteger(1, toBytes() ?: ByteArray(0))

    @Test
    fun bigNatMatchesBigInteger() {
        repeat(3000) {
            val a = big(random.nextInt(1, 2200))
            val b = big(random.nextInt(1, 1200)).let { if (it.signum() == 0) BigInteger.ONE else it }
            assertEquals(a + b, (nat(a) + nat(b)).big(), "$a + $b")
            assertEquals(a * b, (nat(a) * nat(b)).big(), "$a * $b")
            val (q, r) = nat(a).divRem(nat(b))
            assertEquals(a.divide(b), q.big(), "$a / $b")
            assertEquals(a.mod(b), r.big(), "$a % $b")
            assertEquals(a.compareTo(b).coerceIn(-1, 1), nat(a).compareTo(nat(b)).coerceIn(-1, 1))
            if (a >= b) assertEquals(a - b, (nat(a) - nat(b)).big())
            val shift = random.nextInt(0, 100)
            assertEquals(a.shiftLeft(shift), nat(a).shl(shift).big())
        }
    }

    @Test
    fun bigNatDivisionHitsTheRareCorrection() {
        // Divisors whose top limbs are all ones make the trial quotient too large once in a while (Knuth, 4.3.1, D6).
        repeat(2000) {
            val top = BigInteger.ONE.shiftLeft(64 * random.nextInt(2, 6)).subtract(BigInteger.ONE)
            val b = top.shiftLeft(random.nextInt(0, 64)).subtract(big(random.nextInt(1, 40)))
            val a = b.multiply(big(random.nextInt(1, 300))).add(big(random.nextInt(1, 200)))
            val (q, r) = nat(a).divRem(nat(b))
            assertEquals(a.divide(b), q.big())
            assertEquals(a.mod(b), r.big())
        }
    }

    @Test
    fun bigNatDivisionAddsBackWhenTheTrialQuotientIsTooLarge() {
        // Each of these makes the trial quotient one too large after its test, so step D6 adds the divisor back.
        val a = BigInteger.ONE.shiftLeft(96)
        for (b in listOf(BigInteger.ONE.shiftLeft(64) + BigInteger.ONE, BigInteger.ONE.shiftLeft(65) + BigInteger.ONE, BigInteger.ONE.shiftLeft(95) + BigInteger.ONE)) {
            val (q, r) = nat(a).divRem(nat(b))
            assertEquals(a.divide(b), q.big(), "2^96 / $b")
            assertEquals(a.mod(b), r.big(), "2^96 % $b")
        }
    }

    @Test
    fun modPowMatchesBigInteger() {
        repeat(60) {
            val m = big(random.nextInt(2, 1100)).setBit(0).let { if (it <= BigInteger.ONE) BigInteger.valueOf(3) else it }
            val base = big(random.nextInt(1, 1200))
            val e = big(random.nextInt(1, 300))
            assertEquals(base.modPow(e, m), nat(base).modPow(nat(e), nat(m)).big())
        }
    }

    @Test
    fun bytesRoundTrip() {
        assertEquals(BigInteger.ZERO, BigNat.ZERO.big())
        assertNull(BigNat.of(0x1_0000).toBytes(2), "a number that does not fit reports null")
        assertEquals("000102", BigNat.of(0x0102).toBytes(3)!!.joinToString("") { "%02x".format(it) })
        assertEquals(BigInteger("1234567890abcdef1234567890", 16), BigNat.fromHex("1234567890ABCDEF1234567890").big())
    }

    @Test
    fun sha1MatchesTheJdk() {
        val jdk = MessageDigest.getInstance("SHA-1")
        for (size in (0..300) + listOf(1_000_003)) {
            val data = random.nextBytes(size)
            assertTrue(jdk.digest(data).contentEquals(Sha1.hash(data)), "SHA-1 of $size bytes")
        }
    }

    @Test
    fun curveConstantsHold() {
        for (curve in listOf(EcCurve.P256, EcCurve.P384, EcCurve.P521)) assertTrue(curve.constantsHold(), curve.name)
    }

    @Test
    fun ecdsaMatchesTheJdk() {
        for ((curve, jca, hash) in listOf(
            Triple("secp256r1", "SHA256withECDSA", "SHA-256"),
            Triple("secp384r1", "SHA384withECDSA", "SHA-384"),
            Triple("secp521r1", "SHA512withECDSA", "SHA-512"),
            Triple("secp256r1", "SHA512withECDSA", "SHA-512"),
        )) {
            val keys = SigFixtures.ecKeys(curve)
            val message = random.nextBytes(500)
            val der = Signature.getInstance(jca).run { initSign(keys.private); update(message); sign() }
            val pair = Asn1.read(der, 0)!!
            val public = keys.public as ECPublicKey
            val kite = EcCurve.byOid(Asn1.read(public.encoded, 0)!!.children[0].children[1].oid()!!)!!
            val qx = nat(public.w.affineX)
            val qy = nat(public.w.affineY)
            val digest = MessageDigest.getInstance(hash).digest(message)
            val r = pair.children[0].integer()!!
            val s = pair.children[1].integer()!!
            assertTrue(kite.verify(qx, qy, digest, r, s), "$curve $jca")
            val other = MessageDigest.getInstance(hash).digest(message + 1)
            assertFalse(kite.verify(qx, qy, other, r, s), "$curve $jca on another message")
            assertFalse(kite.verify(qx, qy, digest, r, s + BigNat.ONE), "$curve $jca with s changed")
            assertFalse(kite.verify(qx, qx, digest, r, s), "$curve $jca with a key off the curve")
            assertFalse(kite.verify(qx, qy, digest, r, s + kite.n), "$curve $jca with s past the order")
            // y + p names the same point to the field arithmetic, so only the range check refuses it.
            val p = nat((public.params.curve.field as java.security.spec.ECFieldFp).p)
            assertFalse(kite.verify(qx, qy + p, digest, r, s), "$curve $jca with y past the field")
        }
    }

    @Test
    fun compressedPointsDecode() {
        for (curve in listOf("secp256r1", "secp384r1", "secp521r1")) {
            repeat(4) {
                val public = SigFixtures.ecKeys(curve).public as ECPublicKey
                val kite = EcCurve.byOid(Asn1.read(public.encoded, 0)!!.children[0].children[1].oid()!!)!!
                val size = (public.params.curve.field.fieldSize + 7) / 8
                val x = nat(public.w.affineX).toBytes(size)!!
                val prefix = if (public.w.affineY.testBit(0)) 3 else 2
                val (dx, dy) = kite.decodePoint(byteArrayOf(prefix.toByte()) + x)!!
                assertEquals(public.w.affineX, dx.big())
                assertEquals(public.w.affineY, dy.big())
            }
        }
    }

    @Test
    fun rsaPkcs1MatchesTheJdk() {
        val keys = SigFixtures.rsaKeys()
        val public = keys.public as RSAPublicKey
        val n = nat(public.modulus)
        val e = nat(public.publicExponent)
        val message = random.nextBytes(300)
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(message); sign() }
        assertTrue(Rsa.verifyPkcs1(n, e, signature, SigFixtures.SHA256, digest))
        assertEquals(Hash.SHA256, Rsa.hashNamedIn(n, e, signature))
        assertFalse(Rsa.verifyPkcs1(n, e, signature, SigFixtures.SHA256, digest.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertFalse(Rsa.verifyPkcs1(n, e, signature, SigFixtures.SHA384, digest))

        // A DigestInfo without the NULL parameters is the other valid encoding (RFC 8017, 9.2, note 2).
        fun rawSign(info: ByteArray) = Signature.getInstance("NONEwithRSA").run { initSign(keys.private); update(info); sign() }
        val bare = SigFixtures.seq(SigFixtures.algorithm(SigFixtures.SHA256), SigFixtures.octets(digest))
        assertTrue(Rsa.verifyPkcs1(n, e, rawSign(bare), SigFixtures.SHA256, digest))
        // Bytes hidden in the parameters are the forgery route of lenient parsers: refused.
        val hidden = SigFixtures.seq(SigFixtures.algorithm(SigFixtures.SHA256, SigFixtures.octets(ByteArray(8))), SigFixtures.octets(digest))
        assertFalse(Rsa.verifyPkcs1(n, e, rawSign(hidden), SigFixtures.SHA256, digest))
        val trailing = SigFixtures.seq(SigFixtures.algorithm(SigFixtures.SHA256, SigFixtures.NULL), SigFixtures.octets(digest)) + byteArrayOf(0)
        assertFalse(Rsa.verifyPkcs1(n, e, rawSign(trailing), SigFixtures.SHA256, digest))
    }

    @Test
    fun rsaPssMatchesTheJdk() {
        // An odd size makes the encoded message a byte shorter than the key (RFC 8017, 9.1.1, emLen).
        val keys = SigFixtures.rsaKeys(2049)
        val public = keys.public as RSAPublicKey
        val n = nat(public.modulus)
        val e = nat(public.publicExponent)
        val message = random.nextBytes(300)
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        val signature = Signature.getInstance("RSASSA-PSS").run { initSign(keys.private); setParameter(PSS_SHA256); update(message); sign() }
        assertTrue(Rsa.verifyPss(n, e, signature, Hash.SHA256, Hash.SHA256, digest, 32))
        assertFalse(Rsa.verifyPss(n, e, signature, Hash.SHA256, Hash.SHA256, digest, 20), "the wrong salt length")
        assertFalse(Rsa.verifyPss(n, e, signature, Hash.SHA256, Hash.SHA1, digest, 32), "the wrong mask hash")
        assertFalse(Rsa.verifyPss(n, e, signature, Hash.SHA256, Hash.SHA256, MessageDigest.getInstance("SHA-256").digest(message + 1), 32))
    }

    @Test
    fun asn1ReadsIndefiniteLengthsAndRefusesDeepNesting() {
        // SEQUENCE (indefinite) { INTEGER 5, OCTET STRING (constructed, indefinite) { "ab", "c" } }
        val ber = byteArrayOf(0x30, 0x80.toByte(), 0x02, 0x01, 0x05, 0x24, 0x80.toByte(), 0x04, 0x02, 0x61, 0x62, 0x04, 0x01, 0x63, 0x00, 0x00, 0x00, 0x00)
        val node = assertNotNull(Asn1.read(ber, 0))
        assertEquals(ber.size, node.end)
        assertEquals(2, node.children.size)
        assertEquals("abc", node.children[1].content().decodeToString())
        var nested = byteArrayOf(0x05, 0x00)
        repeat(100) { nested = Asn1.encode(0x30, nested) }
        var walk = Asn1.read(nested, 0)
        var depth = 0
        while (walk != null && walk.children.isNotEmpty()) {
            walk = walk.children[0]
            depth++
        }
        assertEquals(Asn1.MAX_DEPTH, depth, "nesting past the limit is not read")
        assertEquals("1.2.840.113549.1.7.2", Asn1.read(Asn1.oid("1.2.840.113549.1.7.2"), 0)!!.oid())
    }
}
