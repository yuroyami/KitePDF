package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/** A chapter placeholder in a pager takes the same taps and zoom gestures as a page (#409). */
class GapGesturesSceneTest {

    /** A book of two square-page chapters, the second held so its slot stays a placeholder. */
    private fun heldBook(): LatchedDocument {
        val book = EpubDocument.open(
            multiSpineEpub(listOf("<p>One.</p>", "<p>Two.</p>")),
            EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
        )
        book.prepareChapter(0)
        return LatchedDocument(book, held = 1)
    }

    private fun redAtCentre(scene: ImageComposeScene, driver: SceneTestDriver) {
        driver.pumpUntil { pixels ->
            val c = pixels[100, 100]
            c.red > 0.8f && c.green < 0.3f && c.blue < 0.3f
        }
    }

    private fun tap(scene: ImageComposeScene, at: Long) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 100f), timeMillis = at, type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 100f), timeMillis = at + 50, type = PointerType.Touch)
    }

    @Test
    fun a_tap_on_a_placeholder_reaches_the_host() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.SinglePage(1))) {
            forBothEffectOrders { queued ->
                val doc = heldBook()
                val taps = AtomicInteger()
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(
                        state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 1, charOffset = 0)),
                        modifier = Modifier.fillMaxSize(),
                        layout = layout,
                        zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                        chapterPlaceholder = { Box(Modifier.size(20.dp).background(Color.Red)) },
                        onTap = { taps.incrementAndGet() },
                    )
                }
                scene.use {
                    redAtCentre(scene, driver)
                    tap(scene, at = 1_000)
                    try {
                        driver.pumpUntilState { taps.get() == 1 }
                    } catch (failure: AssertionError) {
                        throw AssertionError("${layout::class.simpleName}: the placeholder ate the tap", failure)
                    }
                }
            }
        }
    }

    @Test
    fun a_double_tap_on_a_placeholder_zooms() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.SinglePage(1))) {
            forBothEffectOrders { queued ->
                val doc = heldBook()
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(200, 200, queued) {
                    state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 1, charOffset = 0))
                    KiteDocView(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        layout = layout,
                        chapterPlaceholder = { Box(Modifier.size(20.dp).background(Color.Red)) },
                    )
                }
                scene.use {
                    redAtCentre(scene, driver)
                    tap(scene, at = 1_000)
                    driver.pumpFrames(1)
                    tap(scene, at = 1_150)
                    try {
                        driver.pumpUntilState { state.zoom > 1f }
                    } catch (failure: AssertionError) {
                        throw AssertionError("${layout::class.simpleName}: the placeholder ignored the double tap", failure)
                    }
                }
            }
        }
    }
}
