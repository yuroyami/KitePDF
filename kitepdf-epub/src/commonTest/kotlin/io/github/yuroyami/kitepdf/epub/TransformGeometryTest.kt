package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** A transformed box moves its links, its text and its fragments with its paint (#28). */
class TransformGeometryTest {

    private fun page(body: String) = EpubDocument.open(EpubFixtures.epub(body)).pages[0]

    @Test
    fun a_moved_box_moves_its_links_text_and_fragments() {
        val still = page("""<p><a id="t" href="https://example.com/a">Link text</a></p>""")
        val moved = page("""<p style="transform:translate(100px, 20px)"><a id="t" href="https://example.com/a">Link text</a></p>""")
        // 100 by 20 pixels are 75 by 15 points.
        val a = still.links.single().rect
        val b = moved.links.single().rect
        assertEquals(a.left + 75.0, b.left, 0.01)
        assertEquals(a.bottom + 15.0, b.bottom, 0.01)
        val lineA = still.textContent().blocks.single().lines.single()
        val lineB = moved.textContent().blocks.single().lines.single()
        assertEquals(lineA.bounds.left + 75.0, lineB.bounds.left, 0.01)
        assertEquals(lineA.charEdges[3] + 75.0, lineB.charEdges[3], 0.01)
        assertEquals(lineA.bounds.bottom + 15.0, lineB.bounds.bottom, 0.01)
        val href = "OEBPS/chapter1.xhtml#t"
        val fa = EpubDocument.open(EpubFixtures.epub("""<p><a id="t" href="https://example.com/a">Link text</a></p>""")).locateFragment(href)!!.rects.single()
        val fb = EpubDocument.open(EpubFixtures.epub("""<p style="transform:translate(100px, 20px)"><a id="t" href="https://example.com/a">Link text</a></p>""")).locateFragment(href)!!.rects.single()
        assertEquals(fa.left + 75.0, fb.left, 0.01)
    }

    @Test
    fun a_turned_box_keeps_its_text_but_moves_its_link_box() {
        val still = page("""<p><a href="https://example.com/a">Link text</a></p>""")
        val turned = page("""<p style="transform:rotate(90deg)"><a href="https://example.com/a">Link text</a></p>""")
        val a = still.links.single().rect
        val b = turned.links.single().rect
        // A quarter turn swaps the width and the height of the link's box.
        assertEquals(a.right - a.left, b.top - b.bottom, 0.01)
        assertEquals(a.top - a.bottom, b.right - b.left, 0.01)
        // The text of a turned box stays where the layout put it.
        assertEquals(still.textContent().blocks.single().lines.single().bounds, turned.textContent().blocks.single().lines.single().bounds)
    }
}
