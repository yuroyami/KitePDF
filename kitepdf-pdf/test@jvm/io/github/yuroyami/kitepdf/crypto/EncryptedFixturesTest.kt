package io.github.yuroyami.kitepdf.crypto

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteWrongPasswordException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Encrypted files that another program wrote: qpdf's RC4-128 (R3), AES-128 (R4) and AES-256 (R6)
 * handlers, each with a classic cross-reference and again with a cross-reference stream and object
 * streams, a file whose metadata stays in clear, and one with only an owner password (#908).
 * `testResources@jvm/encrypted/make.py` writes them.
 */
class EncryptedFixturesTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(EncryptedFixturesTest::class.java.getResourceAsStream("/encrypted/$name")) { "no fixture $name" }.readBytes()

    private fun assertReads(doc: PdfDocument, name: String) {
        assertTrue(doc.isEncrypted, "$name: not encrypted")
        val text = assertNotNull(doc.pages[0].textContent(), "$name: no text").plainText
        assertTrue("Encrypted page one" in text, "$name: the page reads \"$text\"")
        assertEquals("Encrypted fixture", doc.metadata.title, "$name: the title")
    }

    private val passworded = listOf(
        "rc4-128.pdf", "rc4-128-objstm.pdf", "aes-128.pdf", "aes-128-objstm.pdf", "aes-256.pdf", "aes-256-objstm.pdf",
        "aes-128-cleartext-metadata.pdf",
    )

    @Test
    fun each_handler_opens_with_the_user_and_the_owner_password() {
        for (name in passworded) {
            assertReads(PdfDocument.open(fixture(name), "user"), "$name with the user password")
            assertReads(PdfDocument.open(fixture(name), "owner"), "$name with the owner password")
        }
    }

    @Test
    fun a_wrong_password_is_refused() {
        for (name in passworded) {
            assertFailsWith<KiteWrongPasswordException>(name) { PdfDocument.open(fixture(name), "wrong") }
        }
    }

    @Test
    fun a_file_with_only_an_owner_password_opens_without_one() {
        assertReads(PdfDocument.open(fixture("owner-only.pdf")), "owner-only.pdf")
    }

    /** With `/EncryptMetadata false`, the XMP packet is in clear and the rest is encrypted (ISO 32000-1, 7.6.3.2, Table 21). */
    @Test
    fun cleartext_metadata_reads_back() {
        val doc = PdfDocument.open(fixture("aes-128-cleartext-metadata.pdf"), "user")
        val xmp = assertNotNull(doc.xmpMetadataXml, "no XMP")
        assertTrue("Encrypted fixture" in xmp, "the XMP packet reads: ${xmp.take(200)}")
    }
}
