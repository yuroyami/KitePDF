package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfSigner
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-84 oracle half: a document signed over the scaffold's ByteRange with a
 * throwaway JVM keypair (java.security lives in THIS test, not the library)
 * must open and render in mutool and reopen in KitePDF with the field
 * present. External signature validators are a manual step.
 */
class SignedPdfOracleTest {

    @Test
    fun mutool_renders_the_signed_document() {
        assumeTrue("mutool not found, skipping oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        val original = PdfBuilder()
            .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Signed document") }
            .build()
        val doc = PdfDocument.open(original)
        val editor = doc.edit()
        val signer = PdfSigner(doc, editor, placeholderSize = 8192)
        signer.prepareSignature("Signature1")
        val target = signer.saveForSigning()

        // Sign the two ByteRange spans with a throwaway RSA key. The raw
        // PKCS#1 signature stands in for a full CMS SignedData blob; the
        // scaffold does not care what DER the application supplies.
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(target.bytes, target.byteRange[0], target.byteRange[1])
            update(target.bytes, target.byteRange[2], target.byteRange[3])
        }
        val signed = PdfSigner.embedSignature(target.bytes, target.byteRange, sig.sign())

        val pdf = File.createTempFile("kite-signed", ".pdf").apply {
            deleteOnExit()
            writeBytes(signed)
        }
        val png = File.createTempFile("kite-signed", ".png").apply { deleteOnExit() }
        val proc = ProcessBuilder(tool.absolutePath, "draw", "-o", png.absolutePath, "-r", "72", pdf.absolutePath, "1")
            .redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "mutool timed out")
        assertEquals(0, proc.exitValue(), "mutool draw failed: $out")
        assertTrue(png.length() > 0, "rendered PNG is empty")

        val re = PdfDocument.open(signed)
        val field = re.formFields.firstOrNull { it.fullyQualifiedName == "Signature1" }
        assertNotNull(field, "signature field present after reopen")
        assertEquals(PdfFormField.FieldType.Signature, field.type)
    }
}
