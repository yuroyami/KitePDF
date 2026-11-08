package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteWrongPasswordException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Encrypted PDFs that another program wrote: qpdf's RC4-128, AES-128 and AES-256 handlers, with
 * a classic cross-reference and with object streams, unencrypted metadata, and an owner password
 * over an empty user password (#908). Every other encrypted file the tests open was made by
 * KitePDF or by hand, so #793 and #796 passed every gate.
 */
class ForeignEncryptionTest {

    /** Runs [check] on each fixture and fails once, naming every fixture that failed and why. */
    private fun eachFixture(check: (EncryptedFixtures.Fixture) -> Unit) {
        val failures = EncryptedFixtures.all.mapNotNull { fixture ->
            runCatching { check(fixture) }.exceptionOrNull()?.let { "${fixture.name}: ${it::class.simpleName}: ${it.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun every_fixture_opens_with_its_user_password_and_reads_its_text_and_metadata() = eachFixture { fixture ->
        val doc = PdfDocument.open(fixture.bytes, fixture.userPassword)
        assertTrue(doc.isEncrypted && doc.isAuthenticated, "authenticated")
        assertEquals(1, doc.pageCount)
        assertContains(doc.pages[0].extractText(), fixture.name, message = "the page text")
        assertEquals(fixture.name, doc.info.title, "the /Info title, a string the handler decrypts")
        assertContains(doc.xmpMetadataXml.orEmpty(), fixture.name, message = "the XMP metadata")
    }

    @Test
    fun every_fixture_opens_with_its_owner_password() = eachFixture { fixture ->
        val doc = PdfDocument.open(fixture.bytes, "owner")
        assertTrue(doc.isAuthenticated, "the owner password authenticates")
        assertContains(doc.pages[0].extractText(), fixture.name)
    }

    @Test
    fun a_wrong_password_is_told_apart_from_a_damaged_file() {
        for (fixture in EncryptedFixtures.all.filter { it.userPassword.isNotEmpty() }) {
            assertFailsWith<KiteWrongPasswordException>(fixture.name) { PdfDocument.open(fixture.bytes, "wrong") }
        }
    }

    @Test
    fun the_set_covers_each_handler_with_both_cross_reference_kinds() {
        val kinds = EncryptedFixtures.all.map { it.revision to it.objectStreams }.toSet()
        for (revision in listOf(3, 4, 6)) for (streams in listOf(false, true)) assertTrue(revision to streams in kinds, "R$revision, object streams $streams")
        assertTrue(EncryptedFixtures.all.any { !it.encryptsMetadata })
        assertTrue(EncryptedFixtures.all.any { it.userPassword.isEmpty() })
        assertTrue(EncryptedFixtures.all.any { "identity streams" in it.name }, "a file whose streams are left plain (#796)")
    }
}
