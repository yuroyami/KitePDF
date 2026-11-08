package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** KiteDocViewState.visiblePages names every page that is at least partly on screen (#901). */
class VisiblePagesSceneTest {

    /** [count] empty pages of [width] by [height] points. */
    private fun pagesPdf(count: Int, width: Int, height: Int): PdfDocument {
        val kids = (0 until count).joinToString(" ") { "${3 + it} 0 R" }
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 $width $height] >>"
        repeat(count) { objects += "<< /Type /Page /Parent 2 0 R /Resources << >> >>" }
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun a_strip_that_shows_two_pages_names_both() {
        for (spec in listOf<KiteRenderSpec>(KiteRenderSpec.Rasterized(), KiteRenderSpec.Vectorized())) {
            // Square pages are 400 by 400 slots in a 400 by 600 viewport: page 0 and half of page 1 show.
            val state = KiteDocViewState(pagesPdf(6, 200, 200))
            val (scene, driver) = drivenScene(400, 600, queued = true) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Continuous(), renderSpec = spec)
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(5)
                val name = spec::class.simpleName
                assertEquals(listOf(0, 1), state.visiblePages, name)
                driver.runOnUi { state.scrollToPage(3) }
                driver.pumpFrames(5)
                val now = state.visiblePages
                assertTrue(3 in now && 0 !in now && 1 !in now, "$name: $now")
            }
        }
    }

    @Test
    fun a_pager_names_the_page_it_rests_on() {
        val state = KiteDocViewState(pagesPdf(6, 200, 200))
        val (scene, driver) = drivenScene(300, 300, queued = true) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            driver.pumpFrames(5)
            assertEquals(listOf(0), state.visiblePages)
            driver.runOnUi { state.scrollToPage(4) }
            driver.pumpFrames(5)
            assertEquals(listOf(4), state.visiblePages, "a pager at rest shows one page")
        }
    }
}
