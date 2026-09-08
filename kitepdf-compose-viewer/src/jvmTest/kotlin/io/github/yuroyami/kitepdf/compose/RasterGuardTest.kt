package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The composable call sites' failure guard. An escaped throwable walks out of
 * `produceState` and aborts the host app, so every failure that is not a
 * cancellation must come back as null, errors included (#219).
 */
class RasterGuardTest {

    private class ExplodingPage(private val failure: Throwable) : KitePage {
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) { throw failure }
    }

    private fun rasterizer(): KitePageRasterizer {
        val density = Density(1f)
        return KitePageRasterizer(
            density, LayoutDirection.Ltr,
            TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr),
        )
    }

    @Test
    fun an_error_thrown_while_rendering_reports_null_instead_of_escaping() = runBlocking {
        val result = rasterizer().rasterizeCachedOrNull(
            cache = null, page = ExplodingPage(OutOfMemoryError("synthetic")),
            widthPx = 50, heightPx = 50, background = Color.White, hairlineWidthPx = 1f, theme = null, pageIndex = 0,
        )
        assertNull(result, "an Error must be swallowed the way an Exception is")
    }

    @Test
    fun an_exception_thrown_while_rendering_reports_null() = runBlocking {
        val result = rasterizer().rasterizeCachedOrNull(
            cache = null, page = ExplodingPage(IllegalStateException("torn page")),
            widthPx = 50, heightPx = 50, background = Color.White, hairlineWidthPx = 1f, theme = null, pageIndex = 0,
        )
        assertNull(result)
    }

    @Test
    fun cancellation_still_propagates() {
        assertFailsWith<CancellationException> {
            runBlocking {
                rasterizer().rasterizeCachedOrNull(
                    cache = null, page = ExplodingPage(CancellationException("stop")),
                    widthPx = 50, heightPx = 50, background = Color.White, hairlineWidthPx = 1f, theme = null, pageIndex = 0,
                )
            }
        }
    }
}
