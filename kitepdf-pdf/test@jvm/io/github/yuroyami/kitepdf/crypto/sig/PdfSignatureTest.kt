package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfSignatureValidation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.CmsOptions
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.PdfOptions
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Signed PDFs made with the JDK's providers, checked by [io.github.yuroyami.kitepdf.PdfSignature.validate] (#203). */
class PdfSignatureTest {

    // Key generation is the slow part, so every test shares one set of keys.
    private companion object {
        val root = SigFixtures.certificate("KitePDF Test Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val signer = SigFixtures.certificate("KitePDF Test Signer", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 0x1234_5678_9ABCL)
        val ecSigner = SigFixtures.certificate("KitePDF EC Signer", SigFixtures.ecKeys(), issuer = root, ca = false, serial = 77)
    }

    private fun validate(pdf: ByteArray, anchors: List<ByteArray> = listOf(root.cert)): PdfSignatureValidation {
        val signatures = PdfDocument.open(pdf).signatures
        assertEquals(1, signatures.size)
        return signatures.single().validate(anchors)
    }

    private fun detached(identity: SigFixtures.Identity = signer, certs: List<SigFixtures.Identity> = listOf(identity, root), o: CmsOptions = CmsOptions(), pdf: PdfOptions = PdfOptions()) =
        SigFixtures.signedPdf(pdf) { SigFixtures.cms(identity, certs, it, o = o) }

    @Test
    fun theFixtureCertificatesAreRealCertificates() {
        // The JDK parses and checks the hand-built certificates, so a failure below is KitePDF's.
        val factory = CertificateFactory.getInstance("X.509")
        val rootCert = factory.generateCertificate(root.cert.inputStream()) as X509Certificate
        val signerCert = factory.generateCertificate(signer.cert.inputStream()) as X509Certificate
        rootCert.verify(rootCert.publicKey)
        signerCert.verify(rootCert.publicKey)
        (factory.generateCertificate(ecSigner.cert.inputStream()) as X509Certificate).verify(rootCert.publicKey)
    }

    @Test
    fun aSignedDocumentIsValidAndNamesItsSigner() {
        val pdf = detached()
        val signature = PdfDocument.open(pdf).signatures.single()
        assertEquals("adbe.pkcs7.detached", signature.subFilter)
        assertEquals("KitePDF Test", signature.name)
        assertEquals("Testing", signature.reason)
        assertEquals("Here", signature.location)
        assertEquals(2026, signature.signingTime?.year)
        assertEquals("Signature1", signature.field.fullyQualifiedName)
        assertEquals(4, signature.byteRange.size)
        val result = signature.validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, result.detail)
        assertTrue(result.isValid)
        assertEquals("KitePDF Test Signer", result.signer?.commonName)
        assertEquals("C=US, O=KitePDF Tests, CN=KitePDF Test Signer", result.signer?.subject)
        assertEquals("123456789ABC", result.signer?.serialNumber)
        assertEquals(listOf("KitePDF Test Signer", "KitePDF Test Root"), result.chain.map { it.commonName })
        assertTrue(result.isTrusted)
        assertFalse(result.isModifiedAfterSigning)
        assertEquals(2026, result.signedTime?.year)
        assertEquals(9, result.signedTime?.month)
        assertEquals(2036, result.signer?.notAfter?.year)
        assertTrue(result.signer!!.encoded.contentEquals(signer.cert))
    }

    @Test
    fun trustNeedsAnAnchor() {
        val result = validate(detached(), anchors = emptyList())
        assertEquals(Status.Valid, result.status)
        assertFalse(result.isTrusted)
        assertEquals(2, result.chain.size, "the chain is still reported")
        val stranger = SigFixtures.certificate("Someone Else", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 9)
        assertFalse(validate(detached(), anchors = listOf(stranger.cert)).isTrusted)
    }

    @Test
    fun anAnchorCompletesAChainTheSignatureLeavesShort() {
        val result = validate(detached(certs = listOf(signer)))
        assertEquals(Status.Valid, result.status)
        assertEquals(listOf("KitePDF Test Signer", "KitePDF Test Root"), result.chain.map { it.commonName })
        assertTrue(result.isTrusted)
    }

    @Test
    fun theDocumentSecurityStoreCompletesAChain() {
        val result = validate(detached(certs = listOf(signer), pdf = PdfOptions(dssCerts = listOf(root.cert))), anchors = emptyList())
        assertEquals(Status.Valid, result.status)
        assertEquals(listOf("KitePDF Test Signer", "KitePDF Test Root"), result.chain.map { it.commonName })
    }

    @Test
    fun aChangedByteInsideTheSignedRangeIsADigestMismatch() {
        val pdf = detached()
        val at = String(pdf, Charsets.ISO_8859_1).indexOf("(Testing)") + 1
        pdf[at] = 'R'.code.toByte()
        val result = validate(pdf)
        assertEquals(Status.DigestMismatch, result.status)
        assertFalse(result.isTrusted, "an invalid signature is not trusted")
        assertEquals("KitePDF Test Signer", result.signer?.commonName, "the signer is still named")
    }

    @Test
    fun aRevisionAppendedAfterSigningIsReported() {
        val pdf = SigFixtures.appendRevision(detached())
        val document = PdfDocument.open(pdf)
        assertEquals("Changed after signing", document.info.title, "the appended revision is read")
        val result = document.signatures.single().validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, "the signed revision itself is intact")
        assertTrue(result.isModifiedAfterSigning)
        assertFalse(validate(detached()).isModifiedAfterSigning)
    }

    @Test
    fun oneByteAfterTheSignedRangeIsAModification() {
        val result = validate(detached() + "%".encodeToByteArray())
        assertEquals(Status.Valid, result.status)
        assertTrue(result.isModifiedAfterSigning)
        assertFalse(validate(detached() + "\r\n".encodeToByteArray()).isModifiedAfterSigning, "white space adds nothing")
    }

    @Test
    fun contentSlippedIntoTheHoleIsRefused() {
        val pdf = detached()
        val text = String(pdf, Charsets.ISO_8859_1)
        val close = text.indexOf('>', text.indexOf("/Contents <"))
        // Shorten the string and put a second /Name after it, inside the same unsigned hole.
        val injected = ">/Name (Mallory) /Pad <0"
        injected.toByteArray(Charsets.ISO_8859_1).copyInto(pdf, close - injected.length)
        val signature = PdfDocument.open(pdf).signatures.single()
        assertEquals("Mallory", signature.name, "the injected entry is what a reader sees")
        assertEquals(Status.Malformed, signature.validate(listOf(root.cert)).status)
    }

    @Test
    fun contentSlippedIntoTheHoleIsRefusedWithoutTheParsedContents() {
        // An encrypted file gives no parsed /Contents to compare, so the check of the hole must hold alone.
        val pdf = detached()
        val text = String(pdf, Charsets.ISO_8859_1)
        val close = text.indexOf('>', text.indexOf("/Contents <"))
        val injected = ">/Name (Mallory) /Pad <0"
        injected.toByteArray(Charsets.ISO_8859_1).copyInto(pdf, close - injected.length)
        val signature = PdfDocument.open(pdf).signatures.single()
        val result = SignatureValidator(pdf, signature.byteRange, signature.subFilter, null, emptyList(), emptyList(), listOf(root.cert)).validate()
        assertEquals(Status.Malformed, result.status)
    }

    @Test
    fun aByteRangeOutsideTheFileIsMalformed() {
        val pdf = detached()
        val text = String(pdf, Charsets.ISO_8859_1)
        val at = text.indexOf("/ByteRange [") + "/ByteRange [".length
        val end = text.indexOf(']', at)
        val range = text.substring(at, end).trim().split(Regex("\\s+")).map { it.toLong() }
        val forged = "0 ${range[1]} ${range[2]} ${range[3] + 10}"
        (forged + " ".repeat(end - at - forged.length)).toByteArray(Charsets.ISO_8859_1).copyInto(pdf, at)
        assertEquals(Status.Malformed, validate(pdf).status)
    }

    @Test
    fun aCertificateWithTheIssuersNameButAnotherKeyIsNoIssuer() {
        val impostor = SigFixtures.certificate("KitePDF Test Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val result = validate(detached(certs = listOf(signer)), anchors = listOf(impostor.cert))
        assertEquals(Status.Valid, result.status)
        assertEquals(listOf("KitePDF Test Signer"), result.chain.map { it.commonName })
        assertFalse(result.isTrusted)
    }

    @Test
    fun aSignatureOutsideItsOwnContentsIsMalformed() {
        // The byte range skips a decoy string that holds a valid signature, while /Contents holds another value.
        val pdf = detached(pdf = PdfOptions(holeKey = "Decoy"))
        assertEquals(Status.Malformed, validate(pdf).status)
    }

    @Test
    fun anUnsignedFirstByteIsAModification() {
        val result = validate(detached(pdf = PdfOptions(rangeStart = 1)))
        assertEquals(Status.Valid, result.status)
        assertTrue(result.isModifiedAfterSigning)
    }

    @Test
    fun aCorruptSignatureIsInvalid() {
        val result = validate(detached(o = CmsOptions(corruptSignature = true)))
        assertEquals(Status.Invalid, result.status)
        assertFalse(result.isTrusted)
    }

    @Test
    fun anEcdsaSignerIsValid() {
        val result = validate(detached(identity = ecSigner))
        assertEquals(Status.Valid, result.status, result.detail)
        assertEquals("KitePDF EC Signer", result.signer?.commonName)
        assertTrue(result.isTrusted)
    }

    @Test
    fun anEcdsaSignerWithSha384IsValid() {
        val p384 = SigFixtures.certificate("KitePDF P-384 Signer", SigFixtures.ecKeys("secp384r1"), issuer = root, ca = false, serial = 78)
        val o = CmsOptions(digestOid = SigFixtures.SHA384, digestJca = "SHA-384", ecJca = "SHA384withECDSA", ecOid = SigFixtures.ECDSA_SHA384)
        assertEquals(Status.Valid, validate(detached(identity = p384, o = o)).status)
    }

    @Test
    fun anRsaPssSignerIsValid() {
        val result = validate(detached(o = CmsOptions(pss = SigFixtures.PSS_SHA256)))
        assertEquals(Status.Valid, result.status, result.detail)
    }

    @Test
    fun anRsaPssSignerWithDefaultParametersIsValid() {
        // An empty parameter set means SHA-1, MGF1 over SHA-1 and a salt of 20 bytes (RFC 4055, 3.1).
        val o = CmsOptions(pss = SigFixtures.PSS_DEFAULTS, digestOid = SigFixtures.SHA1, digestJca = "SHA-1")
        val result = validate(detached(o = o))
        assertEquals(Status.Valid, result.status, result.detail)
    }

    @Test
    fun aSignatureWithoutSignedAttributesIsChecked() {
        assertEquals(Status.Valid, validate(detached(o = CmsOptions(signedAttributes = false))).status)
        val pdf = detached(o = CmsOptions(signedAttributes = false))
        pdf[String(pdf, Charsets.ISO_8859_1).indexOf("(Here)") + 1] = 'T'.code.toByte()
        assertEquals(Status.Invalid, validate(pdf).status, "without a digest attribute, a change breaks the signature itself")
    }

    @Test
    fun aSignerNamedByKeyIdentifierIsFound() {
        val result = validate(detached(certs = listOf(root, signer), o = CmsOptions(bySubjectKeyId = true)))
        assertEquals(Status.Valid, result.status, result.detail)
        assertEquals("KitePDF Test Signer", result.signer?.commonName)
    }

    @Test
    fun aSignatureWithoutItsCertificateIsMalformed() {
        val result = validate(detached(certs = listOf(root)))
        assertEquals(Status.Malformed, result.status)
    }

    @Test
    fun theCadesSigningCertificateAttributeIsChecked() {
        val good = CmsOptions(extraAttributes = listOf(SigFixtures.essV2(signer.cert)))
        assertEquals(Status.Valid, validate(detached(o = good, pdf = PdfOptions(subFilter = "ETSI.CAdES.detached"))).status)
        val swapped = CmsOptions(extraAttributes = listOf(SigFixtures.essV2(root.cert)))
        val result = validate(detached(o = swapped, pdf = PdfOptions(subFilter = "ETSI.CAdES.detached")))
        assertEquals(Status.Invalid, result.status)
    }

    @Test
    fun anAdbePkcs7Sha1SignatureIsChecked() {
        val o = CmsOptions(digestOid = SigFixtures.SHA1, digestJca = "SHA-1")
        val pdf = SigFixtures.signedPdf(PdfOptions(subFilter = "adbe.pkcs7.sha1")) {
            SigFixtures.cms(signer, listOf(signer, root), SigFixtures.sha("SHA-1", it), encapsulate = true, o = o)
        }
        assertEquals(Status.Valid, validate(pdf).status)
        pdf[String(pdf, Charsets.ISO_8859_1).indexOf("(Here)") + 1] = 'T'.code.toByte()
        assertEquals(Status.DigestMismatch, validate(pdf).status)
    }

    @Test
    fun anAdbeX509RsaSha1SignatureIsChecked() {
        val entries = "/Cert [<${SigFixtures.hex(signer.cert)}> <${SigFixtures.hex(root.cert)}>]"
        val pdf = SigFixtures.signedPdf(PdfOptions(subFilter = "adbe.x509.rsa_sha1", extraEntries = entries)) {
            SigFixtures.octets(signer.sign(it, "SHA1withRSA"))
        }
        val result = validate(pdf)
        assertEquals(Status.Valid, result.status, result.detail)
        assertEquals("KitePDF Test Signer", result.signer?.commonName)
        assertTrue(result.isTrusted)
        pdf[String(pdf, Charsets.ISO_8859_1).indexOf("(Here)") + 1] = 'T'.code.toByte()
        assertEquals(Status.DigestMismatch, validate(pdf).status)
        // The DigestInfo names the hash, and later writers use SHA-256.
        val sha256 = SigFixtures.signedPdf(PdfOptions(subFilter = "adbe.x509.rsa_sha1", extraEntries = entries)) {
            SigFixtures.octets(signer.sign(it, "SHA256withRSA"))
        }
        assertEquals(Status.Valid, validate(sha256).status)
    }

    @Test
    fun aDocumentTimestampIsChecked() {
        val tsa = SigFixtures.certificate("KitePDF Test Timestamps", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 3)
        val o = CmsOptions(contentType = CmsSignedData.TST_INFO)
        val pdf = SigFixtures.signedPdf(PdfOptions(subFilter = "ETSI.RFC3161", type = "DocTimeStamp")) {
            SigFixtures.cms(tsa, listOf(tsa, root), SigFixtures.tstInfo(it, genTime = "20270102030405Z"), encapsulate = true, o = o)
        }
        val signature = PdfDocument.open(pdf).signatures.single()
        assertTrue(signature.isDocumentTimestamp)
        val result = signature.validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, result.detail)
        assertEquals("KitePDF Test Timestamps", result.signer?.commonName)
        val time = assertNotNull(result.signedTime, "the time the authority states")
        assertEquals(listOf(2027, 1, 2, 3, 4, 5), listOf(time.year, time.month, time.day, time.hour, time.minute, time.second))
        pdf[String(pdf, Charsets.ISO_8859_1).indexOf("(Here)") + 1] = 'T'.code.toByte()
        assertEquals(Status.DigestMismatch, validate(pdf).status)
    }

    @Test
    fun anUnknownDigestIsUnsupported() {
        val o = CmsOptions(digestOid = "1.3.36.3.2.1") // RIPEMD-160, which KitePDF does not hash
        val pdf = SigFixtures.signedPdf { SigFixtures.cms(signer, listOf(signer, root), it, o = CmsOptions(digestOid = o.digestOid, digestJca = "SHA-256")) }
        assertEquals(Status.Unsupported, validate(pdf).status)
    }

    @Test
    fun anUnsignedFieldIsNotListed() {
        val pdf = detached()
        val text = String(pdf, Charsets.ISO_8859_1).replace("/V 5 0 R", "        ")
        assertTrue(PdfDocument.open(text.toByteArray(Charsets.ISO_8859_1)).signatures.isEmpty())
    }
}
