package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/** A page slot on screen asks for its raster before a slot composed ahead of it, in every layout (#370). */
class RasterPrioritySceneTest {

    private fun pdf(pages: Int): PdfDocument =
        PdfDocument.open(PdfBuilder().apply { repeat(pages) { page(width = 200.0, height = 200.0) {} } }.build())

    @Test
    fun a_page_on_screen_rasters_before_a_page_off_screen() = withoutEscapes {
        for (layout in listOf(KiteDocLayout.Continuous(), KiteDocLayout.Paged(), KiteDocLayout.Spread())) forBothEffectOrders { queued ->
            val state = KiteDocViewState(pdf(8), initialPage = 3)
            val (scene, driver) = drivenScene(200, 200, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
            }
            scene.use {
                driver.pumpUntilState { state.adapter != null && state.currentPage == 3 }
                driver.pumpFrames(2)
                val name = layout::class.simpleName
                assertEquals(RasterPriority.VISIBLE, rasterPriorityOf(state, 3), "$name: the page on screen")
                assertEquals(RasterPriority.NEAR, rasterPriorityOf(state, 4), "$name: the page after it, off screen")
                assertEquals(RasterPriority.NEAR, rasterPriorityOf(state, 7), "$name: a page far off screen")
            }
        }
    }
}
