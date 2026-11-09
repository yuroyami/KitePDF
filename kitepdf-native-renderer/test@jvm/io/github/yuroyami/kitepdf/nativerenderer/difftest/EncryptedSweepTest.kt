package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The differential sweep scores the encrypted fixtures of other producers like any page (#908):
 * each is in the corpus with the password that opens it, and the password reaches mutool, which
 * cannot draw the files without it.
 */
class EncryptedSweepTest {

    @Test
    fun the_sweep_holds_every_encrypted_fixture_with_its_password() {
        val entries = Corpus.encrypted()
        assertEquals(10, entries.size, entries.joinToString { it.name })
        for (e in entries) {
            val doc = e.password?.let { PdfDocument.open(e.pdf.readBytes(), it) } ?: PdfDocument.open(e.pdf.readBytes())
            assertTrue(doc.isEncrypted, e.name)
            assertEquals(1, doc.pages.size, e.name)
        }
        assertNull(entries.single { it.name == "enc-owner-only" }.password)
    }

    @Test
    fun mutool_draws_an_encrypted_fixture_only_with_its_password() {
        assumeOracle("mutool is not installed.", MuPdfOracle.available)
        val entry = Corpus.encrypted().single { it.name == "enc-aes-128" }
        assertIs<MuPdfOracle.RenderResult.Failure>(MuPdfOracle.renderDetailed(entry.pdf, 1, 36, null))
        assertIs<MuPdfOracle.RenderResult.Failure>(MuPdfOracle.renderDetailed(entry.pdf, 1, 36, "wrong"))
        assertIs<MuPdfOracle.RenderResult.Success>(MuPdfOracle.renderDetailed(entry.pdf, 1, 36, entry.password))
        assertIs<MuPdfOracle.PageCountResult.Success>(MuPdfOracle.pageCountDetailed(entry.pdf, entry.password))
    }
}
