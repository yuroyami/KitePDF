package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteWrongPasswordException
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The editor sets, changes and removes the password of an existing document (#927). */
class EditorPasswordTest {

    private fun plain() = PdfBuilder()
        .page(300.0, 300.0) { text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Quarterly figures") }
        .build(compress = true)

    private fun locked(permissions: PdfPermissions = PdfPermissions.allowAll) = PdfBuilder()
        .encrypt("user", "owner", permissions, random = Random(3))
        .page(300.0, 300.0) { text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Quarterly figures") }
        .build(compress = true)

    @Test
    fun a_plain_document_gets_a_password_and_permissions() {
        val doc = PdfDocument.open(plain())
        val restricted = PdfPermissions.allowAll.copy(canPrint = false, canCopyContents = false)
        val out = doc.edit().apply { encrypt("open sesame", "boss", restricted, random = Random(9)) }.saveRewritten()
        assertFailsWith<KiteWrongPasswordException> { PdfDocument.open(out) }
        val reopened = PdfDocument.open(out, password = "open sesame".encodeToByteArray())
        assertTrue(reopened.isEncrypted)
        assertFalse(reopened.permissions.canPrint)
        assertFalse(reopened.permissions.canCopyContents)
        assertTrue(reopened.permissions.canModifyContents)
        assertContains(reopened.pages[0].extractText(), "Quarterly figures")
        assertTrue(PdfDocument.open(out, password = "boss".encodeToByteArray()).isAuthenticated)
        assertFalse("Quarterly figures" in out.decodeToString())
        assertNotEquals(doc.trailer["ID"], reopened.trailer["ID"], "a new key, a new /ID")
    }

    @Test
    fun the_password_comes_off() {
        val doc = PdfDocument.open(locked(PdfPermissions.allowAll.copy(canModifyContents = false)), password = "owner".encodeToByteArray())
        val out = doc.edit(Random(1)).apply { removeEncryption() }.saveRewritten(useObjectStreams = true)
        val reopened = PdfDocument.open(out)
        assertFalse(reopened.isEncrypted)
        assertTrue(reopened.permissions.canModifyContents)
        assertContains(reopened.pages[0].extractText(), "Quarterly figures")
    }

    @Test
    fun a_changed_password_refuses_the_old_one() {
        val doc = PdfDocument.open(locked(), password = "owner".encodeToByteArray())
        val out = doc.edit(Random(1)).apply { encrypt("fresh", random = Random(2)) }.saveRewritten()
        assertFailsWith<KiteWrongPasswordException> { PdfDocument.open(out, password = "user".encodeToByteArray()) }
        assertFailsWith<KiteWrongPasswordException> { PdfDocument.open(out, password = "owner".encodeToByteArray()) }
        assertContains(PdfDocument.open(out, password = "fresh".encodeToByteArray()).pages[0].extractText(), "Quarterly figures")
    }

    @Test
    fun a_user_password_that_forbids_changes_cannot_change_the_security() {
        val doc = PdfDocument.open(locked(PdfPermissions.allowAll.copy(canModifyContents = false)), password = "user".encodeToByteArray())
        val editor = doc.edit(Random(1))
        assertFailsWith<IllegalArgumentException> { editor.removeEncryption() }
        assertFailsWith<IllegalArgumentException> { editor.encrypt("mine", random = Random(2)) }
    }

    @Test
    fun an_incremental_save_cannot_change_the_security() {
        val doc = PdfDocument.open(plain())
        val editor = doc.edit().apply { encrypt("pw", random = Random(4)) }
        val error = assertFailsWith<IllegalStateException> { editor.saveIncremental() }
        assertEquals(true, error.message?.contains("saveRewritten"))
    }
}
