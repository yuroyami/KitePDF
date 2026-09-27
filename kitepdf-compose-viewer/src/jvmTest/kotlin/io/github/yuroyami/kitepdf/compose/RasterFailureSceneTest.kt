package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A raster that fails keeps the page's last good bitmap, the state says the page failed, and a
 * retry renders it again (#430).
 */
class RasterFailureSceneTest {

    private class OnePage(page: KitePage) : KiteDocument {
        override val pageCount: Int = 1
        override val pages: List<KitePage> = listOf(page)
    }

    /** A red 100 x 100 page that throws when drawn wider than 1,000 device pixels, until [allowWide]. */
    private class WideFailingPage : KitePage {
        @Volatile var allowWide = false
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            if (!allowWide && displayWidth * deviceCtm.a > 1000.0) throw OutOfMemoryError("a raster too large for this device")
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            val square = KitePath.Builder().apply { rectangle(0.0, 0.0, displayWidth, displayHeight) }.build()
            canvas.fillPath(square, deviceCtm.concat(displayToDeviceBase()), RgbColor(1.0, 0.0, 0.0), evenOdd = false)
            canvas.endPage()
        }
    }

    private fun red(pixels: PixelMap) = pixels[200, 200].let { it.red > 0.8f && it.green < 0.3f && it.blue < 0.3f }

    /** A red 100 x 100 page that throws on every draw once [failing] is set. */
    private class FlakyPage : KitePage {
        @Volatile var failing = false
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            if (failing) throw OutOfMemoryError("no memory for a thumbnail")
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            val square = KitePath.Builder().apply { rectangle(0.0, 0.0, displayWidth, displayHeight) }.build()
            canvas.fillPath(square, deviceCtm.concat(displayToDeviceBase()), RgbColor(1.0, 0.0, 0.0), evenOdd = false)
            canvas.endPage()
        }
    }

    @Test
    fun a_thumbnail_that_fails_to_render_again_keeps_the_old_one() {
        forBothEffectOrders { queued ->
            val page = FlakyPage()
            var background by mutableStateOf(Color.White)
            val (scene, driver) = drivenScene(200, 100, queued) {
                val state = rememberKiteDocViewState(OnePage(page))
                KiteThumbnailStrip(state = state, modifier = Modifier.fillMaxSize(), pageBackground = background)
            }
            scene.use {
                // The first thumbnail is 72 x 72 after 8 px of padding: its centre is (44, 44).
                fun thumbnailRed(pixels: PixelMap) = pixels[44, 44].let { it.red > 0.8f && it.green < 0.3f && it.blue < 0.3f }
                driver.pumpUntil { thumbnailRed(it) }
                page.failing = true
                background = Color.Yellow
                driver.pumpFrames(30)
                driver.pumpUntil { thumbnailRed(it) }
            }
        }
    }

    @Test
    fun a_failed_upgrade_keeps_the_page_and_a_retry_renders_it() {
        forBothEffectOrders { queued ->
            val page = WideFailingPage()
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(400, 400, queued) {
                state = rememberKiteDocViewState(OnePage(page))
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
            }
            scene.use {
                driver.pumpUntil { red(it) }
                driver.pumpUntilState { state.pageRenderState(0) == KitePageRenderState.Ready }
                // A crisp-zoom raster of 1,600 px, which the page cannot draw.
                state.setZoom(4f)
                driver.pumpUntilState { state.pageRenderState(0) == KitePageRenderState.Failed }
                driver.pumpFrames(30)
                driver.pumpUntil { red(it) }
                assertEquals(KitePageRenderState.Failed, state.pageRenderState(0))

                page.allowWide = true
                state.retryPage(0)
                driver.pumpUntilState { state.pageRenderState(0) == KitePageRenderState.Ready }
                driver.pumpUntil { red(it) }
            }
        }
    }
}
