package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

/** A pager page that comes back is a hit in the page-bitmap cache, not a new render (#660). */
class PagerCacheSceneTest {

    private class Square : KitePage {
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            val square = KitePath.Builder().apply { rectangle(10.0, 10.0, 80.0, 80.0) }.build()
            canvas.fillPath(square, deviceCtm.concat(displayToDeviceBase()), RgbColor(1.0, 0.0, 0.0), evenOdd = false)
            canvas.endPage()
        }
    }

    private class Pages(count: Int) : KiteDocument {
        override val pageCount: Int = count
        override val pages: List<KitePage> = List(count) { Square() }
    }

    @Test
    fun a_page_that_comes_back_is_not_rendered_again() {
        for (layout in listOf<KiteDocLayout>(KiteDocLayout.Paged(offscreenPages = 0), KiteDocLayout.Spread())) {
            forBothEffectOrders { queued ->
                val state = KiteDocViewState(Pages(8))
                val renders = ConcurrentHashMap<Int, Int>()
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(
                        state = state, modifier = Modifier.fillMaxSize(), layout = layout,
                        onPageRendered = { i, _ -> renders.merge(i, 1, Int::plus) },
                    )
                }
                scene.use {
                    driver.pumpUntilState { renders.containsKey(0) }
                    driver.runOnUi { state.scrollToPage(5) }
                    driver.pumpUntilState { renders.containsKey(5) }
                    driver.pumpFrames(10)
                    driver.runOnUi { state.scrollToPage(0) }
                    driver.pumpUntilState { state.currentPage == 0 }
                    driver.pumpFrames(60)
                    assertEquals(1, renders[0], "${layout::class.simpleName}: page 0 rendered ${renders[0]} times, $renders")
                }
            }
        }
    }
}
