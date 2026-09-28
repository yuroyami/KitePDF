package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfRevocation
import io.github.yuroyami.kitepdf.PdfSignatureValidation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.CmsOptions
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.OcspStatus
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.PdfOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Revocation from the CRLs and OCSP responses that a signed PDF carries or the caller gives (#447). */
class RevocationTest {

    private companion object {
        val root = SigFixtures.certificate("KitePDF Test Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val signer = SigFixtures.certificate("KitePDF Test Signer", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 0x5151)
        val responder = SigFixtures.certificate("KitePDF OCSP Responder", SigFixtures.ecKeys(), issuer = root, ca = false, serial = 7, ocspSigning = true)
        val plain = SigFixtures.certificate("KitePDF Not A Responder", SigFixtures.ecKeys(), issuer = root, ca = false, serial = 8)
        // The root's name on another key: its lists and responses must not count.
        val impostor = SigFixtures.certificate("KitePDF Test Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        // A responder for OCSP signing, but issued by the impostor.
        val rogue = SigFixtures.certificate("KitePDF Rogue Responder", SigFixtures.ecKeys(), issuer = impostor, ca = false, serial = 9, ocspSigning = true)
        // The root's key under another name, and a certificate of that name with the signer's serial number.
        val renamed = SigFixtures.certificate("KitePDF Other Root", root.keys, issuer = null, ca = true, serial = 2)
        val twin = SigFixtures.certificate("KitePDF Twin", SigFixtures.ecKeys(), issuer = renamed, ca = false, serial = 0x5151)
    }

    private fun validate(
        pdf: PdfOptions = PdfOptions(),
        cms: CmsOptions = CmsOptions(),
        revocationData: List<ByteArray> = emptyList(),
    ): PdfSignatureValidation {
        val bytes = SigFixtures.signedPdf(pdf) { SigFixtures.cms(signer, listOf(signer, root), it, o = cms) }
        val result = PdfDocument.open(bytes).signatures.single().validate(listOf(root.cert), revocationData)
        assertEquals(Status.Valid, result.status, result.detail)
        assertEquals(PdfRevocation.Unknown, result.chain.last().revocation, "a root has no issuer to revoke it")
        return result
    }

    @Test
    fun withoutRevocationDataNothingIsKnown() {
        assertEquals(PdfRevocation.Unknown, validate().signer?.revocation)
    }

    @Test
    fun aCrlInTheSecurityStoreRevokesTheSigner() {
        val result = validate(PdfOptions(dssCrls = listOf(SigFixtures.crl(root, listOf(signer.serial)))))
        assertEquals(PdfRevocation.Revoked, result.signer?.revocation)
        val at = result.signer?.revokedAt
        assertEquals(listOf(2026, 9, 1), listOf(at?.year, at?.month, at?.day))
    }

    @Test
    fun aCrlThatDoesNotListTheSignerSaysItIsGood() {
        val result = validate(PdfOptions(dssCrls = listOf(SigFixtures.crl(root, listOf(signer.serial + java.math.BigInteger.ONE)))))
        assertEquals(PdfRevocation.Good, result.signer?.revocation)
        assertNull(result.signer?.revokedAt)
    }

    @Test
    fun aCrlSignedByAnotherKeyDoesNotCount() {
        val forged = SigFixtures.crl(root, listOf(signer.serial), signer = impostor)
        assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssCrls = listOf(forged))).signer?.revocation)
    }

    @Test
    fun aCrlOfAnotherIssuerNameDoesNotCount() {
        val other = SigFixtures.crl(renamed, listOf(signer.serial), signer = renamed)
        assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssCrls = listOf(other))).signer?.revocation)
    }

    @Test
    fun anOcspStatusCountsOnlyForTheCertificateItNames() {
        for ((what, status) in listOf(
            "another serial number" to OcspStatus(plain, root),
            "another issuer key" to OcspStatus(signer, impostor),
            "another issuer name" to OcspStatus(twin, root),
        )) {
            val response = SigFixtures.ocsp(root, listOf(status))
            assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssOcsps = listOf(response))).signer?.revocation, what)
        }
    }

    @Test
    fun aRevocationOutweighsAGoodStatus() {
        val good = SigFixtures.ocsp(root, listOf(OcspStatus(signer, root)))
        val revoked = SigFixtures.crl(root, listOf(signer.serial))
        assertEquals(PdfRevocation.Revoked, validate(PdfOptions(dssOcsps = listOf(good), dssCrls = listOf(revoked))).signer?.revocation)
    }

    @Test
    fun anOcspResponseOfTheIssuerSaysGoodOrRevoked() {
        val good = SigFixtures.ocsp(root, listOf(OcspStatus(signer, root)))
        assertEquals(PdfRevocation.Good, validate(PdfOptions(dssOcsps = listOf(good))).signer?.revocation)
        val revoked = SigFixtures.ocsp(root, listOf(OcspStatus(signer, root, revokedAt = "20260915000000Z")), byKey = true)
        val result = validate(PdfOptions(dssOcsps = listOf(revoked)))
        assertEquals(PdfRevocation.Revoked, result.signer?.revocation)
        assertEquals(15, result.signer?.revokedAt?.day)
    }

    @Test
    fun aDelegatedResponderCountsOnlyForOcspSigning() {
        val delegated = SigFixtures.ocsp(responder, listOf(OcspStatus(signer, root)), certs = listOf(responder))
        assertEquals(PdfRevocation.Good, validate(PdfOptions(dssOcsps = listOf(delegated))).signer?.revocation)
        val notDelegated = SigFixtures.ocsp(plain, listOf(OcspStatus(signer, root)), certs = listOf(plain))
        assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssOcsps = listOf(notDelegated))).signer?.revocation)
        val forged = SigFixtures.ocsp(impostor, listOf(OcspStatus(signer, root)))
        assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssOcsps = listOf(forged))).signer?.revocation)
        val rogueResponse = SigFixtures.ocsp(rogue, listOf(OcspStatus(signer, root)), certs = listOf(rogue))
        assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssOcsps = listOf(rogueResponse))).signer?.revocation, "a responder that the issuer did not issue")
    }

    @Test
    fun theSignatureCarriesRevocationDataInItsOwnFields() {
        // A CRL and an OCSP response in the revocation field of the SignedData (RFC 5652 and RFC 5940).
        val crl = SigFixtures.crl(root, listOf(signer.serial))
        assertEquals(PdfRevocation.Revoked, validate(cms = CmsOptions(revocation = listOf(crl))).signer?.revocation)
        val other = SigFixtures.seq(SigFixtures.oid("1.3.6.1.5.5.7.16.2"), SigFixtures.ocsp(root, listOf(OcspStatus(signer, root))))
        val ocsp = byteArrayOf(0xA1.toByte()) + other.copyOfRange(1, other.size)
        assertEquals(PdfRevocation.Good, validate(cms = CmsOptions(revocation = listOf(ocsp))).signer?.revocation)
        // Acrobat's signed attribute adbe-revocationInfoArchival, with a CRL.
        val archive = SigFixtures.seq(
            SigFixtures.oid("1.2.840.113583.1.1.8"),
            SigFixtures.set(SigFixtures.seq(SigFixtures.tagged(0xA0, SigFixtures.seq(crl)))),
        )
        assertEquals(PdfRevocation.Revoked, validate(cms = CmsOptions(extraAttributes = listOf(archive))).signer?.revocation)
    }

    @Test
    fun theCallerCanGiveRevocationData() {
        val good = SigFixtures.ocsp(root, listOf(OcspStatus(signer, root)))
        assertEquals(PdfRevocation.Good, validate(revocationData = listOf(good)).signer?.revocation)
        val crl = SigFixtures.crl(root, listOf(signer.serial))
        assertEquals(PdfRevocation.Revoked, validate(revocationData = listOf(crl)).signer?.revocation)
    }
}
