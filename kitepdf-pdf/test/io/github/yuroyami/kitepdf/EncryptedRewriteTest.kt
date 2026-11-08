package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteWrongPasswordException
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A rewrite of an encrypted document keeps its encryption, its password and its permissions (ISO 32000-1, 7.6; #1006). */
class EncryptedRewriteTest {

    private val restricted = PdfPermissions.allowAll.copy(canModifyContents = false, canCopyContents = false)

    private val pdf = PdfBuilder()
        .encrypt("user", "owner", restricted, random = Random(3))
        .page(300.0, 300.0) {
            text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Secret page text")
            text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "Account 12345678")
        }
        .build(compress = true)

    private fun open() = PdfDocument.open(pdf, password = "user".encodeToByteArray())

    private fun assertStillLocked(out: ByteArray, expectedText: List<String>, goneText: List<String> = emptyList()) {
        assertFailsWith<KiteWrongPasswordException> { PdfDocument.open(out) }
        val doc = PdfDocument.open(out, password = "user".encodeToByteArray())
        assertTrue(doc.isEncrypted)
        assertFalse(doc.permissions.canModifyContents, "the permissions go with the encryption")
        assertFalse(doc.permissions.canCopyContents)
        val text = doc.pages[0].extractText()
        for (t in expectedText) assertContains(text, t)
        for (t in goneText) assertFalse(t in text, t)
        val raw = out.decodeToString()
        for (t in expectedText + goneText) assertFalse(t in raw, "no text in clear: $t")
        assertTrue("/Encrypt" in raw)
        PdfDocument.open(out, password = "owner".encodeToByteArray()).pages[0].extractText().let { owner -> for (t in expectedText) assertContains(owner, t) }
    }

    @Test
    fun a_rewrite_keeps_the_encryption() {
        val doc = open()
        val out = doc.edit(Random(5)).apply { setInfo(title = "Reviewed") }.saveRewritten()
        assertStillLocked(out, listOf("Secret page text", "Account 12345678"))
        assertTrue(PdfDocument.open(out, password = "user".encodeToByteArray()).metadata.title == "Reviewed")
    }

    @Test
    fun a_rewrite_with_object_streams_keeps_the_encryption() {
        val doc = open()
        val out = doc.edit(Random(5)).apply { setInfo(title = "Reviewed") }.saveRewritten(useObjectStreams = true)
        assertTrue("/ObjStm" in out.decodeToString())
        assertStillLocked(out, listOf("Secret page text", "Account 12345678"))
        assertTrue(PdfDocument.open(out, password = "user".encodeToByteArray()).metadata.title == "Reviewed")
    }

    @Test
    fun a_redacted_copy_stays_encrypted() {
        val doc = open()
        val out = doc.edit(Random(5)).apply { redactRegion(doc.pages[0], KiteRectangle(10.0, 140.0, 200.0, 170.0)) }.saveRewritten()
        assertStillLocked(out, listOf("Secret page text"), goneText = listOf("Account 12345678"))
    }

    @Test
    fun a_decrypted_copy_is_asked_for() {
        val doc = open()
        val out = doc.edit(Random(5)).saveRewritten(useObjectStreams = false, keepEncryption = false)
        val plain = PdfDocument.open(out)
        assertFalse(plain.isEncrypted)
        assertContains(plain.pages[0].extractText(), "Account 12345678")
    }
}
