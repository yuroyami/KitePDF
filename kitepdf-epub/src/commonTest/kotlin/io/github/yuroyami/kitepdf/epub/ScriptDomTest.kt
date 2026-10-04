package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.script.ScriptDom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The live tree a chapter's scripts change, and the copies it hands the layout (#41). */
class ScriptDomTest {

    private val source = HtmlParser.parse(
        """<html><head><title>T</title></head><body><p id="a" class="x">One <b>bold</b></p><p id="b">Two</p></body></html>""",
    )

    private fun ScriptDom.body(): KiteXmlNode.Element = checkNotNull(query(root, "body", all = false)).single()

    @Test
    fun the_live_tree_is_a_copy_that_maps_back_to_the_layouts() {
        val dom = ScriptDom(source)
        val a = checkNotNull(dom.byId("a"))
        assertNotSame(source.children.first(), dom.root.children.first(), "the scripts own their tree")
        val layoutA = checkNotNull(dom.toLayout[a])
        assertEquals("a", layoutA.attrs["id"])
        assertSame(a, dom.fromLayout[layoutA])
        assertFalse(dom.dirty)
    }

    @Test
    fun moves_keep_one_tree_and_refuse_a_cycle() {
        val dom = ScriptDom(source)
        val body = dom.body()
        val a = checkNotNull(dom.byId("a"))
        val b = checkNotNull(dom.byId("b"))
        assertNull(dom.insert(body, b, a))
        assertEquals(listOf("b", "a"), body.children.filterIsInstance<KiteXmlNode.Element>().map { it.attrs["id"] })
        assertEquals("HierarchyRequestError", dom.insert(a, body, null), "a node cannot go inside itself")
        assertEquals("NotFoundError", dom.remove(a, b), "b is not a's child")
        assertTrue(dom.dirty)
    }

    @Test
    fun a_fragment_gives_up_its_children() {
        val dom = ScriptDom(source)
        val body = dom.body()
        val fragment = dom.createFragment()
        val em = dom.createElement("em", ScriptDom.XHTML_NS, null)
        assertNull(dom.insert(fragment, em, null))
        assertNull(dom.insert(fragment, KiteXmlNode.Text("tail"), null))
        assertEquals(11, dom.kind(fragment))
        assertFalse(dom.isConnected(em))
        assertNull(dom.insert(body, fragment, null))
        assertTrue(fragment.children.isEmpty())
        assertSame(body, em.parent)
        assertTrue(dom.isConnected(em))
        assertEquals("tail", dom.textOf(body.children.last()))
    }

    @Test
    fun html_goes_out_and_comes_back() {
        val dom = ScriptDom(source)
        val a = checkNotNull(dom.byId("a"))
        assertEquals("One <b>bold</b>", dom.html(a, outer = false))
        assertEquals("""<p id="a" class="x">One <b>bold</b></p>""", dom.html(a, outer = true))
        dom.setHtml(a, "<i>it</i> &amp; more")
        assertEquals("<i>it</i> &amp; more", dom.html(a, outer = false))
        assertEquals("it & more", dom.textOf(a))
        assertSame(a, (a.children.first() as KiteXmlNode.Element).parent)
    }

    @Test
    fun selectors_find_and_match() {
        val dom = ScriptDom(source)
        assertEquals(listOf("a", "b"), checkNotNull(dom.query(dom.root, "body > p", all = true)).map { it.attrs["id"] })
        assertEquals(listOf("b"), checkNotNull(dom.query(dom.root, "p:not(.x), title", all = true)).filter { it.tag == "p" }.map { it.attrs["id"] })
        assertEquals(true, dom.matches(checkNotNull(dom.byId("a")), "p.x"))
        assertNull(dom.query(dom.root, "p,,b", all = true), "an empty part does not parse")
    }

    @Test
    fun a_write_goes_after_the_script_in_order() {
        val dom = ScriptDom(source)
        val a = checkNotNull(dom.byId("a"))
        val after = checkNotNull(dom.writeAfter(a, "<span>1</span>"))
        dom.writeAfter(after, "<span>2</span>")
        val tags = dom.body().children.filterIsInstance<KiteXmlNode.Element>().map { it.tag + dom.textOf(it) }
        assertEquals(listOf("pOne bold", "span1", "span2", "pTwo"), tags)
    }

    @Test
    fun a_snapshot_is_the_layouts_and_maps_both_ways() {
        val dom = ScriptDom(source)
        val a = checkNotNull(dom.byId("a"))
        dom.setAttr(a, "class", "y")
        val copy = dom.snapshot()
        assertFalse(dom.dirty)
        val laidA = checkNotNull(dom.toLayout[a])
        assertEquals("y", laidA.attrs["class"])
        assertSame(a, dom.fromLayout[laidA])
        dom.setAttr(a, "class", "z")
        assertEquals("y", laidA.attrs["class"], "a later change does not reach the layout's copy")
        assertTrue(copy.children.isNotEmpty())
    }
}
