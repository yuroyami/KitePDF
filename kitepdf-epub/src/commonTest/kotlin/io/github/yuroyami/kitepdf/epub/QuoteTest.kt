package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `q` element draws quotation marks (HTML, 15.3.4: `q::before { content: open-quote }`), and
 * `quotes: auto` picks them by the content language of the element, as CLDR gives them (CSS
 * Generated Content 3, 3.1). The package's `dc:language` is not the language of a content
 * document, so it picks nothing (EPUB Reading Systems 3.3, 4.4.1) (#511).
 */
class QuoteTest {

    private fun text(body: String, language: String? = null) = EpubDocument.open(
        EpubFixtures.epub(body, language = language),
        EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
    ).pages[0].textContent().plainText.trim()

    @Test
    fun a_q_element_draws_quotation_marks() {
        assertEquals("He said “hello” to me.", text("<p>He said <q>hello</q> to me.</p>"))
    }

    @Test
    fun a_quotation_inside_a_quotation_takes_the_second_pair() {
        assertEquals("“a ‘b ‘c’ d’ e”", text("<p><q>a <q>b <q>c</q> d</q> e</q></p>"))
        assertEquals("“a” “b”", text("<p><q>a</q> <q>b</q></p>"), "a closed quotation leaves the depth where it was")
    }

    @Test
    fun the_content_language_picks_the_marks() {
        assertEquals("«bien»", text("""<p lang="fr"><q>bien</q></p>"""))
        assertEquals("„gut ‚so‘“", text("""<p xml:lang="de"><q>gut <q>so</q></q></p>"""))
        assertEquals("「はい」", text("""<body lang="ja"><p><q>はい</q></p></body>"""))
        assertEquals("«sim»", text("""<p lang="pt-PT"><q>sim</q></p>"""), "a region with marks of its own")
        assertEquals("“sim”", text("""<p lang="pt-BR"><q>sim</q></p>"""), "a region that takes its language's")
        assertEquals("“yes” «oui»", text("""<p lang="en"><q>yes</q> <span lang="fr"><q>oui</q></span></p>"""))
    }

    @Test
    fun the_package_language_picks_nothing() {
        assertEquals("“bien”", text("<p><q>bien</q></p>", language = "fr"))
    }

    @Test
    fun the_quotes_property_sets_the_marks() {
        assertEquals("<<x>>", text("""<p style="quotes: '<<' '>>'"><q>x</q></p>"""))
        assertEquals("[a (b) c]", text("""<p style="quotes: '[' ']' '(' ')'"><q>a <q>b</q> c</q></p>"""))
        assertEquals("[a [b] c]", text("""<p style="quotes: '[' ']'"><q>a <q>b</q> c</q></p>"""), "the last pair repeats")
        assertEquals("x", text("""<p style="quotes: none"><q>x</q></p>"""))
        assertEquals("«x»", text("""<p lang="fr" style="quotes: '<' '>'"><span style="quotes: auto"><q>x</q></span></p>"""))
    }

    @Test
    fun open_quote_and_close_quote_work_on_any_element() {
        val css = "<style>.said::before{content:open-quote} .said::after{content:close-quote} .hush::before{content:no-open-quote}</style>"
        assertEquals("“x”", text("""$css<p><span class="said">x</span></p>"""))
        assertEquals("y ‘x’", text("""$css<p><span class="hush">y </span><span class="said">x</span></p>"""), "no-open-quote counts a level")
        assertEquals("x", text("""<style>span::after{content:close-quote}</style><p><span>x</span></p>"""), "a close-quote with nothing open draws nothing")
    }
}
