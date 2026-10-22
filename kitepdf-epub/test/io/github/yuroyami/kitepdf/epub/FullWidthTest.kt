package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** `text-transform: full-width` and EPUB's `-epub-fullwidth` (CSS Text 3, 2.1, #508). */
class FullWidthTest {

    private fun text(body: String): List<String> =
        EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 640.0))
            .pages[0].textContent().blocks.map { b -> b.lines.joinToString("") { it.text } }

    @Test
    fun letters_digits_and_signs_take_their_full_width_forms() {
        assertEquals(
            listOf("\uFF41\uFF42\uFF43 \uFF11\uFF12\uFF13\uFF01", "\uFF21\uFF22\uFF23\uFFE5"),
            text("<p style='text-transform:full-width'>abc 123!</p><p style='text-transform:-epub-fullwidth'>ABC\u00A5</p>"),
        )
    }

    @Test
    fun half_width_katakana_widens_too() {
        assertEquals(listOf("\u30AB\u30BF\u30AB\u30CA\u3002"), text("<p style='text-transform:full-width'>\uFF76\uFF80\uFF76\uFF85\uFF61</p>"))
    }

    @Test
    fun it_combines_with_a_case_and_inherits() {
        assertEquals(
            listOf("\uFF21\uFF22\uFF23", "\uFF41\uFF42\uFF43", "abc"),
            text(
                """<p style="text-transform:uppercase full-width">abc</p>""" +
                    """<div style="text-transform:full-width"><p>abc</p><p style="text-transform:none">abc</p></div>""",
            ),
        )
    }

    @Test
    fun preserved_white_space_text_widens_as_well() {
        assertEquals(listOf("\uFF41\uFF42"), text("<pre style='text-transform:full-width'>a\nb</pre>").map { it.replace("\n", "") })
    }

    @Test
    fun an_unknown_keyword_leaves_the_declaration_out() {
        val inherited = text("""<div style="text-transform:uppercase"><p style="text-transform:full-width bogus">abc</p></div>""")
        assertEquals(listOf("ABC"), inherited)
    }
}
