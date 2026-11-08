package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfRevocation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.CmsOptions
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfSigner
import io.github.yuroyami.kitepdf.writer.StandardFont
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A PAdES B-T signature, then a document security store, then a document timestamp, all on one file (#924). */
class PadesFlowTest {

    private companion object {
        val root = SigFixtures.certificate("PAdES Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val tsa = SigFixtures.certificate(
            "PAdES TSA", SigFixtures.rsaKeys(), root, ca = false, serial = 2, keyUsage = 0x80,
            extra = listOf(SigFixtures.seq(SigFixtures.oid("2.5.29.37"), byteArrayOf(0x01, 0x01, -1), SigFixtures.octets(SigFixtures.seq(SigFixtures.oid("1.3.6.1.5.5.7.3.8"))))),
        )
        val signer = SigFixtures.certificate("PAdES Signer", SigFixtures.rsaKeys(), root, ca = false, serial = 3)

        /** The bytes a [target] covers. */
        fun covered(target: PdfSigner.SigningTarget): ByteArray =
            target.bytes.copyOfRange(0, target.byteRange[1]) + target.bytes.copyOfRange(target.byteRange[2], target.bytes.size)

        fun stamp(data: ByteArray): ByteArray =
            SigFixtures.cms(tsa, listOf(tsa, root), SigFixtures.tstInfo(data), encapsulate = true, o = CmsOptions(contentType = CmsSignedData.TST_INFO))

        fun pades(): ByteArray {
            val plain = PdfBuilder().page(300.0, 300.0) { text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Agreement") }.build()
            // B-T: a CAdES signature whose signer carries a signature time-stamp token.
            val doc = PdfDocument.open(plain)
            val s1 = PdfSigner(doc, doc.edit(), placeholderSize = 12_000)
            s1.prepareSignature("Signature1", null, 0, PdfSigner.Options(name = "Signer", subFilter = "ETSI.CAdES.detached"))
            val t1 = s1.saveForSigning()
            val cms = SigFixtures.cms(signer, listOf(signer, root), covered(t1), o = CmsOptions(extraAttributes = listOf(SigFixtures.essV2(signer.cert)), timestamp = { stamp(it) }))
            val bt = PdfSigner.embedSignature(t1.bytes, t1.byteRange, cms)
            // B-LT: the revocation data, in a document security store of its own revision.
            val signed = PdfDocument.open(bt)
            val lt = signed.edit().apply {
                addSecurityStore(certificates = listOf(root.cert), crls = listOf(SigFixtures.crl(root, emptyList())), signatures = signed.signatures)
            }.saveIncremental()
            // B-LTA: a document timestamp over all of it.
            val stored = PdfDocument.open(lt)
            val s2 = PdfSigner(stored, stored.edit(), placeholderSize = 12_000)
            s2.prepareDocumentTimestamp()
            val t2 = s2.saveForSigning()
            return PdfSigner.embedSignature(t2.bytes, t2.byteRange, stamp(covered(t2)))
        }
    }

    @Test
    fun eachRevisionValidates() {
        val doc = PdfDocument.open(pades())
        val (signature, timestamp) = doc.signatures.partition { !it.isDocumentTimestamp }.let { it.first.single() to it.second.single() }
        assertEquals("ETSI.CAdES.detached", signature.subFilter)
        val first = signature.validate(listOf(root.cert))
        assertEquals(Status.Valid, first.status, first.detail)
        assertTrue(first.isTrusted, first.detail)
        assertTrue(assertNotNull(first.timestamp).isTrusted, first.timestamp?.detail)
        assertEquals(PdfRevocation.Good, first.signer?.revocation, "the CRL in the store")
        assertTrue(first.areChangesPermitted, "the store and the timestamp: ${first.changes}")

        assertEquals("ETSI.RFC3161", timestamp.subFilter)
        val last = timestamp.validate(listOf(root.cert))
        assertEquals(Status.Valid, last.status, last.detail)
        assertTrue(last.isTrusted, last.detail)
        assertTrue(!last.isModifiedAfterSigning)

        val dss = assertNotNull(doc.catalog.getDict("DSS", doc))
        val vri = assertNotNull(dss.getDict("VRI", doc))
        val key = Sha1.hash(signature.contents!!).joinToString("") { "%02X".format(it) }
        assertNotNull(vri.getDict(key, doc), "a /VRI entry keyed by the SHA-1 of /Contents")
    }

    @Test
    fun popplerValidatesTheCadesSignature() {
        assumeTrue("Poppler's pdfsig is not installed", runCatching { ProcessBuilder("pdfsig", "-v").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        val file = File.createTempFile("pades", ".pdf").apply { writeBytes(pades()); deleteOnExit() }
        val process = ProcessBuilder("pdfsig", "-nocert", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor(30, TimeUnit.SECONDS)
        // Poppler 24 reports "Unimplemented Feature" for a document timestamp, so it is only listed.
        assertContains(output, "Signature Type: ETSI.CAdES.detached")
        assertContains(output, "Signature Validation: Signature is Valid.")
        assertContains(output, "Signature Field Name: DocTimeStamp")
    }
}
