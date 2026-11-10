package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.epub.HtmlParser
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pseudo-classes match for real and `+`/`~` are combinators. Each case
 * resolves a small document against one rule and asserts exactly which
 * elements picked up the declaration.
 */
class SelectorMatchTest {

    private val red = RgbColor(1.0, 0.0, 0.0)

    /** Computed styles for every element keyed by its `id` attribute. */
    private fun resolve(html: String, css: String, states: Map<String, Map<String, String>> = emptyMap()): Map<String, ComputedStyle> {
        val tree = HtmlParser.parse(html)
        fun setStates(el: KiteXmlNode.Element) {
            states[el.attrs["id"]]?.let { st -> el.attrs = el.attrs + st.mapKeys { FormStates.STATE + it.key } }
            for (c in el.children) if (c is KiteXmlNode.Element) setStates(c)
        }
        setStates(tree)
        val resolver = StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 328.0)
        val map = LinkedHashMap<String, ComputedStyle>()
        fun walk(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>, parent: ComputedStyle) {
            val cs = if (el.tag == "#root") resolver.initial() else resolver.compute(el, ancestors, parent)
            el.attrs["id"]?.let { map[it] = cs }
            val childAnc = if (el.tag == "#root") ancestors else listOf(el) + ancestors
            for (c in el.children) if (c is KiteXmlNode.Element) walk(c, childAnc, cs)
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
        assertEquals(setOf("r"), s.filterValues { it.backgroundColor?.color == red }.keys)
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

    @Test
    fun a_control_state_that_a_script_set_wins_over_its_attributes() {
        val html = """<form><input type="checkbox" id="c" checked="checked"/><input type="checkbox" id="d"/>""" +
            """<input id="t" placeholder="p" required="required"/><select><option id="o1">a</option><option id="o2">b</option></select></form>"""
        val states = mapOf(
            "c" to mapOf("checked" to "0"),
            "d" to mapOf("checked" to "1"),
            "t" to mapOf("value" to "typed"),
            "o1" to mapOf("selected" to "0"),
            "o2" to mapOf("selected" to "1"),
        )
        assertEquals(setOf("d", "o2"), resolve(html, ":checked{color:red}", states).redIds())
        assertEquals(setOf("c"), resolve(html, ":default{color:red}", states).redIds())
        assertEquals(emptySet(), resolve(html, ":placeholder-shown{color:red}", states).redIds())
        assertEquals(setOf("t"), resolve(html, ":placeholder-shown{color:red}").redIds())
        assertEquals(setOf("d"), resolve(html, ":indeterminate{color:red}", mapOf("d" to mapOf("indeterminate" to "1"))).redIds())
    }

    @Test
    fun sibling_steps_cost_one_step_each() {
        // A scan from the first child for every sibling step made h1 ~ p cost n^3/6 (#990), and a count
        // of the siblings before each element made p:nth-child(odd) cost n^2/2: 33 s on a debug native
        // build. Steps are counted, not timed, so a slow build cannot fail it and a quadratic one cannot pass.
        val n = 20_000
        val html = "<body><h1>t</h1>" + "<p>x</p>\n".repeat(n) + "</body>"
        val tree = HtmlParser.parse(html)
        val paragraphs = ArrayList<KiteXmlNode.Element>()
        fun collect(el: KiteXmlNode.Element) { if (el.tag == "p") paragraphs.add(el); for (c in el.children) if (c is KiteXmlNode.Element) collect(c) }
        collect(tree)
        for (rule in listOf("h1 ~ p", "p + p", "p:nth-child(odd)", "p:nth-last-child(3n)", "p:nth-of-type(2n+1)", "p:last-child")) {
            val sel = Selector.parse(rule)!!
            val counting = CountingTree()
            val nth = NthIndex { it.tag }
            val cx = MatchContext(counting, null, nth)
            var matched = 0
            for (p in paragraphs) if (sel.matches(p, cx)) matched++
            assertTrue(matched > 0, rule)
            // Each paragraph tests a compound or two, and an :nth- pass visits the body's 2n + 1 children once.
            val steps = counting.tests + nth.visits
            assertTrue(steps <= 4 * n, "$rule took $steps steps for $n siblings")
        }
        // The index answers as a count does, among mixed types and text, and again once the children change.
        val mixed = HtmlParser.parse("<body>" + (0 until 200).joinToString("") { listOf("<p>a</p>", "<h2>b</h2> ", "<div>c</div>")[it * 7 % 3] } + "</body>")
            .children.single { it is KiteXmlNode.Element && it.tag == "body" } as KiteXmlNode.Element
        val index = MatchContext(LayoutTree, null, NthIndex { it.tag })
        val rules = listOf("p:nth-child(odd)", "div:nth-last-child(3n+1)", "h2:nth-of-type(4n)", "p:nth-last-of-type(-n+5)", ":nth-child(7)")
        repeat(2) {
            val elements = mixed.children.filterIsInstance<KiteXmlNode.Element>()
            for (rule in rules) {
                val sel = Selector.parse(rule)!!
                assertEquals(elements.map { sel.matches(it) }, elements.map { sel.matches(it, index) }, rule)
            }
            mixed.children.removeAt(mixed.children.indexOfFirst { it is KiteXmlNode.Element && it.tag == "h2" })
        }
        // A tree that changes is scanned again, so the answer stays right.
        val body = paragraphs[0].parent!!
        val moved = paragraphs[5]
        body.children.remove(moved)
        body.children.add(0, moved)
        assertEquals(null, previousElement(moved))
    }

    /** The layout's tree, counting the compounds that matching tests, each of which asks an element's local name once. */
    private class CountingTree : SelectorTree by LayoutTree {
        var tests = 0

        override fun localName(el: KiteXmlNode.Element): String {
            tests++
            return LayoutTree.localName(el)
        }
    }

    @Test
    fun the_rule_index_finds_each_rule_by_its_id_class_tag_or_none() {
        // The cascade tests an element only against the rules its id, classes and tag can match (#856).
        val css = (1..3000).joinToString("\n") { "p.c$it span.s$it { color: blue }" } + """
            #x { color: red }
            .k.j { color: red }
            P.up { color: red }
            *.any { color: red }
            [data-z] { color: red }
            p.c7 span.s7 { color: red }
            span.s9 { color: red }
            #late.k { color: blue }
        """.trimIndent()
        val html = """<div>
            <p id="x">id</p>
            <p id="kj" class="k  j k">two classes, one twice</p>
            <p id="up" class="up">tag in another case</p>
            <p id="any" class="any">universal</p>
            <p id="z" data-z="1">no key</p>
            <p class="c7"><span id="s7" class="s7">late rule wins</span></p>
            <p class="c9"><span id="s9" class="s9">the more specific rule wins</span></p>
            <p class="c8"><span id="miss" class="s7">no match</span></p>
            <p id="late" class="k">the id rule is more specific</p>
        </div>"""
        val s = resolve(html, css)
        assertEquals(setOf("x", "kj", "up", "any", "z", "s7"), s.redIds())
        assertEquals(RgbColor(0.0, 0.0, 1.0), s.getValue("late").color)
        assertEquals(RgbColor(0.0, 0.0, 1.0), s.getValue("s9").color)
    }

    @Test
    fun an_element_is_tested_only_against_the_rules_that_can_match_it() {
        // Every selector of every rule was tested against every element, three times with ::before and ::after (#856).
        val resolver = StyleResolver(CssParser.parse((1..3000).joinToString("\n") { "p.c$it span.s$it { color: blue }" }, Origin.AUTHOR), 12.0, 328.0)
        val tree = HtmlParser.parse("<div>" + "<p class=\"c5\">x <span class=\"s5\">y</span></p>".repeat(10) + "</div>")
        var elements = 0
        fun walk(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>, parent: ComputedStyle) {
            val cs = if (el.tag == "#root") resolver.initial() else resolver.compute(el, ancestors, parent).also { elements++ }
            val childAnc = if (el.tag == "#root") ancestors else listOf(el) + ancestors
            for (c in el.children) if (c is KiteXmlNode.Element) walk(c, childAnc, cs)
        }
        walk(tree, emptyList(), resolver.initial())
        assertTrue(elements > 20)
        assertTrue(resolver.selectorTests < elements * 20, "${resolver.selectorTests} selector tests for $elements elements and 3,000 rules")
    }
}
