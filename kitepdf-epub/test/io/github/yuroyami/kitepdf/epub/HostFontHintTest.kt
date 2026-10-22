package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A book page says whether it draws text in a host font, so a rasterizer can skip its probe (#131). */
class HostFontHintTest {

    private fun page(body: String) = EpubDocument.open(
        EpubFixtures.epub(body, listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1())),
    ).pages[0]

    @Test
    fun a_page_says_whether_it_draws_host_font_text() {
        assertEquals(true, page("<p>Text without a font of its own.</p>").drawsHostFontText)
        // A list marker draws in a host font too.
        assertEquals(true, page("""<ul><li><img src="pic.bmp" alt=""/></li></ul>""").drawsHostFontText)
        assertEquals(false, page("""<div><img src="pic.bmp" alt="x"/></div>""").drawsHostFontText)
        // An SVG may draw text that the page cannot see into.
        assertNull(page("""<div><svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect width="20" height="20"/></svg></div>""").drawsHostFontText)
    }
}
