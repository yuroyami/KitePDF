package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A large PDF builds its page list off the main thread: its first frame shows a placeholder, and
 * the pages follow. The first composed page built every page object in that frame (#387).
 */
class LargePdfOpenSceneTest {

    /** [pages] pages that fill blue, except page 4,000, which fills red. */
    private fun pdf(pages: Int): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val blue = pages + 3
        val red = pages + 4
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [${(0 until pages).joinToString(" ") { "${it + 3} 0 R" }}] /Count $pages >>")
        repeat(pages) { add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Contents ${if (it == 4_000) red else blue} 0 R >>") }
        for (fill in listOf("0 0 1 rg 0 0 200 300 re f", "1 0 0 rg 0 0 200 300 re f")) {
            add("<< /Length ${fill.length} >>\nstream\n$fill\nendstream")
        }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun the_first_frame_does_not_build_the_page_list() {
        val doc = pdf(5_000)
        lateinit var state: KiteDocViewState
        var readyInFirstComposition: Boolean? = null
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            if (readyInFirstComposition == null) readyInFirstComposition = doc.isChapterReady(0)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            assertEquals(false, readyInFirstComposition, "the first composition built the page list")
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            assertTrue(doc.isChapterReady(0))
            assertEquals(5_000, state.itemCount)
            assertEquals(0, state.currentPage)
        }
    }

    @Test
    fun a_single_page_view_deep_in_the_document_shows_that_page() {
        val doc = pdf(5_000)
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            KiteDocView(document = doc, page = 4_000, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            // The page renders off the main thread and fades in, so wait for a settled page colour.
            val frame = SceneTestDriver(scene).pumpUntil { it[100, 150] == Color.Red || it[100, 150] == Color.Blue }
            assertEquals(Color.Red, frame.toComposeImageBitmap().toPixelMap()[100, 150], "the view does not show page 4,000")
        }
    }

    @Test
    fun a_state_opened_deep_in_the_document_lands_there_once_the_list_exists() {
        val doc = pdf(5_000)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, initialPage = 4_000)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() && state.currentPage == 4_000 }
            assertEquals(4_000, state.currentPage)
            assertFalse(state.pageGeometry.keys.none { it == 4_000 }, "page 4,000 is not on screen: ${state.pageGeometry.keys}")
        }
    }
}
