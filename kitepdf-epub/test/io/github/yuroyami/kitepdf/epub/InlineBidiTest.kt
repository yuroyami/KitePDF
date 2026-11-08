package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inline direction, `dir="auto"`, `<bdi>`, `<bdo>` and `unicode-bidi` change the order inside a line (#932). */
class InlineBidiTest {

    private fun page(body: String) =
        EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 640.0)).pages[0]

    /** The chars of the page's first line, left to right as they stand. */
    private fun visual(body: String): String {
        val line = page(body).textContent().blocks.first().lines.first()
        return line.text.indices.sortedBy { minOf(line.charEdges[it], line.charEdges[it + 1]) }.map { line.text[it] }.joinToString("")
    }

    @Test
    fun bdo_overrides_its_content() {
        assertEquals("abc ZYX def", visual("""<p>abc <bdo dir="rtl">XYZ</bdo> def</p>"""))
    }

    @Test
    fun unicode_bidi_override_on_a_span() {
        assertEquals("abc ZYX def", visual("""<p>abc <span style="unicode-bidi:bidi-override;direction:rtl">XYZ</span> def</p>"""))
    }

    @Test
    fun dir_auto_takes_the_direction_of_its_text() {
        val auto = page("""<p dir="auto">שלום</p>""").textContent().blocks.first().lines.first().bounds
        val rtl = page("""<p dir="rtl">שלום</p>""").textContent().blocks.first().lines.first().bounds
        assertEquals(rtl.left, auto.left, 0.5)
        assertTrue(auto.left > 200.0, "the line stands at the right: $auto")
    }

    @Test
    fun bdi_keeps_a_number_out_of_the_right_to_left_word() {
        // In a plain span the 5 joins the Hebrew run and stands left of it; in a bdi it stays after.
        val isolated = visual("""<p>Price: <bdi>שלום</bdi> 5</p>""")
        assertTrue(isolated.endsWith(" 5"), isolated)
        val plain = visual("""<p>Price: <span>שלום</span> 5</p>""")
        assertTrue(!plain.endsWith(" 5"), plain)
    }
}
