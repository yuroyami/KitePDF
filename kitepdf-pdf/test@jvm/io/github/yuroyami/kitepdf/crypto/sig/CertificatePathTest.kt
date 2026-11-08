package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfSignatureValidation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RFC 5280, 6.1 path validation of a signer's chain (#999): only a CA whose key usage allows
 * certificate signing, within its path length, may issue, and an unknown critical extension stops
 * the path. The chains are those of the issue, which `openssl verify` and Poppler's `pdfsig` refuse.
 */
class CertificatePathTest {

    private companion object {
        const val DIGITAL_SIGNATURE = 0x80
        const val NON_REPUDIATION = 0x40
        const val KEY_CERT_SIGN = 0x04
        const val CRL_SIGN = 0x02
        val root = SigFixtures.certificate("Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1, keyUsage = KEY_CERT_SIGN or CRL_SIGN)
    }

    private fun validate(signer: SigFixtures.Identity, chain: List<SigFixtures.Identity>): PdfSignatureValidation {
        val pdf = SigFixtures.signedPdf { SigFixtures.cms(signer, chain, it) }
        val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, result.detail)
        return result
    }

    @Test
    fun aChainThroughACaIsTrusted() {
        val intermediate = SigFixtures.certificate("Intermediate", SigFixtures.rsaKeys(), root, ca = true, serial = 2, keyUsage = KEY_CERT_SIGN)
        val bob = SigFixtures.certificate("Bob", SigFixtures.rsaKeys(), intermediate, ca = false, serial = 3, keyUsage = DIGITAL_SIGNATURE)
        val result = validate(bob, listOf(bob, intermediate, root))
        assertTrue(result.isTrusted, result.detail)
        assertEquals(null, result.detail)
    }

    @Test
    fun anEndEntityCannotIssue() {
        val alice = SigFixtures.certificate("Alice", SigFixtures.rsaKeys(), root, ca = false, serial = 2, keyUsage = DIGITAL_SIGNATURE or NON_REPUDIATION)
        val ceo = SigFixtures.certificate("CEO", SigFixtures.rsaKeys(), alice, ca = false, serial = 3)
        val result = validate(ceo, listOf(ceo, alice, root))
        assertEquals(listOf("CEO", "Alice", "Root"), result.chain.map { it.commonName })
        assertFalse(result.isTrusted)
        assertTrue(assertNotNull(result.detail).contains("Alice, which issued CEO, is not a CA"), result.detail)
    }

    @Test
    fun aCaWhoseKeyUsageLacksCertificateSigningCannotIssue() {
        val ca = SigFixtures.certificate("NoCertSign CA", SigFixtures.rsaKeys(), root, ca = true, serial = 2, keyUsage = DIGITAL_SIGNATURE or CRL_SIGN)
        val carol = SigFixtures.certificate("Carol", SigFixtures.rsaKeys(), ca, ca = false, serial = 3)
        val result = validate(carol, listOf(carol, ca, root))
        assertFalse(result.isTrusted)
        assertTrue(assertNotNull(result.detail).contains("does not allow certificate signing"), result.detail)
    }

    @Test
    fun aPathLengthConstraintHolds() {
        val pathLen0 = SigFixtures.certificate("PathLen0 CA", SigFixtures.rsaKeys(), root, ca = true, serial = 2, keyUsage = KEY_CERT_SIGN, pathLength = 0)
        val sub = SigFixtures.certificate("Sub CA", SigFixtures.rsaKeys(), pathLen0, ca = true, serial = 3, keyUsage = KEY_CERT_SIGN)
        val dave = SigFixtures.certificate("Dave", SigFixtures.rsaKeys(), sub, ca = false, serial = 4)
        val result = validate(dave, listOf(dave, sub, pathLen0, root))
        assertFalse(result.isTrusted)
        assertTrue(assertNotNull(result.detail).contains("PathLen0 CA allows 0 CAs below it"), result.detail)
        // The same constraint lets a signer sit directly below it.
        val erin = SigFixtures.certificate("Erin", SigFixtures.rsaKeys(), pathLen0, ca = false, serial = 5)
        assertTrue(validate(erin, listOf(erin, pathLen0, root)).isTrusted)
    }

    @Test
    fun anUnknownCriticalExtensionStopsThePath() {
        val unknown = SigFixtures.seq(SigFixtures.oid("1.3.6.1.4.1.99999.1"), byteArrayOf(0x01, 0x01, -1), SigFixtures.octets(byteArrayOf(0x05, 0x00)))
        val leaf = SigFixtures.certificate("Critical Leaf", SigFixtures.rsaKeys(), root, ca = false, serial = 2, extra = listOf(unknown))
        val result = validate(leaf, listOf(leaf, root))
        assertFalse(result.isTrusted)
        assertTrue(assertNotNull(result.detail).contains("critical extension 1.3.6.1.4.1.99999.1"), result.detail)
        // The same extension, not critical, changes nothing.
        val plain = SigFixtures.seq(SigFixtures.oid("1.3.6.1.4.1.99999.1"), SigFixtures.octets(byteArrayOf(0x05, 0x00)))
        val calm = SigFixtures.certificate("Calm Leaf", SigFixtures.rsaKeys(), root, ca = false, serial = 3, extra = listOf(plain))
        assertTrue(validate(calm, listOf(calm, root)).isTrusted)
    }
}
