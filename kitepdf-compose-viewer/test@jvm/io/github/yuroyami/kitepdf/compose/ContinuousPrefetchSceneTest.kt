package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A continuous strip draws the next page ahead once it rests, into the bitmap cache with the key
 * its slot asks for, and `fit = PAGE` shrinks each page until the whole of it shows (#437).
 */
class ContinuousPrefetchSceneTest {

    /** [count] pages of [width] by [height] points, each with a black square. */
    private fun pagesPdf(count: Int, width: Int, height: Int): PdfDocument {
        val content = "0 g 20 20 60 60 re f"
        val kids = (0 until count).joinToString(" ") { "${3 + it * 2} 0 R" }
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 $width $height] >>"
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
    fun the_strip_draws_the_next_page_ahead_once_it_rests() {
        val rendered = Collections.synchronizedList(ArrayList<Int>())
        // Each 200 by 800 page is a 400 by 1600 slot in the 400 by 600 viewport, so page 1 is off screen.
        val state = KiteDocViewState(pagesPdf(3, 200, 800))
        val (scene, driver) = drivenScene(400, 600, queued = true) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Continuous(prefetchPages = 1),
                onPageRendered = { index, _ -> rendered += index },
            )
        }
        scene.use {
            driver.pumpUntilState { 0 in rendered }
            driver.pumpUntilState { 1 in state.prefetchedPages }
            assertEquals(listOf(0), rendered.toList(), "only the page on screen drew in its slot")
            driver.runOnUi { state.scrollToPage(1) }
            driver.pumpUntilState { state.pageRenderState(1) == KitePageRenderState.Ready }
            // onPageRendered fires on a fresh raster only, never on a cache hit.
            assertEquals(listOf(0), rendered.toList(), "page 1 came from the cache, not from a fresh raster")
        }
    }

    @Test
    fun fit_page_draws_the_next_page_ahead_at_the_size_of_its_slot() {
        val rendered = Collections.synchronizedList(ArrayList<Int>())
        // A 200 by 700 page fits a 600-pixel strip at 171 by 599, a size that rounds twice.
        val state = KiteDocViewState(pagesPdf(4, 200, 700))
        val (scene, driver) = drivenScene(400, 600, queued = true) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Continuous(fit = KitePageFit.PAGE, prefetchPages = 1),
                onPageRendered = { index, _ -> rendered += index },
            )
        }
        scene.use {
            driver.pumpUntilState { 0 in rendered }
            driver.pumpUntilState { state.prefetchedPages.isNotEmpty() }
            val ahead = state.prefetchedPages.max()
            assertEquals(false, ahead in rendered.toList(), "page $ahead drew in a slot before the strip drew it ahead")
            driver.runOnUi { state.scrollToPage(ahead) }
            driver.pumpUntilState { state.pageRenderState(ahead) == KitePageRenderState.Ready }
            // onPageRendered fires on a fresh raster only, never on a cache hit.
            assertEquals(false, ahead in rendered.toList(), "page $ahead came from a fresh raster, not from the cache")
        }
    }

    @Test
    fun without_pages_ahead_a_scroll_draws_the_page_it_lands_on() {
        val rendered = Collections.synchronizedList(ArrayList<Int>())
        val state = KiteDocViewState(pagesPdf(3, 200, 800))
        val (scene, driver) = drivenScene(400, 600, queued = true) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Continuous(prefetchPages = 0),
                onPageRendered = { index, _ -> rendered += index },
            )
        }
        scene.use {
            driver.pumpUntilState { 0 in rendered }
            driver.runOnUi { state.scrollToPage(1) }
            driver.pumpUntilState { 1 in rendered }
            assertEquals(emptySet(), state.prefetchedPages, "nothing drew ahead")
        }
    }

    @Test
    fun fit_page_shrinks_a_tall_page_to_the_viewport() {
        val widths = Collections.synchronizedList(ArrayList<Int>())
        val state = KiteDocViewState(pagesPdf(1, 200, 400))
        val (scene, driver) = drivenScene(400, 600, queued = true) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Continuous(fit = KitePageFit.PAGE),
                onPageRendered = { _, bitmap -> widths += bitmap.width },
            )
        }
        scene.use {
            driver.pumpUntilState { widths.isNotEmpty() }
            // A 200 by 400 page fits a 400 by 600 viewport at 300 by 600. Across the width it would be 400 by 800.
            assertEquals(listOf(300), widths.toList())
        }
    }

    @Test
    fun fit_width_keeps_a_tall_page_across_the_width() {
        val widths = Collections.synchronizedList(ArrayList<Int>())
        val state = KiteDocViewState(pagesPdf(1, 200, 400))
        val (scene, driver) = drivenScene(400, 600, queued = true) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Continuous(),
                onPageRendered = { _, bitmap -> widths += bitmap.width },
            )
        }
        scene.use {
            driver.pumpUntilState { widths.isNotEmpty() }
            assertEquals(listOf(400), widths.toList())
        }
    }
}
