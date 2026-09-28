package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLink
import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An SVG page gives each `<a>` with an `href` as a link over what it draws (#433). */
class SvgLinkTest {

    private fun assertBox(expected: KiteRectangle, link: KiteLink, slack: Double = 0.01) {
        val r = link.rect
        for ((want, got) in listOf(expected.left to r.left, expected.bottom to r.bottom, expected.right to r.right, expected.top to r.top)) {
            assertEquals(want, got, slack, "$link is not at $expected")
        }
    }

    @Test
    fun each_link_covers_what_it_draws_and_leads_to_its_address_or_element() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="200" height="300">
            <a href="https://example.com/rect"><rect x="10" y="20" width="30" height="40" fill="red"/></a>
            <a xlink:href="#spot"><circle cx="100" cy="50" r="10" fill="blue"/></a>
            <clipPath id="band"><rect x="0" y="120" width="50" height="10"/></clipPath>
            <g clip-path="url(#band)"><a href="https://example.com/clipped"><rect x="0" y="100" width="200" height="100" fill="green"/></a></g>
            <a href="https://example.com/line"><line x1="10" y1="250" x2="110" y2="250" stroke="black" stroke-width="4"/></a>
            <a href="https://example.com/text"><text x="10" y="280" font-size="20">Hi</text></a>
            <a href="https://example.com/outer"><rect x="150" y="0" width="10" height="10"/><a href="https://example.com/inner"><rect x="170" y="0" width="10" height="10"/></a></a>
            <a href="#nowhere"><rect x="0" y="0" width="5" height="5"/></a>
            <rect id="spot" x="0" y="200" width="10" height="10" fill="black"/>
        </svg>"""
        val links = SvgDocument.open(svg.encodeToByteArray()).pages.single().hyperlinks
        assertEquals(
            listOf("https://example.com/rect", null, "https://example.com/clipped", "https://example.com/line", "https://example.com/text", "https://example.com/inner", "https://example.com/outer"),
            links.map { it.uri },
        )
        assertBox(KiteRectangle(10.0, 20.0, 40.0, 60.0), links[0])
        assertNull(links[0].target)
        // A link to an id brings that element to the top of the view.
        assertBox(KiteRectangle(90.0, 40.0, 110.0, 60.0), links[1])
        assertEquals(KiteBookmark.Page(0), links[1].target)
        assertEquals(200.0, links[1].targetY)
        // Only what the clip shows counts, and a stroke reaches half its width past the line.
        assertBox(KiteRectangle(0.0, 120.0, 50.0, 130.0), links[2])
        assertBox(KiteRectangle(8.0, 248.0, 112.0, 252.0), links[3])
        // The text reaches 0.8 em above its baseline and 0.2 em below it.
        val text = links[4].rect
        assertEquals(10.0, text.left, 0.01)
        assertEquals(264.0, text.bottom, 0.01)
        assertEquals(284.0, text.top, 0.01)
        assertTrue(text.right > 20.0, "the text box has no width: $text")
        // The inner link takes its own shape, and the outer one the rest.
        assertBox(KiteRectangle(170.0, 0.0, 180.0, 10.0), links[5])
        assertBox(KiteRectangle(150.0, 0.0, 160.0, 10.0), links[6])
    }
}
