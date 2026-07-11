package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-84: the signing scaffold. ByteRange arithmetic must cover the whole file
 * except the `/Contents` hex string exactly, embedding must not move a byte,
 * and the signed file must reopen with the signature field present.
 */
class PdfSignerTest {

    private fun baseDoc(): PdfDocument {
        val bytes = PdfBuilder()
            .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Sign me") }
            .build()
        val doc = PdfDocument.open(bytes)
        assertNotNull(doc)
        return doc
    }

    @Test
    fun byte_range_covers_everything_except_contents() {
        val doc = baseDoc()
        val editor = doc.edit()
        val signer = PdfSigner(doc, editor, placeholderSize = 4096)
        signer.prepareSignature("Sig1")
        val t = signer.saveForSigning()
        val br = t.byteRange

        assertEquals(0, br[0], "first range starts at byte 0")
        assertEquals(t.bytes.size, br[2] + br[3], "second range runs to EOF")
        assertEquals(br[1] + 2 + 2 * 4096, br[2], "the gap is exactly the hex string with delimiters")
        assertEquals('<'.code.toByte(), t.bytes[br[1]], "gap starts at the hex opener")
        assertEquals('>'.code.toByte(), t.bytes[br[2] - 1], "gap ends at the hex closer")

        // The patched /ByteRange inside the FILE matches the returned values.
        assertContains(t.bytes.decodeToString(), "/ByteRange [0 ${br[1]} ${br[2]} ${br[3]}")
    }

    @Test
    fun embedding_writes_the_cms_without_moving_bytes() {
        val doc = baseDoc()
        val editor = doc.edit()
        val signer = PdfSigner(doc, editor, placeholderSize = 1024)
        signer.prepareSignature("Signature1")
        val t = signer.saveForSigning()

        val cms = ByteArray(300) { (it % 251).toByte() }
        val signed = PdfSigner.embedSignature(t.bytes, t.byteRange, cms)
        assertEquals(t.bytes.size, signed.size, "embedding must not change the file size")
        // Every byte outside the hex payload is untouched.
        for (i in signed.indices) {
            if (i > t.byteRange[1] && i < t.byteRange[2] - 1) continue
            assertEquals(t.bytes[i], signed[i], "byte $i outside /Contents changed")
        }
        // The hex payload starts with the CMS bytes.
        val hexStart = t.byteRange[1] + 1
        assertEquals('0'.code.toByte(), t.bytes[hexStart], "placeholder was zeros")
        val firstByte = signed.decodeToString(hexStart, hexStart + 2).toInt(16)
        assertEquals(0, firstByte)
        val second = signed.decodeToString(hexStart + 2, hexStart + 4).toInt(16)
        assertEquals(1, second)

        val re = PdfDocument.open(signed)
        val field = re.formFields.firstOrNull { it.fullyQualifiedName == "Signature1" }
        assertNotNull(field, "signature field present after reopen")
        assertEquals(PdfFormField.FieldType.Signature, field.type)
    }

    @Test
    fun oversized_cms_is_refused() {
        val doc = baseDoc()
        val editor = doc.edit()
        val signer = PdfSigner(doc, editor, placeholderSize = 64)
        signer.prepareSignature("S")
        val t = signer.saveForSigning()
        val huge = ByteArray(65)
        var threw = false
        try { PdfSigner.embedSignature(t.bytes, t.byteRange, huge) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw, "a CMS larger than the placeholder must be refused")
    }
}
