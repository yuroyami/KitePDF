package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Inputs at the edge of what the viewer can use: pages far longer than wide (#332), and zoom and
 * pan values that are not finite, and spec values that cannot work (#338).
 */
class ViewerInputLimitsSceneTest {

    /** A PDF of pages of the given sizes, each filled red. */
    private fun pdf(vararg sizes: Pair<Double, Double>): PdfDocument {
        val builder = PdfBuilder()
        for ((width, height) in sizes) {
            builder.page(width = width, height = height) {
                setFillRgb(1.0, 0.0, 0.0)
                rectangle(0.0, 0.0, width, height)
                fill()
            }
        }
        return PdfDocument.open(builder.build())
    }

    /** The two cases the issue measured: a long second page reached by a jump, and a long first page. */
    @Test
    fun a_page_far_taller_than_wide_lays_out_in_the_strip() = withoutEscapes {
        val doc = pdf(400.0 to 600.0, 10.0 to 14_400.0)
        lateinit var state: KiteDocViewState
        var jump by mutableStateOf(false)
        val (scene, driver) = drivenScene(400, 600, queued = false) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
            if (jump) LaunchedEffect(Unit) { state.scrollToPage(1) }
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            jump = true
            driver.pumpUntilState { state.currentPage == 1 }
            driver.pumpFrames(4)
        }

        val single = pdf(50.0 to 14_400.0)
        lateinit var alone: KiteDocViewState
        val (big, bigDriver) = drivenScene(1080, 1920, queued = false) {
            alone = rememberKiteDocViewState(single)
            KiteDocView(state = alone, modifier = Modifier.fillMaxSize())
        }
        big.use { bigDriver.pumpUntilState { alone.pageGeometry.isNotEmpty() } }
    }

    @Test
    fun a_zoom_that_is_not_a_number_is_ignored() = forBothEffectOrders { queued -> withoutEscapes {
        val doc = pdf(200.0 to 200.0)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            onTestUiThread { state.setZoom(Float.NaN) }
            assertEquals(1f, state.zoom)
            // Past the 220 ms settle, where a NaN zoom used to reach the raster size.
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 400) driver.pumpFrames(0)
            onTestUiThread { state.setZoom(Float.POSITIVE_INFINITY, focal = Offset(Float.NaN, 3f)) }
            assertEquals(1f, state.zoom)
        }
    } }

    @Test
    fun animation_and_pan_ignore_input_that_is_not_finite() = runBlocking {
        val state = KiteDocViewState(pdf(200.0 to 200.0))
        state.viewportSize = IntSize(200, 200)
        onTestUiThread { state.setZoom(2f) }
        state.animateZoomTo(Float.NaN)
        assertEquals(2f, state.zoom)
        assertEquals(Offset.Zero, onTestUiThread { state.panBy(Offset(Float.NaN, 5f)) })
        assertEquals(Offset.Zero, state.panOffset)
        onTestUiThread { state.setZoom(2f, focal = Offset(Float.POSITIVE_INFINITY, 0f)) }
        assertEquals(Offset.Zero, state.panOffset, "a focal point that is not finite zooms around the centre")
    }

    @Test
    fun specs_refuse_values_the_viewer_cannot_use() {
        assertFailsWith<IllegalArgumentException> { KiteZoomSpec(doubleTapZoom = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { KiteZoomSpec(doubleTapZoom = 0f) }
        assertFailsWith<IllegalArgumentException> { KiteDocLayout.Paged(offscreenPages = -1) }
        assertFailsWith<IllegalArgumentException> { KiteDocLayout.Spread(offscreenPages = -1) }
        // A double tap past the range is clamped, as every zoom change is.
        KiteZoomSpec(maxZoom = 4f, doubleTapZoom = 10f)
    }
}
