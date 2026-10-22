package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** A thumbnail that scrolls out of the strip and back is a cache lookup, not a new raster (#391). */
class ThumbnailCacheSceneTest {

    /** Red square pages that count their renders together. */
    private class Pages(count: Int) : KiteDocument {
        val renders = AtomicInteger()
        override val pageCount: Int = count
        override val pages: List<KitePage> = List(count) { Square(renders) }
    }

    private class Square(private val renders: AtomicInteger) : KitePage {
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

    @Test
    fun a_thumbnail_that_comes_back_is_not_rendered_again() {
        forBothEffectOrders { queued ->
            val doc = Pages(6)
            val state = KiteDocViewState(doc)
            // The strip shows about four thumbnails when wide, and one when narrow.
            var width by mutableStateOf(400)
            val (scene, driver) = drivenScene(400, 100, queued) {
                Box(Modifier.width(width.dp).height(100.dp)) {
                    KiteThumbnailStrip(state = state)
                }
            }
            scene.use {
                driver.pumpFrames(5)
                driver.pumpUntilState { doc.renders.get() >= 4 }
                driver.pumpFrames(30)
                val shown = doc.renders.get()
                width = 100
                driver.pumpFrames(30)
                width = 400
                driver.pumpFrames(60)
                assertEquals(shown, doc.renders.get(), "the thumbnails that came back were rendered again")
            }
        }
    }
}
