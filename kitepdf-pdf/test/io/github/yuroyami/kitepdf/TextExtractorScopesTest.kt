package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PdfPage.extractText] reads the text of the form XObjects a page draws, with each form's own
 * fonts (ISO 32000-1, 8.10, #459), and puts `/ActualText` in place of the text it marks
 * (14.9.4, #468).
 */
class TextExtractorScopesTest {

    private val sentence = "Neutral lesson alpha beta gamma."

    /** A form XObject numbered [number] that draws [content], with [resources] when given. */
    private fun form(number: Int, content: String, resources: String? = "<< /Font << /F1 4 0 R >> >>") = RawPdf.obj(
        number,
        "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] ${resources?.let { "/Resources $it" } ?: ""} >>",
        content.encodeToByteArray(),
    )

    private fun page(content: String, resources: String, extra: Array<out Pair<Int, ByteArray>>) =
        KitePDF.open(RawPdf.page(content.encodeToByteArray(), resources, extra.toList())).pages[0]

    private fun fold(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun text(content: String, resources: String, vararg extra: Pair<Int, ByteArray>): String =
        fold(page(content, resources, extra).extractText())

    /** The structured text, which search and selection read. */
    private fun structured(content: String, resources: String, vararg extra: Pair<Int, ByteArray>): String =
        fold(page(content, resources, extra).textContent().plainText)

    @Test
    fun text_inside_a_form_is_page_text() {
        assertEquals(sentence, text("BT /F1 18 Tf 36 180 Td ($sentence) Tj ET", "<< /Font << /F1 4 0 R >> >>"))
        assertEquals(
            sentence,
            text("q /Fm0 Do Q", "<< /XObject << /Fm0 6 0 R >> >>", form(6, "BT /F1 18 Tf 36 180 Td ($sentence) Tj ET")),
        )
    }

    @Test
    fun a_form_reads_its_own_fonts_and_one_without_resources_reads_the_page() {
        // The form calls its Helvetica /G; the page has no /G.
        val own = form(6, "BT /G 12 Tf 36 180 Td (own fonts) Tj ET", "<< /Font << /G 4 0 R >> >>")
        assertEquals("own fonts", text("/Fm0 Do", "<< /XObject << /Fm0 6 0 R >> >>", own))
        val inherited = form(6, "BT /F1 12 Tf 36 180 Td (page fonts) Tj ET", resources = null)
        assertEquals("page fonts", text("/Fm0 Do", "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>", inherited))
    }

    @Test
    fun nested_and_repeated_forms_keep_the_order_of_the_content() {
        val inner = form(7, "BT /F1 12 Tf 36 100 Td (inner) Tj ET")
        val outer = form(
            6,
            "BT /F1 12 Tf 36 200 Td (outer) Tj ET /Fm1 Do",
            "<< /Font << /F1 4 0 R >> /XObject << /Fm1 7 0 R >> >>",
        )
        assertEquals(
            "page outer inner outer inner end",
            text(
                "BT /F1 12 Tf 36 700 Td (page) Tj ET /Fm0 Do /Fm0 Do BT /F1 12 Tf 36 50 Td (end) Tj ET",
                "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
                outer, inner,
            ),
        )
    }

    @Test
    fun a_form_that_draws_itself_stops() {
        val loop = form(6, "BT /F1 12 Tf 36 200 Td (once) Tj ET /Fm0 Do", "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>")
        assertEquals("once", text("/Fm0 Do", "<< /XObject << /Fm0 6 0 R >> >>", loop))
    }

    @Test
    fun actual_text_replaces_the_text_it_marks() {
        val resources = "<< /Font << /F1 4 0 R >> /Properties << /P0 6 0 R >> >>"
        val named = RawPdf.obj(6, "<< /ActualText <FEFF064A0651064E> >>")
        val content = "BT /F1 12 Tf 36 700 Td (before ) Tj /Span << /ActualText (fi) >> BDC (X) Tj EMC " +
            "( ) Tj /Span /P0 BDC [(Y) -300 (Z)] TJ EMC ( after) Tj ET"
        assertEquals("before fi \u064A\u0651\u064E after", text(content, resources, named))
        assertEquals("before fi \u064A\u0651\u064E after", structured(content, resources, named))
    }

    @Test
    fun the_outer_actual_text_wins_and_an_open_sequence_ends_with_its_form() {
        // Nested: the outer replacement covers the inner one too.
        val nested = "BT /F1 12 Tf 36 700 Td /Span << /ActualText (outer) >> BDC /Span << /ActualText (inner) >> BDC (x) Tj EMC EMC ET"
        assertEquals("outer", text(nested, "<< /Font << /F1 4 0 R >> >>"))
        assertEquals("outer", structured(nested, "<< /Font << /F1 4 0 R >> >>"))
        // A form that leaves its sequence open does not hide the page text after it.
        val open = form(6, "/Span << /ActualText (form) >> BDC BT /F1 12 Tf 36 200 Td (hidden) Tj ET")
        assertEquals(
            "form after",
            text("/Fm0 Do BT /F1 12 Tf 36 100 Td (after) Tj ET", "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>", open),
        )
    }
}
