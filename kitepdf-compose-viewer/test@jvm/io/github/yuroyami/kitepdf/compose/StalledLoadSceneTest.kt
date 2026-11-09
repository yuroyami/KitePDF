package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A Vectorized page whose content does not come back, because its load throws or the budget drops
 * it at once, stops loading it until its slot comes on screen or leaves it, instead of loading it
 * once a frame (#679).
 */
class StalledLoadSceneTest {

    /** A page whose content is never in memory: its load throws when [throws], else it does nothing. */
    private class Stalled(private val throws: Boolean) : KitePage {
        val loads = AtomicInteger()
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override val isContentLoaded: Boolean get() = false
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun loadContent() {
            loads.incrementAndGet()
            if (throws) error("the layout failed")
        }
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) = Unit
    }

    private class OnePage(page: KitePage) : KiteDocument {
        override val pageCount: Int = 1
        override val pages: List<KitePage> = listOf(page)
    }

    @Test
    fun a_page_that_does_not_load_is_not_loaded_again_every_frame() {
        for (throws in listOf(true, false)) {
            forBothEffectOrders { queued ->
                val page = Stalled(throws)
                val state = KiteDocViewState(OnePage(page))
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), renderSpec = KiteRenderSpec.Vectorized())
                }
                scene.use {
                    driver.pumpUntilState { page.loads.get() >= 1 }
                    repeat(60) {
                        driver.pumpFrames(1)
                        Thread.sleep(5)
                    }
                    assertTrue(page.loads.get() <= 3, "throws $throws: the page loaded ${page.loads.get()} times in 60 frames")
                }
            }
        }
    }
}
