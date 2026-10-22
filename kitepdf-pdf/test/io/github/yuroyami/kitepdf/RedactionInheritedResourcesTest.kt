package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The renderer lets a form with no `/Resources` read the page's, so redaction
 * has to as well: otherwise the engine cannot resolve the font a form's text
 * uses, passes the run through untouched, and leaves text in a file the caller
 * believes is clean.
 */
class RedactionInheritedResourcesTest {

    @Test
    fun text_in_a_form_with_no_resources_is_still_redacted() {
        // The form shows text with /F1, a name only the PAGE resources define.
        val form = RawPdf.obj(
            6,
            "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] >>",
            "BT /F1 24 Tf 100 700 Td (SECRET) Tj ET".encodeToByteArray(),
        )
        val pdf = RawPdf.page(
            content = "q /Fm1 Do Q".encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm1 6 0 R >> >>",
            extra = listOf(form),
        )

        val doc = PdfDocument.open(pdf)
        val editor = doc.edit()
        editor.redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 300.0, 730.0))
        val out = editor.saveRewritten()

        val redacted = PdfDocument.open(out)
        val streams = buildString {
            append(redacted.pages[0].contentBytes.decodeToString())
            // Whatever the form became, its own bytes must not carry the text.
            for (n in 1L..40L) {
                val obj = redacted.resolve(PdfReference(n, 0)) as? PdfStream ?: continue
                append(runCatching { FilterChain.decode(obj).decodeToString() }.getOrDefault(""))
            }
        }
        assertFalse("SECRET" in streams, "the form's text survived redaction")
    }
}
