package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfSignatureValidation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.CmsOptions
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.PdfOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a signer's certificate may be used for (#1000): RFC 5280, 4.2.1.3 for a signature, and
 * RFC 3161, 2.3 for a document timestamp. These are the fixtures of the issue, which
 * `openssl verify -purpose` refuses.
 */
class CertificatePurposeTest {

    private companion object {
        val root = SigFixtures.certificate("Purpose Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)

        fun eku(critical: Boolean, vararg oids: String): ByteArray {
            val list = SigFixtures.seq(*oids.map { SigFixtures.oid(it) }.toTypedArray())
            return if (critical) SigFixtures.seq(SigFixtures.oid("2.5.29.37"), byteArrayOf(0x01, 0x01, -1), SigFixtures.octets(list))
            else SigFixtures.seq(SigFixtures.oid("2.5.29.37"), SigFixtures.octets(list))
        }

        const val TIME_STAMPING = "1.3.6.1.5.5.7.3.8"
        val tsa = SigFixtures.certificate("Real TSA", SigFixtures.rsaKeys(), root, ca = false, serial = 2, keyUsage = 0x80, extra = listOf(eku(true, TIME_STAMPING)))
        val alice = SigFixtures.certificate("Alice", SigFixtures.rsaKeys(), root, ca = false, serial = 3, keyUsage = 0xC0)
        val encryptionOnly = SigFixtures.certificate("Encryption Only", SigFixtures.rsaKeys(), root, ca = false, serial = 4, keyUsage = 0x20)
        val loose = SigFixtures.certificate("Loose TSA", SigFixtures.rsaKeys(), root, ca = false, serial = 5, keyUsage = 0x80, extra = listOf(eku(false, TIME_STAMPING)))
    }

    private fun timestamp(by: SigFixtures.Identity, genTime: String): PdfSignatureValidation {
        val pdf = SigFixtures.signedPdf(PdfOptions(subFilter = "ETSI.RFC3161", type = "DocTimeStamp")) {
            SigFixtures.cms(by, listOf(by, root), SigFixtures.tstInfo(it, genTime = genTime), encapsulate = true, o = CmsOptions(contentType = CmsSignedData.TST_INFO))
        }
        val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, result.detail)
        return result
    }

    @Test
    fun aTimeStampingAuthorityMakesATrustedTimestamp() {
        val result = timestamp(tsa, "20260928120000Z")
        assertTrue(result.isTrusted, result.detail)
        assertEquals(2026, result.signedTime?.year)
    }

    @Test
    fun anyOtherCertificateMakesAnUntrustedTimestamp() {
        // Alice would back-date the document to 2020, a time no authority vouches for.
        val result = timestamp(alice, "20250601000000Z")
        assertFalse(result.isTrusted)
        assertTrue(assertNotNull(result.detail).contains("Alice is not a time stamping authority"), result.detail)
        // The usage must be in a critical extension, as RFC 3161 asks.
        assertFalse(timestamp(loose, "20260928120000Z").isTrusted)
    }

    @Test
    fun aKeyThatMayNotSignMakesAnUntrustedSignature() {
        val pdf = SigFixtures.signedPdf { SigFixtures.cms(encryptionOnly, listOf(encryptionOnly, root), it) }
        val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status)
        assertFalse(result.isTrusted)
        assertTrue(assertNotNull(result.detail).contains("does not allow signing"), result.detail)
        // Alice's key usage allows it.
        val signed = SigFixtures.signedPdf { SigFixtures.cms(alice, listOf(alice, root), it) }
        assertTrue(PdfDocument.open(signed).signatures.single().validate(listOf(root.cert)).isTrusted)
    }
}
