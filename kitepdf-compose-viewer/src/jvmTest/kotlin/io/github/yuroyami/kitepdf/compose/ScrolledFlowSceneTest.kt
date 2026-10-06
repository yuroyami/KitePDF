package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A book whose `rendition:flow` is `scrolled-doc` reads in a pager whose chapters each fit the
 * width and scroll down, and a sideways swipe moves from one chapter to the next (EPUB 3.3, W3C
 * tests lay-pkg-flow-scrolled-doc and scr-support_scrolled-doc, #505).
 */
class ScrolledFlowSceneTest {

    /** A long chapter of text, an anchor near its end, and a red block at its very end. */
    private val long = (1..40).joinToString("") { "<p${if (it == 35) " id=\"late\"" else ""}>Paragraph $it of a chapter that scrolls.</p>" } +
        "<div style=\"height: 60px; background-color: #ff0000\"></div>"

    private fun book(flow: String?, settings: EpubSettings = EpubSettings(pageWidth = 200.0, pageHeight = 300.0, margin = 10.0)): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val meta = flow?.let { """<meta property="rendition:flow">$it</meta>""" }.orEmpty()
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier>$meta</metadata>""" +
            """<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/></manifest>""" +
            """<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        fun chapter(body: String) = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
        return EpubDocument.open(
            storedZipOf(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/c1.xhtml" to chapter(long).encodeToByteArray(),
                    "OEBPS/c2.xhtml" to chapter("<p>Second chapter.</p>").encodeToByteArray(),
                ),
            ),
            settings,
        )
    }

    private var clock = 1_000L

    private fun drag(scene: ImageComposeScene, from: Offset, to: Offset, steps: Int = 30) {
        scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = clock, type = PointerType.Touch)
        for (step in 1..steps) {
            clock += 30
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step.toFloat() / steps), timeMillis = clock, type = PointerType.Touch)
        }
        scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = clock, type = PointerType.Touch)
    }

    private fun wheel(scene: ImageComposeScene, deltaY: Float) {
        clock += 10
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 150f), scrollDelta = Offset(0f, deltaY), timeMillis = clock, type = PointerType.Mouse)
    }

    private fun withPager(block: (ImageComposeScene, KiteDocViewState, SceneTestDriver) -> Unit) {
        val doc = book("scrolled-doc")
        val state = KiteDocViewState(doc)
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.forDocument(doc))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            driver.pumpFrames(5)
            block(scene, state, driver)
        }
    }

    @Test
    fun the_layout_follows_the_book_flow() {
        assertEquals(KiteDocLayout.Paged(fit = KitePageFit.WIDTH), KiteDocLayout.forDocument(book("scrolled-doc")))
        assertEquals(KiteDocLayout.Continuous(), KiteDocLayout.forDocument(book("scrolled-continuous")))
        assertEquals(KiteDocLayout.Paged(), KiteDocLayout.forDocument(book("paginated")))
        assertEquals(KiteDocLayout.Paged(), KiteDocLayout.forDocument(book(null)))
        val reader = EpubSettings(pageWidth = 200.0, pageHeight = 300.0, margin = 10.0, scrolled = false)
        assertEquals(KiteDocLayout.Paged(), KiteDocLayout.forDocument(book("scrolled-doc", reader)))
        assertEquals(KiteDocLayout.Continuous(), KiteDocLayout.forDocument(book(null, reader.copy(scrolled = true))))
    }

    @Test
    fun a_chapter_fills_the_width_starts_at_its_top_and_scrolls_down() {
        withPager { scene, state, driver ->
            val rect = onTestUiThread { state.pageGeometry.getValue(0) }
            assertEquals(0f, rect.left)
            assertEquals(200f, rect.right)
            assertEquals(0f, rect.top)
            assertTrue(rect.height > 600f, "the chapter is ${rect.height} px tall")
            assertEquals(Offset.Zero, state.panOffset)
            drag(scene, Offset(100f, 250f), Offset(100f, 50f))
            driver.pumpFrames(30)
            assertTrue(state.panOffset.y < -150f, "the drag moved the chapter by ${state.panOffset.y}")
            assertEquals(0, state.currentPage)
        }
    }

    @Test
    fun a_sideways_drag_moves_to_the_next_chapter() {
        withPager { scene, state, driver ->
            drag(scene, Offset(190f, 150f), Offset(10f, 150f))
            driver.pumpUntilState { state.currentPage == 1 }
            assertEquals(1, state.currentPage)
        }
    }

    @Test
    fun the_wheel_scrolls_to_the_end_of_the_chapter_and_then_turns_it() {
        withPager { scene, state, driver ->
            wheel(scene, 1f)
            driver.pumpFrames(3)
            assertTrue(state.panOffset.y < -30f, "a notch moved the chapter by ${state.panOffset.y}")
            assertEquals(0, state.currentPage)
            // Spin on to the end of the chapter, in one gesture: it stops there and does not turn.
            repeat(100) { wheel(scene, 1f) }
            driver.pumpFrames(30)
            assertEquals(0, state.currentPage, "the gesture that reached the end also turned the page")
            val end = state.panOffset.y
            val rect = onTestUiThread { state.pageGeometry.getValue(0) }
            assertEquals(-(rect.height - 300f), end, 1f)
            // The red block at the end of the chapter shows at the bottom of the view.
            val pixels = driver.pumpFrames(40).toComposeImageBitmap().toPixelMap()
            val red = (0 until 200 step 4).count { x -> (200 until 300 step 4).any { y -> pixels[x, y].let { it.red > 0.9f && it.green < 0.1f } } }
            assertTrue(red > 20, "the end of the chapter did not draw: $red columns of red")
            // After a pause, the next gesture turns to the next chapter, at its top.
            clock += 1_000
            wheel(scene, 1f)
            driver.pumpUntilState { state.currentPage == 1 }
            assertEquals(1, state.currentPage)
        }
    }

    @Test
    fun a_link_to_an_anchor_shows_it_at_the_top() {
        withPager { _, state, driver ->
            driver.runOnUi { state.scrollTo(KiteBookmark.Flow(0, 0, fragment = "late")) }
            driver.pumpFrames(10)
            val top = state.document.topOf(KiteBookmark.Flow(0, 0, fragment = "late"))!!
            val rect = onTestUiThread { state.pageGeometry.getValue(0) }
            val scale = rect.width / state.document.page(io.github.yuroyami.kitepdf.core.KiteLocation(0, 0)).displayWidth
            assertEquals(-(top * scale).toFloat(), state.panOffset.y, 2f)
        }
    }
}
