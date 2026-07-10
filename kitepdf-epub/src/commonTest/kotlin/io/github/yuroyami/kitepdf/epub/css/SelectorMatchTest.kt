package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.epub.HtmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-60: pseudo-classes match for real and `+`/`~` are combinators. Each case
 * resolves a small document against one rule and asserts exactly which
 * elements picked up the declaration.
 */
class SelectorMatchTest {

    private val red = RgbColor(1.0, 0.0, 0.0)

    /** Computed styles for every element keyed by its `id` attribute. */
    private fun resolve(html: String, css: String): Map<String, ComputedStyle> {
        val tree = HtmlParser.parse(html)
        val resolver = StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 328.0)
        val map = LinkedHashMap<String, ComputedStyle>()
        fun walk(el: HtmlNode.Element, ancestors: List<HtmlNode.Element>, parent: ComputedStyle) {
            val cs = if (el.tag == "#root") resolver.initial() else resolver.compute(el, ancestors, parent)
            el.attrs["id"]?.let { map[it] = cs }
            val childAnc = if (el.tag == "#root") ancestors else listOf(el) + ancestors
            for (c in el.children) if (c is HtmlNode.Element) walk(c, childAnc, cs)
        }
        walk(tree, emptyList(), resolver.initial())
        return map
    }

    private fun Map<String, ComputedStyle>.redIds(): Set<String> =
        filterValues { it.color == red }.keys

    private val list = """
        <ul>
          <li id="a">one</li>
          <li id="b">two</li>
          <li id="c">three</li>
        </ul>
    """.trimIndent()

    /* ── regression cases from the audit ─────────────────────────────────── */

    @Test
    fun last_child_styles_only_the_last_item() {
        val s = resolve(list, "li:last-child{color:red}")
        assertEquals(setOf("c"), s.redIds(), "li:last-child must not style every li")
    }

    @Test
    fun next_sibling_does_not_style_the_left_side() {
        val s = resolve("""<div><p id="p">x</p><span id="s">y</span></div>""", "p + span{color:red}")
        assertEquals(setOf("s"), s.redIds(), "p + span styles the span, never the p")
    }

    /* ── pseudo-classes ──────────────────────────────────────────────────── */

    @Test
    fun first_child() {
        assertEquals(setOf("a"), resolve(list, "li:first-child{color:red}").redIds())
    }

    @Test
    fun only_child() {
        val html = """<div><p id="solo">x</p></div><div><p id="p1">a</p><p id="p2">b</p></div>"""
        assertEquals(setOf("solo"), resolve(html, "p:only-child{color:red}").redIds())
    }

    @Test
    fun nth_child_odd_even_and_anb() {
        assertEquals(setOf("a", "c"), resolve(list, "li:nth-child(odd){color:red}").redIds())
        assertEquals(setOf("b"), resolve(list, "li:nth-child(even){color:red}").redIds())
        assertEquals(setOf("b"), resolve(list, "li:nth-child(2){color:red}").redIds())
        assertEquals(setOf("a", "c"), resolve(list, "li:nth-child(2n+1){color:red}").redIds())
        assertEquals(setOf("a", "b"), resolve(list, "li:nth-child(-n+2){color:red}").redIds())
        assertEquals(setOf("a", "b", "c"), resolve(list, "li:nth-child(n){color:red}").redIds())
    }

    @Test
    fun nth_child_counts_elements_not_text_nodes() {
        // Whitespace text between items must not shift the child index.
        val html = "<ul>\n  <li id=\"a\">1</li>\n  <li id=\"b\">2</li>\n</ul>"
        assertEquals(setOf("a"), resolve(html, "li:nth-child(1){color:red}").redIds())
    }

    @Test
    fun first_and_last_of_type() {
        val html = """<div><h1 id="h">t</h1><p id="p1">a</p><p id="p2">b</p></div>"""
        assertEquals(setOf("p1"), resolve(html, "p:first-of-type{color:red}").redIds())
        assertEquals(setOf("p2"), resolve(html, "p:last-of-type{color:red}").redIds())
    }

    @Test
    fun empty_ignores_whitespace_but_not_content() {
        val html = """<div><p id="e"></p><p id="w">   </p><p id="t">x</p><p id="k"><span></span></p></div>"""
        assertEquals(setOf("e", "w"), resolve(html, "p:empty{color:red}").redIds())
    }

    @Test
    fun root_matches_the_document_element_only() {
        // background-color does not inherit, so only the true match paints red.
        val html = """<html id="r"><body id="b"><p id="p">x</p></body></html>"""
        val s = resolve(html, ":root{background-color:red}")
        assertEquals(setOf("r"), s.filterValues { it.backgroundColor == red }.keys)
    }

    @Test
    fun not_excludes_the_matching_compound() {
        assertEquals(setOf("a", "b"), resolve(list, "li:not(#c){color:red}").redIds())
    }

    @Test
    fun link_requires_href() {
        val html = """<p><a id="l" href="x.html">link</a><a id="n">name-only</a></p>"""
        assertEquals(setOf("l"), resolve(html, "a:link{color:red}").redIds())
    }

    @Test
    fun unknown_pseudo_class_never_matches() {
        val s = resolve("""<p id="p">x</p>""", "p:target{color:red}")
        assertEquals(emptySet(), s.redIds(), "unknown pseudo-class must disable the selector")
    }

    @Test
    fun interaction_states_never_match() {
        val html = """<p><a id="l" href="x">link</a></p>"""
        assertEquals(emptySet(), resolve(html, "a:hover{color:red}").redIds())
        assertEquals(emptySet(), resolve(html, "a:visited{color:red}").redIds())
    }

    /* ── sibling combinators ─────────────────────────────────────────────── */

    @Test
    fun next_sibling_requires_adjacency() {
        val html = """<div><h1 id="h">t</h1><p id="p1">a</p><p id="p2">b</p></div>"""
        assertEquals(setOf("p1"), resolve(html, "h1 + p{color:red}").redIds())
    }

    @Test
    fun next_sibling_skips_interleaved_text() {
        val html = "<div><h1 id=\"h\">t</h1>\n  between \n<p id=\"p1\">a</p></div>"
        assertEquals(setOf("p1"), resolve(html, "h1 + p{color:red}").redIds())
    }

    @Test
    fun subsequent_sibling_matches_all_following() {
        val html = """<div><h1 id="h">t</h1><p id="p1">a</p><ul id="u"></ul><p id="p2">b</p></div>"""
        assertEquals(setOf("p1", "p2"), resolve(html, "h1 ~ p{color:red}").redIds())
    }

    @Test
    fun sibling_combinator_chains_with_descendant() {
        val html = """<div><h1 id="h">t</h1><p id="p1"><em id="e">x</em></p></div>"""
        assertEquals(setOf("e"), resolve(html, "h1 + p em{color:red}").redIds())
    }

    @Test
    fun tight_sibling_without_spaces_parses() {
        val html = """<div><p id="p">x</p><span id="s">y</span></div>"""
        assertEquals(setOf("s"), resolve(html, "p+span{color:red}").redIds())
    }

    /* ── structural sanity ───────────────────────────────────────────────── */

    @Test
    fun child_combinator_still_works_through_parent_pointers() {
        val html = """<div id="d"><p id="p1">a<span><p id="nope-not-valid"></p></span></p></div>"""
        val s = resolve("""<div><p id="direct">a</p><section><p id="nested">b</p></section></div>""", "div > p{color:red}")
        assertEquals(setOf("direct"), s.redIds())
    }

    @Test
    fun nth_child_with_spaces_in_argument_parses() {
        assertEquals(setOf("a", "c"), resolve(list, "li:nth-child(2n + 1){color:red}").redIds())
    }

    @Test
    fun specificity_counts_pseudo_classes() {
        // li:first-child (0,1,1) must beat plain li (0,0,1) regardless of order.
        val s = resolve(list, "li:first-child{color:red} li{color:blue}")
        assertTrue(s.redIds().contains("a"), "pseudo-class specificity must win over type")
    }
}
