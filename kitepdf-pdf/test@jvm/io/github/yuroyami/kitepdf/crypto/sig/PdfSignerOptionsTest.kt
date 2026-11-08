package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDate
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfStream
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What PdfSigner writes about a signature and how its widget looks (ISO 32000-1, 12.8.1 and 12.7.4.5; #922). */
class PdfSignerOptionsTest {

    private companion object {
        val root = SigFixtures.certificate("Options Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val signer = SigFixtures.certificate("Options Signer", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 2)
        val when_ = PdfDate(2026, 9, 28, 12, 0, 0, 'Z', 0, 0)
    }

    private fun plain(): ByteArray = PdfBuilder().page(300.0, 300.0) { text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Contract") }.build()

    private fun sign(pdf: ByteArray, field: String, rect: KiteRectangle?, options: PdfSigner.Options): ByteArray {
        val doc = PdfDocument.open(pdf)
        val signer = PdfSigner(doc, doc.edit(), placeholderSize = 8192)
        signer.prepareSignature(field, rect, 0, options)
        val target = signer.saveForSigning()
        val signed = SigFixtures.concat(listOf(target.bytes.copyOfRange(0, target.byteRange[1]), target.bytes.copyOfRange(target.byteRange[2], target.bytes.size)))
        return PdfSigner.embedSignature(target.bytes, target.byteRange, SigFixtures.cms(PdfSignerOptionsTest.signer, listOf(PdfSignerOptionsTest.signer, root), signed))
    }

    private val options = PdfSigner.Options(name = "Ada Lovelace", reason = "I approve", location = "London", contactInfo = "ada@example.org", signingTime = when_)

    @Test
    fun theEntriesReadBack() {
        val out = sign(plain(), "Approval", null, options)
        val signature = PdfDocument.open(out).signatures.single()
        assertEquals("Ada Lovelace", signature.name)
        assertEquals("I approve", signature.reason)
        assertEquals("London", signature.location)
        assertEquals("ada@example.org", signature.contactInfo)
        assertEquals(when_, signature.signingTime)
        assertEquals(Status.Valid, signature.validate(listOf(root.cert)).status)
        val widget = PdfDocument.open(out).formFields.single { it.fullyQualifiedName == "Approval" }
        assertNull(widget.fieldDict["AP"], "an invisible signature has no appearance")
    }

    @Test
    fun aVisibleSignatureDrawsTheDefaultAppearance() {
        val out = sign(plain(), "Approval", KiteRectangle(20.0, 20.0, 220.0, 80.0), options)
        val doc = PdfDocument.open(out)
        val field = doc.formFields.single { it.fullyQualifiedName == "Approval" }
        val look = assertNotNull(field.fieldDict.getDict("AP", doc)?.get("N")?.resolve(doc) as? PdfStream)
        val content = FilterChain.decode(look).decodeToString()
        assertContains(content, "Digitally signed by Ada Lovelace")
        assertContains(content, "Reason: I approve")
        assertContains(content, "Date: 2026-09-28 12:00:00 Z")
        assertEquals(Status.Valid, doc.signatures.single().validate(listOf(root.cert)).status)
    }

    @Test
    fun anAppearanceOfTheAppsOwn() {
        val custom = PdfSigner.Options(name = "Ada", appearance = { w, h -> setFillRgb(0.0, 0.0, 1.0); rectangle(0.0, 0.0, w, h / 2); fill() })
        val out = sign(plain(), "Approval", KiteRectangle(20.0, 20.0, 120.0, 60.0), custom)
        val doc = PdfDocument.open(out)
        val look = doc.formFields.single { it.fullyQualifiedName == "Approval" }.fieldDict.getDict("AP", doc)?.get("N")?.resolve(doc) as PdfStream
        val content = FilterChain.decode(look).decodeToString()
        assertContains(content, "0 0 100 20 re")
    }

    @Test
    fun aDocumentWithAnUnfilledPlaceholderCanBeSignedAgain() {
        // The first saveForSigning leaves its placeholder, never filled, in the file.
        val doc = PdfDocument.open(plain())
        val abandoned = PdfSigner(doc, doc.edit(), placeholderSize = 8192).apply { prepareSignature("Abandoned") }.saveForSigning().bytes
        val out = sign(abandoned, "Second", null, PdfSigner.Options(name = "Second signer"))
        val second = PdfDocument.open(out).signatures.single { it.field.fullyQualifiedName == "Second" }
        assertEquals(Status.Valid, second.validate(listOf(root.cert)).status)
    }

    @Test
    fun popplerReadsTheSignature() {
        assumeTrue("Poppler's pdfsig is not installed", runCatching { ProcessBuilder("pdfsig", "-v").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        val file = File.createTempFile("signed", ".pdf").apply { writeBytes(sign(plain(), "Approval", null, options)); deleteOnExit() }
        val process = ProcessBuilder("pdfsig", "-nocert", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor(30, TimeUnit.SECONDS)
        // This Poppler prints the field and the signer but not /Reason or /Location.
        assertContains(output, "Signature Field Name: Approval")
        assertContains(output, "Signer Certificate Common Name: Options Signer")
        assertTrue("Reason" !in output || "I approve" in output, output)
    }
}
