package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteWarningSink
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tree-building + tag-soup recovery in [HtmlParser]. */
class HtmlParserTest {

    private fun KiteXmlNode.Element.elements(tag: String): List<KiteXmlNode.Element> =
        children.filterIsInstance<KiteXmlNode.Element>().filter { it.tag == tag }

    private fun KiteXmlNode.Element.descendants(tag: String): List<KiteXmlNode.Element> {
        val out = ArrayList<KiteXmlNode.Element>()
        fun rec(e: KiteXmlNode.Element) {
            for (c in e.children) if (c is KiteXmlNode.Element) { if (c.tag == tag) out.add(c); rec(c) }
        }
        rec(this); return out
    }

    @Test
    fun implicit_close_of_p_by_following_block() {
        // Unclosed <p> is closed when the next <p> starts (optional end tag).
        val root = HtmlParser.parse("<body><p>one<p>two</body>")
        val body = root.elements("body").single()
        assertEquals(2, body.elements("p").size, "two sibling <p>, not nested")
    }

    @Test
    fun implicit_close_of_li_siblings() {
        val root = HtmlParser.parse("<ul><li>a<li>b<li>c</ul>")
        val ul = root.descendants("ul").single()
        assertEquals(3, ul.elements("li").size, "three sibling <li>")
    }

    @Test
    fun void_element_takes_no_children() {
        val root = HtmlParser.parse("<p>a<br>b</p>")
        val p = root.descendants("p").single()
        val br = p.elements("br").single()
        assertTrue(br.children.isEmpty(), "<br> is void")
        // Text 'b' after <br> is a sibling of <br>, still inside <p>.
        assertEquals("ab", p.children.filterIsInstance<KiteXmlNode.Text>().joinToString("") { it.text })
    }

    @Test
    fun nested_lists_keep_inner_items_inner() {
        val root = HtmlParser.parse("<ul><li>outer<ul><li>inner</ul><li>outer2</ul>")
        val lists = root.descendants("ul")
        assertEquals(2, lists.size)
        // Outer list has 2 items; inner list has 1.
        val outer = lists.first()
        assertEquals(2, outer.elements("li").size)
    }

    @Test
    fun explicit_close_still_wins() {
        val root = HtmlParser.parse("<div><p>x</p><span>y</span></div>")
        val div = root.descendants("div").single()
        assertEquals(1, div.elements("p").size)
        assertEquals(1, div.elements("span").size)
    }

    // ---- the depth limit (#450) -----------------------------------------------

    /** Every element under this one, in document order. No recursion, so a deep tree cannot overflow the test. */
    private fun KiteXmlNode.Element.allElements(): List<KiteXmlNode.Element> {
        val out = ArrayList<KiteXmlNode.Element>()
        val pending = arrayListOf(this)
        while (pending.isNotEmpty()) {
            val e = pending.removeAt(pending.lastIndex)
            if (e !== this) out.add(e)
            for (i in e.children.indices.reversed()) (e.children[i] as? KiteXmlNode.Element)?.let(pending::add)
        }
        return out
    }

    /** The level of this element: 1 for `html`, 2 for `body`. */
    private fun KiteXmlNode.Element.level(): Int = generateSequence(parent) { it.parent }.count()

    /** A body that holds [depth] nested [open] elements around the words "deep words", then a paragraph. */
    private fun nested(open: String, close: String, depth: Int) =
        "<html><body>" + open.repeat(depth) + "deep words" + close.repeat(depth) + "<p>After.</p></body></html>"

    @Test
    fun nesting_up_to_the_depth_limit_is_kept_as_written() {
        for (tag in listOf("div", "span")) for (levels in listOf(HtmlParser.MAX_DEPTH - 1, HtmlParser.MAX_DEPTH)) {
            // html and body are the first two levels.
            val root = HtmlParser.parse(nested("<$tag>", "</$tag>", levels - 2))
            val chain = root.allElements().filter { it.tag == tag }
            assertEquals(levels - 2, chain.size)
            chain.zipWithNext { outer, inner -> assertSame(outer, inner.parent, "$tag at $levels levels") }
            assertEquals(levels, chain.last().level())
            assertEquals("deep words", chain.last().textContent())
        }
    }

    @Test
    fun an_element_one_level_past_the_depth_limit_moves_beside_the_deepest_element() {
        val root = HtmlParser.parse(nested("<div>", "</div>", HtmlParser.MAX_DEPTH - 1))
        val divs = root.allElements().filter { it.tag == "div" }
        val deepest = divs[divs.size - 2]
        val moved = divs.last()
        assertEquals(HtmlParser.MAX_DEPTH, deepest.level())
        assertSame(deepest.parent, moved.parent, "the element past the limit is a sibling of the deepest element")
        assertEquals("deep words", moved.textContent(), "the text stays in its own element")
    }

    @Test
    fun nesting_far_past_the_depth_limit_keeps_every_element_and_its_text() {
        val shapes = listOf(
            "<div>" to "</div>",
            "<span>" to "</span>",
            "<section><ul><li><span><em>" to "</em></span></li></ul></section>",
        )
        for ((open, close) in shapes) {
            val perUnit = open.count { it == '<' }
            val units = 1_000 / perUnit
            val root = HtmlParser.parse(nested(open, close, units))
            val all = root.allElements()
            assertEquals(HtmlParser.MAX_DEPTH, all.maxOf { it.level() }, open)
            assertEquals(units * perUnit + 3, all.size, "every element of $open is kept, with html, body and p")
            val holder = all.single { e -> e.children.any { it is KiteXmlNode.Text && it.text == "deep words" } }
            assertEquals(open.substringAfterLast('<').dropLast(1), holder.tag, "the text stays in its own element")
            val body = root.elements("html").single().elements("body").single()
            assertEquals("After.", body.elements("p").single().textContent(), "the paragraph after $open stays in the body")
        }
    }

    @Test
    fun an_element_past_the_depth_limit_does_not_move_into_a_table_part() {
        // A table lays out rows and cells only, so an element that moved into one would lose its text.
        val written = mapOf("table" to "tbody", "tbody" to "tr", "tr" to "td")
        // The limit falls on another part of the table for each count of elements around the tables.
        for (pad in 0..4) {
            val tables = "<table><tbody><tr><td><div>".repeat(100) + "deep words" + "</div></td></tr></tbody></table>".repeat(100)
            val root = HtmlParser.parse("<html><body>" + "<div>".repeat(pad) + tables + "</div>".repeat(pad) + "</body></html>")
            for (e in root.allElements()) {
                val parent = e.parent?.tag ?: continue
                if (parent in written) assertEquals(written[parent], e.tag, "a child of $parent, with $pad elements around the tables")
            }
        }
    }

    @Test
    fun nesting_past_the_depth_limit_warns_once() {
        val warnings = ArrayList<String>()
        KiteWarnings.sink = KiteWarningSink { warnings.add(it) }
        try {
            HtmlParser.parse(nested("<div>", "</div>", HtmlParser.MAX_DEPTH - 2))
            assertTrue(warnings.isEmpty(), "nesting at the limit is silent: $warnings")
            HtmlParser.parse(nested("<div>", "</div>", 1_000))
            assertEquals(1, warnings.size, "$warnings")
            assertTrue("${HtmlParser.MAX_DEPTH}" in warnings.single(), warnings.single())
        } finally {
            KiteWarnings.sink = null
        }
    }
}
