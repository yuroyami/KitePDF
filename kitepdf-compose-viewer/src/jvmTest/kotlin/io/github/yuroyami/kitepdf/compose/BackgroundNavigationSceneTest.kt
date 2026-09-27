package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A host may navigate from any thread. The suspend calls of a state run on the thread of the
 * viewer that shows it, so the caller's thread writes no viewer state and scrolls no container,
 * and an animated call finds the viewer's frame clock (#429).
 *
 * The pages draw as vectors, so no raster lands from the raster thread during a check.
 */
class BackgroundNavigationSceneTest {

    /** [count] empty 200 x 200 pages. */
    private fun pagesPdf(count: Int): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val kids = (0 until count).joinToString(" ") { "${it + 3} 0 R" }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 200 200] >>")
        repeat(count) { add("<< /Type /Page /Parent 2 0 R /Resources << >> >>") }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /** Runs [calls] on a background dispatcher while the scene draws frames, and waits for them to end. */
    private fun fromBackground(driver: SceneTestDriver, calls: suspend () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                calls()
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }
        driver.pumpUntilState { job.isCompleted }
        failure.get()?.let { throw AssertionError("a call from the background thread failed: $it", it) }
    }

    @Test
    fun a_pdf_navigates_from_a_background_thread() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.Continuous())) {
            forBothEffectOrders { queued ->
                val name = layout::class.simpleName
                val doc = pagesPdf(6)
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(200, 200, queued) {
                    state = rememberKiteDocViewState(doc)
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout, renderSpec = KiteRenderSpec.Vectorized())
                }
                scene.use {
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                    driver.pumpFrames(5)
                    val offThread = viewerWritesOffThread {
                        fromBackground(driver) {
                            state.scrollToPage(3)
                            state.animateScrollToPage(1)
                            state.nextPage()
                            state.scrollTo(KiteLocation(0, 5))
                            state.animateZoomTo(2f)
                        }
                        // The zoom settles after a pause, and the page then draws again at it.
                        Thread.sleep(400)
                        driver.pumpFrames(5)
                    }
                    assertEquals(5, state.currentPage, name)
                    assertEquals(2f, state.zoom, name)
                    assertEquals(emptyList(), offThread, name)
                }
            }
        }
    }

    @Test
    fun a_book_goes_to_a_bookmark_from_a_background_thread() {
        forBothEffectOrders { queued ->
            val doc = EpubDocument.open(
                multiSpineEpub(List(5) { "<p>Chapter $it.</p>" }),
                EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
            )
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), renderSpec = KiteRenderSpec.Vectorized())
            }
            scene.use {
                // Every chapter is laid out and published, so only the call publishes during the check.
                driver.pumpUntilState { state.isComplete && state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(5)
                val offThread = viewerWritesOffThread {
                    fromBackground(driver) { state.scrollTo(KiteBookmark.Flow(3, 0)) }
                    driver.pumpFrames(5)
                }
                assertEquals(3, state.currentLocation.chapter)
                assertEquals(emptyList(), offThread)
            }
        }
    }
}
