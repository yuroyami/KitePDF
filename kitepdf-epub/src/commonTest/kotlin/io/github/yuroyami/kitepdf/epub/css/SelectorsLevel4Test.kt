package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.KiteWarningSink
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A book's style sheet reads the selectors of Selectors 4, and drops a rule a browser drops (#549). */
class SelectorsLevel4Test {

    private val red = RgbColor(1.0, 0.0, 0.0)

    /** The ids of the elements a rule of [css] makes red: every element has a colour of its own, so none inherits it. */
    private fun redIds(html: String, css: String): Set<String> {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val resolver = StyleResolver(CssParser.parse("*{color:blue}", Origin.AUTHOR) + rules, 12.0, 328.0)
        val out = LinkedHashSet<String>()
        fun walk(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>, parent: ComputedStyle) {
            val cs = if (el.tag == "#root") resolver.initial() else resolver.compute(el, ancestors, parent)
            if (cs.color == red) el.attrs["id"]?.let { out.add(it) }
            val below = if (el.tag == "#root") ancestors else listOf(el) + ancestors
            for (c in el.children) if (c is KiteXmlNode.Element) walk(c, below, cs)
        }
        walk(tree, emptyList(), resolver.initial())
        return out
    }

    private val paragraphs = """<div id="d"><h1 id="h">T</h1><p id="a" class="a">1</p><p id="b" class="b">2</p><p id="c" class="a b">3</p></div>"""

    @Test
    fun negation_takes_a_list_and_any_selector() {
        assertEquals(setOf("b", "c"), redIds(paragraphs, "p:not(:first-of-type){color:red}"))
        assertEquals(setOf("h"), redIds(paragraphs, "#d > :not(.a, .b){color:red}"))
        assertEquals(setOf("a", "c"), redIds(paragraphs, "p:not(:not(.a)){color:red}"))
    }

    @Test
    fun the_structural_pseudo_classes_count_by_type_and_from_the_end() {
        assertEquals(setOf("b"), redIds(paragraphs, "p:nth-of-type(2){color:red}"))
        assertEquals(setOf("a", "c"), redIds(paragraphs, "p:nth-of-type(odd){color:red}"))
        assertEquals(setOf("c"), redIds(paragraphs, "p:nth-last-child(1){color:red}"))
        assertEquals(setOf("b", "c"), redIds(paragraphs, "p:nth-last-of-type(-n+2){color:red}"))
        assertEquals(setOf("d", "h"), redIds(paragraphs, ":only-of-type{color:red}"))
        assertEquals(setOf("c"), redIds(paragraphs, "p:nth-child(2 of .a){color:red}"))
        assertEquals(setOf("a", "c"), redIds(paragraphs, "p:nth-child( 2n + 2 ){color:red}"))
    }

    @Test
    fun is_where_and_has_match_and_count_as_selectors_4_has_it() {
        assertEquals(setOf("h", "a", "c"), redIds(paragraphs, ":is(h1, .a){color:red}"))
        assertEquals(setOf("d"), redIds(paragraphs, "div:has(> p.b){color:red}"))
        assertEquals(setOf("a", "b"), redIds(paragraphs, "p:has(+ .b){color:red}"))
        // :where counts nothing, so a type selector after it wins; :is counts its most specific argument.
        assertEquals(emptySet(), redIds(paragraphs, ":where(#a){color:red} p{color:blue}"))
        assertEquals(setOf("a", "b", "c"), redIds(paragraphs, ":is(#a, p){color:red} p.a{color:blue}"))
    }

    @Test
    fun attribute_selectors_take_a_case_flag_and_a_declared_namespace() {
        val html = """<aside id="n" epub:type="footnote rearnote" data-x="Note">x</aside><p id="p" data-x="note">y</p>"""
        assertEquals(setOf("n", "p"), redIds(html, """[data-x="NOTE" i]{color:red}"""))
        assertEquals(setOf("p"), redIds(html, """[data-x="note"]{color:red}"""))
        assertEquals(setOf("n"), redIds(html, """@namespace epub "http://www.idpf.org/2007/ops"; aside[epub|type~="footnote"]{color:red}"""))
        // An undeclared prefix is not valid, so the rule goes.
        assertEquals(emptySet(), redIds(html, """aside[epub|type~="footnote"]{color:red}"""))
        // A default namespace applies to type selectors; HTML's is the layout's every element but SVG's and MathML's.
        assertEquals(setOf("n", "p"), redIds(html, """@namespace url(http://www.w3.org/1999/xhtml); *{color:red}"""))
        assertEquals(emptySet(), redIds(html, """@namespace "http://www.w3.org/2000/svg"; p{color:red}"""))
        // An @namespace after a style rule is not one.
        assertEquals(setOf("p"), redIds(html, """b{color:blue} @namespace "http://www.w3.org/2000/svg"; p{color:red}"""))
    }

    @Test
    fun one_invalid_selector_drops_its_whole_rule() {
        assertEquals(emptySet(), redIds(paragraphs, "p::foo, h1{color:red}"))
        assertEquals(emptySet(), redIds(paragraphs, "p:foo, h1{color:red}"))
        assertEquals(emptySet(), redIds(paragraphs, "p::-moz-selection, h1{color:red}"))
        assertEquals(emptySet(), redIds(paragraphs, "p >, h1{color:red}"))
        // A pseudo-element the layout draws nothing for is still valid, and leaves the rest of the list.
        assertEquals(setOf("h"), redIds(paragraphs, "p::first-line, p::selection, h1{color:red}"))
        assertEquals(setOf("h"), redIds(paragraphs, "p:hover, :is(h1, :foo){color:red}"))
    }

    @Test
    fun the_comment_marks_of_an_old_style_element_are_skipped() {
        assertEquals(setOf("a", "c"), redIds(paragraphs, "<!--\n.a{color:red}\n-->"))
    }

    @Test
    fun an_escape_names_what_a_bare_character_cannot() {
        val html = """<p id="a:b">x</p><p id="1st">y</p>"""
        assertEquals(setOf("a:b"), redIds(html, """#a\:b{color:red}"""))
        assertEquals(setOf("1st"), redIds(html, """#\31 st{color:red}"""))
    }

    @Test
    fun language_and_direction_come_from_the_nearest_element_that_sets_them() {
        val html = """<div lang="fr-CA" dir="rtl"><p id="f">x</p><p id="e" lang="en" dir="ltr">y</p><p id="a" dir="auto">abc</p><p id="h" dir="auto">שלום abc</p></div>"""
        assertEquals(setOf("f", "a", "h"), redIds(html, "p:lang(fr){color:red}"))
        assertEquals(setOf("f", "a", "h"), redIds(html, """p:lang("*-CA"){color:red}"""))
        assertEquals(setOf("e"), redIds(html, "p:lang(en, de){color:red}"))
        assertEquals(setOf("f", "h"), redIds(html, "p:dir(rtl){color:red}"))
        assertEquals(setOf("e", "a"), redIds(html, "p:dir(ltr){color:red}"))
    }

    @Test
    fun form_states_follow_the_attributes() {
        val html = """<form><input id="c" type="checkbox" checked=""/><input id="u" type="checkbox"/>""" +
            """<fieldset disabled=""><legend><input id="l"/></legend><input id="f"/></fieldset>""" +
            """<select><option id="o1">a</option><option id="o2">b</option></select><input id="r" required=""/></form>"""
        assertEquals(setOf("c", "o1"), redIds(html, ":checked{color:red}"))
        assertEquals(setOf("f"), redIds(html, "input:disabled{color:red}"))
        assertEquals(setOf("r"), redIds(html, "input:invalid{color:red}"))
    }

    @Test
    fun a_selector_list_parses_as_a_browser_reads_it() {
        val valid = listOf(
            "p:nth-child(2n+ 1)", "p:nth-child(2n -1)", "p:nth-child(-n- 3)", "p:nth-child(+n)", "p:nth-child(N)", "p:nth-child(-0n+0)",
            "[a|=b]", "[*|a]", "[|a]", "[a~=\"b\"i]", "*|p", "|*", ".-a", "#a.b#c", "p ,q", "p:is()", "p:is(p, :foo)",
            "p:has(p:is(:has(b)))", "p:host(p)", "p\\", "\\70", "p:hover::before", "p::before::marker", "p::-webkit-scrollbar",
            "p::highlight(x)", ":dir(foo)", "p:not(:not(p))", "p:nth-child(n of p, q)",
        )
        val invalid = listOf(
            "p:nth-child(- n+3)", "p:nth-child(2 n)", "p:nth-child(2n+-1)", "p:nth-child(+-2)", "p:nth-child(1.5)", "p:nth-child(2n of)",
            "p:nth-of-type(2n of p)", "[a=b c]", "[a=1]", "[ns|a]", "a#1", ".1", "p,", ",p", "p>>q", "> p", "*p", "p*", "p|q",
            "p:not()", "p:has()", "p:has(:has(b))", "p:has(:not(:has(b)))", "p:not(::before)", "p::before span", "p::before:first-child",
            "p::moz", "p::-moz-selection", "p:local-link", ":foo", "p:dir(\"ltr\")", "<!--p", "", " ",
        )
        for (s in valid) assertNotNull(Selector.parseList(s), "valid: $s")
        for (s in invalid) assertNull(Selector.parseList(s), "invalid: $s")
    }

    @Test
    fun specificity_follows_selectors_4() {
        fun spec(s: String) = assertNotNull(Selector.parseList(s)).single().specificity.let { listOf(it shr 16, (it shr 8) and 255, it and 255) }
        assertEquals(listOf(1, 0, 1), spec("p:is(#x, .y)"))
        assertEquals(listOf(0, 0, 1), spec("p:where(#x, .y)"))
        assertEquals(listOf(1, 0, 0), spec(":not(#x, p)"))
        assertEquals(listOf(0, 2, 1), spec("p:nth-child(2 of .a)"))
        assertEquals(listOf(0, 1, 2), spec("div:has(> p.a)"))
        assertEquals(listOf(0, 0, 2), spec("p::before"))
        assertEquals(listOf(0, 0, 0), spec("*|*"))
    }

    @Test
    fun a_selector_nested_beyond_the_limit_warns_once() {
        val previous = KiteWarnings.sink
        val warnings = ArrayList<String>()
        KiteWarnings.sink = KiteWarningSink { warnings.add(it) }
        try {
            assertNull(Selector.parseList(":is(".repeat(2_000) + "p" + ")".repeat(2_000)))
            assertNotNull(Selector.parseList(":is(".repeat(CssParser.MAX_NESTING) + "p" + ")".repeat(CssParser.MAX_NESTING)))
        } finally {
            KiteWarnings.sink = previous
        }
        assertEquals(1, warnings.size)
        assertTrue("nested beyond" in warnings.single())
    }
}
