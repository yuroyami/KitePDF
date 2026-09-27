package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * An XPS page reads its size from the start tag of its markup, the package keeps parsed pages
 * within a budget, and a page drawn again does not decode its images again. The size parsed the
 * whole page, every parsed page stayed for the life of the document, and each render decoded
 * the images again (#385).
 */
class XpsPageReadsTest {

    private fun fixedPage(width: Int, body: String = "") =
        """<FixedPage Width="$width" Height="96" xmlns="http://schemas.microsoft.com/xps/2005/06">$body</FixedPage>""".encodeToByteArray()

    private fun withPage(transform: (ByteArray) -> ByteArray) =
        XpsFixtures.parts("").map { (name, bytes) -> if (name.endsWith(".fpage")) name to transform(bytes) else name to bytes }

    @Test
    fun the_size_comes_from_the_start_tag_when_the_whole_page_cannot_be_read() {
        // Only the first piece of an interleaved page is present, so the whole part cannot be read.
        val parts = XpsFixtures.parts("<Path Fill=\"#ff0000\" Data=\"M0,0 L20,0 20,20Z\"/>")
        val page = parts.last()
        val pieces = parts.dropLast(1) + listOf("${page.first}/[0].piece" to page.second.copyOfRange(0, page.second.size - 4))
        val xps = XpsDocument.open(XpsFixtures.storedZip(pieces)).pages.single()
        assertEquals(144.0, xps.displayWidth, "the size did not come from the start tag")
        assertEquals(72.0, xps.displayHeight)
    }

    @Test
    fun a_start_tag_past_the_first_bytes_falls_back_to_the_full_parse() {
        val comment = ("<!--" + "x".repeat(20_000) + "-->").encodeToByteArray()
        val xps = XpsDocument.open(XpsFixtures.storedZip(withPage { comment + it })).pages.single()
        assertEquals(144.0, xps.displayWidth)
        assertEquals(72.0, xps.displayHeight)
    }

    @Test
    fun a_root_that_is_not_a_fixed_page_keeps_the_page_content_size() {
        val parts = withPage { """<Canvas Width="400" Height="400"/>""".encodeToByteArray() }.map { (name, bytes) ->
            if (name.endsWith(".fdoc")) {
                name to """<FixedDocument><PageContent Source="Pages/Page%201.fpage" Width="96" Height="192"/></FixedDocument>""".encodeToByteArray()
            } else name to bytes
        }
        val xps = XpsDocument.open(XpsFixtures.storedZip(parts)).pages.single()
        assertEquals(72.0, xps.displayWidth)
        assertEquals(144.0, xps.displayHeight)
    }

    @Test
    fun the_package_keeps_the_pages_used_most_recently_within_its_budget() {
        val pages = listOf("a.fpage" to fixedPage(1), "b.fpage" to fixedPage(2), "c.fpage" to fixedPage(3))
        val xps = XpsPackage(XpsFixtures.storedZip(pages), parsedPageBytes = 2L * pages[0].second.size)
        val a = assertNotNull(xps.page("a.fpage"))
        val b = assertNotNull(xps.page("b.fpage"))
        assertSame(a, xps.page("a.fpage"), "a page read again was parsed again")
        // A third page pushes out the one used least recently, b.
        xps.page("c.fpage")
        assertSame(a, xps.page("a.fpage"))
        assertNotSame(b, xps.page("b.fpage"), "the package kept more pages than its budget")
    }

    @Test
    fun a_page_larger_than_the_whole_budget_is_not_kept() {
        val big = fixedPage(1, """<Path Data="M0,0 L1,1"/>""".repeat(10))
        val xps = XpsPackage(XpsFixtures.storedZip(listOf("big.fpage" to big)), parsedPageBytes = 100)
        assertNotSame(xps.page("big.fpage"), xps.page("big.fpage"))
    }

    @Test
    fun a_page_drawn_again_does_not_decode_its_image_again() {
        val content = """<Path Data="M0,0 L40,0 40,20 0,20 Z"><Path.Fill>
            <ImageBrush ImageSource="../../Resources/red.bmp" Viewbox="0,0,2,1" Viewport="0,0,40,20"
              ViewboxUnits="Absolute" ViewportUnits="Absolute"/>
            </Path.Fill></Path>"""
        val page = XpsDocument.open(XpsFixtures.packageBytes(content, listOf("Resources/red.bmp" to XpsFixtures.bmp2x1()))).pages.single()
        fun image() = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image
        assertSame(image(), image(), "the second render decoded the image again")
    }
}
