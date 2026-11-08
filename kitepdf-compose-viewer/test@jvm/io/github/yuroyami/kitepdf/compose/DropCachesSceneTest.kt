package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** KiteDocViewState.dropCaches frees the bitmaps of the pages off screen, and keeps the page on screen (#903). */
class DropCachesSceneTest {

    private fun pagesPdf(count: Int): PdfDocument {
        val content = "0 g 20 20 60 60 re f"
        val kids = (0 until count).joinToString(" ") { "${3 + it * 2} 0 R" }
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 200 200] >>"
        repeat(count) {
            objects += "<< /Type /Page /Parent 2 0 R /Resources << >> /Contents ${4 + it * 2} 0 R >>"
            objects += "<< /Length ${content.length} >>\nstream\n$content\nendstream"
        }
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun the_page_on_screen_keeps_its_bitmap_and_the_rest_draw_again() {
        val rendered = Collections.synchronizedList(ArrayList<Int>())
        val state = KiteDocViewState(pagesPdf(20))
        val (scene, driver) = drivenScene(200, 200, queued = true) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), onPageRendered = { index, _ -> rendered += index })
        }
        scene.use {
            for (page in 0 until 20) {
                driver.runOnUi { state.scrollToPage(page) }
                driver.pumpUntilState { page in rendered }
            }
            assertTrue(state.cachedBitmapCount >= 20, "the cache held the pages: ${state.cachedBitmapCount}")
            driver.runOnUi { state.dropCaches() }
            assertEquals(1, state.cachedBitmapCount, "only the page on screen is left")
            rendered.clear()
            driver.pumpFrames(10)
            assertEquals(emptyList(), rendered.toList(), "the page on screen did not draw again")
            driver.runOnUi { state.scrollToPage(0) }
            driver.pumpUntilState { 0 in rendered }
            assertTrue(state.cachedBitmapCount >= 2, "page 0 drew again and fills the cache: ${state.cachedBitmapCount}")
        }
    }
}
