package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A viewer whose size changes on every frame, as in a window drag or an animated layout, renders
 * each page once the size holds, not once per frame at sizes never used again (#390).
 */
class RasterResizeSceneTest {

    /** A red 100 x 100 page that counts its renders. */
    private class CountingPage : KitePage {
        val renders = AtomicInteger()
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            renders.incrementAndGet()
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            val square = KitePath.Builder().apply { rectangle(0.0, 0.0, displayWidth, displayHeight) }.build()
            canvas.fillPath(square, deviceCtm.concat(displayToDeviceBase()), RgbColor(1.0, 0.0, 0.0), evenOdd = false)
            canvas.endPage()
        }
    }

    private class OnePage(page: KitePage) : KiteDocument {
        override val pageCount: Int = 1
        override val pages: List<KitePage> = listOf(page)
    }

    private fun red(pixels: PixelMap, x: Int, y: Int) = pixels[x, y].let { it.red > 0.8f && it.green < 0.3f && it.blue < 0.3f }

    @Test
    fun a_resize_renders_the_page_once_it_holds() {
        forBothEffectOrders { queued ->
            val page = CountingPage()
            // One document for the whole test: a new one per frame would be a new viewer per frame.
            val document = OnePage(page)
            var width by mutableStateOf(200)
            val (scene, driver) = drivenScene(400, 400, queued) {
                Box(Modifier.size(width.dp, 400.dp)) {
                    KiteDocView(
                        state = rememberKiteDocViewState(document),
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.SinglePage(0),
                    )
                }
            }
            scene.use {
                driver.pumpUntil { red(it, 100, 200) }
                val before = page.renders.get()
                // Thirty sizes, one frame each.
                for (w in 201..230) {
                    width = w
                    driver.pumpFrames(1)
                }
                // Once the size holds, the page renders at it.
                driver.pumpUntil { red(it, 225, 200) && page.renders.get() > before }
                driver.pumpFrames(20)
                val during = page.renders.get() - before
                assertTrue(during <= 2, "$during renders for one resize")
            }
        }
    }
}
