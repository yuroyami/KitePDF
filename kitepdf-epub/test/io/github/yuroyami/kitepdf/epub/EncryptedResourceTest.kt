package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A resource that `META-INF/encryption.xml` lists under an algorithm KitePDF cannot undo is
 * ciphertext, so the book does not hold it, and a spine encrypted so is a book under DRM
 * (OCF 3.3, 4.4 and 4.5; #875). The font obfuscations still undo (#22).
 */
class EncryptedResourceTest {

    private fun encryption(vararg uris: Pair<String, String>) = """
        <?xml version="1.0"?>
        <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
        ${uris.joinToString("\n") { (uri, algo) -> """<enc:EncryptedData><enc:EncryptionMethod Algorithm="$algo"/><enc:CipherData><enc:CipherReference URI="$uri"/></enc:CipherData></enc:EncryptedData>""" }}
        </encryption>
    """.trimIndent().encodeToByteArray()

    @Test
    fun an_encrypted_chapter_makes_open_say_the_book_is_encrypted() {
        val cipher = Random(875).nextBytes(600)
        val bytes = EpubFixtures.epub(
            "<p>never shown</p>",
            chapterBytes = cipher,
            unlisted = listOf("META-INF/encryption.xml" to encryption("OEBPS/chapter1.xhtml" to AES128)),
        )
        val e = assertFailsWith<EpubFormatException> { EpubDocument.open(bytes) }
        assertTrue("encrypted" in e.message.orEmpty(), e.message)
        assertTrue("OEBPS/chapter1.xhtml" in e.message.orEmpty(), e.message)
    }

    @Test
    fun an_encrypted_image_and_style_sheet_are_not_read() {
        val bmp = EpubFixtures.bmp2x1()
        val css = "p { font-size: 40px }".encodeToByteArray()
        val body = """<body><link rel="stylesheet" href="big.css"/><p>Text</p><img src="red.bmp" alt="red"/></body>"""
        fun open(vararg encrypted: Pair<String, String>) = EpubDocument.open(
            EpubFixtures.epub(
                body,
                extraEntries = listOf("OEBPS/red.bmp" to bmp, "OEBPS/big.css" to css),
                unlisted = if (encrypted.isEmpty()) emptyList() else listOf("META-INF/encryption.xml" to encryption(*encrypted)),
            ),
        )
        fun calls(doc: EpubDocument) = doc.pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
        fun size(doc: EpubDocument) = calls(doc).filterIsInstance<RecordingCanvas.Call.Glyphs>()
            .filter { it.text.isNotBlank() }.map { it.fontSize * it.textToDevice.d }.distinct().single()

        val plain = open()
        val locked = open("OEBPS/red.bmp" to AES128, "OEBPS/big.css" to AES128)
        assertEquals(1, calls(plain).filterIsInstance<RecordingCanvas.Call.Image>().size)
        assertEquals(0, calls(locked).filterIsInstance<RecordingCanvas.Call.Image>().size)
        assertNull(locked.resource("OEBPS/red.bmp"))
        assertTrue(size(plain) > size(locked) * 2, "plain ${size(plain)}, locked ${size(locked)}")

        // A font obfuscation is no encryption: the file still reads.
        val obfuscated = open("OEBPS/red.bmp" to Deobfuscate.IDPF_ALGORITHM)
        assertEquals(1, calls(obfuscated).filterIsInstance<RecordingCanvas.Call.Image>().size)
    }

    private companion object {
        const val AES128 = "http://www.w3.org/2001/04/xmlenc#aes128-cbc"
    }
}
