package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A page raster that lands while the report effect for the old value starts is
 * reported once, not twice (#229). The raster thread can finish in that window when
 * the machine is busy. Here an observer remembered before the effect writes the
 * raster in the same apply, so the window opens every time.
 */
class FreshRasterReportSceneTest {

    /** Lands the raster when it is remembered, before the effect after it starts. */
    private class LandOnRemember(private val land: () -> Unit) : RememberObserver {
        override fun onRemembered() = land()
        override fun onForgotten() {}
        override fun onAbandoned() {}
    }

    @Test
    fun a_raster_that_lands_as_its_report_starts_is_reported_once() {
        val bitmap = ImageBitmap(1, 1)
        val raster = mutableStateOf<Pair<ImageBitmap, Boolean>?>(null)
        var reports = 0
        ImageComposeScene(width = 10, height = 10) {
            val rastered by raster
            remember { LandOnRemember { raster.value = bitmap to true } }
            ReportFreshRaster(rastered) { reports++ }
        }.use { scene -> repeat(5) { scene.render(it * 16_000_000L) } }
        assertEquals(1, reports)
    }
}
