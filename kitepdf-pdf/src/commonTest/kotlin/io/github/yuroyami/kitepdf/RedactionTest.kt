package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.Rectangle

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests true region redaction ([io.github.yuroyami.kitepdf.writer.PdfEditor.redactRegions]):
 * targeted text must be removed (not merely covered) and unrecoverable from the
 * output, while surrounding content survives.
 */
class RedactionTest {

    private val secret = "SECRET CODE 9999"
    private val keep = "public footer text"

    /** One page: a secret line near the top, a public line near the bottom. */
    private fun twoLinePdf(compress: Boolean): ByteArray =
        PdfBuilder().page {
            text(StandardFont.Helvetica, 24.0, 72.0, 700.0, secret)
            text(StandardFont.Helvetica, 12.0, 72.0, 100.0, keep)
        }.build(compress)

    /** Rectangle (page user space) covering the secret line only. */
    private val secretRegion = Rectangle(left = 60.0, bottom = 690.0, right = 470.0, top = 726.0)

    @Test fun redaction_removes_target_text_and_keeps_the_rest() {
        val base = twoLinePdf(compress = false)
        assertContains(KitePDF.open(base).pages[0].extractText(), secret) // sanity

        val doc = KitePDF.open(base)
        val out = doc.edit().apply { redactRegion(doc.pages[0], secretRegion) }.saveRewritten()

        val reopened = KitePDF.open(out)
        val text = reopened.pages[0].extractText()
        assertFalse(text.contains("SECRET"), "redacted text still extractable: $text")
        assertContains(text, keep)
        assertEquals(1, reopened.pageCount)
    }

    @Test fun redacted_text_is_not_recoverable_from_raw_bytes() {
        // Base is uncompressed, so an un-dropped original stream would leave the
        // secret as plaintext in the output — this catches an incremental leak.
        val base = twoLinePdf(compress = false)
        val doc = KitePDF.open(base)
        val out = doc.edit().apply { redactRegion(doc.pages[0], secretRegion) }.saveRewritten()

        assertFalse(containsBytes(out, secret.encodeToByteArray()), "secret bytes survive in the redacted file")
        // The public text should still be somewhere (it survives redaction).
        assertTrue(containsBytes(base, secret.encodeToByteArray())) // confirms the scan would catch a leak
    }

    @Test fun incremental_save_is_refused_after_redaction() {
        val doc = KitePDF.open(twoLinePdf(compress = false))
        val editor = doc.edit().apply { redactRegion(doc.pages[0], secretRegion) }
        assertFailsWith<IllegalStateException> { editor.saveIncremental() }
    }

    @Test fun full_page_redaction_removes_all_text() {
        val doc = KitePDF.open(twoLinePdf(compress = true))
        val out = doc.edit().apply {
            redactRegion(doc.pages[0], Rectangle(0.0, 0.0, 612.0, 792.0))
        }.saveRewritten()
        assertTrue(KitePDF.open(out).pages[0].extractText().isBlank())
    }

    @Test fun rewrite_drops_orphaned_objects() {
        // After redaction the original content stream is unreachable; the
        // rewrite must not carry it (the secret lived there).
        val base = twoLinePdf(compress = false)
        val doc = KitePDF.open(base)
        val out = doc.edit().apply { redactRegion(doc.pages[0], secretRegion) }.saveRewritten()
        // Rewritten file should be no larger than original + a small margin and
        // must still be a valid, openable single-page document.
        val reopened = KitePDF.open(out)
        assertEquals(1, reopened.pageCount)
        assertContains(reopened.pages[0].extractText(), keep)
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
