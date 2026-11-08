package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfSignatureValidation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.CmsOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chain checked against the signing time, RFC 5280, 6.1.3 (a)(2) (#634). The fixtures sign at 2026-09-28. */
class CertificateDatesTest {

    private companion object {
        val root = SigFixtures.certificate("Dates Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val current = SigFixtures.certificate("Current", SigFixtures.rsaKeys(), root, ca = false, serial = 2)
        val expired = SigFixtures.certificate("Expired", SigFixtures.rsaKeys(), root, ca = false, serial = 3, notAfter = "260101000000Z")
    }

    private fun validate(signer: SigFixtures.Identity, o: CmsOptions = CmsOptions()): PdfSignatureValidation {
        val pdf = SigFixtures.signedPdf { SigFixtures.cms(signer, listOf(signer, root), it, o = o) }
        return PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
    }

    @Test
    fun aCertificateValidAtTheSigningTimeKeepsTheChainTrusted() {
        val result = validate(current)
        assertEquals(true, result.certificatesValidAtSigning)
        assertTrue(result.isTrusted, result.detail)
    }

    @Test
    fun aCertificateThatExpiredBeforeTheSigningTimeIsNotTrusted() {
        val result = validate(expired)
        assertEquals(Status.Valid, result.status, "the signature still matches its bytes")
        assertEquals(false, result.certificatesValidAtSigning)
        assertFalse(result.isTrusted)
        assertTrue(assertNotNull(result.detail).contains("Expired was not valid at the signing time"), result.detail)
    }

    @Test
    fun aSignatureWithoutASigningTimeSaysNothing() {
        val result = validate(expired, CmsOptions(signedAttributes = false))
        assertEquals(Status.Valid, result.status, result.detail)
        assertNull(result.signedTime)
        assertNull(result.certificatesValidAtSigning)
        assertTrue(result.isTrusted)
    }
}
