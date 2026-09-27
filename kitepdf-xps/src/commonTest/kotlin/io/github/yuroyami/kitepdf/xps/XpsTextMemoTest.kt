package io.github.yuroyami.kitepdf.xps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** An XPS page builds its text once: a selection drag asks for it at the rate of pointer events (#380). */
class XpsTextMemoTest {

    @Test
    fun a_page_builds_its_text_once() {
        val content = """<Glyphs Fill="#000" FontRenderingEmSize="12" OriginX="0" OriginY="12" UnicodeString="kept" FontUri="missing"/>"""
        val page = XpsDocument.open(XpsFixtures.packageBytes(content)).pages.single()
        val first = page.textContent()
        assertEquals("kept", first.plainText)
        assertSame(first, page.textContent(), "a second call gives the text the first one built")
    }
}
