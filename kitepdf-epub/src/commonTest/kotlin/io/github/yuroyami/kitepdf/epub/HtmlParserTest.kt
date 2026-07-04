package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tree-building + tag-soup recovery in [HtmlParser]. */
class HtmlParserTest {

    private fun HtmlNode.Element.elements(tag: String): List<HtmlNode.Element> =
        children.filterIsInstance<HtmlNode.Element>().filter { it.tag == tag }

    private fun HtmlNode.Element.descendants(tag: String): List<HtmlNode.Element> {
        val out = ArrayList<HtmlNode.Element>()
        fun rec(e: HtmlNode.Element) {
            for (c in e.children) if (c is HtmlNode.Element) { if (c.tag == tag) out.add(c); rec(c) }
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
        assertEquals("ab", p.children.filterIsInstance<HtmlNode.Text>().joinToString("") { it.text })
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
}
