package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLink
import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An XPS page gives each element with a FixedPage.NavigateUri as a link over what it draws (#433). */
class XpsLinkTest {

    /** Two pages of 192 x 96 units, 144 x 72 points; the second lists the name `Part2`. */
    private fun twoPages(first: String, second: String): XpsDocument {
        val ns = "http://schemas.microsoft.com/xps/2005/06"
        val parts = listOf(
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="R1" Type="$ns/fixedrepresentation" Target="Payload/Sequence.bin"/></Relationships>""",
            "[Content_Types].xml" to """<Types><Default Extension="odttf" ContentType="application/vnd.ms-package.obfuscated-opentype"/></Types>""",
            "Payload/Sequence.bin" to """<FixedDocumentSequence xmlns="$ns"><DocumentReference Source="../Documents/One.fdoc"/></FixedDocumentSequence>""",
            "Documents/One.fdoc" to """<FixedDocument><PageContent Source="Pages/One.fpage"/>""" +
                """<PageContent Source="Pages/Two.fpage"><PageContent.LinkTargets><LinkTarget Name="Part2"/></PageContent.LinkTargets></PageContent></FixedDocument>""",
            "Documents/Pages/One.fpage" to """<FixedPage Width="192" Height="96" xmlns="$ns">$first</FixedPage>""",
            "Documents/Pages/Two.fpage" to """<FixedPage Width="192" Height="96" xmlns="$ns">$second</FixedPage>""",
        ).map { it.first to it.second.encodeToByteArray() }
        return XpsDocument.open(XpsFixtures.storedZip(parts + ("Resources/font.ttf" to XpsFixtures.squareTtf())))
    }

    private fun assertBox(expected: KiteRectangle, link: KiteLink) {
        val r = link.rect
        for ((want, got) in listOf(expected.left to r.left, expected.bottom to r.bottom, expected.right to r.right, expected.top to r.top)) {
            assertEquals(want, got, 0.01, "$link is not at $expected")
        }
    }

    @Test
    fun each_link_covers_what_its_element_draws_and_leads_to_its_address_or_place() {
        val doc = twoPages(
            """<Path Data="M 10,10 L 50,10 L 50,30 L 10,30 Z" Fill="#FF0000" FixedPage.NavigateUri="https://example.com/a"/>""" +
                // A canvas link covers all it holds, and leads to the element that the second page names.
                """<Canvas FixedPage.NavigateUri="#Part2"><Path Data="M 60,40 L 100,40 L 100,60 L 60,60 Z" Fill="#0F0"/>""" +
                """<Path Data="M 110,40 L 120,40 L 120,50 L 110,50 Z" Fill="#0F0"/></Canvas>""" +
                // A link through a clip covers only what shows, and a page part leads to that page.
                """<Canvas Clip="M 0,70 L 30,70 L 30,80 L 0,80 Z" FixedPage.NavigateUri="Two.fpage">""" +
                """<Path Data="M 0,0 L 192,0 L 192,96 L 0,96 Z" Fill="#00F"/></Canvas>""" +
                """<Glyphs Fill="#000" FontUri="../../Resources/font.ttf" FontRenderingEmSize="20" OriginX="130" OriginY="80" UnicodeString="AA" FixedPage.NavigateUri="https://example.com/text"/>""" +
                // A stroked line reaches half its thickness past the line.
                """<Path Data="M 10,90 L 60,90" Stroke="#000" StrokeThickness="4" FixedPage.NavigateUri="https://example.com/line"/>""" +
                // The inner link takes its own path, and the outer one the rest.
                """<Canvas FixedPage.NavigateUri="https://example.com/outer"><Path Data="M 140,0 L 150,0 L 150,10 L 140,10 Z" Fill="#000" FixedPage.NavigateUri="https://example.com/inner"/>""" +
                """<Path Data="M 160,0 L 170,0 L 170,10 L 160,10 Z" Fill="#000"/></Canvas>""" +
                // A path that draws nothing and a name that no page lists make no link.
                """<Path Data="M 0,0 L 5,0 L 5,5 Z" FixedPage.NavigateUri="https://example.com/none"/>""" +
                """<Path Data="M 0,0 L 5,0 L 5,5 Z" Fill="#000" FixedPage.NavigateUri="#Missing"/>""",
            """<Canvas Name="Part2"><Path Data="M 0,40 L 50,40 L 50,60 L 0,60 Z" Fill="#000"/></Canvas>""",
        )
        val links = doc.pages[0].hyperlinks
        assertEquals(
            listOf("https://example.com/a", null, null, "https://example.com/text", "https://example.com/line", "https://example.com/inner", "https://example.com/outer"),
            links.map { it.uri },
        )
        assertBox(KiteRectangle(7.5, 7.5, 37.5, 22.5), links[0])
        assertNull(links[0].target)

        assertBox(KiteRectangle(45.0, 30.0, 90.0, 45.0), links[1])
        assertEquals(KiteBookmark.Page(1), links[1].target)
        // The named canvas starts 40 units, 30 points, down its page.
        assertEquals(30.0, links[1].targetY)

        assertBox(KiteRectangle(0.0, 52.5, 22.5, 60.0), links[2])
        assertEquals(KiteBookmark.Page(1), links[2].target)
        assertNull(links[2].targetY)

        // Two glyphs of 12 units from 130, from 0.8 em above the baseline at 80 to 0.2 em below it.
        assertBox(KiteRectangle(97.5, 48.0, 115.5, 63.0), links[3])
        assertBox(KiteRectangle(6.0, 66.0, 46.5, 69.0), links[4])
        assertBox(KiteRectangle(105.0, 0.0, 112.5, 7.5), links[5])
        assertBox(KiteRectangle(120.0, 0.0, 127.5, 7.5), links[6])

        assertTrue(doc.pages[1].hyperlinks.isEmpty())
    }
}
