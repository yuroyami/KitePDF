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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The signature time-stamp token of a signer (RFC 3161, Appendix A) is read and checked (#925). */
class SignatureTimestampTest {

    private companion object {
        val root = SigFixtures.certificate("Stamp Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)

        fun eku(vararg oids: String): ByteArray =
            SigFixtures.seq(SigFixtures.oid("2.5.29.37"), byteArrayOf(0x01, 0x01, -1), SigFixtures.octets(SigFixtures.seq(*oids.map { SigFixtures.oid(it) }.toTypedArray())))

        val tsa = SigFixtures.certificate("Stamp TSA", SigFixtures.rsaKeys(), root, ca = false, serial = 2, keyUsage = 0x80, extra = listOf(eku("1.3.6.1.5.5.7.3.8")))
        val notTsa = SigFixtures.certificate("Not A TSA", SigFixtures.rsaKeys(), root, ca = false, serial = 3, keyUsage = 0x80)
        // A signer whose certificate ran out in 2026, before the claimed signing time.
        val signer = SigFixtures.certificate("Stamped Signer", SigFixtures.rsaKeys(), root, ca = false, serial = 4, notAfter = "260601000000Z")
    }

    /** A token by [by] over the signature value, stating [genTime]; [corrupt] changes one byte of the imprint. */
    private fun token(by: SigFixtures.Identity, genTime: String, corrupt: Boolean = false): (ByteArray) -> ByteArray = { signature ->
        val imprinted = if (corrupt) signature.copyOf().also { it[0] = (it[0] + 1).toByte() } else signature
        SigFixtures.cms(by, listOf(by, root), SigFixtures.tstInfo(imprinted, genTime = genTime), encapsulate = true, o = CmsOptions(contentType = CmsSignedData.TST_INFO))
    }

    private fun validate(stamp: ((ByteArray) -> ByteArray)?, signingTime: String = "260928120000Z"): PdfSignatureValidation {
        val pdf = SigFixtures.signedPdf(PdfOptions()) {
            SigFixtures.cms(signer, listOf(signer, root), it, o = CmsOptions(signingTime = signingTime, timestamp = stamp))
        }
        val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, result.detail)
        return result
    }

    @Test
    fun aValidTokenGivesItsTimeAndIsTrusted() {
        val result = validate(token(tsa, "20260301120000Z"))
        val stamp = assertNotNull(result.timestamp)
        assertTrue(stamp.isValid, stamp.detail)
        assertTrue(stamp.isTrusted, stamp.detail)
        assertEquals(2026, stamp.time?.year)
        assertEquals(3, stamp.time?.month)
        assertEquals("Stamp TSA", stamp.authority?.commonName)
        assertEquals(9, result.signedTime?.month, "the signer's claim stays as it is")
        // The certificate expired in June, after the stamped March and before the claimed September,
        // and the stamped time is what counts.
        assertEquals(true, result.certificatesValidAtSigning)
        assertTrue(result.isTrusted, result.detail)
    }

    @Test
    fun withoutATokenTheSignersClaimCounts() {
        val result = validate(null)
        assertNull(result.timestamp)
        assertEquals(false, result.certificatesValidAtSigning)
        assertFalse(result.isTrusted)
    }

    @Test
    fun aTokenOverAnotherSignatureIsInvalidAndTheSignatureStillValid() {
        val result = validate(token(tsa, "20260301120000Z", corrupt = true))
        val stamp = assertNotNull(result.timestamp)
        assertFalse(stamp.isValid)
        assertFalse(stamp.isTrusted)
        assertTrue(assertNotNull(stamp.detail).contains("another signature"), stamp.detail)
        assertEquals(Status.Valid, result.status)
        assertEquals(false, result.certificatesValidAtSigning, "an invalid token proves no time")
    }

    @Test
    fun aTokenFromACertificateThatMayNotStampTimeIsUntrusted() {
        val result = validate(token(notTsa, "20260301120000Z"))
        val stamp = assertNotNull(result.timestamp)
        assertTrue(stamp.isValid, stamp.detail)
        assertFalse(stamp.isTrusted)
        assertTrue(assertNotNull(stamp.detail).contains("not a time stamping authority"), stamp.detail)
        assertEquals(false, result.certificatesValidAtSigning)
    }
}
