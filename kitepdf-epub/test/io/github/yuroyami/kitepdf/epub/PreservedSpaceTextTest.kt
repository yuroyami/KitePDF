package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteStructuredText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text of a line in preserved white space holds every space the layout kept, leading ones too (#576). */
class PreservedSpaceTextTest {

    private fun text(body: String): KiteStructuredText =
        EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 2000.0)).pages[0].textContent()

    private fun lines(body: String): List<String> = text(body).blocks.flatMap { b -> b.lines.map { it.text } }

    @Test
    fun repeated_spaces_in_pre_stay_in_the_line_text() {
        assertEquals(listOf("a  b"), lines("<pre>a  b</pre>"))
        assertEquals(listOf("x = 1    // one"), lines("<pre>x = 1    // one</pre>"))
    }

    @Test
    fun leading_spaces_indent_the_line_text() {
        assertEquals(listOf("    x"), lines("""<p style="white-space:pre">    x</p>"""))
        assertEquals(listOf("fun f() {", "    return 1", "}"), lines("<pre>fun f() {\n    return 1\n}</pre>"))
    }

    @Test
    fun pre_wrap_keeps_its_spaces_too() {
        assertEquals(listOf("  a   b"), lines("""<p style="white-space:pre-wrap">  a   b</p>"""))
    }

    @Test
    fun copied_code_keeps_its_indentation() {
        val code = "if (a) {\n  if (b) {\n    c()\n  }\n}"
        val t = text("<pre>$code</pre>")
        assertEquals(code, t.copyText(0, t.charCount - 1))
    }

    @Test
    fun each_space_spans_its_share_of_the_gap() {
        val line = text("<pre>    x</pre>").blocks.single().lines.single()
        val edges = line.charEdges
        for (k in 1 until edges.size) assertTrue(edges[k] > edges[k - 1], "${edges.toList()}")
        val space = edges[1] - edges[0]
        assertTrue(space > 3.0, "a space spans $space")
        assertEquals(edges[4] - edges[0], 4 * space, 0.01)
    }

    @Test
    fun collapsed_spaces_still_read_as_one() {
        assertEquals(listOf("a b c"), lines("<p>a   b \n  c</p>"))
        assertEquals(listOf("a b"), lines("<p>   a b</p>"))
    }
}
