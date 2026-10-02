package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where chapter layout runs on the UI thread, as in a browser, a chapter away from the reader
 * lays out only while the view rests, so a scroll or a pinch does not stall (#389).
 */
class RestingLoaderSceneTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0)

    private fun book(chapters: Int = 10): EpubDocument = EpubDocument.open(
        multiSpineEpub(
            List(chapters) { c ->
                "<h1>Chapter ${c + 1}</h1>" + (0 until 6).joinToString("") { "<p>Chapter ${c + 1} paragraph $it with words enough to wrap.</p>" }
            },
        ),
        settings,
    )

    /** The chapters that lie more than one chapter away from [reader] and are laid out. */
    private fun farReady(doc: EpubDocument, reader: Int) = (0 until doc.chapterCount).filter { abs(it - reader) > 1 && doc.isChapterReady(it) }

    /** Pumps frames for [millis] of real time, running [each] before every frame. */
    private fun pumpFor(driver: SceneTestDriver, millis: Long, each: (Int) -> Unit = {}) {
        val until = System.nanoTime() + millis * 1_000_000
        var frame = 0
        while (System.nanoTime() < until) {
            each(frame++)
            driver.pumpFrames(1)
            Thread.sleep(15)
        }
    }

    @Test
    fun chapters_away_from_the_reader_wait_while_the_zoom_changes() = forBothEffectOrders { queued ->
        val doc = book()
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 5)).also {
                it.layoutWaitsForRest = true
                it.restMillis = 600
            }
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { doc.isChapterReady(4) && doc.isChapterReady(5) && doc.isChapterReady(6) }
            // A new zoom every 100 ms, for far longer than the rest time, which is long enough that a slow frame does not look like a rest. The frames between two
            // changes are still, so only a rest measured over the whole rest time sees the motion.
            var zoomedAt = 0L
            var step = 0
            pumpFor(driver, 2000) {
                if (System.nanoTime() - zoomedAt > 100_000_000L) {
                    onTestUiThread { state.setZoom(1.2f + step++ * 0.01f) }
                    zoomedAt = System.nanoTime()
                }
            }
            assertEquals(emptyList(), farReady(doc, state.currentLocation.chapter), "chapters away from the reader laid out while the view moved")
            driver.pumpUntilState { doc.isComplete }
        }
    }

    @Test
    fun chapters_away_from_the_reader_wait_while_a_finger_holds_the_scroll() = forBothEffectOrders { queued ->
        val doc = book()
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 5)).also {
                it.layoutWaitsForRest = true
                it.restMillis = 300
            }
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { doc.isChapterReady(4) && doc.isChapterReady(5) && doc.isChapterReady(6) && state.openAt == null }
            // Drag a little, then hold the finger still: the scroll stays in progress, and nothing moves.
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 150f), type = PointerType.Touch)
            for (step in 1..6) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 150f - 6f * step), type = PointerType.Touch)
                driver.pumpFrames(1)
            }
            assertTrue(state.adapter?.isScrollInProgress == true, "the drag should hold the scroll")
            pumpFor(driver, 1200)
            assertEquals(emptyList(), farReady(doc, state.currentLocation.chapter), "chapters away from the reader laid out under a held scroll")
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 114f), type = PointerType.Touch)
            driver.pumpUntilState { doc.isComplete }
        }
    }
}
